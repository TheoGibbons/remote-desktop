# Faster phone viewing from the PC

Written 2026-11-10 against v1.0.53 (`30009c4`), then checked against the code by an independent review and revised the same day (the review log is the last section). Built so far: all of Phase 0 (step 0.1 first, the rest on 2026-11-11), Phase 1, Phase 2 and steps 3.1 (switched off until it has been measured) and 4.1 (all 2026-11-11), whose text below now describes what was built. What they still lack is on a phone and by hand: see the end of each phase and section 11. Steps 3.2, 4.2 and 4.3, and Phase 5, are still a plan.

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
| Turning it on | Once. It survives restarts. | On the phone, every time. Android 14+ asks for consent each session. Android 15 QPR1+ may also end it when the phone locks (the S26 on Android 17 did not, 2026-11-12). |

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
  For Accessibility pictures (4.1) it is five minutes, not 30 s (2026-11-11, after the first session on the S26). A phone that is being looked at changes now and then: the clock in the status bar each minute, a tap after a pause. With 30 s nearly every one of those changes was a keyframe, and a keyframe of a screen that had been still came to 105 to 157 KB, where the change itself is 2 to 5 KB as an ordinary picture and a JPEG of the screen was 70 KB. So a watched, idle phone cost twice what it did with JPEGs. With five minutes, 131 s of use sent keyframes only at the start, at a turn and when one was asked for.

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
2. Every viewer lists `h264` and the phone's encoder works: type `5` H.264. That was only in smooth sharing until step 4.1 was built; it is now so in Accessibility viewing too.
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
`stop-smooth`, from a sender that may ask, stops `ScreenCaptureService`. Not where smooth sharing is the only way the phone can show its screen, which is Android 10 and earlier, and any phone whose Accessibility service is off (review, 2026-11-11): the viewer would be left with no picture and no way to see the prompt that brings it back. Such a phone says `smooth: "only"` in `screen-info`, the PC's row is ticked and greyed with a tooltip saying why, and a `stop-smooth` is answered `unavailable` with the reason. Viewers are told when the word changes with nothing else, which is when the Accessibility service is switched on or off: as first built they heard it only with the next `screen-info`, and until then the row offered a stop the phone refused (`PhoneScreenCapture.tellSmooth`, 2026-11-11). Asked to start on such a phone, a viewer is told that the prompt has to be approved on the phone itself. Whenever smooth sharing ends, every viewer on a current app is sent `smooth-status ended` with a reason: "Smooth video was stopped from *name*.", "Smooth video was stopped on the phone.", or "Smooth video ended because the phone was locked." when the lock screen is up at that moment. Android's `onStop` gives no reason itself. Reasons are whole sentences in the phone's words, and a viewer shows them as they are.

**1.4 The menu row.** Built: `SmoothVideo` in `PhoneMenu.cs`, and the row in `PhoneViewWindow`.
A *Smooth video* row in the viewer's `⋯` menu, shown when `screen-info` carries `smooth` from a phone this PC has approved, and greyed when it says `unavailable`, or `only` (ticked as well: on, and not to be stopped from here, see 1.3). Its tooltip reads: "Asks the phone to share its screen as video. Approve the prompt on the phone; you can click it here. If the phone is on its side, scroll the prompt up to reach its button." Choosing it sends `request-smooth`. The prompt then appears in the picture and can be clicked like anything else. On, the row is ticked, and choosing it sends `stop-smooth`: Android 15+ blanks password fields and one-time codes during screen sharing, so there are moments to switch back. Each `smooth-status` is shown as a toast, in the phone's words where it gave some, and a phone that says nothing for 5 s is reported. Like everything in that menu, the row does not take keyboard focus.

Not built:
- The same row in the phone's own viewer of another phone (`PhoneViewerActivity`). It ignores `smooth-status`.
- A row for a phone on Android 10 or earlier that is not yet sharing. Such a phone has no other way to show its screen, so it sends no picture and no `screen-info`, and the PC never learns that it could ask. Smooth sharing still has to be turned on at that phone, as before.

**1.5 Keep the phone awake while it is viewed in smooth sharing.** Built: `KeepAwake` (a plain class) and a screen wake lock in `ScreenCaptureService`. Agreed by Theo on 2026-11-10, with the 10-minute limit.
On Android 15 QPR1 and later, a phone that sleeps on its usual timeout and locks ends smooth sharing, and smooth sharing cannot be started again until the phone is unlocked and the prompt approved. **That is what Android's notes say; on the S26 (Android 17) it was not so (2026-11-12).** *Lock screen* from the viewer's menu turned the screen off and locked the phone, and smooth sharing went on: no `smooth-status`, the projection still listed by Android, pictures still H.264 from the mirror, for the minute until it was stopped from the PC's row. Stopping worked with the phone asleep and left it asleep, and the wake lock had been let go at the lock. Asked for again on the locked phone, it answered "Unlock the phone first." So "Smooth video ended because the phone was locked." has not been seen on a phone, and nothing here relies on a lock ending the sharing.
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

A review the same day reported seven findings, all confirmed and fixed, in the text above where it says "review, 2026-11-11" and in 1.4's row (`only`). None of those fixes has run on a phone: they were made with the rig down, on unit tests alone. What they leave to be seen on a phone comes to: the row ticked and greyed, and a stop refused, on a phone with its Accessibility service off; a PC that reconnects not getting ten more minutes; a prompt going when smooth sharing is turned on at the phone under it; the wait growing after a second refusal; and the words a PC gets from a phone that cannot ask. Seen on the S26 on 2026-11-12: the wait after a refusal ("Ask again in 7 seconds" a few seconds after the first, "17 seconds" after the second), a prompt that goes with its viewer setting the wait and showing nothing on the phone, and the row greyed and ticked the moment the Accessibility service goes off (one `screen-info`, the same stream, `only`; `on` again when it is back). Still not seen: a prompt going when sharing is turned on at the phone under it, the reconnecting PC's ten minutes, and a phone that cannot ask.

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
| `PhoneVideo` | Everything decided about the encoder: which to try, when a picture is drawn, what comes out as, what counts as failure, and when video is tried again. Plain; it knows the encoder only as an interface (`VideoPipe`), so every path is tested with a made-up one. |
| `VideoLane` | The capture thread's hold on `PhoneVideo`, which has a thread of its own (review, 2026-11-11). Plain. |
| `VideoPacer` | When the texture is drawn into the encoder, and as what. Plain. |
| `VideoSetup` | The order encoders and sizes are tried in, and the bitrate. Plain. |
| `AnnexB` | Reads NAL units: puts parameter sets in front of keyframes, leaves filler out. Plain. |

- One thread owns the EGL context, the texture and all drawing: the video's own, not the capture thread (review, 2026-11-11; see "an encoder that stops answering" below). EGL config with `EGL_RECORDABLE_ANDROID`; `EGLExt.eglPresentationTimeANDROID` before each swap; `SurfaceTexture.setDefaultBufferSize` when the texture is made. The fragment shader asks for high precision where the phone has it: at medium a picture 2,340 pixels tall is sampled off by a fraction of a pixel. On the S26 a pattern of one-pixel lines and flat colours came back exact.
- The pacer is a class of its own, `VideoPacer`, not `FramePacer<Unit>`: there is no picture to hold, since the texture always has the newest, and it also decides keyframes and repeats. At most one draw per 33 ms. Every encode waits for room (`PhoneScreenCapture.hasRoom`, which is `InFlight` and the app's own queue): new pictures, keyframes and repeats alike. While there is none it looks again every 15 ms. Nothing is drawn before the screen's first picture has reached the texture, or the first keyframe would be black.
- The capture time is Android's stamp on the texture's picture (checked as for JPEGs, `PhoneFrame.captureTime`), goes through the encoder as the presentation timestamp and comes back with the output buffer, where `captureMs` is read. Timestamps always increase. A repeat uses the current time and sets the repeat flag. So does a picture that changed during a pause, when pictures go again: nobody waited for it.
- What was drawn and has not come out is kept in a small ledger by timestamp, which is how a frame coming out is known to be a repeat, and how a dead encoder is noticed.
- Encoder choice and settings are in appendix A. In short: hardware H.264, constant bitrate where offered, no B-frames, 30 pictures a second.
- Size: the capture size (`StreamSize`), each side made even. `VideoCapabilities.areSizeAndRateSupported` is asked first, and about the size both ways round: many encoders list their limits for a picture on its side (so wide, and at most so high) and encode the same picture upright all the same, and going by the list alone an upright phone was sent at three quarters size for nothing (review, 2026-11-11). Each size is tried on every hardware encoder, then 0.75× and 0.5× (where a software encoder may be tried too, at a long side of 1280 or less), then JPEG. At a smaller size the mirror stays full size and the OpenGL step scales; `screen-info` and the frame header say the encoded size.
- Keyframes, as D8 lists them: `PARAMETER_KEY_REQUEST_SYNC_FRAME`, then the texture is drawn again even if the screen has not changed. On the S26 the request takes hold on the very next frame drawn. A viewer's `request-keyframe` is honoured at most once a second in total, and one that comes sooner is honoured when the second is up, not dropped. The phone's own keyframes (a stream starting, a viewer joining a running stream, a picture the socket would not take, the last picture unacknowledged) do not wait for that. A request is dropped when it names another stream, and when a keyframe sent in the last second has not yet been acknowledged by the viewer that asks: the two have crossed.
- If a keyframe is asked for and an ordinary picture comes out, it is asked for again a quarter of a second later. Eight such in a row over two seconds or more count as a failure. As first built it was eight in a row whenever, asked again with every picture, and on a changing screen eight went by in a quarter of a second (review, 2026-11-11).
- A still screen: the picture is drawn again 150 ms and 600 ms after the last change or keyframe, each marked repeat, then nothing. On the S26 every picture came out by itself about 15 ms after it was drawn, so there the repeats only sharpen; on an encoder that holds one back they also push it out.
- The encoder gives its SPS and PPS once, in a buffer of their own (and again in the output format, which is read too). They are kept and put in front of every keyframe that does not already begin with them, whether or not it was asked for: the frame is looked at, not the request. A leading access unit delimiter stays first. Filler (NAL type 12) is left out. `KEY_PREPEND_HEADER_TO_SYNC_FRAMES` is not used: an encoder without it fails to configure.
- **What counts as the encoder failing**, each ending the same way (2.2): nothing starts at any size; a draw throws or the encoder reports an error; the screen gives the texture no picture within 3 s of being attached while the phone's screen is on; three or more pictures have gone in over 2 s and nothing has come out; a keyframe comes with no parameter sets known; keyframe requests are ignored for two seconds. An encoder that keeps its newest picture until the next comes, or passes one over, is not failing.
- **An encoder that stops answering** (review, 2026-11-11). A call into an encoder that has wedged never comes back: a draw waiting to hand over its picture, or just asking for a keyframe. As first built that call was made on the capture thread, behind a watchdog that was set only after the keyframe had been asked for and that relied on the encoder letting go; and everything that would have fallen back to JPEG ran on that same thread, as did hearing Android end the sharing. Now the video has a thread of its own. `PhoneVideo` times every call it makes into the encoder (starting it, drawing, closing it). The capture thread looks twice a second while an encoder runs, and whenever it asks the video for something it waits for the answer only as long as the video's thread is getting on. A call that has taken 2 s is a wedge: the video is given up there and then, from the capture thread, which marks it so that nothing more of it is sent, points the screen back at the `ImageReader`, writes the history line and tells the viewers of the JPEG stream. The encoder is released from a spare thread, which frees a stuck draw if anything will; if the video's thread does come back it closes what it had and ends, and if not, that thread and its encoder are what is lost. Video is then not tried again until the app's process restarts.
- **When video is tried again** (review, 2026-11-11; as first built, two failures of any kind gave it up until smooth sharing was restarted). An encoder that will not start at a size is passed over at once. An encoder that starts and then fails takes its size with it: the next try, which is the next time pictures start or the viewers come to agree on H.264, begins at the next size down. A size that failed stays failed for that run of smooth sharing, so a phone whose encoder cannot do full size does not stumble over it every few minutes. A display that will not take the encoder counts the same. When no size is left, or nothing will start at all, pictures stay JPEGs; ten minutes after the failure that did it, everything is forgotten and full size is tried again. The PC forgets its own video failures after the same ten minutes.

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

**2.2 The capture service.** Built on 2026-11-11. `ScreenCaptureService` keeps the projection and the virtual display and has a second output next to the `ImageReader`. `ScreenOutputs` (plain, tested) decides which of the two the screen is mirrored into, what the viewers are told, and everything that happens when video fails; `VideoWish` (plain) whether video is to be tried. The service hands them its display, its threads and the way to the viewers, and no more: as first built the steps from a failure to the JPEG announcement were in the service itself, where no test reached them (review, 2026-11-11).
- The format is chosen by `StreamFormat` (5.3), from what every current viewer offered. `PhoneScreenCapture.send` carries JPEGs and `sendVideo` H.264; a stream is one or the other, and changing is a new stream id. A v1.0.53 PC, a PC that does not offer `h264` and a phone get exactly what they got before.
- A size change (0.6) ends the encoder before the mirror takes its new shape and starts one at the new size, with a new OpenGL context; its first frame is a keyframe. The stream id stays, and `screen-info` with the new size goes first.
- A short pause of the gate stops the pacer and nothing else. On resume the stream gets a new id and a keyframe from the same encoder. **Changed from the plan:** when nobody has viewed for 5 s (0.6's idle rule) the encoder and its OpenGL context are let go of, not only the mirror. A phone has few hardware encoders and its camera may want one. Starting again costs about 60 ms.
- Any encoder or GL failure (2.1) tears the video output down, points the mirror at the `ImageReader`, which makes Android draw the screen at once, and tells the viewers of a new JPEG stream. The phone's connection history gets a line, "Video went back to pictures" ("Smooth video went back to pictures" until step 4.1), with the reason and the raw error. A failed try is not made again until pictures next start or the viewers next come to agree on H.264; what the next try is, and when video is given up, is in 2.1.
- An H.264 frame, once encoded, is always sent (D2): `sendBinary` does not refuse it for queue size. If the socket itself will not take one, it takes no sequence number and nothing but a keyframe is sent after it. The same holds at the start of every stream, so a stream's first frame is a keyframe whatever the encoder was in the middle of.
- The same holds for a frame encoded while no pictures may go (review, 2026-11-11). A device joining the session closes the gate until it is recognised, and a frame encoded in that moment is not sent. If the gate opened again before the stream had been paused, the stream went on with the next frame, built on one the viewer never had, and with no number missing the viewer asked for nothing. Now such a stream sends nothing more until a keyframe; the encoder makes its next picture one, without encoding one just for that; and when the phone next looks at who is viewing and pictures may go, it asks for it, so a still screen gets it too.
- The last frame going unacknowledged (D8) has the screen encoded again as a keyframe marked repeat; `InFlight` times it as it does a JPEG sent again.
- When sharing stops, the display is released first, then the encoder and the context on the capture thread, which then ends. Nothing is started after that.
- Tests: `PhoneVideoTest` (every way of failing, with a made-up encoder), `VideoPacerTest`, `VideoSetupTest` (with `StreamFormat` and `VideoWish`), `AnnexBTest`, `ScreenOutputsTest` (viewers coming and going, a pause, a turn, sharing stopping, and every kind of failure followed to what the viewers are told), `VideoLaneTest` (an encoder that really does not come back, on real threads), `PhoneH264StreamTest` (who gets H.264, stream ids, a frame never dropped, keyframe requests, and a failed encoder followed through the service's wiring to the JPEG stream on the wire), and a case in `ConnectionHistoryTest`.
- **On the S26** (2026-11-11, no viewer: a throwaway addition stood in for one and wrote the frames to a file): 30 frames a second while Settings was scrolled; a keyframe on request on a still screen; a frame thrown away on purpose was followed by a keyframe 39 ms later; a 2 s pause kept the encoder and resumed with a keyframe; a turn each way gave a new encoder and a keyframe at the new size; the encoder's thread was gone 5 s after the last "viewer" and both threads were gone when sharing stopped; a failure forced in the middle put the mirror back on the `ImageReader`, and a second one kept it there (two failures gave video up, as built then). Windows' decoder (the C1 tool) gave every picture of those files as it was fed.
- **A review the same day** reported five findings, all confirmed and fixed, in the text above where it says "review, 2026-11-11": an encoder that stops answering, a frame that goes unsent, what the next try at video is and when it is given up, a display that refuses the encoder, and the wiring from a failure to the viewers that no test reached. None of the fixes has run on a phone: they were made on unit tests alone, with the S26 in use elsewhere. That includes the video's own thread, which every picture now goes through.
- **Seen with a real viewer** (2026-11-11, the S26 on the rig, both halves on `main`, the phone awake and unlocked throughout). Asked for from the PC's menu row, with Android's prompt pressed by a tap sent as the viewer's: `screen-info` with `codec: "h264"`, its keyframe 0.1 to 0.3 s later, and the readout saying H264 at 1080 x 2340. The picture is upright, sharp, and the colours of the same screen as a JPEG (mean difference 6 in 255). Scrolling Settings: 30 pictures a second at 2.7 Mbit/s, no frame lost, no keyframe asked for (section 11 has the figures). A still screen: the two repeats, then nothing. Closing and opening the viewer: a new stream and a picture on the PC within 0.9 s. Stopping from the row and starting again; a turn each way (same stream, the new size said first, its keyframe 0.06 s later); ten turns in ten seconds and twenty viewers in a row: one encoder at a time, none 9 s after the last viewer had gone, no failure, nothing in the phone's history. A keyframe on request with the repeat bit, at most one a second, none for another stream's id or for a device that is not viewing. A device nobody had approved joining for 3 s: `paused`, no picture, then a new stream and its keyframe from the same encoder.
- **Falling back, seen the same day.** A frame lost on the way (the rig's relay left it out): the PC asked 36 ms later and had its keyframe 37 ms after that. Every keyframe lost after it: the PC asked once a second, five times, then asked for JPEG by itself, said so in a toast and in the readout, and offered only JPEG when its window was opened again. The phone's encoder told it had failed (a throwaway receiver in a debug build, never committed): JPEG in the same instant with the viewer untouched and one line in the history each time; the next viewer got video at 810 x 1754, the one after the second failure at 540 x 1170, and after the third JPEG with no encoder started. The last frames before a still screen lost: the phone sent a keyframe by itself two seconds later.
- **Trying again after ten minutes, seen for each side by itself.** The PC gave video up at 17:03:04 and offered H.264 again when its window was opened at 17:13:15. The phone's third failure was at 17:14:52, and the viewer opened at 17:25:05 had video at full size again.
- **Seen on 2026-11-12:** a slow upload (3.1 has the figures), and the phone locked under video, where the video went on (the end of Phase 1 says how).
- **Not seen:** an approved device joining mid-stream (the blink of the gate), a second viewer, a v1.0.53 app on either side, a phone viewing a phone; the S20 and any other maker's encoder; an hour of it for heat; anything through the production relay. `SCRATCH\step-10\E2E-CHECKS.md` lists the checks and which of them ran.
- **Seen and left** (none of it wrong on a phone or a link as they are): a request for a keyframe that arrives while the one before it is being encoded gets a keyframe of its own a second later; when the viewers come to agree on H.264 again the phone first says the JPEG stream once more and then the new one; a keyframe that reaches the PC with its picture cut short (made so on purpose in the rig's relay, which no link can do to an encrypted frame) is not counted as a decoder failure.

**2.3 The decoder on the PC.** Built: `H264Decoder.cs`, `Nv12Converter.cs` and `MediaFoundationStartup.cs`, with their checks in `H264Tests.cs` of `RemoteDesktopWin.PhoneViewTests`. Nothing calls them until 2.4.
- **The decoder** is Microsoft's H.264 decoder, made by its class id (`CLSID_CMSH264DecoderMFT`) so that it is the software one and not whatever enumerates first. No Direct3D device is given to it. Media Foundation is started through `MediaFoundationStartup`, which `H264Encoder.cs` now uses too.
- **Set on it, before the types:** `CODECAPI_AVLowLatencyMode` 1 as a 32-bit integer (the GUID `H264Encoder.cs` calls `MFLowLatency`), and `CODECAPI_AVDecVideoMaxCodedWidth` and `…Height` at 4096. Low-latency mode is required: without it the decoder runs 31 pictures behind (C1). The size limits change nothing on Windows 11 and are there for an older decoder with a lower limit, which nobody has tried.
- **Input:** H.264, progressive, and no size. The decoder reads the size from the stream, at the start and whenever it changes, so a turned phone needs no new decoder. The plan first put the frame header's size on the input type. That is not needed, and a wrong one does no harm either.
- **Filler data is taken out** of every access unit before it is fed (NAL units of type 12). An encoder holding a constant bitrate pads with them, and the decoder keeps a picture whose access unit ends in one until the next arrives (C1). The phone should leave them out before sending too (2.1): they are bytes with nothing in them.
- **Output:** NV12 into one sample of our own, reused for every picture (in software the decoder brings none). `MF_E_TRANSFORM_STREAM_CHANGE` means "choose the output type again": at the first picture and at every change of size. Only then are the padded size, the visible rectangle (`MF_MT_MINIMUM_DISPLAY_APERTURE`), the row stride (`MF_MT_DEFAULT_STRIDE`) and the colours (`MF_MT_YUV_MATRIX`, `MF_MT_VIDEO_NOMINAL_RANGE`) true; before it the output type only repeats the input type. 1080×2340 comes out as 1088×2352. A stream that does not say its colours is read as Android reads one: BT.601 up to 720×576, BT.709 above, limited range.
- **One call does a frame:** `Decode(accessUnit, tag, handler)` feeds one access unit and calls the handler with each picture that comes out, which is one as a rule. The viewer passes `seq` as the tag and it comes back on the picture. A new input buffer is made for every access unit, because the decoder still reads it after `ProcessInput` returns. The answer is one of:
  - `Picture`;
  - `NoPicture`: taken, and it held no picture;
  - `Pending`: taken, and its picture is not out yet (see below);
  - `NeedsKeyframe`: the decoder is at a fresh start (new, after `Reset`, after a failure) and this was not a keyframe with SPS and PPS in the same access unit, so it was not looked at;
  - `Failed`: the decoder reported an error, **or a keyframe went in and no picture had come out a second later**. The decoder has been reset and wants a keyframe;
  - `Disposed`.
- **No picture larger than 1440 × 3200** (4,608,000 pixels, padding included), either way round. Windows itself decodes up to 4096 × 4096, and a picture is three arrays of 4 bytes a pixel on its way to the screen, so a stream that changed to an absurd size with every keyframe could have had the PC making 64 MB arrays over and over. A phone's shorter side is 1080 at most, so the tallest real screen is well inside. A larger picture is refused when the decoder announces it, before anything is made for it, and counts as a failure.
- **A picture can be late, and that is not a failure.** As a rule the picture is there as soon as its frame has gone in. With the processor busy it can take some milliseconds more: most often the first picture after a size announcement, most often when another decoder has just been let go (a window closed and one opened), and now and then an ordinary frame's. `Decode` looks again for 3 ms without sleeping and then answers `Pending`; the picture comes out of the next `Decode`, or of `Poll`, which the feed's thread calls between frames, sooner at first and then up to 50 ms apart, so a screen that has stopped changing does not hide it. Pictures are matched to their frames by the number that went in with them, not by which frame was going in when they came out. Only a keyframe whose picture has still not come after a second is a failure. While a keyframe's picture is owed, the frames behind it wait: a keyframe the decoder can make nothing of gives no picture while it is left alone, but fed more frames it goes on to make pictures out of nothing. (The first version of this waited 150 ms inside `Decode` and called anything later a failure. A review pointed out that a busy PC could then give video up without any fault, and that the wait held up the feed thread's acknowledgements.)
- **Why the last rule.** The decoder itself reports no error for a missing frame, a stream that starts without a keyframe, a keyframe without its parameter sets, or a size it cannot take. It gives nothing and says nothing. A keyframe that yields no picture is the one sure sign that this PC cannot decode the stream, and it is what makes D9 unnecessary. Gaps are still the viewer's to see, from the sequence numbers: `Reset`, then ask for a keyframe (2.4).
- **Threads.** `Decode` and `Reset` may come from any thread, one call at a time; a second call while one is in progress throws. `Dispose` may come from any thread at any moment and never waits. If a frame is being decoded, the decoder is torn down when that call returns, after the handler has finished, on the decoding thread. A picture's memory is the decoder's and is good only inside the handler, so the handler converts it there.
- **`Nv12Converter`:** NV12 to BGRA in unsafe code, with look-up tables, for BT.709 and BT.601 in limited and full range. It writes only the visible rectangle. It can be given a band of rows, so several threads can share a picture, and its inner loop carries nothing from one pair of pixels to the next, which is what vector code needs. Neither has been needed: about 3 to 6 ms a picture on one thread (C1). It may be called from any thread.
- **`H264Decoder.IsAvailable`** says whether this Windows can decode H.264 at all, found out once. The N editions without the Media Feature Pack cannot, and nor can a Windows Server without Media Foundation. The viewer then never offers `h264`.
- **Not built: hardware decoding.** Giving the same transform a Direct3D 11 device makes the graphics card decode. In C1 that was no quicker by the clock (4.0 to 4.7 ms a picture against 2.5 to 5.3 ms), because the picture has to be copied back to memory for WPF, but it took about a tenth of the processor time (0.4 to 0.9 ms a picture against 4 to 9 ms). It is some twenty lines: a device, a device manager, one message to the transform, and reading the picture through `IMF2DBuffer`. It was tried on one AMD card only. Something for a PC that turns out too slow.
- **Not built either:** `CODECAPI_AVDecNumWorkerThreads`. Left to itself the decoder used twice the processor time of a two-thread decoder to save half a millisecond a picture.
- **The checks** make their streams on the spot: a drawn pattern goes through the app's own `H264Encoder` and back. No recording of a phone is used. They cover 320×240, 322×242, 1080×2400, 1080×2340 and 2400×1080 (size, visible rectangle, patches of known colour in every corner, one picture for each access unit), filler, a fresh start, a reset, rubbish in the stream, a decoder limited below the stream's size, a size that changes mid-stream, the handler's rules, disposing while a frame is being decoded, and threads. Each check runs with the graphics card's encoder and with Windows' software one, whichever the PC has. Where there is no H.264 decoder, or no encoder at all, they print SKIP and pass. (The app itself still sends only from a graphics card's encoder: the software one was meant as its fall-back, never worked, and now does, but using it is a decision not taken. `H264Encoder.cs` says why.)

**2.4 The viewer.** Built on the PC: `PhoneVideoStream.cs` (the rules), `PhoneVideoPictures.cs` (decoded pictures on their way to the window), and video in `PhoneFeed.cs`, `PhoneViewWindow` and `MainWindow`. Checked without a phone: `VideoFeedTests.cs` puts real video, made on the spot, through the real feed, and `PrivacyLifecycleTests` puts it through the real main window and phone window, never shown. Seen with a phone on 2026-11-11: the end of 2.2 says what. What the two halves agreed is in `PROTOCOL.md`, "Phone video".
- **The offer.** `start-view` carries `codecs: ["h264", "jpeg"]` when the PC has approved the phone, Windows has a decoder (`H264Decoder.IsAvailable`, found out on the feed's thread as the app starts, never on the window's) and the decoder has not been given up. Otherwise it is as before. If a window gets its `start-view` out before the answer is known, the phone is asked again when it is.
- **Three threads.** The socket's thread checks each frame against the stream, acknowledges it and puts it in a queue. That is all it does, so it never waits for the decoder. The feed's own thread takes frames from the queue one at a time, decodes each with `H264Decoder` and converts its colours. The window's thread copies the newest finished picture into a `WriteableBitmap` at background priority, about a millisecond.
- **Nothing is skipped but a finished picture.** Every H.264 frame is decoded, in order. A JPEG is still newest-only. A decoded picture the window has not drawn by the time the next is ready is replaced by it. Pictures go from the feed's thread to the window's in three arrays that are used again, so there is no 10 MB array per picture.
- **A keyframe is known by its bytes.** The feed looks at what each video frame holds and tells `PhoneVideoStream`; the keyframe bit in the header is the phone's word and is not gone by. It also keeps the stream's last SPS and PPS, from a keyframe or from a frame of their own, and puts them in front of an IDR picture that comes without. So a phone that hands on its encoder's parameter sets as a frame of their own (Android gives them out as a separate buffer, which makes this the likeliest mistake), or sends them only with its first keyframe, or never sets the bit, is shown all the same. The rule for the phone is unchanged: send them together.
- **When the chain breaks** `PhoneVideoStream` says so, and is tested without a decoder: a missing `seq`, a stream this viewer comes into part way, a decoder failure. Frames are then passed over, still acknowledged, until a keyframe comes. `request-keyframe` goes at once, from the thread that saw it, and again every second until one comes; never more than one a second. It names the stream, so a phone can drop one that crosses a change of stream. The last good picture stays on show meanwhile. Not for ever: five requests in a row that bring frames and no keyframe, and video is given up as below. A phone that ignored the request would otherwise leave the window frozen for good. Requests that bring nothing at all are not counted, since then it is the connection.
- **Falling behind.** At most 30 frames or 4 MB wait to be decoded, a second's worth. One more and they are all let go and a keyframe is asked for: a phone shown a second late is worse than a short freeze. If a keyframe happens to arrive while five or more frames wait, they are let go without asking, since it needs none of them. The plan first said only "if the feed overflows"; the numbers and the skip are new.
- **Giving up.** H.264 is given up for a phone when the decoder has failed twice while it was viewed, and when five keyframe requests have gone unanswered. `start-view` goes again with `codecs: ["jpeg"]`, a toast tells the person at the PC, and the readout says why. If video goes on arriving all the same, the `start-view` is sent again, at most once a second: the window decides what to offer under the feed's lock and sends outside it, so one that still offered video can reach the phone after the feed's, and a message can be lost. It is that phone's video that is given up, and not for good: it is offered again when a window is opened on the phone ten minutes or more later. (The plan said "in a session" and left open what one is. The first version made it every phone until the app was restarted; a review found that too much to hang on two failures, and it now belongs to the phone and wears off.) A failure is rare by design, since frames cannot arrive damaged and lateness does not count; the likely cause is a Windows that cannot decode the stream at all, which does not get better by trying at every opening.
- **A turned phone** needs nothing: the decoder takes the new size from the keyframe, whether or not the stream id changes. A new stream id starts afresh from that stream's keyframe. The picture's size comes from the decoded picture itself, never from `screen-info`.
- **While a frame is in the decoder** the window may close, the phone may leave, pause or start another stream, and this PC may stop approving it. The decoder is let go or left alone as the case needs, without waiting for the frame in hand, and when that frame's picture comes out it is shown only if it still belongs to the current stream of the phone being viewed. The checks do each of these at that moment.
- **Acknowledgements are unchanged:** every frame of the stream is acknowledged as it arrives, before it is decoded, drawn or not. So a PC that decodes slowly does not slow the phone; it falls behind and asks for a keyframe, as above.
- **The readout** names the codec, shows how long a picture takes to decode and convert (for JPEG too), and says when a keyframe is being waited for and how often one has been asked for. It shows the bitrate the phone's encoder is aiming at when the phone's `diagnostic-pong` carries `bitrate`.
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

**On the phones.** With the readout open: scrolling a long page; a still screen; closing and reopening the viewer mid-stream; turning the phone; locking and unlocking the S26; a second viewer joining; a viewer on v1.0.53 joining, which should move everyone to type `1`; another device joining the session mid-stream (the short pause); the limited-upload run from 0.4; an hour of viewing for heat and battery. Also, the other way round: a PC's screen viewed from a phone as video, with its colours held against the PC's own screen. The PC's encoder was found to convert with BT.601 numbers under a stream that said BT.709, and now converts with BT.709 and says so; that change has not been seen on a phone, and was tried only with an AMD graphics card's encoder, not Intel's or NVIDIA's.

**Done when**, on the home network through the production relay, on both phones:
- scrolling shows at least 25 pictures a second and a median delay of 0.2 s or less;
- a still screen sends nothing;
- a viewer that opens mid-stream has a picture within a second;
- with the upload limited to 2 Mbit/s, the delay stays within about 0.3 s of the unlimited figure, at fewer pictures a second;
- every item under "On the phones" behaves;
- old and new apps still work together (section 9).

### Phase 3. Staying sharp on a slow link

After Phase 2 a slow link is on time but shows few pictures a second, each at full quality. This phase trades quality for rate.

**3.1 Bitrate steps.** **Built on 2026-11-11 and shipped switched off. Measured on the S26 on 2026-11-12, and left off:** the figures and the reasons are at the end of this item. `BitrateSteps` (plain) holds the rules and `PhoneVideo` drives it.

**The switch.** One constant, `VideoSetup.BITRATE_STEPS`, says whether the app uses the steps, and it says no. Off, every encoder keeps the rate it starts with and nothing else changes: a slow link shows fewer pictures at full quality, as after Phase 2. **The rule: it is turned on only by what a session on a held-back link has measured** (`SCRATCH\step-11\E2E-CHECKS.md`, section 10, lists what, in order), with those figures written into this section first. The session turns it on in its own build.

A review on the day they were built found the first version unsafe to ship switched on, and worst in Accessibility viewing. The text below describes the steps as they are after it; "review" marks what changed.

- **Smooth sharing only** (review). As first built the steps also ran for Accessibility pictures (4.1), which could come down and were never given more than they started with. At 2.5 pictures a second one late acknowledgement or one large keyframe is most of a second's evidence, the first change after a still half minute is always a keyframe, and a rate that can only be taken away is taken away for the session. Accessibility pictures now keep the rate their encoder starts with. The encoder's own rate control is enough at that pace, and on a slow link the pacer still holds back.
- **What they go by** (review): how long a picture that was due had to wait because a viewer's acknowledgements were late (`PhoneScreenCapture.viewersBehind`, which is `InFlight`, 0.7). `PhoneVideo` says so every time the pacer looks whether to encode: at each new picture, and every 15 ms while one waits. As first built it was "no room", which is also true while the app's own queue is full, and a file fetched from the phone fills that queue for as long as it takes: a download walked the rate to the floor. Waiting for the app's queue is not counted now, and a second in which that queue was full counts for nothing either way.
- **A bad second** is one in which pictures waited more than 300 ms. **Two in a row** bring the rate down: times 0.7, not below 300 kbit/s (review: it was one). One alone, a viewer that was busy for a moment, counts for nothing.
- **No bad seconds for two seconds after a keyframe**, whatever it was made for (review): a viewer joining, a pause ending, the last picture unacknowledged, the thirty-second rule, a request. A keyframe of 100 KB takes most of a second on an ordinary upload at any bitrate. As first built only an encoder's start and a step down had this. After a step down it is one second, so on a link that stays too slow steps down come three seconds apart.
- **Back up to where an encoder starts** (review): below that rate, every second in which pictures went and that was not bad is a good one, with however much ordinary waiting and whatever the pictures needed. Three bring a step up, times 1.25, and every good second after that another. From 1 Mbit/s the rate is back where it started eight seconds after the link is good again; from the floor, fourteen. As first built it needed five seconds with no more than 50 ms of waiting in each and pictures that used their share, and any second with more began the count again, which an uneven link seldom allows.
- **Above where an encoder starts**, up to 8 Mbit/s, the link has to show that more is worth sending: only seconds count in which the pictures came to 60% or more of what the encoder was given for them and waited no more than 150 ms. Five for the first step, two for each after. Other seconds neither count nor begin the count again.
- **No evidence either way**: a second in which nothing was encoded (a still screen never has the encoder looked at).
- **Two `request-keyframe`s within 5 s** are a step down, as in `ScreenStreamer`, at most one every two seconds. Counted are requests from viewers that the phone goes on to honour. One that crossed a keyframe on its way (2.1) is dropped before it gets here, and the phone's own keyframes do not count.
- **A step up that has to be taken back** within ten seconds is not tried again for ten seconds, then twenty, then thirty at most. Not in the plan as first written. It is the PC host's rule against a ladder that hunts. A step up that holds puts this back to nothing.
- **From one encoder to the next.** The rate is kept as a share of what an encoder of that size starts at. An encoder started within ten seconds of the last one ending (the phone was turned, the encoder failed) starts at that share of its own rate, also when the first size tried will not start (review: that case started over). One started later starts over.
- **How it is applied.** `PARAMETER_KEY_VIDEO_BITRATE`, which needs no keyframe and changes nothing about the stream. `diagnostic-pong`'s `bitrate` is the rate now aimed at, and the PC's readout shows it.
- **Failures (2.1's rules).** The call that gives the encoder a new rate is timed like every other, so an encoder that never answers it is a wedge. One that throws has failed, like a draw that throws. An encoder that takes the rate and ignores it has not failed: the pacer still holds back, so a slow link shows fewer pictures at full quality.
- **Tests.** `BitrateStepsTest` is built from what a link does: two minutes of uneven acknowledgements, a large keyframe on an ordinary upload, a keyframe every few seconds, a viewer busy for one second, a file going out, a still screen between two bad seconds, a pause with a picture waiting; then each rule. `VideoBitrateTest` drives the encoder as a phone does, with pictures of the sizes C3 measured, and holds that off means off and that the constant is what the app goes by. Each rule was also taken out in turn to see a test fail (`SCRATCH\step-11\tools\mutate.py`).
- **What is still only reasoning.** The steps trust `InFlight`'s notion of late: 100 ms more than the quickest acknowledgement of the last minute. On a link where acknowledgements are late by more than that for seconds on end without the link being full, two bad seconds in a row will come, and the rate will go down for nothing and climb back, over and over. Whether real links do that is what the session has to find out before the switch is turned.

**Done when**, with the upload limited to 5, 2 and 1 Mbit/s in turn, the delay stays under 0.3 s, scrolling stays above 15 pictures a second, and sharpness returns within about 10 s of the limit lifting. By the rules alone: three steps down take about eight seconds, and the way back from 1 Mbit/s eight.

**Measured on 2026-11-12, and why the switch stays off.** S26 on the rig, smooth sharing, the Settings list scrolled without a stop, the readout read every one to four seconds (the rig's PC app answers slowly while video runs). The rig's relay held the phone's upload: each message from the phone was passed on when a link of that rate would have delivered its last byte, and the phone's socket was not read meanwhile, so TCP pushed back (`SCRATCH\rig\rig-relay.mjs`, "Limit"; a quarter of a second's worth may go at once). The constant was turned on in the session's own build only.

| Link | Steps | Pictures/s | Delay median / worst (ms) | What the encoder aimed at (kbit/s) | An ordinary picture (KB) |
|---|---|---|---|---|---|
| 2 Mbit/s | off, as shipped | 16.1 (9 to 22) | 257 / 523 | 2,925 throughout | 14.1 |
| none | off | 30 | 152 / 178 | 2,925 | 11.8 |
| none, five minutes of scrolling and stopping | on | 30 while scrolling | 171 / 265 | 2,925, then 8,000 within six seconds and for the rest; never below 2,925 | 27.6 |
| 5 Mbit/s for a minute, from 8,000 | on | 25.6 (least 21) | 203 / 391 | 5,600, 7,000, 4,899, 5,359: at or under the link only after 46 s, and above it again at the end | 24.5 |
| 2 Mbit/s for a minute | on | 25.4 (least 18) | 240 / 436 | 1,838 after ten seconds, then 2,297, 2,010, 2,199, 2,748, 1,924: eight changes in the minute | 10.5 |
| 1 Mbit/s for a minute | on | 16.9 (least 15) | 311 / 472 | 942, 660, 323, then 300, the floor, for the rest | 7.2 |
| limit lifted | on | 30 | 167 / 181 | 468, 1,144, 2,793, 4,365, 8,000: at 2,925 or more after about 15 s | 22.0 |

Throughout: no keyframe made by a step, no new stream, no `request-keyframe`, nothing lost, no "Video failed". Accessibility viewing kept 2,925 with the steps on, as built.

What the steps did well: at 2 Mbit/s they gave 25 pictures a second where the shipped build gives 16, at the same delay, and softer. What did not pass, each against section 10 of the checks:

- **The rate was above the link for most of a minute at 5 Mbit/s**, and went back above it at 2 Mbit/s (2,748). A step up that has to be taken back is tried again too soon for a link whose limit does not move.
- **Eight changes in a minute at 2 Mbit/s**, where the rule was at most one every ten, then twenty, then thirty seconds.
- **At 1 Mbit/s the rate sank to the floor and stayed.** Below about 1 Mbit/s the S26's encoder does not follow: its pictures of a scrolling list stayed near 7 KB, 17 a second, whatever it was told. So the steps went down for nothing, and the delay stayed over 0.3 s (311 ms median) where under 0.3 s was asked for.
- **The delay's worst was 0.39 to 0.47 s** at every limit; with the steps off at 2 Mbit/s it was 0.52 s.
- **Back to the starting rate took about 15 s** from the floor, where ten were asked for.
- **With no limit the rate went to 8 Mbit/s in six seconds and stayed.** It never went below where it starts, which was the check. But the link here was the home Wi-Fi to a relay on the same network, and on it an ordinary picture came to 27.6 KB where it is 11 to 12 KB at the starting rate, more than twice the data, for no gain that was measured: the delay was 171 ms against 152 to 169.

Not run, since they were to follow a pass: a second viewer, a turn, a pause and a file while scrolling with no limit; two keyframe requests; a turn under a limit. Not possible on the rig: a real uneven link (the phone on mobile data), which is where the worry of "What is still only reasoning" lies.

What would have to change before it is measured again: no step below what the encoder can follow (it could be found by watching whether the pictures shrink); a longer hold after a step up is taken back, so a steady limit is met once and kept; and a reason to go above the starting rate at all, or a lower ceiling.

**3.2 Optional: send no more pixels than the viewer shows.** The viewer window is about 1230 physical pixels tall on a 150% screen, while the phone sends 2340. The viewer could report its size, as `view-region` does for a PC host, and the phone could mirror at that size. It would cut the data by about two thirds, at the price of a keyframe on each window resize. Decide from Phase 2's measurements.

### Phase 4. Follow-ups

- **4.1 Accessibility pictures through the same encoder.** No longer optional: Accessibility viewing is the main mode (section 12), so this was built straight after Phase 2. **Built on 2026-11-11, and seen on the S26 on 2026-11-11 and 12:** what was seen, and what it costs, is the last point below. The text before it describes what was built.

  While every viewer offers `h264`, each screenshot goes through `PhoneVideo`, the class smooth sharing uses, with an encoder of the Accessibility service's own. Rule 2 in section 5.3 no longer needs smooth sharing. The rate stays at 2.5 a second, since Android sets it.

  - **How a screenshot gets into the encoder.** As this plan suggested: it is drawn onto the surface that feeds the encoder's texture (`Surface.lockHardwareCanvas`, in `PhoneVideoEncoder.show`), which is the surface Android mirrors the screen into in smooth sharing. Android then says a picture is in the texture exactly as it does for a mirrored screen, so everything after the texture (`VideoGl`, the encoder, `VideoPacer`, the ledger, every failure rule) is the code that ran on the S26 in C3, unchanged. The screenshot is a bitmap over graphics memory, and Android's own drawing code puts it on the surface on the graphics card. So it is not copied for this, a screenshot of a wide-colour screen has its colours converted by Android, a larger one is scaled there, and the texture's own matrix keeps it the right way up. The other way considered was to upload the app's copy of the screenshot as an ordinary OpenGL texture and draw it with a second shader: 10 MB copied for every picture, and new OpenGL code that nobody could run before a release, where an upside-down picture is one sign error away. What is new and unseen here is only the drawing onto the surface.
  - **Threads.** Screenshots arrive on the capture's worker thread, as before. The encoder has a thread of its own, as in smooth sharing, started when video is first wanted. The worker hands the screenshot over (`VideoLane.show`) and does not wait. The drawing onto the surface is done on the video's thread, and timed like every other call into the encoder, so a surface or an encoder that stops answering cannot hold up the thread that has to fall back to JPEG. At most one screenshot waits for the video's thread; a newer one takes its place.
  - **A still screen sends nothing.** The worker still copies each screenshot into the app's memory and compares it with the last one that went, as it does for JPEGs, and hands over only one that differs. Nothing is passed over for lack of room at that point: the pacer holds back before encoding, and the texture always has the newest. A keyframe asked for on a still screen is the texture drawn again, marked repeat; no screenshot is taken for it. The two sharpening repeats follow each picture as in smooth sharing. On a screen that keeps changing only the first of them has time to happen, so a changing screen is encoded about five times a second.
  - **Capture times.** Android stamps a picture drawn onto the surface with the time of the drawing. `PhoneVideo` goes by the screenshot's own time, which is handed over with it.
  - **After a pause** the texture holds the screen from before the pause, since no screenshot was taken meanwhile. That is not sent: the new stream's keyframe is the next screenshot (`VideoPacer.resumedStale`). In smooth sharing the texture is kept up to date through a pause and the keyframe is made at once.
  - **To smooth sharing and back.** Each has an encoder of its own, and `capture` in `screen-info` changes, so the stream changes (5.4): a new id, said first, and a keyframe nobody asked for. When smooth sharing starts, the Accessibility service's encoder is let go of at once, not after the five idle seconds, and anything it still gives is not sent. When smooth sharing ends, anything its encoder still gives is not sent either, and the next screenshot starts an encoder again. Whichever is about to start an encoder first has the other let go of its own, and waits until it is gone (review, 2026-11-11): as first built the two could overlap for some tens of milliseconds, and a phone with one encoder to give would have failed to start the second and stayed on JPEG for ten minutes.
  - **A turned phone.** The screenshot comes at the new size. The encoder at the old size is ended and one is started at the new, `screen-info` with the new size goes first, and the stream id stays.
  - **What steps 5 to 7 built** needed nothing new. `lock-state` does not depend on how pictures are sent. The unlock overlay is placed by fractions of the screen, so a picture encoded smaller does not move it. The PC judges a video picture for blackness where it judges a JPEG (`PhoneViewWindow.Look`, and the picture on show when the pace of the test passed one over). The words "Screen unavailable" are a picture the app draws, and go through the encoder like a screenshot. That picture is now drawn at the size of the last screenshot: at the size the service is told its display has, which can leave out the navigation bar, every sleep and wake would have meant a new encoder.
  - **Failures** are `PhoneVideo`'s and `ScreenOutputs`', as in 2.1 and 2.2: smaller sizes, then JPEG for ten minutes, and a wedged encoder not again for a day. Smooth sharing and screenshots each remember their own wedge (review, 2026-11-11: as first built they shared one flag, for the life of the process, so a stall on the screenshots' new way into the encoder would also have ended smooth sharing's video, for as long as the Accessibility service ran, which can be weeks). When a wedge has worn off, screenshots get a video made anew, thread and all; the days are counted on a clock that goes on while the phone sleeps. Three things differ. Nothing makes Android draw the screen at once when the encoder fails, so the next screenshot is sent as a JPEG whether or not the screen has changed. The Accessibility service's `PhoneVideo` lives for weeks, so a size that failed is tried again when video starts a day or more after the last failure; in smooth sharing it stays failed for the run. And a screenshot that was handed over and has not reached the texture three seconds later counts as a failure, in place of "the screen gave no picture".
  - **Viewers that did not offer `h264`** get exactly what they got: no encoder is made for them, and the JPEG path is the code it was. Mixed viewers go by `StreamFormat`, as in smooth sharing.
  - **Nobody viewing.** No screenshot is taken, the encoder is let go of five seconds after pictures stop, and the last screenshot is not kept. The video's thread, once it has been started, stays, parked.
  - **The connection history's line** reads "Video went back to pictures" for both ways of viewing. It read "Smooth video went back to pictures".
  - **What it costs, as far as that can be judged without a phone.** For each screenshot the phone used to do a copy out of graphics memory and a comparison, and, if the screen had changed, compress a JPEG of about 2.5 million pixels: section 2.2 put capture and compression together at 80 to 150 ms. It now does the same copy and comparison, and for a changed screen one drawing on the graphics card and two or three hardware encodes (the picture and the repeats that sharpen it), which C3 measured at about 10 ms of processor time each on the S26 (4.4 ms in the app, the rest in the phone's codec service). A still screen costs what it cost before. So the processor does less for every changed picture, and the radio sends less. Not known until measured: what an idle hardware encoder costs while a still screen is being viewed, and what Android's drawing costs.
  - **Tests.** `ScreenshotVideoTest` (plain: when a handed picture is encoded and with what time, a still screen, a pause, who closes a picture, each failure). `AccessibilityVideoTest` (the real capture class and stream, with a made-up encoder: who gets video, a still screen, keyframes, a turn, a pause, nobody viewing, smooth sharing taking over and giving back, and each failure followed to the JPEG on the wire). After the review, the same test drives the callback Android answers a screenshot request with, with a real screenshot result, and follows the screenshot's graphics memory to whoever closes it; and `ScreenshotDrawingTest` tries the drawing on an ordinary canvas (it fills the canvas at any size, the right way up, and always hands the canvas back).
  - **The bitrate** of screenshots stays where their encoder starts: the steps of 3.1 are not used for them (review, 2026-11-11).
  - **Not built:** doing without the copy into the app's memory. It is now only there to tell a still screen, and is most of what a screenshot still costs. Telling a still screen some cheaper way would save it.
  - **On the S26, 2026-11-11 and 12 (on the rig, the relay on the home network).** It works. The readout says "Accessibility viewing, H264, 1080 x 2340". The picture is upright and sharp, and its colours are within 1 to 3 levels of the JPEG of the same screen. A still screen sent nothing for a minute but the status bar's clock. A keyframe asked for on a still screen came 86 to 154 ms later, marked repeat. A turn kept the stream and had its keyframe 36 to 40 ms after `screen-info`. A new viewer had a new stream and its keyframe 96 to 216 ms after `start-view`. To smooth video and back, five times: a new stream each way, the Accessibility keyframe 105 to 123 ms after smooth video ended, never two encoders, no keyframe asked for. The encoder was gone five to eight seconds after the viewer closed, and twenty quick opens left one. Told it had failed (a throwaway receiver, never committed), three times: a JPEG stream and one JPEG with the next screenshot (0.35 to 0.43 s) though the screen was still, one line in the history each time, the next view at 810 x 1754, then 540 x 1170, then JPEG with no encoder. Asleep, on the lock screen and on the black unlock screen the pictures went as video, and the captions and the drawn grid came as with JPEGs; a sleeping S26 gives its always-on screen, not the words "Screen unavailable".
    - **The one change that came of it** (`4b02856`): the safety keyframe of D8 waits five minutes for screenshots. With thirty seconds nearly every change of a watched phone was a keyframe, and a keyframe of a screen that has been still is 105 to 157 KB on the S26 (a stream's first is 14 to 32 KB). After the change, 131 s of use had keyframes only at the start, at a turn and on request.
    - **What it costs in data.** A small change (the clock, a tap that changes little) is 2 to 5 KB with its repeats, where a JPEG was 70 KB. A screen that changes all over is another matter: the encoder gives one picture in 400 ms most of what it would give twelve. Scrolling Display settings: 5.0 frames a second (2.5 new pictures of 64 to 100 KB and a repeat of 2 to 13 KB after each), 1,750 to 1,890 kbit/s, where JPEGs were 2.5 a second of 70 KB, 1,420 kbit/s. So "the radio sends less" above holds for small changes and not for scrolling. Delay 88 to 98 ms median and 143 worst, against 101 and 122 for JPEGs.
    - **On a link held to 1 Mbit/s** (the rig's relay, 3.1): the bitrate stays 2,925,000, as built. Scrolling the Settings list, twice: 1.5 frames a second of 87 KB, the picture 0.92 to 0.93 s old at the median and 1.6 to 1.8 s at worst. As JPEG: 1.2 to 1.3 a second of 87 KB, 0.60 s old, 0.62 to 0.66 s at worst. Video is the later of the two there because it encodes at once, when there is room again, the screenshot it was last handed, which is up to 400 ms old, where the JPEG path waits for the next screenshot. One swipe after 35 s of stillness, which is nearer to how a phone is used: the first picture was at the PC 0.65 s after the swipe began as video (64 KB, an ordinary picture since the change above) and 0.9 s as JPEG (93 KB), and the whole of it came to 253 KB against 565 KB.
    - **Seen and left:** on the second forced failure the phone said `screen-info` for 540 x 1170 video on the old stream and, 22 ms later, the JPEG stream; no picture went between the two. A PC that asks again after a relay restart sends `start-view` twice, 0.2 s apart, and gets two keyframes.
    - **Not seen:** a pause (a device nobody approved joining) and a second viewer mid-stream, the battery and the processor, an hour of it, the grid of the unlock screen at a smaller picture size, anything through the production relay, a v1.0.53 app on either side, and the S20.

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
- **A picture comes out for every access unit fed**, at once, carrying the sample time that went in. (Not always, as turned out while 2.4 was built: with the processor busy a picture can come a few milliseconds after its access unit went in, most often the first after the decoder announces a size, which is every stream's first. See 2.3.) That is with `CODECAPI_AVLowLatencyMode` on. With it off the software decoder held back 31 pictures and the hardware one 15, although the stream says there is no reordering.
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
| The Windows decoder holds frames back despite low-latency mode | It does in one case (C1): when an access unit ends in filler data | The PC takes filler out before decoding, and the phone should not send it. C3 looks for it in the app's own stream. Apart from that, a picture can be a few milliseconds late on a busy PC, mostly the first after a size announcement; it is waited for and is not a failure (2.3). |
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
| 2026-11-11 | `main` at `a7400c5`, debug builds, Phase 2 in | S26 | Smooth sharing, type `5` H.264 | none | 30 | 11 | 169 / 201 (± 3) | 5 to 95 | 0 | **On the rig, as above, and the delay is overstated there: see below.** Scrolling Settings: 2,675 kbit/s against the encoder's aim of 2,925. Still for 10 s: the two repeats, then no pictures at all. No frames lost, no keyframe asked for. Decoding took 14 ms a picture. |
| 2026-11-11 | the same | S26 | Smooth sharing, type `5` JPEG (the PC had asked for JPEG) | none | 9.9 | 88 | 95 / 133 (± 2) | 5 to 119 | 0 | **On the rig, as above.** The same test a minute later, for comparison. No frames lost. |
| 2026-11-11 | the same | S26 | Accessibility viewing, type `5` JPEG | none | 2.4 | 87 | 110 / 136 (± 3) | 5 to 118 | 0 | **On the rig, as above.** The same test again. No frames lost. |
| 2026-11-11 | `main` at `f263570`, debug builds, steps 11 and 12 in | S26 | Accessibility viewing, type `5` H.264 | none | 5.0 (2.5 new and a repeat after each) | 44 to 47 a frame: 64 to 100 a new picture, 2 to 13 a repeat | 88 to 98 / 143 (± 3) | 5 to 116 | 0 | **On the rig, as above.** Scrolling Display settings, twice: 1,750 to 1,890 kbit/s against the encoder's aim of 2,925. Still for 10 s: no pictures at all. No frames lost, no keyframe asked for. Decoding took 13 ms a picture. |
| 2026-11-11 | the same | S26 | Accessibility viewing, type `5` JPEG (the PC had asked for JPEG) | none | 2.5 | 70 | 101 / 122 (± 3) | 6 to 105 | 0 | **On the rig, as above.** The same page a minute later, for comparison: 1,420 kbit/s. |
| 2026-11-12 | `main` at `4b02856`, debug builds | S26 | Accessibility viewing, type `5` H.264 | 1 Mbit/s, by the rig's relay | 1.5 | 87 | 921 to 933 / 1,585 to 1,798 | 5 to 1,196 | 0 | **On the rig.** Scrolling the Settings list, twice. The encoder's aim stays 2,925. Why the picture is older than a JPEG there: 4.1, "On a link held to 1 Mbit/s". |
| 2026-11-12 | the same | S26 | Accessibility viewing, type `5` JPEG | 1 Mbit/s | 1.2 to 1.3 | 87 | 595 / 624 to 661 | 5 to 502 | 0 | **On the rig.** The same, for comparison. |
| 2026-11-12 | the same | S26 | Smooth sharing, type `5` H.264 | 2 Mbit/s | 16.1 | 14 | 257 / 523 | 7 to 280 | 0 | **On the rig.** As shipped, the bitrate steps off: the aim stays 2,925 and pictures are held back. With the steps on: 3.1. |

How to read these:

- They were read off the readout once a second through UI Automation, not copied with Shift+F8: the rig cannot hold Shift down in the viewer. The copy's text was checked off screen and in tests.
- The delay runs from the moment the phone has the picture to the moment the PC draws it. The wait for the next capture is on top: up to 400 ms in Accessibility viewing, which is most of what a person feels, and up to 100 ms in smooth sharing.
- With the relay on the same network the trip is a few ms, so nearly all of the 0.1 s is the phone copying and compressing the picture and the PC decoding it. Through the internet the round trip is added, and on a slow upload the time a picture waits, which is what these figures cannot show.
- **The H.264 delay is the rig's, not the pipeline's.** Since 2026-11-11 the rig's PC app runs on a Windows desktop that is never shown, and there WPF draws about twenty times a second instead of sixty. A video picture is copied into a bitmap the window already shows (`WriteableBitmap`), and that copy waits for WPF to have drawn the one before: 20 to 25 ms on average and up to 0.1 s for a phone-sized picture on that desktop, measured with a throwaway window, and the viewer's thread was held for up to 0.26 s at a time while video ran (it answered at once with JPEGs, which are not drawn that way). So some tens of ms of the 169 are the rig. On a desktop that is shown the wait is at most one refresh. A reading from the installed app (Shift+F8) will say what the real figure is; 30 pictures a second and 11 KB a picture are as measured.
- There is no "before" row. v1.0.53 has no readout and stamps no picture. What can be compared with it is pictures a second and KB per picture, by eye or from the relay.

Still to take, by hand (see the end of Phase 0): both phones through the production relay, and the S20 at all. The run with the upload limited was taken on the rig on 2026-11-12 (the last three rows), with the rig's relay holding the phone's upload; through a real slow link it is still to take.

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

1. **Order of Phase 1 and Phase 2.** Phase 1 was built first (2026-11-11), and Theo has not said otherwise.
2. **Rate.** 30 pictures a second is the default, as built (`VideoSetup.FPS`), with 60 left as the optional step 4.3. Theo has not confirmed it.

No longer open here:

- How to enter the pattern or PIN: Theo chose option A on 2026-11-10 (the phone reports where its unlock area is). See `PHONE-UNLOCK-PLAN.md`.
- Whether unlocking goes before Phase 0: yes, as a plan of its own, unless Theo says otherwise (`PHONE-PLANS-ORDER.md`).
- Whether Accessibility viewing should also keep the phone awake: no (`PHONE-UNLOCK-PLAN.md`, decision 7). Not confirmed by Theo.

## 13. Seen along the way, not part of this plan

- **The S26's connection to a relay on this PC sometimes goes dead as a view starts. Not explained.** Seen on the rig on 2026-11-11, 8 times in about 80 viewer opens, in two bursts of a few minutes with long clean stretches between. It came back that afternoon, 9 times in 17 (below). Each time the phone's `screen-info` arrived and its first picture never did, and 20 to 40 s later the phone gave up on the connection ("No reply to a ping within 20 s", or the connection reset) and was back 2 s after that. What is known:
  - It is the network path and not the app's logic. The app's threads were all idle (Java stacks taken during a stall), the pictures had been handed to the socket, and the phone's TCP connection showed them unacknowledged: send queue growing to 850 KB, `cwnd:1`, the retransmit timer doubling (`ss -tin`). Other connections between the same phone and PC (adb) worked throughout, and so did a new connection to the relay.
  - It is not Nagle being off: it happened the same with that change taken out.
  - The app from before Phase 0 did not show it in 11 opens, in a stretch where the new one had just shown it 3 times in 8. But the new app also ran 20 and then 16 opens without it, so that comparison proves little either way. The debug app's connection history has no drop in any earlier rig session.
  - Both devices were on NordVPN with the phone's traffic to this PC going over Wi-Fi directly, and unexplained drops of the S26 are on record from before (2026-11-01).
  - While it lasts, a viewer sees a frozen picture, as it would have with any earlier build, and the phone's hold-back falls back to its queue rule after two seconds, so pictures pile up in the system's buffer until the phone gives up.
  - A second session later the same morning (10:08 to 11:20, the phone unlocked) went looking for it and did not see it once. The viewer was opened 202 times, in blocks of five that took turns between this build (106 opens) and the app from before Phase 0 (96 opens), with first pictures of 53 to 176 KB. Neither stalled. Beside them ran two plain TCP connections from the phone to a listener on this PC, with no app code in them, one as the shell's user and one as the app's own. Each stayed quiet for 12 s and then sent 100 KB, over 350 times each, and every burst arrived whole within a second. A 1,472-byte ping that may not be split went from the PC to the phone and came back, so the Wi-Fi carried full-size packets.
  - It came back that afternoon, and the comparison was missed again. Between 15:16 and 15:36 the viewer got no picture in 9 sessions of 17, each session a fresh start of the phone app and one viewer open. The phone's socket showed what it had in the morning (`cwnd:1`, five retransmits), and no picture reached the relay. All 17 ran the 14:51 build of `main`, the first that can send video; the same build had opened cleanly in the 7 sessions before 15:16. No other build and no plain connection was running in those twenty minutes.
  - The loop ran from 15:41 to 16:16, and by then the burst was over. Three builds took turns: the one from before Phase 0 (25 opens), the morning's (28) and the 14:51 one (12). They were opened in blocks, in fresh sessions, and in fresh sessions with the viewer opened the moment the phone had joined, as in the burst. None of the 65 stalled. The two plain connections sent 100 KB every 12 s, 280 times, each whole and within 161 ms, and two 1 MB uploads took 0.1 s.
  - So it is still not proved, but nothing points at the app. Whether a build stalls has gone with the time of day and not with the build: the morning's stalled in the morning's bursts and in none of 134 opens outside them; the 14:51 build went 0 in 7, then 9 in 17, then 0 in 12. The build from before Phase 0 has never stalled, but has had only 11 opens inside a burst. And what stalls is below the app: the phone's TCP sending again and again and hearing nothing.
  - The phone's side shows no cause, as far as Android tells. Looked at three times a second from 15:39 to 16:16, its route to this PC was Wi-Fi (`wlan0`) for the app's user and for the shell's, never the VPN's tunnel, and nothing about its addresses or networks changed. Its Wi-Fi network had been up since 05:48 and its VPN network since 07:42, so no burst that day came with the phone's VPN or Wi-Fi reconnecting. It was on one access point radio all day (5 GHz, channel 149, -44 to -54 dBm). That stretch held no stall, though: the route during one has still not been seen.
  - A lead on the PC's side, found afterwards in Windows' own Wi-Fi log. This PC is on Wi-Fi too, on another radio of the same access point (5 GHz, channel 40), so every picture crosses the air twice. Its Wi-Fi joined the network afresh at 09:04:41, 09:16:39, 09:17:10 and 09:19:07, and the first morning burst ran from 09:04 to 09:21; and again at 15:05:41 and 15:06:11, ten minutes before the afternoon's first stall. It did not in the long clean stretches: none between 09:19 and 13:31, which holds the 202 clean opens, and none after 15:06. It does not fit everywhere: the second morning burst (09:33) has no such event near it, and four opens just after 15:06 were fine. A lead, not a finding.
  - To settle it, the loop has to be started the moment a rig session meets a stall. It now brings up a fresh session for each open, takes the three builds in turn, runs the plain connections, and writes down the phone's route and networks and this PC's Wi-Fi all the while (`session-loop.ps1`, kept with the rig's scratch files beside `stall-loop.ps1`). Two other tests would say more: the same sessions with this PC on a cable, and a packet capture on the PC (`pktmon`, which needs an administrator's shell). The production relay has not been tried.
  - Decided on 2026-11-11: this does not hold up a release. The lost packets are below the app, both builds send their pictures the same way, and neither stalled in 202 opens. Every later rig session watches for it, and runs the loop above if it comes back.

- **The PC's viewer can be left showing a dead phone.** Fixed on 2026-11-11 (`DeviceLink`). When the PC itself reconnected (a relay redeploy drops everyone), every device came back under a new id, and no "left" message arrived for the old one. The viewer window stayed open on the old id with its last picture, and went on sending what was typed and clicked to that id, which the restarted relay may by then have given to another device. The files window and the window on another PC did the same. Each window now knows its device by the fingerprint of its approved key. While the device is away the window sends nothing and says so over the picture; it is attached again only to a device that proves the same key and is approved, and then asks for everything afresh (`start-view`, the phone's state, a new stream; the feed, the menu, the unlock overlay and smooth video start empty). When only the phone reconnects, the viewer used to close; it now waits the same way. A viewer waits 60 seconds once the PC is on the relay and then closes with a toast; a files window waits for good. Found again, a device counts as viewed only once it answers with `screen-info`: until then the window says it is asking, sends no input, and closes after another 60 seconds or at once on `denied` (review, 2026-11-11: a phone that had revoked the PC meanwhile left the old picture on screen, unmarked, for good). What video was given up for goes with the phone to its new id, and is no longer kept by an id that has died. Not seen on a real device: tests drive the real main window and phone window with devices that sign the PC's challenge, but no relay was restarted under a real viewer.
- **A PC that was being viewed kept streaming to its viewers' old ids.** Found in review and fixed on 2026-11-11. On a drop the PC did not stop its stream or forget its viewers, and after a relay restart the old id of a viewer may be another approved device's, which was then sent the desktop unasked. Between reconnecting and `welcome` a frame could also go out unaddressed, to everyone. The PC now ends the stream with the connection, takes nobody for approved until `welcome`, never sends a frame to nobody, and drops clipboard and update requests that waited through a reconnection. The phone already forgot its viewers on its own drop (`ConnectionManager.scheduleReconnect` and `welcome` both call `PhoneScreenCapture.onViewStopped()`), and sends nothing while it has none.
- **What of these fixes was seen on the rig, 2026-11-12** (S26, `main` at `4b02856`; the list is `SCRATCH\fixes\DEVICE-CHECKS.md`). The relay stopped for five seconds under an open viewer: "This PC has lost its connection. Reconnecting…" over the picture within 0.3 s, and live pictures again about three seconds after the relay was back, with no click and the readout carrying on. The PC's `start-view` after the restart carries `resume`, the one from opening a viewer does not. With the phone asleep it stayed asleep through the restart, showed no toast, and the viewer had its asleep caption again. With smooth video on, video came back by itself (the same way of viewing, a new stream, a keyframe first) and the row was ticked. A viewer closed and opened by hand woke the sleeping phone and was announced; so was one opened nine seconds after the PC app had been ended and started again, which is the rule since the review. The Accessibility service switched off under smooth video: one `screen-info`, the same stream, `smooth` `only`, the row greyed and ticked with its tooltip; `on` and free again when the service was back. Disconnect from the phone's row closed the viewer, and View opened it again. A device that has not proved its key had View and Files greyed, with the tooltip. Seen and left: after a restart the PC sends `start-view` twice, 0.2 s apart, and gets two keyframes. Not caught: the two messages between losing the connection and the picture (the picture was back 0.14 s after the PC asked). Not possible on the rig: flight mode on the phone (adb reaches it by Wi-Fi), a files window (a saved file opens Explorer on the user's desktop), two phones, a phone or a PC viewing the rig's PC (its desktop is never shown), a real mouse over a greyed button, an app on v1.0.53, and the clipboard, which the rig shares with the user.
- **A new PC with a phone on v1.0.53 shows that phone's "started viewing" toast after every relay restart and every dropped connection.** Accepted, 2026-11-11. The PC's viewer now asks for the screen again by itself where it used to sit dead, and a v1.0.53 phone announces every `start-view` from an id it has not seen, having no notion of a viewer that is only back. The toast is true: the phone is being viewed again. It goes away as phones update, and nothing is built for it.
- **The PC will open a viewer on a device it has not approved.** Fixed on 2026-11-11 (`DeviceActions`). Every row in the device list had View and Files buttons. D7 stops a device that is not approved supplying a video stream, but its bare type `1` pictures were still drawn, and what was typed in the viewer went to it. Both buttons are now greyed until the device is approved, with a tooltip for each case: approval waiting, refused, taken away, or a device that has not proved its key. Files is held back with View, although a device that had approved this PC did serve its files: a file sent from that window goes to a device nobody has checked.
- **The picture freezes while a file is downloaded from the phone.** File chunks and pictures share one queue on the phone, and chunks may fill it to 6 MB. The PC has separate lanes for this; the phone does not.

## Appendix A. Encoder and decoder settings

Phone encoder (`MediaCodec`, `video/avc`):

| Setting | Value |
|---|---|
| Choice of encoder | Hardware first (`isHardwareAccelerated` on API 29+, otherwise by name). A software encoder only at a long side of 1280 or less. |
| `KEY_COLOR_FORMAT` | `COLOR_FormatSurface` |
| `KEY_BIT_RATE` | Start at 3 Mbit/s for a 1080×2400 picture, scaled by pixel count. Step 3.1, where it is switched on, moves it between 0.3 and 8 Mbit/s in smooth sharing, and an encoder that follows another at once starts at the share of its rate the other had come to. Accessibility pictures keep the rate they start with. |
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
| A picture that has not come with its frame | Looked for again for 3 ms, then owed: asked for between frames (`Poll`). A keyframe's that has not come after a second is a failure; until then the frames behind it wait |

## Appendix B. Files

| Area | Files |
|---|---|
| Phone, changed | `Crypto.kt`, `ConnectionManager.kt`, `PhoneScreenCapture.kt`, `ScreenCaptureService.kt`, `AccessibilityScreenCapture.kt`, `InputAccessibilityService.kt`, `PhoneViewerActivity.kt`, `PermissionsActivity.kt`, `AndroidManifest.xml`, `strings.xml`, `app/build.gradle.kts` |
| Phone, new | `PhoneFrame.kt`, `PhoneVideoStream.kt` (the viewer's side, for a phone viewing a phone), `NoDelaySocketFactory.kt`, `InFlight.kt`, `PhoneVideo.kt`, `PhoneVideoEncoder.kt`, `VideoGl.kt` (the GL class), `VideoLane.kt`, `VideoSetup.kt`, `BitrateSteps.kt`, `SmoothSharing.kt`, `SmoothPrompt.kt` (which holds `ScreenShareRequestActivity`) |
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
