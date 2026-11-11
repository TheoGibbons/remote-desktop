using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
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
/// the button and closes when it leaves (see <see cref="MenuHover"/>).
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
    private readonly WsClient _ws;
    private readonly string _phoneId;

    public string PeerId => _phoneId;

    private Point? _downPos;
    private DateTime _downTime;
    private bool _touching;             // a touch-down has been sent to the phone
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

    // The size of the phone's picture, and the one the window was last sized
    // round. They differ while the window is maximised or minimised.
    private (int W, int H)? _picture;
    private (int W, int H)? _fitted;

    private static readonly Brush MenuOpenBrush = Frozen(Color.FromArgb(0x1F, 0xFF, 0xFF, 0xFF));

    private const double DragSlopPx = 6;
    private static readonly TimeSpan HoldDelay = TimeSpan.FromMilliseconds(450);
    private static readonly TimeSpan MoveInterval = TimeSpan.FromMilliseconds(30);

    public PhoneViewWindow(WsClient ws, string phoneId, string name)
    {
        InitializeComponent();
        _ws = ws;
        _phoneId = phoneId;
        Title = name;

        _caption = new WindowCaption(this);
        _caption.Changed += LayOutTitleBar;
        LayOutTitleBar();

        _menuTimer = new DispatcherTimer(DispatcherPriority.Input);
        _menuTimer.Tick += (_, _) => { _menu.Tick(DateTime.UtcNow); ShowMenuState(); };
        MenuPopup.CustomPopupPlacementCallback = PlaceMenu;
        // The menu doesn't follow the window, so it goes when the window
        // moves, changes size or is left for another.
        Deactivated += (_, _) => CloseMenu();
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

        _ws.SendJson(new JsonObject { ["type"] = "start-view", ["to"] = _phoneId });
        Closed += (_, _) => _ws.SendJson(new JsonObject { ["type"] = "stop-view", ["to"] = _phoneId });

        // Holding the button without moving becomes a long-press on the phone.
        _holdTimer = new DispatcherTimer { Interval = HoldDelay };
        _holdTimer.Tick += (_, _) =>
        {
            _holdTimer.Stop();
            if (_downPos is { } p && !_touching) BeginTouch(p);
        };
        Loaded += (_, _) => { Focus(); Keyboard.Focus(this); FitToPicture(); };
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
            MenuPopup.IsOpen = _menu.IsOpen;
            if (_menu.IsOpen) MenuButton.Background = MenuOpenBrush;
            else MenuButton.ClearValue(BackgroundProperty);
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
        // Passing over a window in the background shouldn't pop a menu up
        // over whatever is being worked in. A click still opens it.
        if (!IsActive) return;
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
    public void ResendStartView() =>
        _ws.SendJson(new JsonObject { ["type"] = "start-view", ["to"] = _phoneId });

    // ---------------- window size ----------------

    public void OnScreenInfo(int width, int height, bool typing)
    {
        Dispatcher.BeginInvoke(() =>
        {
            _phoneTypes = typing;
            if (width <= 0 || height <= 0) return;
            _picture = (width, height);
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
        double longSide = _fitted is { } old ? PhoneFit.ShownLongSide(old.W, old.H, hostW, hostH) : 0;
        var work = _caption.WorkArea;
        var fit = PhoneFit.Fit(picture.W, picture.H, longSide,
            frameW: ActualWidth - hostW, frameH: ActualHeight - hostH,
            window: new FitRect(Left, Top, ActualWidth, ActualHeight),
            work: new FitRect(work.Left, work.Top, work.Width, work.Height));
        _fitted = picture;
        Left = fit.Left;
        Top = fit.Top;
        Width = fit.Width;
        Height = fit.Height;
    }

    public void OnFrame(byte[] frame)
    {
        try
        {
            using var ms = new MemoryStream(frame, 1, frame.Length - 1);
            var img = new BitmapImage();
            img.BeginInit();
            img.CacheOption = BitmapCacheOption.OnLoad;
            img.StreamSource = ms;
            img.EndInit();
            img.Freeze();
            ScreenImage.Source = img;
        }
        catch { }
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

    private void SendTouch(string action, double x, double y) =>
        _ws.SendJson(new JsonObject
        {
            ["type"] = "touch",
            ["to"] = _phoneId,
            ["action"] = action,
            ["x"] = x,
            ["y"] = y,
        });

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
    }

    private void Screen_MouseMove(object sender, MouseEventArgs e)
    {
        if (_downPos is not { } down) return;
        var pos = e.GetPosition(ScreenImage);

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
        ScreenImage.ReleaseMouseCapture();
        _holdTimer.Stop();
        if (_downPos is not { } down) return;
        var up = e.GetPosition(ScreenImage);
        _downPos = null;

        if (_touching)
        {
            _touching = false;
            var n = Normalize(up) ?? Normalize(down);
            if (n is { } end) SendTouch("up", end.x, end.y);
        }
        else if (Normalize(down) is { } a)
        {
            // Short press with no movement: plain tap.
            _ws.SendJson(new JsonObject { ["type"] = "tap", ["to"] = _phoneId, ["x"] = a.x, ["y"] = a.y });
        }
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
    ///      on its keys. Not built yet.
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
