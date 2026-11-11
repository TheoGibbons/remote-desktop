# Phone menu in the Windows viewer: plan

Written 2026-11-10 against `main` at `30009c4` (one commit after v1.0.53). Phases 1 and 2 are built; the rest is not.

This is the first of three plans to be built. `PHONE-PLANS-ORDER.md` gives the order (this plan is its steps 1 to 4, after a step 0 that lets a debug build sit beside the release app) and settles where this plan met `PHONE-UNLOCK-PLAN.md` and `PHONE-VIDEO-PLAN.md`. This file has been edited to match.

## What we're building

When the Windows app is viewing an Android phone, the window's title bar gets a small `⋯` button just left of minimise / maximise / close. Hovering it (or clicking it) opens a menu that acts on the phone:

```
┌──────────────────────────────────────────┐
│    ◁ Back        ○ Home       ▢ Recents  │
├──────────────────────────────────────────┤
│  🔈  ━━━━━━━━━━━●━━━━━━━━━━━━━━          │   media volume; click the icon to mute
├──────────────────────────────────────────┤
│  Rotation    [Auto]  Portrait  Landscape │
├──────────────────────────────────────────┤
│  Notifications                           │
│  Quick settings                          │
│  Screenshot                              │
│  Lock screen                             │
└──────────────────────────────────────────┘
```

Back, Home, Recents and Notifications already exist as protocol messages, so they work against today's phone app. Volume, rotation, Quick settings, Screenshot and Lock need a phone update.

## Decisions I made (change any before we start)

1. **The menu opens on hover and on click, and closes when the pointer leaves.** Clicking an item does not close it, so Back can be clicked several times.
2. **The title bar becomes ours, the three window buttons stay Windows' own.** WPF has no way to add a button to a system title bar, so the phone window draws its own (dark) bar and leaves a gap where Windows keeps drawing and handling minimise / maximise / close. Built and checked on this PC; see [Title bar](#title-bar-built-in-phase-1).
3. **Rotation is three choices (Auto / Portrait / Landscape), not a toggle,** and the phone holds a forced orientation only while someone is viewing it. A toggle can't say "give rotation back to the phone", and a phone left stuck sideways after a session would be a nasty surprise.
4. **Volume means media volume,** and the phone shows its own volume panel as it changes, the same as pressing its volume keys.
5. **Extras included:** Notifications, Quick settings, Screenshot, Lock screen. See [Anything else?](#anything-else) for what I left out and why.
6. **Only the Windows viewer gets the menu.** The phone-viewing-a-phone screen keeps its existing button bar; it could use the same new messages later.

## What I found

### Title bar (built in Phase 1)

`WindowCaption.cs` gives a window this title bar. It was checked on Windows 11 build 26200 at 150 % scaling, by sending hit tests to the real window and looking at screenshots of it, normal and maximised:

- Windows still draws minimise / maximise / close in the top right, and those points hit-test as the native codes (minimise 8, maximise 9, close 20). Windows 11 offers the snap-layouts flyout to any window that answers "maximise button" there, so that should keep working.
- The `⋯` button just left of them hit-tests as ordinary client area (1), top row included, so WPF gets its hover and click.
- The rest of the bar hit-tests as caption (2): drag, double-click to maximise and the right-click system menu are untouched. Its top few pixels resize the window (12), as on any window.
- The frame on the left, right and bottom is still the system's own. The window is resized from just outside its edge, and every pixel of the picture takes clicks.
- With dark mode and a caption colour set, the system-drawn part and our part are the same `#1E1E1E`, with no visible seam. The title and `⋯` dim with the system's buttons when the window is not the active one.

The recipe:

```
WM_NCCALCSIZE hook   DefWindowProc works out the standard frame; then the top is put back where it
                     was, so the title bar's share goes to the client area. Maximised, the top is
                     moved down by the frame's thickness instead, to the screen's edge.
                     The hook is added after WindowChrome's own, so it is asked first.
WindowChrome         GlassFrameThickness = 0,barHeight,0,0, UseAeroCaptionButtons = True, CornerRadius = 0
                     ResizeBorderThickness = the system frame's thickness (0 while maximised)
                     CaptionHeight = the bar's bottom edge, from the top of the window, less the top resize border
Window               Background = Transparent (the picture area paints #111 itself)
Title bar row        no background at all, so the system frame shows through and colours can't mismatch
DwmSetWindowAttribute(hwnd, 20, 1)            dark caption, white glyphs (19 on Windows 10 before 2004)
DwmSetWindowAttribute(hwnd, 35, 0x001E1E1E)   caption colour (COLORREF). Windows 11 only; ignore failure
DwmGetWindowAttribute(hwnd, 5, out RECT)      caption-button bounds in window pixels
```

The first recipe let `WindowChrome` own the whole window with `ResizeBorderThickness = 6`. That puts the resize border inside the window, over the outer edge of the picture, which is where Android's edge swipes start. Keeping the system's frame on three sides avoids it, and also deals with a maximised window: nothing hangs past the screen's edge, so the window needs no padding of its own.

What `WindowChrome` must be told, because it answers every hit test itself, measuring from the window's edges:

- **The resize borders, sized to the system's frame** (`GetSystemMetricsForDpi` of `SM_CXSIZEFRAME` + `SM_CXPADDEDBORDER`, 11 px here). They then fall exactly on the invisible frame at the sides and bottom.
- **The bar's metrics, from Windows.** The button bounds came back as 220 × 44 px (about 146 × 29 DIP), and 33 px tall maximised. That width is kept free on the right and that height is the bar's. They are read again on `SourceInitialized`, `Loaded`, `ContentRendered`, `StateChanged` and `DpiChanged`; before the window is shown they are not known. WPF raises `DpiChanged` before it gives the window its new size, so they are read once more after it, when layout has settled.

Not verified, because it can't be on this PC:

- **A real mouse.** Synthetic mouse input is dropped here. So: clicking minimise, maximise and close; their hover highlight; the snap flyout; dragging and double-clicking the bar; resizing from each edge; and the hover-to-open menu itself are all hand checks.
- **Windows 10.** The caption-colour attribute doesn't exist there, so the bar would be black rather than `#1E1E1E` (still seamless, since the whole bar is the system frame). I expect the buttons to work, since this is what `WindowChrome` was designed for, but nobody has looked.
- High-contrast themes, and dragging the window between monitors with different scaling.

Fallback if Windows 10 misbehaves: `UseAeroCaptionButtons = False`, `GlassFrameThickness = 0,0,0,1`, and draw three buttons ourselves (Segoe MDL2 `E921`, `E922`/`E923`, `E8BB`, wired to `SystemCommands`). About 40 lines, and it loses the snap flyout.

### Rotation (possible)

A normal app can't turn the screen for other apps, but an accessibility service can, and the phone app already has one. The service adds an invisible window that asks for an orientation; Android turns the whole display to suit the topmost window that asks. No new permission, nothing new for Google Play to review.

```kotlin
val params = WindowManager.LayoutParams(0, 0, TYPE_ACCESSIBILITY_OVERLAY,
    FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE   // or _PORTRAIT
windowManager.addView(View(service), params)   // the service's own WindowManager; removeView to give rotation back
```

Evidence: the current AOSP window manager (`DisplayArea.Tokens.getOrientation`) takes the orientation of any visible non-app window that sets one, with no check on window type or permission. [Curmudgeon Rotation](https://github.com/curmudgeon-works/curmudgeon-rotation), a maintained open-source app, forces rotation with exactly this window from its accessibility service.

Limits, all to be checked on real phones:

- Unproven on your phones. The S20 is Android 13 and the S942W is Android 17; I have not run this on either.
- On tablets and unfolded foldables (Android 16 and later), the system may ignore orientation requests.
- An app that only draws one way may look odd when forced the other way.
- If a 0 × 0 window turns out not to count as visible on some phone, use 1 × 1.

The alternative, writing the system rotation setting, needs a new "Modify system settings" permission and only turns apps that allow it. The S20 shows why that is not enough: its rotation setting already says landscape while its home screen stays portrait.

### Existing bug this exposes: smooth capture ignores rotation

`ScreenCaptureService` (the optional MediaProjection stream) sizes its virtual display once, in `setupCapture()`, and never again. From reading the code, not from reproducing it: when the phone turns, the picture is letterboxed inside the old shape and taps land in the wrong place, because input is mapped with the rotated screen size. The accessibility-screenshot path is fine; it sends a new `screen-info` whenever the size changes. This needs fixing for rotation to be usable, and it is a bug today for anyone who turns their phone in smooth mode.

### Volume

`AudioManager` needs no permission for media volume. Both your phones report a range of 0 to 15, so the slider has 16 positions. Android refuses some ring and notification changes during Do Not Disturb, which is one reason to stay with media volume.

### Window size

`PhoneViewWindow.OnScreenInfo` always sets the height to 820 and derives the width. A landscape phone would come out about 1800 wide, wider than many screens. It also measures the frame from `Content`, which will be the whole window once the title bar is ours.

## How the menu behaves

- **Open:** pointer rests on `⋯` for 150 ms, or a click. Resting only counts while the window is the active one, so passing over a window in the background doesn't pop a menu up over other work. If the window becomes active with the pointer already on `⋯`, the 150 ms start then.
- **Close:** 350 ms after the pointer has left both the button and the menu; at once when the window loses focus, moves or resizes; on Esc; on a click on `⋯` once the menu has been open for 600 ms (a click sooner than that was meant to open it). A menu opened without the pointer on it, by a touch or by UI Automation, has nothing to leave and stays until one of the others closes it.
- **Never closes during a slider drag,** even if the pointer leaves the menu.
- **Never takes keyboard focus.** The window types into the phone whenever it is focused, so the `⋯` button and everything in the menu are `Focusable="False"`. Otherwise a click would move focus onto a button, and the next Space typed for the phone would press it. While the menu is open, Esc closes the menu instead of sending Back.
- **Built as a WPF `Popup`,** not a `ContextMenu`: a context menu takes focus and mouse capture and closes on every click. `StaysOpen="True"`, `AllowsTransparency="True"`, placed under the button with right edges aligned. It looks like the app's other `⋯` menus (the `RdMenu` card: white, 10 px corners, soft shadow), with a button style that copies `RdMenuItem`.
- **State:** the window asks the phone for its state when it opens and each time the menu opens, since the phone does not push it. The rows that need an updated phone are hidden until it answers. A phone that is showing a picture and has not answered two seconds after its first one is running an older app, and gets one line in their place: "Update Remote Desktop on this phone for volume, screenshots and more." (Phase 3 changes it to name rotation.) A phone that shows no picture gets neither: it has not let this PC in yet, and updating would not help.
- **Volume slider:** 0 to `volumeMax`, snapping to whole steps. Sends at most one message per 100 ms while dragging and always the final value. Ignores incoming state while dragging so the thumb doesn't jump back. What it last sent is forgotten when a drag starts and when the phone reports its state, so a drag that ends where the last one did is still sent. A level the thumb rests on mid-drag is sent when its 100 ms come round. A click on the track moves the thumb there and sends at once.
- **Answers to the slider:** the phone answers every `volume` with its state, so the answers to a drag's earlier messages arrive after the drag has ended. The slider counts what it has sent and shows only the answer to the last, so the thumb doesn't jump back and forth. Answers still owed two seconds after the last message are given up on.
- **Speaker icon:** mutes a phone that is making sound and unmutes a muted one. A phone turned right down is not muted, so there the icon sets the last level heard in this window, or the quietest step if none, as the icon on Android's own volume panel does. Android reports a volume of 0 while muted, so the thumb sits at the left then.
- **Rotation:** highlights what the phone reports. After choosing Portrait or Landscape, if the picture hasn't changed shape within 3 seconds, toast: "The phone didn't rotate. The app on screen may not allow it."
- Right-click for Back and middle-click for Home stay as they are.

## Protocol additions

All go in PROTOCOL.md under *Input: controlling Android*, and the new viewer-to-phone types join the enforcement list. The phone drops every one of them unless `mayControl(from)` passes, exactly like `tap`.

| type | direction | fields | notes |
|------|-----------|--------|-------|
| `quick-settings` / `lock` / `screenshot` | viewer → phone | – | Global actions, like `back`. `lock` and `screenshot` need Android 9. A screenshot is saved on the phone and then offered to the PC by the existing `fs-offer` (seen on the S26). On the S26 the first `back` after `quick-settings` shows the notifications and the second closes them. |
| `volume` | viewer → phone | `level`? (int), `mute`? (bool) | Media volume, `0..volumeMax`. The phone answers the sender with `phone-state`. |
| `rotate` | viewer → phone | `orientation` (`auto` \| `portrait` \| `landscape`) | Only from a device that is currently viewing. Held until `auto`, or until the phone has no viewers left. The phone answers with `phone-state`. |
| `get-phone-state` | viewer → phone | – | The phone answers the sender with `phone-state`. |
| `phone-state` | phone → viewer | `to`, `volume`, `volumeMax`, `muted`, `rotation`, `can` | `can` lists what this phone supports right now: any of `volume`, `rotate`, `quick-settings`, `lock`, `screenshot`. A viewer shows only those. Older phones never send it. Until Phase 3, `rotation` is always `auto` and `can` never has `rotate`. |

## Work, in order

### Phase 1: title bar and menu, Windows only (built)

Ships on its own and works with the phone app people already have.

- `windows/RemoteDesktopWin/WindowCaption.cs` (new): applies the recipe above to a window and exposes the bar's height, the width kept for the system's buttons, and the work area of the window's monitor. Kept separate so `PcViewWindow` can use it later.
- `PhoneViewWindow.xaml`: a title bar row (`TitleText` bound to `Title`, trimmed with an ellipsis; `MenuButton`, the `⋯`, with `WindowChrome.IsHitTestVisibleInChrome="True"`; a column kept clear for the system's buttons); the picture below in `PictureHost`; the `Popup`.
- The picture host is a `Grid` holding the picture alone for now. The other two plans add layers above the picture in it (unlock overlay and captions, then the Stats readout), so nothing else should assume the host has one child.
- The menu has a row of Back, Home and Recents, then a separator, then Notifications. Phase 2's volume row and Phase 3's rotation row go between the two, each under a separator; Quick settings, Screenshot and Lock screen go after Notifications. The three navigation icons are drawn shapes; the only font glyph is the `⋯` (Segoe MDL2 `E712`, as on the main window's device rows).
- `PhoneViewWindow.xaml.cs`: hover logic, senders for `back` / `homebtn` / `recents` / `notifications`, Esc handling. Title and `⋯` dim when inactive (in XAML). All key handling is in `Window_PreviewKeyDown`, with the order of checks written out: the later plans add F8 ahead of the Esc check and PIN digits after it (`PHONE-PLANS-ORDER.md`, row 14). After Esc has closed the menu, holding it down does not go Back.
- `windows/RemoteDesktopWin/PhoneMenu.cs` (new, no WPF types): the hover timing as a small state machine with the clock passed in (`MenuHover`), the window-fit maths (`PhoneFit`), the send throttle (`SendThrottle`) and `phone-state` parsing (`PhoneState`). The last two have tests and no caller until Phase 2.
- Window fit: the frame is measured against the picture host, not `Content`. The phone stays the same size on screen when it turns (the long side of the picture as drawn stays what it was). The window is never larger than the work area of the monitor it is on, and is moved only as far as keeps it there. It is not resized while maximised or minimised, and catches up when it is restored. It is only fitted again when the picture's shape changes, so a size the user chose survives repeated `screen-info`.
- `App.xaml`: `RdMenuButton` (a menu row that is a `Button` and never takes the focus) and `RdCaptionButton` (a button in a `WindowCaption` title bar).
- Tests: console project `windows/RemoteDesktopWin.PhoneViewTests` that compiles `PhoneMenu.cs` in, like `ClipboardTests`, and runs in the Windows step of `.github/workflows/release.yml`. It covers: hover opens after the delay and not before; leaving closes after the delay; re-entering cancels the close; clicks; a drag holds it open; Esc; the first fit, turning, a small work area and staying on screen; the throttle; `phone-state`. The unlock and video plans add their Windows cases to this same project.
- Checked by eye, from screenshots of the real window (the system's part of the bar does not show in an offscreen render): normal, inactive, maximised, landscape, minimum width with a long name, and the open menu.
- Checked against the S26 through UI Automation: see the build-order table in `PHONE-PLANS-ORDER.md` for what was and wasn't.
- Still to check by hand, with a real mouse: minimise, maximise and close respond to clicks; hover opens and closes the menu; the snap flyout on maximise; dragging and double-clicking the bar; resizing from each edge and corner. And Windows 10.

### Phase 2: phone state, volume and the extra actions (built)

Phone:

- `android/.../PhoneControls.kt` (new): answers `volume` and `get-phone-state` with `phone-state`. Volume is `setStreamVolume(STREAM_MUSIC, level, FLAG_SHOW_UI)` and `adjustStreamVolume` with `ADJUST_MUTE` / `ADJUST_UNMUTE`; the level is clamped, a `level` or `mute` of the wrong type changes nothing, `SecurityException` is caught and `isVolumeFixed` respected. `phone-state` is built by a plain function from the numbers, so it is tested without a phone.
- `ConnectionManager.handleJson`: `volume` and `get-phone-state` go to `PhoneControls` behind `mayControl(from)`; `quick-settings`, `lock` and `screenshot` join the input branch.
- `InputAccessibilityService.handle`: the three global actions (`GLOBAL_ACTION_QUICK_SETTINGS`; `GLOBAL_ACTION_LOCK_SCREEN` and `GLOBAL_ACTION_TAKE_SCREENSHOT` on API 28+). `menuActions` says which of them this phone can do now, for `can`.
- Waking: `InputAccessibilityService.wakesPhone(msg)` lists the messages that are real input, as row 9 of `PHONE-PLANS-ORDER.md` has them. `handle` wakes the screen for those and nothing else, so `lock` does not wake, and a message a later step routes into `handle` does not wake unless it is added to the list. `volume` and `get-phone-state` never reach `handle`; the same goes for `rotate` in Phase 3.
- Tests: `PhoneControlsTest`, on the `AllowOtherPhonesTest` pattern (`RecordingSocket`, fields set by reflection, **no new shadow of `PeerAuth` or `ConnectionManager`**, which breaks other test classes in a full run). Volume from an approved PC changes the stream and is answered; levels out of range and nonsense; mute and unmute; ignored with control off, from an unapproved device, and from a phone while *Allow other phones* is off; `phone-state` reports range and `can`, on Android 8 and with the service off; each global action is performed; which messages wake a sleeping phone.

Windows:

- `MainWindow.OnJson`: `phone-state` from the viewed phone goes to `_phoneView.OnPhoneState`.
- `PhoneViewWindow`: asks on open, on `ResendStartView` and on each menu open; shows the rows; the slider; the speaker icon; the "update the phone" line.
- `PhoneMenu.cs` gained the decisions: `VolumeSender` (what the slider sends and which answers it shows), `MenuRows` (which rows, and when the update line), `PhoneState.SpeakerClick`, and a timer hook on `SendThrottle` so a level the thumb rests on mid-drag is sent without waiting for the drag to end.
- `App.xaml`: `RdMenuSlider`, a slider that never takes the focus.
- Tests in `RemoteDesktopWin.PhoneViewTests` for all of the above.

One `phone-state` message is in both test suites as the same text (`phoneStateIsBuiltAsTheWindowsAppReadsIt` on the phone, `CheckPhoneState` on Windows): the phone must build it and Windows must read it, so the two can't come to disagree on a field's name or type.

Checked on the S26 through the rig (Android 17, debug build): the slider moved the media volume and the phone's own volume panel showed; mute and unmute; after the phone's volume keys changed the level, the reopened menu showed it; Quick settings opened; Screenshot saved a file in `DCIM/Screenshots` and the PC's "New screenshot" card appeared about two seconds later, through the existing `fs-offer` with nothing added. Checked by eye, from pictures of the menu: all rows, the update line, and several partial `can` lists.

Not checked: `lock` on a real phone (left to the unlock plan, where the lock screen is the subject), and anything needing a real mouse: dragging the thumb, clicking the track, and that the menu stays open while the thumb is dragged outside it.

### Phase 3: rotation

Phone:

- `android/.../RotationOverride.kt` (new), owned by `InputAccessibilityService`: add, update and remove the overlay window, on the main thread, with `addView` failures caught.
- `rotate` is accepted only when `PhoneScreenCapture.isViewing(from)`.
- Release when the phone has no viewers left. `PhoneScreenCapture.refresh()` already runs on stop-view, peer-left, relay loss, revocation, and when the control switch goes off (`Prefs.allowControl` clears the viewers), so that is the one place to hook. The window also dies with the service, so a crash can't leave the phone sideways.
- Also release when the screen turns off or the phone locks (`ACTION_SCREEN_OFF`, registered only while an override is held). The override is not put back afterwards; `phone-state` reports `auto`. The lock screen is where someone unlocks the phone remotely, so it keeps the phone's own orientation.
- Fix `ScreenCaptureService`. This is the only place the fix is planned: `PHONE-VIDEO-PLAN.md` step 0.6 used to carry it too and now relies on this one. Register a `DisplayManager.DisplayListener` on the capture thread, and `MediaProjection.Callback.onCapturedContentResize` on API 34+. When the output size changes, make a new `ImageReader`, call `virtualDisplay.resize(w, h, dpi)` and `virtualDisplay.surface = reader.surface`, close the old reader, clear the pacer and `reusableBitmap`, and send `screen-info`.
- Tests: `rotate` from a viewing PC adds a window with the right `screenOrientation`; from a device that isn't viewing, nothing; `auto`, stop-view, peer-left and screen-off remove it; `phone-state` reports it.

Windows:

- The three-way control, the highlight from `phone-state.rotation`, the "didn't rotate" toast. The window re-fit from Phase 1 does the rest.
- The row goes between the volume row and Notifications (a comment in `PhoneViewWindow.xaml` marks the place), shown when `can` has `rotate`: add it to `MenuRows`. The update line then reads "…for volume, rotation and more."
- The phone side adds `rotate` to `can` and reports the real `rotation` from `PhoneControls.state`. PROTOCOL.md already describes both fields; it needs the `rotate` message.

### Phase 4: docs and release

- PROTOCOL.md: Phase 2's messages went in with Phase 2, since any release ships what is on `main`. Check `rotate` is there too.
- README.md "PC → Phone" list; windows/README.md "a phone →" paragraph; android/README.md if it describes what a viewer can do. README.md still says a secure lock can't be unlocked remotely; the unlock plan rewrites that, so don't add to it here.
- Both apps must ship. Usual release steps in RELEASING.md.

## Compatibility

| PC app | Phone app | What happens |
|--------|-----------|--------------|
| new | old (1.0.53 or earlier) | Back, Home, Recents and Notifications work. The rest is replaced by the "update the phone" line. |
| old | new | Nothing changes. |
| new | new | Everything, limited to what the phone lists in `can`. |

## Checks that need a real phone

Phase 1 can be checked against the released phone app. Phases 2 and 3 need a build on a phone. Step 0 of `PHONE-PLANS-ORDER.md` gives the debug build its own app id (`.debug`), so it installs beside the release app as a separate device with its own approvals. Theo agreed to that on the S26 (SM-S942W, Android 17) only. Nothing is installed on the S20 (Android 13): its half of these checks is done by Theo when a release reaches it.

1. Volume: slider moves the phone's media volume and the phone's panel shows; mute and unmute; menu reopened shows the real level. Done on the S26, except dragging with a real mouse.
2. Screenshot: the capture card pops up on the PC. Done on the S26.
3. Lock: the phone locks, and the next click in the viewer wakes it. Getting back in is blind until `PHONE-UNLOCK-PLAN.md` is built. Not done: it is first tried in that plan's Phase 1.
4. Rotation on the S26: home screen, a portrait-only app, a video app. Taps land where clicked after turning. The S20 at release.
5. Rotation, then lock the phone: the override is gone and the lock screen is the phone's usual one.
6. Rotation, then close the viewer window, and separately kill the PC app: the phone returns to its own orientation both times.
7. Smooth mode (MediaProjection) on, then rotate: the picture fills the window and taps land right.
8. Windows 10, if one can be found: buttons draw and work.

## Anything else?

Included above: Notifications, Quick settings, Screenshot, Lock screen, mute.

Worth adding, cheap, say the word:

- **Always on top** (Windows only): one checkable row; handy for a small phone window.
- **Keyboard shortcuts:** Alt is already left to Windows by the viewer, so Alt+B / H / R could be Back / Home / Recents, and Alt+Up / Down volume. The menu could show them beside each row.

Left out:

- **Keep the phone awake while viewing:** it sleeps on its own timeout and wakes on the next click. `PHONE-VIDEO-PLAN.md` step 1.5 holds it awake in smooth sharing only, which is the one case that needs it.
- **Power menu:** easy, but a restart leaves the phone at its PIN prompt, where the app can't run until someone unlocks it by hand. The unlock plan does not change that.
- **Brightness:** needs the "Modify system settings" permission on the phone.
- **Ring, alarm and call volume:** more sliders than the menu wants, and Do Not Disturb rules get in the way.
