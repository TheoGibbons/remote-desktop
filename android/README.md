# Android app

Kotlin app (min SDK 26 / Android 8.0). It:

- views & controls the paired PC with an **always-visible mouse pointer**
  (touchpad-style): drag to move the pointer, tap = left click, two-finger tap =
  right click, press & hold then drag = left click-drag, two-finger drag = pan,
  pinch = zoom, three-finger drag = scroll wheel, and a **full
  on-screen keyboard** with Win / Ctrl / Alt / Shift / function keys plus
  native-IME typing for long text;
- shares its own screen (MediaProjection) so the PC can see it, and accepts
  taps/swipes from the PC (Accessibility gesture dispatch);
- browses the PC's files and transfers files both ways.

## Build

Open the `android/` folder in **Android Studio** (Giraffe or newer) and press
Run — it downloads the right Gradle/SDK automatically.

Or from the command line (needs the Android SDK; set `sdk.dir` in
`android/local.properties` or the `ANDROID_HOME` env var):

```bash
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-run setup (once)

1. Launch **Remote Desktop**.
2. Easiest: click **QR code** in the desktop app and tap **Scan QR** here — it
   fills in the server URL and session ID and connects. (Or type the session ID
   into the main-screen box / tap the refresh icon to generate a strong one and
   copy it to the PC. The server URL lives in **⋮ → Settings → Server URL**.)
3. Settings persist, a small foreground service keeps the session alive in the
   background (and after reboots), and the app auto-connects from then on.

Then grant the capabilities you want in **Permissions & Access → System
Permissions** (each is a one-time OS permission):

| Row | Enables | Permission |
|-----|---------|------------|
| **Screen sharing** | PC can *see* this phone | MediaProjection consent (one dialog) |
| **Tap control (Accessibility)** | PC can *tap/swipe* this phone | Accessibility service toggle |
| **File access** | PC can browse this phone's files | All-files access (Android 11+) |

The OS permissions above are one-time grants; the two switches under **Allow
Paired Devices** (*View and control this phone* and *Browse and transfer
files*) are the ongoing consent switches — turn one off and paired devices
lose that capability immediately, without revoking the OS permission.

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
- **Browse files** → download to `Downloads/RemoteDesktop`, or upload.

## Google Play notes

- The app requests `MANAGE_EXTERNAL_STORAGE` (All-files access) so the desktop
  can browse arbitrary folders. Play requires a declaration/justification for
  this permission, or you can restrict file browsing to app-scoped/SAF
  directories and drop it. For personal sideloading it works as-is.
- `FOREGROUND_SERVICE_MEDIA_PROJECTION` is used for screen sharing and shows an
  ongoing notification, per Android policy.

## Why the PC can't unlock the phone

Android does not let any app dismiss a **secure** lockscreen (PIN/pattern/
biometric); accessibility gestures can't cross it. If the phone uses swipe-only
(no secure lock), the PC's swipe/tap can unlock it because the gesture lands on
the lockscreen surface itself.
