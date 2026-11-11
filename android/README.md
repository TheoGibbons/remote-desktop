# Android app

Kotlin app (min SDK 26 / Android 8.0, compile/target SDK 36 / Android 16). It:

- views & controls the paired PC with an **always-visible mouse pointer**
  (touchpad-style): drag to move the pointer, tap = left click, two-finger tap =
  right click, press & hold then drag = left click-drag, two-finger drag = pan,
  pinch = zoom, three-finger drag = scroll wheel, and a **full
  on-screen keyboard** with Win / Ctrl / Alt / Shift / function keys plus
  native-IME typing for long text;
- shares its own screen using Accessibility screenshots on Android 11+ (with
  optional smoother MediaProjection sharing), and accepts taps/swipes from the PC,
  which can also set its media volume, turn its screen, take a screenshot and lock it;
- browses the PC's files and transfers files both ways.

## Build

Open the `android/` folder in **Android Studio** with support for Android Gradle
Plugin 9.4 and press Run. Use JDK 21 for Gradle so the Android 16 unit tests can
run. The app still targets Java 17 bytecode. Install Android SDK Platform 36 when
prompted; the Gradle wrapper selects Gradle 9.8.2 automatically. Kotlin is built
into the Android Gradle plugin, so the app applies no Kotlin plugin of its own;
the root `build.gradle.kts` pins the Kotlin version.

Or from the command line (needs the Android SDK; set `sdk.dir` in
`android/local.properties` or the `ANDROID_HOME` env var):

```bash
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build installs beside the release app, not over it. It has its own id
(`co.remotedesktop.debug`), its own name ("Remote Desktop (debug)") and a
`-debug` version. Android treats it as a separate app, so on the PC it shows up
as a separate device with its own identity, and it has to be approved on its
own. The accessibility service and the other permissions are also separate:
turn them on for the debug app too.

Public APKs and Play App Bundles use the Gradle `release` build and must be signed. Tagged GitHub
releases provide the signing values through secrets, compile the production
relay URL into the app's first-run default, and verify both signatures. The APK
and AAB share the same version and existing signing key. See
[`../RELEASING.md`](../RELEASING.md). Existing installs retain their saved URL.

Run build, compatibility tests and lint with:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The inset tests cover Android 14, 15 and 16, including keyboard visibility and
cutouts. H.264 regression tests simulate output becoming ready after the final
network packet, decoder congestion, output timeout and viewer pause cleanup.
Before publishing, also test pairing, remote input, screen-sharing
consent, file transfers, and rotation on an Android 16 device or emulator.

## First-run setup (once)

1. Launch **Remote Desktop**.
2. Easiest: click **QR code** in the desktop app and tap **Scan QR** here — it
   fills in the server URL and session ID and connects. (Or type the session ID
   under **Session ID** / tap **Generate new session ID** for a strong one and
   copy it to the PC. The server URL lives in **⋮ → Settings → Server URL**.)
   The **Session ID** screen also has **Scan QR code** and **Save & connect**.
   If no ID is saved, the main-screen card shows a red message asking you to add one.
3. Settings persist, a small foreground service keeps the session alive in the
   background (and after reboots and app updates), and the app auto-connects
   from then on.

Then grant the capabilities you want in **Permissions & Access → System
Permissions**:

| Row | Enables | Permission |
|-----|---------|------------|
| **Screen view and control (Accessibility)** | Default phone viewing on Android 11+, plus taps/swipes | Accessibility service toggle; normally persists across reboots |
| **Smooth screen sharing (optional)** | Smoother phone viewing while enabled | MediaProjection consent, required again after reboot or the capture session ends |
| **File access** | PC can browse this phone's files | All-files access (Android 11+) |
| **Notifications** | Waking this phone's screen for a viewer, and pairing requests while the app is in the background | Notification permission (asked at first launch on Android 13+) |

Accessibility viewing sends a screenshot about every 400 ms (up to 2.5 FPS), only
while an approved device is viewing. It needs no root, USB debugging, or new
screen-sharing approval after each reboot. The phone must first be unlocked and
connected; manufacturer battery restrictions can affect reconnection. Protected
content cannot be captured. Capture failures replace the previous picture with
"Screen unavailable" and retry. Rotation updates the desktop's screen dimensions.

Enable **Smooth screen sharing** for the existing faster stream (up to 10 FPS).
It takes over from Accessibility until stopped, then default viewing resumes
automatically. It follows the screen when the phone turns. It is optional and does not count as a missing permission on
Android 11+. Android 8–10 still require screen sharing for viewing and use
Accessibility for input only.

After updating an existing installation, Android may require turning the
Accessibility service off and on once to load the new screenshot capability.

The two switches under **Allow Paired Devices** (*View and control this phone* and *Browse and transfer
files*) are the ongoing consent switches — turn one off and paired devices
lose that capability immediately, without revoking the OS permission. Disabling
control, stopping a view, disconnecting, or revoking a viewer ends its capture
request. Re-enabling control requires the desktop to request viewing again.

The third switch, **Offer new photos to PCs** (on by default), makes each
photo, video, screenshot or screen recording you take pop up on your paired PCs
for a minute, ready to download with a click. It needs *Browse and transfer
files*, since that is how the PC fetches the file. Nothing polls: Android tells
the app when MediaStore changes, and the app looks once the change settles, so
a burst of shots arrives as one offer. Only files written in the last ten
minutes in the camera's or the system's own folders count (`DCIM/Camera`,
`DCIM/Screenshots`, `Pictures/Screenshots`, screen-recording folders), so
downloads, chat apps and archives kept deeper in `DCIM` never pop up. A capture
taken while no PC is online is not offered later.

**Reconnecting.** The app reconnects on its own, waiting longer after each
failure (up to 30 seconds), and tries again at once when a network comes up or
the phone moves to a different one (Wi-Fi, mobile data, or a VPN switching
between them). While it isn't connected, the connection-status card says why
(for example "No reply to a ping within 20 s"), and its refresh button tries
again immediately. If the relay refuses the connection, the card shows the
relay's reason. After too many session keys are tried from one network, the
relay blocks that network for an hour, and the card says **Blocked until** and
the time. A viewer that loses the connection shows **Reconnecting…** over the
last picture and resumes once the PC approves the new connection; if the PC is
gone when the phone gets back, the viewer closes.

**Connection history.** **⋮ → Settings → Connection history** lists, newest
first, each time this phone connected, lost the connection, failed to connect
or changed network, with its own reason, how long the connection had lasted
and the network it was on. "App started" after a gap means Android stopped the
app. The history survives restarts (the last 200 entries). **Copy** puts it on
the clipboard with UTC times, to line up with the relay's log (`[leave]`
reasons, see `server/README.md`).

**Approving devices.** The first time a device connects it must be approved on
the **Devices** screen (tap the connection-status card; a dialog appears, or a
notification if the app is in the background): compare the short **device
code** it shows with the code on that device before tapping *Allow*. Approval
is keyed to the device's cryptographic identity and sticks across reconnects
and restarts. Each trusted device has a **Revoke** button (it must be approved
again to connect) and, while online, a **Disconnect** button (ends the session;
trust is kept). Changing the session ID resets all approvals. While an
unapproved device is present in the session, screen sharing and file serving
pause.

The connection card lists each device online in the session with where it
stands: *Paired*, *Checking…*, *Waiting for approval* (approve this phone on
that PC, using the code shown), *Needs your approval*, or *Denied*. Each PC has
its own buttons, which work once the PC and this phone have approved each other:

- **View** → live PC view with the mouse/keyboard controls.
- **Browse** → download to `Downloads`, or select one or multiple files to upload to the desktop's `Downloads` folder.

Reinstalling the app gives the phone a new device code, so each PC must approve
it again. Updating in place keeps the code.

The file browser opens in the desktop's Downloads folder and shows file sizes
and modification dates beneath each name.
Tap the info button for the full name, type, exact size, modification time, and
location. The sort menu offers name, size, and modification date in either
direction (newest first by default), plus a Folders first tickbox (off by
default, so folders mix in with files), and remembers your choices. Tap the
current path to see its full location; Upload always sends to desktop Downloads.
While a download or upload runs, **Cancel** beside the status line stops it,
including any uploads still queued; a part-downloaded file is deleted.
Rotating the phone keeps the same visible file and scroll offset while the
folder reloads.

You can also select files or images in a file manager or gallery, tap **Share**,
and choose **Remote Desktop**. Choose an online desktop by its name and device
code, then tap **Send**. Pairing and desktop file-access permission must be
enabled; the share screen keeps the selection while you connect or review
devices. Transfers continue across screen rotation, and cancelled or failed
batches retain only files that have not been confirmed saved. Files go to the
desktop's `Downloads` folder, with duplicate names kept as separate files.
Use an updated desktop app for save confirmations. If an older app does not
reply, check `Downloads` before retrying to avoid sending duplicates.

Desktop sessions open in landscape. Switching apps keeps the current zoom and
desktop position; the viewer restores its viewport when streaming resumes.
Under **Settings → Privacy → Auto privacy**, **Auto privacy** enables the desktop
privacy screen when viewing starts, and **Auto un-privacy** restores the physical
desktop when viewing stops, including when leaving the viewer or switching apps.
Both switches are independent, saved, and off by default. Auto privacy requires
desktop support and remote-control permission; a manual unlock stays in effect
until the next viewing connection. An updated Windows host also applies auto
un-privacy after a connection drop; older hosts can only receive the restore
command while the connection is still available.
**Remember privacy screen**, on by default, instead keeps each PC's privacy
screen as it was last set in the viewer. It stays as it is when viewing stops,
and the next viewing connection to that PC turns it back on or off to match,
even if it was changed at the PC in between. PCs are told apart by their device
identity. It replaces both auto options, so turning one on turns the other off;
an install that already used an auto option keeps it.
The desktop viewer's top bar has, from the left: **Close**, the PC's name,
**Keyboard**, **Copy**, **Paste**, **Rotate**, **Privacy** and **More (⋮)**,
which holds **Stats** and the gesture help. Hold an icon to see its name
(holding Copy instead takes the PC's clipboard as it is).
Tap **Rotate** to switch the phone between portrait
and landscape, including when Android's auto-rotate is off. The selected
orientation stays in effect for the current viewer session.

Choose **More → Stats** for live end-to-end round-trip time,
wire/screen throughput, changed-frame FPS, JPEG decode time, dirty-area rate,
sender queue depth, sequence gaps, and decode errors. The same panel has a
persistent **Highlight updated rectangles in red** debugging option.

While holding a left-click drag, hold your finger near any edge of the visible
desktop to keep moving the cursor in that direction. The zoomed view follows
the cursor so you can extend a selection beyond the phone screen without zooming
out. Movement speeds up closer to the edge and stops when you move away or lift
your finger. The keyboard control rows use 75% of their previous
height. The keyboard uses Gboard-style typing rows; **?123** opens numbers and
symbols, **ABC** returns to letters, and **Fn** exposes F1–F12. Desktop modifiers
and navigation keys sit above the typing rows. **Shift** beside Z capitalizes
the next letter; double-tap it for Caps Lock and tap again to turn it off. Hold
that arrow Shift while pressing other keys for physical-keyboard shortcuts such
as **Shift+Enter**, **Shift+Tab**, and **Shift+Arrow**. **Del** and **Backspace**
repeat quickly while held, after a short initial delay. The
**Shift** beside Ctrl stays enabled until toggled off for desktop shortcuts.
**PrtSc** presses Print Screen once, however long it is held; with **Win** or
**Alt** latched it sends Win+PrtSc or Alt+PrtSc.

## Google Play notes

- The app requests `MANAGE_EXTERNAL_STORAGE` (All-files access) so the desktop
  can browse arbitrary folders. Play requires a declaration/justification for
  this permission, or you can restrict file browsing to app-scoped/SAF
  directories and drop it. For personal sideloading it works as-is.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` is used for screen sharing and shows an
  ongoing notification, per Android policy.
- The Accessibility service declares screenshot capability and describes screen
  viewing in its system permission description. Default viewing uses this only
  on Android 11+ and preserves the same device-approval and remote-control gates.
- The Accessibility service also acts as an input method (`flagInputMethodEditor`,
  Android 13+), so the PC's keyboard can type into the focused text field
  alongside the user's own keyboard. It types only what an approved device
  sends while remote control is allowed, and reads no text.

## What a viewer's menu can do

Besides taps and typing, an approved device that may control this phone
(*View and control this phone*, and *Allow other phones* for another phone)
can:

- set the media volume, and mute it. The phone shows its own volume panel, as
  when its volume keys are pressed;
- hold the screen in portrait or landscape while it is viewing, whichever way
  the phone is lying and whether or not auto-rotate is on. The phone turns its
  own way again when the last viewer stops or disconnects, and when the screen
  turns off, so it is never left sideways and its lock screen is always its
  usual one;
- open Quick settings, take a screenshot (saved like any other, so it is
  offered to your PCs if *Offer new photos to PCs* is on), and lock the phone.
  Locking and screenshots need Android 9 or later.

All of it goes through the Accessibility service except the volume, and needs
no further permission. The Windows app has these in the **⋯** menu of the
window that shows the phone.

## Waking a sleeping phone

When an approved device starts viewing, or sends a tap or key while the screen
is off, the phone turns its screen on. The lock screen stays in place and the
phone sleeps again on its usual timeout. Setting the volume, turning the
screen and locking are not input and leave a sleeping phone asleep. So does a
viewer that is already viewing and asks for the picture again.

A Samsung phone turns its screen on for an app only while that app's
notifications are allowed. That is why **Notifications** is one of the rows
in *Permissions & Access*, and is counted with the required ones on every
phone. Tapping the row opens the app's notification settings.

While a device is viewing, the phone tells it whether the screen is on and
whether the phone is locked, and whether a wake it tried a second ago failed.
The viewer shows that as a line over the picture: that the phone is asleep,
or that it didn't wake and notifications need allowing. The phone listens for
its screen and its lock only while someone is viewing.

## Unlocking the phone from a viewer

A viewer's taps and drags land on the lock screen as they do anywhere else.
So an approved device can swipe the lock screen away, and then draw the
pattern, or tap in the PIN or password, on the unlock screen behind it.

For now that is done blind. Android leaves the unlock screen out of every
picture an app can take, so the viewer gets it black, apart from a Back
arrow, and says over it that clicks and drags still reach it. The ordinary
lock screen, with its clock and notifications, is in the picture as usual.
Drawing a pattern this way has been tried on a Samsung phone; a PIN and a
password have not been tried yet.

What to know:

- Anyone using a device you have approved, who knows the pattern or PIN, gets
  a fully unlocked phone. *View and control this phone* is the switch that
  governs that.
- After a restart Android does not run the app until the phone has been
  unlocked once by hand.
- Fingerprint and face can't be supplied from another device.
- Wrong attempts count against the phone's own limit, as they do on the
  phone itself.
