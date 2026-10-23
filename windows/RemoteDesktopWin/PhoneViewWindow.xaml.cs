using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
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
/// The window has no navigation buttons of its own: clicking the phone's
/// buttons in the picture works, and right- and middle-click stand in for
/// them on a phone that uses gestures.
///
/// While it is focused, the keyboard types into the phone's focused text
/// field: characters as <c>text</c>, composed here with this PC's layout, and
/// editing keys and Ctrl shortcuts as <c>key</c>. Ctrl+V pastes this PC's
/// clipboard rather than the phone's, and Esc is Back. Alt combinations stay
/// with Windows.
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

    private const double DragSlopPx = 6;
    private static readonly TimeSpan HoldDelay = TimeSpan.FromMilliseconds(450);
    private static readonly TimeSpan MoveInterval = TimeSpan.FromMilliseconds(30);

    public PhoneViewWindow(WsClient ws, string phoneId)
    {
        InitializeComponent();
        _ws = ws;
        _phoneId = phoneId;
        _ws.SendJson(new JsonObject { ["type"] = "start-view", ["to"] = _phoneId });
        Closed += (_, _) => _ws.SendJson(new JsonObject { ["type"] = "stop-view", ["to"] = _phoneId });

        // Holding the button without moving becomes a long-press on the phone.
        _holdTimer = new DispatcherTimer { Interval = HoldDelay };
        _holdTimer.Tick += (_, _) =>
        {
            _holdTimer.Stop();
            if (_downPos is { } p && !_touching) BeginTouch(p);
        };
        Loaded += (_, _) => { Focus(); Keyboard.Focus(this); };
    }

    /// <summary>Ask for the stream again — the first start-view is dropped when
    /// the phone hasn't approved this device yet.</summary>
    public void ResendStartView() =>
        _ws.SendJson(new JsonObject { ["type"] = "start-view", ["to"] = _phoneId });

    public void OnScreenInfo(int width, int height, bool typing)
    {
        Dispatcher.BeginInvoke(() => _phoneTypes = typing);
        if (width <= 0 || height <= 0) return;
        // Size the window to the phone's aspect ratio, so the picture fills it
        // between the title bar and frame. They are measured once the window
        // has been laid out, and estimated before.
        Dispatcher.BeginInvoke(() =>
        {
            var targetH = Math.Min(820, SystemParameters.WorkArea.Height - 60);
            double frameW = 16, frameH = 39;
            if (Content is FrameworkElement client && client.ActualWidth > 0)
            {
                frameW = ActualWidth - client.ActualWidth;
                frameH = ActualHeight - client.ActualHeight;
            }
            Height = targetH;
            Width = Math.Max(280, (targetH - frameH) * width / height + frameW);
        });
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
        _ws.SendJson(new JsonObject { ["type"] = action, ["to"] = _phoneId });
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

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        if (IsPaste(key, Keyboard.Modifiers))
        {
            if (!e.IsRepeat) _ = PasteAsync();
            e.Handled = true;
            return;
        }
        // Held keys repeat, so holding Backspace keeps deleting.
        if (KeyMessage(key, Keyboard.Modifiers) is not { } msg) return;
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
