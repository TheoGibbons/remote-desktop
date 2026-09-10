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
| `hello` | `session` (the derived **pairing id**, not the raw key), `device` (`windows`\|`android`), `name`, `uid`? | First message after connect. Cleartext. `uid` is a stable random per-install id; when a device reconnects with the same `uid`, the server kicks its stale connection from the session (and broadcasts `peer-left`) so the device is never listed twice. |

### Server → Client

| type          | fields                                | notes |
|---------------|----------------------------------------|-------|
| `welcome`     | `id` (your peer id), `peers` (array of `{id, device, name}`) | Reply to `hello`. |
| `peer-joined` | `peer` `{id, device, name}`            | |
| `peer-left`   | `id`                                   | |
| `error`       | `code`, `message`                      | e.g. `rate-limited`, `bad-hello`. Connection is closed after. |

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

**Enforcement.** Messages that view, control, or touch files (`start-view`,
`request-keyframe`, `mouse`, `scroll`, `key`, `text`, `cad`, `tap`, `swipe`,
`touch`, `pinch`, `back`, `homebtn`, `recents`, `fs-*`) are dropped unless the
sending peer is trusted. Because **binary frames are broadcast** and every key
holder can decrypt them, a host additionally sends no video frames and serves
no file transfers while *any* unapproved peer is present in the session (the
stream resumes automatically once the peer is approved or leaves; a keyframe
covers the gap).

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
| `start-view`  | `to`   | Ask peer to start streaming its screen to me. |
| `stop-view`   | `to`   | Stop streaming. |
| `screen-info` | `width`, `height`, `desktopWidth`?, `desktopHeight`? | `width`/`height` are the pixel size of the streamed image. `desktopWidth`/`desktopHeight` are the size of the whole desktop, which is the coordinate space `view-region` and screen-patch region rects are expressed in; absent from older hosts, where the streamed image *is* the whole desktop. Sent when streaming starts and whenever the geometry changes. |
| `view-region` | `to`, `x`, `y`, `w`, `h`, `outW`, `outH` | Viewer tells the host which part of the desktop it can actually show (normalized 0..1) and how many pixels it is worth sending that region at. See [Viewport streaming](#viewport-streaming). |
| `request-keyframe` | `to` | Viewer asks the streaming host for a full frame (sent when a sequence gap is detected in dirty-rect patches, throttled to one per ~2 s). |
| `diagnostic-ping` | `to`, `nonce` | Authenticated viewer RTT probe. A trusted host echoes the nonce in `diagnostic-pong`. |
| `diagnostic-pong` | `to`, `nonce`, `hostQueueBytes`, `targetFps`, `jpegQuality`, `maxWidth` | RTT response plus the desktop sender's queued binary backlog. The three stream fields report what the host has **adapted to**, not what the user configured, so they move during a session. |

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

**Input — controlling Android** (coordinates normalized 0..1 over the phone screen)

| type    | fields | notes |
|---------|--------|-------|
| `tap`   | `x`, `y` | |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `ms` | Drag gesture over `ms` milliseconds. |
| `touch` | `action` (`down`\|`move`\|`up`), `x`, `y` | Streamed pointer for real-time press / long-press / drag. The phone plays it as one continued accessibility stroke: the finger stays down from `down` until `up`. Senders should throttle `move` to ~30 ms. |
| `pinch` | `x`, `y`, `dir` (`in`\|`out`) | Two-finger pinch step centered at `x`,`y` (e.g. one mouse-wheel notch). |
| `back` / `homebtn` / `recents` | – | Global navigation actions. |

**File system** (`path` uses the native separators of the target device)

| type             | fields | notes |
|------------------|--------|-------|
| `fs-list`        | `to`, `path`, `reqId` | Empty `path` = roots (drives on Windows, storage root on Android). |
| `fs-list-result` | `reqId`, `path`, `entries` `[{name, dir, size, mtime}]`, `error?` | |
| `fs-get`         | `to`, `path`, `xferId` | Ask peer to send me a file. |
| `fs-begin`       | `to`, `xferId`, `name`, `size` | Sender announces an incoming file (used for both push-upload and get-response). |
| `fs-end`         | `to`, `xferId`, `ok`, `error?` | |
| `fs-saved`       | `to`, `xferId`, `path` | Optional receiver reply after saving; `path` is the actual absolute destination, including any duplicate-name suffix. Older peers may omit this reply. |

## Binary frames

The **first byte is the cleartext frame type**; the rest is the encrypted
payload (`nonce(12) || ciphertext || tag(16)`, AES-256-GCM, AAD = the frame-type
byte). After decryption the payload is:

| byte | meaning     | decrypted payload |
|------|-------------|-------------------|
| `1`  | video frame | `JPEG bytes` — one full JPEG image of the streamed screen. (Still used by the Android host; the Windows host streams type `3`.) |
| `2`  | file chunk  | `[xferId: uint32 BE][data bytes]` — sequential chunks (≤ 256 KiB) for the transfer announced by `fs-begin`. |
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

So the wire layout is `[frameType(1)][nonce(12)][ciphertext][tag(16)]`. Binary
frames are broadcast by the server to all other peers in the session (sessions
are expected to hold 2 devices; the design tolerates more).

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

Region mode is used only while **every** viewer has requested a region. Patches
are broadcast, so a session has one stream and it must be one that every viewer
can read: a viewer that never sends `view-region` (an older build, or the
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
dirty-rect screen patch. Frame =
`nonce(12) || ciphertext || tag(16)`.

**Control envelope** (text frame): the inner control message is encrypted on
channel 0 and wrapped as

```json
{ "type": "enc", "to": "<peerId>"?, "d": "<base64(nonce||ct||tag)>" }
```

The `to` is copied from the inner message so the relay routes it unchanged; the
relay adds `from` to the envelope, and the receiver copies it onto the decrypted
inner message.

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
```

**Not covered:** replay protection (a malicious *active* relay could reorder or
replay ciphertext; GCM guarantees confidentiality + integrity, not freshness)
and metadata privacy. The threat model is a passive/curious relay and network
eavesdroppers.

## Server rate limiting

- Per IP: max **20 `hello` attempts per 10 minutes**. Exceeding it closes the
  connection with `error: rate-limited` and bans the IP for 1 hour.
- Connections that don't send a valid `hello` within 10 seconds are dropped.
- Max 16 concurrent connections per IP.

## Reconnection

Clients auto-reconnect with backoff (2s → 30s max) and re-send `hello`. The
session key never expires, and the device-authentication handshake re-runs
silently on every reconnect — an approved device never sees a prompt again
unless it is revoked or the session key changes.
