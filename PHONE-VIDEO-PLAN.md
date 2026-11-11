# Faster phone viewing from the PC

Written 2026-11-10 against v1.0.53 (`30009c4`), then checked against the code by an independent review and revised the same day (the review log is the last section). Built so far: all of Phase 0 (step 0.1 first, the rest on 2026-11-11) and Phase 1 (2026-11-11), whose text below now describes what was built. What they still lack is on a phone and by hand: see the end of each phase and section 11. Everything after Phase 1 is still a plan.

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
It is a Media Foundation transform, reached through `Vortice.MediaFoundation`, which the app already ships for `H264Encoder.cs`. No new dependency. The decoder outputs NV12; the app converts to BGRA and draws into a `WriteableBitmap`. Software decoding of one phone-sized stream takes 3 to 5 ms a picture on a desktop (check C1). Hardware decoding was no quicker by the clock there, because the picture has to be copied back to memory for WPF, though it took about a tenth of the processor time; it is some twenty lines on the same transform, and is left until a PC turns out too slow (2.3). FFmpeg and OpenH264 were rejected as new native dependencies. HEVC needs a paid Windows extension.

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

**D9. If Windows cannot decode tall pictures, the phone sends them sideways. Not needed, not built.**
The GL step would turn the picture 90°, a header flag would say so, and the PC would turn it back while converting colours. Check C1 (section 7) found that Windows decodes every phone size, upright and on its side, with nothing set, and generated pictures up to 4096 pixels either way. So none of this is built, and flag bit 3 stays reserved. That was one PC on Windows 11; Windows 10 is untried. A PC whose Windows does refuse a tall picture is not left with a black window: its decoder gives no picture for the keyframe, `H264Decoder` reports that as a failure, and the viewer goes back to JPEG (2.3, 2.4).

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
| `screen-info` | phone → each viewer | New field `smooth`: `"on"`, `"only"` (on, and the phone's one way of showing its screen, so a viewer may not stop it), `"available"` or `"unavailable"`, for viewers that sent `codecs`. A Phase 0 phone never sends it, so a newer PC shows no row the phone can't act on. | 1, built |
| `request-smooth` | viewer → phone | New. Asks the phone to show its screen-sharing prompt. | 1, built |
| `stop-smooth` | viewer → phone | New. Ends smooth sharing; Accessibility viewing takes over. | 1, built |
| `smooth-status` | phone → viewer | New. `state`: `prompting`, `started`, `declined`, `ended` or `unavailable`. `reason`, where there is one, is a whole sentence that the viewer shows as it is, e.g. "Unlock the phone first." or "Smooth video ended because the phone was locked." | 1, built |
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

**0.3 The readout.** Built: `PhoneStats.cs`, with `PhoneFeed` pinging and timing the answers.
A *Stats* row in the `⋯` menu toggles a small overlay, and so does F8. No F-key is forwarded to the phone, so F8 is free. It shows: capture mode, format, size, pictures a second, kbit/s, delay (latest, average and worst over the last second), round trip, the phone's queue, and gaps. Shift+F8 copies the last 60 seconds as text, so numbers can be pasted into a message or into section 11.

How the delay is worked out:
- The PC uses a steady clock (`Stopwatch`), and notes times on the socket thread, not in a UI handler.
- Every 2 s while the overlay is open it sends `diagnostic-ping` and notes when. The feed's own thread sends it, and the socket thread times the pong, so a busy window delays neither. The pong's `hostTimeMs` minus that send time is the difference between the two clocks plus the ping's travel time.
- The clock difference is taken as the smallest such value in the last minute, minus half the smallest round trip in the last minute. Only the ping's own trip enters this, and pings travel towards the phone, the direction video does not load. Halving a round trip measured through the phone's busy send queue would understate the delay by half that queue.
- Delay = (PC clock when the picture is drawn + clock difference) − `captureMs`, in 32-bit wrapping arithmetic.
- It is shown with "±" half the smallest round trip.
- A repeat (flag bit 4) is left out. A phone that sends type `1` has no capture time, and the readout says so.
- "Drawn" is when the window's thread assigns the picture, which is a frame or so before it is on the glass.

**0.4 Record the baseline** in section 11, before changing anything else: both phones, both modes. Also record:
- `tcp_wmem` on the S26 (the S20's is in 2.3);
- one run with the phone's upload limited to about 2 Mbit/s (a router setting or a phone hotspot), comparing the readout's delay with the phone's reported queue. A long delay beside a small queue confirms the system buffer is where pictures wait.

**0.5 Small fixes.** Built. None was measured before and after: there was no phone at hand while they were built, and the baseline (0.4) was taken with all of them in. Measuring one alone means building the app without it.
- **Turn Nagle off on the phone.** Give the `OkHttpClient` a `SocketFactory` that sets `tcpNoDelay` on each socket it creates. OkHttp creates the raw socket through the factory and layers TLS over it.
- **Decode JPEGs off the PC's UI thread.** Built with step 0.2: the feed's own thread decodes (newest only, as `FrameMailbox(1)` did), freezes the bitmap and runs the unlock plan's black-picture test, and the UI thread only assigns the picture. Still to do here: measure it. A flat test picture of 1080 × 2340 took 5 ms; a real screen will take longer.
- **Don't send a screenshot that hasn't changed.** Accessibility viewing sends every screenshot, about 100 KB two and a half times a second, even on a still screen. On a slow upload a changed picture then waits behind unchanged ones. Compare each screenshot with the last one sent (`Bitmap.sameAs`) and skip it if equal, except when a viewer has just joined or the size changed. As built, one also goes when pictures start again after a pause, and viewers that cannot acknowledge are still sent an unchanged screen every 3 s: nothing else would replace a picture the relay dropped for them, which the next screenshot used to do 400 ms later. The bitmap that was sent is kept to compare with, not copied.
- **For viewers on older apps**, which can't acknowledge, lower the skip threshold from 300 KB to about 64 KB. It costs nothing, but helps little on a slow upload, because the system buffer in front of it is far larger. As built the 64 KB limit is the queue rule for everyone: with acknowledgements, "the app's own queue is nearly empty" (0.7) is the same test.
- **Seen while building this, and fixed with it:** both viewers looked at a picture for blackness at most twice a second, and one that came sooner stood as whatever the one before was. That mended itself 400 ms later while every screenshot was sent. With a still screen sending nothing, the unlock screen's last, black picture could go unjudged for good. A picture that was passed over is now looked at when its turn comes.

**0.6 Repair the capture service.**
- **Follow the phone when it turns.** Already built by the time this step is reached: it is in PHONE-MENU-PLAN.md, Phase 3 (display listener, `onCapturedContentResize` on API 34+, `VirtualDisplay.resize`, a new `ImageReader`, a new `screen-info`). Here, only confirm with the readout that a turned phone fills the window in smooth sharing, and make sure the size change goes out through the new shared `send` of step 0.2.
- **Stop mirroring when nobody is viewing.** Built, in `ScreenCaptureService` with the on/off state in `CaptureTarget`; not tried on a phone. Before, the virtual display kept drawing into the `ImageReader` whether or not anyone viewed, and each picture was thrown away. Call `setSurface(null)` once streaming has been off for about 5 s, not at once, because of the short pauses described above. Attach it again when streaming starts. Attaching also makes Android draw one picture straight away, so a new viewer sees the screen without waiting for it to change.

**0.7 Hold back by what has arrived** (D3).
Viewers acknowledge from step 0.2 on (built): for each type `5` frame of the current stream, `frame-ack` with the highest `seq` received, at most one per 30 ms, from the thread that received the frame; one held back by the 30 ms goes a moment later, when they are up. That part had to ship with 0.2, so that every viewer that offers `codecs` also acknowledges. The phone already checks and remembers each one (`PhoneScreenCapture.onFrameAck`). This step is the phone acting on it. If the baseline shows no queueing on the home network, it can wait and become the first step of Phase 2, which cannot do without it.

Phone: a new plain class, `InFlight`. Built as listed, with these made definite:
- "Usual" is kept for each viewer, not one for all: a far viewer is not late by a near one's measure.
- A viewer is quiet when a frame sent to it has gone unacknowledged for 2 s. On a still screen nothing is owed, so nobody goes quiet.
- The picture sent again is the last JPEG, kept by `PhoneScreenCapture`, with its own sequence number, the first one's capture time and the repeat flag. After four tries (1, 2, 4 and 8 s apart) it is left. A viewer whose acknowledgements usually take longer than a second gets its usual time plus 100 ms in place of the second (8 s at most), and nothing is sent again while there is no room for a picture: a viewer that has been answering is behind, not missing a picture, until it has been silent for 2 s.
- A screenshot is passed over for lack of room before it is compressed.
- It notes when each frame was handed to the socket.
- When an acknowledgement arrives, it notes how long that frame took. The shortest such time in the last 60 s is "usual".
- There is **room** when the app's own queue is nearly empty and no frame has been waiting for its acknowledgement longer than usual plus 100 ms. Before the first acknowledgement, two frames may be in flight.
- With several viewers, the slowest one that is still answering sets the pace. A viewer that has acknowledged nothing for 2 s stops counting until it answers again.
- If the newest frame is still unacknowledged after 1 s and nothing has been sent since, the picture is sent again. Further tries wait 2 s, 4 s, then 8 s.
- Both capture classes ask `InFlight` for room when every viewer acknowledges, and keep the queue rule otherwise.

The 100 ms is a starting value; tune it with the readout. Known and unchanged: while a file is being downloaded from the phone, its chunks fill the same queue and the picture freezes.

**0.8 Docs.** Written. `PROTOCOL.md`: the frame table, `screen-info`, `frame-ack`, the phone's pong, the paragraph on the Android sender's queue (it says 300 KB) and the list of encryption channels. `android/README.md`: the debug build, and that Accessibility viewing no longer sends unchanged pictures. `windows/README.md`: F8.

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

**Where that stands** (2026-11-11). Everything is built and tested without a phone. Section 11 holds what has been measured so far. Left for Theo's hand, because they need the real phones, the real internet or a throttled link:
- figures through the production relay, from the S20 and the S26, in both modes (section 11 has rig figures only: the relay was on this PC);
- the limited-upload run of 0.4, comparing the readout's delay with the phone's queue;
- "before" figures: v1.0.53 has no readout, and a v1.0.53 phone shows rate and size only, so before and after can be compared for pictures a second and KB per picture, not for delay;
- a v1.0.53 PC viewing an updated phone, and an updated PC viewing a v1.0.53 phone (unit tests, and v1.0.53's code read, say both work);
- a phone viewing a phone, which needs two.

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

Built on 2026-11-11, after check C2 (section 7), whose answers were all yes. The text below describes what was built. Where it differs from the plan as first written, it says so.

**1.1 Ask for the whole screen.** Built: `ScreenShare.consentIntent`, used by the Permissions screen and by step 1.2.
`createScreenCaptureIntent()` with no arguments offers "a single app" first on Android 14+, and a single-app share breaks tap positions, which are fractions of the whole screen. On API 34+ the helper passes `MediaProjectionConfig.createConfigForDefaultDisplay()`. On the S26 the prompt then comes up on "Share entire screen" with the other choice greyed out (C2).

**1.2 Show the prompt on request.** Built: `SmoothSharing` (every rule, a plain class), `SmoothPrompt` (carries them out) and `ScreenShareRequestActivity`.
The activity is a plain `Activity` with the translucent platform theme, out of recents, in its own task. It is **not** `noHistory`: Android never delivers an activity result to a `noHistory` activity, and the result is the consent. It launches the consent intent and hands the result to `SmoothPrompt`, which starts `ScreenCaptureService`.

`ConnectionManager` passes `request-smooth` to `SmoothPrompt` with four facts about the sender, and `SmoothSharing` decides:
- sender not approved, control off, a phone while *Allow other phones* is off, or not viewing: nothing happens and nothing is answered;
- already sharing: `started`;
- phone locked or asleep: `unavailable`, "Unlock the phone first.";
- pictures held back because a device is waiting to be approved: `unavailable`, since nobody could see the prompt in a viewer;
- a prompt is already up: `prompting`, and the sender waits for that one;
- a prompt went without a yes a moment ago: `unavailable`, saying how long is left (10 s after the first, doubling to 5 minutes if it goes on; review, 2026-11-11);
- otherwise the prompt is shown: `prompting`, then `started` or `declined`.

Changed from the first plan, on what C2 showed:
- **A repeat is answered, not ignored.** The plan dropped a repeat within 10 s. Now a request while a prompt is up joins it, and one made too soon after a prompt that got no yes is told why and how long to wait, so a viewer never has to guess from silence. Silence is kept for senders that may not ask.
- **The wait follows every prompt that got no yes**, not only a refusal: one nobody answered, one the screen turned off under, one whose asker stopped viewing. As first built only Cancel or Back set it, so a viewer could put the prompt back up the moment it had gone, by asking again or by closing and opening its window. The wait doubles each time in a row, from 10 s to 5 minutes, and a yes or 10 quiet minutes bring it back to 10 s.
- **Sharing that starts another way takes the prompt away**, and whoever asked is told `started`. As first built the prompt stayed up, and the viewer heard "Nobody answered" a minute later with smooth sharing on.
- **A turned phone does not ask twice.** The activity handles the turn itself (`configChanges`), and if Android makes it anew all the same it remembers that it has asked.
- **A prompt nobody answers is taken away after a minute**, and the viewer is told `declined`, "Nobody answered the prompt on the phone." C2 saw one stand for two minutes and more.
- **It is taken away at once when every viewer that asked has stopped viewing**, and nobody is told. It would otherwise wait on an unattended phone for whoever picks it up next.
- **The screen turning off takes it away too**, with `declined` and the reason.

Android lets an app whose Accessibility service the system has bound start an activity with no window open (C2: `BAL_ALLOW_TOKEN`). Where that fails, a notification asks instead ("… is asking for smooth video. Tap to see Android's prompt, and choose there."), and the viewer is told `prompting` with "Tap the notification on the phone to see the prompt." That happens when the Accessibility service is off, and when the activity has not appeared two seconds after it was started, since a start Android refuses fails without a word. With notifications off as well, the answer is `unavailable`, in words that say the phone could not ask and where on the phone smooth sharing is turned on. That covers the app's notifications being off and also just their "Requests from your devices" kind, which a person can switch off by itself (review, 2026-11-11: as first built that case posted a notification nobody saw, and the viewer heard "Nobody answered" a minute later). A phone whose Android has no prompt to give answers `unavailable` too; as first built it said `declined`.

**1.3 Stop on request, and say why it ended.** Built.
`stop-smooth`, from a sender that may ask, stops `ScreenCaptureService`. Not where smooth sharing is the only way the phone can show its screen, which is Android 10 and earlier, and any phone whose Accessibility service is off (review, 2026-11-11): the viewer would be left with no picture and no way to see the prompt that brings it back. Such a phone says `smooth: "only"` in `screen-info`, the PC's row is ticked and greyed with a tooltip saying why, and a `stop-smooth` is answered `unavailable` with the reason. Asked to start on such a phone, a viewer is told that the prompt has to be approved on the phone itself. Whenever smooth sharing ends, every viewer on a current app is sent `smooth-status ended` with a reason: "Smooth video was stopped from *name*.", "Smooth video was stopped on the phone.", or "Smooth video ended because the phone was locked." when the lock screen is up at that moment. Android's `onStop` gives no reason itself. Reasons are whole sentences in the phone's words, and a viewer shows them as they are.

**1.4 The menu row.** Built: `SmoothVideo` in `PhoneMenu.cs`, and the row in `PhoneViewWindow`.
A *Smooth video* row in the viewer's `⋯` menu, shown when `screen-info` carries `smooth` from a phone this PC has approved, and greyed when it says `unavailable`, or `only` (ticked as well: on, and not to be stopped from here, see 1.3). Its tooltip reads: "Asks the phone to share its screen as video. Approve the prompt on the phone; you can click it here. If the phone is on its side, scroll the prompt up to reach its button." Choosing it sends `request-smooth`. The prompt then appears in the picture and can be clicked like anything else. On, the row is ticked, and choosing it sends `stop-smooth`: Android 15+ blanks password fields and one-time codes during screen sharing, so there are moments to switch back. Each `smooth-status` is shown as a toast, in the phone's words where it gave some, and a phone that says nothing for 5 s is reported. Like everything in that menu, the row does not take keyboard focus.

Not built:
- The same row in the phone's own viewer of another phone (`PhoneViewerActivity`). It ignores `smooth-status`.
- A row for a phone on Android 10 or earlier that is not yet sharing. Such a phone has no other way to show its screen, so it sends no picture and no `screen-info`, and the PC never learns that it could ask. Smooth sharing still has to be turned on at that phone, as before.

**1.5 Keep the phone awake while it is viewed in smooth sharing.** Built: `KeepAwake` (a plain class) and a screen wake lock in `ScreenCaptureService`. Agreed by Theo on 2026-11-10, with the 10-minute limit.
On Android 15 QPR1 and later, a phone that sleeps on its usual timeout and locks ends smooth sharing, and smooth sharing cannot be started again until the phone is unlocked and the prompt approved.
- While at least one approved device is viewing and smooth sharing is running, the service holds a dimmed screen wake lock. A short pause in the pictures (a device joining the session) does not let go.
- It lets go when the last viewer stops, disconnects or loses approval.
- It lets go when a viewer sends the menu plan's `lock`, before the screen is turned off, and does not take hold again until a viewer's next input.
- It lets go after 10 minutes without input from a viewer: the lock is taken with that time limit, and each input takes it again. Input is what wakes a sleeping phone (`InputAccessibilityService.wakesPhone`), not acknowledgements, pings or menu state requests.
- A device beginning to view starts the 10 minutes too, and so does smooth sharing starting. A device that only comes back does not (review, 2026-11-11). A PC whose connection drops sends `start-view` again by itself, and as first built that counted as a new viewer each time: a forgotten viewer on a poor link held the phone awake for good, and undid a viewer's Lock. A device is now known by its identity, which outlasts its peer id, and its `start-view` is a return unless its last view ended with its own `stop-view`. No length of absence makes a return a new start: a PC that sleeps and wakes by itself would otherwise get ten minutes each time.
- C2 found the wake lock accepted and counted by Android 17, so the 1×1 overlay fallback was not built. What nobody has seen is the screen staying on past its timeout: the S26 was on its charger with "stay awake while charging" on.

**1.6 Docs.** Written. `PROTOCOL.md`: "Smooth sharing on request" (the three messages, `smooth`, who may send, keeping awake). `android/README.md`, `windows/README.md` and `README.md`: the row, the prompt, what ends it.

**Tests.** Built.
- `SmoothSharingTest` (plain): all fifteen kinds of sender that lack one or more of the four things are refused, whatever state the phone is in, and cannot join or disturb a prompt that is up; each answer above; one prompt at a time; the 10 s after a refusal; every way a prompt ends is told once; a prompt goes when those who asked have; the reasons for the end.
- `SmoothRequestTest` (the app carrying it out): a stranger, an approved device that is not viewing or has stopped, a phone with other phones shut out, a device whose approval was taken back, any device with control off, each get no prompt, no notification and no answer; the prompt asks for the whole screen; approving starts the service; a turn does not ask twice; the viewer stopping, the screen turning off and a minute passing each take the prompt away; the notification fallback; `smooth` in `screen-info` only for a viewer that sent `codecs`.
- `KeepAwakeTest`: held only while viewed in smooth sharing, let go after 10 idle minutes and by `lock`, taken again on input, and neither restarted nor a Lock undone by a device that only comes back. `SmoothScreenHoldTest`: the wake lock itself, taken and let go by the capture service; nothing taken once sharing has ended; a second approval while sharing starts nothing.
- `PhoneScreenCaptureTest`: Permissions asks for the whole screen on API 34+, and not before.
- PC, `PhoneViewTests`: the row appears only when the phone sends `smooth`, asks and stops, reports no answer after 5 s, and has words for every state. `PrivacyTests`: the same through the real window, and a phone this PC has not approved gets no row and no say.

**On the S26** (Android 17), on the rig, 2026-11-11: the row asked; the prompt appeared with no app window open and showed in the picture; a tap sent as the viewer's own pressed *Share screen*; smooth sharing started, the viewer was told and its readout said "Smooth sharing"; the row again stopped it and the toast said from which PC; *Cancel* gave "declined", and asking again at once was refused with the reason; a prompt left alone went after 60 s; a prompt went when the viewer closed; a turn while the prompt was up left one prompt and the same activity; a second request while one was up showed no second prompt; the wake lock was held while viewed, let go when the viewer closed and taken again when it came back.

Not seen there: the phone locking (it ends the sharing and is the reason for 1.5), the screen actually staying on past its timeout, the ten idle minutes, the notification fallback, and a real mouse click on the prompt (the rig has no mouse; the tap went through the relay as the viewer's). The S20 has seen none of it.

A review the same day reported seven findings, all confirmed and fixed, in the text above where it says "review, 2026-11-11" and in 1.4's row (`only`). None of those fixes has run on a phone: they were made with the rig down, on unit tests alone. What they leave to be seen on a phone comes to: the row ticked and greyed, and a stop refused, on a phone with its Accessibility service off; a PC that reconnects not getting ten more minutes; a prompt going when smooth sharing is turned on at the phone under it; the wait growing after a second refusal; and the words a PC gets from a phone that cannot ask.

**Done when** a PC user can go from opening the viewer to smooth sharing without touching an unlocked phone. On the S26 that is so.

### Phase 2. Video instead of JPEGs

Run check C1 first; it decides whether D9 is needed. Then C3. Step 0.7 must be in place.

**2.1 The encoder on the phone.** Built on 2026-11-11, after check C3 (section 7), whose answers were all yes. The text below describes what was built.

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

The classes that touch Android decide nothing, and the ones that decide touch nothing of Android's:

| Class | What it is |
|---|---|
| `PhoneVideoEncoder`, `VideoGl` | MediaCodec and the OpenGL step. Set up as appendix A lists; whatever goes wrong is thrown or reported. Checked on the S26. |
| `PhoneVideo` | Everything decided about the encoder: which to try, when a picture is drawn, what comes out as, and what counts as failure. Plain; it knows the encoder only as an interface (`VideoPipe`), so every path is tested with a made-up one. |
| `VideoPacer` | When the texture is drawn into the encoder, and as what. Plain. |
| `VideoSetup` | The order encoders and sizes are tried in, and the bitrate. Plain. |
| `AnnexB` | Reads NAL units: puts parameter sets in front of keyframes, leaves filler out. Plain. |

- One thread, the capture thread, owns the EGL context, the texture and all drawing. EGL config with `EGL_RECORDABLE_ANDROID`; `EGLExt.eglPresentationTimeANDROID` before each swap; `SurfaceTexture.setDefaultBufferSize` when the texture is made. The fragment shader asks for high precision where the phone has it: at medium a picture 2,340 pixels tall is sampled off by a fraction of a pixel. On the S26 a pattern of one-pixel lines and flat colours came back exact.
- The pacer is a class of its own, `VideoPacer`, not `FramePacer<Unit>`: there is no picture to hold, since the texture always has the newest, and it also decides keyframes and repeats. At most one draw per 33 ms. Every encode waits for room (`PhoneScreenCapture.hasRoom`, which is `InFlight` and the app's own queue): new pictures, keyframes and repeats alike. While there is none it looks again every 15 ms. Nothing is drawn before the screen's first picture has reached the texture, or the first keyframe would be black.
- The capture time is Android's stamp on the texture's picture (checked as for JPEGs, `PhoneFrame.captureTime`), goes through the encoder as the presentation timestamp and comes back with the output buffer, where `captureMs` is read. Timestamps always increase. A repeat uses the current time and sets the repeat flag. So does a picture that changed during a pause, when pictures go again: nobody waited for it.
- What was drawn and has not come out is kept in a small ledger by timestamp, which is how a frame coming out is known to be a repeat, and how a dead encoder is noticed.
- Encoder choice and settings are in appendix A. In short: hardware H.264, constant bitrate where offered, no B-frames, 30 pictures a second.
- Size: the capture size (`StreamSize`), each side made even. `VideoCapabilities.areSizeAndRateSupported` is asked first. Each size is tried on every hardware encoder, then 0.75× and 0.5× (where a software encoder may be tried too, at a long side of 1280 or less), then JPEG. At a smaller size the mirror stays full size and the OpenGL step scales; `screen-info` and the frame header say the encoded size.
- Keyframes, as D8 lists them: `PARAMETER_KEY_REQUEST_SYNC_FRAME`, then the texture is drawn again even if the screen has not changed. On the S26 the request takes hold on the very next frame drawn. A viewer's `request-keyframe` is honoured at most once a second in total, and one that comes sooner is honoured when the second is up, not dropped. The phone's own keyframes (a stream starting, a viewer joining a running stream, a picture the socket would not take, the last picture unacknowledged) do not wait for that. A request is dropped when it names another stream, and when a keyframe sent in the last second has not yet been acknowledged by the viewer that asks: the two have crossed.
- If a keyframe is asked for and an ordinary picture comes out, it is asked for again. Eight in a row count as a failure.
- A still screen: the picture is drawn again 150 ms and 600 ms after the last change or keyframe, each marked repeat, then nothing. On the S26 every picture came out by itself about 15 ms after it was drawn, so there the repeats only sharpen; on an encoder that holds one back they also push it out.
- The encoder gives its SPS and PPS once, in a buffer of their own (and again in the output format, which is read too). They are kept and put in front of every keyframe that does not already begin with them, whether or not it was asked for: the frame is looked at, not the request. A leading access unit delimiter stays first. Filler (NAL type 12) is left out. `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` is not used: an encoder without it fails to configure.
- **What counts as the encoder failing**, each ending the same way (2.2): nothing starts at any size; a draw throws or the encoder reports an error; a draw does not come back within 2 s (an encoder that has stopped taking pictures blocks the swap, so the encoder is released from another thread to free it); the screen gives the texture no picture within 3 s of being attached while the phone's screen is on; three or more pictures have gone in over 2 s and nothing has come out; a keyframe comes with no parameter sets known; eight keyframe requests in a row are ignored. An encoder that keeps its newest picture until the next comes, or passes one over, is not failing.

**What it costs, on the S26** (2026-11-11, the capture service's own pipeline on the real screen, written to a file, no viewer):

| | |
|---|---|
| Android's stamp on the picture to the encoded frame in the app's hands | 21 to 23 ms median, 27 to 28 ms at the 95th, about 50 ms for the first frame of a new encoder |
| The OpenGL draw and swap | 0.6 ms median, 4 ms at most |
| Starting the encoder and OpenGL | 22 to 35 ms, and the first picture 23 to 27 ms after the screen is attached |
| Processor, scrolling Settings at 30 pictures a second | The app about 12% of one core, the phone's codec service about 16% of one core. The app's own processor time came to 4.4 ms a picture, file writing included. |
| Processor, still screen | None: nothing is drawn. |
| Data, scrolling Settings | 11 KB a picture (median), 22 KB at the 95th, about 2.1 Mbit/s at 30 a second. JPEG was 96 KB a picture at 10 a second (section 11). |
| A keyframe | 15 to 30 KB for a still home screen or list, 95 to 165 KB when asked for in the middle of a busy picture |

**2.2 The capture service.** Built on 2026-11-11. `ScreenCaptureService` keeps the projection and the virtual display and has a second output next to the `ImageReader`. `ScreenOutputs` (plain, tested) decides which of the two the screen is mirrored into, and `VideoWish` (plain) whether video is to be tried.
- The format is chosen by `StreamFormat` (5.3), from what every current viewer offered. `PhoneScreenCapture.send` carries JPEGs and `sendVideo` H.264; a stream is one or the other, and changing is a new stream id. A v1.0.53 PC, a PC that does not offer `h264` and a phone get exactly what they got before.
- A size change (0.6) ends the encoder before the mirror takes its new shape and starts one at the new size, with a new OpenGL context; its first frame is a keyframe. The stream id stays, and `screen-info` with the new size goes first.
- A short pause of the gate stops the pacer and nothing else. On resume the stream gets a new id and a keyframe from the same encoder. **Changed from the plan:** when nobody has viewed for 5 s (0.6's idle rule) the encoder and its OpenGL context are let go of, not only the mirror. A phone has few hardware encoders and its camera may want one. Starting again costs about 60 ms.
- Any encoder or GL failure (2.1) tears the video output down, points the mirror at the `ImageReader`, which makes Android draw the screen at once, and tells the viewers of a new JPEG stream. The phone's connection history gets a line, "Smooth video went back to pictures", with the reason and the raw error. A failed try is not made again until pictures next start or the viewers next come to agree on H.264; two failures in one run of the service keep it on JPEG until the service restarts.
- An H.264 frame, once encoded, is always sent (D2): `sendBinary` does not refuse it for queue size. If the socket itself will not take one, it takes no sequence number and nothing but a keyframe is sent after it. The same holds at the start of every stream, so a stream's first frame is a keyframe whatever the encoder was in the middle of.
- The last frame going unacknowledged (D8) has the screen encoded again as a keyframe marked repeat; `InFlight` times it as it does a JPEG sent again.
- When sharing stops, the display is released first, then the encoder and the context on the capture thread, which then ends. Nothing is started after that.
- Tests: `PhoneVideoTest` (every way of failing, with a made-up encoder), `VideoPacerTest`, `VideoSetupTest` (with `StreamFormat` and `VideoWish`), `AnnexBTest`, `ScreenOutputsTest` (viewers coming and going, a pause, a turn, a failure, sharing stopping), `PhoneH264StreamTest` (who gets H.264, stream ids, a frame never dropped, keyframe requests), and a case in `ConnectionHistoryTest`.
- **On the S26** (2026-11-11, no viewer: a throwaway addition stood in for one and wrote the frames to a file): 30 frames a second while Settings was scrolled; a keyframe on request on a still screen; a frame thrown away on purpose was followed by a keyframe 39 ms later; a 2 s pause kept the encoder and resumed with a keyframe; a turn each way gave a new encoder and a keyframe at the new size; the encoder's thread was gone 5 s after the last "viewer" and both threads were gone when sharing stopped; a failure forced in the middle put the mirror back on the `ImageReader`, and a second one kept it there. Windows' decoder (the C1 tool) gave every picture of those files as it was fed.
- **Not seen:** anything with a real viewer (acknowledgements, holding back, `request-keyframe` over the wire, the PC drawing it), which waits for the rig; the S20 and any other maker's encoder; an hour of it for heat. `SCRATCH\step-10\E2E-CHECKS.md` lists the checks.

**2.3 The decoder on the PC.** Built: `H264Decoder.cs`, `Nv12Converter.cs` and `MediaFoundationStartup.cs`, with their checks in `H264Tests.cs` of `RemoteDesktopWin.PhoneViewTests`. Nothing calls them until 2.4.
- **The decoder** is Microsoft's H.264 decoder, made by its class id (`CLSID_CMSH264DecoderMFT`) so that it is the software one and not whatever enumerates first. No Direct3D device is given to it. Media Foundation is started through `MediaFoundationStartup`, which `H264Encoder.cs` now uses too.
- **Set on it, before the types:** `CODECAPI_AVLowLatencyMode` 1 as a 32-bit integer (the GUID `H264Encoder.cs` calls `MFLowLatency`), and `CODECAPI_AVDecVideoMaxCodedWidth` and `…Height` at 4096. Low-latency mode is required: without it the decoder runs 31 pictures behind (C1). The size limits change nothing on Windows 11 and are there for an older decoder with a lower limit, which nobody has tried.
- **Input:** H.264, progressive, and no size. The decoder reads the size from the stream, at the start and whenever it changes, so a turned phone needs no new decoder. The plan first put the frame header's size on the input type. That is not needed, and a wrong one does no harm either.
- **Filler data is taken out** of every access unit before it is fed (NAL units of type 12). An encoder holding a constant bitrate pads with them, and the decoder keeps a picture whose access unit ends in one until the next arrives (C1). The phone should leave them out before sending too (2.1): they are bytes with nothing in them.
- **Output:** NV12 into one sample of our own, reused for every picture (in software the decoder brings none). `MF_E_TRANSFORM_STREAM_CHANGE` means "choose the output type again": at the first picture and at every change of size. Only then are the padded size, the visible rectangle (`MF_MT_MINIMUM_DISPLAY_APERTURE`), the row stride (`MF_MT_DEFAULT_STRIDE`) and the colours (`MF_MT_YUV_MATRIX`, `MF_MT_VIDEO_NOMINAL_RANGE`) true; before it the output type only repeats the input type. 1080×2340 comes out as 1088×2352. A stream that does not say its colours is read as Android reads one: BT.601 up to 720×576, BT.709 above, limited range.
- **One call does a frame:** `Decode(accessUnit, tag, handler)` feeds one access unit and calls the handler with each picture that comes out, which is one. The viewer passes `seq` as the tag and it comes back on the picture. A new input buffer is made for every access unit, because the decoder still reads it after `ProcessInput` returns. The answer is one of:
  - `Picture`;
  - `NoPicture`: taken, nothing out yet, not a fault;
  - `NeedsKeyframe`: the decoder is at a fresh start (new, after `Reset`, after a failure) and this was not a keyframe with SPS and PPS in the same access unit, so it was not looked at;
  - `Failed`: the decoder reported an error, **or a keyframe went in and no picture came out**. The decoder has been reset and wants a keyframe;
  - `Disposed`.
- **No picture larger than 1440 × 3200** (4,608,000 pixels, padding included), either way round. Windows itself decodes up to 4096 × 4096, and a picture is three arrays of 4 bytes a pixel on its way to the screen, so a stream that changed to an absurd size with every keyframe could have had the PC making 64 MB arrays over and over. A phone's shorter side is 1080 at most, so the tallest real screen is well inside. A larger picture is refused when the decoder announces it, before anything is made for it, and counts as a failure.
- **A picture that is a moment late.** Ordinary frames give their picture at once, on a busy processor too. The first picture after a size announcement does not always: for a millisecond or ten the decoder has nothing to give, most often when another decoder has just been let go, as when a window is closed and one opened. So after an announcement, and after any keyframe, `Decode` asks again for up to 150 ms before it believes there is no picture. A keyframe the decoder really cannot decode costs that long once.
- **Why the last rule.** The decoder itself reports no error for a missing frame, a stream that starts without a keyframe, a keyframe without its parameter sets, or a size it cannot take. It gives nothing and says nothing. A keyframe that yields no picture is the one sure sign that this PC cannot decode the stream, and it is what makes D9 unnecessary. Gaps are still the viewer's to see, from the sequence numbers: `Reset`, then ask for a keyframe (2.4).
- **Threads.** `Decode` and `Reset` may come from any thread, one call at a time; a second call while one is in progress throws. `Dispose` may come from any thread at any moment and never waits. If a frame is being decoded, the decoder is torn down when that call returns, after the handler has finished, on the decoding thread. A picture's memory is the decoder's and is good only inside the handler, so the handler converts it there.
- **`Nv12Converter`:** NV12 to BGRA in unsafe code, with look-up tables, for BT.709 and BT.601 in limited and full range. It writes only the visible rectangle. It can be given a band of rows, so several threads can share a picture, and its inner loop carries nothing from one pair of pixels to the next, which is what vector code needs. Neither has been needed: about 3 to 6 ms a picture on one thread (C1). It may be called from any thread.
- **`H264Decoder.IsAvailable`** says whether this Windows can decode H.264 at all, found out once. The N editions without the Media Feature Pack cannot, and nor can a Windows Server without Media Foundation. The viewer then never offers `h264`.
- **Not built: hardware decoding.** Giving the same transform a Direct3D 11 device makes the graphics card decode. In C1 that was no quicker by the clock (4.0 to 4.7 ms a picture against 2.5 to 5.3 ms), because the picture has to be copied back to memory for WPF, but it took about a tenth of the processor time (0.4 to 0.9 ms a picture against 4 to 9 ms). It is some twenty lines: a device, a device manager, one message to the transform, and reading the picture through `IMF2DBuffer`. It was tried on one AMD card only. Something for a PC that turns out too slow.
- **Not built either:** `CODECAPI_AVDecNumWorkerThreads`. Left to itself the decoder used twice the processor time of a two-thread decoder to save half a millisecond a picture.
- **The checks** make their streams on the spot: a drawn pattern goes through the app's own `H264Encoder` and back. No recording of a phone is used. They cover 320×240, 322×242, 1080×2400, 1080×2340 and 2400×1080 (size, visible rectangle, patches of known colour in every corner, one picture for each access unit), filler, a fresh start, a reset, rubbish in the stream, a decoder limited below the stream's size, a size that changes mid-stream, the handler's rules, disposing while a frame is being decoded, and threads. Each check runs with the graphics card's encoder and with Windows' software one, whichever the PC has. Where there is no H.264 decoder, or no encoder at all, they print SKIP and pass. (The app itself still sends only from a graphics card's encoder: the software one was meant as its fall-back, never worked, and now does, but using it is a decision not taken. `H264Encoder.cs` says why.)

**2.4 The viewer.** Built on the PC: `PhoneVideoStream.cs` (the rules), `PhoneVideoPictures.cs` (decoded pictures on their way to the window), and video in `PhoneFeed.cs`, `PhoneViewWindow` and `MainWindow`. Checked without a phone: `VideoFeedTests.cs` puts real video, made on the spot, through the real feed, and `PrivacyLifecycleTests` puts it through the real main window and phone window, never shown. Not seen with a phone: the phone's half (2.1, 2.2) is not built. What the two halves agreed is in `PROTOCOL.md`, "Phone video".
- **The offer.** `start-view` carries `codecs: ["h264", "jpeg"]` when the PC has approved the phone, Windows has a decoder (`H264Decoder.IsAvailable`, found out on the feed's thread as the app starts, never on the window's) and the decoder has not been given up. Otherwise it is as before. If a window gets its `start-view` out before the answer is known, the phone is asked again when it is.
- **Three threads.** The socket's thread checks each frame against the stream, acknowledges it and puts it in a queue. That is all it does, so it never waits for the decoder. The feed's own thread takes frames from the queue one at a time, decodes each with `H264Decoder` and converts its colours. The window's thread copies the newest finished picture into a `WriteableBitmap` at background priority, about a millisecond.
- **Nothing is skipped but a finished picture.** Every H.264 frame is decoded, in order. A JPEG is still newest-only. A decoded picture the window has not drawn by the time the next is ready is replaced by it. Pictures go from the feed's thread to the window's in three arrays that are used again, so there is no 10 MB array per picture.
- **When the chain breaks** `PhoneVideoStream` says so, and is tested without a decoder: a missing `seq`, a stream this viewer comes into part way, a decoder failure, a frame whose header says keyframe and whose bytes do not. Frames are then passed over, still acknowledged, until a keyframe comes. `request-keyframe` goes at once, from the thread that saw it, and again every second until one comes; never more than one a second. It names the stream, so a phone can drop one that crosses a change of stream. The last good picture stays on show meanwhile.
- **Falling behind.** At most 30 frames or 4 MB wait to be decoded, a second's worth. One more and they are all let go and a keyframe is asked for: a phone shown a second late is worse than a short freeze. If a keyframe happens to arrive while five or more frames wait, they are let go without asking, since it needs none of them. The plan first said only "if the feed overflows"; the numbers and the skip are new.
- **Giving up.** Two decoder failures while a phone is viewed and H.264 is given up: `start-view` goes again with `codecs: ["jpeg"]`, and no phone is offered video until the app is restarted. The plan said "in a session" and left open what one is. A failure is rare by design, since frames cannot arrive damaged; the likely cause is a Windows that cannot decode the stream at all, which does not get better by trying each time a window opens. The readout says when it has happened.
- **A turned phone** needs nothing: the decoder takes the new size from the keyframe, whether or not the stream id changes. A new stream id starts afresh from that stream's keyframe. The picture's size comes from the decoded picture itself, never from `screen-info`.
- **While a frame is in the decoder** the window may close, the phone may leave, pause or start another stream, and this PC may stop approving it. The decoder is let go or left alone as the case needs, without waiting for the frame in hand, and when that frame's picture comes out it is shown only if it still belongs to the current stream of the phone being viewed. The checks do each of these at that moment.
- **Acknowledgements are unchanged:** every frame of the stream is acknowledged as it arrives, before it is decoded, drawn or not. So a PC that decodes slowly does not slow the phone; it falls behind and asks for a keyframe, as above.
- **The readout** names the codec, shows how long a picture takes to decode and convert (for JPEG too), and says when a keyframe is being waited for and how often one has been asked for.
- **A phone that sends only JPEG, a phone on v1.0.53, and a PC without a decoder see no difference:** the first two are sent the same `start-view` plus one word in a list they do not read past, and the third sends exactly what it sent before.

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

**C1. Can Windows decode what the phones encode?** Run on 2026-11-11: **yes, every size, in software, with nothing raised. D9 is not needed.**

Theo agreed on 2026-11-10, for the S26 only. Six clips were recorded from its hardware encoder (`c2.qti.avc.encoder`) with the phone's own recorder, while a Settings list was dragged (the recorder writes a frame only when the screen changes):

```
adb exec-out screenrecord --output-format=h264 --time-limit 2 - > clip.h264
```

That is run from Git Bash or cmd. Windows PowerShell's `>` rewrites binary output as text and would ruin the clip, which would look like "Windows can't decode it". The recorder's `--size` gave the other sizes and `--bit-rate` the lower rate: two seconds at each of 1080×2340, 1080×2400, 2340×1080 and 2400×1080 at the recorder's 20 Mbit/s, and five seconds at 1080×2340 and 2340×1080 at 3 Mbit/s. The phone accepted every size asked for. The clips show the phone's screen, so they stayed in a scratch folder and were never committed.

A scratch program created the decoder as step 2.3 then described it and fed each clip one access unit at a time. The PC was an Intel Core i7-12700K (12 cores) with an AMD Radeon RX 6600, Windows 11 build 26200, decoder `msmpeg2vdec.dll` 10.0.26100.9711. Another build was running on it at the time, so the times are a little high rather than low.

| Size | Software decoder | With a Direct3D 11 device (hardware) | Had to be set |
|---|---|---|---|
| 1080×2340 | Accepted | Accepted | Nothing |
| 1080×2400 | Accepted | Accepted | Nothing |
| 2340×1080 | Accepted | Accepted | Nothing |
| 2400×1080 | Accepted | Accepted | Nothing |

- **No size limit was met.** Pictures generated on the PC went through as well, up to 1080×3840, 3840×1080, 2304×4096 and 4096×2304, in software and in hardware. `CODECAPI_AVDecVideoMaxCodedWidth` and `…Height` were never needed. They do work: set to 1920×1088, the decoder refused the 1080×2400 clip.
- **What the decoder reports**, after the first picture: 1080×2340 comes out padded to 1088×2352, visible part 1080×2340 at (0, 0) (`MF_MT_MINIMUM_DISPLAY_APERTURE`), stride 1088 (`MF_MT_DEFAULT_STRIDE`). 1080×2400 is 1088×2400, stride 1088. 2340×1080 is 2352×1088, stride 2352. 2400×1080 is 2400×1088, stride 2400. Colours: `MF_MT_YUV_MATRIX` 1 (BT.709) and `MF_MT_VIDEO_NOMINAL_RANGE` 2 (16 to 235), which is what the stream itself says. Before the first picture the output type only repeats what the input type said, with no visible part, stride or colours.
- **A picture comes out for every access unit fed**, at once, carrying the sample time that went in. (One exception was found later, while 2.4 was built, and only with the processor busy: the first picture after the decoder announces a size, which is every stream's first, can come a few milliseconds after its access unit went in. See 2.3.) That is with `CODECAPI_AVLowLatencyMode` on. With it off the software decoder held back 31 pictures and the hardware one 15, although the stream says there is no reordering.
- **One thing does make it hold a picture back: filler data.** If an access unit ends with a filler NAL unit (type 12), the decoder keeps that picture until the next access unit arrives. Software and hardware both. Take the filler out and the picture comes at once. The S26's recorder clips have no filler. The PC's own encoder puts it in at a constant bitrate when the picture is simple, and a phone encoder in CBR mode may do the same.
- **Software and hardware give the same picture.** The last picture of each clip matched between the two (a checksum over a sample of its bytes).
- **Time per picture, by the clock** (median; 95 in 100 were within a millisecond or two of it):

| Step | Time |
|---|---|
| Decode in software, 3 Mbit/s | 2.5 to 3.6 ms. A keyframe 7 to 11 ms. |
| Decode in software, 20 Mbit/s | 3.9 to 5.3 ms. A keyframe 9 to 18 ms. |
| Decode in hardware, either bitrate | 4.0 to 4.7 ms, the copy back from the graphics card included. The first picture 20 to 28 ms. |
| NV12 to BGRA, one thread, plain code with tables | 3.0 to 5.8 ms |
| The same in four bands of rows on four threads | 0.9 to 2.3 ms |
| Drawing: `WritePixels` of the whole 10 MB picture, UI thread | 0.8 to 1.3 ms |
| Drawing: WPF's own render thread, per picture | 2 to 4 ms of processor time |

- **Processor time per picture**, at 3 Mbit/s. Software decoding takes about 8 ms summed over the decoder's threads when it picks their number itself, 4.0 ms with one thread (3.8 ms by the clock) and 4.8 ms with two (3.2 ms by the clock). Hardware decoding takes 0.4 to 0.9 ms. Decode, convert and draw together come to 11 to 19 ms of processor time a picture, spread over the decoder's threads, the feed's thread, the UI thread and WPF's render thread. At 30 pictures a second that is a third to a half of one core on this PC.
- **Creating the decoder** takes 1 to 3 ms in software (about 20 ms the first time in a process) and 35 to 80 ms with a Direct3D device.
- **The drawing figures** are from a real top-level WPF window, shown and drawn by the graphics card, 797×1767 pixels at 150% scaling, with an `Image` showing a `WriteableBitmap`. It was cloaked (Windows left it off the screen) and could not be activated. Reading the window's surface back showed WPF was drawing what was written. It kept up with 60 pictures a second. Not measured: the graphics card's own time, and Windows' work to put a visible window on the screen. Trust the UI thread figure; take the render thread figure as about right.
- **Where WPF draws in software** (a Remote Desktop session, no usable graphics card) its render thread took about 33 ms a picture and managed 28 a second. That is the same for JPEG pictures of this size today, so it is not new, but on such a PC drawing is the limit, not decoding.

**What the recorder's stream looks like:** High profile, level 5.2, CABAC, one reference frame, no B-frames, picture order type 2, one slice per picture. The colour description says BT.709, limited range. It carries a bitstream restriction (no reordering, one frame buffered). SPS and PPS come once, in front of the first keyframe; the 3 Mbit/s clips have two more keyframes each and those have no parameter sets. No delimiters, no SEI, no filler. Four-byte start codes. About 117 pictures a second (the phone's screen runs at 120).

**How the decoder treats a broken stream.** It never reports an error for any of these:
- A missing access unit: it carries on and gives a picture for every later one. Only the sequence numbers show the gap.
- After a flush: nothing comes out until the next keyframe. It still knows the parameter sets from before the flush.
- A new decoder fed a stream with no parameter sets: nothing comes out, ever.
- A size above its limit, with no size on the input type: nothing comes out. With the size on the input type it is refused at set-up (`MF_E_INVALIDMEDIATYPE`).
- A new size mid-stream (a turned phone), tall to wide and wide to tall: `MF_E_TRANSFORM_STREAM_CHANGE` once more, then pictures. No new decoder, in software and in hardware.

**Still to take from a clip made with this app's own settings (C3).** The recorder sets the encoder up its own way, so:
- Does the decoder hold a picture back? Look for NAL units of type 12 in the clip, which is what CBR may add.
- Does the SPS still say BT.709, limited range, and no reordering?

**Not checked:** Windows 10, and any Windows 11 older than this one, where the limit of 2304 lines that Mozilla wrote about may still be; hardware decoding on Intel or NVIDIA graphics; a slow PC.

**C2. Does the consent prompt work remotely?** Run on the S26 (Android 17) on 2026-11-11, on the rig, with a throwaway addition to the debug build that was never committed. A made-up message from the viewer made the phone start a request activity as step 1.2 describes it. Every tap, drag and Back was sent as a viewer's own message, so each went through the app's gesture injection and none through adb. Afterwards the debug app was put back to the build from `main` and the phone was left not sharing.

| Question | Result |
|---|---|
| Does the prompt appear when started from the Accessibility service with no app window open? | **Yes.** With only the home screen in front and no window of the app anywhere, the prompt was up in 0.1 s. Android's log gives its reason for allowing the start as `BAL_ALLOW_TOKEN`: the pass a process holds while the system has its Accessibility service bound. Starting from the application's context instead of the service's got the same pass. |
| Does it show in Accessibility screenshots? | **Yes**, whole and readable in the viewer, and so does the list its mode chooser opens. |
| Does a tap injected through the Accessibility service press its button? | **Yes.** A viewer's `tap` on *Share screen* gave the request activity `RESULT_OK`, `ScreenCaptureService` started from that callback, and the viewer was told `capture: "screen-share"` 0.2 s after the tap. A tap on *Cancel*, and Back, each gave `RESULT_CANCELED`. A tap opened the mode chooser too. |
| Does asking for the whole screen remove the single-app choice on a Samsung? | **Yes, in effect.** The chooser is still drawn, already on *Share entire screen*. Its other line reads "Share one app. Not supported by the app" and cannot be picked. One tap on *Share screen* is all it takes. Asked for the way Permissions asks today, the prompt comes up on *Share one app* with a *Next* button, so step 1.1 is needed. |
| Does a screen wake lock held by the service keep the screen on? | **Probably. Not seen.** Android took the lock from the app while it was sharing, dimmed or bright, did not mark it disabled, and counted it as keeping the phone awake (`dumpsys power`: `mWakeLockSummary` `0x25` dimmed and `0x23` bright, both with the stay-awake bit). But the S26 was on its charger with its owner's "stay awake while charging" on, which holds the screen on by itself. So the screen outlasting its timeout was not watched. That takes the phone off its charger for longer than its timeout, which is 5 minutes on the S26. |

Also seen:

- **With the phone on its side, the prompt's buttons are out of view.** In landscape, over Settings, the prompt is taller than the screen. Its title, chooser and warning show; *Cancel* and *Share screen* are below the edge. A viewer's drag upwards scrolls it, and the buttons can then be pressed.
- **A prompt nobody answers stays.** It stood untouched for over two minutes. Back from a viewer dismisses it, and the activity is told `RESULT_CANCELED`. While the chooser's list is open, the first Back closes only the list.
- **Turning the phone while the prompt is up restarts the request activity under it.** The prompt stays and its answer reaches the new activity: sharing started from it. So the activity must not ask again when it is created a second time.
- **A second request while a prompt is up shows no second prompt.** Android hands the start to the task that is already there.
- Stopping the service on a viewer's message had the viewer back on Accessibility pictures in under 0.1 s.

Not answered: the S20 (Android 13), where nothing is installed; whether other makers' phones allow the start; what the screen turning off does to a prompt that is up, which would have locked the phone.

**C3. Does the GL path behave on the S26?** Run on the S26 (Android 17) on 2026-11-11, over adb, with no viewer and no rig, by a throwaway addition to the debug build that was never committed. It drove the app's real encoder classes (2.1): first on a generated pattern, then on the real screen through the capture service's own virtual display, with the home screen and Settings in front. Android's prompt was pressed over adb, for this check only. Afterwards the debug app was put back to the build without the addition and the phone was left not sharing.

| Question | Result |
|---|---|
| Does a picture arrive as soon as the surface is attached, without the screen changing? | **Yes.** 23 to 27 ms after the virtual display was pointed at the texture's surface, on a still screen, every time (four attaches). Nothing more came while the screen stayed still. |
| Does drawing the same texture again after a keyframe request give a keyframe on a still screen? | **Yes.** `PARAMETER_KEY_REQUEST_SYNC_FRAME` and one draw of the unchanged texture gave an IDR picture 31 to 40 ms later, every time. The request takes hold on the very next frame drawn. |
| Does a frame come out without another being fed in after it? | **Yes.** Each picture came out about 15 ms after it was drawn (median; 30 ms at the 95th). A single draw on a still screen came out alone in about 20 ms. |
| Does `VirtualDisplay.resize` after a turn need new consent? | **No.** The sharing went on through a turn each way with no prompt. A new encoder at 2340 × 1080 opened in 25 ms and had its first picture 23 ms after being attached. |
| Time from capture to encoded frame | **About 20 ms.** From Android's stamp on the picture to the encoded frame: 19 to 23 ms median, 26 to 28 ms at the 95th, 45 to 57 ms for the first frame of a new encoder. The stamp is on the uptime clock, about 1 ms before the picture reaches the app. The OpenGL draw is 0.6 ms. |
| Can it write its stream to a file, for C1's second pass? | **Yes.** `SCRATCH\step-10\app-clip-1080x2340.h264` (the pattern) and two clips of the real screen, each as it would go into frames. Windows' decoder gave every picture of all three as it was fed, 698 of 698, none held back. |

Also seen:

- **The encoders.** `c2.qti.avc.encoder` and `OMX.qcom.video.encoder.avc` are hardware, offer a constant bitrate and take 1080 × 2340 either way round at 30 a second. The two software encoders refuse that size, as expected.
- **The stream.** High profile, level 5.0, CABAC, one slice a picture, no B-frames, `max_num_reorder_frames` 0. The SPS says BT.709, limited range. No filler, no SEI and no delimiters in 698 frames at a constant bitrate. SPS and PPS come once, in a buffer of their own; IDR pictures come bare, so the app has to put them in front. With the app's settings no keyframe came that was not asked for.
- **The picture.** The right way up. Flat patches of ten known colours decoded to within 1 of their values, and lines one pixel wide came back as drawn.
- **A still screen costs nothing**, and the encoder gave no error when nothing was fed for many seconds.

Not answered: the S20's Exynos encoder and Android 13, where nothing is installed; any other maker's phone; anything that needs a viewer.

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
| Windows refuses pictures taller than 2304 px | Not on Windows 11 build 26200, up to 4096 either way (C1). Windows 10 and older Windows 11 untried | The size attributes are set to 4096 in any case. A PC that still cannot decode a phone's size gives no picture for the keyframe, which counts as a decoder failure, and falls back to JPEG after two (2.4). D9 is not needed. |
| The Windows decoder holds frames back despite low-latency mode | It does in one case (C1): when an access unit ends in filler data | The PC takes filler out before decoding, and the phone should not send it. C3 looks for it in the app's own stream. Apart from that, only the first picture after a size announcement can be a few milliseconds late, and the decoder waits for it (2.3). |
| A phone's hardware encoder misbehaves (odd sizes, ignored settings, holds a frame back) | Medium across all Android phones. The S20's Exynos encoder is not tried before release (section 8). | Size fallback, then JPEG. Two failures keep the session on JPEG. The repeats flush a held frame. |
| The consent prompt can't be started or clicked remotely on some Android version | Medium. Not so on the S26 with Android 17 (C2). | Notification fallback, built. At worst one tap on the phone, which is still less than today. |
| Smooth sharing ends at every lock on Android 15+ | Certain | Falls back to Accessibility viewing by itself; the viewer says why and the menu row can start it again. Step 1.5 stops the phone locking from its own timeout while it is in use. |
| Android 15+ blanks sensitive content (password fields, one-time codes) during screen sharing | Certain on those versions | Switching back from the menu row (1.4), and a note in the README. |
| A phone kept awake and unlocked longer than its owner expects | Follows from 1.5 | Only in smooth sharing, only while viewed, and released after 10 minutes without input. Agreed by Theo (section 12). |
| 30 pictures a second heats the phone or drains it | Low: hardware encoding, and a still screen costs nothing | Mirroring stops when nobody views (0.6). Measured in the hour-long check. Lower the cap if needed. |
| The PC can't keep up (decode, convert and upload about 10 MB per picture) | Low on a desktop: 11 to 19 ms of processor time a picture, on several threads, of the 33 ms each has (C1). A slow PC is untried | It then falls a second behind, lets the waiting frames go and asks for a keyframe (2.4), which shows as a freeze now and then. Hardware decoding (2.3), converting in bands of rows, or 3.2. |
| The CI machine lacks the Windows codecs | Likely | Codec tests print SKIP there; the plain-logic tests still run. |
| The readout's delay is off | By half the difference between the two directions' travel time; a few ms on a home network | Shown as "±" beside the figure. |

## 11. Baseline measurements

Fill in during Phase 0 from the Shift+F8 copy. One row per phone and mode, the same test each time: scroll a long page for 20 seconds, then leave it still for 10.

| Date | Build | Phone | Mode and format | Upload limit | Pictures/s | KB per picture | Delay median / worst (ms) | Round trip (ms) | Phone queue, worst (KB) | Notes |
|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-11 | `main` at `0a1ff90`, debug builds, with all of Phase 0 in | S26 | Accessibility viewing, type `5` JPEG | none | 2.4 | 91 | 103 / 121 (± 2) | 4 to 114 | 89 | **On the rig: the relay ran on this PC, over the home Wi-Fi, not through the internet.** Scrolling Settings. Still for 10 s: no pictures at all. No frames lost. |
| 2026-11-11 | the same | S26 | Smooth sharing, type `5` JPEG | none | 10 | 96 | 94 / 136 (± 2) | 4 to 96 | 0 | **On the rig, as above.** The last picture of the scroll was 273 ms old: it had waited its turn in the pacer. No frames lost. |

How to read these:

- They were read off the readout once a second through UI Automation, not copied with Shift+F8: the rig cannot hold Shift down in the viewer. The copy's text was checked off screen and in tests.
- The delay runs from the moment the phone has the picture to the moment the PC draws it. The wait for the next capture is on top: up to 400 ms in Accessibility viewing, which is most of what a person feels, and up to 100 ms in smooth sharing.
- With the relay on the same network the trip is a few ms, so nearly all of the 0.1 s is the phone copying and compressing the picture and the PC decoding it. Through the internet the round trip is added, and on a slow upload the time a picture waits, which is what these figures cannot show.
- There is no "before" row. v1.0.53 has no readout and stamps no picture. What can be compared with it is pictures a second and KB per picture, by eye or from the relay.

Still to take, by hand (see the end of Phase 0): both phones through the production relay, the S20 at all, and the run with the upload limited to about 2 Mbit/s.

Send buffer sizes (`adb shell cat /proc/sys/net/ipv4/tcp_wmem`): S20 `524288 1048576 4194304`. S26 `2097152 4194304 8388608`, so on the S26 the system holds 4 MB by default where the app cannot see, 8 MB at most.

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

- **The S26's connection to a relay on this PC sometimes goes dead as a view starts. Not explained.** Seen on the rig on 2026-11-11, 8 times in about 80 viewer opens, in two bursts of a few minutes with long clean stretches between. Each time the phone's `screen-info` arrived and its first picture never did, and 20 to 40 s later the phone gave up on the connection ("No reply to a ping within 20 s", or the connection reset) and was back 2 s after that. What is known:
  - It is the network path and not the app's logic. The app's threads were all idle (Java stacks taken during a stall), the pictures had been handed to the socket, and the phone's TCP connection showed them unacknowledged: send queue growing to 850 KB, `cwnd:1`, the retransmit timer doubling (`ss -tin`). Other connections between the same phone and PC (adb) worked throughout, and so did a new connection to the relay.
  - It is not Nagle being off: it happened the same with that change taken out.
  - The app from before Phase 0 did not show it in 11 opens, in a stretch where the new one had just shown it 3 times in 8. But the new app also ran 20 and then 16 opens without it, so that comparison proves little either way. The debug app's connection history has no drop in any earlier rig session.
  - Both devices were on NordVPN with the phone's traffic to this PC going over Wi-Fi directly, and unexplained drops of the S26 are on record from before (2026-11-01).
  - While it lasts, a viewer sees a frozen picture, as it would have with any earlier build, and the phone's hold-back falls back to its queue rule after two seconds, so pictures pile up in the system's buffer until the phone gives up.
  - A second session later the same morning (10:08 to 11:20, the phone unlocked) went looking for it and did not see it once. The viewer was opened 202 times, in blocks of five that took turns between this build (106 opens) and the app from before Phase 0 (96 opens), with first pictures of 53 to 176 KB. Neither stalled. Beside them ran two plain TCP connections from the phone to a listener on this PC, with no app code in them, one as the shell's user and one as the app's own. Each stayed quiet for 12 s and then sent 100 KB, over 350 times each, and every burst arrived whole within a second. A 1,472-byte ping that may not be split went from the PC to the phone and came back, so the Wi-Fi carried full-size packets.
  - So it is still not known whether the new app plays a part. Nothing in 106 opens says it does. But with no stall there was nothing to compare, and whatever made the two bursts earlier (09:04 to 09:21 and 09:33 to 09:38) had gone an hour later.
  - To settle it, the same loop has to be running while a burst is on. At a stall it writes down whether the old app and the plain connections stall too, the stalled connection beside a healthy one, the phone's route and addresses, and the PC's retransmit counts. A packet capture on the PC (`pktmon`) would show more and needs an administrator's shell. The production relay has not been tried.
  - Decided on 2026-11-11: this does not hold up a release. The lost packets are below the app, both builds send their pictures the same way, and neither stalled in 202 opens. Every later rig session watches for it, and runs the loop above if it comes back.

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

PC decoder (Media Foundation), as built in `H264Decoder.cs`:

| Setting | Value |
|---|---|
| Transform | `CLSID_CMSH264DecoderMFT`: Microsoft's H.264 decoder, in software (no Direct3D device is given to it) |
| `CODECAPI_AVLowLatencyMode` | 1, set as a 32-bit integer on the transform's attributes before the types. Required: 31 pictures late without it |
| `CODECAPI_AVDecVideoMaxCodedWidth`, `…Height` | 4096, set the same way. Not needed on Windows 11; for an older decoder with a lower limit, untried |
| Input type | H.264, progressive, no size: the decoder reads it from the stream |
| Input samples | A new buffer for every access unit, with filler data (NAL type 12) taken out |
| Sample time | The frame's `seq` |
| Output type | NV12, chosen again after `MF_E_TRANSFORM_STREAM_CHANGE`; the visible rectangle, stride and colours are read then |
| Output samples | One of our own, reused; made again when the size changes. (With a Direct3D device the decoder would bring its own.) |
| A picture that must come and has not | Asked for again for up to 150 ms, after a size announcement and after a keyframe |

## Appendix B. Files

| Area | Files |
|---|---|
| Phone, changed | `Crypto.kt`, `ConnectionManager.kt`, `PhoneScreenCapture.kt`, `ScreenCaptureService.kt`, `AccessibilityScreenCapture.kt`, `InputAccessibilityService.kt`, `PhoneViewerActivity.kt`, `PermissionsActivity.kt`, `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts` |
| Phone, new | `PhoneFrame.kt`, `PhoneVideoStream.kt` (the viewer's side, for a phone viewing a phone), `NoDelaySocketFactory.kt`, `InFlight.kt`, `PhoneVideoEncoder.kt` and its GL class, `BitrateSteps.kt`, `ScreenShareRequestActivity.kt` |
| PC, changed | `Crypto.cs`, `MainWindow.xaml.cs`, `PhoneViewWindow.xaml`, `PhoneViewWindow.xaml.cs`, `H264Encoder.cs` (shared Media Foundation start-up) |
| PC, new | `PhoneFrame.cs`, `PhoneVideoStream.cs`, `PhoneFeed.cs`, `PhoneStats.cs`, `H264Decoder.cs`, `Nv12Converter.cs`, `MediaFoundationStartup.cs`, `PhoneVideoPictures.cs`, cases added to the menu plan's test project `RemoteDesktopWin.PhoneViewTests` |
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
