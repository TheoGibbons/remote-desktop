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
not overwritten. Tagged GitHub releases build, sign through Azure Artifact
Signing, timestamp, and verify the EXE automatically. Local `dotnet publish`
does not perform that signing. For setup and publishing steps, see
[`../RELEASING.md`](../RELEASING.md).

(You can also open the folder in Visual Studio 2022 — it will detect the
`.csproj` — and press F5.)

Run the deterministic H.264 idle/recovery regression checks from the repository
root with `dotnet run --project windows/RemoteDesktopWin.Tests`. They exercise
the production capture loop with capture, encoder and transport doubles, including
an idle keyframe request behind a busy lane and the periodic repair of a final
inter frame. They take about 11 seconds and do not require a relay or GPU encoder.

Run `dotnet run --project windows/RemoteDesktopWin.SingleInstanceTests` for
cross-process checks of duplicate-launch rejection, normal shutdown, restart
handoff, and recovery after a crash. These checks use an isolated lock and do
not launch the UI or connect to a relay.

Run `dotnet run --project windows/RemoteDesktopWin.FileTransferTests` for file
save confirmations, duplicate names, incomplete and cancelled transfers (from
either end, before and after they begin),
unknown sizes, empty files and save failures, and for downloading what a phone
offers when it takes a photo or screenshot. These checks use temporary files
and a transport double, without connecting to a relay.

Run `dotnet run --project windows/RemoteDesktopWin.ClipboardTests` for the
clipboard's HTML envelope (including non-ASCII offsets), its size limit and the
terminal shortcuts. They never touch the real clipboard or press keys.

## Clipboard

When a phone taps **Copy**, this app presses copy in the window in front, waits
for the clipboard to change, and sends back the text along with its HTML version
when the app offered one. Copy is Ctrl+Insert rather than Ctrl+C almost
everywhere: with nothing selected, Ctrl+C stops the program running in a
terminal, and VS Code's or an IDE's terminal shares its window class with the
editor, so it can't be singled out. File Explorer gets Ctrl+C, a classic console
window gets its own Edit > Copy command, and PuTTY copies on select. If the
clipboard didn't change, the phone is shown what was already on it and asked
before using it; something a password manager marked private is never sent
that way. Long-pressing **Copy** on the phone takes the clipboard as it is,
without pressing anything. Copied files arrive as paths the phone can download
while **Browse and transfer files** is on.

A phone's **Paste** puts its text on this clipboard and presses Ctrl+V (Shift+Insert
in terminals). Text sent with **Copy to PC** only lands on the clipboard, with a
toast. Everything the clipboard does needs **View and control this computer**,
and nothing happens while the PC is locked. Like any injected input, the keys
can't reach a window running as administrator unless this app runs elevated
too, and the phone is told which of those it hit. Private copies arriving here
are kept out of clipboard history and cloud sync.

The PC viewer's **Copy to this PC** and **Paste from this PC** buttons do the
same with another PC. Clipboard work runs on its own threads with a time
limit, so an app that is slow to hand over what it copied can't stall the
connection.

## Privacy mode

While viewing from Android, tap **Privacy** to cover each physical Windows
monitor with a black, click-through screen containing small centered
**type unlock** text. The Android button turns red while privacy is active and
returns to its normal colour when the host confirms that it is off. Tap
**Show PC** to restore the screens, or type `unlock` on the physical keyboard
(no Enter needed; case insensitive). Other local
keyboard and mouse events are suppressed while the app's remote input keeps
working. Standard Windows pointer shapes are blanked during privacy, reapplied
when Windows settings change and once a second to repair silent cursor scheme
reloads. The configured cursor scheme is restored afterward. A hidden helper also restores
the scheme if the host process exits without cleanup. Android still draws its
own remote pointer. By default, privacy stays active when the viewer pauses or closes, a
device disconnects, approval is revoked, or the relay connection drops. Android's
**Settings → Privacy → Auto privacy** can enable privacy when viewing starts;
**Auto un-privacy** opts that viewing session into restoring local access when
it ends, including a dropped connection. Both are off by default. **Remember
privacy screen**, on by default, instead reapplies whatever the phone last set
for this PC each time viewing starts. Reconnecting
viewers receive the current privacy state and can tap **Show PC** to restore local
access. Typing `unlock` locally still works without a remote session. Turning off
remote control, locking/logging off Windows, or exiting the host also restores
local access. Privacy does not persist across host restarts.

Requires Windows 10 version 2004 or later. The overlays use
[`WDA_EXCLUDEFROMCAPTURE`](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowdisplayaffinity)
and remain absent from the app's DXGI and GDI captures. If overlay creation or
input-hook installation fails, activation rolls back and Android shows the
error. Display changes resize/recreate the overlays. This is a convenience
filter: Windows secure desktops (including UAC and Ctrl+Alt+Del) and applications
that read hardware through raw input are outside the low-level hook boundary.

Run `dotnet run --project windows/RemoteDesktopWin.PrivacyTests` for the
deterministic unlock/input-policy and host-session lifecycle checks. The lifecycle
checks use an isolated trust store and never connect to a relay or hide screens.
On an interactive Windows desktop,
add `-- --capture-smoke --input-smoke --cursor-smoke` to verify both actual capture paths,
click-through/no-focus behavior, native hook pass/block behavior, and cursor
restoration during normal cleanup and an abrupt host exit. The capture
check briefly shows a small test window; the input check briefly installs hooks
and consumes its injected test events before they reach any desktop application.
The cursor check briefly blanks standard pointer shapes and restores them.

## First-run setup (once)

Only one app instance can run per Windows session, including while it is hidden
in the tray. Launching another copy shows an error and exits. Open the existing
app from its tray icon, or choose **Exit** there before launching it again.

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
    long-press, mouse wheel = pinch zoom in/out, right-click = Back,
    middle-click = Home. If the phone shows its own navigation buttons,
    clicking them works too. While the window is focused, your keyboard types
    into the phone's focused text box (Android 13 and later), with Enter,
    Backspace, arrows and Ctrl shortcuts; Esc is Back, and Ctrl+V pastes this
    PC's clipboard.
- **Browse files** → a file explorer to download from / upload to the selected
  device (a PC or a phone), titled with its name. Each device gets its own
  window, so files can go to two phones at once, and each window's status line
  shows only that device's transfers. It opens in that device's Downloads
  folder, newest first; the sort button changes the order (name, size or date,
  either way) and can group folders first, and remembers your choice. On a
  phone with an SD card (or a USB drive), the up arrow at the top of its storage
  lists them all by name; pick one to switch to it. Downloads are saved to
  your Downloads folder — asked of Windows rather than assumed, so a relocated
  Downloads is honoured; uploads land in the peer's `Downloads` folder on
  Windows or Android alike. A name that clashes with a file already there gets
  a counter appended — nothing is overwritten.
  Choose **Upload** or drag files onto the device's files window to send them
  — several at once, also when the app runs as administrator. Files added while
  an upload runs join its queue. The browser moves to the phone's Downloads
  folder as the upload starts and refreshes after the phone confirms the saved
  files. **Cancel**, beside the status line while a download or upload runs,
  stops that window's transfers: a part-downloaded file is deleted, and
  cancelling an upload drops the rest of its queue.
- **Transfers** (beside the main window's transfer line, beside a files
  window's **Download**, or in the tray menu) lists every file sent or received
  this session, with which device it went to or came from, its progress and how
  it ended. Each file still under way has its own cancel button, which stops
  just that file; **Clear finished** tidies the list.
- **New photos and screenshots from a phone** pop up as a card in the corner of
  the screen, with a preview, for a minute (longer while the pointer rests on
  it). Click it to download them into Downloads, then click again to open the
  file, or the folder for several; **Show in folder** opens Downloads with the
  file selected. **✕** dismisses it, or cancels a download
  under way; a failed download can be retried with another click. The card
  never takes focus from what you are typing in. To stop them, turn off
  **Offer new photos to PCs** on the phone.
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
