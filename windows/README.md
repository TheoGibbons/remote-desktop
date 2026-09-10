# Windows app (RemoteDesktopWin)

C# / WPF app targeting **.NET 10 (Windows)**. It:

- streams the whole virtual desktop — **all monitors stitched** into one image —
  as dirty-rect JPEG tiles when a phone **or another PC** asks to view it;
- injects mouse and keyboard (via `SendInput`, incl. Win/Ctrl/Alt/Shift and
  absolute multi-monitor positioning);
- **views and controls another Windows PC** (full keyboard + mouse), as well as
  showing a phone's screen and sending taps/swipes;
- browses the other device's files and transfers files both ways.

## Build & run

Prerequisites: [.NET 10 SDK](https://dotnet.microsoft.com/download) on Windows.

```powershell
cd windows\RemoteDesktopWin
dotnet run
```

To produce a single self-contained exe:

```powershell
dotnet publish -c Release -r win-x64 --self-contained ^
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
# output in bin\Release\net10.0-windows\win-x64\publish\
```

For a public build with the production relay pre-filled for new installations,
add `-p:DefaultRelayServerUrl=wss://relay.remote-desktop.co/ws`. Existing user settings are
not overwritten. Tagged GitHub releases perform this build automatically; see
[`../RELEASING.md`](../RELEASING.md).

(You can also open the folder in Visual Studio 2022 — it will detect the
`.csproj` — and press F5.)

## First-run setup (once)

1. Launch the app.
2. Click **Generate** to create a session key (or paste the one from your phone).
3. Set the **Server URL** (e.g. `ws://192.168.1.50:8090/ws` or `wss://relay.remote-desktop.co/ws`).
4. **Save & Connect.** Settings persist to
   `%APPDATA%\RemoteDesktopWin\settings.json` and the app auto-connects on every
   launch from then on.

Leave **"Allow paired devices to view and control this computer"** checked for
a phone **or another paired PC** to be able to control this computer.
**"Allow paired devices to browse and transfer files on this computer"** gates
file access separately — with it off, peers cannot list or download this PC's
files (your own downloads from other devices still work). Both toggles persist
across restarts.

**Approving devices.** The first time a device connects it must be approved
here (and this PC must be approved there): a prompt shows its name and a short
**device code** — approve only if the same code is shown on that device (each
device's own code appears under the checkboxes). Approval sticks across
reconnects and restarts; it is keyed to the device's cryptographic identity in
`%APPDATA%\RemoteDesktopWin\` (`identity.bin`, `trusted-devices.json`), not to
its name. The **Devices** list shows every approved or connected device with
**Approve / Deny / Revoke / Disconnect** buttons — *Revoke* deletes the
approval (the device must be approved again to connect), *Disconnect* just ends
its current session. Changing the session key resets all approvals. While an
unapproved device is present in the session, streaming and file serving pause.

- Select a device in **Devices**, then **View screen** (or double-click
  it) → opens a live view:
  - **another PC** → full desktop control: move/click/scroll with the mouse and
    type with your real keyboard (shortcuts, modifiers and arrows included) while
    the window is focused. A **Ctrl+Alt+Del** button covers the one combo Windows
    won't let apps send directly. The remote PC must have *Allow paired devices…*
    checked.
  - **a phone** → click = tap, press-and-drag = real drag, hold in place =
    long-press, mouse wheel = pinch zoom in/out; Back/Home/Recents buttons included.
- **Browse files** → a file explorer to download from / upload to the selected
  device (a PC or a phone). Downloads are saved to
  `%USERPROFILE%\Downloads`; uploads land in the peer's `Downloads` folder on
  Windows or Android alike. A name that clashes with a file already there gets
  a counter appended — nothing is overwritten.
- **QR code** (next to *Generate*) → shows the server URL + session key as a QR
  code the phone scans to pair in one step.
- Closing the window hides the app to the **tray** — paired devices can still
  connect. Right-click the tray icon → Exit to quit, and tick **Start with
  Windows** so the PC is reachable after a reboot without opening anything.
  A startup launch passes `--minimized`, so after logging in the app sits in
  the tray without showing its window or taking focus; double-click the tray
  icon (or right-click → Open) to bring it up.
- **Controlling elevated windows** (Task Manager, UAC-elevated installers):
  Windows UIPI silently blocks injected input into higher-integrity windows,
  so remote control appears to "stop working" while they have focus. Click
  **Restart as administrator** to fix that; *Start with Windows* then switches
  from a Run-key entry to a highest-run-level scheduled task automatically
  (the Run key can't start elevated programs). The UAC consent dialog itself
  lives on the secure desktop and can never be captured or clicked remotely —
  that one is by design and has no workaround.

## Notes

- Screen capture uses the **DXGI Desktop Duplication API** (the OS reports
  exactly which regions changed, at low CPU cost), falling back automatically
  to GDI `CopyFromScreen` + frame diffing where duplication isn't available
  (secure desktop, some RDP sessions, rotated monitors) and retrying DXGI
  every ~10 s while on the fallback. Capture pauses briefly on the secure
  desktop (UAC prompt / lock screen) and resumes automatically.
- Streaming is **dirty-rect based**: only changed regions are sent as JPEG
  tiles (plus a keyframe on start/resize/request and every ~10 s while things
  change). A static screen costs almost no bandwidth, which is why streaming
  defaults to **native resolution** and JPEG quality 80.
- Frame rate/quality/scale are in `settings.json` (`Fps`, `JpegQuality`,
  `MaxStreamWidth` — `0` means native resolution; set e.g. `1600` to clamp
  width on very slow links).
