using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Displays the phone's streamed screen and forwards mouse input as touch:
///  - quick click                -> tap
///  - press and hold             -> long-press (touch held down)
///  - press and drag             -> real drag (streamed touch moves)
///  - mouse wheel                -> pinch zoom in/out at the cursor
/// </summary>
public partial class PhoneViewWindow : Window
{
    private readonly WsClient _ws;
    private readonly string _phoneId;

    private Point? _downPos;
    private DateTime _downTime;
    private bool _touching;             // a touch-down has been sent to the phone
    private DateTime _lastMoveSent;
    private readonly DispatcherTimer _holdTimer;

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
    }

    public void OnScreenInfo(int width, int height)
    {
        if (width <= 0 || height <= 0) return;
        // Size the window to the phone's aspect ratio.
        Dispatcher.BeginInvoke(() =>
        {
            var targetH = Math.Min(820, SystemParameters.WorkArea.Height - 60);
            Height = targetH;
            Width = Math.Max(280, (targetH - 90) * width / height);
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

    private void Back_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "back", ["to"] = _phoneId });

    private void Home_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "homebtn", ["to"] = _phoneId });

    private void Recents_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "recents", ["to"] = _phoneId });
}
