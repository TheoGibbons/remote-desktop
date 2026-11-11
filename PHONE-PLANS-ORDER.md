# The three phone plans: build order and how they fit together

Written 2026-11-10 against `main` at `30009c4`. The Status column of the table below says what is built.

Three plans change how a phone is viewed and controlled:

- `PHONE-MENU-PLAN.md`: a `⋯` menu in the Windows viewer's title bar (navigation, volume, rotation, lock).
- `PHONE-UNLOCK-PLAN.md`: waking a sleeping phone and unlocking it from a viewer.
- `PHONE-VIDEO-PLAN.md`: less delay, smooth sharing from the PC, H.264 from phone to PC.

They were written separately on the same day and overlap. This file says what order to build them in and which plan wins where they disagreed. The three plans have been edited to match, so each can be followed without the others open. Where a plan and this file still differ, this file wins.

## Build order

One plan at a time, one commit per step, working tree clean between steps.

| # | Step | From | Can be released after it? | Status |
|---|------|------|---------------------------|--------|
| 0 | Debug build installs beside the release app (`.debug` id) | Video 0.1 | Nothing for users in it | Built: debug build is `co.remotedesktop.debug`; not installed on a phone. |
| 1 | Title bar, menu, window fit, `RemoteDesktopWin.PhoneViewTests` | Menu Phase 1 | Yes. Windows only, works with phones on v1.0.53. | Built. On the S26: Back, Home, Recents and Notifications from the menu, and typing. Not checked: anything that needs a real mouse (clicking minimise / maximise / close, hover, snap flyout, dragging, resizing), and Windows 10. |
| 2 | Phone state, volume, Quick settings, Screenshot, Lock | Menu Phase 2 | Yes | Built. On the S26: volume and mute from the menu, the level read back, Quick settings, and Screenshot with its card on the PC. Not checked: *Lock screen* on a real phone (first tried in step 5), and dragging the slider with a real mouse. |
| 3 | Rotation, and smooth sharing following a turned phone | Menu Phase 3 | Yes | Built. On the S26: Landscape, Portrait and Auto on the home screen and in Settings, the phone turning back when the viewer closes or the PC app dies, and smooth sharing following the turn. Not checked: taps landing right after a turn (needs a real mouse), a portrait-only app, the toast, and release on screen-off or lock (unit tests only; first tried in step 5). |
| 4 | Docs | Menu Phase 4 | Release | Written: PROTOCOL.md, README.md, windows/README.md, android/README.md. Not released. |
| 5 | Wake, `lock-state`, captions, notifications row | Unlock Phase 1 | Yes. No new permission. | Built. On the S26, awake and unlocked: `lock-state` reaches the PC when the viewer opens and the PC asks for `phone-state` on it, no caption over a normal picture, a `start-view` sent again shows no toast and is not told `lock-state` again, and the Notifications row is shown and counted (5 of 5, and 4 of 5 with notifications off). The PC's captions were also seen in the real window over the S26's picture, with `lock-state` sent in the phone's name. Not checked: anything with the phone asleep or locked (waking with notifications on and off, every caption on a real phone, the black unlock screen), which waits for the one session at the end; and unlocking by PIN or password, which nobody has tried. |
| 6 | Read the unlock screen, draw the grid or keys on Windows | Unlock Phase 2 | With step 7 |  |
| 7 | The same overlay in the phone viewer, docs | Unlock Phase 3 | Release of its own (see below) |  |
| 8 | Type `5` frames, readout, baseline, small fixes, idle mirroring, acknowledgements | Video Phase 0, less 0.1 and the rotation half of 0.6 | Yes |  |
| 9 | Smooth sharing from the PC | Video Phase 1, after check C2 | Yes |  |
| 10 | H.264 | Video Phase 2, after checks C1 and C3 | Yes |  |
| 11 | Accessibility pictures through the encoder | Video 4.1 | Yes |  |
| 12 | Bitrate steps, then the optional items | Video Phase 3, rest of 4 | Yes |  |

Why this order:

- **Step 0 first.** Every plan has checks that need a build on the S26, and each described a different way to get one there. It is a few lines of Gradle.
- **Menu before the other two.** Theo's decision. It gives `PhoneViewWindow` its own title bar and a picture host. The unlock overlay and the video plan's Stats overlay go inside that host, and *Smooth video* and *Stats* are rows in the menu.
- **Unlock before video.** My call; Theo has not confirmed it, and nothing breaks if the two swap.
  - The menu adds a *Lock screen* row. Until unlock is built, the only way back in is blind.
  - The wake (`ScreenWaker`) is already on `main` and goes out with the next release. On a Samsung with notifications off it does nothing and nobody is told. Unlock Phase 1 is what explains it.
  - Unlock is three small steps. Video is five or more releases.
  - Unlock needs nothing from video. Video Phase 1 gains from it: "Unlock the phone first" becomes something the person at the PC can do.
- **Unlock Phases 2 and 3 get a release with nothing else in it.** Phase 2 lets the Accessibility service read window content. Nobody knows yet whether Android switches the service off when an update adds that. If it does, the phone can't be viewed until someone re-enables it by hand, so that release should carry one risk only, and go out when the phones are within reach. The Google Play accessibility declaration must be reworded before it.

Any release ships every commit on `main`, so each step must leave both apps working with the other side one step behind. The compatibility tables in the three plans cover that.

## Where the plans disagreed, and what is decided

| # | Subject | Decision | Was |
|---|---------|----------|-----|
| 1 | Unlocking was planned twice | `PHONE-UNLOCK-PLAN.md` is the plan. The video plan's Phase U is now a pointer to it. | Video Phase U and the unlock plan, differing in protocol. |
| 2 | Lock messages | One `lock-state` message, phone to each viewer. No `locked` or `screenOn` on `screen-info`, no `lock-keys`. | Video Phase U had the fields and `lock-keys`. |
| 3 | Typing a PIN | The viewer turns digits into `tap`s on the reported keys. Digits are never sent as `text`. | Video Phase U sent `text` and had the phone tap. |
| 4 | Smooth sharing ignores a turned phone | Fixed once, in Menu Phase 3, including `onCapturedContentResize` on API 34+. Video 0.6 keeps only "stop mirroring when nobody views". | Both plans had it; "whichever is built first". |
| 5 | Debug build on a phone | Step 0: `applicationIdSuffix = ".debug"`, committed. The adb-driven probe receiver, when a check needs one, is an uncommitted addition to that build. | Menu: replaces the release app, ask first. Unlock: temporary `.probe` id. Video: `.debug`. |
| 6 | Which phones are tested before a release | S26 only, on the debug build. The S20 is first tried when a release reaches it. Nothing else is installed on either phone without asking. | Menu wanted rotation checked on both before release. |
| 7 | Windows test project | `RemoteDesktopWin.PhoneViewTests`, created in step 1. Unlock and video add their cases to it. No `PhoneVideoTests`. | Video named both. |
| 8 | Keeping the phone awake | Only video step 1.5: in smooth sharing, while viewed, let go after 10 idle minutes. Accessibility viewing does not hold the screen on. The menu's optional "keep awake" row is dropped. | Three different answers. |
| 9 | Messages that must not wake the phone | Only real input wakes it: `tap`, `swipe`, `touch` (not moves), `pinch`, `text`, `key`, the navigation buttons, `quick-settings`, `screenshot`. Not `lock`, `volume`, `rotate`, `get-phone-state`, `frame-ack`, `diagnostic-ping`, `request-keyframe`, `request-smooth`, `stop-smooth`. The same split defines "input" for the 10-minute limit in 1.5. | Menu listed three; video listed none. |
| 10 | A repeated `start-view` | Wakes the phone and shows the toast only for a viewer that was not already viewing. | Today every `start-view` does both; video re-sends it to change `codecs`. |
| 11 | Forced rotation and the lock screen | The rotation override is dropped when the phone's screen turns off or it locks, and the menu shows Auto again. My call. | Menu left it to a check. |
| 12 | *Lock screen* while smooth sharing holds the phone awake | `lock` also lets go of the 1.5 wake lock. On Android 15 QPR1+ the lock ends smooth sharing anyway; on the S20 it does not. | Not covered. |
| 13 | What is drawn over the picture | Three layers in the menu plan's picture host, bottom to top: the picture, the unlock overlay and captions, the Stats readout. | Video said "nothing is drawn over the picture". |
| 14 | Keys in the Windows viewer | Checked in this order: F8 and Shift+F8 (Stats); Esc while the menu is open (closes it); digits, Backspace and Enter while a PIN `entry` is current (become taps); everything else is typed into the phone. | Each plan claimed keys without knowing of the others. |
| 15 | The black-picture caption | A function over decoded pixels in `LockOverlay.cs`, called wherever the picture is decoded: the UI thread at first, the feed's thread after video 0.5, on the converted picture after video Phase 2. A phone that sends no `lock-state` gets the caption on blackness alone, with wording that covers "asleep" too. | Unlock tied it to `locked`, which an old phone never sends, yet promised it for old phones. |
| 16 | "The service cannot read window content" | No longer true after step 6, so video D11 now rests on the rule alone: the app never presses the consent prompt. `LockScreenReader` runs only while the phone is locked, and `request-smooth` is refused while it is locked, so the reader never sees the prompt. | Video D11 leaned on `canRetrieveWindowContent="false"`. |
| 17 | Three state messages from the phone | They stay separate. `screen-info`: the stream (pushed). `phone-state`: volume and rotation (asked for). `lock-state`: asleep, locked, unlock screen (pushed). | Not in conflict, but easy to merge by mistake. |

Things several steps touch, in the order they will meet them:

- `PhoneScreenCapture.refresh()` is the hook for dropping the rotation override (step 3), for the lock receiver (step 5) and for ending a stream (step 8).
- `PermissionsActivity`: the notifications row (step 5), then asking for the whole screen (step 9).
- `accessibility_service_config.xml` exists twice (`xml/` and `xml-v30/`). Step 6 changes both.
- `README.md` line 236 and "Why the PC can't unlock the phone" in `android/README.md` say unlocking is impossible. Step 5 rewrites them. Step 4, which comes first, should not describe *Lock screen* as a one-way trip.

## For Theo

Decided by me, easy to change before the step that builds it:

1. Unlock before video (see above). The alternative is the order in your message: menu, video, unlock.
2. The rotation override is dropped when the phone locks (row 11).
3. Accessibility viewing does not keep the phone awake (row 8). The video plan had this as an open question.

Still open in the plans themselves, none of them blocking steps 0 to 7:

- Menu: its six decisions are marked "change any before we start", and two cheap extras (always on top, Alt shortcuts) are offered.
- Video: Phase 1 before Phase 2 unless you say otherwise; 30 pictures a second as the default.
