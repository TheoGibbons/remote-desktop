# Android app

Kotlin app (min SDK 26 / Android 8.0, compile/target SDK 36 / Android 16). It:

- views & controls the paired PC with an **always-visible mouse pointer**
  (touchpad-style): drag to move the pointer, tap = left click, two-finger tap =
  right click, press & hold then drag = left click-drag, two-finger drag = pan,
  pinch = zoom, three-finger drag = scroll wheel, and a **full
  on-screen keyboard** with Win / Ctrl / Alt / Shift / function keys plus
  native-IME typing for long text;
- shares its own screen using Accessibility screenshots on Android 11+ (with
  optional smoother MediaProjection sharing), and accepts taps/swipes from the PC;
- browses the PC's files and transfers files both ways.

## Build

Open the `android/` folder in **Android Studio** with support for Android Gradle
Plugin 8.11 and press Run. Use JDK 21 for Gradle so the Android 16 unit tests can
run. The app still targets Java 17 bytecode. Install Android SDK Platform 36 when
prompted; the Gradle wrapper selects Gradle 8.13 automatically.

Or from the command line (needs the Android SDK; set `sdk.dir` in
`android/local.properties` or the `ANDROID_HOME` env var):

```bash
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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
   background (and after reboots), and the app auto-connects from then on.

Then grant the capabilities you want in **Permissions & Access → System
Permissions**:

| Row | Enables | Permission |
|-----|---------|------------|
| **Screen view and control (Accessibility)** | Default phone viewing on Android 11+, plus taps/swipes | Accessibility service toggle; normally persists across reboots |
| **Smooth screen sharing (optional)** | Smoother phone viewing while enabled | MediaProjection consent, required again after reboot or the capture session ends |
| **File access** | PC can browse this phone's files | All-files access (Android 11+) |

Accessibility viewing sends a screenshot about every 400 ms (up to 2.5 FPS), only
while an approved device is viewing. It needs no root, USB debugging, or new
screen-sharing approval after each reboot. The phone must first be unlocked and
connected; manufacturer battery restrictions can affect reconnection. Protected
content cannot be captured. Capture failures replace the previous picture with
"Screen unavailable" and retry. Rotation updates the desktop's screen dimensions.

Enable **Smooth screen sharing** for the existing faster stream (up to 10 FPS).
It takes over from Accessibility until stopped, then default viewing resumes
automatically. It is optional and does not count as a missing permission on
Android 11+. Android 8–10 still require screen sharing for viewing and use
Accessibility for input only.

After updating an existing installation, Android may require turning the
Accessibility service off and on once to load the new screenshot capability.

The two switches under **Allow Paired Devices** (*View and control this phone* and *Browse and transfer
files*) are the ongoing consent switches — turn one off and paired devices
lose that capability immediately, without revoking the OS permission. Disabling
control, stopping a view, disconnecting, or revoking a viewer ends its capture
request. Re-enabling control requires the desktop to request viewing again.

**Reconnecting.** The app reconnects on its own, waiting longer after each
failure (up to 30 seconds). While it isn't connected, the refresh button on the
connection-status card tries again immediately. If the relay refuses the
connection, the card shows its reason. After too many session keys are tried
from one network, the relay blocks that network for an hour, and the card says
**Blocked until** and the time.

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

- **View desktop** → live PC view with the mouse/keyboard controls.
- **Browse files** → download to `Downloads`, or select one or multiple files to upload to the desktop's `Downloads` folder.

The file browser shows file sizes and modification dates beneath each name.
Tap the info button for the full name, type, exact size, modification time, and
location. The sort menu offers name, size, and modification date in either
direction, always keeping folders first, and remembers your choice. Tap the
current path to see its full location; Upload always sends to desktop Downloads.
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
**Remember privacy screen** instead keeps each PC's privacy screen as it was last
set in the viewer. It stays as it is when viewing stops, and the next viewing
connection to that PC turns it back on or off to match, even if it was changed
at the PC in between. PCs are told apart by their device identity. It replaces
both auto options, so turning one on turns the other off.
In the desktop viewer, tap **Rotate (↻)** to switch the phone between portrait
and landscape, including when Android's auto-rotate is off. The selected
orientation stays in effect for the current viewer session.

Tap **Stats** for live end-to-end round-trip time,
wire/screen throughput, changed-frame FPS, JPEG decode time, dirty-area rate,
sender queue depth, sequence gaps, and decode errors. The same panel has a
persistent **Highlight updated rectangles in red** debugging option.

While holding a left-click drag, hold your finger near any edge of the visible
desktop to keep moving the cursor in that direction. The zoomed view follows
the cursor so you can extend a selection beyond the phone screen without zooming
out. Movement speeds up closer to the edge and stops when you move away or lift
your finger. The top toolbar and keyboard control rows use 75% of their previous
height. The keyboard uses Gboard-style typing rows; **?123** opens numbers and
symbols, **ABC** returns to letters, and **Fn** exposes F1–F12. Desktop modifiers
and navigation keys sit above the typing rows. **Shift** beside Z capitalizes
the next letter; double-tap it for Caps Lock and tap again to turn it off. Hold
that arrow Shift while pressing other keys for physical-keyboard shortcuts such
as **Shift+Enter**, **Shift+Tab**, and **Shift+Arrow**. **Del** and **Backspace**
repeat quickly while held, after a short initial delay. The
**Shift** beside Ctrl stays enabled until toggled off for desktop shortcuts.

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

## Why the PC can't unlock the phone

Android does not let any app dismiss a **secure** lockscreen (PIN/pattern/
biometric); accessibility gestures can't cross it. If the phone uses swipe-only
(no secure lock), the PC's swipe/tap can unlock it because the gesture lands on
the lockscreen surface itself.
