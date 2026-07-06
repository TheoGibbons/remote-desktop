# Windows app (RemoteDesktopWin)

C# / WPF app targeting **.NET 8 (Windows)**. It:

- streams the whole virtual desktop — **all monitors stitched** into one image —
  as JPEG frames when a phone asks to view it;
- injects mouse and keyboard (via `SendInput`, incl. Win/Ctrl/Alt/Shift and
  absolute multi-monitor positioning);
- shows the phone's screen and sends taps/swipes;
- browses the phone's files and transfers files both ways.

## Build & run

Prerequisites: [.NET 8 SDK](https://dotnet.microsoft.com/download) on Windows.

```powershell
cd windows\RemoteDesktopWin
dotnet run
```

To produce a single self-contained exe:

```powershell
dotnet publish -c Release -r win-x64 --self-contained ^
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
# output in bin\Release\net8.0-windows\win-x64\publish\
```

(You can also open the folder in Visual Studio 2022 — it will detect the
`.csproj` — and press F5.)

## First-run setup (once)

1. Launch the app.
2. Click **Generate** to create a session key (or paste the one from your phone).
3. Set the **Server URL** (e.g. `ws://192.168.1.50:8090/ws` or `wss://your.domain/ws`).
4. **Save & Connect.** Settings persist to
   `%APPDATA%\RemoteDesktopWin\settings.json` and the app auto-connects on every
   launch from then on.

Leave **"Allow paired devices to view and control this computer"** checked for
the phone to be able to control the PC.

- **View phone screen** / double-click the phone in the list → opens a live view
  where clicking taps and press-drag swipes; Back/Home/Recents buttons included.
- **Browse phone files** → a file explorer to download from / upload to the phone.
  Downloads are saved to `%USERPROFILE%\Downloads\RemoteDesktop`.

## Notes

- Screen capture uses GDI `CopyFromScreen`, so it will briefly pause on the
  secure desktop (UAC prompt / lock screen) and resume automatically.
- Streaming frame rate/quality/scale are in `settings.json`
  (`Fps`, `JpegQuality`, `MaxStreamWidth`).
