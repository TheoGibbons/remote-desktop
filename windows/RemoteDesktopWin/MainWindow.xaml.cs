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

    // ---------- run at login ----------

    private void Startup_Changed(object sender, RoutedEventArgs e)
    {
        _settings.StartWithWindows = StartupCheck.IsChecked == true;
        _settings.Save();
        try
        {
            using var run = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run");
            if (_settings.StartWithWindows)
                run.SetValue("RemoteDesktopWin", '"' + Environment.ProcessPath + '"');
            else
                run.DeleteValue("RemoteDesktopWin", throwOnMissingValue: false);
        }
        catch (Exception ex)
        {
            Toast.Show("Could not update startup setting: " + ex.Message);
        }
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
                _peers.RemoveAll(p => p.Id == msg["id"]?.GetValue<string>());
                RefreshPeerList();
                break;

            case "error":
                StatusText.Text = "Error: " + msg["message"]?.GetValue<string>();
                break;

            case "start-view":
                if (AllowStreamCheck.IsChecked == true)
                {
                    _streamer.Start();
                    Toast.Show($"{PeerName(msg["from"]?.GetValue<string>())} started viewing this screen");
                }
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
                _phoneView?.OnScreenInfo(
                    msg["width"]?.GetValue<int>() ?? 0,
                    msg["height"]?.GetValue<int>() ?? 0);
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
            case 1: // video frame from the phone
                Dispatcher.BeginInvoke(() => _phoneView?.OnFrame(data));
                break;
            case 2: // file chunk
                _fs.HandleFileChunk(data);
                break;
        }
    }

    private void RefreshPeerList()
    {
        PeerList.ItemsSource = null;
        PeerList.ItemsSource = _peers;
        var hasAndroid = _peers.Any(p => p.Device == "android");
        ViewPhoneButton.IsEnabled = hasAndroid;
        FilesButton.IsEnabled = hasAndroid;
    }

    private Peer? FirstAndroid() => _peers.FirstOrDefault(p => p.Device == "android");

    private string PeerName(string? id) =>
        _peers.FirstOrDefault(p => p.Id == id)?.Name ?? "A paired device";

    private void ViewPhone_Click(object sender, RoutedEventArgs e) => OpenPhoneView();

    private void PeerList_DoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e) => OpenPhoneView();

    private void OpenPhoneView()
    {
        var phone = FirstAndroid();
        if (phone == null) return;
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

    private void Files_Click(object sender, RoutedEventArgs e)
    {
        var phone = FirstAndroid();
        if (phone == null) return;
        if (_fileExplorer == null || !_fileExplorer.IsLoaded)
        {
            _fileExplorer = new FileExplorerWindow(_ws, _fs, phone.Id);
            _fileExplorer.Closed += (_, _) => _fileExplorer = null;
            _fileExplorer.Show();
        }
        else
        {
            _fileExplorer.Activate();
        }
    }
}
