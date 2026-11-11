# Remote Desktop Protocol

All communication runs over a single WebSocket connection per device to the relay
server (`/ws`). Messages are either **JSON text frames** (control) or **binary
frames** (video / file data). The server relays messages between devices that
share the same session and never inspects payloads beyond routing. All peer
content is **end-to-end encrypted** (see [End-to-end encryption](#end-to-end-encryption));
the relay only ever sees ciphertext plus minimal routing metadata.

## Pairing model

- Every device is configured once with `{ server URL, session key, device name }`.
- The session key is a long random string (recommend 32+ chars) and is the only
  secret. The **raw key is never sent to the server.** Each device derives, via
  HKDF-SHA256:
  - a **pairing id** — sent to the server (as `hello.session`) to join a session;
  - an **encryption key** — never leaves the device.
  Any devices deriving the same pairing id are joined into one session; only
  devices holding the same raw key can decrypt the traffic. A mismatched key
  therefore simply fails to pair.
- The session key alone does **not** grant control. It is the rendezvous +
  encryption secret; actually viewing, controlling or browsing a device
  additionally requires that device to have **approved the peer's device
  identity** once (see [Device authentication](#device-authentication)).
- The server rate-limits join attempts per IP so the pairing id cannot be
  brute-forced by spamming (see below).

## JSON messages

Every JSON message has a `type`. Messages forwarded between peers get a `from`
field (peer id) added by the server. A message may include `to: <peerId>` to
route to one peer; otherwise it is broadcast to all other peers in the session.

### Client → Server (handled by server)

| type    | fields                                   | notes |
|---------|------------------------------------------|-------|
| `hello` | `session` (the derived **pairing id**, not the raw key), `device` (`windows`\|`android`), `name`, `uid`?, `version`? | First message after connect. Cleartext. `uid` is a stable random per-install id; when a device reconnects with the same `uid`, the server kicks its stale connection from the session (and broadcasts `peer-left`) so the device is never listed twice. `version` is the app's version, e.g. `1.0.53`; the server passes it on in peer lists so each device can tell when another is behind. |

### Server → Client

| type          | fields                                | notes |
|---------------|----------------------------------------|-------|
| `welcome`     | `id` (your peer id), `peers` (array of `{id, device, name, version?}`), `binaryTo`?, `latest`? | Reply to `hello`. `binaryTo: true` means this relay delivers [addressed binary frames](#addressed-binary-frames); older relays omit it. `latest` is the newest released app version (see [Updates](#updates)); left out until the relay knows it. A peer's `version` is missing for apps from before it was sent. |
| `peer-joined` | `peer` `{id, device, name, version?}`  | |
| `latest`      | `version`                              | A new release is out. Sent to every connected device when the relay first sees it. A device trusts only the relay's own message, which has no `from`. |
| `peer-left`   | `id`                                   | |
| `error`       | `code`, `message`, `retryAfter`?       | e.g. `rate-limited`, `banned`, `too-many-connections`, `bad-hello`. Connection is closed after. A ban carries `retryAfter`, the seconds until the relay accepts this IP again. |

### Updates

The relay polls GitHub's latest release every 10 minutes (`RELEASES_URL`,
`RELEASE_CHECK_MS`; `RELEASES_URL=""` turns it off) and names it in `welcome`
and `latest` only once the release has both the Windows exe and the Android APK
uploaded. It never announces its own version: pushing `main` deploys the relay
before the tag build has published the apps, and a failed build publishes
nothing. Drafts and prereleases are ignored. Versions compare numerically
(`1.0.10` > `1.0.9`), and a prerelease comes before its release.

- **Windows** downloads the release's exe beside the running one as `.new`. It
  must match the release's `SHA256SUMS.txt`, report that version, and carry a
  valid Authenticode signature from the same publisher as the running exe (the
  certificate itself renews every few days). The relay only names a version, so
  it can't hand a PC a program. Once nobody has used the app for 3 minutes (no
  viewer, no transfer, no viewer or file window open, no approval waiting, none
  of its windows in front), the running exe is renamed to `.old`, the new one
  takes its name and starts with `--restart --updated`, and the old one exits.
  The new copy deletes `.old`. Unsigned development builds never update.
- **Android** posts one notification per version that opens the APK download in
  the browser, and Settings → App version offers the same. Installs from Google
  Play, and builds that aren't tagged releases, aren't told.

The relay's status page (`/`) shows its own version and the newest app release.

## Device authentication

Every install generates a persistent **EC P-256 keypair** on first run (Android:
AndroidKeyStore, non-exportable; Windows: PKCS#8 encrypted with DPAPI next to
the settings file). The SHA-256 hash of the DER `SubjectPublicKeyInfo` is the
device's **fingerprint**; its first 8 hex digits, uppercased and grouped
(`A3F2-9C41`), are shown to users as the **device code**.

Each device keeps a local **trust store** of approved fingerprints, scoped to
the current pairing id — changing the session key wipes it. Trust decisions are
made entirely on-device; the relay stores nothing and cannot vouch for anyone.

**Handshake.** On `welcome` (for every listed peer) and on `peer-joined`, each
device challenges the other, so trust is established mutually within one round
trip of joining:

| type             | fields | notes |
|------------------|--------|-------|
| `auth-challenge` | `to`, `nonce` | `nonce` = base64 of 32 random bytes, single-use. |
| `auth-response`  | `to`, `nonce` (echoed), `pub` (base64 SPKI), `sig` (base64) | `sig` = ECDSA-SHA256 (DER, RFC 3279) over `"remote-desktop/auth-v1" ‖ nonce ‖ pub ‖ utf8(pairId)`. |
| `auth-result`    | `to`, `ok`, `status` | Trust state toward the recipient: `trusted`, `pending` (approval prompt showing), `denied`, `revoked`, `disconnected`. Informational — enforcement is the sender dropping messages. |

The challenger verifies the signature against the presented key (the nonce
prevents replay; the pairId binds it to this session), computes the
fingerprint, and:

- **known fingerprint** → mark the peer trusted for this connection, update
  `lastSeen`, send `auth-result ok:true status:trusted`. No user interaction —
  an approved device never re-prompts unless revoked or the session key changes.
- **unknown fingerprint** → show an approve/deny prompt naming the peer and its
  device code (users should compare codes out-of-band), send `status:pending`,
  then `trusted` or `denied` once the user decides. Denials are remembered for
  the rest of the process so a hostile peer cannot spam prompts by reconnecting.

**Enforcement.** Messages that view, control, or touch files or the clipboard
(`start-view`, `request-keyframe`, `privacy-mode`, `mouse`, `scroll`, `key`, `text`, `cad`, `tap`, `swipe`,
`touch`, `pinch`, `back`, `homebtn`, `recents`, `notifications`, `quick-settings`, `screenshot`, `lock`, `volume`,
`rotate`, `get-phone-state`, `request-smooth`, `stop-smooth`, `fs-*`, `clip-get`, `clip-set`) are dropped unless the
sending peer is trusted. A phone also drops input, `volume`, `rotate`, `get-phone-state`, `request-smooth`, `stop-smooth`, `start-view` and `fs-*` from
another phone (a peer whose `device` is `android`) unless its *Allow other
phones* switch is on, which it is by default; an `fs-list` or `fs-get` it refuses
that way says so in its `error`. The switch goes by the type each device
declares to the relay: it is a consent step, not a defence against a modified
app on an approved phone. A phone takes `frame-ack`, `request-smooth` and
`stop-smooth`, and answers `diagnostic-ping`, only from a device that is
viewing it at that moment: one whose `start-view` it accepted and that has not
stopped since. From anyone else they are dropped without an answer. Because every key holder can decrypt binary frames,
and older relays **broadcast them** to the whole session, a host additionally
sends no video frames and serves no file transfers while *any* unapproved peer
is present in the session (the stream resumes automatically once the peer is
approved or leaves; a keyframe covers the gap).

**Revocation.** Each device shows its trusted-device list with per-device
*Revoke* (delete the fingerprint — the device must be re-approved on its next
connect) and *Disconnect* (courtesy `auth-result status:disconnected`; viewers
close their windows, trust is kept). The relay cannot kick peers, so both are
enforced by the host ignoring the peer, which is the actual security boundary.

A peer that never answers the challenge (e.g. an older app version) simply
stays untrusted: it can sit in the session but sees no frames and injects
nothing.

### Peer ↔ Peer (end-to-end encrypted)

The messages below are the **inner, decrypted** messages. On the wire each one is
carried inside an `enc` envelope (see [End-to-end encryption](#end-to-end-encryption)):
the sender encrypts the inner JSON and lifts its `to` (if any) into the cleartext
envelope so the relay can still route it; the receiver decrypts and sees the
server-added `from`. Broadcast (no `to`) still goes to all other peers.

**Viewing**

| type          | fields | notes |
|---------------|--------|-------|
| `start-view`  | `to`, `autoUnprivacy`? (boolean), `codecs`? (array) | Ask peer to start streaming its screen to me. Windows records `autoUnprivacy: true` for an approved viewer and restores the physical desktop when that viewing session ends (stop-view, peer/relay disconnect, or loss of approval). Defaults to false; each start-view replaces that viewer's policy. Older hosts ignore it. `codecs` is for a phone: the formats the viewer can draw inside type `5` frames, so far only `"jpeg"`. Sending it also promises `frame-ack`. See [Phone frames](#phone-frames). PC hosts ignore it. |
| `stop-view`   | `to`   | Stop streaming. |
| `privacy-mode` | `to`, `enabled` (boolean) | Windows only: hide physical monitors and block local keyboard/mouse while preserving app-injected remote input. Enabling requires remote-control consent, an approved active viewer, and all peers trusted. Disabling requires remote-control consent and an approved peer. Privacy stays active after viewing stops or peers/relay disconnect unless a departing viewer opted into autoUnprivacy; reconnecting approved viewers receive the current state. |
| `get-privacy-state` | `to` | Approved peer asks Windows for privacy availability and current state. |
| `privacy-state` | `to`, `enabled`, `supported`, `allowed`, `error?` | Windows acknowledges a toggle and announces local unlock or automatic cleanup. Viewers must use this state rather than optimistically toggling. Older hosts may omit it. |
| `screen-info` | `width`, `height`, `desktopWidth`?, `desktopHeight`?, `typing`?, `stream`?, `codec`?, `capture`?, `paused`?, `smooth`? | `width`/`height` are the pixel size of the streamed image. `desktopWidth`/`desktopHeight` are the size of the whole desktop, which is the coordinate space `view-region` and screen-patch region rects are expressed in; absent from older hosts, where the streamed image *is* the whole desktop. A phone sets `typing: true` when it can type `text` and `key` into its text fields; older apps omit it. Sent when streaming starts and whenever the geometry changes. A phone sends it to each viewer by itself (older phone apps broadcast it), and tells a viewer that sent `codecs` how its pictures are coming in `stream`, `codec`, `capture` and `paused`: see [Phone frames](#phone-frames). To the same viewers it says in `smooth` whether smooth sharing is `on`, or on and not to be stopped by a viewer (`only`), or can be asked for (`available`), or cannot (`unavailable`): see [Smooth sharing on request](#smooth-sharing-on-request). |
| `view-region` | `to`, `x`, `y`, `w`, `h`, `outW`, `outH`, `h264`? | Viewer tells the host which part of the desktop it can actually show (normalized 0..1) and how many pixels it is worth sending that region at. `h264: true` additionally advertises that the viewer can decode binary frame type `4`. See [Viewport streaming](#viewport-streaming). |
| `request-keyframe` | `to` | Viewer asks the streaming host for a full frame (sent when a sequence gap is detected in dirty-rect patches, throttled to one per ~2 s). |
| `diagnostic-ping` | `to`, `nonce` | Authenticated viewer RTT probe. A trusted host echoes the nonce in `diagnostic-pong`. A phone answers only a device that is viewing it; phone apps up to v1.0.53 never answer. The PC's phone window sends one every two seconds while its Stats readout is open, and works out from the answers how old each picture is when it is drawn: the phone's `hostTimeMs` less the time the ping was sent is the difference between the two clocks plus the ping's trip, and the smallest such figure of the last minute, less half the shortest round trip, is taken as the difference. |
| `diagnostic-pong` | `to`, `nonce`, `hostQueueBytes`, `targetFps`, `jpegQuality`, `maxWidth` | From a PC: RTT response plus the desktop sender's queued binary backlog. The three stream fields report what the host has **adapted to**, not what the user configured, so they move during a session. |
| `diagnostic-pong` | `to`, `nonce`, `hostTimeMs`, `hostQueueBytes`, `codec`, `capture`, `fps` | From a phone. `hostTimeMs` is the phone's uptime clock in milliseconds, read as the ping is handled; `captureMs` in a type `5` frame is the low 32 bits of the same clock, so a viewer can work out how old a picture is. `hostQueueBytes` is what waits in the phone's own send queue, which is not all that waits (the system buffers more below it). `codec` (`jpeg`) and `capture` (`accessibility` or `screen-share`) are as in `screen-info`. `fps` is how many pictures the phone sent in the last second. |
| `frame-ack` | `to`, `stream`, `seq` | Viewer to phone: the highest `seq` that has arrived of the type `5` stream `stream`. Sent as frames arrive, before they are decoded, at most one per 30 ms; a frame that arrives sooner than that after the last one is acknowledged when the 30 ms are up, so the last frame of a burst is never left out. Every viewer that sends `codecs` sends these, for every frame of the stream including one it does not draw. A phone takes them only from a device that is viewing it, and only for the stream it is sending. It holds the next picture back by them: see [Phone frames](#phone-frames). |

**Input — controlling Windows** (coordinates normalized 0..1 over the stitched
virtual desktop). The controller may be a phone or another PC — the host injects
these identically either way, so Windows↔Windows control needs no new messages;
the viewer simply decodes the type-3 stream (below) and sends these.

| type     | fields | notes |
|----------|--------|-------|
| `mouse`  | `action` (`move`\|`down`\|`up`), `button` (`left`\|`right`\|`middle`), `x`, `y` | |
| `scroll` | `dx`, `dy` | Wheel notches; positive `dy` scrolls up. |
| `key`    | `action` (`down`\|`up`\|`press`), `code` | Key names: `A`-`Z`, `0`-`9`, `F1`-`F12`, `ENTER`, `ESC`, `TAB`, `SPACE`, `BACKSPACE`, `DELETE`, `INSERT`, `HOME`, `END`, `PGUP`, `PGDN`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `WIN`, `CTRL`, `ALT`, `SHIFT`, `CAPS`, `PRINTSCREEN`, or a single character. |
| `text`   | `text` | Type a unicode string (used for normal typing without modifiers). |
| `cad`    | – | Request Ctrl+Alt+Del. The real secure attention sequence can't be injected by normal apps, so the host calls `SendSAS` where policy allows it and opens Task Manager otherwise. |

**Input — controlling Android** (coordinates normalized 0..1 over the phone screen).
The controller may be a PC or another phone.

| type    | fields | notes |
|---------|--------|-------|
| `tap`   | `x`, `y`, `onlyIfLocked`? | A tap at that point. With `onlyIfLocked: true` the phone makes the tap only if its lock screen or unlock screen is up at the moment it would be made, and drops it otherwise. A viewer sets it on the taps it makes for the keys of a PIN `entry` (see `lock-state`): the phone may have unlocked since the viewer was told where the keys are, and that place is then something on the home screen or in an app. Phones before it ignore the field and tap. |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `ms` | Drag gesture over `ms` milliseconds. |
| `touch` | `action` (`down`\|`move`\|`up`), `x`, `y` | Streamed pointer for real-time press / long-press / drag. The phone plays it as one continued accessibility stroke: the finger stays down from `down` until `up`. Senders should throttle `move` to ~30 ms. |
| `pinch` | `x`, `y`, `dir` (`in`\|`out`), `scale`? | Two-finger pinch centered at `x`,`y`. Without `scale`, a fixed step (one mouse-wheel notch). With it, the end finger spread divided by the start spread, from another phone's own pinch; the phone starts the fingers at least the system's minimum scaling span apart (about 27 mm), since a pinch that starts closer barely zooms, and fits the gesture to the screen. |
| `back` / `homebtn` / `recents` | – | Global navigation actions. |
| `notifications` | – | Opens the notification shade; `back` closes it. |
| `quick-settings` | – | Opens quick settings; `back` closes it. A Samsung phone shows its notifications at the first `back` and closes them at the second. |
| `screenshot` | – | The phone takes a screenshot and saves it where it saves its own. Android 9 or later. A PC is then offered the file like any other capture (`fs-offer`, see [New captures](#new-captures)). |
| `lock` | – | Locks the phone and turns its screen off. Android 9 or later. |
| `volume` | `level`? (integer), `mute`? (boolean) | Sets the media volume to `level`, from 0 to `volumeMax`, and mutes or unmutes it. Either field may be left out; a `level` out of range is brought into it. The phone shows its own volume panel, as when its volume keys are pressed, and answers with `phone-state`. |
| `rotate` | `orientation` (`auto` \| `portrait` \| `landscape`) | Holds the phone's screen upright or on its side, whichever way the phone is lying and whether or not its auto-rotate is on; `auto` gives rotation back to the phone. Dropped unless the sender is viewing the phone (its `start-view` was accepted and not stopped). The phone lets go by itself when its last viewer stops or leaves, and when its screen turns off, which is also when it locks; it does not take hold again afterwards. While its screen is off or it is locked the phone holds nothing: a `rotate` then changes nothing, so the lock screen always comes up the phone's own way. Any other `orientation` changes nothing either. The phone answers with `phone-state` once the screen is held or isn't, so `rotation` there is what really happened; the picture's new size follows in `screen-info`. |
| `get-phone-state` | – | Ask the phone for `phone-state`. |
| `text`  | `text` | Type a unicode string into the focused text field, line breaks as `\n`. The sender composes characters with its own keyboard layout. |
| `key`   | `code`, `ctrl`?, `shift`? | Press one key, with Ctrl and Shift held if set. `code` is `ENTER`, `BACKSPACE`, `DELETE`, `TAB`, `HOME`, `END`, `PGUP`, `PGDN`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `ESC`, or a letter `A`-`Z` for a Ctrl shortcut. Plain `ENTER` performs the field's own action (Search, Send, Next...) the way the on-screen keyboard's Enter does. `ESC` is Back. With no text field focused, the arrows and `ENTER` move between and press controls. |

The phone types through its accessibility service's own input method, which
Android 13 added, so on older versions only `ESC` works. A viewer that sees
`screen-info` without `typing` should say so rather than type into nothing.
The desktop keeps Ctrl+V for itself and sends its clipboard as `text`, since
a paste there is expected to come from the PC.

A phone that has gone to sleep is woken by input, as touching it would wake
it: by every message in the table except the `move`s of a `touch`, `lock`,
`volume`, `rotate` and `get-phone-state`. `start-view` wakes it too, when it
comes from a device that was not already viewing; a viewer that sends it again
neither wakes the phone nor is announced on it again. Its lock screen stays in
place.

The phone answers `volume`, `rotate` and `get-phone-state`, to the device that sent them, with:

| type          | fields | notes |
|---------------|--------|-------|
| `phone-state` | `to`, `volume`, `volumeMax`, `muted`, `rotation`, `can` | `volume` is the media volume, an integer from 0 to `volumeMax` (15 on most phones). Android reports 0 while `muted` (boolean) is true. `rotation` is `portrait` or `landscape` while a viewer is holding the screen that way with `rotate`, and `auto` otherwise; it is what is being held, not which way the screen happens to be. `can` lists what the phone is able to do right now, as an array of names: `volume` (absent on a device whose volume is fixed), and `rotate`, `quick-settings`, `lock` and `screenshot` (absent while the accessibility service is off; the last two need Android 9). `rotate` is also absent while the phone's screen is off or it is locked, and back once it is unlocked. The first apps to send `phone-state` could not rotate: they leave `rotate` out and always say `auto`. |

A viewer offers only what `can` lists, and ignores names it doesn't know. Apps
before these messages never send `phone-state`, so a viewer that asks and
hears nothing offers none of it. The phone sends it only when asked; it is not
pushed when the volume changes on the phone or when the phone lets go of a
rotation, so a viewer asks again whenever it is about to show either.

The phone tells each device that is viewing it whether its screen is on and
whether it is locked, without being asked:

| type          | fields | notes |
|---------------|--------|-------|
| `lock-state`  | `to`, `screenOn`, `locked`, `wakeFailed`?, `entry`?, `message`? | `screenOn` (boolean) is false while the phone is asleep. `locked` (boolean) is true while its lock screen or its unlock screen is up, whether or not a pattern or PIN is set. `wakeFailed` is there only while the screen is off, after the phone tried to turn it on (for a `start-view` or for input) and it was still off a second later: `notifications` when the app's notifications are switched off on the phone, which is when a Samsung phone refuses, and `unknown` otherwise. `entry` and `message` are there only while the phone is locked with its screen on, and are described below. |

It is sent to each viewer by itself, never broadcast, and only while that
viewer would be sent the picture: when its `start-view` is accepted, and
whenever a field changes. A viewer that starts while the phone is asleep is
told once it is known whether the phone woke: at once if it did, a second
later if it didn't. The phone listens for its screen and its lock only while
someone is viewing.

A viewer uses it to explain a dark picture. With `screenOn` false it says the
phone is asleep, or that it didn't wake and why. Android leaves the unlock
screen, where the pattern or PIN is entered, out of every picture, so it
arrives black; input still reaches it. A viewer that is shown a picture that
is nearly all black while `locked` is true says that, unless the phone has
sent an `entry` to draw. The ordinary lock screen is in the picture and needs
no words. What a phone lists in `can` changes with its screen and its lock
(see `rotate`), so a viewer asks for `phone-state` again when `screenOn` or
`locked` changes.

`entry` says where the pattern, PIN or password is entered, once the unlock
screen is up and the phone could make it out. A viewer draws that itself over
the black picture, since Android won't put it in one:

| `entry` | meaning |
|---------|---------|
| `{ "kind": "pattern", "area": [x, y, w, h] }` | The pattern grid, as one rectangle. Android does not report the dots: they sit at the centres of an even three by three split of it. |
| `{ "kind": "pin", "keys": [{ "label": "1", "rect": [x, y, w, h] }, ...] }` | The keys of a PIN pad. The labels are the ten digits `0` to `9`, always all of them, and `delete` and `ok` where the phone has those keys. |
| `{ "kind": "password" }` | A password field. There is nothing to draw. |

Rectangles are fractions of the screen, from its top left corner, the way
`tap` gives a point. `message` is the lock screen's own message line, such as
"Incorrect pattern drawn" or "Try again in 30 seconds"; it is how the phone
answers a wrong try, and a viewer shows it with what it draws.

Unlocking needs no message of its own. A pattern is drawn with `touch`, as it
is on any screen. A PIN's keys are tapped with `tap`. A viewer with a
keyboard turns each typed digit into a `tap` at the middle of that key's
`rect`, and Backspace and Enter into taps on `delete` and `ok` where the pad
has them. Those taps carry `onlyIfLocked: true`: the viewer's idea of where
the keys are is as old as the last `lock-state`, and the phone drops such a
tap if it has unlocked meanwhile. While a `pin` entry is current a viewer
sends nothing that is typed as `text`: digits become taps and anything else
typed with them is dropped. A password is typed with `text` and `key`.
Nothing entered is kept on either side, and the phone remembers no positions:
they are read afresh each time.

The phone reads its lock screen for this only while all three hold: it is
locked, its screen is on, and a device is viewing. It reads the layout of the
system's own lock screen window and nothing else: where each view is and what
it is called, the words of the message line, and what something that can be
pressed says if that is a digit or names a key. It sends no more of it than
`entry` and `message`. An unlock screen it can't make out gives no `entry`.

An `entry` is sent once the phone has read the grid or the pad in the same
place twice, a quarter of a second apart: the unlock screen slides in, and
its keys are not at once where they will be. It is withdrawn when the phone
has read its lock screen from end to end and found none, when an app is in
front of the lock screen, and when the phone unlocks or sleeps; not when a
read merely failed. `message` follows the lock screen within about a second.

Apps before this message never send it. A viewer then has only the picture to
go by, and says of a nearly black one that the screen is off or the phone is
on its unlock screen. Apps that send it without `entry` leave a viewer to say
that the unlock screen is hidden. Older viewers ignore `lock-state`, or the
`entry` in it. A viewer ignores fields it doesn't know, an `entry` whose
`kind` it doesn't know or that makes no sense, and keys whose `label` it
doesn't know; and a `wakeFailed` it doesn't know is still a wake that failed.

#### Smooth sharing on request

A phone shows its screen in one of two ways. Accessibility viewing is there
whenever the phone has Android 11 or later and its Accessibility service is
on. Smooth sharing (Android's screen sharing, `capture: "screen-share"`) is
faster, and Android wants a person's consent each time it starts. A viewer
can ask the phone to show that prompt, and to stop sharing again:

| type             | fields | notes |
|------------------|--------|-------|
| `request-smooth` | `to` | Viewer to phone: show Android's screen-sharing prompt. |
| `stop-smooth`    | `to` | Viewer to phone: end smooth sharing. Accessibility viewing takes over. Refused where the phone has no Accessibility viewing to take over: see `only` below. |
| `smooth-status`  | `to`, `state`, `reason`? | Phone to viewer: what came of it. `state` is `prompting`, `started`, `declined`, `ended` or `unavailable`. `reason` is a sentence in the phone's own words that a viewer shows as it is. |

The phone acts on the two requests only from a device that is viewing it: one
it has approved, with control switched on, let in by *Allow other phones*, and
whose `start-view` it accepted and that has not stopped since. From anyone
else they are dropped without an answer, so a viewer that hears nothing for
five seconds says the phone did not answer. Neither is input: they do not wake
a sleeping phone.

What a viewer that asked is told:

| `state` | when |
|---------|------|
| `prompting` | The prompt is on the phone's screen. It has a `reason` when the prompt cannot be answered from a viewer: the phone has no picture to show until smooth sharing is on, so someone has to approve it on the phone. Sent again, with a `reason`, if the phone could only put up a notification that opens the prompt when tapped. |
| `started` | Smooth sharing is running: the prompt was approved, or it was running already, or it was turned on at the phone while the prompt was up (the prompt is then taken away). |
| `declined` | The prompt was refused (no `reason`), nobody answered it within a minute, or the phone's screen turned off under it. |
| `unavailable` | No prompt was shown, and `reason` says why: the phone is locked or asleep ("Unlock the phone first."), it is holding pictures back while a device waits to be approved, a prompt went without a yes a moment ago (below), or it could not show one: Android gave it no prompt, or would not show it and the notification that asks instead is switched off on the phone. Also the answer to a `stop-smooth` the phone refuses. |
| `ended` | Smooth sharing has stopped. Sent to every device viewing at that moment that sent `codecs`, whether or not it asked. `reason` says whether a viewer stopped it (and which), it was stopped on the phone, or the phone was locked: Android 15 and later end screen sharing when the phone locks. |

The prompt is Android's own, and only a person answers it: whoever holds the
phone, or whoever is at a viewer, where the prompt is in the picture and its
button can be clicked like anything else on the phone's screen. The app never
presses it. The phone asks Android for the whole screen, so the prompt comes
up on that choice and one press starts it. On a phone lying on its side the
prompt is taller than the screen and its buttons are out of view until it is
scrolled.

One prompt is up at a time. A second request while one is up, from the same
viewer or another, is answered `prompting` and waits for the same prompt. The
phone takes the prompt away when every viewer that asked has stopped viewing,
without telling anyone. A request to a phone that is already sharing is
answered `started`.

The phone's owner is not to be nagged. After a prompt has gone without a yes,
whichever way (refused, left for a minute, the screen turned off under it, or
whoever asked stopped viewing), nobody may ask again for ten seconds. Each
further one in a row doubles that, up to five minutes. A yes, or ten minutes
without such a prompt, starts over. A request made in that time shows nothing
and is answered `unavailable`, with a `reason` that says how long is left.

A phone can show its screen without smooth sharing only by Accessibility
pictures, which need Android 11 and its Accessibility service switched on.
Where it cannot, smooth sharing is the only picture a viewer has, and a viewer
that stopped it would be left with none, and with no way to see the prompt
that brings it back. Such a phone says `smooth: "only"` while it is sharing,
and answers a `stop-smooth` with `unavailable` and the reason. A viewer shows
smooth sharing as on and offers no way to stop it. It can still be stopped on
the phone.

A viewer offers this only to a phone whose `screen-info` carries `smooth`.
Apps before these messages never send it, and ignore the requests. `smooth`
changes with `capture`, so it arrives with the `screen-info` that announces
the new stream. A viewer ignores a `state` or a `smooth` it does not know.

While a device is viewing a phone in smooth sharing, the phone keeps its
screen on (it may dim): on Android 15 and later a phone that sleeps and locks
ends the sharing, and it cannot start again without the prompt. The phone lets
go when the last viewer stops or leaves, when a viewer sends `lock`, and after
ten minutes in which nobody did anything. Input does something: what wakes a
sleeping phone, listed above, and the next such message takes hold again. So
does smooth sharing starting, and a device beginning to view. A device that is
only back after its connection dropped does not: a viewer asks for the screen
again by itself then, with nobody at it. The phone takes a `start-view` for
such a return unless that device, by its identity and not its peer id, ended
its last view with `stop-view`. So a viewer sends `stop-view` when the person
at it stops viewing, and not when its connection drops. Accessibility viewing
does not keep the phone awake.

**File system** (`path` uses the native separators of the target device)

| type             | fields | notes |
|------------------|--------|-------|
| `fs-list`        | `to`, `path`, `reqId` | Empty `path` = roots (drives on Windows, storage root on Android). Android answers the folders above a storage volume (`/storage/emulated`, `/storage`), which apps can't list, with its volumes at `/storage` when it has more than one, else with internal storage. |
| `fs-list-result` | `reqId`, `path`, `entries` `[{name, dir, size, mtime, label?}]`, `error?`, `downloadsPath?` | Both apps advertise their absolute upload destination in `downloadsPath`, so the browsing device can open there and return there as an upload starts. Older peers omit it. A storage volume's entry has its path below `path` as `name` (`emulated/0`) and what Android calls it as `label` ("SD card"); browsers show the label when present. |
| `fs-get`         | `to`, `path`, `xferId` | Ask peer to send me a file. |
| `fs-begin`       | `to`, `xferId`, `name`, `size` | Sender announces an incoming file (used for both push-upload and get-response). |
| `fs-end`         | `to`, `xferId`, `ok`, `error?` | A sender cancels its own transfer by ending it early with `ok: false`. |
| `fs-cancel`      | `to`, `xferId` | Receiver gives up on a file it asked for with `fs-get`, before or after its `fs-begin`. The sender stops and ends it with `fs-end` `ok: false`; only the device the file is going to can cancel it. The receiver drops what has arrived at once and ignores the rest. A receiver also sends it when it can no longer write a file it asked for (a full disk). Older senders ignore this and send the whole file, so a receiver also refuses an `fs-begin` for a download it cancelled before it began, even while it accepts pushes. |
| `fs-saved`       | `to`, `xferId`, `path?`, `error?` | Receiver reply after saving; `path` is the actual absolute destination, including any duplicate-name suffix. Both apps reply with `error` if saving fails or a known-size file is incomplete, and at once to an `fs-begin` they refuse as a push (file access off, or the sender not approved) and when a chunk of a pushed file cannot be written (a full disk), so the sender can stop. A sender waiting for this stops on any `error`, even mid-file. Older peers may omit this reply; Android share-sheet sends and Windows uploads wait for confirmation and treat a timeout as unconfirmed. |
| `fs-offer`       | `to`, `files` `[{path, name, size, kind}]`, `thumb?` | A phone tells a PC about photos, videos, screenshots or screen recordings it has just saved, so the PC can offer to download them with `fs-get` on `path`. `kind` is `photo`, `video`, `screenshot` or `screen-recording`; a PC treats anything else as a plain file. `thumb` is a base64 JPEG preview of the last file, at most 256 px on its longer side, where the phone could make one. See [New captures](#new-captures). |

A transfer lives on the connections it started on. When a device reconnects
it joins under a new peer id, and the relay tells everyone its old id left, so
a receiver drops every transfer from that id and ignores the chunks and
`fs-end` that follow under it. Frames still queued on the old connection are
gone too. So a sender whose relay connection drops, or whose receiver leaves,
fails the file instead of finishing it over the next connection; it would
otherwise look sent while nothing arrived. A Windows upload counts as sent only
once `fs-saved` arrives, and offers a retry when it fails.

### Remote updates

A viewer that sees a PC's `version` is older than `latest` can ask it to update
now, without waiting for it to be idle (see [Updates](#updates)).

| type            | fields | notes |
|-----------------|--------|-------|
| `update-now`    | `to` | Ask a Windows host to install the newest release now. It acts only for an approved device, and restarts at once, so its viewers reconnect. Only sent to peers that announced a `version`; older apps don't know it. |
| `update-status` | `to`, `state`, `version`?, `error`? | The host's answer. `state` is `updating` (downloading, then restarting into `version`), `current` (already the newest), or `failed` with a human-readable `error`, e.g. the relay hasn't named a release yet, or the download didn't verify. |

### New captures

A phone sends `fs-offer` only to PCs that it and they have approved, only while
its file access is on and every peer in the session is approved, which are the
conditions under which it would serve the `fs-get` that follows. It never
offers a file later: a capture saved while no PC could take it up stays
unoffered. Several files saved in quick succession, like a burst of shots, go
out as one offer.

The phone learns of new files from MediaStore's change notifications, so it
does no work while nothing is being saved. A file counts as a capture when it
was written in the last ten minutes and sits where cameras and the system save:
at most one folder into `DCIM` (`DCIM/Camera`, `DCIM/Screenshots`), or in a
`Screenshots` or screen-recordings folder in `Pictures` or `Movies`. Deeper
folders are left alone, since that is where people keep archives. A file moved
or restored into one of these folders keeps its old modification time and is
not offered.

A PC acts on `fs-offer` only from an approved device, and older apps ignore it.

**Clipboard** (only a Windows host serves these; a phone or another PC sends them)

| type              | fields | notes |
|-------------------|--------|-------|
| `clip-get`        | `to`, `reqId`, `press?` | Copy the selection in the window in front and send back the clipboard. The host presses copy (see below), then waits up to 1.5 s for the clipboard to change. `press: false` skips the key and sends the clipboard as it is. |
| `clip-get-result` | `to`, `reqId`, `text?`, `html?`, `files?`, `folders?`, `fresh`, `sensitive?`, `error?` | `html` is the copied fragment of the clipboard's HTML version, when the source app offered one. `files` lists the absolute paths of copied files (for `fs-get`) when the clipboard holds files rather than text: at most 100, only while the host's file access is on, folders left out and counted in `folders`. `fresh: false` means the copy changed nothing, so this is what was already on the clipboard; a viewer should ask before using it. `sensitive: true` means the source asked to keep the copy out of clipboard history, as password managers do; a host never sends such a copy with `fresh: false`, and Android hides it from its copy preview. `error` is a human-readable reason, e.g. the window in front runs as administrator, the PC is locked, or the clipboard holds only a picture. |
| `clip-set`        | `to`, `reqId`, `text`, `html?`, `paste?`, `sensitive?` | Put this text on the receiver's clipboard, with Windows line breaks. With `paste: true` the receiver also presses paste (see below). `sensitive: true` keeps it out of Windows clipboard history and cloud sync. Without `paste`, Windows shows a toast saying what arrived. |
| `clip-set-result` | `to`, `reqId`, `error?` | |

Every clipboard request, including a plain `clip-set`, needs remote-control
consent: it presses keys, or changes what the PC pastes next. A host answers
one at a time, re-checks consent when each request's turn comes, and refuses
while the PC is locked.

Which keys a host presses depends on the window in front. Copy is
**Ctrl+Insert**, which is copy in Office, browsers, Electron apps, VS Code, the
JetBrains IDEs and terminals, and which never stops a running program the way
Ctrl+C does in a terminal with nothing selected; an editor's integrated
terminal can't be told apart from the editor by its window class. File
Explorer and the desktop get Ctrl+C. A classic console window gets its own
Edit > Copy and Edit > Paste commands (`WM_SYSCOMMAND` `0xFFF0` / `0xFFF1`),
so no key reaches the program at all. PuTTY copies on select, so nothing is
pressed and the result is never `fresh`. Paste is Shift+Insert in terminals and
Ctrl+V elsewhere. Any other modifier already down is released first.

`text` plus `html` is at most **200,000** UTF-16 code units. Android passes a
clip to the system through Binder, whose buffer is about 1 MB, and a large
control message holds up any input queued behind it. A sender drops `html`
first and refuses text that is still too long; receivers apply the same rule.
Older apps ignore these messages, so senders give up after about 10 seconds.

## Binary frames

The **first byte is the cleartext frame type**; the rest is the encrypted
payload (`nonce(12) || ciphertext || tag(16)`, AES-256-GCM, AAD = the frame-type
byte). After decryption the payload is:

| byte | meaning     | decrypted payload |
|------|-------------|-------------------|
| `1`  | video frame | `JPEG bytes` — one full JPEG image of the streamed screen. (Sent by a phone while any of its viewers is on an app that does not know type `5`; the Windows host streams type `3`.) |
| `2`  | file chunk  | `[xferId: uint32 BE][data bytes]` — sequential chunks (≤ 256 KiB) for the transfer announced by `fs-begin`. |
| `4`  | H.264 frame | `[seq u32][flags u8: bit0 keyframe][surfW u16][surfH u16][regionX u16][regionY u16][regionW u16][regionH u16]` followed by one Annex B access unit. The region is always present: this path runs only in region mode. See [Hardware video](#hardware-video). |
| `5`  | phone frame | `[stream u64][seq u32][flags u8][width u16][height u16][captureMs u32]` followed by the picture, so far always one full JPEG of the phone's screen. Integers are big-endian. See [Phone frames](#phone-frames). |
| `3`  | screen patch | Dirty-rect update, all integers big-endian: `[seq u32][flags u8][surfW u16][surfH u16][rectCount u16]`, then — only when `flags` bit 1 is set — `[regionX u16][regionY u16][regionW u16][regionH u16]`, then `rectCount` × `[x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes]`. `flags` bit 0 = keyframe (a single rect covering the whole image); bit 1 = the image covers only the given desktop rect; bit 2 = scrolled, i.e. the crop moved and the overlapping pixels are to be carried across rather than resent (see [Viewport streaming](#viewport-streaming)). |

**Dirty-rect streaming (type 3).** The host compares each captured frame to the
previous one on a 64 px block grid and sends only the changed regions as JPEG
tiles, so a static screen costs (near) zero bandwidth. Tiles are absolute pixel
content: the viewer blits them into a persistent `surfW × surfH` canvas, and a
*lost* patch (they may be dropped under backpressure) only leaves those regions
stale — there is no codec-style corruption. Recovery rules:

- `seq` increments per patch **including dropped ones**, so a viewer detects
  loss as a gap and sends `request-keyframe`.
- **Backpressure is answered with damage, not keyframes.** When the send lane
  is still busy the host skips encoding that tick and accumulates the changed
  blocks; the next patch that goes out repaints their union. A host must not
  respond to congestion by sending a keyframe — a full-screen JPEG is the most
  expensive frame there is, so it refills the very queue it was waiting on and
  pins the link at one frame per round trip.
- The host sends a keyframe on stream start / `request-keyframe` / surface
  resize, and every ~10 s while deltas are being sent (safety net).
- A viewer that has no canvas yet ignores deltas and asks for a keyframe.
- **`surfW`/`surfH` change while streaming.** The host scales its output down
  under sustained congestion and back up when the link clears, so a viewer must
  treat every patch header as authoritative and reallocate its canvas (and
  release the old one) whenever the size differs. A size change is always
  accompanied by a keyframe and a fresh `screen-info`.

So the wire layout is `[frameType(1)][nonce(12)][ciphertext][tag(16)]`. A
binary frame sent like that is broadcast by the server to all other peers in
the session.

### Addressed binary frames

Broadcast is wrong as soon as a session holds more than two devices. A phone
viewing a PC that is itself viewing a second phone received both hosts'
streams and drew them alternately, and any device that wasn't watching still
paid the bandwidth. So a sender names the recipients, and the relay delivers
the frame to those peers only:

```
[0x80 | frameType][count u8]{[idLen u8][peer id, UTF-8]} x count [nonce(12)][ciphertext][tag(16)]
```

The relay strips the recipient list and clears bit 7, so a receiver gets the
plain `[frameType][nonce][ciphertext][tag]` above and decrypts it as it always
has; the AAD is still the plain frame-type byte. Ids that aren't in the session
are skipped, a peer listed twice gets the frame once, and a malformed or empty
list drops the frame.

Hosts address video to the peers that sent `start-view`, and file chunks to
the device the file is going to. A relay that doesn't understand the format
would broadcast it unchanged, which no receiver can read, so a sender
addresses frames only on a connection whose `welcome` carried `binaryTo: true`,
and broadcasts otherwise. A viewing app should still ignore frame types its
host never sends (a desktop viewer ignores types `1` and `5`; a phone viewing a
phone draws `1` and `5` and ignores `3` and `4`), since an older relay or sender
broadcasts everything. A type `1` frame names no sender, so behind an older
relay a phone viewer can't tell two streaming phones apart. Type `5` frames
carry a stream id for that reason.

### Phone frames

A phone sends its screen as whole JPEGs. Type `1` is the bare JPEG. Type `5`
is the same picture behind a header:

```
[stream u64][seq u32][flags u8][width u16][height u16][captureMs u32][picture bytes]
```

| field | meaning |
|-------|---------|
| `stream` | The id of the stream the frame belongs to: 64 random bits, picked by the phone when the stream starts. |
| `seq` | 0 for a stream's first frame, one more for each frame after it. A gap means a frame was lost on the way. A picture the phone decided not to send takes no number. |
| `flags` bit 0 | Keyframe: a picture that needs no earlier one. Always set for a JPEG. |
| `flags` bits 1–2 | Format: `0` JPEG. `1` is kept for H.264; `2` and `3` are not used. A viewer does not draw a format it did not offer. |
| `flags` bit 3 | Kept for a picture sent turned on its side. Not used. |
| `flags` bit 4 | Repeat: the picture of the frame before, sent again because no viewer said that one had arrived (see below). It has its own `seq` and the first one's `captureMs`. A viewer draws it like any frame and leaves it out of its delay figures. |
| `flags` bits 5–7 | Not used; a viewer ignores them. |
| `width`, `height` | The picture's size in pixels. The shorter side is at most 1080, so a phone on its side sends up to 2340 × 1080 or so. |
| `captureMs` | The low 32 bits of the phone's uptime clock, in milliseconds, when the screen was captured, or when the picture reached the app if Android's own stamp on it is not on that clock. See `hostTimeMs` in `diagnostic-pong`. |

**Which type a phone sends.** A viewer on a current app puts `codecs` in its
`start-view`. If every device viewing the phone has offered `"jpeg"`, the phone
sends type `5`. If any has not, which is every app up to v1.0.53, all of them
get type `1`: there is one stream for all viewers. The choice is made again
whenever a viewer starts, stops or sends `start-view` again, and each
`start-view` replaces what that viewer offered before.

**Streams.** A type `1` frame names no sender, so a viewer draws one from
anyone who holds the session key. Type `5` closes that for apps that know it:

- A stream starts when pictures can first go as type `5`, when they can again
  after a stop, and when the phone changes how it captures its screen. The
  phone picks a new id and, before the stream's first frame, sends each viewer
  a `screen-info` addressed to that viewer with `stream` (the id as 16 hex
  digits), `codec` (`jpeg`) and `capture` (`accessibility`, or `screen-share`
  for Android's screen sharing). A viewer that joins a running stream is told
  the same id and takes up the numbering where it finds it.
- A viewer believes a `stream` only from the phone it is viewing, and only if
  it has itself approved that phone, which is also the only time it offers
  `codecs`. It reads the id in the order messages arrive, before the frame
  behind it.
- While a stream is announced, the viewer draws only type `5` frames that carry
  its id, each number once, and no type `1` frames at all.
- When a viewer that needs type `1` joins, the phone ends the stream: the
  viewers that sent `codecs` get a `screen-info` without `stream`, and from
  then on draw type `1` like everyone else.
- When the phone stops sending for a while with viewers still there, as it does
  while a device nobody has approved is in the session, it sends those viewers
  a `screen-info` with `paused: true` and no `stream`. Until the next
  `screen-info` they draw nothing, of either type: whatever arrives is not the
  phone's. The id is dead, and the next stream gets a new one.
- A stream also ends with its last viewer, and when the phone's connection
  drops. Nobody is told then.

A viewer on an older app is sent `screen-info` exactly when it always was,
with the fields it always had: it sizes its window on every one. The extra
ones that a stream starting or ending brings go only to viewers that sent
`codecs`.

What this does not cover: a phone on an older app sends type `1` only, and so
does a current one while an older viewer is watching beside a current one. And
all of a phone's viewers share one id, so one approved viewer could draw into
another's window.

A phone never sends a picture with nobody to address it to; the relay would
take that for a broadcast.

**Holding back.** While a stream is running, every viewer acknowledges
(`frame-ack`), and the phone sends the next picture only while none of them is
behind. For each viewer it knows the usual time from sending a frame to
hearing it acknowledged: the shortest in the last minute. There is room for
another picture while no frame has waited for a viewer's acknowledgement more
than 100 ms longer than is usual for that viewer; until a viewer has
acknowledged anything, two frames may be on their way to it. The slowest
viewer sets the pace. One that leaves a frame unacknowledged for two seconds
stops counting until it acknowledges something again. A picture that has to
wait is replaced by any newer one, so what goes when there is room is the
screen as it is then.

If the newest frame is still unacknowledged by some viewer one second after it
went, and nothing has been sent since, the phone sends the same picture again
with the repeat flag, then after twice, four times and eight times as long,
and then leaves it: the relay drops frames for a viewer that is backed up, and
on a screen that has stopped changing nothing else would replace a lost one.
A picture that is only late is not sent again. A viewer whose acknowledgements
usually take longer than a second is given its usual time and 100 ms more in
place of the second, up to eight seconds. And a picture goes again only while
there is room for a picture at all: a viewer that has been acknowledging is
taken to be behind, not to have lost the picture, until it has left it
unacknowledged for two seconds.

**A still screen.** A phone sends a picture when its screen has changed, not
on a clock. A viewer is also sent one, whatever it shows, when it sends
`start-view`, when the picture's size changes and when pictures start again
after a pause. While viewers are being sent type `1`, which cannot be
acknowledged, an unchanged screen is still sent every three seconds.

## Viewport streaming

A phone showing a 7680×2160 desktop displays about 1080 columns of it. Encoding
the rest is work nobody can see, so a viewer reports what it is actually looking
at and the host sends that instead.

The viewer sends `view-region` with the desktop rect it can display, normalized
0..1, plus `outW`/`outH` — the pixel size worth delivering it at, which is
however many screen pixels that region occupies, since one image pixel per
screen pixel is the most a display can use. Viewers should ask for a small
margin around the visible area so that a short pan needs no round trip, report
once a gesture settles rather than on every touch move, and skip a report that
matches the last one: **every region change costs a keyframe.**

The host replies with patches carrying `flags` bit 1 and the region rect in
desktop pixels. It also applies its own limits on top of the request — the
congestion ladder and the configured `MaxStreamWidth` can both deliver fewer
pixels than asked for — and never sends more than the region natively holds,
because past 1:1 the extra pixels are interpolation rather than detail.

Region mode is used only while **every** viewer has requested a region. All
viewers get the same patches, so a session has one stream and it must be one
that every viewer can read: a viewer that never sends `view-region` (an older build, or the
Windows PC viewer) keeps the whole-desktop stream, and its presence puts the
session back on that path for everyone. With several region viewers the host
streams the union of their requests at the densest resolution any of them asked
for.

Because the streamed image is no longer the whole desktop, a viewer must treat
`desktopWidth`/`desktopHeight` from `screen-info` as its coordinate space —
normalized input coordinates are relative to the desktop, not to the region it
happens to be receiving.

**Panning carries pixels rather than resending them.** When the crop moves but
keeps its size and pixel density and still overlaps what the viewer holds, the
host sets `flags` bit 2 and sends only the newly exposed strips. The viewer
shifts its existing canvas by the difference and paints those strips into the
gap. Without this a pan costs a full keyframe of the region every time it
settles, which is what took the keyframe rate from ~4% to ~27%.

For the shift to be exact, the host quantizes: it picks an integer number of
desktop pixels per output pixel and snaps the crop to that grid, so any move
between two crops is a whole number of output pixels and nothing drifts.
Integer-factor downscaling is also cleaner than an arbitrary bilinear ratio.
A viewer must verify the arithmetic before trusting it — same crop size, exact
integer ratio, whole-pixel move, non-empty overlap, and a canvas that was valid
to begin with — and ask for a keyframe if any of that fails.

The scroll is decided against the geometry the viewer last *received*, not the
last one computed: a tick can be captured and then skipped for congestion, and
the viewport can move twice before a patch goes out. Measuring against computed
geometry would send strips relative to a crop the viewer never had, which is
silent corruption rather than a visible glitch.

The host also skips capturing outputs the streamed region does not touch. On
the DXGI path that saves a full-resolution GPU copy per monitor per frame, so
viewing one monitor of a two-monitor desktop costs about half what it used to.
An output that comes back into view is repainted in full, since the dirty
metadata accumulated while it was ignored no longer describes the gap — the
region change that brought it back forces a keyframe anyway.

## Hardware video

JPEG tiles re-encode every changed pixel from scratch. A window playing video
is one large rectangle changing every frame, which is the worst case for that
and the best case for inter-frame prediction, so the host can instead stream
H.264 (binary frame type `4`) through whatever encoder the GPU registers.

Like region mode this is negotiated and all-or-nothing: the host uses it only
while **every** viewer has set `h264: true` on its `view-region`, because every
viewer gets the same stream. It also rides on region mode, since the
encoder needs a fixed frame size and a well-defined crop, and a whole
7680-wide desktop is past what most hardware encoders accept at all.

Three consequences worth knowing:

- **A pan needs no keyframe.** The encoder sees a shifted picture and codes it
  as motion, which is what it is good at. The scroll flag on type `3` exists
  because JPEG tiles have no such mechanism; it is not used here.
- **The frame size does not track the crop.** Every frame header carries the
  region, so `surfW`/`surfH` are only the resolution the host chose to deliver
  that crop at, and the two are free to differ. The host therefore pins the
  frame size and lets the crop move and resize under it, rebuilding the encoder
  only when the ideal resolution has drifted about half again either way.
  Non-uniform scaling between crop and frame is harmless: the viewer maps the
  frame back onto the crop rect, so the stretch cancels.
- **Rebuilding is what costs.** Encoders will not take a mid-stream resolution
  change, so a rebuild is a teardown on both ends plus an IDR and a round trip.
  Resizing the frame to follow every pan and zoom made keyframes 72% of all
  frames, which is why the pinning above matters more than it looks.
- **Frame dimensions are even**, and the crop is trimmed to match rather than
  the frame alone — 4:2:0 chroma is sampled in 2x2 blocks, and trimming only
  the frame would leave the region no longer describing the pixels in it.

A viewer must be able to start from any frame with `flags` bit 0, which the
host sets when the access unit carries SPS or an IDR slice. The host asks its
encoder for one on demand, but not every encoder honours that, so it also
configures a GOP of about two seconds to bound how long a viewer that joined
late or lost a frame stays broken.

## Queueing and priority

Everything shares one TCP connection per device, so anything buffered ahead of a
keystroke delays it. Both the sender and the relay therefore keep the video
backlog deliberately small.

**Sender lanes.** Outgoing frames are split in two:

- *reliable* — control JSON and file chunks, strict FIFO. The ordering is
  load-bearing: `fs-begin` must precede its chunks and `fs-end` must follow
  them, so these share one queue and are only ever taken from its head.
- *video* — types `1` and `3`, droppable, capped at a couple of frames. An
  overflow discards the **oldest** frame, never the newest: a stale screen
  update is worthless once a newer one exists.

A control message at the head of the reliable lane goes first, then video, then
a file chunk. So input and `diagnostic-ping`/`pong` overtake both bulk transfers
and screen data, while the reliable lane is never reordered against itself.

**Relay buffering.** The relay drops a binary frame for any peer whose socket
already has more than 256 KiB buffered, except file chunks (`2`), which cannot
be dropped without silently corrupting a transfer and keep an 8 MiB ceiling.
The frame-type byte is cleartext precisely so the relay can tell these apart
without decrypting anything.

**Android sender.** A phone has one queue (OkHttp's). It skips a picture (type
`1` or `5`) while more than 64 KB is queued, which is less than one picture: the
one before must have left the app. It holds back file chunks only above 6 MB.
That queue is not all that waits. The system buffers a megabyte or more below
it, where the app cannot see, so for type `5` the phone also goes by what its
viewers have acknowledged (see [Phone frames](#phone-frames)). A screen picture
skipped for either reason, or for the 10 fps cap, is kept and replaced by any
newer one, and the newest goes out once there is room, so the last change on
screen always arrives. The phone's socket has Nagle's algorithm off, like the
relay's and the PC's, so a small message is not held for the packet before it.

## End-to-end encryption

Everything except the `hello` and the server's own control messages
(`welcome` / `peer-*` / `error`) is encrypted so the relay cannot read it.

**Key derivation** (HKDF-SHA256, RFC 5869), from the UTF-8 session key:

```
salt   = "remote-desktop/v1"
encKey = HKDF(ikm=sessionKey, salt, info="e2ee-key", 32)   # AES-256 key, stays on-device
pairId = HKDF(ikm=sessionKey, salt, info="pair-id", 32)    # lowercase hex, sent as hello.session
```

Because `pairId` and `encKey` come from independent HKDF `info` labels, learning
the `pairId` (which the server necessarily sees) reveals nothing about `encKey`.

**AEAD:** AES-256-GCM, fresh random 12-byte nonce per message, 16-byte tag. The
associated data is a single **channel** byte, binding each ciphertext to its
context: `0` = JSON control, `1` = full-frame video, `2` = file chunk, `3` =
dirty-rect screen patch, `4` = H.264 from a PC, `5` = phone frame. Frame =
`nonce(12) || ciphertext || tag(16)`.

**Control envelope** (text frame): the inner control message is encrypted on
channel 0 and wrapped as

```json
{ "type": "enc", "to": "<peerId>"?, "d": "<base64(nonce||ct||tag)>" }
```

The `to` is copied from the inner message so the relay routes it unchanged; the
relay adds `from` to the envelope, and the receiver copies it onto the decrypted
inner message.

**What a receiver refuses.** The relay adds `from` to everything it passes on
from a device, and its own messages have none. So an app drops a message that
is not encrypted and has a `from`: a device speaks to another only inside
`enc`. It takes `from` from the envelope alone, never from inside the
encrypted message. And it drops `welcome`, `peer-joined`, `peer-left`, `error`
and `latest` when they come with a `from`, encrypted or not: only the relay
says who is in the session.

**What the relay can still see (metadata):** connection timing/size, IP (for
rate limiting), the `pairId`, each peer's `device` type and chosen `name`, and
`to`/`from` routing ids. It cannot see screen contents, input, file names or file
data.

**Cross-language test vectors** (verified by `server/test-e2ee.mjs`, `Crypto.SelfTest()`
in C#, and `Crypto.selfTest()` in Kotlin):

```
sessionKey = "correct horse battery staple"
encKey     = 198b7e020fa0c8fdfeb3514e6b402eb3ea5af30addadc19153965eeec0e8dabf
pairId     = 9a81be09e574b5a3078107db507c21722e8ea1f9f0a7bf762d6894a5dd371626
channel 0, nonce = 000102030405060708090a0b, plaintext = {"type":"mouse","x":0.5}
  -> 000102030405060708090a0b11316fc47766f9c1dec528cf275cf3fa7d82e9c7f09830195d76f8782e7999f9150b6d97be135331
channel 5, same nonce, plaintext = the phone frame below, as bytes
  -> 000102030405060708090a0b94cfa1257157e9eb0ca847bb5530f5d2670534023049dd9bf5c7c4c85dc7fd49ca1773758a5994f402
```

**Phone frame test vector** (built and read back by `PhoneFrame.selfTest()` in
Kotlin and `PhoneFrame.SelfTest()` in C#, so the two apps cannot come to
disagree on the header): stream `fedcba9876543210`, `seq` 4026531841, a JPEG
keyframe, 2340 × 1080, `captureMs` 4294967280, and the four picture bytes
`ffd8ffd9`:

```
fedcba9876543210 f0000001 01 0924 0438 fffffff0 ffd8ffd9
```

**Not covered:** replay protection (a malicious *active* relay could reorder or
replay ciphertext; GCM guarantees confidentiality + integrity, not freshness)
and metadata privacy. The threat model is a passive/curious relay and network
eavesdroppers.

## Server rate limiting

- Per IP: max **20 different pairing ids per 10 minutes**. A device that
  reconnects with its own pairing id adds nothing, so flaky networks and relay
  restarts never trip it. Exceeding it closes the connection with
  `error: rate-limited` and bans the IP for 1 hour.
- While banned, every connection from the IP gets `error: banned` with
  `retryAfter` and is closed with code 4001.
- Connections that don't send a valid `hello` within 10 seconds are dropped.
- Max 16 concurrent connections per IP; more get `error: too-many-connections`
  and close code 4002.

## Reconnection

Clients auto-reconnect with backoff (2s → 30s max) and re-send `hello`. The
session key never expires, and the device-authentication handshake re-runs
silently on every reconnect — an approved device never sees a prompt again
unless it is revoked or the session key changes.

### File-access status

An authenticated viewer sends `get-file-access` with `to` to query a desktop or
a phone. It replies with `file-access`, `to`, and `allowed` (boolean). `allowed`
is true only when file access is enabled and all session peers are trusted; a
phone asked by another phone also requires its *Allow other phones* switch. A
desktop sends the same status unasked when file consent or peer trust changes; a
phone answers only when asked. A phone adds `phones` (boolean), the state of
that switch, and another phone offers View and Browse for it only after a reply
with `phones: true`; a phone on an older app never replies. Android disables
uploads until it receives an affirmative status, including on returning from
the file picker. The device still enforces consent on every file request.
