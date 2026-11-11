using System.ComponentModel;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Threading;

namespace RemoteDesktopWin;

public partial class MainWindow : Window
{
    private readonly AppSettings _settings;
    private readonly WsClient _ws = new();
    private readonly ScreenStreamer _streamer;
    private readonly FsService _fs;
    private readonly ClipboardService _clip;
    private readonly PeerAuth _auth;
    private readonly PrivacyOverlayManager _privacy;
    private readonly HashSet<string> _autoUnprivacyViewers = new();
    private readonly PhoneFeed _phoneFeed;
    // The phone picture that is decoded and waiting to be shown, with the
    // window it was decoded for. Only the newest is kept.
    private (PhoneViewWindow View, PhoneViewWindow.DecodedPicture Picture, PhoneFrameHeader? Header)? _phonePicture;
    private readonly object _phonePictureLock = new();
    private bool _phoneDrawPosted;
    private readonly FrameMailbox _pcPatches;

    // A few hundred milliseconds of a desktop stream. A window further behind
    // than that is better served by a keyframe than by catching up.
    private const int MaxWaitingPatches = 8;

    /// <summary>A device online in the session. Version is null for apps from
    /// before devices announced it.</summary>
    private record Peer(string Id, string Device, string Name, string? Version)
    {
        public static Peer From(JsonNode p) => new(
            p["id"]!.GetValue<string>(), p["device"]!.GetValue<string>(), p["name"]!.GetValue<string>(),
            ClipboardService.Str(p["version"]));
    }

    private readonly Updater _updater = new(Updater.RunningVersion, Environment.ProcessPath!);
    // Checks once a minute whether a downloaded update can go in without interrupting anyone.
    private readonly DispatcherTimer _updateTimer = new() { Interval = TimeSpan.FromMinutes(1) };
    private int _idleMinutes;
    private const int IdleMinutesBeforeUpdate = 3;
    private string? _updateBalloonShownFor;
    private Action? _balloonClicked;

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
        /// <summary>The app version, shown fainter after the subtitle. Empty for
        /// apps too old to say and for devices that are offline.</summary>
        public string VersionText { get; init; } = "";
        public System.Windows.Media.Brush? Dot { get; init; }
        /// <summary>What the dot's colour means, for its tooltip and for screen
        /// readers: the colour alone says whether a device is online.</summary>
        public string DotTip { get; init; } = "";
        public bool ShowApprove { get; init; }
        public bool ShowView { get; init; }
        public bool ShowDisconnect { get; init; }
        public bool ShowRevoke { get; init; }
        /// <summary>The newer version this PC could be asked to update to.</summary>
        public string? UpdateTo { get; init; }
        public bool ShowMore => ShowDisconnect || ShowRevoke || UpdateTo != null;
    }

    private string _lastState = "disconnected";

    // Compact layout: once connected with devices in the session, only the
    // essentials stay visible; these track what the user opted to reveal.
    private bool _extrasShown;
    private bool _accessExpanded;
    private bool _simpleSized; // window is currently shrunk to the simple view

    private readonly List<Peer> _peers = new();
    private volatile PhoneViewWindow? _phoneView;   // read by the phone feed's thread too
    private PcViewWindow? _pcView;
    // One per device, so each upload goes to the device its window names.
    private readonly Dictionary<string, FileExplorerWindow> _fileExplorers = new();
    private readonly TransferList _transfers;
    private readonly DownloadsFolderOpener _downloadsOpener;
    private TransfersWindow? _transfersWindow;
    private System.Windows.Forms.NotifyIcon? _tray;
    private readonly bool _trayIcon;
    private bool _exiting;
    private bool _trayTipShown;

    private bool _settingsInitialized;

    public MainWindow() : this(AppSettings.Load(), new PeerAuth(AppSettings.StorageDir)) { }

    /// <param name="trayIcon">False for a test, which builds this window without
    /// showing it: an icon in the notification area would be the one thing of it
    /// on the screen, and a run that crashed would leave it there.</param>
    internal MainWindow(AppSettings settings, PeerAuth auth, bool trayIcon = true)
    {
        _trayIcon = trayIcon;
        _settings = settings;
        _auth = auth;
        InitializeComponent();
        VersionText.Text = Updater.RunningVersion;
        _updater.Changed += () => Dispatcher.BeginInvoke(OnUpdaterChanged);
        _updateTimer.Tick += (_, _) => UpdateTick();
        _updateTimer.Start();
        if (App.JustUpdated) Loaded += (_, _) => Toast.Show($"Remote Desktop updated to version {Updater.RunningVersion}");
        _streamer = new ScreenStreamer(_ws)
        {
            Fps = _settings.Fps,
            JpegQuality = _settings.JpegQuality,
            MaxWidth = _settings.MaxStreamWidth,
        };
        _fs = new FsService(_ws);
        _transfers = new TransferList(_fs, PeerName);
        _downloadsOpener = new DownloadsFolderOpener(_transfers, _fs.IncomingDir,
            a => Dispatcher.BeginInvoke(DispatcherPriority.Background, a));
        // Posted like the transfer list's updates, so the list has heard the file is done.
        _fs.PushSaved += path => Dispatcher.BeginInvoke(() => _downloadsOpener.Saved(path));
        _clip = new ClipboardService(_ws.SendJson,
            peer => AllowStreamCheck.IsChecked == true && _auth.IsTrusted(peer),
            () => AllowFilesCheck.IsChecked == true);
        _privacy = new PrivacyOverlayManager(Dispatcher);
        _privacy.Changed += error =>
        {
            _streamer.RequestKeyframe();
            foreach (var peer in _peers) SendPrivacyState(peer.Id, error);
        };
        Closed += (_, _) => _privacy.Dispose();
        // Privacy normally survives session endings. An approved viewer may
        // explicitly request restoration when its viewing session ends.
        _streamer.StreamingChanged += streaming =>
        {
            if (!streaming) RestorePrivacyForEndedViewers();
        };

        // The stream is broadcast and every key holder can decrypt it, so it
        // pauses whenever an unapproved device shares the session (checked on
        // the capture thread — use the persisted consent flag, not the CheckBox).
        _streamer.Gate = () => _settings.AllowRemoteControl && _auth.AllPeersTrusted;
        // BeginInvoke: the prompt is modal, so it opens after OnJson returns
        // rather than from inside it.
        _auth.ApprovalNeeded += req => Dispatcher.BeginInvoke(() => ShowApprovalPrompt(req));
        _auth.Changed += RefreshDeviceList;
        // A phone approved, or no longer approved, while its window is open is asked again.
        _auth.Changed += () => _phoneView?.TrustChanged();
        _auth.Changed += () =>
        {
            RestorePrivacyForEndedViewers();
            foreach (var peer in _peers) SendPrivacyState(peer.Id);
        };

        ServerUrlBox.Text = _settings.ServerUrl;
        SessionKeyBox.Text = _settings.SessionKey;
        DeviceNameBox.Text = _settings.DeviceName;
        OwnCodeText.Text = $"This device's code: {DeviceIdentity.ShortCode(_auth.OwnFingerprint)}";
        StartupCheck.IsChecked = _settings.StartWithWindows;
        AllowStreamCheck.IsChecked = _settings.AllowRemoteControl;
        AllowFilesCheck.IsChecked = _settings.AllowFileAccess;
        _settingsInitialized = true;
        if (IsElevated())
        {
            ElevateButton.Visibility = Visibility.Collapsed;
            ElevateGrantedText.Visibility = Visibility.Visible;
            ElevateCaption.Text = "Admin-only windows like Task Manager can be viewed and controlled.";
            Title += " (administrator)";
        }
        if (AppProfile.Name is { } profile)
        {
            // A development copy beside the installed app: told apart by name,
            // and kept from the settings the two would otherwise share.
            Title += $" [{profile}]";
            StartupCheck.IsEnabled = false;
            ElevateButton.IsEnabled = false;
        }

        // Frames are drawn below input and rendering, so a stream the PC cannot
        // keep up with costs frames rather than a frozen window.
        Action<Action> drawLater = draw => Dispatcher.BeginInvoke(DispatcherPriority.Background, draw);
        _phoneFeed = new PhoneFeed(DecodePhonePicture, _ws.SendJson,
            () => System.Diagnostics.Stopwatch.GetTimestamp() * 1000 / System.Diagnostics.Stopwatch.Frequency);
        Closed += (_, _) => _phoneFeed.Dispose();
        _pcPatches = new FrameMailbox(MaxWaitingPatches, patch => _pcView?.OnPatch(patch), drawLater);

        // Never make the socket thread wait for the window: while it waits it
        // answers no pings, so the relay drops the PC, and the PC can neither
        // notice nor reconnect until the window catches up. BeginInvoke keeps
        // the messages in order.
        _ws.StateChanged += s => Dispatcher.BeginInvoke(() => OnState(s));
        _ws.JsonReceived += OnJsonReceived;
        _ws.BinaryReceived += OnBinary;
        _fs.TransferChanged += u => Dispatcher.BeginInvoke(() =>
        {
            // Null once that file has ended: a cancel can land between a chunk and its progress.
            if (_transfers.Apply(u) is not { } row) return;
            // Every device's transfers show here, so each names its device.
            if (u.HasProgress) TransferText.ShowTransfer(u.Sending, $"{u.Percent}% {u.Name} · {row.PeerName}");
            else if (u.Text is { } text) TransferText.Text = $"{text} · {row.PeerName}";
        });

        SetupTray();
        // Closing the window hides to the tray so paired devices can still
        // connect; the session only ends via the tray's Exit.
        Closing += OnClosingToTray;

        // Launched at login (--minimized): come up in the tray, not on screen.
        // App.OnStartup shows the window regardless, so hide it once it's up — and
        // don't let it steal focus from whatever the user is doing meanwhile.
        if (App.StartMinimized)
        {
            ShowActivated = false;
            Loaded += (_, _) => Hide();
        }

        // Refresh the login entry in the background (schtasks spawns processes):
        // it may predate --minimized, or point at an exe that has since moved.
        if (_settings.StartWithWindows)
            Task.Run(ApplyStartupRegistration);

        if (_settings.AutoConnect && !string.IsNullOrWhiteSpace(_settings.SessionKey))
            Connect();
    }

    // ---------- tray ----------

    private void SetupTray()
    {
        _tray = new System.Windows.Forms.NotifyIcon
        {
            Text = AppProfile.Name is { } profile ? $"Remote Desktop [{profile}]" : "Remote Desktop",
            Visible = _trayIcon,
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
        menu.Items.Add("Transfers", null, (_, _) => ShowTransfers());
        menu.Items.Add(new System.Windows.Forms.ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitApp());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => RestoreFromTray();
        _tray.BalloonTipClicked += (_, _) => _balloonClicked?.Invoke();
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
            _balloonClicked = null;
            _tray?.ShowBalloonTip(3000, "Remote Desktop is still running",
                "Paired devices can still connect. Right-click the tray icon and choose Exit to quit.",
                System.Windows.Forms.ToolTipIcon.Info);
        }
    }

    private void ExitApp()
    {
        _exiting = true;
        _privacy.Disable();
        _streamer.Stop();
        _ws.Stop();
        if (_tray != null) { _tray.Visible = false; _tray.Dispose(); _tray = null; }
        Application.Current.Shutdown();
    }

    // ---------- updates ----------

    // The relay names the newest release; this PC downloads it straight away
    // and installs it once nobody has been using the app for a few minutes.

    private void OnLatestRelease(string? version)
    {
        // A development copy stays the build it is. Never hearing of a release
        // also keeps "update now" from a paired device from doing anything.
        if (AppProfile.Name != null) return;
        _updater.OnLatest(version);
        _ = _updater.PrepareAsync();
        RefreshDeviceList(); // other PCs that are behind can now be asked to update
    }

    /// <summary>The app can't update itself, so the user has to.</summary>
    private bool UpdateNeedsYou =>
        _updater.UpdateAvailable && _updater.Target == _updater.Latest &&
        _updater.State == Updater.Phase.Failed && !_updater.FailureIsTemporary;

    private void OnUpdaterChanged()
    {
        var latest = _updater.Latest;
        var phase = _updater.Target == latest ? _updater.State : Updater.Phase.Idle;
        string? text = null, button = null;
        if (latest != null && !_updater.UpdateAvailable) text = "Up to date";
        else if (_updater.UpdateAvailable)
        {
            (text, button) = phase switch
            {
                Updater.Phase.Downloading => ($"Downloading version {latest}…", null),
                Updater.Phase.Ready => ($"Version {latest} is ready. It installs itself when nobody is using this PC remotely.", "Restart now"),
                Updater.Phase.Failed when _updater.FailureIsTemporary =>
                    ($"Couldn't download version {latest} yet: {_updater.Error} It tries again within the hour.", "Download"),
                Updater.Phase.Failed => ($"Couldn't update to version {latest}: {_updater.Error}", "Download"),
                Updater.Phase.Unsupported => ($"Version {latest} is available. {_updater.Error}", "Download"),
                _ => ($"Version {latest} is available.", null),
            };
        }
        UpdateText.Text = text ?? "";
        UpdateText.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        UpdateButton.Content = button;
        UpdateButton.Visibility = button == null ? Visibility.Collapsed : Visibility.Visible;
        ApplySectionVisibility();

        // Waiting in the tray, the window can't say so; once per version.
        if (UpdateNeedsYou && _updateBalloonShownFor != latest)
        {
            _updateBalloonShownFor = latest;
            _balloonClicked = OpenReleasesPage;
            _tray?.ShowBalloonTip(10000, $"Remote Desktop {latest} is available",
                $"This PC couldn't update itself: {_updater.Error} Click to download it.",
                System.Windows.Forms.ToolTipIcon.Info);
        }
    }

    private void UpdateButton_Click(object sender, RoutedEventArgs e)
    {
        if (_updater.ReadyToInstall) InstallUpdate();
        else OpenReleasesPage();
    }

    private static void OpenReleasesPage()
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Updater.ReleasesPage)
            {
                UseShellExecute = true,
            });
        }
        catch (Exception ex) { Toast.Show("Couldn't open the browser: " + ex.Message); }
    }

    private void UpdateTick()
    {
        if (!_updater.UpdateAvailable) return;
        if (!_updater.ReadyToInstall)
        {
            _idleMinutes = 0;
            _ = _updater.PrepareAsync(); // retries a failed download once its hour is up
            return;
        }
        _idleMinutes = IsIdleForUpdate() ? _idleMinutes + 1 : 0;
        if (_idleMinutes >= IdleMinutesBeforeUpdate) InstallUpdate();
    }

    /// <summary>Restarting now would interrupt nobody: no one is viewing this
    /// PC or moving files, and none of this app's windows is being used. Nor is
    /// the privacy screen up: it ends with the app, uncovering the screen.</summary>
    private bool IsIdleForUpdate() =>
        !_privacy.IsEnabled &&
        !_streamer.IsStreaming &&
        _transfers.ActiveCount == 0 &&
        _auth.Pending.Count == 0 &&
        _pcView == null && _phoneView == null && _fileExplorers.Count == 0 &&
        !CaptureOfferCard.AnyOpen &&
        !Application.Current.Windows.OfType<Window>().Any(w => w.IsActive);

    /// <summary>Swaps in the downloaded exe, starts it, and exits. False if that
    /// failed, in which case this copy keeps running.</summary>
    private bool InstallUpdate()
    {
        var args = $"{App.RestartSwitch} {App.UpdatedSwitch}" + (IsVisible ? "" : " " + App.MinimizedSwitch);
        try
        {
            _updater.Install(exe => System.Diagnostics.Process.Start(
                new System.Diagnostics.ProcessStartInfo(exe, args) { UseShellExecute = false }));
        }
        catch (Exception ex)
        {
            Toast.Show($"Couldn't install version {_updater.Latest}: {ex.Message}");
            return false;
        }
        ExitApp();
        return true;
    }

    /// <summary>A trusted device asked this PC to update now, e.g. from the
    /// phone that is viewing it. It answers with update-status as it goes.</summary>
    private async Task UpdateForPeerAsync(string peerId)
    {
        void Reply(string state, string? error = null) => _ws.SendJson(new JsonObject
        {
            ["type"] = "update-status",
            ["to"] = peerId,
            ["state"] = state,
            ["version"] = state == "current" ? Updater.RunningVersion : _updater.Latest,
            ["error"] = error,
        });

        if (_updater.Latest == null)
        {
            Reply("failed", "The relay hasn't said which version is newest yet.");
            return;
        }
        if (!_updater.UpdateAvailable)
        {
            Reply("current");
            return;
        }
        const string privacyOn = "Its privacy screen is on, and restarting would uncover the screen. Turn privacy off, then update.";
        if (_privacy.IsEnabled)
        {
            Reply("failed", privacyOn);
            return;
        }
        Reply("updating");
        Toast.Show($"{PeerName(peerId)} asked this PC to update to version {_updater.Latest}");
        if (!await _updater.PrepareAsync(retryFailed: true))
        {
            Reply("failed", _updater.Error ?? "The download failed.");
            return;
        }
        if (_privacy.IsEnabled) Reply("failed", privacyOn); // turned on during the download
        else if (!InstallUpdate()) Reply("failed", _updater.Error ?? "Installing it failed.");
    }

    /// <summary>How a PC this one asked to update is getting on.</summary>
    private void OnPeerUpdateStatus(string peerId, JsonNode msg)
    {
        var name = PeerName(peerId);
        var version = ClipboardService.Str(msg["version"]);
        switch (ClipboardService.Str(msg["state"]))
        {
            case "updating":
                Toast.Show($"{name} is updating to version {version}. It reconnects when it restarts.");
                break;
            case "current":
                Toast.Show($"{name} is already up to date");
                break;
            case "failed":
                Toast.Show($"{name} couldn't update: {ClipboardService.Str(msg["error"])}", 8000);
                break;
        }
    }

    // ---------- elevation & run at login ----------

    // Windows UIPI silently discards SendInput into higher-integrity windows
    // (Task Manager auto-elevates, so does any UAC-elevated app). Running this
    // app elevated is the supported way to control those remotely.

    internal static bool IsElevated()
    {
        using var id = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(id)
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }

    private void Elevate_Click(object sender, RoutedEventArgs e)
    {
        // The elevated copy may not inherit the profile, and would then start as the installed app.
        if (AppProfile.Name != null) return;
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(Environment.ProcessPath!)
            {
                UseShellExecute = true,
                Verb = "runas",
                Arguments = App.RestartSwitch,
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
        if (!_settingsInitialized) return;
        _settings.StartWithWindows = StartupCheck.IsChecked == true;
        _settings.Save();
        ApplyStartupRegistration();
        ApplySectionVisibility();
    }

    private void Consent_Changed(object sender, RoutedEventArgs e)
    {
        if (!_settingsInitialized) return;
        _settings.AllowRemoteControl = AllowStreamCheck.IsChecked == true;
        if (!_settings.AllowRemoteControl) _privacy.Disable();
        _settings.AllowFileAccess = AllowFilesCheck.IsChecked == true;
        _settings.Save();
        foreach (var peer in _peers) SendFileAccess(peer.Id);
        foreach (var peer in _peers) SendPrivacyState(peer.Id);
        ApplySectionVisibility();
    }

    private void SendFileAccess(string peerId)
    {
        if (!_auth.IsTrusted(peerId)) return;
        _ws.SendJson(new JsonObject
        {
            ["type"] = "file-access",
            ["to"] = peerId,
            ["allowed"] = _settings.AllowFileAccess && _auth.AllPeersTrusted,
        });
    }

    /// <summary>
    /// Register (or remove) launch-at-login matching the current elevation:
    /// a plain HKCU Run entry when not elevated, a highest-run-level scheduled
    /// task when elevated (the Run key cannot start elevated programs). Either
    /// way the app is launched with --minimized so a login doesn't pop the
    /// window open — it waits in the tray.
    /// </summary>
    private void ApplyStartupRegistration()
    {
        // The startup entry has one name, and it belongs to the installed app.
        if (AppProfile.Name != null) return;
        var exe = Environment.ProcessPath!;
        try
        {
            using var run = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Run");
            run.DeleteValue("RemoteDesktopWin", throwOnMissingValue: false);
            RunSchtasks("/Delete /F /TN RemoteDesktopWin"); // stale task from an elevated install

            if (!_settings.StartWithWindows) return;
            if (IsElevated())
                RunSchtasks($"/Create /F /TN RemoteDesktopWin /SC ONLOGON /RL HIGHEST /TR \"\\\"{exe}\\\" {App.MinimizedSwitch}\"");
            else
                run.SetValue("RemoteDesktopWin", '"' + exe + "\" " + App.MinimizedSwitch);
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
        // Posted from the socket thread, so an exception here would end the app.
        try { ApplyState(state); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Ignored a state change that failed: {ex}"); }
    }

    private void ApplyState(string state)
    {
        _lastState = state;
        if (state != "connected")
        {
            _peers.Clear();
            _auth.ResetConnection(); // peer ids are stale after a drop
            CaptureOfferCard.PeerLeft(null);
            // After the cards, which cancel their own downloads.
            _fs.PeerLeft(null, RelayLostError(state));
            RefreshDeviceList();
        }
        UpdateStatusCard();
    }

    /// <summary>Why transfers failed, with this PC's own reason for the drop when it has
    /// one: the app keeps no log, and this is the clue to a connection that keeps dropping.</summary>
    private static string RelayLostError(string state)
    {
        const string lost = "The connection to the relay was lost";
        const string prefix = "disconnected: ";
        if (!state.StartsWith(prefix)) return lost;
        var why = state[prefix.Length..];
        var retry = why.IndexOf(" — retrying", StringComparison.Ordinal);
        return $"{lost} ({(retry >= 0 ? why[..retry] : why).TrimEnd('.')})";
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
    /// essentials: the devices list and any incomplete permissions, with the
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
        // Also when this PC can't update itself, so the Download button is in reach.
        VersionCard.Visibility = extras || UpdateNeedsYou ? Visibility.Visible : Visibility.Collapsed;

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
        AccessCard.Visibility = extras || granted < 4 ? Visibility.Visible : Visibility.Collapsed;

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

    private void OnJsonReceived(JsonNode msg)
    {
        // On the socket thread nothing may throw: a type that is not text is no type.
        var type = msg["type"] is JsonValue value && value.TryGetValue<string>(out var text) ? text : null;
        // What a phone says about its stream is read here, on the socket
        // thread, where it is still in order with the frames behind it. The
        // window hears the rest of the message later, like any other.
        if (type == "screen-info") _phoneFeed.OnScreenInfo(msg);
        // And its answer to a ping is timed here, not when the window gets to it.
        else if (type == "diagnostic-pong") _phoneFeed.OnPong(msg);
        // fs-begin is the exception: it opens the file that the chunks behind
        // it are written to, and chunks are written on the socket thread.
        if (type == "fs-begin") Dispatcher.Invoke(() => OnJson(msg));
        else Dispatcher.BeginInvoke(() => OnJson(msg));
    }

    /// <summary>The phone feed's thread: turns the newest picture into a
    /// bitmap there, and leaves it for the window to show when it next can.</summary>
    private void DecodePhonePicture(PhonePicture picture)
    {
        if (_phoneView is not { } view || view.Decode(picture.Data, picture.Offset, picture.Length) is not { } decoded) return;
        lock (_phonePictureLock)
        {
            _phonePicture = (view, decoded, picture.Header);
            if (_phoneDrawPosted) return;
            _phoneDrawPosted = true;
        }
        // Drawn below input and rendering, so a stream the PC cannot keep up
        // with costs pictures rather than a frozen window.
        Dispatcher.BeginInvoke(DispatcherPriority.Background, ShowPhonePicture);
    }

    private void ShowPhonePicture()
    {
        (PhoneViewWindow View, PhoneViewWindow.DecodedPicture Picture, PhoneFrameHeader? Header)? waiting;
        lock (_phonePictureLock)
        {
            waiting = _phonePicture;
            _phonePicture = null;
            _phoneDrawPosted = false;
        }
        // Not into a window that has been closed, or opened on another phone, meanwhile.
        if (waiting is not { } latest || !ReferenceEquals(latest.View, _phoneView)) return;
        latest.View.ShowPicture(latest.Picture);
        // For the readout: this is the moment the picture's age is counted to.
        _phoneFeed.Drawn(latest.Header);
    }

    private void OnJson(JsonNode msg)
    {
        // Posted from the socket thread: an exception here would end the app,
        // and under Invoke one malformed message would drop the relay connection.
        try { HandleJson(msg); }
        catch (Exception ex) { System.Diagnostics.Debug.WriteLine($"Ignored a message that failed: {ex}"); }
    }

    private void HandleJson(JsonNode msg)
    {
        var from = msg["from"]?.GetValue<string>();
        // Host-side consent: the toggle must be on AND the sender must be an
        // approved device (see PROTOCOL.md, "Device authentication").
        bool controlAllowed = AllowStreamCheck.IsChecked == true && _auth.IsTrusted(from);

        var type = msg["type"]?.GetValue<string>();
        // Only the relay says who is in the session, and its own messages
        // have no sender. From a device, these are a device posing as the
        // relay, whether or not it encrypted them.
        if (from != null && type is "welcome" or "peer-joined" or "peer-left" or "error" or "latest") return;

        switch (type)
        {
            case "welcome":
                _peers.Clear();
                foreach (var p in msg["peers"]!.AsArray())
                    _peers.Add(Peer.From(p!));
                foreach (var p in _peers)
                    _auth.OnPeerJoined(p.Id, p.Name, p.Device);
                RefreshDeviceList();
                OnLatestRelease(ClipboardService.Str(msg["latest"]));
                break;

            case "latest":
                // Only the relay's own word counts; a peer's message carries "from".
                if (from == null) OnLatestRelease(ClipboardService.Str(msg["version"]));
                break;

            case "update-now":
                // Another device saw this PC is behind and asked it to update now.
                if (_auth.IsTrusted(from) && from != null) _ = UpdateForPeerAsync(from);
                break;

            case "update-status":
                if (from != null) OnPeerUpdateStatus(from, msg);
                break;

            case "peer-joined":
                var joined = Peer.From(msg["peer"]!);
                _peers.Add(joined);
                _auth.OnPeerJoined(joined.Id, joined.Name, joined.Device);
                RefreshDeviceList();
                Toast.Show($"{joined.Name} ({joined.Device}) is online");
                break;

            case "peer-left":
                var leftId = msg["id"]?.GetValue<string>();
                var leftName = PeerName(leftId);
                _peers.RemoveAll(p => p.Id == leftId);
                if (leftId != null)
                {
                    _auth.OnPeerLeft(leftId);
                    // Drop their viewport, or the stream stays cropped to a
                    // region nobody is looking at any more.
                    _streamer.Regions.RemoveViewer(leftId);
                    CaptureOfferCard.PeerLeft(leftId);
                    _fs.PeerLeft(leftId, $"{leftName} went offline");
                }
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

            case "get-file-access":
                if (from != null) SendFileAccess(from);
                break;

            case "diagnostic-ping":
                if (_auth.IsTrusted(from) && from != null)
                {
                    _ws.SendJson(new JsonObject
                    {
                        ["type"] = "diagnostic-pong",
                        ["nonce"] = msg["nonce"]?.GetValue<long>() ?? -1,
                        ["hostQueueBytes"] = _ws.QueuedBinaryBytes,
                        // Report what the stream is actually doing after
                        // adaptation, not the configured ceiling — the whole
                        // point of the panel is to show where it settled.
                        ["targetFps"] = _streamer.ActiveFps,
                        ["jpegQuality"] = _streamer.ActiveQuality,
                        ["maxWidth"] = _streamer.ActiveWidth,
                        ["codec"] = _streamer.ActiveCodec,
                        ["to"] = from,
                    });
                }
                break;

            case "start-view":
                if (controlAllowed)
                {
                    if (from != null)
                    {
                        _streamer.Regions.AddViewer(from);
                        if (msg["autoUnprivacy"] is JsonValue option &&
                            option.TryGetValue<bool>(out var restore) && restore)
                            _autoUnprivacyViewers.Add(from);
                        else _autoUnprivacyViewers.Remove(from);
                    }
                    _streamer.Start();
                    _streamer.RequestKeyframe(); // joining mid-stream needs a full frame
                    if (from != null) SendPrivacyState(from);
                    Toast.Show($"{PeerName(from)} started viewing this screen");
                }
                break;

            case "view-region":
                // The viewer is telling us which part of the desktop it can
                // actually show, so we stop encoding the rest.
                if (_auth.IsTrusted(from) && from != null)
                {
                    _streamer.Regions.SetRegion(from, new ViewRegion(
                        msg["x"]?.GetValue<double>() ?? 0,
                        msg["y"]?.GetValue<double>() ?? 0,
                        msg["w"]?.GetValue<double>() ?? 1,
                        msg["h"]?.GetValue<double>() ?? 1,
                        msg["outW"]?.GetValue<int>() ?? 0,
                        msg["outH"]?.GetValue<int>() ?? 0));
                    _streamer.Regions.SetH264(from, msg["h264"]?.GetValue<bool>() ?? false);
                }
                break;

            case "request-keyframe":
                if (_auth.IsTrusted(from)) _streamer.RequestKeyframe();
                break;

            case "stop-view":
                if (!_auth.IsTrusted(from)) break;
                if (from != null) _streamer.Regions.RemoveViewer(from);
                _streamer.Stop();
                Toast.Show($"{PeerName(from)} stopped viewing this screen");
                break;

            case "get-privacy-state":
                if (from != null) SendPrivacyState(from);
                break;

            case "privacy-mode":
                if (!controlAllowed || from == null) break;
                if (msg["enabled"] is not JsonValue value || !value.TryGetValue<bool>(out var enabled)) break;
                try
                {
                    if (enabled)
                    {
                        if (!_auth.AllPeersTrusted || !_streamer.IsStreaming || !_streamer.Regions.IsViewing(from))
                        {
                            SendPrivacyState(from, "Start viewing the desktop before enabling privacy.");
                            break;
                        }
                        _privacy.Enable();
                    }
                    else _privacy.Disable();
                    SendPrivacyState(from);
                }
                catch (Exception ex) { SendPrivacyState(from, ex.Message); }
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

            // Clipboard requests need the control switch, which the service
            // checks when each one's turn comes and answers with an error.
            // Unapproved peers get no answer at all.
            case "clip-get":
                if (_auth.IsTrusted(from) && from != null)
                    _ = _clip.HandleGetAsync(from, ClipboardService.Str(msg["reqId"]),
                        press: ClipboardService.Flag(msg["press"], true));
                break;

            case "clip-set":
                if (_auth.IsTrusted(from) && from != null)
                    _ = _clip.HandleSetAsync(msg, from, PeerName(from));
                break;

            case "clip-get-result":
                if (_pcView != null && _pcView.PeerId == from) _pcView.OnClipResult(msg);
                break;
            case "clip-set-result":
                if (_pcView != null && _pcView.PeerId == from) _pcView.OnPasteResult(msg);
                break;

            case "screen-info":
                var siW = msg["width"]?.GetValue<int>() ?? 0;
                var siH = msg["height"]?.GetValue<int>() ?? 0;
                if (_phoneView != null && _phoneView.PeerId == from)
                    _phoneView.OnScreenInfo(siW, siH, msg["typing"]?.GetValue<bool>() ?? false);
                if (_pcView != null && _pcView.PeerId == from) _pcView.OnScreenInfo(siW, siH);
                break;

            case "phone-state":
                // The phone being viewed says what its menu can offer, and its volume.
                if (_phoneView != null && _phoneView.PeerId == from && msg is JsonObject phoneState)
                    _phoneView.OnPhoneState(phoneState);
                break;

            case "lock-state":
                // The phone being viewed says whether its screen is on and whether it is locked.
                if (_phoneView != null && _phoneView.PeerId == from && msg is JsonObject lockState)
                    _phoneView.OnLockState(lockState);
                break;

            case "fs-list":
            case "fs-get":
            case "fs-begin":
            case "fs-end":
            case "fs-cancel":
                // File chunks are broadcast like video, so serving also requires
                // that no unapproved device is present in the session.
                _fs.HandleJson(msg, peerAccessAllowed:
                    AllowFilesCheck.IsChecked == true && _auth.IsTrusted(from) && _auth.AllPeersTrusted);
                break;

            case "fs-offer":
                // A phone just took a photo, video or screenshot. Only an
                // approved device may put a card on this screen.
                if (_auth.IsTrusted(from) && from != null &&
                    CaptureOffer.Parse(_ws, _fs, from, PeerName(from), msg, a => Dispatcher.BeginInvoke(a)) is { } offer)
                    CaptureOfferCard.Show(offer);
                break;

            case "fs-list-result":
                if (from != null && _fileExplorers.TryGetValue(from, out var listed)) listed.OnListResult(msg);
                break;
            case "fs-saved":
                // The answer to a file this PC sent, which an upload waits for.
                _fs.HandleJson(msg, peerAccessAllowed: false);
                if (from != null && _fileExplorers.TryGetValue(from, out var saved)) saved.OnUploadResult(msg);
                break;
        }
    }

    private void SendPrivacyState(string peerId, string? error = null)
    {
        if (!_auth.IsTrusted(peerId)) return;
        _ws.SendJson(new JsonObject
        {
            ["type"] = "privacy-state",
            ["to"] = peerId,
            ["enabled"] = _privacy.IsEnabled,
            ["supported"] = OperatingSystem.IsWindowsVersionAtLeast(10, 0, 19041),
            ["allowed"] = _settings.AllowRemoteControl && _auth.AllPeersTrusted,
            ["error"] = error,
        });
    }

    private void RestorePrivacyForEndedViewers()
    {
        if (_autoUnprivacyViewers.RemoveWhere(id =>
                !_auth.IsTrusted(id) || !_streamer.Regions.IsViewing(id)) > 0)
            _privacy.Disable();
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
                if (from != null) _streamer.Regions.RemoveViewer(from);
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
            case 1: // full-frame JPEG (phone host on an older app, or with an older viewer beside this one)
            case 5: // the same behind a header naming its stream (phone host)
                _phoneFeed.OnFrame(data);
                break;
            case 2: // file chunk
                _fs.HandleFileChunk(data);
                break;
            case 3: // dirty-rect screen patch (Windows host)
                _pcPatches.Post(data);
                break;
            // No case 4: this viewer does not decode H.264, so it never sets
            // h264 on its view-region, and a host will not put a session on
            // that path unless every viewer in it has.
        }
    }

    /// <summary>Rebuild the devices list: online peers (with their trust state)
    /// plus trusted devices that are currently offline. Each row carries its
    /// own action buttons (approve/deny, view/files, disconnect/revoke).</summary>
    private void RefreshDeviceList()
    {
        foreach (var peer in _peers) SendFileAccess(peer.Id);
        var good = (System.Windows.Media.Brush)FindResource("RdGoodBrush");
        var warn = (System.Windows.Media.Brush)FindResource("RdWarnBrush");
        var offline = (System.Windows.Media.Brush)FindResource("RdOfflineBrush");

        var rows = new List<DeviceRow>();
        foreach (var p in _peers)
        {
            var fp = _auth.FingerprintOf(p.Id);
            var trusted = _auth.IsTrusted(p.Id);
            var pending = _auth.Pending.Any(r => r.PeerId == p.Id);
            // The green dot already says "online", so the text only adds what
            // the dot can't: the code, and why a device isn't approved yet.
            var status =
                trusted ? "" :
                pending ? "waiting for your approval" :
                fp != null ? "not approved" :
                "Verifying…";
            var code = fp == null ? "" : $"Code {DeviceIdentity.ShortCode(fp)}";
            var subtitle = code.Length > 0 && status.Length > 0 ? $"{code} — {status}" : code + status;
            rows.Add(new DeviceRow
            {
                PeerId = p.Id,
                Fingerprint = fp,
                Name = p.Name,
                Title = $"{p.Name} ({p.Device})",
                Subtitle = subtitle,
                VersionText = p.Version == null ? "" : $" · v{p.Version.TrimStart('v')}",
                Dot = trusted ? good : warn,
                DotTip = trusted ? "Online" : "Online, not approved",
                ShowApprove = pending,
                ShowView = true,
                ShowDisconnect = trusted,
                ShowRevoke = fp != null && _auth.TrustedDevices.Any(d => d.Fingerprint == fp),
                // Only PCs can update themselves; a phone's user installs the APK.
                UpdateTo = trusted && p.Device == "windows" && p.Version != null && _updater.Latest is { } latest &&
                           Updater.CompareVersions(latest, p.Version) > 0 ? latest : null,
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
                    DotTip = "Offline",
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
        if (row.UpdateTo is { } version && row.PeerId is { } peerId)
        {
            var item = new MenuItem
            {
                Header = $"Update to version {version}",
                Style = (Style)FindResource("RdMenuItem"),
                ToolTip = "That PC downloads the new version and restarts the app (its viewers reconnect)",
            };
            item.Click += (_, _) =>
            {
                _ws.SendJson(new JsonObject { ["type"] = "update-now", ["to"] = peerId });
                Toast.Show($"Asked {row.Name} to update");
            };
            menu.Items.Add(item);
        }
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
            var view = new PhoneViewWindow(_ws, phone.Id, phone.Name, _phoneFeed, () => _auth.IsTrusted(phone.Id));
            _phoneView = view;
            view.Closed += (_, _) => _phoneView = null;
            view.Show();
        }
        else
        {
            _phoneView?.Activate();
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
        if (_fileExplorers.TryGetValue(peer.Id, out var open))
        {
            open.Activate();
            return;
        }
        var explorer = new FileExplorerWindow(_ws, _fs, peer.Id, peer.Name, _settings, ShowTransfers,
            _downloadsOpener.Saved);
        _fileExplorers[peer.Id] = explorer;
        explorer.Closed += (_, _) => _fileExplorers.Remove(peer.Id);
        explorer.Show();
    }

    private void Transfers_Click(object sender, RoutedEventArgs e) => ShowTransfers();

    private void ShowTransfers()
    {
        if (_transfersWindow == null)
        {
            _transfersWindow = new TransfersWindow(_transfers);
            _transfersWindow.Closed += (_, _) => _transfersWindow = null;
            _transfersWindow.Show();
        }
        else
        {
            if (_transfersWindow.WindowState == WindowState.Minimized) _transfersWindow.WindowState = WindowState.Normal;
            _transfersWindow.Activate();
        }
    }
}
