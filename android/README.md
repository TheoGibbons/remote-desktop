# Android app

Kotlin app (min SDK 26 / Android 8.0). It:

- views & controls the paired PC: **pinch-zoom/pan**, tap = click, long-press =
  right-click, two-finger drag = scroll, and a **full on-screen keyboard** with
  Win / Ctrl / Alt / Shift / function keys plus native-IME typing for long text;
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
2. Enter the **Server URL** and paste the **same session key** used on the PC
   (or tap *Generate strong key* here and copy it to the PC).
3. **Save & connect.** Settings persist and the app auto-connects from then on.

Then grant the capabilities you want (each is a one-time OS permission):

| Button | Enables | Permission |
|--------|---------|------------|
| **Enable screen sharing** | PC can *see* this phone | MediaProjection consent (one dialog) |
| **Enable tap control (Accessibility)** | PC can *tap/swipe* this phone | Accessibility service toggle |
| **Grant file access** | PC can browse this phone's files | All-files access (Android 11+) |

- **View desktop** → live PC view with the mouse/keyboard controls.
- **Browse desktop files** → download to `Downloads/RemoteDesktop`, or upload.

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
