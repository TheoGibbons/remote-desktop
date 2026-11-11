# Waking and unlocking a phone from a viewer: plan

Written 2026-11-10 against `main` at `30009c4` (one commit after v1.0.53). Phase 1 was built on 2026-11-11 and its section below describes what was built; Phases 2 and 3 are not built. None of it is released.

**This is the plan for unlocking.** `PHONE-VIDEO-PLAN.md` used to contain a *Phase U. Unlocking the phone from the PC*; that phase is now a pointer here, and its checks C4 and C5 stay there as a record. [Differences from Phase U](#differences-from-phase-u) lists what changed on the way. `PHONE-PLANS-ORDER.md` gives the build order: this plan is its steps 5 to 7, after the menu plan and before the video plan.

## What we're building

Someone opens the viewer on a phone that is asleep and locked, in another room. Today they get a black picture and nothing they do has any effect. After this:

1. The phone wakes when the viewer opens, and the viewer says so if it can't.
2. The viewer says what state the phone is in: asleep, locked, or showing its unlock screen.
3. On the unlock screen, which Android blanks out of the picture, the viewer draws the pattern grid or PIN keys itself, at the real positions, so the pattern can be drawn or the PIN typed.

```
   lock screen (visible today)      unlock screen today        unlock screen after
  ┌──────────────────────┐        ┌──────────────────────┐    ┌──────────────────────┐
  │        12:30         │        │                      │    │  Draw your pattern   │
  │                      │        │                      │    │                      │
  │                      │        │       (black)        │    │     ●    ●    ●      │
  │                      │        │                      │    │     ●    ●    ●      │
  │   Swipe to unlock    │        │                      │    │     ●    ●    ●      │
  │                      │        │                  ‹   │    │   Emergency call  ‹  │
  └──────────────────────┘        └──────────────────────┘    └──────────────────────┘
```

Both viewers get it: the Windows phone window and the phone-viewing-a-phone screen.

## Decisions

Theo confirmed 1 and left 5 to me on 2026-11-10. The rest are mine; change any before we start.

1. **The phone reports where its unlock area is** (Phase U's option A). **Confirmed by Theo.** That means letting the Accessibility service read window content, which it deliberately cannot do today (`canRetrieveWindowContent="false"`). It reads only the lock screen, only while the phone is locked and being viewed. This also answers open question 1 of `PHONE-VIDEO-PLAN.md`.
2. **Phase 1 ships without that capability.** Waking, saying what is going on, and correcting the docs need no new permission and are useful alone.
3. **One new message, `lock-state`,** carries everything. Not extra fields on `screen-info`: a v1.0.53 PC resizes its window on every `screen-info`.
4. **A typed PIN becomes taps, made by the viewer.** The viewer knows where the keys are, so typing `4` sends an ordinary `tap` on the 4 key. The phone needs no new input code and the digits are never sent as text.
5. **The viewer draws your own drag over the pattern grid** as you make it, locally. Without it there is no feedback at all on a black picture. Theo doesn't mind either way, so it stays in; the trail is cleared when the drag ends.
6. **Nothing is remembered.** No PIN, no pattern, no key positions are stored on either side.
7. **No keeping the phone awake.** It sleeps on its own timeout and wakes on the next click. This is also the answer to the video plan's question of whether Accessibility viewing should hold the screen on: no. Only its step 1.5 does, in smooth sharing.
8. **Only real input wakes the phone,** and a repeated `start-view` from a device that is already viewing does not. The video plan re-sends `start-view` to change formats, and the menu plan adds messages (`lock`, `volume`, `rotate`, `get-phone-state`) that must leave a sleeping phone asleep.

## What I found

### Run on the S26, 2026-11-10

SM-S942W, Android 17, pattern lock, Extend Unlock on. Done with a side-by-side copy of the app (own app id, the repo's code plus a receiver driven over adb), uninstalled afterwards. The installed v1.0.53 was not touched.

| Question | Result |
|---|---|
| Does `ScreenWaker` turn the screen on? | Yes, in under a second, with the "Turn screen on" special access left at its default. |
| On what condition? | The app's notifications must be allowed. With them off Samsung cancels it; the system log says `Screen__On : Cancel (notifications are disabled : …)`. Granting the special access did not help. |
| Is the ordinary lock screen in an Accessibility screenshot? | Yes, in full: clock, notifications, shortcuts. |
| Is the unlock (pattern) screen? | No. Black, apart from the Back arrow. SurfaceFlinger lists that layer as `secure`. |
| Do gestures from the service land on the lock screen? | Yes. A swipe up opened the phone outright while Extend Unlock held it, and reached the pattern screen once it didn't. |
| Do they land on the pattern grid? | Yes. A two-dot stroke sent as a `swipe` produced `onPatternDetected` in the keyguard's log and "Incorrect pattern drawn" on screen, with no lockout timer. |
| Where is the grid? | One view, `com.android.systemui:id/lockPatternView`, at `[157,1203][922,1968]` on the 1080×2340 screen: a 765 px square. The dots are not reported singly. |
| What else is on that screen? | The message line (`sec_bouncer_message_area`, e.g. "For your security, draw your pattern."), Emergency call, a lock icon. |

This matches the video plan's C4 (Theo unlocked blind with the released apps) and C5 (an Accessibility service with window content sees that same square).

### Not verified by anyone

- Whether the nine dots sit on an even 3×3 grid inside the square. It needs the real pattern drawn against a drawn grid.
- PIN and password lock screens, and any phone that isn't the S26. The S20 (Android 13) is untried.
- Whether the short test stroke counted as a failed attempt. No timer appeared, but I can't rule it out.
- Whether Android switches the Accessibility service off when an update adds the window-content capability. Both probes were fresh installs enabled over adb. (Answered on 2026-11-11 for the S26 and a debug build: it does not. See check 5 under "Checks that need a real phone".)
- Whether Google Play accepts the capability.
- Smooth sharing (MediaProjection) on the lock screen. Only Accessibility capture was tested.
- Typing into a password lock field through the service's input method.

## Protocol addition

One message, added to PROTOCOL.md under *Input: controlling Android*.

| type | direction | fields |
|------|-----------|--------|
| `lock-state` | phone → each viewer | `to`, `screenOn` (bool), `locked` (bool), `wakeFailed`?, `entry`?, `message`? |

- `wakeFailed` (built in Phase 1) is present only while the screen is off after the phone tried to turn it on and it was still off a second later: `notifications` when the app's notifications are off on the phone, `unknown` otherwise. The plan first had the viewer work this out from `screenOn: false` alone, which can't tell a phone nobody tried to wake from one that refused.
- `entry` is present only while the unlock screen is up and the phone could read it (Phase 2). `{ "kind": "pattern", "area": [x, y, w, h] }` or `{ "kind": "pin", "keys": [{ "label": "1", "rect": [x, y, w, h] }, …] }` or `{ "kind": "password" }`. Rectangles are fractions of the screen, like `tap`. PIN key labels are the digits plus `delete` and `ok` where the phone has them.
- `message` is the lock screen's own message line, e.g. "Incorrect pattern drawn" or a "try again in 30 seconds" countdown.
- Sent to each device that is viewing, addressed, never broadcast: when it starts viewing, and whenever any field changes. The same gate as frames (`PhoneScreenCapture.maySendFrames` and `recipients()`). The phone remembers what it last told each viewer, so a viewer that sends `start-view` again is not told again. A viewer that starts while the phone is asleep is told once it is known whether the phone woke, so it is never told "asleep" for the moment waking takes.
- Older viewers ignore it. Older phones never send it, and a new viewer then behaves as today.

No new viewer-to-phone message. Unlocking uses `tap`, `touch`, `swipe` and `text`, which already require `mayControl(from)`.

## Work, in order

### Phase 1: wake, say what is going on (no new permission)

Built 2026-11-11. On the S26 only what an awake, unlocked phone can show has been checked; everything that needs it asleep or locked is listed for the session at the end of the build (check 1 below).

Phone:

- `LockState.kt`: reads `PowerManager.isInteractive` and `KeyguardManager.isKeyguardLocked`, builds `lock-state`, and sends it when a viewer starts and on `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` / `ACTION_USER_PRESENT`. The receiver is registered only while someone may be sent the picture; `PhoneScreenCapture.refresh()`, which runs on every start and stop, switches it. The menu plan's `rotate` is refused, and left out of `can`, on the same reading (`LockState.Reading.inUse`), so the two can't come to mean different things by "locked".
- `PhoneScreenCapture.onViewRequested` wakes the phone, and shows the "started viewing" toast, only when the device was not already viewing (decision 8, ruling 10).
- Notifications are part of waking. `PermissionsActivity` has a **Notifications** row that opens the app's notification settings, and it is counted with the required permissions there and on the main screen (one list now, `PermissionsActivity.required`). It is counted on every phone, not only Samsung: nobody knows which other makers refuse, and pairing requests also arrive as notifications.
- `ScreenWaker` looks again a second after trying. If the screen is still off, `lock-state` goes out with `wakeFailed`, which says whether notifications are off. One look covers every try made while it is awaited.
- Not built: the fallback for phones that ignore the wake lock (the Accessibility service starting a tiny activity with `setTurnScreenOn(true)` and `setShowWhenLocked(true)` that finishes at once). Build it only if a phone needs it; the S26 does not.
- The comment at the top of `InputAccessibilityService.kt` no longer says a secure lock can't be crossed.

Viewers (`PhoneViewWindow` on Windows, `PhoneViewerActivity` on Android): a caption over the picture, driven by `lock-state`. On Windows it is a layer in the menu plan's picture host, above the picture and below the video plan's Stats readout. It sits at the top of the picture, clear of where a pattern is drawn and of the words "Screen unavailable" that the phone sends in the middle of a black picture while its screen is off. Clicks pass through it. The decisions are in `LockOverlay.cs` (Windows) and `LockCaption.kt` (Android).

| State | Caption |
|---|---|
| `screenOn: false` | "The phone is asleep. Click to wake it." |
| `screenOn: false`, `wakeFailed: notifications` | "The phone didn't wake. Allow notifications for Remote Desktop on the phone." |
| `screenOn: false`, any other `wakeFailed` | "The phone didn't wake. Click to try again." |
| `locked`, no `entry` | none while the lock screen is visible; "Swipe up to unlock" is already in the picture |
| picture nearly all black while `locked` | "Android hides the unlock screen from the picture. Clicks and drags still reach it." |
| awake and not locked | none, however dark the picture |
| picture nearly all black, from a phone that has sent no `lock-state` (v1.0.53 or older) | "The phone's screen is off, or it is on its unlock screen. Clicks and drags still reach an unlock screen." |

The phone viewer on Android says the same with the other phone's name for "the phone" and "tap" for "click".

The two black-picture rows are what make Phase 1 usable alone: the viewer notices the black picture itself (it has the frame), so blind unlocking at least explains itself.

The black test is a plain function over decoded pixels (`LockOverlay.IsNearlyBlack`). It looks at about a hundred pixels across the picture's shorter side and calls the picture nearly all black when at most 1 in 100 of them has a colour channel above 16. That lets through the unlock screen's Back arrow and the words "Screen unavailable", and keeps out a clock on a black wallpaper and a dark theme's dark grey. It runs twice a second at most, and only while a black picture would mean something: on a locked phone, or one that has never sent `lock-state`. It is called wherever the picture is decoded. Today that is `PhoneViewWindow.OnFrame` on the UI thread. The video plan moves decoding to its feed thread (step 0.5) and later to an H.264 decoder (Phase 2); the call moves with it each time.

When `lock-state` changes, the Windows viewer asks for `phone-state` again: the phone holds no rotation while asleep or locked and leaves `rotate` out of `can`, and it does not push `phone-state`.

Docs: PROTOCOL.md has `lock-state`. README.md, windows/README.md and `android/README.md` (now "Unlocking the phone from a viewer") say what is true: it can be done, by pattern, PIN or password, blind for now, once the phone has been unlocked by hand after a restart. They also say that only a pattern has been tried so far.

Tests: `LockStateTest` on the `AllowOtherPhonesTest` pattern (`RecordingSocket`, fields set by reflection, no new shadow of `PeerAuth` or `ConnectionManager`): sent to a viewer on start; sent again on screen off, on and unlock; never to a device that isn't viewing; nothing with control off or while an unapproved device is in the session; a viewer that asks again neither wakes nor is announced; a failed wake and its reason. `LockCaptionTest` and two cases in `PhoneViewerActivityTest` for the phone viewer. Windows: `CheckLockState`, `CheckCaptions`, `CheckBlackPicture` and `CheckBlackWatch` in `RemoteDesktopWin.PhoneViewTests`. The `lock-state` text is shared between `LockStateTest` and `CheckLockState`, as `phone-state` is.

### Phase 2: find the unlock area, draw it (Windows viewer)

Phone:

- `accessibility_service_config.xml` (both copies): `canRetrieveWindowContent="true"`, plus `flagReportViewIds` so the grid is recognised by its id and not by guesswork. Add `flagRetrieveInteractiveWindows` only if the keyguard window can't be reached through `rootInActiveWindow`.
- `LockScreenReader.kt` (new). Split in two so the logic is testable:
  - a pure function from a plain list of nodes (id, class, text, bounds, clickable) to an `entry`;
  - a thin walker that turns `AccessibilityNodeInfo` into that list.
  - Pattern: the node with id ending `lockPatternView`. PIN: nodes whose text is a single digit, plus delete and confirm by id (`delete_button`, `key_enter` on AOSP; Samsung's ids to be read off the S26). Password: an editable node on the keyguard. Anything else: no `entry`, and the viewer falls back to Phase 1's caption.
- It runs only when all three hold: the phone is locked, someone is viewing, and a window change arrives (`onAccessibilityEvent`, which is empty today; the service already receives `TYPE_WINDOW_STATE_CHANGED`). It reads only the package `com.android.systemui`. Nothing else on the phone is ever walked. Android's screen-sharing consent prompt is also drawn by `com.android.systemui`; the locked condition is what keeps the reader away from it, and the video plan refuses `request-smooth` while the phone is locked. The app never presses that prompt (video plan, D11).
- `LockState` includes `entry` and `message` and re-sends when they change.

Windows:

- `LockOverlay.cs` (new, no WPF types): dot centres for an even 3×3 grid in `area`; which dot a point is on; digit, Backspace and Enter to key rectangle; the nearly-all-black test from Phase 1.
- `PhoneViewWindow`: an overlay layer above the picture, shown while `entry` is present.
  - Pattern: nine dots, the phone's `message` above them, and the trail of the current drag. The mouse still sends the ordinary `touch` down / move / up, which is how Theo's blind unlock already worked. The overlay only draws.
  - PIN: the keys with their labels. Clicking one is an ordinary click through to the phone. Digit keys on the PC keyboard send a `tap` at that key's centre; Backspace and Enter go to `delete` and `ok`. While a PIN `entry` is current, typed digits are not sent as `text`. In the window's key handling this check comes after F8 (Stats) and after Esc-closes-the-menu, and before ordinary typing.
  - Password: no drawing. The caption says "Type the phone's password and press Enter", and typing goes through the existing `text` path. If that doesn't reach the field (unverified), say "Password locks can't be typed from here yet" and leave it for later.
- No retry, no automation: each attempt is one the person made. The phone's own lockout message shows through `message`.

Tests: `LockScreenReaderTest` (plain JVM, no Robolectric) with the S26's real node list from the run above, an AOSP PIN pad, and a screen with nothing recognisable. Windows: cases for `LockOverlay` in the console test project the menu plan adds (`RemoteDesktopWin.PhoneViewTests`).

### Phase 3: the phone viewer, and docs

- `PhoneViewerActivity` / `RemoteScreenView`: the same overlay, drawn in the view's own `onDraw`. A finger drag over the grid already streams as `touch`. PIN keys are tapped directly; there is no hardware keyboard to map.
- PROTOCOL.md: `lock-state`. `android/README.md`: what the Accessibility service now reads and when, replacing "reads no text". The Google Play accessibility declaration needs the same rewording before the release that carries Phase 2.
- Both apps ship. Usual steps in RELEASING.md. Phases 2 and 3 go out in a release with nothing else new in it, at a time when the phones are within reach: if Android switches the Accessibility service off when the update adds the window-content capability (check 5 below), a phone can't be viewed until someone turns it back on by hand.

## Compatibility

| Viewer | Phone | What happens |
|--------|-------|--------------|
| new | v1.0.53 or older | As today: no wake, and no captions except the viewer's own black-picture one, in its wording for a phone that sends no `lock-state`. |
| old | new | The phone wakes. Unlocking is blind, as today. |
| new | new, Phase 1 only | Wake, captions, blind unlock. |
| new | new, Phase 2 | The grid or keys are drawn. |

## How this sits with the other two plans

`PHONE-PLANS-ORDER.md` has the full list. In short:

- **`PHONE-MENU-PLAN.md`** is built first. It rebuilds `PhoneViewWindow` (own title bar, a picture host, window fit), and the overlay here goes inside that picture host. Its *Lock screen* menu action gives a way to lock the phone; this plan gives the way back in. Its forced rotation is dropped when the phone locks, so the unlock screen is always in the phone's own orientation. Its `lock` message does not wake the phone.
- **`PHONE-VIDEO-PLAN.md`** is built afterwards. It makes `screen-info` addressed, moves picture decoding off the UI thread (the black test moves with it) and refuses to start smooth sharing on a locked phone. Its step 1.5 (keep awake in smooth sharing) is separate.
- A release cut before Phase 1 here still ships the wake that is already on `main`. On a Samsung with notifications off that wake does nothing and nobody is told, which is no worse than v1.0.53.

## Differences from Phase U

Phase U has been removed from the video plan; this table is the record of what it said.

| Phase U said | This plan says | Why |
|---|---|---|
| `locked` and `screenOn` as new `screen-info` fields, plus a separate `lock-keys` | one `lock-state` message | A v1.0.53 PC resizes on every `screen-info`; one message holds one state. |
| Typed digits travel as `text` and the phone taps the keys | The viewer sends `tap`s | No new phone input code; digits never sent as text. |
| Notifications counted as required | Same, and the viewer is told when a wake failed | So a dark picture explains itself. |
| "Drawing a pattern blind probably works; nobody has tried it" | Tried: the keyguard registers a stroke sent by the service | Run above. |

## Limits whichever way it is built

- After a restart Android does not run the app until the phone has been unlocked once by hand.
- Fingerprint and face can't be supplied remotely.
- An approved device whose user knows the pattern or PIN gets a fully unlocked phone. *View and control this phone* already governs that, and the docs should say it plainly.
- Wrong attempts count against the phone's own limit, the same as on the phone.

## Checks that need a real phone

Step 0 of `PHONE-PLANS-ORDER.md` gives the debug build a permanent `.debug` app id, so it installs beside the release app. Paired as an extra device, it covers these checks end to end with a viewer. Where a check is easier driven over adb, as the 2026-11-10 run was, add the probe receiver to that build without committing it. All of this is on the S26 only; nothing is installed on the S20.

1. Phase 1: phone asleep, open the viewer: it wakes. Notifications off: it doesn't, and the viewer says why. Not run yet: the S26 can't be reopened by an agent once it is locked, so this and the asleep, locked and unlock-screen captions wait for the one session at the end of the build.
2. Phase 2 on the S26: draw the real pattern on the drawn grid. This settles whether the dots are on an even grid; adjust the inset if not.
3. Switch the S26 to a PIN, then a password: read the node list, confirm the keys are found, unlock by clicking and by typing.
4. Wrong pattern five times: the lockout message and countdown appear in the viewer.
5. Updating an installed app to the Phase 2 build: does Android switch the Accessibility service off? **Run on the S26 on 2026-11-11: no.** The debug app (`co.remotedesktop.debug`) was installed from the build before Phase 2, with its service switched on and bound, and was updated in place with `adb install -r` to a build whose only difference was the new capability and `flagReportViewIds`.

   | | Before | 3 s, 15 s and 75 s after the update |
   |---|---|---|
   | `settings get secure enabled_accessibility_services` | lists the debug app's service | the same: still listed |
   | The service in `dumpsys accessibility` | bound, `capabilities=160` (gestures, screenshots), `fetchFlags=0` | bound again (the app's process was restarted by the update), `capabilities=161` (the same plus window content), `fetchFlags=256` (view ids) |
   | Inside the app | | `serviceInfo` says it can retrieve window content, and `rootInActiveWindow` returns the window in front, not null |
   | The session | viewing works | the phone rejoined by itself within seconds, and a viewer opened afterwards got its picture |

   Nothing was shown on the phone and nothing had to be re-enabled. What this does not cover: the app was signed with the debug key and updated over adb, not by Google Play or by opening a downloaded APK, though switching a service off is the system's doing and not the installer's; the S26 runs Android 17, and the S20 (Android 13) or another maker's phone may differ; and it was one run. So the release that carries this should still go out when the phones are within reach. The readings are in the scratch folder of the run (`step-6\gate-*.txt`); the "before" build is kept there as `before.apk`, so the run can be repeated.
6. The S20 (Android 13), on a release.
7. Phone viewing a phone: the overlay lines up on a different screen shape.

## Still open

1. **Before or after the video plan?** Before, by my choice and not yet confirmed by Theo; `PHONE-PLANS-ORDER.md` gives the reasons. After the menu plan either way.

Answered by Theo on 2026-11-10:

| Question | Answer |
|---|---|
| May the Accessibility service read the lock screen's layout? | Yes. All three phases are in. |
| Draw the drag trail over the pattern grid? | No preference. It is drawn, and cleared when the drag ends. |
