# Waking and unlocking a phone from a viewer: plan

Written 2026-11-10 against `main` at `30009c4` (one commit after v1.0.53). All three phases were built on 2026-11-11 and their sections below describe what was built. It was released in v1.0.55 on 2026-11-12. What needs a sleeping or locked phone was tried on the S26 that day (see each phase's section); the real pattern, a PIN and a password lock have not been tried by anyone.

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
- `PhoneScreenCapture.onViewRequested` wakes the phone, and shows the "started viewing" toast, only when the device was not already viewing (decision 8, ruling 10). Nor for a device that is only back (`ViewerReturns`, added 2026-11-11): a PC that reconnects has a new peer id, so its `start-view` looked like a new viewer's, and every restart of the relay woke every phone that was being viewed. The phone now knows a viewer by its identity. A viewer that asks again by itself marks its `start-view` `resume: true`, and the phone honours that only from a device whose last view ended without its own `stop-view` no more than five minutes before. As first built the missing `stop-view` alone made a return, and that silenced viewers someone had opened: a PC sends no `stop-view` when its window is closed while it can reach nobody, or gives up waiting. What is remembered is dropped when the phone's owner revokes the device, switches control off or stops the connection. `KeepAwake` tells a return by the missing `stop-view` alone, with no limit of time: the reasons for the difference are in `ViewerReturns`.
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

Built 2026-11-11. Tried on the locked S26 on 2026-11-12, in the one session at the end of the build ("The lock session", under "Checks that need a real phone"): the grid is found, read and drawn over. Drawing a pattern, right or wrong, is left for Theo's own hand (checks 2 to 4).

Phone:

- `accessibility_service_config.xml` (both copies): `canRetrieveWindowContent="true"`, plus `flagReportViewIds` so the grid is recognised by its id and not by guesswork. `flagRetrieveInteractiveWindows` is not asked for: the reader takes the window in front (`rootInActiveWindow`). If the session at the end finds the unlock window is not the one in front on the S26, that flag is the fix.
- `LockScreenReader.kt`, in two halves:
  - `find`, a plain function from a list of nodes (id, class, text, description, bounds, clickable, editable) to an `entry` and a `message`;
  - `walk`, which turns `AccessibilityNodeInfo` into that list, and `read`, which refuses any window that is not `com.android.systemui`'s.
  - Pattern: the node whose id ends in `lockPatternView`. PIN: all ten digits, as nodes named `key0` to `key9` (Android's own names), or failing that as things to press that say a single digit; `delete_button` and `key_enter`, or something to press that says Delete or Enter, are the other two keys. Fewer than ten digits is not a pad. A digit merely on show (a badge, a clock) is not a key, and a field never is. Password: an editable or password node. The message: the text of nodes whose id ends in `message_area` (`keyguard_message_area`, Samsung's `sec_bouncer_message_area`). Anything else: no `entry`, and the viewer falls back to Phase 1's caption. Samsung's PIN ids have not been read off a phone: a debug build logs the ids, classes and bounds it walked (never the text) under the tag `LockScreenReader`, which is how to read them when the S26 is switched to a PIN.
  - What a view says is asked for in two cases only (review, 2026-11-11): the message line, and something to press that says a digit or names a key. The walker first copied every view's text into memory, notifications and fields included; it never sent them, but "reads only the layout" has to be literally true. A field that is typed into is never asked.
  - A read is `Complete` or `Unknown`. No window in front, a walk that broke off, or more than 400 views is `Unknown`: it says nothing, what viewers were told stands, and it is tried three more times. An app's window in front of the lock screen is `Complete` with nothing in it.
- It runs only while all three hold: the phone is locked, its screen is on, and someone is viewing. `LockState` decides that from the same reading it sends, asks afresh at every read, and drops what was read the moment one of the three ends. The plan had it run only when a window change arrived; it also reads once when the three first hold, since a viewer may start with the unlock screen already up.
- While it runs, the service asks Android for the changes inside `com.android.systemui`'s windows only (`TYPE_WINDOW_CONTENT_CHANGED`, by `setServiceInfo`), so a new message line is noticed. The rest of the time the service is told what it was told before (`TYPE_WINDOW_STATE_CHANGED`), and drops it unread. Asking for content changes from every app all the time would have cost battery for nothing.
- When it reads is decided by `LockScreenWatch.kt` (plain, tested with the clock passed in): at once when a window comes or goes; at most once a second when only something inside a window changes, and half as often each time such a read finds nothing new, down to once in eight seconds; never because time passed. The first build read 150 ms after every change, on the main thread, which is where a viewer's touches are played. The walk now runs on a thread of its own, one read at a time; the phone is asked whether it is still locked there as well, and a read that comes back after reading stopped is dropped.
- A grid or pad is told to viewers only once two reads 250 ms apart agree on where it is, because the unlock screen slides in.
- A `tap` may carry `onlyIfLocked: true`; the phone then makes it only if its lock screen is up at the moment it would be made. Both viewers set it on the taps they make for typed PIN digits, since what a viewer knows of the pad is a relay trip old and a digit typed just after the phone unlocked would otherwise land on the home screen.
- Android's screen-sharing consent prompt is also drawn by `com.android.systemui`; the locked condition is what keeps the reader away from it, and the video plan refuses `request-smooth` while the phone is locked. The app never presses that prompt (video plan, D11).
- `LockState` includes `entry` and `message` and re-sends when they change. `message` is not written to the phone's log.
- The description Android shows when the service is switched on (`accessibility_description`) says what is read and when.

Windows:

- `LockOverlay.cs` (no WPF types): reading `entry` and `message`, refusing anything that makes no sense; dot centres for an even 3×3 split of `area`; which dot a point is on (within 0.3 of the distance between dots, Android's own figure); digits to key rectangles; the nearly-all-black test from Phase 1.
- `PhoneViewWindow`: a `Canvas` in the picture host, above the picture and below the caption, shown while a pattern or PIN `entry` is current. It takes no clicks.
  - Pattern: nine dots, the phone's `message` above them (or "Draw the phone's pattern." when it sends none), and the trail of the current drag, with the dots it has passed drawn larger. The mouse still sends the ordinary `touch` down / move / up, which is how Theo's blind unlock already worked. The overlay only draws, and the trail is cleared when the button comes up.
  - PIN: the keys with their labels. Clicking one is an ordinary click through to the phone. Digits typed on the PC keyboard send a `tap` at that key's centre, marked `onlyIfLocked`; Backspace and Enter go to `delete` and `ok`, and do nothing on a pad without them. While a PIN `entry` is current, nothing typed is sent as `text`: digits are tapped, anything typed with them is dropped, and Ctrl+V does nothing. Digits are claimed where they arrive as characters (`PreviewTextInput`), so the keyboard's layout doesn't matter; Backspace and Enter in the window's key handling, after Esc-closes-the-menu and before ordinary typing (ruling 14).
  - A drag cut short by the window losing the mouse (Alt+Tab) lifts the phone's finger and clears the trail; before, neither happened until the next click.
  - Password: no drawing. The caption says "Type the phone's password and press Enter.", under the phone's `message` when there is one, and typing goes through the existing `text` path. Whether that reaches a password lock's field is unverified (check 3).
  - The Phase 1 caption about a hidden unlock screen shows only while the phone sends no `entry`.
- The window asks for `phone-state` again only when `screenOn` or `locked` changes, not at every new `message`: a lock screen counting down sends one a second.
- No retry, no automation: each attempt is one the person made. The phone's own lockout message shows through `message`.

Tests: `LockScreenReaderTest` (plain JVM, no Robolectric) with the S26's pattern screen as recorded above (the grid's id and bounds and the message's id and words are the recorded ones; the other views' ids and bounds were not written down and are filled in), Android's own PIN pad, a pad known only by what its keys say, a password field, and screens with nothing to enter. `LockStateTest` for when it reads and when it stops, with a window built from `AccessibilityNodeInfo`. Windows: `CheckLockEntry`, `CheckUnlockCaptions`, `CheckPatternDots` and `CheckPinTaps` in `RemoteDesktopWin.PhoneViewTests`. The `entry` texts are shared between the three suites.

### Phase 3: the phone viewer, and docs

Built 2026-11-11, released in v1.0.55 (2026-11-12).

- `PhoneViewerActivity` / `PhoneScreenView` (the plan said `RemoteScreenView`, which is the PC viewer's view): the same overlay, drawn in the view's own `onDraw`, decided in `LockCaption.kt`. A finger drag over the grid already streams as `touch`; its trail is drawn while one finger is down. PIN keys are tapped directly. The viewer's keyboard box follows the same rule as the PC's keyboard while a PIN pad is up (`LockCaption.pinMessages`): digits become taps marked `onlyIfLocked`, Backspace and Enter the pad's delete and OK keys, and nothing is sent as text. A password is typed with the viewer's keyboard button.
- PROTOCOL.md: `entry` and `message` of `lock-state`. `android/README.md`: what the Accessibility service now reads and when, replacing "reads no text". README.md and windows/README.md say the unlock screen is drawn.
- RELEASING.md has the note that the Google Play accessibility declaration must be reworded before the first build with Phase 2 is uploaded to Play, with suggested wording.
- Both apps ship. Usual steps in RELEASING.md. Phases 2 and 3 go out in a release with nothing else new in it, at a time when the phones are within reach. On the S26 an update that adds the window-content capability left the Accessibility service on and working (check 5 below), but that was one phone and a debug build.

## Compatibility

| Viewer | Phone | What happens |
|--------|-------|--------------|
| new | v1.0.53 or older | As today: no wake, and no captions except the viewer's own black-picture one, in its wording for a phone that sends no `lock-state`. |
| old | new | The phone wakes. Unlocking is blind, as today. |
| new | new, Phase 1 only | Wake, captions, blind unlock. |
| new | new, Phase 2 | The grid or keys are drawn. |
| Phase 1 viewer | new, Phase 2 | The viewer ignores `entry` and says the unlock screen is hidden, as with a Phase 1 phone. |

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

1. Phase 1: phone asleep, open the viewer: it wakes. Notifications off: it doesn't, and the viewer says why. Run on 2026-11-12: "The lock session" below.
2. Phase 2 on the S26: draw the real pattern on the drawn grid. This settles whether the dots are on an even grid; adjust the inset if not. **For Theo's hand**: only he knows the pattern. The session at the end can check, without it, that the reader finds the grid and that the drawn square is where the phone reports it.
3. Switch the S26 to a PIN, then a password: read the node list (a debug build logs it under `LockScreenReader`), confirm the keys are found, unlock by clicking and by typing. **For Theo's hand.** Not run. Samsung's key ids are a guess until then, and whether typed text reaches a password lock's field is unknown.
4. Wrong pattern five times: the lockout message and countdown appear in the viewer. The session at the end drew nothing, by instruction, so one wrong try and its message are still unseen; five in a row locks the phone out for a while and is for Theo to choose.
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

### The lock session, 2026-11-12

S26 (Android 17), the debug build of `main` at `4b02856`, on the rig, with the relay's trace on. The PC got its pictures as H.264 throughout. The phone began unlocked and was locked from the viewer's menu; no pattern was drawn or guessed at any point, so it ended locked.

| Check | What was seen |
|---|---|
| Nothing is read while unlocked | Notifications pulled down and closed from the menu: no `LockScreenReader` line, `lock-state` without `entry` or `message`, the service listening for `TYPE_WINDOW_STATE_CHANGED` only. |
| A tap marked `onlyIfLocked` on an unlocked phone | Dropped: the same tap without the mark landed on what was under it. |
| *Lock screen* from the menu, phone unlocked, Landscape held | `lock-state` with `screenOn` false and `locked` true 0.8 s after `lock`; the keyguard up; no rotation overlay left; `phone-state` says `auto` and has no `rotate`. Smooth sharing, which was on, did not end (video plan, end of Phase 1). |
| Asleep, viewer open | Caption "The phone is asleep. Click to wake it." The picture is the phone's always-on screen, 2 % of it lit; the window keeps its size. |
| `start-view` sent again; `rotate` sent | Still asleep three seconds later, no toast, no new `lock-state`; nothing turned. |
| Back from the menu | Awake in 0.35 s, still locked; `lock-state` on and locked; no caption; the lock screen in the picture, upright. |
| The lock screen is read | "Walked 70 views: entry=null message=false"; no `entry` sent; the service now listens for content changes too. |
| The menu on a locked phone | The three Rotation rows are not there; *Smooth video* answers "Unlock the phone first."; `rotate` sent anyway changes nothing. |
| The unlock screen (a swipe up) | `lock-state` with `entry` `pattern`, `area` `[0.1454,0.3679,0.7083,0.3269]` and `message` "Draw unlock pattern", 1.25 s after the swipe began, about 0.9 s after the screen was up. The reader's line: `lockPatternView [157,861][922,1626] clickable`, which is that area to four places. The picture is black but for the Back arrow (0.5 % lit). |
| What the PC drew | No caption; "Draw unlock pattern" above nine dots. Their centres, in a picture 541.5 px wide that begins 44 px down the window: 142.8, 270.7 and 398.7 px across, 540.3, 668.4 and 796.3 px down. That is within 0.002 of the picture of `x + w/6`, `x + w/2`, `x + 5w/6` and the same down. |
| Back from the unlock screen | `lock-state` without `entry`; the dots gone; the lock screen back in the picture. |
| Unlock screen up, viewer closed | Back and the swipe again over adb: no new `LockScreenReader` line; the service listens for window changes only. |
| Unlock screen up and drawn, then asleep | `lock-state` off with no `entry`; the asleep caption; no dots; nothing read while it slept. |
| A viewer opens on the sleeping phone | Awake in 0.3 s; exactly one `lock-state` (on, locked), 54 ms after `start-view`, none with `screenOn` false before it; no caption; the phone shows "started viewing". |
| The same with the app's notifications off | Still asleep three seconds later. `lock-state` with `wakeFailed` `notifications` 1.01 s after `start-view`. Caption "The phone didn't wake. Allow notifications for Remote Desktop on the phone." Samsung's log: "Screen__On : Cancel (notifications are disabled : co.remotedesktop.debug)". Back: still asleep, no new `lock-state`. Notifications allowed again: the next viewer woke it. |
| *Lock screen* from the menu on the lock screen, then a key | Screen off 0.3 s after `lock`; a key from the viewer woke it in 0.3 s. |
| The relay restarted under a viewer, phone asleep | It stayed asleep and showed no toast; the viewer said it had lost its connection and then showed the asleep caption again; the PC's `start-view` carried `resume`. |

The grid was at a different height from 2026-11-10 (`y` 0.3679, then 0.5141): Samsung places it by what else is on the unlock screen. The reader follows it.

Not run: one wrong try and the message that answers it; the real pattern; a PIN and a password lock; the caption for a wake that fails with notifications on; the grid over a picture encoded smaller. They are on Theo's list above.

## Still open

1. **Before or after the video plan?** Before, by my choice and not yet confirmed by Theo. It was built that way (this plan's three phases, then the video plan's); `PHONE-PLANS-ORDER.md` gives the reasons. After the menu plan either way.

Answered by Theo on 2026-11-10:

| Question | Answer |
|---|---|
| May the Accessibility service read the lock screen's layout? | Yes. All three phases are in. |
| Draw the drag trail over the pattern grid? | No preference. It is drawn, and cleared when the drag ends. |
