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
    private readonly PeerAuth _auth = new(AppSettings.StorageDir);

    private record Peer(string Id, string Device, string Name);

    /// <summary>One row of the devices list: an online peer, a pending approval,
    /// or a trusted-but-offline device from the trust store. Public so the
    /// XAML DataTemplate can bind to it.</summary>
    public sealed record DeviceRow
    {
        public string? PeerId { get; init; }
        public string? Fingerprint { get; init; }
        public string Name { get; init; } = "";
        public string Title { get; init; } = "";
        public string Subtitle { get; init; } = "";
        public System.Windows.Media.Brush? Dot { get; init; }
        public bool ShowApprove { get; init; }
        public bool ShowView { get; init; }
        public bool ShowDisconnect { get; init; }
        public bool ShowRevoke { get; init; }
        public bool ShowMore => ShowDisconnect || ShowRevoke;
    }

    private string _lastState = "disconnected";

    // Compact layout: once connected with devices in the session, only the
    // essentials stay visible; these track what the user opted to reveal.
    private bool _extrasShown;
    private bool _accessExpanded;
    private bool _simpleSized; // window is currently shrunk to the simple view

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

        // The stream is broadcast and every key holder can decrypt it, so it
        // pauses whenever an unapproved device shares the session (checked on
        // the capture thread — use the persisted consent flag, not the CheckBox).
        _streamer.Gate = () => _settings.AllowRemoteControl && _auth.AllPeersTrusted;
        // BeginInvoke: the prompt is modal, and OnJson runs inside a blocking
        // Dispatcher.Invoke from the socket thread — don't stall the receive loop.
        _auth.ApprovalNeeded += req => Dispatcher.BeginInvoke(() => ShowApprovalPrompt(req));
        _auth.Changed += RefreshDeviceList;

        ServerUrlBox.Text = _settings.ServerUrl;
        SessionKeyBox.Text = _settings.SessionKey;
        DeviceNameBox.Text = _settings.DeviceName;
        OwnCodeText.Text = $"This device's code: {DeviceIdentity.ShortCode(_auth.OwnFingerprint)}";
        StartupCheck.IsChecked = _settings.StartWithWindows;
        AllowStreamCheck.IsChecked = _settings.AllowRemoteControl;
        AllowFilesCheck.IsChecked = _settings.AllowFileAccess;
        if (IsElevated())
        {
            ElevateButton.Visibility = Visibility.Collapsed;
            ElevateGrantedText.Visibility = Visibility.Visible;
            ElevateCaption.Text = "Admin-only windows like Task Manager can be viewed and controlled.";
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
        ApplySectionVisibility();
    }

    private void Consent_Changed(object sender, RoutedEventArgs e)
    {
        _settings.AllowRemoteControl = AllowStreamCheck.IsChecked == true;
        _settings.AllowFileAccess = AllowFilesCheck.IsChecked == true;
        _settings.Save();
        ApplySectionVisibility();
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
        string key;
        do
        {
            var bytes = RandomNumberGenerator.GetBytes(24);
            key = Convert.ToBase64String(bytes).Replace("+", "-").Replace("/", "_").TrimEnd('=');
        } while (SessionKeyPolicy.WeaknessOf(key) != null); // ~1 in 3000 random keys contains a run
        SessionKeyBox.Text = key;
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

        var weakness = SessionKeyPolicy.WeaknessOf(_settings.SessionKey);
        if (weakness != null)
        {
            MessageBox.Show(
                $"This session key is too easy to guess: {weakness}.\n\n" +
                "Anyone who guesses the key gets full control of this computer — use Generate for a strong one.",
                "Weak session key");
            return;
        }
        _peers.Clear();
        _auth.Attach(_ws);
        _auth.OnSessionStart(Crypto.DeriveKeys(_settings.SessionKey).pairId); // wipes trust if the key changed
        RefreshDeviceList();
        _ws.Start(_settings.ServerUrl, _settings.SessionKey, _settings.DeviceName, _settings.DeviceUid);
    }

    private void OnState(string state)
    {
        _lastState = state;
        if (state != "connected")
        {
            _peers.Clear();
            _auth.ResetConnection(); // peer ids are stale after a drop
            RefreshDeviceList();
        }
        UpdateStatusCard();
    }

    private void UpdateStatusCard()
    {
        var s = _lastState;
        if (s == "connected")
        {
            StatusTitle.Text = "Connected";
            StatusDot.Fill = (System.Windows.Media.Brush)FindResource("RdGoodBrush");
            StatusSubtitle.Text = _peers.Count == 0
                ? "No paired devices online"
                : "Paired: " + string.Join(", ", _peers.Select(p => $"{p.Name} ({p.Device})"));
        }
        else if (s == "connecting")
        {
            StatusTitle.Text = "Connecting…";
            StatusDot.Fill = (System.Windows.Media.Brush)FindResource("RdWarnBrush");
            StatusSubtitle.Text = _settings.ServerUrl;
        }
        else
        {
            StatusTitle.Text = "Not connected";
            StatusDot.Fill = (System.Windows.Media.Brush)FindResource("RdDangerBrush");
            StatusSubtitle.Text = s.StartsWith("disconnected") ? s : "Save & connect to join the session";
        }
        ApplySectionVisibility();
    }

    /// <summary>
    /// Once connected with devices in the session the window slims down to the
    /// essentials: the devices list and a one-line permissions summary, with the
    /// window shrunk to fit. Everything else hides behind "Show more settings".
    /// Outside that state the full layout is shown, as before.
    /// </summary>
    private void ApplySectionVisibility()
    {
        bool compact = _lastState == "connected" && _peers.Count > 0;
        bool extras = !compact || _extrasShown;

        SessionCard.Visibility = extras ? Visibility.Visible : Visibility.Collapsed;
        StatusCard.Visibility = extras ? Visibility.Visible : Visibility.Collapsed;
        ComputerCard.Visibility = extras ? Visibility.Visible : Visibility.Collapsed;

        bool detail = !compact || _accessExpanded;
        AccessDetailPanel.Visibility = detail ? Visibility.Visible : Visibility.Collapsed;
        AccessSummaryText.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
        AccessToggleButton.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
        AccessToggleButton.Content = detail ? "\uE70E" : "\uE70D"; // chevron up / down
        AccessToggleButton.ToolTip = detail ? "Hide the permission switches" : "Show the permission switches";

        int granted = (AllowStreamCheck.IsChecked == true ? 1 : 0)
                    + (AllowFilesCheck.IsChecked == true ? 1 : 0)
                    + (StartupCheck.IsChecked == true ? 1 : 0)
                    + (IsElevated() ? 1 : 0);
        AccessSummaryText.Text = $"{granted}/4 permissions granted";

        MoreButton.Visibility = compact ? Visibility.Visible : Visibility.Collapsed;
        MoreButton.Content = _extrasShown ? "Hide extra settings" : "Show more settings";

        // The simple view holds far less content, so let the window hug it
        // (it re-fits as devices come and go); restore the tall fixed window
        // whenever the full layout is back.
        bool simple = compact && !_extrasShown;
        if (simple != _simpleSized)
        {
            _simpleSized = simple;
            if (simple)
            {
                MinHeight = 240;
                MaxHeight = 800; // long device lists scroll instead of growing off-screen
                SizeToContent = SizeToContent.Height;
            }
            else
            {
                SizeToContent = SizeToContent.Manual;
                MinHeight = 600;
                MaxHeight = double.PositiveInfinity;
                Height = 800;
            }
        }
    }

    private void AccessToggle_Click(object sender, RoutedEventArgs e)
    {
        _accessExpanded = !_accessExpanded;
        ApplySectionVisibility();
    }

    private void More_Click(object sender, RoutedEventArgs e)
    {
        _extrasShown = !_extrasShown;
        ApplySectionVisibility();
    }

    private void OnJson(JsonNode msg)
    {
        var from = msg["from"]?.GetValue<string>();
        // Host-side consent: the toggle must be on AND the sender must be an
        // approved device (see PROTOCOL.md, "Device authentication").
        bool controlAllowed = AllowStreamCheck.IsChecked == true && _auth.IsTrusted(from);

        switch (msg["type"]?.GetValue<string>())
        {
            case "welcome":
                _peers.Clear();
                foreach (var p in msg["peers"]!.AsArray())
                    _peers.Add(new Peer(p!["id"]!.GetValue<string>(), p["device"]!.GetValue<string>(), p["name"]!.GetValue<string>()));
                foreach (var p in _peers)
                    _auth.OnPeerJoined(p.Id, p.Name, p.Device);
                RefreshDeviceList();
                break;

            case "peer-joined":
                var np = msg["peer"]!;
                var joined = new Peer(np["id"]!.GetValue<string>(), np["device"]!.GetValue<string>(), np["name"]!.GetValue<string>());
                _peers.Add(joined);
                _auth.OnPeerJoined(joined.Id, joined.Name, joined.Device);
                RefreshDeviceList();
                Toast.Show($"{joined.Name} ({joined.Device}) is online");
                break;

            case "peer-left":
                var leftId = msg["id"]?.GetValue<string>();
                _peers.RemoveAll(p => p.Id == leftId);
                if (leftId != null) _auth.OnPeerLeft(leftId);
                RefreshDeviceList();
                if (_pcView != null && _pcView.PeerId == leftId) _pcView.Close();
                if (_phoneView != null && _phoneView.PeerId == leftId) _phoneView.Close();
                break;

            case "error":
                StatusSubtitle.Text = "Error: " + msg["message"]?.GetValue<string>();
                break;

            case "auth-challenge":
            case "auth-response":
                _auth.HandleJson(msg);
                break;

            case "auth-result":
                OnAuthResult(from, msg["status"]?.GetValue<string>());
                break;

            case "start-view":
                if (controlAllowed)
                {
                    _streamer.Start();
                    _streamer.RequestKeyframe(); // joining mid-stream needs a full frame
                    Toast.Show($"{PeerName(from)} started viewing this screen");
                }
                break;

            case "request-keyframe":
                if (_auth.IsTrusted(from)) _streamer.RequestKeyframe();
                break;

            case "stop-view":
                if (!_auth.IsTrusted(from)) break;
                _streamer.Stop();
                Toast.Show($"{PeerName(from)} stopped viewing this screen");
                break;

            case "cad":
                if (controlAllowed) InputInjector.CtrlAltDel();
                break;

            case "mouse":
                if (!controlAllowed) break;
                var action = msg["action"]!.GetValue<string>();
                var x = msg["x"]!.GetValue<double>();
                var y = msg["y"]!.GetValue<double>();
                if (action == "move") InputInjector.MouseMove(x, y);
                else InputInjector.MouseButton(msg["button"]?.GetValue<string>() ?? "left", action == "down", x, y);
                break;

            case "scroll":
                if (controlAllowed)
                    InputInjector.Scroll(msg["dx"]?.GetValue<double>() ?? 0, msg["dy"]?.GetValue<double>() ?? 0);
                break;

            case "key":
                if (controlAllowed)
                    InputInjector.KeyEvent(msg["code"]!.GetValue<string>(), msg["action"]?.GetValue<string>() ?? "press");
                break;

            case "text":
                if (controlAllowed)
                    InputInjector.TypeText(msg["text"]?.GetValue<string>() ?? "");
                break;

            case "screen-info":
                var siW = msg["width"]?.GetValue<int>() ?? 0;
                var siH = msg["height"]?.GetValue<int>() ?? 0;
                if (_phoneView != null && _phoneView.PeerId == from) _phoneView.OnScreenInfo(siW, siH);
                if (_pcView != null && _pcView.PeerId == from) _pcView.OnScreenInfo(siW, siH);
                break;

            case "fs-list":
            case "fs-get":
            case "fs-begin":
            case "fs-end":
                // File chunks are broadcast like video, so serving also requires
                // that no unapproved device is present in the session.
                _fs.HandleJson(msg, peerAccessAllowed:
                    AllowFilesCheck.IsChecked == true && _auth.IsTrusted(from) && _auth.AllPeersTrusted);
                break;

            case "fs-list-result":
                _fileExplorer?.OnListResult(msg);
                break;
        }
    }

    /// <summary>A peer told us where we stand with it (we are the viewer here).</summary>
    private void OnAuthResult(string? from, string? status)
    {
        if (from == null) return;
        switch (status)
        {
            case "trusted":
                // Approved (possibly after a prompt): re-request any stream whose
                // start-view was dropped while we were still unapproved.
                if (_pcView != null && _pcView.PeerId == from) _pcView.ResendStartView();
                if (_phoneView != null && _phoneView.PeerId == from) _phoneView.ResendStartView();
                break;
            case "pending":
                Toast.Show($"{PeerName(from)} is asking its user to approve this device — " +
                           $"code {DeviceIdentity.ShortCode(_auth.OwnFingerprint)}");
                break;
            case "denied":
                Toast.Show($"{PeerName(from)} denied this device access");
                break;
            case "revoked":
            case "disconnected":
                if (_pcView != null && _pcView.PeerId == from) _pcView.Close();
                if (_phoneView != null && _phoneView.PeerId == from) _phoneView.Close();
                _streamer.Stop(); // a full hang-up: stop streaming to them too
                Toast.Show(status == "revoked"
                    ? $"{PeerName(from)} revoked this device's access"
                    : $"{PeerName(from)} ended this device's session");
                break;
        }
    }

    private void ShowApprovalPrompt(PeerAuth.PendingRequest req)
    {
        RestoreFromTray();
        var result = MessageBox.Show(this,
            $"\"{req.Name}\" ({req.Device}) wants to pair with this computer.\n\n" +
            $"Device code: {DeviceIdentity.ShortCode(req.Fingerprint)}\n\n" +
            "Only allow if that device shows the same code. Once approved it can " +
            "view, control and browse this computer whenever those switches are on.",
            "New device wants to connect",
            MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
        if (result == MessageBoxResult.Yes) _auth.Approve(req.Fingerprint);
        else _auth.Deny(req.Fingerprint);
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

    /// <summary>Rebuild the devices list: online peers (with their trust state)
    /// plus trusted devices that are currently offline. Each row carries its
    /// own action buttons (approve/deny, view/files, disconnect/revoke).</summary>
    private void RefreshDeviceList()
    {
        var good = (System.Windows.Media.Brush)FindResource("RdGoodBrush");
        var warn = (System.Windows.Media.Brush)FindResource("RdWarnBrush");
        var offline = (System.Windows.Media.Brush)FindResource("RdOfflineBrush");

        var rows = new List<DeviceRow>();
        foreach (var p in _peers)
        {
            var fp = _auth.FingerprintOf(p.Id);
            var trusted = _auth.IsTrusted(p.Id);
            var pending = _auth.Pending.Any(r => r.PeerId == p.Id);
            var status =
                trusted ? "online" :
                pending ? "online — waiting for your approval" :
                fp != null ? "online — not approved" :
                "online — verifying…";
            var code = fp == null ? "" : $"Code {DeviceIdentity.ShortCode(fp)} — ";
            rows.Add(new DeviceRow
            {
                PeerId = p.Id,
                Fingerprint = fp,
                Name = p.Name,
                Title = $"{p.Name} ({p.Device})",
                Subtitle = code + status,
                Dot = trusted ? good : warn,
                ShowApprove = pending,
                ShowView = true,
                ShowDisconnect = trusted,
                ShowRevoke = fp != null && _auth.TrustedDevices.Any(d => d.Fingerprint == fp),
            });
        }
        foreach (var d in _auth.TrustedDevices)
            if (rows.All(r => r.Fingerprint != d.Fingerprint))
                rows.Add(new DeviceRow
                {
                    Fingerprint = d.Fingerprint,
                    Name = d.Name,
                    Title = $"{d.Name} ({d.Device})",
                    Subtitle = $"Code {DeviceIdentity.ShortCode(d.Fingerprint)} — " +
                               $"offline, trusted (last seen {d.LastSeen.ToLocalTime():g})",
                    Dot = offline,
                    ShowRevoke = true,
                });

        DevicesList.ItemsSource = rows;
        DevicesEmptyText.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        UpdateStatusCard();
    }

    private static DeviceRow? RowOf(object sender) => (sender as FrameworkElement)?.DataContext as DeviceRow;

    private void RowApprove_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender)?.Fingerprint is { } fp) _auth.Approve(fp);
    }

    private void RowDeny_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender)?.Fingerprint is { } fp) _auth.Deny(fp);
    }

    /// <summary>Disconnect/Revoke live in a per-row "⋯" menu.</summary>
    private void RowMore_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender) is not { } row) return;
        var menu = new ContextMenu { Style = (Style)FindResource("RdMenu") };
        if (row.ShowDisconnect)
        {
            var item = new MenuItem
            {
                Header = "Disconnect",
                Style = (Style)FindResource("RdMenuItem"),
                ToolTip = "End this device's current session (it stays trusted)",
            };
            item.Click += (_, _) => DisconnectRow(row);
            menu.Items.Add(item);
        }
        if (row.ShowRevoke)
        {
            var item = new MenuItem
            {
                Header = "Revoke",
                Style = (Style)FindResource("RdMenuItemDanger"),
                ToolTip = "Forget this device — it must be approved again to connect",
            };
            item.Click += (_, _) => RevokeRow(row);
            menu.Items.Add(item);
        }
        menu.PlacementTarget = (UIElement)sender;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    private void RevokeRow(DeviceRow row)
    {
        if (row.Fingerprint is not { } fp) return;
        var res = MessageBox.Show(this,
            $"Revoke \"{row.Name}\" ({DeviceIdentity.ShortCode(fp)})?\n\n" +
            "It will be disconnected and must be approved again before it can connect.",
            "Revoke device", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
        if (res != MessageBoxResult.Yes) return;
        _auth.Revoke(fp); // untrusting the live peer also pauses the stream via the gate
        Toast.Show($"Revoked {row.Name}");
    }

    private void DisconnectRow(DeviceRow row)
    {
        if (row.PeerId is not { } id) return;
        _auth.DisconnectPeer(id);
        // Hang up whatever it was watching. (Sessions are expected to hold two
        // devices, so stopping the streamer outright is fine.)
        _streamer.Stop();
        Toast.Show($"Disconnected {row.Name} (it stays trusted and may reconnect)");
    }

    /// <summary>The device name lives outside the session card, so apply it as
    /// soon as the field loses focus (the name is announced when joining).</summary>
    private void DeviceName_LostFocus(object sender, RoutedEventArgs e)
    {
        if (DeviceNameBox.Text.Trim() != _settings.DeviceName &&
            !string.IsNullOrWhiteSpace(_settings.SessionKey))
            Connect();
    }

    private void RowView_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender)?.PeerId is { } id)
            OpenViewFor(_peers.FirstOrDefault(p => p.Id == id));
    }

    private void RowFiles_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender)?.PeerId is { } id)
            OpenFilesFor(_peers.FirstOrDefault(p => p.Id == id));
    }

    private string PeerName(string? id) =>
        _peers.FirstOrDefault(p => p.Id == id)?.Name ?? "A paired device";

    private void OpenViewFor(Peer? peer)
    {
        if (peer == null) { Toast.Show("That device is no longer online."); return; }
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

    private void OpenFilesFor(Peer? peer)
    {
        if (peer == null) { Toast.Show("That device is no longer online."); return; }
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
