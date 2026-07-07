using System.ComponentModel;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;

namespace RemoteDesktopWin;

public partial class MainWindow : Window
{
    private readonly AppSettings _settings = AppSettings.Load();
    private readonly WsClient _ws = new();
    private readonly ScreenStreamer _streamer;
    private readonly FsService _fs;

    private record Peer(string Id, string Device, string Name)
    {
        public override string ToString() => $"{Name} ({Device})";
    }

    private readonly List<Peer> _peers = new();
    private PhoneViewWindow? _phoneView;
    private PcViewWindow? _pcView;
    private FileExplorerWindow? _fileExplorer;
    private System.Windows.Forms.NotifyIcon? _tray;
    private bool _exiting;
    private bool _trayTipShown;

    public MainWindow()
    {
        InitializeComponent();
        _streamer = new ScreenStreamer(_ws)
        {
            Fps = _settings.Fps,
            JpegQuality = _settings.JpegQuality,
            MaxWidth = _settings.MaxStreamWidth,
        };
        _fs = new FsService(_ws);

        ServerUrlBox.Text = _settings.ServerUrl;
        SessionKeyBox.Text = _settings.SessionKey;
        DeviceNameBox.Text = _settings.DeviceName;
        StartupCheck.IsChecked = _settings.StartWithWindows;
        if (IsElevated())
        {
            ElevateButton.Visibility = Visibility.Collapsed;
            Title += " (administrator)";
        }

        _ws.StateChanged += s => Dispatcher.Invoke(() => OnState(s));
        _ws.JsonReceived += m => Dispatcher.Invoke(() => OnJson(m));
        _ws.BinaryReceived += OnBinary;
        _fs.TransferStatus += s => Dispatcher.Invoke(() => TransferText.Text = s);

        SetupTray();
        // Closing the window hides to the tray so paired devices can still
        // connect; the session only ends via the tray's Exit.
        Closing += OnClosingToTray;

        if (_settings.AutoConnect && !string.IsNullOrWhiteSpace(_settings.SessionKey))
            Connect();
    }

    // ---------- tray ----------

    private void SetupTray()
    {
        _tray = new System.Windows.Forms.NotifyIcon
        {
            Text = "Remote Desktop",
            Visible = true,
        };
        try
        {
            var exe = Environment.ProcessPath!;
            _tray.Icon = System.Drawing.Icon.ExtractAssociatedIcon(exe);
        }
        catch
        {
            _tray.Icon = System.Drawing.SystemIcons.Application;
        }

        var menu = new System.Windows.Forms.ContextMenuStrip();
        menu.Items.Add("Open", null, (_, _) => RestoreFromTray());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => RestoreFromTray();
    }

    private void RestoreFromTray()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
    }

    private void OnClosingToTray(object? sender, CancelEventArgs e)
    {
        if (_exiting) return;
        e.Cancel = true;
        Hide();
        if (!_trayTipShown)
        {
            _trayTipShown = true;
            _tray?.ShowBalloonTip(3000, "Remote Desktop is still running",
                "Paired devices can still connect. Right-click the tray icon and choose Exit to quit.",
                System.Windows.Forms.ToolTipIcon.Info);
        }
    }

    private void ExitApp()
    {
        _exiting = true;
        _streamer.Stop();
        _ws.Stop();
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
        Application.Current.Shutdown();
    }

    // ---------- elevation & run at login ----------

    // Windows UIPI silently discards SendInput into higher-integrity windows
    // (Task Manager auto-elevates, so does any UAC-elevated app). Running this
    // app elevated is the supported way to control those remotely.

    private static bool IsElevated()
    {
        using var id = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(id)
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }

    private void Elevate_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Environment.ProcessPath!)
            {
                UseShellExecute = true,
                Verb = "runas",
            });
            ExitApp();
        }
        catch
        {
            // User declined the UAC prompt — keep running as-is.
        }
    }

    private void Startup_Changed(object sender, RoutedEventArgs e)
    {
        _settings.StartWithWindows = StartupCheck.IsChecked == true;
        _settings.Save();
        ApplyStartupRegistration();
    }

    /// <summary>
    /// Register (or remove) launch-at-login matching the current elevation:
    /// a plain HKCU Run entry when not elevated, a highest-run-level scheduled
    /// task when elevated (the Run key cannot start elevated programs).
    /// </summary>
    private void ApplyStartupRegistration()
    {
        var exe = Environment.ProcessPath!;
        try
        {
            using var run = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run");
            run.DeleteValue("RemoteDesktopWin", throwOnMissingValue: false);
            RunSchtasks("/Delete /F /TN RemoteDesktopWin"); // stale task from an elevated install

            if (!_settings.StartWithWindows) return;
            if (IsElevated())
                RunSchtasks($"/Create /F /TN RemoteDesktopWin /SC ONLOGON /RL HIGHEST /TR \"\\\"{exe}\\\"\"");
            else
                run.SetValue("RemoteDesktopWin", '"' + exe + '"');
        }
        catch (Exception ex)
        {
            Toast.Show("Could not update startup setting: " + ex.Message);
        }
    }

    private static void RunSchtasks(string args)
    {
        try
        {
            using var p = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("schtasks.exe", args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
            });
            p?.WaitForExit(5000);
        }
        catch { }
    }

    private void GenKey_Click(object sender, RoutedEventArgs e)
    {
        var bytes = RandomNumberGenerator.GetBytes(24);
        SessionKeyBox.Text = Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=');
    }

    private void Qr_Click(object sender, RoutedEventArgs e)
    {
        var key = SessionKeyBox.Text.Trim();
        if (string.IsNullOrEmpty(key))
        {
            MessageBox.Show("Enter or generate a session key first.", "Pair by QR code");
            return;
        }
        new QrWindow(ServerUrlBox.Text.Trim(), key) { Owner = this }.ShowDialog();
    }

    private void Connect_Click(object sender, RoutedEventArgs e) => Connect();

    private void Connect()
    {
        _settings.ServerUrl = ServerUrlBox.Text.Trim();
        _settings.SessionKey = SessionKeyBox.Text.Trim();
        _settings.DeviceName = DeviceNameBox.Text.Trim();
        _settings.Save();

        if (_settings.SessionKey.Length < 16)
        {
            MessageBox.Show("Session key must be at least 16 characters. Use Generate for a strong one.");
            return;
        }
        _peers.Clear();
        RefreshPeerList();
        _ws.Start(_settings.ServerUrl, _settings.SessionKey, _settings.DeviceName, _settings.DeviceUid);
    }

    private void OnState(string state)
    {
        StatusText.Text = state;
        if (state != "connected")
        {
            _peers.Clear();
            RefreshPeerList();
        }
    }

    private void OnJson(JsonNode msg)
    {
        switch (msg["type"]?.GetValue<string>())
        {
            case "welcome":
                _peers.Clear();
                foreach (var p in msg["peers"]!.AsArray())
                    _peers.Add(new Peer(p!["id"]!.GetValue<string>(), p["device"]!.GetValue<string>(), p["name"]!.GetValue<string>()));
                RefreshPeerList();
                break;

            case "peer-joined":
                var np = msg["peer"]!;
                var joined = new Peer(np["id"]!.GetValue<string>(), np["device"]!.GetValue<string>(), np["name"]!.GetValue<string>());
                _peers.Add(joined);
                RefreshPeerList();
                Toast.Show($"{joined.Name} ({joined.Device}) is now paired and online");
                break;

            case "peer-left":
                var leftId = msg["id"]?.GetValue<string>();
                _peers.RemoveAll(p => p.Id == leftId);
                RefreshPeerList();
                if (_pcView != null && _pcView.PeerId == leftId) _pcView.Close();
                if (_phoneView != null && _phoneView.PeerId == leftId) _phoneView.Close();
                break;

            case "error":
                StatusText.Text = "Error: " + msg["message"]?.GetValue<string>();
                break;

            case "start-view":
                if (AllowStreamCheck.IsChecked == true)
                {
                    _streamer.Start();
                    _streamer.RequestKeyframe(); // joining mid-stream needs a full frame
                    Toast.Show($"{PeerName(msg["from"]?.GetValue<string>())} started viewing this screen");
                }
                break;

            case "request-keyframe":
                _streamer.RequestKeyframe();
                break;

            case "stop-view":
                _streamer.Stop();
                Toast.Show($"{PeerName(msg["from"]?.GetValue<string>())} stopped viewing this screen");
                break;

            case "cad":
                if (AllowStreamCheck.IsChecked == true) InputInjector.CtrlAltDel();
                break;

            case "mouse":
                if (AllowStreamCheck.IsChecked != true) break;
                var action = msg["action"]!.GetValue<string>();
                var x = msg["x"]!.GetValue<double>();
                var y = msg["y"]!.GetValue<double>();
                if (action == "move") InputInjector.MouseMove(x, y);
                else InputInjector.MouseButton(msg["button"]?.GetValue<string>() ?? "left", action == "down", x, y);
                break;

            case "scroll":
                if (AllowStreamCheck.IsChecked == true)
                    InputInjector.Scroll(msg["dx"]?.GetValue<double>() ?? 0, msg["dy"]?.GetValue<double>() ?? 0);
                break;

            case "key":
                if (AllowStreamCheck.IsChecked == true)
                    InputInjector.KeyEvent(msg["code"]!.GetValue<string>(), msg["action"]?.GetValue<string>() ?? "press");
                break;

            case "text":
                if (AllowStreamCheck.IsChecked == true)
                    InputInjector.TypeText(msg["text"]?.GetValue<string>() ?? "");
                break;

            case "screen-info":
                var siW = msg["width"]?.GetValue<int>() ?? 0;
                var siH = msg["height"]?.GetValue<int>() ?? 0;
                var siFrom = msg["from"]?.GetValue<string>();
                if (_phoneView != null && _phoneView.PeerId == siFrom) _phoneView.OnScreenInfo(siW, siH);
                if (_pcView != null && _pcView.PeerId == siFrom) _pcView.OnScreenInfo(siW, siH);
                break;

            case "fs-list":
            case "fs-get":
            case "fs-begin":
            case "fs-end":
                _fs.HandleJson(msg);
                break;

            case "fs-list-result":
                _fileExplorer?.OnListResult(msg);
                break;
        }
    }

    private void OnBinary(byte[] data)
    {
        if (data.Length == 0) return;
        switch (data[0])
        {
            case 1: // full-frame JPEG (phone host)
                Dispatcher.BeginInvoke(() => _phoneView?.OnFrame(data));
                break;
            case 2: // file chunk
                _fs.HandleFileChunk(data);
                break;
            case 3: // dirty-rect screen patch (Windows host)
                Dispatcher.BeginInvoke(() => _pcView?.OnPatch(data));
                break;
        }
    }

    private void RefreshPeerList()
    {
        var selectedId = (PeerList.SelectedItem as Peer)?.Id;
        PeerList.ItemsSource = null;
        PeerList.ItemsSource = _peers;
        if (selectedId != null)
            PeerList.SelectedItem = _peers.FirstOrDefault(p => p.Id == selectedId);
        // Both a PC and a phone can be viewed and browsed; act on the selected peer.
        ViewButton.IsEnabled = _peers.Count > 0;
        FilesButton.IsEnabled = _peers.Count > 0;
    }

    private string PeerName(string? id) =>
        _peers.FirstOrDefault(p => p.Id == id)?.Name ?? "A paired device";

    /// <summary>The selected peer, or the only paired peer if there is exactly one.</summary>
    private Peer? TargetPeer() =>
        PeerList.SelectedItem as Peer ?? (_peers.Count == 1 ? _peers[0] : null);

    private void View_Click(object sender, RoutedEventArgs e) => OpenViewFor(TargetPeer());

    private void PeerList_DoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e) =>
        OpenViewFor(PeerList.SelectedItem as Peer ?? TargetPeer());

    private void OpenViewFor(Peer? peer)
    {
        if (peer == null) { Toast.Show("Select a paired device in the list first."); return; }
        if (peer.Device == "windows") OpenPcView(peer);
        else OpenPhoneView(peer);
    }

    private void OpenPhoneView(Peer phone)
    {
        if (_phoneView == null || !_phoneView.IsLoaded)
        {
            _phoneView = new PhoneViewWindow(_ws, phone.Id);
            _phoneView.Closed += (_, _) => _phoneView = null;
            _phoneView.Show();
        }
        else
        {
            _phoneView.Activate();
        }
    }

    private void OpenPcView(Peer pc)
    {
        if (_pcView == null || !_pcView.IsLoaded)
        {
            _pcView = new PcViewWindow(_ws, pc.Id, pc.Name);
            _pcView.Closed += (_, _) => _pcView = null;
            _pcView.Show();
        }
        else
        {
            _pcView.Activate();
        }
    }

    private void Files_Click(object sender, RoutedEventArgs e)
    {
        var peer = TargetPeer();
        if (peer == null) { Toast.Show("Select a paired device in the list first."); return; }
        if (_fileExplorer == null || !_fileExplorer.IsLoaded)
        {
            _fileExplorer = new FileExplorerWindow(_ws, _fs, peer.Id);
            _fileExplorer.Closed += (_, _) => _fileExplorer = null;
            _fileExplorer.Show();
        }
        else
        {
            _fileExplorer.Activate();
        }
    }
}
