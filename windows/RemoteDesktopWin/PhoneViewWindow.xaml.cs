using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Displays the phone's streamed screen and forwards mouse input to it:
///  - quick click                -> tap
///  - press and hold             -> long-press (touch held down)
///  - press and drag             -> real drag (streamed touch moves)
///  - mouse wheel                -> pinch zoom in/out at the cursor
///  - right-click                -> Back
///  - middle-click               -> Home
/// The "⋯" button in the title bar opens a menu with Back, Home, Recents and
/// Notifications, for a phone that uses gestures; clicking the phone's own
/// buttons in the picture works too. The menu opens when the pointer rests on
/// the button and closes when it leaves (see <see cref="MenuHover"/>). A phone
/// that says it can (<c>phone-state</c>) also gets its media volume, which
/// way its screen is held, Quick settings, Screenshot and Lock screen there
/// (see <see cref="MenuRows"/>).
///
/// A phone that is asleep, or on the unlock screen Android leaves out of the
/// picture, gets a line of words over the picture saying so (see
/// <see cref="LockCaption"/>). Clicks pass through it. When the phone says
/// where its pattern or PIN is entered, the dots or the keys are drawn there
/// (see <see cref="LockOverlay"/>): the mouse draws the pattern as it would
/// on any screen, and digits typed on the keyboard become taps on the keys.
///
/// While it is focused, the keyboard types into the phone's focused text
/// field: characters as <c>text</c>, composed here with this PC's layout, and
/// editing keys and Ctrl shortcuts as <c>key</c>. Ctrl+V pastes this PC's
/// clipboard rather than the phone's, and Esc is Back. Alt combinations stay
/// with Windows. Nothing in the title bar or the menu takes the focus, so
/// using them never gets in the way of typing.
/// </summary>
public partial class PhoneViewWindow : Window
{
    /// <summary>A picture ready to be shown, and what was seen in it:
    /// whether it is nearly all black, if it was looked at, and how many
    /// times the phone's screen or lock had changed by then.</summary>
    internal sealed record DecodedPicture(BitmapSource Image, bool? Black, int AsOf);

    private readonly WsClient _ws;
    private readonly string _phoneId;

    // Where this phone's frames come in, and whether this PC has approved
    // the phone. Only an approved phone is offered type 5 frames, since the
    // feed then takes its word for which stream is its own. Both are null in
    // a window made without them, which is offered nothing.
    private readonly PhoneFeed? _feed;
    private readonly Func<bool>? _approved;
    private bool _offered;              // the last start-view offered codecs

    public string PeerId => _phoneId;

    private Point? _downPos;
    private DateTime _downTime;
    private bool _touching;             // a touch-down has been sent to the phone
    private (double x, double y)? _lastTouch;   // where the phone's finger was last put or moved
    private DateTime _lastMoveSent;
    private readonly DispatcherTimer _holdTimer;

    // What the phone's screen-info said about taking typed text; null until
    // the first one. Older apps and phones before Android 13 say false.
    private bool? _phoneTypes;
    private bool _warnedNoTyping;

    private readonly WindowCaption _caption;
    private readonly MenuHover _menu = new();
    private readonly DispatcherTimer _menuTimer;
    private bool _escClosedMenu;        // Esc is still held from closing the menu

    // What the phone last said about itself; null until it does, and for
    // good with a phone app too old to say.
    private PhoneState? _phoneState;
    private DateTime? _viewingSince;    // when the phone first sent a picture's size
    private readonly DispatcherTimer _updateLineTimer;
    private readonly VolumeSender _volume = new();
    private readonly AskPace _asks = new();
    private readonly DispatcherTimer _volumeTimer;
    private bool _showingPhoneVolume;   // the slider is being moved to what the phone said
    private int _lastAudible;           // the last level above 0 the phone had or was sent; 0 if none yet
    private bool _showingPhoneRotation; // the rotation choices are being lit as the phone said
    private readonly RotationCheck _rotation = new();
    private readonly DispatcherTimer _rotationTimer;

    // What the phone last said in lock-state; null until it does, and for
    // good with a phone app too old to say. Written here, on the window's
    // thread, and read where pictures are decoded.
    private volatile LockInfo? _lock;
    // The black test runs where pictures are decoded, which is not the
    // window's thread, so the watch and the pixels are used under its lock.
    private readonly BlackWatch _blackWatch = new();
    private byte[] _pixels = [];        // the picture last looked at, kept to save allocating it again
    private bool _pictureBlack;         // whether the picture on show is nearly all black
    private int _lockChanges;           // goes up each time the phone's screen or lock changes

    // The drag being made over the pattern grid, as fractions of the phone's
    // screen, and the dots it has passed over. Drawn, never sent or kept:
    // both are emptied when the button comes up.
    private readonly List<Point> _trail = [];
    private readonly HashSet<int> _trailDots = [];

    private static readonly Brush OverlayBrush = Frozen(Color.FromArgb(0xE6, 0xFF, 0xFF, 0xFF));
    private static readonly Brush OverlayFaintBrush = Frozen(Color.FromArgb(0x73, 0xFF, 0xFF, 0xFF));

    // The size of the phone's picture, and the one the window was last sized
    // round. They differ while the window is maximised or minimised.
    private (int W, int H)? _picture;
    private (int W, int H)? _fitted;
    private double _fittedLongSide;     // the picture's long side as that fit drew it

    private static readonly Brush MenuOpenBrush = Frozen(Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF));

    private const double DragSlopPx = 6;
    private static readonly TimeSpan HoldDelay = TimeSpan.FromMilliseconds(450);
    private static readonly TimeSpan MoveInterval = TimeSpan.FromMilliseconds(30);

    /// <summary>A window on a phone that is offered nothing but the bare
    /// JPEGs every app sends.</summary>
    public PhoneViewWindow(WsClient ws, string phoneId, string name) : this(ws, phoneId, name, null, null) { }

    /// <param name="feed">Where this phone's frames come in.</param>
    /// <param name="approved">Whether this PC has approved the phone, asked
    /// each time the phone is asked for its screen.</param>
    internal PhoneViewWindow(WsClient ws, string phoneId, string name, PhoneFeed? feed, Func<bool>? approved)
    {
        InitializeComponent();
        _ws = ws;
        _phoneId = phoneId;
        _feed = feed;
        _approved = approved;
        Title = name;

        _caption = new WindowCaption(this);
        _caption.Changed += LayOutTitleBar;
        LayOutTitleBar();

        _menuTimer = new DispatcherTimer(DispatcherPriority.Input);
        _menuTimer.Tick += (_, _) => { _menu.Tick(DateTime.UtcNow); ShowMenuState(); };
        MenuPopup.CustomPopupPlacementCallback = PlaceMenu;
        // Resting on the button counts only while this is the active window.
        _menu.WindowActive(IsActive, DateTime.UtcNow);
        Activated += (_, _) => { _menu.WindowActive(true, DateTime.UtcNow); ShowMenuState(); };
        // The menu doesn't follow the window, so it goes when the window
        // moves, changes size or is left for another.
        Deactivated += (_, _) => { _menu.WindowActive(false, DateTime.UtcNow); ShowMenuState(); };
        LocationChanged += (_, _) => CloseMenu();
        SizeChanged += (_, _) => CloseMenu();
        StateChanged += (_, _) =>
        {
            CloseMenu();
            // Back from maximised or minimised: catch up with a phone that
            // turned meanwhile, once the window has its restored size.
            Dispatcher.BeginInvoke(DispatcherPriority.Loaded, FitToPicture);
        };
        Closed += (_, _) => CloseMenu();

        _updateLineTimer = new DispatcherTimer();
        _updateLineTimer.Tick += (_, _) => ShowMenuRows();
        _volumeTimer = new DispatcherTimer(DispatcherPriority.Input);
        _volumeTimer.Tick += (_, _) => SendVolume(_volume.Tick(DateTime.UtcNow));
        _rotationTimer = new DispatcherTimer();
        _rotationTimer.Tick += (_, _) =>
        {
            _rotationTimer.Stop();
            if (_rotation.Tick(DateTime.UtcNow))
                Toast.Show("The phone didn't rotate. The app on screen may not allow it.");
        };
        Closed += (_, _) => { _updateLineTimer.Stop(); _volumeTimer.Stop(); _rotationTimer.Stop(); };

        SendStartView();
        AskPhoneState(always: true);
        Closed += (_, _) =>
        {
            _feed?.Stop(_phoneId);
            _ws.SendJson(new JsonObject { ["type"] = "stop-view", ["to"] = _phoneId });
        };

        // Holding the button without moving becomes a long-press on the phone.
        _holdTimer = new DispatcherTimer { Interval = HoldDelay };
        _holdTimer.Tick += (_, _) =>
        {
            _holdTimer.Stop();
            if (_downPos is { } p && !_touching) BeginTouch(p);
        };
        Loaded += (_, _) => { Focus(); Keyboard.Focus(this); FitToPicture(); };
        // What is drawn over the picture is placed by the picture's size.
        ScreenImage.SizeChanged += (_, _) => DrawUnlockScreen();
        PictureHost.SizeChanged += (_, _) => DrawUnlockScreen();
    }

    private static Brush Frozen(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private void SendAction(string type) =>
        _ws.SendJson(new JsonObject { ["type"] = type, ["to"] = _phoneId });

    // ---------------- title bar and menu ----------------

    private void LayOutTitleBar()
    {
        BarRow.Height = new GridLength(_caption.BarHeight);
        SystemButtonsColumn.Width = new GridLength(_caption.ButtonsWidth);
    }

    /// <summary>Under the button, right edges in line. The sizes come in
    /// screen pixels, and the popup's is the card without the margin that
    /// holds its shadow; the point is where the margin starts.</summary>
    private CustomPopupPlacement[] PlaceMenu(Size popup, Size target, Point offset)
    {
        double scale = VisualTreeHelper.GetDpi(this).DpiScaleX;
        double x = target.Width - popup.Width - MenuCard.Margin.Left * scale;
        return [new CustomPopupPlacement(new Point(x, target.Height), PopupPrimaryAxis.Horizontal)];
    }

    /// <summary>Makes the popup and the timer match what <see cref="_menu"/> has decided.</summary>
    private void ShowMenuState()
    {
        if (MenuPopup.IsOpen != _menu.IsOpen)
        {
            if (_menu.IsOpen)
            {
                // The volume may have been changed on the phone since.
                AskPhoneState();
                ShowMenuRows();
                MenuButton.Background = MenuOpenBrush;
            }
            else
            {
                MenuButton.ClearValue(BackgroundProperty);
                // A drag the menu closed under still sends where it had got to.
                if (_volume.Dragging) EndVolumeDrag();
            }
            MenuPopup.IsOpen = _menu.IsOpen;
        }
        _menuTimer.Stop();
        if (_menu.NextDue is { } due)
        {
            var wait = due - DateTime.UtcNow;
            _menuTimer.Interval = wait > TimeSpan.Zero ? wait : TimeSpan.Zero;
            _menuTimer.Start();
        }
    }

    private void CloseMenu()
    {
        _menu.Dismiss();
        ShowMenuState();
    }

    private void MenuButton_Click(object sender, RoutedEventArgs e)
    {
        _menu.Click(DateTime.UtcNow);
        ShowMenuState();
    }

    private void MenuButton_MouseEnter(object sender, MouseEventArgs e)
    {
        // In a background window this opens nothing until the window is the
        // active one (see MenuHover.WindowActive). A click still opens it.
        _menu.PointerOnButton(true, DateTime.UtcNow);
        ShowMenuState();
    }

    private void MenuButton_MouseLeave(object sender, MouseEventArgs e)
    {
        _menu.PointerOnButton(false, DateTime.UtcNow);
        ShowMenuState();
    }

    private void MenuCard_MouseEnter(object sender, MouseEventArgs e)
    {
        _menu.PointerOnMenu(true, DateTime.UtcNow);
        ShowMenuState();
    }

    private void MenuCard_MouseLeave(object sender, MouseEventArgs e)
    {
        _menu.PointerOnMenu(false, DateTime.UtcNow);
        ShowMenuState();
    }

    /// <summary>A row of the menu whose Tag is the message to send. The menu
    /// stays open, so the row can be pressed again.</summary>
    private void MenuAction_Click(object sender, RoutedEventArgs e)
    {
        if ((sender as FrameworkElement)?.Tag is string type) SendAction(type);
    }

    /// <summary>Ask for the stream again — the first start-view is dropped when
    /// the phone hasn't approved this device yet.</summary>
    public void ResendStartView()
    {
        SendStartView();
        AskPhoneState(always: true);
    }

    /// <summary>Every start-view goes from here, so each one says what this
    /// viewer can draw: the phone takes the latest as the whole of it.</summary>
    private void SendStartView()
    {
        _offered = _feed != null && _approved?.Invoke() == true;
        // The feed is told first: the phone's answer is read on another thread.
        _feed?.View(_phoneId, _offered);
        _ws.SendJson(PhoneFeed.StartView(_phoneId, _offered));
    }

    /// <summary>This PC has approved a device, or no longer does. If that
    /// changes what this phone may be offered, it is asked again.</summary>
    public void TrustChanged()
    {
        if (_feed != null && (_approved?.Invoke() == true) != _offered) SendStartView();
    }

    // ---------------- what the phone can do, and its volume ----------------

    /// <summary>Asked when the window opens and when the menu does, though
    /// not more than once a second however often it opens. A phone app from
    /// before the menu never answers.</summary>
    private void AskPhoneState(bool always = false)
    {
        var now = DateTime.UtcNow;
        if (!_asks.Ask(now, always)) return;
        // Its answer is counted with the slider's, so they can be told apart.
        _volume.Sent(now);
        SendAction("get-phone-state");
    }

    public void OnPhoneState(JsonObject msg)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (PhoneState.Parse(msg) is not { } state) return;
            // What the phone can do and how its screen is held always apply.
            // Its volume doesn't when it is an answer the slider has since
            // moved on from.
            bool volumeToo = _volume.Heard(DateTime.UtcNow) || _phoneState == null;
            _phoneState = volumeToo ? state : state.WithVolumeOf(_phoneState!);
            ShowMenuRows();
            if (!volumeToo) return;
            if (state.Volume > 0) _lastAudible = state.Volume;
            ShowVolume();
        });
    }

    /// <summary>Shows the rows the phone can act on and how its screen is
    /// held, or the line saying to update a phone that has said nothing.</summary>
    private void ShowMenuRows()
    {
        _updateLineTimer.Stop();
        var now = DateTime.UtcNow;
        var rows = MenuRows.For(_phoneState, _viewingSince, now);
        if (!rows.UpdateLine && MenuRows.UpdateLineDue(_phoneState, _viewingSince) is { } due)
        {
            _updateLineTimer.Interval = due > now ? due - now : TimeSpan.Zero;
            _updateLineTimer.Start();
        }

        static Visibility Shown(bool show) => show ? Visibility.Visible : Visibility.Collapsed;
        VolumeRow.Visibility = Shown(rows.Volume);
        RotationRow.Visibility = Shown(rows.Rotation);
        ShowRotation();
        QuickSettingsButton.Visibility = Shown(rows.QuickSettings);
        ScreenshotButton.Visibility = Shown(rows.Screenshot);
        LockButton.Visibility = Shown(rows.Lock);
        UpdateLine.Visibility = Shown(rows.UpdateLine);
    }

    /// <summary>Puts the slider and the speaker icon where the phone said.
    /// Only for a state whose volume is taken (see <see cref="OnPhoneState"/>):
    /// at any other time the slider is where the user put it.</summary>
    private void ShowVolume()
    {
        if (_phoneState is not { } state) return;
        _showingPhoneVolume = true;
        VolumeSlider.Maximum = state.VolumeMax;
        VolumeSlider.Value = state.Volume;
        _showingPhoneVolume = false;
        // Segoe MDL2 Assets: a speaker, and a speaker crossed out.
        MuteButton.Content = state.IsSilent ? "\uE74F" : "\uE767";
        MuteButton.ToolTip = state.IsSilent ? "Unmute" : "Mute";
        AutomationProperties.SetName(MuteButton, state.IsSilent ? "Unmute" : "Mute");
    }

    /// <summary>Lights the choice the phone says is in force.</summary>
    private void ShowRotation()
    {
        var held = _phoneState?.Rotation ?? PhoneState.Auto;
        _showingPhoneRotation = true;
        RotateAutoButton.IsChecked = held == PhoneState.Auto;
        RotatePortraitButton.IsChecked = held == PhoneState.Portrait;
        RotateLandscapeButton.IsChecked = held == PhoneState.Landscape;
        _showingPhoneRotation = false;
    }

    /// <summary>A rotation choice was clicked, or pressed through UI
    /// Automation, which toggles it without a click. Either way the phone is
    /// asked, and the choice stays unlit until the phone says it holds.</summary>
    private void RotateButton_Changed(object sender, RoutedEventArgs e)
    {
        if (_showingPhoneRotation || (sender as FrameworkElement)?.Tag is not string orientation) return;
        var now = DateTime.UtcNow;
        _volume.Sent(now);
        _ws.SendJson(new JsonObject { ["type"] = "rotate", ["to"] = _phoneId, ["orientation"] = orientation });
        ShowRotation();

        _rotation.Chose(orientation, _picture?.W ?? 0, _picture?.H ?? 0, now);
        _rotationTimer.Stop();
        if (_rotation.NextDue is { } due)
        {
            _rotationTimer.Interval = due - now;
            _rotationTimer.Start();
        }
    }

    private void MuteButton_Click(object sender, RoutedEventArgs e)
    {
        if (_phoneState is not { } state) return;
        var (mute, level) = state.SpeakerClick(_lastAudible);
        var msg = new JsonObject { ["type"] = "volume", ["to"] = _phoneId };
        if (mute is { } on) msg["mute"] = on;
        if (level is { } to) msg["level"] = to;
        _volume.Sent(DateTime.UtcNow);
        _ws.SendJson(msg);
    }

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_showingPhoneVolume) return;
        SendVolume(_volume.Moved((int)Math.Round(e.NewValue), DateTime.UtcNow));
    }

    private void VolumeSlider_DragStarted(object sender, DragStartedEventArgs e)
    {
        _menu.DragStarted();
        _volume.DragStarted();
        ShowMenuState();
    }

    private void VolumeSlider_DragCompleted(object sender, DragCompletedEventArgs e)
    {
        EndVolumeDrag();
        ShowMenuState();
    }

    private void EndVolumeDrag()
    {
        var now = DateTime.UtcNow;
        _menu.DragEnded(now);
        SendVolume(_volume.DragEnded(now));
    }

    /// <summary>Sends a level the slider has decided on, if any, and sets the
    /// timer for one it is holding back.</summary>
    private void SendVolume(int? level)
    {
        if (level is { } value)
        {
            if (value > 0) _lastAudible = value;
            _ws.SendJson(new JsonObject { ["type"] = "volume", ["to"] = _phoneId, ["level"] = value });
        }
        _volumeTimer.Stop();
        if (_volume.NextDue is { } due)
        {
            var wait = due - DateTime.UtcNow;
            _volumeTimer.Interval = wait > TimeSpan.Zero ? wait : TimeSpan.Zero;
            _volumeTimer.Start();
        }
    }

    // ---------------- asleep, locked, unlock screen ----------------

    public void OnLockState(JsonObject msg)
    {
        Dispatcher.BeginInvoke(() =>
        {
            if (LockInfo.Parse(msg) is not { } state || state == _lock) return;
            bool screenOrLock = !state.SameScreenAndLock(_lock);
            _lock = state;
            // The picture on show was taken before this, and so was any that
            // is decoded and on its way here.
            if (screenOrLock)
            {
                lock (_blackWatch) _blackWatch.Forget();
                _pictureBlack = false;
                _lockChanges++;
            }
            if (state.Entry is not PatternEntry) ClearTrail();
            ShowCaption();
            DrawUnlockScreen();
            // What the phone can do goes with its screen and its lock: it
            // turns its screen for nobody while asleep or locked, and lets
            // go of a turn it held. It doesn't say so by itself, so it is
            // asked. Not for a message line that changes: a lock screen
            // counting down changes it every second.
            if (screenOrLock) AskPhoneState(always: true);
        });
    }

    // ---------------- the unlock screen, drawn ----------------

    /// <summary>The PIN pad the phone is showing, if it is.</summary>
    private PinEntry? CurrentPin => _lock is { ScreenOn: true, Locked: true, Entry: PinEntry pin } ? pin : null;

    /// <summary>Where the picture is drawn inside its host, letterboxed.</summary>
    private Rect? PictureRect()
    {
        if (ScreenImage.Source is not { } picture) return null;
        double iw = picture.Width, ih = picture.Height;
        double cw = ScreenImage.ActualWidth, ch = ScreenImage.ActualHeight;
        if (iw <= 0 || ih <= 0 || cw <= 0 || ch <= 0) return null;
        double scale = Math.Min(cw / iw, ch / ih);
        double dw = iw * scale, dh = ih * scale;
        var origin = ScreenImage.TranslatePoint(new Point((cw - dw) / 2, (ch - dh) / 2), PictureHost);
        return new Rect(origin.X, origin.Y, dw, dh);
    }

    /// <summary>
    /// Draws the pattern's dots or the PIN's keys where the phone says they
    /// are, with the line above them, or clears them away. Everything is
    /// placed by the picture, so it lines up at any window size.
    /// </summary>
    private void DrawUnlockScreen()
    {
        UnlockOverlay.Children.Clear();
        var heading = LockOverlay.Heading(_lock);
        if (heading == null || _lock?.Entry is not { } entry || PictureRect() is not { } picture)
        {
            UnlockOverlay.Visibility = Visibility.Collapsed;
            return;
        }
        UnlockOverlay.Visibility = Visibility.Visible;
        Point At(double x, double y) => new(picture.X + x * picture.Width, picture.Y + y * picture.Height);

        double top;
        if (entry is PatternEntry pattern)
        {
            var area = pattern.Area;
            top = At(0, area.Y).Y;
            double cell = area.Width * picture.Width / 3;
            if (_trail.Count > 1)
            {
                var line = new System.Windows.Shapes.Polyline
                {
                    Stroke = OverlayFaintBrush,
                    StrokeThickness = Math.Max(2, cell * 0.06),
                    StrokeLineJoin = PenLineJoin.Round,
                    StrokeStartLineCap = PenLineCap.Round,
                    StrokeEndLineCap = PenLineCap.Round,
                };
                foreach (var point in _trail) line.Points.Add(At(point.X, point.Y));
                UnlockOverlay.Children.Add(line);
            }
            var dots = LockOverlay.Dots(area);
            for (int i = 0; i < dots.Length; i++)
            {
                bool taken = _trailDots.Contains(i);
                double radius = Math.Max(3, cell * (taken ? 0.11 : 0.06));
                var dot = new System.Windows.Shapes.Ellipse { Width = radius * 2, Height = radius * 2, Fill = OverlayBrush };
                var centre = At(dots[i].X, dots[i].Y);
                System.Windows.Controls.Canvas.SetLeft(dot, centre.X - radius);
                System.Windows.Controls.Canvas.SetTop(dot, centre.Y - radius);
                UnlockOverlay.Children.Add(dot);
            }
        }
        else if (entry is PinEntry pin)
        {
            top = pin.Keys.Min(key => At(0, key.Rect.Y).Y);
            foreach (var key in pin.Keys)
            {
                var corner = At(key.Rect.X, key.Rect.Y);
                double w = key.Rect.Width * picture.Width, h = key.Rect.Height * picture.Height;
                var box = new System.Windows.Controls.Border
                {
                    Width = w,
                    Height = h,
                    CornerRadius = new CornerRadius(Math.Min(w, h) * 0.3),
                    BorderBrush = OverlayFaintBrush,
                    BorderThickness = new Thickness(1),
                    Child = new System.Windows.Controls.TextBlock
                    {
                        // The key that rubs out, as keyboards draw it.
                        Text = key.Label switch { PinEntry.Delete => "\u232B", PinEntry.Ok => "OK", _ => key.Label },
                        FontFamily = new FontFamily("Segoe UI Symbol, Segoe UI"),
                        FontSize = Math.Clamp(Math.Min(w, h) * (key.Label.Length == 1 ? 0.42 : 0.3), 9, 30),
                        Foreground = OverlayBrush,
                        HorizontalAlignment = HorizontalAlignment.Center,
                        VerticalAlignment = VerticalAlignment.Center,
                    },
                };
                System.Windows.Controls.Canvas.SetLeft(box, corner.X);
                System.Windows.Controls.Canvas.SetTop(box, corner.Y);
                UnlockOverlay.Children.Add(box);
            }
        }
        else return;

        // The line above them, as the phone has it above its own.
        double width = Math.Max(40, picture.Width - 24);
        var words = new System.Windows.Controls.TextBlock
        {
            Text = heading,
            Width = width,
            TextWrapping = TextWrapping.Wrap,
            TextAlignment = TextAlignment.Center,
            FontFamily = new FontFamily("Segoe UI"),
            FontSize = 14,
            Foreground = OverlayBrush,
        };
        words.Measure(new Size(width, double.PositiveInfinity));
        System.Windows.Controls.Canvas.SetLeft(words, picture.X + (picture.Width - width) / 2);
        System.Windows.Controls.Canvas.SetTop(words, Math.Max(picture.Y + 4, top - words.DesiredSize.Height - 14));
        UnlockOverlay.Children.Add(words);
    }

    /// <summary>The mouse is down or moving over the picture: remembers
    /// where, while the phone shows its pattern grid, so the drag can be
    /// seen. It is the only sign of it on a black picture.</summary>
    private void TrailTo(Point at)
    {
        if (_lock is not { ScreenOn: true, Locked: true, Entry: PatternEntry pattern } || Normalize(at) is not { } n) return;
        _trail.Add(new Point(n.x, n.y));
        int dot = LockOverlay.DotAt(n.x, n.y, pattern.Area);
        if (dot >= 0) _trailDots.Add(dot);
        DrawUnlockScreen();
    }

    private void ClearTrail()
    {
        if (_trail.Count == 0 && _trailDots.Count == 0) return;
        _trail.Clear();
        _trailDots.Clear();
        DrawUnlockScreen();
    }

    /// <summary>Taps the keys at the points given, in order: digits typed for a PIN.</summary>
    private void TapEach(IEnumerable<(double X, double Y)> points)
    {
        foreach (var (x, y) in points)
        {
            var tap = LockOverlay.PinTapMessage(x, y);
            tap["to"] = _phoneId;
            _ws.SendJson(tap);
        }
    }

    /// <summary>Puts the words over the picture, or takes them away.</summary>
    private void ShowCaption()
    {
        var text = LockCaption.For(_lock, _pictureBlack);
        if (text == null)
        {
            CaptionBox.Visibility = Visibility.Collapsed;
            return;
        }
        bool changed = CaptionBox.Visibility != Visibility.Visible || CaptionText.Text != text;
        CaptionText.Text = text;
        CaptionBox.Visibility = Visibility.Visible;
        // Read out by a screen reader, which can't see that the picture went dark.
        if (changed)
            UIElementAutomationPeer.CreatePeerForElement(CaptionText)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
    }

    /// <summary>
    /// The black test, run where the picture is decoded: twice a second at
    /// most, and only while a black picture would mean something. Any thread.
    /// </summary>
    /// <returns>Whether the picture is nearly all black, or null when this
    /// one was not looked at.</returns>
    private bool? LookAtPicture(BitmapSource picture)
    {
        lock (_blackWatch)
        {
            var now = DateTime.UtcNow;
            if (!_blackWatch.Due(_lock, now)) return null;
            var format = picture.Format;
            bool colour = format == PixelFormats.Bgr32 || format == PixelFormats.Bgra32 || format == PixelFormats.Pbgra32 ||
                format == PixelFormats.Bgr24 || format == PixelFormats.Gray8;
            bool black = false;
            if (colour)
            {
                int bytesPerPixel = format.BitsPerPixel / 8;
                int stride = picture.PixelWidth * bytesPerPixel;
                int size = stride * picture.PixelHeight;
                if (_pixels.Length < size) _pixels = new byte[size];
                picture.CopyPixels(_pixels, stride, 0);
                black = LockOverlay.IsNearlyBlack(_pixels.AsSpan(0, size), picture.PixelWidth, picture.PixelHeight,
                    stride, bytesPerPixel);
            }
            _blackWatch.Looked(black, now);
            return black;
        }
    }

    // ---------------- window size ----------------

    public void OnScreenInfo(int width, int height, bool typing)
    {
        Dispatcher.BeginInvoke(() =>
        {
            _phoneTypes = typing;
            if (_viewingSince == null)
            {
                _viewingSince = DateTime.UtcNow;
                ShowMenuRows();
            }
            if (width <= 0 || height <= 0) return;
            _picture = (width, height);
            _rotation.Picture(width, height);
            FitToPicture();
        });
    }

    /// <summary>
    /// Sizes the window round the picture, so the picture fills it below the
    /// title bar. Only when the picture's shape has changed: the phone repeats
    /// screen-info, and a size the user chose should survive that. A phone
    /// that turns stays the same size on screen (see <see cref="PhoneFit"/>).
    /// </summary>
    private void FitToPicture()
    {
        if (_picture is not { } picture || !IsLoaded) return;
        if (_fitted is { } last && PhoneFit.SameShape(last.W, last.H, picture.W, picture.H)) return;
        // A maximised window keeps its size; it is fitted when it is restored.
        if (WindowState != WindowState.Normal) return;

        double hostW = PictureHost.ActualWidth, hostH = PictureHost.ActualHeight;
        double longSide = _fitted is { } old
            ? PhoneFit.KeptLongSide(PhoneFit.ShownLongSide(old.W, old.H, hostW, hostH), _fittedLongSide) : 0;
        double frameW = ActualWidth - hostW, frameH = ActualHeight - hostH;
        var work = _caption.WorkArea;
        var fit = PhoneFit.Fit(picture.W, picture.H, longSide,
            frameW, frameH,
            window: new FitRect(Left, Top, ActualWidth, ActualHeight),
            work: new FitRect(work.Left, work.Top, work.Width, work.Height));
        _fitted = picture;
        _fittedLongSide = PhoneFit.ShownLongSide(picture.W, picture.H, fit.Width - frameW, fit.Height - frameH);
        Left = fit.Left;
        Top = fit.Top;
        Width = fit.Width;
        Height = fit.Height;
    }

    /// <summary>
    /// Turns a JPEG into a picture that can be shown, and looks whether it
    /// is black. This is the slow part, 15 to 30 ms for a phone's screen, so
    /// it is done on the phone feed's thread and never on the window's.
    /// </summary>
    /// <returns>Null for bytes that are no picture.</returns>
    internal DecodedPicture? Decode(byte[] data, int offset, int count)
    {
        try
        {
            using var ms = new MemoryStream(data, offset, count);
            var img = new BitmapImage();
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.StreamSource = ms;
            img.EndInit();
            img.Freeze();
            // The phone may say its lock has changed while this picture is on
            // its way to the window. What was seen in it was seen before
            // that, and is not carried over.
            int asOf = Volatile.Read(ref _lockChanges);
            return new DecodedPicture(img, LookAtPicture(img), asOf);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Puts a decoded picture on show. The window's thread.</summary>
    internal void ShowPicture(DecodedPicture picture)
    {
        ScreenImage.Source = picture.Image;
        if (picture.Black is not { } black || picture.AsOf != _lockChanges || black == _pictureBlack) return;
        _pictureBlack = black;
        ShowCaption();
    }

    /// <summary>Decodes and shows a type 1 frame in one go, on the window's
    /// thread. The app goes through the feed; this is for a window driven by hand.</summary>
    public void OnFrame(byte[] frame)
    {
        if (Decode(frame, 1, frame.Length - 1) is { } picture) ShowPicture(picture);
    }

    /// <summary>Convert a mouse position on the Image control to normalized phone coords.</summary>
    private (double x, double y)? Normalize(Point p)
    {
        if (ScreenImage.Source == null) return null;
        double iw = ScreenImage.Source.Width, ih = ScreenImage.Source.Height;
        double cw = ScreenImage.ActualWidth, ch = ScreenImage.ActualHeight;
        if (cw <= 0 || ch <= 0) return null;

        // Stretch=Uniform letterboxes; find the displayed image rect.
        double scale = Math.Min(cw / iw, ch / ih);
        double dw = iw * scale, dh = ih * scale;
        double ox = (cw - dw) / 2, oy = (ch - dh) / 2;
        double nx = (p.X - ox) / dw, ny = (p.Y - oy) / dh;
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return (nx, ny);
    }

    private void SendTouch(string action, double x, double y)
    {
        _lastTouch = (x, y);
        _ws.SendJson(new JsonObject
        {
            ["type"] = "touch",
            ["to"] = _phoneId,
            ["action"] = action,
            ["x"] = x,
            ["y"] = y,
        });
    }

    private void BeginTouch(Point at)
    {
        if (Normalize(at) is not { } n) return;
        _touching = true;
        SendTouch("down", n.x, n.y);
    }

    private void Screen_MouseDown(object sender, MouseButtonEventArgs e)
    {
        _downPos = e.GetPosition(ScreenImage);
        _downTime = DateTime.UtcNow;
        _touching = false;
        ScreenImage.CaptureMouse();
        _holdTimer.Start();
        TrailTo(_downPos.Value);
    }

    private void Screen_MouseMove(object sender, MouseEventArgs e)
    {
        if (_downPos is not { } down) return;
        var pos = e.GetPosition(ScreenImage);
        TrailTo(pos);

        if (!_touching)
        {
            if ((pos - down).Length < DragSlopPx) return;
            _holdTimer.Stop();
            BeginTouch(down); // drag starts where the button went down
        }

        if (DateTime.UtcNow - _lastMoveSent < MoveInterval) return;
        if (Normalize(pos) is not { } n) return;
        _lastMoveSent = DateTime.UtcNow;
        SendTouch("move", n.x, n.y);
    }

    private void Screen_MouseUp(object sender, MouseButtonEventArgs e)
    {
        _holdTimer.Stop();
        ClearTrail();
        if (_downPos is not { } down)
        {
            ScreenImage.ReleaseMouseCapture();
            return;
        }
        bool touching = _touching;
        // Forgotten before the capture is let go: letting go is otherwise
        // taken for a drag that was cut short (see DragLost).
        _downPos = null;
        _touching = false;
        ScreenImage.ReleaseMouseCapture();
        var up = e.GetPosition(ScreenImage);

        if (touching)
        {
            var n = Normalize(up) ?? Normalize(down);
            if (n is { } end) SendTouch("up", end.x, end.y);
        }
        else if (Normalize(down) is { } a)
        {
            // Short press with no movement: plain tap.
            _ws.SendJson(new JsonObject { ["type"] = "tap", ["to"] = _phoneId, ["x"] = a.x, ["y"] = a.y });
        }
    }

    private void Screen_LostMouseCapture(object sender, MouseEventArgs e) => DragLost();

    /// <summary>
    /// The mouse was taken away with the button still down: Alt+Tab, another
    /// window coming to the front. No button-up will come. The finger held
    /// down on the phone is lifted, where it was, and the trail drawn over a
    /// pattern grid is cleared. A press that had not become a touch yet is
    /// dropped: nobody finished that click.
    /// </summary>
    private void DragLost()
    {
        if (_downPos is not { } down) return;
        _holdTimer.Stop();
        bool touching = _touching;
        _downPos = null;
        _touching = false;
        ClearTrail();
        if (touching && (_lastTouch ?? Normalize(down)) is { } at) SendTouch("up", at.x, at.y);
    }

    private void Screen_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        var at = Normalize(e.GetPosition(ScreenImage)) ?? (0.5, 0.5);
        _ws.SendJson(new JsonObject
        {
            ["type"] = "pinch",
            ["to"] = _phoneId,
            ["x"] = at.Item1,
            ["y"] = at.Item2,
            ["dir"] = e.Delta > 0 ? "in" : "out",
        });
    }

    private void Screen_OtherButtonUp(object sender, MouseButtonEventArgs e)
    {
        var action = e.ChangedButton switch
        {
            MouseButton.Right => "back",
            MouseButton.Middle => "homebtn",
            _ => null,
        };
        if (action == null) return;
        e.Handled = true;
        SendAction(action);
    }

    // ---------------- keyboard ----------------

    /// <summary>
    /// The <c>key</c> message for a key pressed with these modifiers, or null
    /// when the key types a character (sent from TextInput instead) or the
    /// phone has no use for it. Letters count only with Ctrl, as shortcuts.
    /// </summary>
    internal static JsonObject? KeyMessage(Key key, ModifierKeys modifiers)
    {
        // Alt+F4 and the like belong to Windows, and AltGr arrives as Ctrl+Alt
        // with the character it makes in TextInput.
        if ((modifiers & ModifierKeys.Alt) != 0) return null;
        bool ctrl = (modifiers & ModifierKeys.Control) != 0;
        bool shift = (modifiers & ModifierKeys.Shift) != 0;
        var code = key switch
        {
            >= Key.A and <= Key.Z when ctrl => key.ToString(),
            Key.Enter => "ENTER",
            Key.Escape => "ESC",
            Key.Tab => "TAB",
            Key.Back => "BACKSPACE",
            Key.Delete => "DELETE",
            Key.Home => "HOME",
            Key.End => "END",
            Key.PageUp => "PGUP",
            Key.PageDown => "PGDN",
            Key.Up => "UP",
            Key.Down => "DOWN",
            Key.Left => "LEFT",
            Key.Right => "RIGHT",
            _ => null,
        };
        if (code == null) return null;
        var msg = new JsonObject { ["type"] = "key", ["code"] = code };
        if (ctrl) msg["ctrl"] = true;
        if (shift) msg["shift"] = true;
        return msg;
    }

    private static bool IsPaste(Key key, ModifierKeys modifiers) =>
        modifiers == ModifierKeys.Control && key == Key.V || modifiers == ModifierKeys.Shift && key == Key.Insert;

    /// <summary>
    /// Every key pressed in the window comes through here, and the order of
    /// the checks is the order keys are claimed in. It is shared with the
    /// plans that add to it (PHONE-PLANS-ORDER.md, row 14):
    ///   1. F8 and Shift+F8: the Stats readout. Not built yet.
    ///   2. Esc while the menu is open: closes the menu.
    ///   3. Digits, Backspace and Enter while the phone asks for a PIN: taps
    ///      on its keys, never text. Digits are claimed in
    ///      <see cref="Window_PreviewTextInput"/>, where they arrive as
    ///      characters whatever the keyboard's layout.
    ///   4. Everything else is typed into the phone.
    /// A key that makes a character is left unhandled here and arrives in
    /// <see cref="Window_PreviewTextInput"/>; handling it here stops that.
    /// </summary>
    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        var modifiers = Keyboard.Modifiers;

        // 2. Esc closes the open menu instead of going Back on the phone, and
        // holding it down doesn't then go Back either.
        if (key == Key.Escape && (modifiers & ModifierKeys.Alt) == 0)
        {
            if (_menu.IsOpen)
            {
                CloseMenu();
                _escClosedMenu = true;
                e.Handled = true;
                return;
            }
            if (_escClosedMenu && e.IsRepeat)
            {
                e.Handled = true;
                return;
            }
            _escClosedMenu = false;
        }

        // 3. A PIN pad on the phone: Backspace and Enter are its own two
        // keys, tapped, and a paste has nowhere to go. A pad without one of
        // them (some confirm by themselves) gets nothing for it.
        if (CurrentPin is { } pin)
        {
            var label = modifiers != ModifierKeys.None ? null : key switch
            {
                Key.Back => PinEntry.Delete,
                Key.Enter => PinEntry.Ok,
                _ => null,
            };
            if (label != null)
            {
                if (pin.Tap(label) is { } at) TapEach([at]);
                e.Handled = true;
                return;
            }
            if (IsPaste(key, modifiers))
            {
                e.Handled = true;
                return;
            }
        }

        // 4. The phone's keys.
        if (IsPaste(key, modifiers))
        {
            if (!e.IsRepeat) _ = PasteAsync();
            e.Handled = true;
            return;
        }
        // Held keys repeat, so holding Backspace keeps deleting.
        if (KeyMessage(key, modifiers) is not { } msg) return;
        msg["to"] = _phoneId;
        _ws.SendJson(msg);
        e.Handled = true;
    }

    private void Window_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        var text = e.Text;
        if (string.IsNullOrEmpty(text)) return;
        if (text.Length == 1 && char.IsControl(text[0])) return; // control chars go via key events
        // Digits for a PIN are taps on the phone's own keys, and nothing
        // typed while its pad is up is sent as text: not a digit, and not a
        // letter that came with one.
        if (CurrentPin is { } pin)
        {
            TapEach(LockOverlay.PinTaps(pin, text));
            e.Handled = true;
            return;
        }
        SendText(text);
        e.Handled = true;
    }

    private void SendText(string text)
    {
        if (_phoneTypes == false && !_warnedNoTyping)
        {
            _warnedNoTyping = true;
            Toast.Show("This phone can't take typing from the PC. It needs Android 13 or later and the latest Remote Desktop app.");
        }
        _ws.SendJson(new JsonObject { ["type"] = "text", ["to"] = _phoneId, ["text"] = text });
    }

    /// <summary>Types this PC's clipboard into the phone, which is where a
    /// paste in this window is expected to come from.</summary>
    private async Task PasteAsync()
    {
        ClipboardSnapshot clip;
        try { clip = await ClipboardService.ReadAsync(); }
        catch (Exception ex)
        {
            Toast.Show("Couldn't read this PC's clipboard: " + ex.Message);
            return;
        }
        if (clip.TextLength > ClipboardFormat.MaxChars)
        {
            Toast.Show(ClipboardFormat.TooLong(clip.TextLength));
            return;
        }
        var text = string.IsNullOrEmpty(clip.Text) && !string.IsNullOrEmpty(clip.Html)
            ? ClipboardFormat.PlainText(clip.Html) : clip.Text;
        var (fitText, _, error) = ClipboardFormat.Fit(text, null);
        if (error != null || string.IsNullOrEmpty(fitText))
        {
            Toast.Show(error ?? "This PC's clipboard has no text to paste.");
            return;
        }
        SendText(fitText.ReplaceLineEndings("\n"));
    }
}
