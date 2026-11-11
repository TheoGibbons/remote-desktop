# Faster phone viewing from the PC

Written 2026-11-10 against v1.0.53 (`30009c4`), then checked against the code by an independent review and revised the same day (the review log is the last section). Built so far: step 0.1, and step 0.2 on 2026-11-11, whose text below now describes what was built. Everything else is still a plan.

Theo answered the open questions on 2026-11-10; section 12 records the answers, and they changed the order of work (section 3) and added Phase U, which has since become a plan of its own.

Two other plans written the same day change the same viewer window, and both are built before this one: `PHONE-MENU-PLAN.md`, then `PHONE-UNLOCK-PLAN.md`. `PHONE-PLANS-ORDER.md` gives the order across all three (this plan is its step 0 and steps 8 to 12) and settles where they disagreed. This file has been edited to match. Section 3.1 says where this plan meets the other two.

Terms used throughout:

- **Accessibility viewing**: the default way a phone shows its screen. The Accessibility service takes a screenshot about every 400 ms (`AccessibilityScreenCapture.kt`).
- **Smooth sharing**: Android's screen-sharing permission (MediaProjection), turned on in the phone's *Permissions & Access* screen (`ScreenCaptureService.kt`).
- **Viewer**: the PC window that shows the phone (`PhoneViewWindow`).
- **Picture**: one captured image of the phone's screen. **Keyframe**: a picture that can be shown without any earlier one.

## 1. Goal

Viewing a phone from the PC is about a second behind and choppy next to Phone Link. When this plan is done:

- With smooth sharing on, the viewer shows 30 pictures a second, about 0.1–0.2 s behind the phone through the relay. Today it is at most 10 a second, 0.2 s behind at best and well over a second on a slow upload.
- The PC user can switch a phone to smooth sharing from the viewer, instead of walking to the phone's Permissions screen.
- Accessibility viewing keeps its rate, because Android caps it, but loses some delay and stops sending pictures that haven't changed.
- The viewer can show the real delay, so each step is judged by a number rather than by feel.
- Old and new apps keep working with each other, and the relay does not change.

## 2. What the investigation found

### 2.1 Two capture modes, and the slower one is probably what is in use

| | Accessibility viewing | Smooth sharing |
|---|---|---|
| Capture | One screenshot every 400 ms or more. Android refuses requests closer than 333 ms. | Android mirrors the screen into the app whenever it changes. |
| Sent as | A whole JPEG per picture (frame type `1`), quality 55, its shorter side at most 1080 px (so a phone on its side is sent 2340 × 1080, as sharp as upright). Every screenshot is sent, changed or not. | The same format, only when the screen changes |
| Rate | Up to 2.5 a second | Up to 10 a second |
| Turning it on | Once. It survives restarts. | On the phone, every time. Android 14+ asks for consent each session. Android 15 QPR1+ also ends it whenever the phone locks. |

The original proposal (video compression, then smaller queues, then a direct connection) speeds up smooth sharing only. Three things say the phones are usually in Accessibility viewing:

- On 2026-11-10 the S26 (SM-S942W, Android 17) was running `InputAccessibilityService` and `ConnectionService` but not `ScreenCaptureService`, and `dumpsys media_projection` showed no session.
- The S26 is on Android 17, so smooth sharing ends at every lock and needs a new consent tap each time.
- Smooth sharing can only be started on the phone itself.

In Accessibility viewing a delay near one second comes from the screenshot rate, not from queues (section 2.2). So the plan adds two things the proposal did not have: a way to start smooth sharing from the PC (Phase 1), and a measurement step that says which mode a session is in and where its time goes (Phase 0).

### 2.2 Where the time goes

These are estimates from reading the code. Phase 0 replaces them with measurements.

| Step | Accessibility viewing | Smooth sharing today | Smooth sharing with video (target) |
|---|---|---|---|
| Waiting for the next capture | 0–400 ms | 0–100 ms | 0–33 ms |
| Capture and compression on the phone | 80–150 ms | 40–80 ms | 10–25 ms |
| Waiting in send queues | Little on a fast upload | None on a fast upload. On a slow one, seconds (see 2.3, "the phone cannot see most of its own queue"). | About a tenth of a second at most |
| Upload of one picture | 25–120 ms (60–150 KB) | 25–120 ms | A few ms |
| Relay, download, decode, draw | 50–100 ms | 50–100 ms | 50–100 ms |
| **Total** | **0.35–0.75 s** at 2.5 pictures a second | **0.15–0.3 s**, or seconds on a slow upload | **0.1–0.2 s** at 30 pictures a second |

A click adds its own trip to the phone (50–100 ms) and the phone's animation, which Accessibility viewing shows at 2.5 pictures a second. That is the "about a second" feel.

### 2.3 Other facts checked

- **The phone cannot see most of its own queue.** The app skips a picture when OkHttp's queue holds more than 300 KB (`ConnectionManager.kt:114`), but OkHttp's figure leaves out what the operating system has buffered. The S20's send buffer starts at 1 MB and can grow to 4 MB (`/proc/sys/net/ipv4/tcp_wmem` reads `524288 1048576 4194304`). So on an upload slower than the stream, about a megabyte piles up where the app can't see it, before the 300 KB limit notices anything. At 5 Mbit/s a megabyte is 1.6 s. The relay has the same blind spot: its 256 KiB limit (`server.js:324`) measures only its own socket to the Traefik proxy in front of it. This is why decision D3 measures arrival at the viewer instead. The S26's buffer sizes were not read; adb dropped.
- **Both test phones can encode the full portrait screen as H.264 in hardware.** S20 (Exynos 990, Android 13, 1080×2400): `OMX.Exynos.AVC.Encoder`, up to 8192×8192. S26 (SM8850, Android 17, 1080×2340): `c2.qti.avc.encoder`, up to 8192×8192, VBR and CBR. Both take sizes in steps of 2 pixels. Read from the phones' codec lists over adb.
- **Windows' built-in H.264 decoder may not take a picture taller than 2304 px.** Microsoft documents "up to level 5.1" and "maximum resolution 4096 × 2304". A phone picture is 2340 or 2400 px tall. It probably works: Firefox treats the limit as a total pixel count since portrait videos taller than the documented height were found to decode (Mozilla bug 1422239), though an 8192×1152 video still failed. Check C1 (section 7) settles it before any decoder code is written, and the fallback is designed in (decision D9).
- **The phone's connection delays small packets.** OkHttp 4.12 never sets `TCP_NODELAY`, so Nagle's algorithm is on for the phone's socket (`ConnectionManager.kt:51`). A small picture or control message can wait up to one round trip for the previous packet's acknowledgement. The relay (`ws`) and the PC (.NET) already turn Nagle off.
- **A binary frame names no sender.** The relay adds `from` to JSON only, so the viewer draws type `1` frames from any device that holds the session key, approved or not. Feeding such frames to a video decoder would widen that. Decision D7 closes it for the new frame type.
- **On the PC, messages and pictures arrive on different threads.** JSON is posted to the UI thread (`MainWindow.xaml.cs:719`); binary frames are handled on the socket thread (`MainWindow.xaml.cs:1054`). Anything a picture depends on from a message just before it, such as a stream id, has to travel with the pictures, not through the UI thread. `fs-begin` is already special-cased there for the same reason.
- **The relay needs no change.** `relayBinary` (`server.js:306`) treats every frame type except file chunks alike.
- **The other direction already does this.** `H264Encoder.cs` (PC), `H264Decoder.kt` (phone) and frame type `4` are the model for conventions: one Annex B access unit per frame, SPS and PPS ahead of every keyframe, a sequence number, and `request-keyframe` after a gap.
- **Testing on real phones is awkward today.** The debug build has the same application id as the release app, so it replaces it and loses the phone's identity. No emulator image is installed. Both phones are reachable over adb.

## 3. Phases at a glance

| Phase | What changes for the person viewing | Needs | Size |
|---|---|---|---|
| 0. Measure, repair, and hold back properly | A readout of the real delay. Less delay in both modes, most of all on a slow upload. A turned phone shows correctly in smooth sharing. | – | Medium |
| 1. Smooth sharing from the PC | A button in the viewer switches the phone to smooth sharing and back. | 0 | Small to medium |
| 2. Video instead of JPEGs | In smooth sharing: 30 pictures a second, about 0.1–0.2 s behind | 0 | Large |
| 3. Staying sharp on a slow link | On a poor connection the picture gets softer instead of slower | 2 | Medium |
| 4. Follow-ups | Optional items, each on its own | 2 | Small to medium each |
| 5. Direct connection on the same network | Skips the relay, saving the last 0.1–0.2 s | 2 | Large, needs its own plan |

**Order of work**, after Theo's answers (section 12):

1. Step 0.1 alone, the debug build that installs beside the release app. All three plans use it for their phone checks.
2. `PHONE-MENU-PLAN.md`, so this plan's controls go into its `⋯` menu.
3. `PHONE-UNLOCK-PLAN.md`, which was Phase U here. It needs none of this plan's phases.
4. The rest of Phase 0.
5. Phase 1.
6. Phase 2.
7. Step 4.1, Accessibility pictures through the video encoder. It moved up from the optional list.
8. Phase 3, then whatever else of Phase 4 is wanted.

The phone is viewed from elsewhere about half the time, often locked. Smooth sharing cannot start on a locked phone and ends when one locks, so **Accessibility viewing is the main mode** and smooth sharing is the better mode for when the phone is at hand. That is why unlocking and step 4.1 moved up. Phase 0 already serves both modes.

Phase 0 comes first because everything after it is judged by its readout and built on its repairs. Phases 1 and 2 both need Phase 0 and not each other. Each phase can be released on its own once the ones it needs are out.

After Phase 0, compare the measured numbers with section 2.2. If smooth sharing is already within 0.2 s on the home network, Phase 2 still gives 30 pictures a second and a fraction of the data, but little further delay. Say so before starting it.

### 3.1 Where this meets the other two plans

`PHONE-MENU-PLAN.md` adds a `⋯` menu to the viewer's title bar (Back, Home, Recents, volume, rotation). Neither plan needs the other, but they touch the same places:

- **The same window.** The menu plan gives `PhoneViewWindow` its own title bar and puts the picture in a host element. This plan changes how the picture is drawn and adds overlays on it. Build them one after the other, not side by side.
- **Controls.** The menu is built first (decided 2026-11-10), so *Smooth video* and *Stats* are rows in it (steps 0.3 and 1.4) and this plan draws no buttons over the picture. F8 stays as a shortcut for Stats. The window checks keys in a fixed order: F8, then Esc while the menu is open, then PIN digits while the unlock overlay is up, then typing into the phone.
- **Layers.** The menu plan's picture host holds, bottom to top: the picture, the unlock plan's overlay and captions, the Stats readout.
- **Rotation in smooth sharing.** Both plans contained the same fix to `ScreenCaptureService`. It is built in the menu plan's Phase 3, which comes first. Step 0.6 here only checks it is there.
- **Window size.** Both say to re-fit the window only when the picture's shape changes. The menu plan's version is fuller (keep the long side, cap to the work area); use that one.
- **Messages.** Both add viewer-to-phone messages, with no names in common. Its `phone-state` describes the phone's controls; `screen-info` here describes the stream. They stay separate.
- **Tests.** One Windows test project for the phone viewer is enough. This plan adds its cases to the menu plan's `RemoteDesktopWin.PhoneViewTests`.
- **Keeping the phone awake.** Step 1.5 here is the only place it happens, and only in smooth sharing. The menu plan's optional row for it is dropped, and the unlock plan decided that Accessibility viewing does not hold the screen on.
- **Waking the phone.** Only real input wakes a sleeping phone. `frame-ack`, `diagnostic-ping`, `request-keyframe`, `request-smooth` and `stop-smooth` do not, and nor do the menu plan's `lock`, `volume`, `rotate` and `get-phone-state`. None of them counts as input for the 10-minute limit in step 1.5 either.
- **Phone checks.** Step 0.1 below is built before anything else, so the other two plans' phone checks use it too.

`PHONE-UNLOCK-PLAN.md` wakes a sleeping phone and draws the unlock screen that Android blanks out of the picture. The Phase U stub in section 6 lists what it means for this plan.

## 4. Design decisions

**D1. The encoder is fed through an OpenGL step, not straight from the screen.**
The screen is mirrored into a texture (`SurfaceTexture`), and the app draws that texture onto the encoder's input surface when it chooses to. Feeding the encoder directly is less code, and it is what scrcpy does, but then every screen change is encoded whether or not the network can take it. The GL step lets the app:

- encode only when there is room, and then the newest picture, so the last change on screen always arrives (what `FramePacer` guarantees for JPEGs today);
- encode the same picture again on a still screen, for a keyframe when a viewer joins or reports a gap;
- cap the rate on every Android version (the encoder's own cap needs Android 10);
- turn the picture sideways if Windows cannot decode tall ones (D9).

This is the arrangement WebRTC uses for Android screen sharing.

**D2. An encoded frame is never dropped on the phone.**
Dropping one breaks every frame after it until the next keyframe, and a keyframe is the largest frame there is. So the phone holds back before encoding, never after. That goes for keyframes and repeats too: every encode waits for room.

**D3. "Room" is judged by what the viewer says has arrived, not by the phone's own queue.**
The phone's queue figure misses a megabyte or more of system buffer (2.3), and the relay's misses the proxy. So the viewer acknowledges each picture, and the phone sends the next one only while nothing has been waiting for its acknowledgement much longer than usual. That bounds the wait wherever the queue is hiding. It also tells the phone when the last picture it sent was lost, and it is the signal Phase 3 lowers the bitrate by.

**D4. A new frame type `5` for pictures from a phone, carrying either JPEG or H.264.**
Type `4` is not reused: its header describes a desktop region, and older Android viewers decode any type `4` they receive, which an older relay that broadcasts would deliver to them. Type `5` has a header with a stream id, sequence number, size and capture time, so JPEG and video are measured and acknowledged the same way. Type `1` stays for viewers that don't know type `5`.

**D5. One stream for all viewers.**
The phone picks the best format every current viewer understands, as the PC host does. Encoding twice for mixed viewers is not worth the battery.

**D6. The PC decodes with Windows' own H.264 decoder, in software, on its own thread.**
It is a Media Foundation transform, reached through `Vortice.MediaFoundation`, which the app already ships for `H264Encoder.cs`. No new dependency. The decoder outputs NV12; the app converts to BGRA and draws into a `WriteableBitmap`. Software decoding of one phone-sized stream is cheap, and hardware decoding would add Direct3D plumbing for no visible gain. FFmpeg and OpenH264 were rejected as new native dependencies. HEVC needs a paid Windows extension.

**D7. A viewer only draws type `5` pictures that carry the stream id its phone gave it.**
- The phone picks a random 64-bit id when a stream starts and tells each viewer in a `screen-info` addressed to that viewer. The relay delivers an addressed message to that one device, and the phone streams nothing while a device it hasn't approved is present.
- A viewer accepts an id only from a phone it has itself approved. Today the PC will open a viewer on any device in the list and believe its `screen-info`, approved or not.
- While a phone has announced a stream, its viewer ignores type `1` frames. Otherwise the old unauthenticated path would stay open beside the new one.
- When streaming pauses with viewers still there, the phone tells them so with `paused: true`, and the id is dead. The next stream gets a new one. A paused viewer draws nothing, of either type, until the next `screen-info`. The plan first had it fall back to type `1` here, but the usual reason for a pause is an unapproved device joining the session, and that device could then have drawn type `1` frames into the viewer for as long as it stayed.
- A stream that ends because an older viewer joined is different: pictures keep coming, as type `1`, so the viewer is told there is no stream and is not told `paused`.
- A viewer draws each sequence number of a stream once. A number it has seen is a frame played back.
- What this does not cover: phones on older apps still send type `1`, which anyone with the session key can imitate, as today. And all of one phone's viewers share an id, so one approved viewer could draw into another's window.

**D8. Keyframes are sent when needed, not on a timer.**
The connection is TCP, so a frame is only lost when the relay drops one for a backed-up viewer. Then:
- the viewer sees the gap in the sequence numbers and sends `request-keyframe`, which phones will now honour, at most once a second in total;
- if the lost frame was the last one sent, no gap ever shows, but the phone sees its acknowledgement missing and sends the picture again as a keyframe;
- as a safety net against a decoder that has gone wrong without noticing, the phone asks for a keyframe when 30 s have passed since the last one and a changed picture is about to be encoded anyway. The encoder's own interval setting can't do this: it counts frames, not seconds.

A still screen sends nothing.

**D9. If Windows cannot decode tall pictures, the phone sends them sideways.**
The GL step turns the picture 90°, a header flag says so, and the PC turns it back while converting colours. Only built if check C1 fails.

**D10. On a slow link the bitrate goes down, not the resolution.**
Text on a phone screen needs its pixels. Until Phase 3 lowers the bitrate, a slow link shows fewer pictures a second at full quality, still on time.

**D11. Smooth sharing can be asked for from the PC, and consent is never automated.**
The phone shows Android's own prompt. Someone taps it, on the phone or through the viewer. The app never presses it itself. That is a rule, not a limit of the platform: once the unlock plan is built the Accessibility service can read window content, but its reader runs only while the phone is locked, and `request-smooth` is refused on a locked phone, so the two never meet.

**Not in this plan:** audio, changes to how a PC is viewed, changes to the relay, UDP or WebRTC transport.

## 5. Protocol changes

All additions. `PROTOCOL.md` is updated in the phase that first uses each one; every phase below has a docs step.

### 5.1 Frame type `5`: phone frame

Decrypted payload, integers big-endian. The associated data for encryption is the frame-type byte, as for every other type, so both `Crypto` classes gain channel `5`.

```
[stream u64][seq u32][flags u8][width u16][height u16][captureMs u32][picture bytes]
```

| Field | Meaning |
|---|---|
| `stream` | Random id of this stream, announced in `screen-info`. |
| `seq` | Starts at 0 for each stream and goes up by one per frame sent. A gap means a frame was lost. |
| `flags` bit 0 | Keyframe. Always set for JPEG. For H.264, set when the access unit starts with SPS and PPS and holds an IDR picture. |
| `flags` bits 1–2 | Format: `0` JPEG, `1` H.264 (one Annex B access unit). `2` and `3` are reserved; a viewer ignores frames in a format it did not offer. |
| `flags` bit 3 | Sideways: the picture is the screen turned 90° clockwise and the viewer turns it back. Reserved until D9 is needed. |
| `flags` bit 4 | Repeat: the same screen content as an earlier frame, sent again as a keyframe or for sharpness. Viewers leave it out of delay figures. |
| `width`, `height` | Size of the picture as encoded, in pixels. |
| `captureMs` | Low 32 bits of the phone's uptime clock, in ms, when the content was captured. Android's own stamp on the picture is used when it is at most 10 s behind that clock and not ahead of it; otherwise the time the picture reached the app (`PhoneFrame.captureTime`). |

### 5.2 JSON messages

| Message | Direction | Change | Phase |
|---|---|---|---|
| `start-view` | viewer → phone | New optional `codecs`: array of `"jpeg"` and `"h264"`. Its presence means the viewer understands type `5` and will send `frame-ack`. A viewer offers it only to a phone it has approved. A later `start-view` from the same viewer replaces its list, which is how a viewer gives up on H.264 mid-session; every re-send carries the current list. PC hosts ignore it. Every viewer lists `"jpeg"`; one that did not would be sent type `1`. | 0, built |
| `screen-info` | phone → each viewer | Addressed to each viewer rather than broadcast. New fields: `stream` (16 hex digits; left out when there is no type `5` stream), `codec` (`"jpeg"` or `"h264"`, with `stream`), `capture` (`"accessibility"` or `"screen-share"`), `paused` (true when nothing is being sent for now; see D7). Sent again whenever one of them changes, but only to viewers that sent `codecs`; older viewers get it exactly when they do today, with only the fields it has today, because a v1.0.53 PC resets its window size on every one. | 0, built |
| `frame-ack` | viewer → phone | New. `stream`, `seq`: the highest sequence number received of that stream. At most one per 30 ms. A frame that arrives inside the 30 ms is acknowledged when they are up, so the last frame of a burst is always acknowledged; step 0.7 would otherwise wait on it for nothing. | 0, built |
| `diagnostic-ping` / `diagnostic-pong` | viewer ↔ phone | Phones now answer. The pong carries `nonce`, `hostTimeMs` (the clock `captureMs` uses, in full, read as the ping is handled), `hostQueueBytes`, `codec`, `capture`, `fps` (sent in the last second) and `bitrate` (current target, H.264 only; not sent until Phase 2). | 0, built |
| `screen-info` | phone → each viewer | New field `smooth`: `"on"`, `"available"` or `"unavailable"`. A Phase 0 phone never sends it, so a newer PC shows no button the phone can't act on. | 1 |
| `request-smooth` | viewer → phone | New. Asks the phone to show its screen-sharing prompt. | 1 |
| `stop-smooth` | viewer → phone | New. Ends smooth sharing; Accessibility viewing takes over. | 1 |
| `smooth-status` | phone → viewer | New. `state`: `prompting`, `started`, `declined`, `ended` or `unavailable`, with a human-readable `reason` for the last two, e.g. "the phone was locked" or "Unlock the phone first". | 1 |
| `request-keyframe` | viewer → phone | Phones now honour it, at most one a second in total across viewers. | 2 |

**Who may send them.** The phone acts on `frame-ack`, `diagnostic-ping`, `request-keyframe`, `request-smooth` and `stop-smooth` only from a peer that is currently viewing it. That already means approved, control switched on, and the *Allow other phones* rule passed. They join the list in `PROTOCOL.md` of messages dropped otherwise. A dropped request gets no answer, so a viewer gives up waiting for `smooth-status` after 5 s and says the phone didn't answer.

### 5.3 Which format a phone sends

1. Any viewer without `codecs`, or whose list has nothing this phone sends: type `1`, as today, for everyone, and `screen-info` carries no `stream`.
2. Every viewer lists `h264`, the phone is in smooth sharing, and its encoder works: type `5` H.264.
3. Otherwise: type `5` JPEG.

The choice is made again whenever a viewer joins, leaves or re-sends `start-view`, and when the capture mode changes.

### 5.4 When a stream starts and ends

- **Starts** when streaming begins or resumes, and when the format or the capture mode changes. The phone picks a new id, sends `screen-info` with it, then a keyframe. A viewer that joins a running stream is told its id and nothing else changes; a turned phone keeps its stream.
- **Ends** when the last viewer leaves, and when the gate closes (an unapproved device joined, control was switched off, the connection dropped). Viewers that are still viewing and sent `codecs` are sent `screen-info` without `stream` and with `paused: true`. Switching control off and a dropped connection leave nobody viewing, so nobody is told.
- **Ends** also when a viewer that needs type `1` joins. The others are sent `screen-info` without `stream`, and not paused, ahead of the first type `1` frame.
- A picture the send queue refuses takes no sequence number, so a viewer never counts a gap for it.
- A frame is never sent to an empty list of recipients. Today `sendBinary` broadcasts in that case (`ConnectionManager.kt:355`).

## 6. The phases

Conventions for all phases:

- Everything that decides something (pacing, format choice, acknowledgements, keyframe requests, bitrate steps, header layout, gap handling) goes in plain classes with unit tests, as `FramePacer` is. The classes that touch MediaCodec, OpenGL or Media Foundation hold no decisions and are checked on real devices.
- The frame gate does not move: a phone sends nothing unless `PhoneScreenCapture.maySendFrames` is true, checked again just before each send.
- The gate closes for a moment every time any device joins the session, until that device is recognised (`PeerAuth.onPeerJoined`). Nothing expensive may be torn down for a pause that short.

### Phase 0. Measure, repair, and hold back properly

**0.1 Let the debug build sit beside the release app.** Built first of everything, before the menu plan (`PHONE-PLANS-ORDER.md`, step 0).
In `android/app/build.gradle.kts`, give the `debug` build type `applicationIdSuffix = ".debug"`, a `-debug` version suffix and its own label ("Remote Desktop (debug)"). Nothing in the code depends on the bare package name: the FileProvider authority and the settings link already use `packageName`. The debug app is then a separate device with its own identity, Accessibility switch and approvals. When it first joins a session, every other device pauses its streams and file transfers until it has approved the newcomer, so approve it on each one straight away.

**0.2 Type `5` with JPEG inside, and the messages around it.** Built 2026-11-11, without a phone: unit tests on both sides, the shared vectors, and the real window fed by a real feed off screen. Pictures flowing end to end are first seen in the session that closes Phase 0.

Phone, as a host:
- `Crypto.kt`: channel `5`.
- New `PhoneFrame.kt`: builds and parses the header. Plain JVM. It holds two test vectors, whole frames as hex, that `PhoneFrame.cs` holds too; each app builds them and reads them back in its tests, and the first is also encrypted on channel `5` in all three `Crypto` self-tests.
- `PhoneScreenCapture.kt`: keep each viewer's `codecs`, the stream id and the sequence number; choose the format (5.3); start and end streams (5.4); one `send` used by both capture classes, so the gate, the recipients and the header live in one place, and one `sendScreenInfo` that they call where they have always sent `screen-info`. The "started viewing" toast and the wake were already only for a viewer that was not already viewing (the unlock plan built both).
- `ConnectionManager.kt`: read `codecs` from `start-view`; answer `diagnostic-ping`; take `frame-ack`, which is remembered and not yet acted on (0.7); apply the rule in 5.2 about who may send them.
- `ScreenCaptureService.kt` and `AccessibilityScreenCapture.kt`: send through the shared `send`, with the capture time (`Image.timestamp`, `ScreenshotResult.timestamp`). Whether both sit just before `SystemClock.uptimeMillis()` has not been checked on a device. `PhoneFrame.captureTime` takes Android's stamp only when it does, and the time the picture arrived otherwise, so a stamp on another clock costs accuracy and not sense. The readout (0.3) will show which it is.

PC:
- `Crypto.cs`: channel `5`.
- New `PhoneFrame.cs` (parse, and build for the tests) and `PhoneVideoStream.cs` (current stream id, sequence gaps, per-second figures, and `AckPacer`, which paces `frame-ack`). Both plain, both tested.
- New `PhoneFeed.cs`, owned by `MainWindow` in place of `_phoneFrames`. The socket thread hands it, in the order they arrive:
  - what a `screen-info` says about the stream, read in `OnJsonReceived` *before* that message is posted to the UI thread, and taken only when it comes from the phone being viewed and this PC has approved that phone;
  - type `5` frames;
  - type `1` frames.

  Each is dealt with there and then, on the socket thread, under one lock: the announcement sets the current stream; a frame is checked against it (id, sequence number, type `1` only while no stream is announced and not paused), counted and acknowledged. That is a few comparisons, and it is what keeps the order: if the announcement went through the UI thread instead, the first frame of every stream would overtake it and be thrown away as "wrong stream". The plan first gave the stream to the consumer thread, but then the socket thread could not know which frames to acknowledge.

  What passes is left for the feed's own thread, newest only while every frame is a whole picture. That thread decodes the JPEG and looks whether it is black (`LockOverlay`, ruling 15), then leaves the result for the UI thread, which only assigns it. This is step 0.5's "decode off the UI thread", done here because the feed made it natural. The same thread sends an acknowledgement that was held back for the 30 ms rule.
- `PhoneViewWindow`: `start-view` carries `codecs: ["jpeg"]` when the PC has approved the phone, on every send including `ResendStartView`, and is sent again when the PC approves the phone, or stops approving it, while the window is open. The window already re-fits only when the picture's shape changes (the menu plan built it).

Phone, as a viewer of another phone (`PhoneViewerActivity`): offer `codecs: ["jpeg"]` to a phone this one has approved, draw type `5` JPEG with the announced id, draw type `1` only while none is announced, and acknowledge. `ConnectionManager` has listeners that run on the network thread (`networkJsonListeners`), and the activity reads the announced id there, in order with the frames. Without this, a phone watching alongside a PC would put both back on type `1`.

**0.3 The readout.**
A *Stats* row in the `⋯` menu toggles a small overlay, and so does F8. No F-key is forwarded to the phone, so F8 is free. It shows: capture mode, format, size, pictures a second, kbit/s, delay (latest, average and worst over the last second), round trip, the phone's queue, and gaps. Shift+F8 copies the last 60 seconds as text, so numbers can be pasted into a message or into section 11.

How the delay is worked out:
- The PC uses a steady clock (`Stopwatch`), and notes times on the socket thread, not in a UI handler.
- Every 2 s while the overlay is open it sends `diagnostic-ping` and notes when. The pong's `hostTimeMs` minus that send time is the difference between the two clocks plus the ping's travel time.
- The clock difference is taken as the smallest such value in the last minute, minus half the smallest round trip in the last minute. Only the ping's own trip enters this, and pings travel towards the phone, the direction video does not load. Halving a round trip measured through the phone's busy send queue would understate the delay by half that queue.
- Delay = (PC clock when the picture is drawn + clock difference) − `captureMs`, in 32-bit wrapping arithmetic.
- It is shown with "±" half the smallest round trip.

**0.4 Record the baseline** in section 11, before changing anything else: both phones, both modes. Also record:
- `tcp_wmem` on the S26 (the S20's is in 2.3);
- one run with the phone's upload limited to about 2 Mbit/s (a router setting or a phone hotspot), comparing the readout's delay with the phone's reported queue. A long delay beside a small queue confirms the system buffer is where pictures wait.

**0.5 Small fixes.** Measure each one before and after.
- **Turn Nagle off on the phone.** Give the `OkHttpClient` a `SocketFactory` that sets `tcpNoDelay` on each socket it creates. OkHttp creates the raw socket through the factory and layers TLS over it.
- **Decode JPEGs off the PC's UI thread.** Built with step 0.2: the feed's own thread decodes (newest only, as `FrameMailbox(1)` did), freezes the bitmap and runs the unlock plan's black-picture test, and the UI thread only assigns the picture. Still to do here: measure it. A flat test picture of 1080 × 2340 took 5 ms; a real screen will take longer.
- **Don't send a screenshot that hasn't changed.** Accessibility viewing sends every screenshot, about 100 KB two and a half times a second, even on a still screen. On a slow upload a changed picture then waits behind unchanged ones. Compare each screenshot with the last one sent (`Bitmap.sameAs`) and skip it if equal, except when a viewer has just joined or the size changed.
- **For viewers on older apps**, which can't acknowledge, lower the skip threshold from 300 KB to about 64 KB. It costs nothing, but helps little on a slow upload, because the system buffer in front of it is far larger.

**0.6 Repair the capture service.**
- **Follow the phone when it turns.** Already built by the time this step is reached: it is in PHONE-MENU-PLAN.md, Phase 3 (display listener, `onCapturedContentResize` on API 34+, `VirtualDisplay.resize`, a new `ImageReader`, a new `screen-info`). Here, only confirm with the readout that a turned phone fills the window in smooth sharing, and make sure the size change goes out through the new shared `send` of step 0.2.
- **Stop mirroring when nobody is viewing.** Today the virtual display keeps drawing into the `ImageReader` whether or not anyone views, and each picture is thrown away. Call `setSurface(null)` once streaming has been off for about 5 s, not at once, because of the short pauses described above. Attach it again when streaming starts. Attaching also makes Android draw one picture straight away, so a new viewer sees the screen without waiting for it to change.

**0.7 Hold back by what has arrived** (D3).
Viewers acknowledge from step 0.2 on (built): for each type `5` frame of the current stream, `frame-ack` with the highest `seq` received, at most one per 30 ms, from the thread that received the frame; one held back by the 30 ms goes a moment later, when they are up. That part had to ship with 0.2, so that every viewer that offers `codecs` also acknowledges. The phone already checks and remembers each one (`PhoneScreenCapture.onFrameAck`). This step is the phone acting on it. If the baseline shows no queueing on the home network, it can wait and become the first step of Phase 2, which cannot do without it.

Phone: a new plain class, `InFlight`.
- It notes when each frame was handed to the socket.
- When an acknowledgement arrives, it notes how long that frame took. The shortest such time in the last 60 s is "usual".
- There is **room** when the app's own queue is nearly empty and no frame has been waiting for its acknowledgement longer than usual plus 100 ms. Before the first acknowledgement, two frames may be in flight.
- With several viewers, the slowest one that is still answering sets the pace. A viewer that has acknowledged nothing for 2 s stops counting until it answers again.
- If the newest frame is still unacknowledged after 1 s and nothing has been sent since, the picture is sent again. Further tries wait 2 s, 4 s, then 8 s.
- Both capture classes ask `InFlight` for room when every viewer acknowledges, and keep the queue rule otherwise.

The 100 ms is a starting value; tune it with the readout. Known and unchanged: while a file is being downloaded from the phone, its chunks fill the same queue and the picture freezes.

**0.8 Docs.** `PROTOCOL.md`: the frame table, `screen-info`, `frame-ack`, the phone's pong, the paragraph on the Android sender's queue (it says 300 KB) and the list of encryption channels. `android/README.md`: the debug build, and that Accessibility viewing no longer sends unchanged pictures. `windows/README.md`: F8.

**Tests.** Those that belong to step 0.2 are built: on the phone everything listed except `InFlight` and the unchanged screenshot (`PhoneFrameTest`, `PhoneStreamTest`, `PhoneScreenCaptureTest`, `PhoneViewerActivityTest`); on the PC everything except the delay arithmetic, which comes with the readout, plus the feed's own cases (newest only, the last frame of a burst acknowledged, a phone the PC has not approved, a pause); and the relay's.
- Phone:
  - header round trip;
  - format choice for every mix of viewers; a viewer without `codecs` still gets a bare type `1` JPEG, byte for byte as now;
  - `screen-info` is addressed, carries the id only for a type `5` stream, and loses it when streaming pauses;
  - extra `screen-info`s go only to viewers that sent `codecs`;
  - `diagnostic-ping` and `frame-ack` from a peer that is not viewing do nothing;
  - nothing is ever sent to an empty recipient list;
  - `InFlight`: room, the slowest-viewer rule, the quiet-viewer rule, the resend and its back-off;
  - an unchanged screenshot is skipped, and a new viewer still gets one;
  - a size change sends new dimensions before the next picture (as `lateFramesAreDroppedAndRotationResendsDimensionsBeforeJpeg` checks for Accessibility viewing);
  - `PhoneViewerActivityTest`: type `5` with the right id is drawn, the wrong id is not, type `1` is ignored while a stream is announced.
  - Existing tests to update: `AllowOtherPhonesTest.videoIsSkippedLongBeforeFileChunks`, and `PhoneScreenCaptureTest`'s connection shadow, which asserts channel `1`.
- PC: add to the menu plan's `RemoteDesktopWin.PhoneViewTests`, linking the plain classes. Cases:
  - header parsing;
  - frames that directly follow their announcement are accepted;
  - a frame with the wrong id is ignored;
  - an announcement from a phone the PC has not approved is ignored;
  - type `1` is refused while an id is current;
  - a sequence gap is counted;
  - the delay arithmetic, including the 32-bit clock wrapping and a pong that was held up;
  - `ResendStartView` keeps `codecs`.
- Relay: one case in `server/test.mjs` pinning that an addressed type `5` frame reaches only its recipients and falls under the video buffer limit. Built: a viewer that stops reading is sent 24 MiB of each kind, and about 0.7 MB of type `5` reaches it against 9 MB of type `2`.

**Done when** the readout shows a believable delay for both modes on both phones, section 11 holds numbers from before and after, and old and new apps work together in both directions (section 9). If 0.7 is done in this phase, the limited-upload run also stays within about 0.3 s of the unlimited one.

### Phase U. Unlocking the phone from the PC

Moved to `PHONE-UNLOCK-PLAN.md`, which is the plan for waking and unlocking a phone. It is built after the menu plan and before Phase 0 here (`PHONE-PLANS-ORDER.md`). Checks C4 and C5 in section 7 stay here as the record of what was found.

What that plan changed from the phase that stood here: one `lock-state` message, not `locked` and `screenOn` fields on `screen-info` plus a `lock-keys` message; and a typed PIN becomes `tap`s made by the viewer, not `text` that the phone turns into taps. Theo chose option A (the phone reports where its unlock area is) on 2026-11-10.

What it means for the phases below:

- A locked phone can be unlocked from the viewer, so "Unlock the phone first" (step 1.2) is something the person at the PC can do.
- The Accessibility service will be able to read window content, on the lock screen only (see D11).
- The viewer has a caption and overlay layer above the picture. The Stats readout (0.3) sits above that.
- The viewer tests each decoded picture for "nearly all black" (`LockOverlay.cs`). Steps 0.5 and 2.4 move decoding, and the call moves with it.
- Accessibility viewing does not keep the phone awake. Step 1.5 stays limited to smooth sharing.

### Phase 1. Smooth sharing from the PC

Run check C2 (section 7) first.

**1.1 Ask for the whole screen.**
`PermissionsActivity.kt:98` calls `createScreenCaptureIntent()` with no arguments. On Android 14+ that offers "a single app", and a single-app share breaks tap positions, which are fractions of the whole screen. Use `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` on API 34+, from one helper that Permissions and step 1.2 share. Android's documentation warns that a manufacturer can override this, so C2 checks it on the Samsungs.

**1.2 Show the prompt on request.**
New `ScreenShareRequestActivity`: a plain `Activity` (as `ReceivedFileActivity` and `CopyToPcActivity` are) with the translucent platform theme, out of recents, in its own task. It must **not** be `noHistory`: Android never delivers an activity result to a `noHistory` activity, and the result is the consent. It launches the consent intent, starts `ScreenCaptureService` with the result, and finishes itself in the result callback.

`ConnectionManager` handles `request-smooth` (5.2 says from whom):
- phone locked: `smooth-status unavailable`, "Unlock the phone first";
- already sharing: `started`;
- otherwise start the activity from the Accessibility service and send `prompting`, then `started` or `declined`.

One prompt at a time; a repeat request within 10 s is ignored. Declining, backing out and the screen turning off while the prompt is up must each end in a `smooth-status`.

Android lets an app whose service the system has bound, as it binds the Accessibility service, start an activity with no window open. If a phone blocks it anyway (C2), fall back to a notification, "Your PC is asking for smooth video. Tap to allow", which is always allowed to open the activity.

**1.3 Stop on request, and say why it ended.**
`stop-smooth` stops `ScreenCaptureService`. Whenever smooth sharing ends, the phone sends `smooth-status ended` with a reason: "stopped from the PC", "stopped on the phone", or "the phone was locked" when the lock screen is up at that moment. Android's `onStop` gives no reason itself.

**1.4 The menu row.**
A *Smooth video* row in the viewer's `⋯` menu, shown when `screen-info` carries `smooth`, and greyed with the reason when it says `unavailable`. Off, its tooltip reads: "Asks the phone to share its screen as video. Approve the prompt on the phone; you can click it here." Choosing it sends `request-smooth`. The prompt then appears in the picture and can be clicked like anything else. On, the row is ticked, and choosing it sends `stop-smooth`: Android 15+ blanks password fields and one-time codes during screen sharing, so there are moments to switch back. When smooth sharing ends by itself, the viewer says why in a toast. Like everything in that menu, the row must not take keyboard focus, because the window types into the phone whenever it has focus.

**1.5 Keep the phone awake while it is viewed in smooth sharing.** Agreed by Theo on 2026-11-10, with the 10-minute limit.
On Android 15 QPR1 and later, a phone that sleeps on its usual timeout and locks ends smooth sharing, and smooth sharing cannot be started again until the phone is unlocked and the prompt approved. So a session would drop back to Accessibility viewing whenever the person stops clicking for a minute, and the person would have to unlock the phone from the viewer and approve the prompt all over again.
- While at least one viewer is viewing in smooth sharing, hold the screen on, dimmed if the phone allows: a screen wake lock held by `ScreenCaptureService` (the `WAKE_LOCK` permission is already declared for `ScreenWaker`).
- Let go when the last such viewer stops, disconnects or loses approval.
- Let go when a viewer sends the menu plan's `lock`.
- Also let go after 10 minutes without any input from a viewer. Input means taps, drags, typing and the navigation buttons, not acknowledgements, pings or menu state requests. The PC sends `stop-view` only when its window closes, so without a limit a forgotten or minimised viewer would keep an unattended phone awake and unlocked for good.
- If C2 shows a wake lock no longer holds the screen on, use a 1×1 Accessibility overlay window of its own with `FLAG_KEEP_SCREEN_ON`.

**1.6 Docs.** `PROTOCOL.md`: the three new messages, `smooth`, and the list of gated messages. `android/README.md`: the Smooth screen sharing row and the paragraphs on it, what Android 15+ blanks, and keeping awake. `windows/README.md` and `README.md`: the button.

**Tests.**
- `request-smooth` and `stop-smooth` from a peer that is not viewing do nothing;
- a locked phone answers `unavailable`;
- a second request while one is open is ignored;
- Permissions and the request activity ask for the whole screen on API 34+;
- each way the prompt can end produces one `smooth-status`;
- the screen is held on only while a viewer is in smooth sharing, and released when the last one leaves and after 10 idle minutes;
- PC: the row appears only when the phone sends `smooth`; no answer within 5 s is reported.

**On the S26** (Android 17; see section 8 for the S20): the prompt appears with no app window open; it is visible in Accessibility viewing; a click in the viewer presses its button; smooth sharing starts and the viewer switches; choosing the row again switches back; locking the phone ends it and the viewer says so; the phone stays awake for five idle minutes while viewed and sleeps normally after the viewer closes.

**Done when** a PC user can go from opening the viewer to smooth sharing without touching an unlocked phone, or with one tap on the phone if C2 shows the prompt can't be clicked remotely.

### Phase 2. Video instead of JPEGs

Run check C1 first; it decides whether D9 is needed. Then C3. Step 0.7 must be in place.

**2.1 The encoder on the phone.** New `PhoneVideoEncoder.kt`, with the OpenGL part in its own class.

```
screen ─ VirtualDisplay ─► SurfaceTexture (GL texture)
                              │  new picture: updateTexImage(), tell the pacer
                              ▼
                           pacer: rate cap, room (0.7), keyframe wanted,
                                  repeats on a still screen
                              │  when due: draw the texture, set its timestamp, swap
                              ▼
                     encoder input surface ─► MediaCodec (H.264)
                              │  output callback, on its own thread
                              ▼
        prepend SPS/PPS to keyframes ─► type 5 header ─► ConnectionManager
```

- One thread owns the EGL context, the texture and all drawing. EGL config with `EGL_RECORDABLE_ANDROID`; `EGLExt.eglPresentationTimeANDROID` before each swap. Call `SurfaceTexture.setDefaultBufferSize` when the texture is created and on every resize, or pictures arrive 1×1.
- The pacer is `FramePacer<Unit>` at 33 ms, asking `InFlight` for room. Every encode waits for room: new pictures, keyframes and repeats alike.
- The capture time travels through the encoder as the presentation timestamp and comes back in the output buffer's info, which is where `captureMs` is read. Timestamps must always increase; a repeat uses the current time and sets the repeat flag.
- Encoder choice and settings are in appendix A. In short: hardware H.264, constant bitrate where offered, no B-frames, 30 pictures a second.
- Size: the capture size (`StreamSize`: shorter side at most 1080 px, so up to 2400 × 1080 on its side), even numbers. Ask `VideoCapabilities.areSizeAndRateSupported` first. If the encoder refuses or fails to start, try 0.75× and 0.5×, then give up and use JPEG.
- Keyframes, as D8 lists them: `PARAMETER_KEY_REQUEST_SYNC_FRAME`, then draw the current texture again even if the screen has not changed. `request-keyframe` is honoured at most once a second in total.
- A still screen: draw the current picture again 150 ms and 600 ms after the last change, each marked repeat, then nothing. This sharpens a picture that was encoded mid-motion, and pushes the last real frame out of an encoder that holds one back.
- The encoder is told SPS and PPS once, in a config buffer. Keep them and put them in front of every keyframe, unless the keyframe already starts with an SPS. `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` is not used: an encoder without it fails to configure.

**2.2 The capture service.** `ScreenCaptureService` keeps the projection and the virtual display and gains a second output next to the `ImageReader`. `setSurface` switches between them when the chosen format changes (5.3).
- A size change (0.6) rebuilds the encoder at the new size; its first frame is a keyframe.
- A short pause of the gate stops the pacer and nothing else. On resume the stream gets a new id and a keyframe from the same encoder.
- Any encoder or GL error tears the video output down and switches to JPEG. Two failures in one run of the service keep it on JPEG until the service restarts.
- An H.264 frame, once encoded, is always sent (D2). `sendBinary` must not refuse it for queue size.

**2.3 The decoder on the PC.** New `H264Decoder.cs` and `Nv12Converter.cs`.
- Create the Microsoft H.264 decoder by its class id (`CLSID_CMSH264DecoderMFT`), so it is the synchronous software one and not whatever enumerates first. Media Foundation must be started first; `H264Encoder.cs` keeps its start-up call private, so share it.
- Set `CODECAPI_AVLowLatencyMode` to 1 as a 32-bit integer; it is the GUID `H264Encoder.cs` calls `MFLowLatency`. No Direct3D manager, so it decodes in software.
- Input: one access unit per sample, the frame size from the header, and `seq` as the sample time, so a picture coming out can be matched to the frame that went in. Output: NV12. `MF_E_TRANSFORM_STREAM_CHANGE` means "choose the output type again", which happens on the first frame and whenever the size changes, so a turned phone needs no new decoder.
- Read the visible rectangle and the row stride from the output type (`MF_MT_MINIMUM_DISPLAY_APERTURE`, `MF_MT_DEFAULT_STRIDE`). The decoder pads to whole 16-pixel blocks, so 1080×2340 comes out as 1088×2352. Read the colour matrix and range too (`MF_MT_YUV_MATRIX`, `MF_MT_VIDEO_NOMINAL_RANGE`) and convert accordingly. The phone asks its encoder for BT.709, limited range.
- `Nv12Converter`: NV12 to BGRA in unsafe code, written so the inner loop can be vectorised later. Plain and tested against hand-computed values.
- Reuse buffers and samples. A BGRA picture is about 10 MB; allocating one per frame at 30 a second would keep the garbage collector busy.
- If the decoder cannot be created (Windows N editions lack it), the viewer simply never offers `h264`.

**2.4 The viewer.**
- `start-view` offers `codecs: ["h264", "jpeg"]` when a decoder could be created and the PC has approved the phone.
- The feed's consumer thread (0.2) decodes every H.264 frame in order, converts it, and leaves the newest finished picture for the UI thread, which copies it into a `WriteableBitmap` at background priority, as frames are drawn today. Only finished pictures the UI thread has not drawn yet may be skipped.
- Gap, decoder error, or a stream that begins without a keyframe: stop decoding, send `request-keyframe`, and ask again every second until one arrives. `PhoneVideoStream` decides this, so it is tested without a decoder.
- If the feed overflows (the PC cannot keep up): empty it, reset the decoder, ask for a keyframe.
- Two decoder failures in a session: re-send `start-view` with `codecs: ["jpeg"]` and stay there.
- The picture's size comes from each frame, so a resize or a change of format needs no waiting for `screen-info`.

**2.5 Docs.** `PROTOCOL.md` (H.264 in type `5`, `request-keyframe` for phones, a section like "Hardware video" for this direction), `README.md`, `android/README.md`, `windows/README.md`.

**Tests.**
- Phone, JVM:
  - a keyframe wanted while there is no room waits, then goes;
  - repeats stop when the screen changes;
  - SPS/PPS go in front of a keyframe once and are not duplicated;
  - keyframe requests are limited to one a second across viewers;
  - a viewer joining mid-stream gets a keyframe;
  - a pause and resume gives a new id and a keyframe without a new encoder;
  - the safety keyframe is asked for by the clock, and never on a still screen;
  - switching between the two outputs as viewers come and go.
- PC:
  - `PhoneVideoStream` for gaps, a first frame that is not a keyframe, the retry timer, and the fall back to JPEG after two failures;
  - `Nv12Converter` against known values, including padded strides;
  - a round trip through the real codecs, encoding synthetic pictures with `H264Encoder` and decoding them, at 320×240 and at 1080×2400. This test needs Vortice, unsafe code and `System.Drawing` in the test project. It prints SKIP and passes on a machine without the Windows codecs (a missing `mfplat.dll` counts), which the CI runner may be.
- Never commit a recording of a real phone's screen. Test pictures are generated.

**On the phones.** With the readout open: scrolling a long page; a still screen; closing and reopening the viewer mid-stream; turning the phone; locking and unlocking the S26; a second viewer joining; a viewer on v1.0.53 joining, which should move everyone to type `1`; another device joining the session mid-stream (the short pause); the limited-upload run from 0.4; an hour of viewing for heat and battery.

**Done when**, on the home network through the production relay, on both phones:
- scrolling shows at least 25 pictures a second and a median delay of 0.2 s or less;
- a still screen sends nothing;
- a viewer that opens mid-stream has a picture within a second;
- with the upload limited to 2 Mbit/s, the delay stays within about 0.3 s of the unlimited figure, at fewer pictures a second;
- every item under "On the phones" behaves;
- old and new apps still work together (section 9).

### Phase 3. Staying sharp on a slow link

After Phase 2 a slow link is on time but shows few pictures a second, each at full quality. This phase trades quality for rate.

**3.1 Bitrate steps.** New plain class, driven once a second by how much of that second `InFlight` had no room:
- no room more than about 30% of the time: multiply the bitrate by 0.7, down to 300 kbit/s;
- room throughout for 5 s while the encoder was using most of its budget: multiply by 1.25, up to the ceiling (appendix A);
- a still screen is not evidence either way, the same rule as the PC host's ladder;
- two `request-keyframe`s within 5 s mean frames are being dropped at the relay: step down, as `ScreenStreamer` does.

Applied with `PARAMETER_KEY_VIDEO_BITRATE`, which needs no keyframe. Wait a few seconds between steps.

**Done when**, with the upload limited to 5, 2 and 1 Mbit/s in turn, the delay stays under 0.3 s, scrolling stays above 15 pictures a second, and sharpness returns within about 10 s of the limit lifting.

**3.2 Optional: send no more pixels than the viewer shows.** The viewer window is about 1230 physical pixels tall on a 150% screen, while the phone sends 2340. The viewer could report its size, as `view-region` does for a PC host, and the phone could mirror at that size. It would cut the data by about two thirds, at the price of a keyframe on each window resize. Decide from Phase 2's measurements.

### Phase 4. Follow-ups

- **4.1 Accessibility pictures through the same encoder.** No longer optional: Accessibility viewing is the main mode (section 12), so this is built straight after Phase 2. Draw each screenshot onto the encoder's surface (`Surface.lockHardwareCanvas`, into the same texture the mirror feeds) instead of compressing a JPEG. The rate stays at 2.5 a second, but each picture is a few KB instead of about 100, and the CPU work of copying and JPEG compression goes away. Rule 2 in section 5.3 then no longer needs smooth sharing. Phase 0's baseline says how much delay this buys: it is the upload and compression share of the Accessibility figure.

The rest are optional:
- **4.2 A phone viewing a phone decodes H.264** with the existing `H264Decoder.kt`. Until then a phone viewer offers only JPEG, and its presence keeps a shared session on JPEG.
- **4.3 A 60-a-second option**, and frame-rate and bitrate limits in the phone's settings.

### Phase 5. Direct connection on the same network (outline)

It saves the relay round trip, 0.1–0.2 s by the estimate in 2.2, and Phase 0's round-trip figure will say what it really is. It needs its own plan. Starting points:

- The phone listens and the PC connects. An outbound connection from the PC raises no Windows Firewall prompt, and the app has no installer to add a rule.
- The phone announces its address and port to approved PCs over the relay. The PC tries it and keeps the relay as the fallback.
- The direct link uses the same encryption and the same device-identity check as the relay path.
- Networks that isolate Wi-Fi clients from each other will always need the relay.

## 7. Checks to run before the large pieces

Each is an hour or two and can change the design, so they come first.

**C4. Can a locked phone be unlocked from the PC today?** Done by Theo on 2026-11-10: yes, blind. The result:

| Step | What happened |
|---|---|
| Phone asleep, viewer opened | Black picture, and nothing done in the viewer has any effect. The phone does not wake. |
| Screen turned on by hand | The lock screen shows in the viewer, clock and "Swipe to unlock" included. |
| Click and drag upwards | The phone goes to its unlock screen. The picture turns black apart from the Back arrow in the bottom corner: Android keeps that screen out of screenshots. |
| Dragging the pattern where the grid is | **The phone unlocks.** The touches land; they just can't be seen. |

This was with the released apps (v1.0.53) and the S26, which is locked with a pattern. `PHONE-UNLOCK-PLAN.md` builds on it.

**C5. Can the app find the lock screen's keys?** Run on the S26 on 2026-11-10 at Theo's request, with a throwaway copy of the app built outside the repo (own app id, `canRetrieveWindowContent` on, a log of what it sees while the phone is locked). It was uninstalled afterwards and the phone's Accessibility setting put back.

| Question | Result |
|---|---|
| What lock does the S26 use? | A **pattern**, not a PIN. |
| Does an ordinary Accessibility service see the lock screen's unlock area? | **Yes.** With the pattern screen up it saw two windows: the navigation bar (the Back button) and the unlock window. In the unlock window: the text "For your security, draw your pattern.", one clickable view at `[157,1203][922,1968]` on the 1080×2340 screen, which is the pattern grid as a single 765×765 square, the Emergency call button, and the lock icon. Android's own inspection tool names that view `com.android.systemui:id/lockPatternView`. |
| Are the nine dots reported one by one? | No, only the square. The viewer has to draw an even 3×3 grid inside it. |
| Does `ScreenWaker` turn the screen on? | **Yes, if the app's notifications are allowed.** Samsung cancels the wake for an app whose notifications are off (system log: "Screen__On : Cancel (notifications are disabled)"). With notifications on, the phone woke in about a second. The separate "Turn screen on" special access made no difference either way and can stay at its default. |

Not answered by this run:
- Whether the dots sit exactly on an even grid inside the square. Checking means drawing the real pattern in a viewer that shows the grid.
- PIN and password locks. The phone would have to be switched to each.
- Whether Android asks for the Accessibility service to be switched off and on when an installed app gains the new capability in an update. The probe was a fresh install enabled over adb.
- Whether Google Play accepts the capability.

**C1. Can Windows decode what the phones encode?** Before Phase 2.
Theo agreed on 2026-11-10, for the S26 only. Record two seconds from its hardware encoder while something on its screen is moving (the recorder writes a frame only when the screen changes):

```
adb exec-out screenrecord --output-format=h264 --time-limit 2 - > clip.h264
```

Run that from Git Bash or cmd. Windows PowerShell's `>` rewrites binary output as text and would ruin the clip, which would look like "Windows can't decode it". The clips show whatever is on the phone's screen, so they stay in a scratch folder and are never committed.

A scratch program creates the decoder as step 2.3 describes, feeds the clip one access unit at a time, and reports:
- whether 1080×2340 is accepted at all, and 1080×2400 (the S20's size) from a generated clip; and the same sizes turned (2340×1080, 2400×1080), which a phone on its side sends since the size limit moved to the shorter side. If not, try raising `CODECAPI_AVDecVideoMaxCodedHeight` and `…Width` on the decoder before concluding that D9 is needed;
- the padded size, visible rectangle, stride and colour information;
- milliseconds per frame for decoding, for the colour conversion, and for drawing in a real WPF window, which adds a 10 MB copy to the graphics card per picture.

The recorder sets the encoder up its own way, so two answers must be taken again from a clip made with this app's own settings (C3 can write one): whether each frame comes out as soon as it goes in, and how the colours are signalled. Any hold-back is added delay, and would mean looking at how the stream signals frame order.

**C2. Does the consent prompt work remotely?** Before Phase 1, on the debug build from 0.1, on the S26:
- does the prompt appear when started from the Accessibility service with no app window open (Android's documentation is no longer explicit about this, and Android 17 is the one to watch);
- does it show in Accessibility screenshots;
- does a tap injected through the Accessibility service press its button;
- does asking for the whole screen remove the single-app choice on a Samsung;
- does a screen wake lock held by the service keep the screen on.

**C3. Does the GL path behave on the S26?** At the start of Phase 2, as a throwaway screen in the debug build showing a generated pattern:
- a picture arrives as soon as the surface is attached, without the screen changing;
- drawing the same texture again after a keyframe request produces a keyframe on a still screen;
- a frame comes out of the encoder without another one being fed in after it;
- `VirtualDisplay.resize` after a turn needs no new consent;
- time from capture to encoded frame;
- it can write its encoded stream to a file, for C1's second pass.

## 8. Testing

**Automated**, as each phase lists:
- Android: `.\gradlew.bat :app:testDebugUnitTest` from `android/`, with `JAVA_HOME` set to Android Studio's JBR. Always finish with the full suite: Robolectric shadows of `ConnectionManager` and `PeerAuth` clash across test classes in a full run but not with `--tests`. New test classes should set those objects' fields by reflection with a `RecordingSocket`, as `CaptureOffersTest` does, rather than add another shadow.
- Windows: `dotnet run --project windows/RemoteDesktopWin.PhoneViewTests`, plus the existing test projects.
- Relay: `npm test` in `server/`.

**On real phones.** Theo agreed on 2026-11-10 to the debug build and to screen clips on the **S26 only** (Android 17, Qualcomm encoder). That covers the stricter Android rules and one hardware encoder.
- Nothing is installed on the S20, and no clips are taken from it. So Android 13's behaviour and the Exynos encoder are first seen on a real phone when a release reaches the S20. Wherever this plan says "on both phones", the S20 half is checked then, by Theo, with the readout. Until then the fallbacks (JPEG after an encoder failure, the notification if the prompt can't be started) are what protect it.
- Do not install anything else on either phone without asking again.
- The debug PC exe cannot run while the installed app does (single-instance). It shares `%AppData%\RemoteDesktopWin` with it, so it joins the session as the same PC, and a server URL changed for a test must be put back.
- The readout can be read without a person: its text has an automation id, and UI Automation from PowerShell can press F8 and read it.
- An emulator is a fallback for the Android side only. It needs a system image downloaded and has only a software encoder, so it proves the logic but not the hardware.

**Limiting the upload.** Two ways, for two questions:
- To see the phone's own buffers fill, limit the real link: a router's speed limit for the phone, or the phone tethered to a slow hotspot.
- To repeat a run exactly, put a small rate-limiting proxy in front of a local relay (`scripts/up-local.sh`) and point the debug apps at it. It must stop reading when it is over its rate, not read everything and hold it. Even so the waiting data sits on the PC rather than the phone, so judge by the readout's delay, never by the phone's queue figure.

## 9. Compatibility

| Viewer | Phone | What happens |
|---|---|---|
| New PC | v1.0.53 or older | The phone ignores `codecs` and sends type `1`, and its `screen-info` has no `stream`, so the viewer draws type `1` as now. The readout shows rate and size but no delay. No Smooth video button, because `screen-info` has no `smooth`. |
| v1.0.53 PC or older | New | No `codecs`, so the phone sends type `1` and `screen-info` only when it does today. The small fixes on the phone still apply. |
| New phone as viewer | New | Type `5` JPEG. It offers no `h264` until 4.2. |
| Older phone as viewer | New | Type `1`. |
| New and old viewers together | New | Type `1` for all. When the old one leaves, a new stream starts in a better format. |
| PC and phone viewing together, all new | New | Type `5` JPEG until 4.2, then H.264. |
| Any | Any, through an old relay that broadcasts | Unchanged. Viewers that don't know type `5` ignore it, and viewers that do check the stream id. |

The PC updates itself when idle, and phones only when their owner installs the update, so a new PC with an older phone will be the common mix.

## 10. Risks

| Risk | Likelihood | What we do |
|---|---|---|
| Pictures wait in buffers the phone can't see | Certain on a slow upload | Step 0.7 holds back by acknowledgements. If that proves too coarse, cap the socket's send buffer in the same `SocketFactory` as 0.5, at the price of slower file downloads from the phone. |
| Windows refuses pictures taller than 2304 px | Low, but unverified; C1 settles it | Raise the decoder's size attributes; failing that, sideways encoding (D9) |
| The Windows decoder holds frames back despite low-latency mode | Low | C1 measures it. If so, look at the stream's frame-order signalling, and try the Baseline profile on the phone. |
| A phone's hardware encoder misbehaves (odd sizes, ignored settings, holds a frame back) | Medium across all Android phones. The S20's Exynos encoder is not tried before release (section 8). | Size fallback, then JPEG. Two failures keep the session on JPEG. The repeats flush a held frame. |
| The consent prompt can't be started or clicked remotely on some Android version | Medium | Notification fallback. At worst one tap on the phone, which is still less than today. |
| Smooth sharing ends at every lock on Android 15+ | Certain | Falls back to Accessibility viewing by itself; the viewer says why and the menu row can start it again. Step 1.5 stops the phone locking from its own timeout while it is in use. |
| Android 15+ blanks sensitive content (password fields, one-time codes) during screen sharing | Certain on those versions | Switching back from the menu row (1.4), and a note in the README. |
| A phone kept awake and unlocked longer than its owner expects | Follows from 1.5 | Only in smooth sharing, only while viewed, and released after 10 minutes without input. Agreed by Theo (section 12). |
| 30 pictures a second heats the phone or drains it | Low: hardware encoding, and a still screen costs nothing | Mirroring stops when nobody views (0.6). Measured in the hour-long check. Lower the cap if needed. |
| The PC can't keep up (decode, convert and upload about 10 MB per picture) | Low on a desktop | Measured in C1. Vectorise the conversion, or do 3.2. |
| The CI machine lacks the Windows codecs | Likely | Codec tests print SKIP there; the plain-logic tests still run. |
| The readout's delay is off | By half the difference between the two directions' travel time; a few ms on a home network | Shown as "±" beside the figure. |

## 11. Baseline measurements

Fill in during Phase 0 from the Shift+F8 copy. One row per phone and mode, the same test each time: scroll a long page for 20 seconds, then leave it still for 10.

| Date | Build | Phone | Mode and format | Upload limit | Pictures/s | KB per picture | Delay median / worst (ms) | Round trip (ms) | Phone queue, worst (KB) | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | |

Send buffer sizes (`adb shell cat /proc/sys/net/ipv4/tcp_wmem`): S20 `524288 1048576 4194304`. S26: not read yet.

## 12. Decisions, and what is still open

Answered by Theo on 2026-11-10:

| Question | Answer | What it changed |
|---|---|---|
| Where is the phone when it is viewed? | About half the time beside the PC, half elsewhere. Being able to unlock it from the PC by typing the PIN would be a real benefit. | Accessibility viewing is the main mode. Phase U added, now `PHONE-UNLOCK-PLAN.md`. Step 4.1 moved up to follow Phase 2. |
| Debug build and screen clips on the phones? | Yes, on the S26 only. | Checks C1–C3 and all device testing use the S26. The S20 is first tried on a release (section 8). |
| Keep the phone awake in smooth sharing, letting go after 10 minutes without input? | Yes. | Step 1.5 is in. |
| This plan or the menu plan first? | The menu. | *Smooth video* and *Stats* are rows in the `⋯` menu; nothing is drawn over the picture. |

Read with some care: the first answer was taken to mean that the mode which works on a locked, distant phone should be the main one, which is Accessibility viewing. If it meant smooth sharing, the order in section 3 goes back to Phase 1, then Phase 2, with step 4.1 optional.

Still open:

1. **Order of Phase 1 and Phase 2.** Phase 1 is first unless Theo says otherwise.
2. **Rate.** 30 pictures a second as the default, with 60 as a later option?

No longer open here:

- How to enter the pattern or PIN: Theo chose option A on 2026-11-10 (the phone reports where its unlock area is). See `PHONE-UNLOCK-PLAN.md`.
- Whether unlocking goes before Phase 0: yes, as a plan of its own, unless Theo says otherwise (`PHONE-PLANS-ORDER.md`).
- Whether Accessibility viewing should also keep the phone awake: no (`PHONE-UNLOCK-PLAN.md`, decision 7). Not confirmed by Theo.

## 13. Seen along the way, not part of this plan

- **The PC's viewer can be left showing a dead phone.** When the PC itself reconnects (a relay redeploy drops everyone), every device comes back under a new id, and no "left" message arrives for the old one. The viewer window stays open on the old id with its last picture (`MainWindow.ApplyState` clears the peer list but closes nothing). The phone's own viewer handles this case (`PhoneViewerActivity.updateReconnectNotice`). A fix would find the same device again by its identity and re-send `start-view`.
- **The PC will open a viewer on a device it has not approved.** Every row in the device list has a View button. D7 stops such a device supplying a video stream, but the button is still there.
- **The picture freezes while a file is downloaded from the phone.** File chunks and pictures share one queue on the phone, and chunks may fill it to 6 MB. The PC has separate lanes for this; the phone does not.

## Appendix A. Encoder and decoder settings

Phone encoder (`MediaCodec`, `video/avc`):

| Setting | Value |
|---|---|
| Choice of encoder | Hardware first (`isHardwareAccelerated` on API 29+, otherwise by name). A software encoder only at a long side of 1280 or less. |
| `KEY_COLOR_FORMAT` | `COLOR_FormatSurface` |
| `KEY_BIT_RATE` | Start at 3 Mbit/s for a 1080×2400 picture, scaled by pixel count. Phase 3 moves it between 0.3 and 8 Mbit/s. |
| `KEY_BITRATE_MODE` | CBR where `isBitrateModeSupported` says so, else VBR |
| `KEY_FRAME_RATE` | 30 |
| `KEY_I_FRAME_INTERVAL` | 600 s, in effect never: it counts frames at the nominal rate, not seconds. Keyframes come from requests (D8). |
| `KEY_MAX_B_FRAMES` | 0 (API 29+; it is also the default) |
| `KEY_LATENCY` | 1 (API 26+; ignored where unsupported) |
| `KEY_PRIORITY` | 0, realtime |
| `KEY_COLOR_STANDARD`, `_RANGE`, `_TRANSFER` | BT.709, limited, SDR |
| Profile | The encoder's default. Trying High is a later tuning step. |
| While running | `PARAMETER_KEY_REQUEST_SYNC_FRAME`, `PARAMETER_KEY_VIDEO_BITRATE` |
| Not used | `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` (can fail to configure), `KEY_REPEAT_PREVIOUS_FRAME_AFTER` and `KEY_MAX_FPS_TO_ENCODER` (the GL step does both) |

PC decoder (Media Foundation):

| Setting | Value |
|---|---|
| Transform | `CLSID_CMSH264DecoderMFT`: Microsoft's H.264 decoder, synchronous, software |
| `CODECAPI_AVLowLatencyMode` | 1, set as a 32-bit integer on the transform's attributes before the types |
| Input type | H.264, the size from the frame header, progressive |
| Sample time | The frame's `seq` |
| Output type | NV12, chosen again after `MF_E_TRANSFORM_STREAM_CHANGE` |
| Output samples | Check `GetOutputStreamInfo`: supply and reuse our own unless the decoder provides them |

## Appendix B. Files

| Area | Files |
|---|---|
| Phone, changed | `Crypto.kt`, `ConnectionManager.kt`, `PhoneScreenCapture.kt`, `ScreenCaptureService.kt`, `AccessibilityScreenCapture.kt`, `InputAccessibilityService.kt`, `PhoneViewerActivity.kt`, `PermissionsActivity.kt`, `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts` |
| Phone, new | `PhoneFrame.kt`, `PhoneVideoStream.kt` (the viewer's side, for a phone viewing a phone), `InFlight.kt`, `PhoneVideoEncoder.kt` and its GL class, `BitrateSteps.kt`, `ScreenShareRequestActivity.kt` |
| PC, changed | `Crypto.cs`, `MainWindow.xaml.cs`, `PhoneViewWindow.xaml`, `PhoneViewWindow.xaml.cs`, `H264Encoder.cs` (shared Media Foundation start-up) |
| PC, new | `PhoneFrame.cs`, `PhoneVideoStream.cs`, `PhoneFeed.cs`, `H264Decoder.cs`, `Nv12Converter.cs`, cases added to the menu plan's test project `RemoteDesktopWin.PhoneViewTests` |
| Relay | `server/test.mjs`, and the channel `5` vector in `server/test-e2ee.mjs` |
| Build and docs | `.github/workflows/release.yml`, `PROTOCOL.md`, `README.md`, `android/README.md`, `windows/README.md` |

## Sources

- Android screen-sharing rules (consent per session, stop on lock, whole-screen option, resizing): https://developer.android.com/media/grow/media-projection
- Android encoder settings: https://developer.android.com/reference/android/media/MediaFormat
- Starting an activity from the background: https://developer.android.com/guide/components/activities/background-starts
- `noHistory` and activity results: https://developer.android.com/guide/topics/manifest/activity-element
- Windows H.264 decoder limits and attributes: https://learn.microsoft.com/en-us/windows/win32/medfound/h-264-video-decoder
- Low-latency mode: https://learn.microsoft.com/en-us/windows/win32/medfound/codecapi-avlowlatencymode
- Mozilla on the 4096 × 2304 limit and portrait sizes: https://bugzilla.mozilla.org/show_bug.cgi?id=1422239
- OkHttp 4.12 socket setup (no `TCP_NODELAY`): https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnection.kt

## Review log

2026-11-10. An independent pass checked the first draft against the code and the platform documentation. The draft's account of the existing code held. These parts of the design changed:

- **Holding back by acknowledgements (D3, 0.7).** The draft judged "room" by the phone's own queue. That figure leaves out the system's send buffer, which on the S20 starts at 1 MB, so the delay bound it promised would not have held on a slow upload.
- **The stream id travels with the pictures on the PC (0.2).** The draft checked it on a worker while the id arrived through the UI thread, so each stream's first keyframe would have been thrown away.
- **What the stream id protects (D7).** Added: the viewer must have approved the phone, type `1` is ignored while a stream is announced, the id dies when streaming pauses, 64 bits, and never sending to an empty recipient list.
- **The consent activity (1.2).** The draft made it `noHistory`, which never receives the consent result.
- **The delay formula (0.3).** The draft had the clock difference with the wrong sign, and estimated it in a way that a busy send queue would bias.
- **Phase order.** The rotation and idle-mirroring repairs moved from Phase 1 to Phase 0, since Phase 2 needs them. Congestion handling that the draft left to Phase 3 moved into Phases 0 and 2, so Phase 2 is safe to release alone.
- **Keyframes (D8).** The 30-second safety net could not rest on the encoder's interval setting, which counts frames.
- **Smooth sharing messages (5.2).** Added `stop-smooth`, an `ended` state with a reason, a viewer-side timeout, and `smooth` only from Phase 1.
- **Keeping awake (1.5).** Added the 10-minute limit; dropped the idea of borrowing the menu plan's rotation overlay.
- **Phone viewers (0.2).** A phone watching another phone now understands type `5`, so it no longer drags a shared session back to type `1`.
- **Check C1.** Binary-safe recording, the decoder's size attributes, a second pass with the app's own encoder settings, and drawing cost.
- **Docs steps** added to Phases 0 and 1.

Not verified by anyone yet, and so left to the checks in section 7: starting the prompt from the background on Android 17, tapping it remotely, wake locks on Android 17, and the Windows decoder's behaviour with tall pictures and in low-latency mode.

2026-11-11. Building step 0.2 changed these parts, and the text above now says what was built:

- **A pause is said as a pause (D7, 5.4).** The plan had a paused viewer fall back to drawing type `1`. A pause is usually an unapproved device joining, which is exactly who could then draw into the viewer. `screen-info` now carries `paused: true`, and a paused viewer draws nothing.
- **The socket thread checks and acknowledges; the feed's thread only decodes (0.2).** The plan gave the stream's state to the consumer thread and the acknowledgement to the socket thread, which could not then know what to acknowledge.
- **The last frame of a burst is acknowledged (5.2, 0.7).** "At most one per 30 ms" alone would leave it out, and the phone would then hold back, and send it again, for nothing.
- **Decoding left the PC's UI thread now (0.5)**, with the black-picture test, since the feed's thread was there to do it.
- **The capture time is checked, not trusted (5.1, 0.2).** No phone was at hand to see which clock Android stamps pictures with.
- **A sequence number is drawn once, and a refused picture takes none.**
- **A shared test vector for the header**, in both apps and, encrypted, in the relay's reference crypto.
