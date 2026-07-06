using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Imaging;

namespace RemoteDesktopWin;

/// <summary>
/// Displays the phone's streamed screen. A click becomes a tap; a press-drag
/// becomes a swipe gesture on the phone.
/// </summary>
public partial class PhoneViewWindow : Window
{
    private readonly WsClient _ws;
    private readonly string _phoneId;

    private Point? _downPos;
    private DateTime _downTime;

    public PhoneViewWindow(WsClient ws, string phoneId)
    {
        InitializeComponent();
        _ws = ws;
        _phoneId = phoneId;
        _ws.SendJson(new JsonObject { ["type"] = "start-view", ["to"] = _phoneId });
        Closed += (_, _) => _ws.SendJson(new JsonObject { ["type"] = "stop-view", ["to"] = _phoneId });
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

    private void Screen_MouseDown(object sender, MouseButtonEventArgs e)
    {
        _downPos = e.GetPosition(ScreenImage);
        _downTime = DateTime.UtcNow;
        ScreenImage.CaptureMouse();
    }

    private void Screen_MouseMove(object sender, MouseEventArgs e) { }

    private void Screen_MouseUp(object sender, MouseButtonEventArgs e)
    {
        ScreenImage.ReleaseMouseCapture();
        if (_downPos == null) return;
        var up = e.GetPosition(ScreenImage);
        var down = _downPos.Value;
        _downPos = null;

        var a = Normalize(down);
        var b = Normalize(up);
        if (a == null) return;

        var dist = (up - down).Length;
        if (dist < 8 || b == null)
        {
            _ws.SendJson(new JsonObject { ["type"] = "tap", ["to"] = _phoneId, ["x"] = a.Value.x, ["y"] = a.Value.y });
        }
        else
        {
            var ms = Math.Clamp((int)(DateTime.UtcNow - _downTime).TotalMilliseconds, 60, 1500);
            _ws.SendJson(new JsonObject
            {
                ["type"] = "swipe",
                ["to"] = _phoneId,
                ["x1"] = a.Value.x, ["y1"] = a.Value.y,
                ["x2"] = b.Value.x, ["y2"] = b.Value.y,
                ["ms"] = ms,
            });
        }
    }

    private void Back_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "back", ["to"] = _phoneId });

    private void Home_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "homebtn", ["to"] = _phoneId });

    private void Recents_Click(object sender, RoutedEventArgs e) =>
        _ws.SendJson(new JsonObject { ["type"] = "recents", ["to"] = _phoneId });
}
