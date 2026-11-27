using System.Globalization;
using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Views and controls another Windows PC. It is the C#/WPF counterpart of the
/// Android <c>RemoteScreenView</c> + <c>ViewerActivity</c>:
///
///  - decodes the host's dirty-rect stream (binary frame type 3) into a
///    persistent <see cref="WriteableBitmap"/> composite (see PROTOCOL.md);
///  - zooms, from fitting the whole desktop to 400%, and pans when the pointer
///    is pushed against an edge. It tells the host which part of the desktop
///    it shows (<c>view-region</c>), so that part comes at the detail this
///    screen can use rather than the whole desktop shrunk to fit;
///  - forwards real mouse input as <c>mouse</c> / <c>scroll</c> messages
///    (coordinates normalized 0..1 over the stitched desktop);
///  - forwards the physical keyboard while focused: every mapped key (letters,
///    digits, space, navigation, function and modifier keys) goes as <c>key</c>
///    down/up, so the host reproduces case and shortcuts with its own layout;
///    unmapped keys (punctuation, dead keys, IME) fall back to a <c>text</c>
///    message composed on this side. In full screen the keys Windows keeps for
///    itself go too (see <see cref="FullScreenKeys"/>).
///
/// The remote host injects all of this exactly as it already does for a phone
/// viewer — the protocol and host code are unchanged.
/// </summary>
public partial class PcViewWindow : Window, IDeviceWindow
{
    private readonly WsClient _ws;
    // Which PC this is, and the id it has in the session now: none while its
    // connection, or this PC's, has dropped (see DeviceLink).
    private readonly DeviceLink _link;
    private readonly string _name;
    private readonly string _hint;

    // The Copy or Paste request in flight: one at a time, only its own answer
    // counts, and a host too old to know them never answers at all.
    private string? _clipReq;
    private int _clipCounter;
    private readonly DispatcherTimer _clipTimeout = new() { Interval = TimeSpan.FromSeconds(10) };

    // Dirty-rect compositing state (mirrors ViewerActivity on Android).
    private WriteableBitmap? _compose;
    private int _surfW, _surfH;
    // The part of the desktop the composite shows, in desktop pixels.
    private Int32Rect _composeRegion;
    private long _lastSeq = -1;
    private bool _haveKeyframe;
    private DateTime _lastKeyframeReq;
    private bool _sizedToSurface;

    // What was seen before, desktop-sized but smaller, drawn grey and dimmed
    // under the picture for the parts the stream is not covering just now.
    private RenderTargetBitmap? _backdrop;
    private bool _keptAsBackdrop;
    private const double BackdropMaxWidth = 2560;

    // Zoom and pan, and what the PC was last asked to stream.
    private readonly PcViewport _viewport = new();
    private double _dpi = 1;
    // Throttled, not put off: a steady edge pan still asks as it goes.
    private readonly DispatcherTimer _askTimer = new() { Interval = TimeSpan.FromMilliseconds(150) };
    private string? _lastAsked;
    private double _askedZoom;
    private bool _settingSlider;

    // Edge panning: the pointer this close to an edge with more desktop beyond
    // it, for this long, pans. The pause lets the pointer cross an edge
    // without moving the picture.
    private const double EdgeZone = 30; // device-independent pixels
    private static readonly TimeSpan EdgeDwell = TimeSpan.FromMilliseconds(200);
    private readonly DispatcherTimer _panTimer = new(DispatcherPriority.Render) { Interval = TimeSpan.FromMilliseconds(16) };
    private DateTime? _edgeSince;
    private DateTime _lastPanTick;

    // Full screen: the window as it was, the keys taken from Windows, and the toolbar's hiding.
    private bool _fullScreen;
    private (WindowState State, WindowStyle Style, ResizeMode Resize) _windowed;
    private FullScreenKeys? _keys;
    private readonly DispatcherTimer _hideBar = new() { Interval = TimeSpan.FromMilliseconds(700) };

    // Keys we've forwarded a "down" for, so we can send the matching "up" (and
    // release everything if the window loses focus, preventing stuck modifiers).
    private readonly HashSet<string> _downKeys = new();
    private DateTime _lastMoveSent;
    private static readonly TimeSpan MoveInterval = TimeSpan.FromMilliseconds(30);

    /// <summary>The id the PC has now; null while it is away.</summary>
    public string? PeerId => _link.PeerId;

    /// <summary>Whether this is the window of the device with that id.</summary>
    public bool IsOn(string? peerId) => peerId != null && _link.PeerId == peerId;

    DeviceLink IDeviceWindow.Link => _link;
    string IDeviceWindow.DeviceName => _name;

    /// <param name="identity">The fingerprint of the PC's approved key, by
    /// which it is found again after a reconnection.</param>
    public PcViewWindow(WsClient ws, string peerId, string name, string? identity = null)
    {
        InitializeComponent();
        WindowCaption.Darken(this);
        SizeChanged += (_, _) => FitBarLabels();
        _ws = ws;
        _link = new DeviceLink(peerId, identity);
        _name = name;
        _hint = HintText.Text;
        Title = "Remote: " + name;
        _clipTimeout.Tick += (_, _) =>
        {
            EndClipRequest();
            Toast.Show($"{_name} didn't answer. It may not have approved this PC yet, or it needs a newer Remote Desktop.");
        };
        _askTimer.Tick += (_, _) => Ask();
        _panTimer.Tick += (_, _) => EdgePanTick();
        _hideBar.Tick += (_, _) =>
        {
            _hideBar.Stop();
            if (_fullScreen && !ToolbarHost.IsMouseOver) ToolbarHost.Visibility = Visibility.Collapsed;
        };

        Send(new JsonObject { ["type"] = "start-view" }, always: true);
        Closed += (_, _) =>
        {
            _clipTimeout.Stop();
            _askTimer.Stop();
            _panTimer.Stop();
            _hideBar.Stop();
            _keys?.Dispose();
            _keys = null;
            Send(new JsonObject { ["type"] = "stop-view" }, always: true);
        };
        Loaded += (_, _) => { Focusable = true; FocusScreen(); };
    }

    /// <summary>
    /// Everything the window sends the PC goes from here: to the id that PC
    /// has now, and nowhere while it is away. The id it had is dead then, and
    /// may be another device's by the time this PC is back. Nor while it is
    /// back and has not answered yet, when the picture is still the old one;
    /// only asking for the screen, and saying the window has closed, go then.
    /// </summary>
    private void Send(JsonObject msg, bool always = false)
    {
        if (_link.PeerId is not { } id || !always && !_link.Live) return;
        msg["to"] = id;
        _ws.SendJson(msg);
    }

    /// <summary>Ask for the stream again — the first start-view is dropped when
    /// the host hasn't approved this device yet, and the part on show with it.</summary>
    public void ResendStartView()
    {
        Send(new JsonObject { ["type"] = "start-view" }, always: true);
        AskAgain();
    }

    /// <summary>The PC has sent its screen's size or a full picture. If it had
    /// been away, that is its answer to being asked again.</summary>
    private void Answered()
    {
        if (!_link.Answered()) return;
        HintText.Text = _hint;
        // It forgot which part was on show along with the old id.
        AskAgain();
    }

    /// <summary>
    /// The link to the PC has changed. Away, the window says so where its
    /// hint is, sends nothing and draws nothing; back, under a new id, it
    /// asks for the screen again and waits for a full picture.
    /// </summary>
    void IDeviceWindow.LinkChanged(string? was)
    {
        HintText.Text = _link.Notice(_name) ?? _hint;
        if (_link.Away)
        {
            if (was == null) return;
            // Keys held down there can't be let go from here; that PC lets go of them itself.
            _downKeys.Clear();
            EndClipRequest();
            return;
        }
        if (was != null) return;
        _haveKeyframe = false;
        _lastSeq = -1;
        _lastAsked = null;
        _askedZoom = 0;
        Send(new JsonObject { ["type"] = "start-view" }, always: true);
    }

    /// <summary>The wait for the PC is over: the window says so and closes.</summary>
    void IDeviceWindow.GaveUp()
    {
        Toast.Show(_link.GaveUpText(_name), 10000);
        Close();
    }

    /// <summary>The size of the picture, and of the desktop it is all or part of.</summary>
    public void OnScreenInfo(int width, int height, int desktopWidth = 0, int desktopHeight = 0)
    {
        Answered();
        // A host that does not say streams its whole desktop.
        int w = desktopWidth > 0 ? desktopWidth : width, h = desktopHeight > 0 ? desktopHeight : height;
        if (w > 0 && h > 0) SetDesktop(w, h);
    }

    private void SetDesktop(int w, int h)
    {
        if (w == _viewport.DeskW && h == _viewport.DeskH) return;
        bool first = _viewport.DeskW <= 0;
        _viewport.SetDesktop(w, h);
        // Its screens changed: what was seen before no longer lines up.
        if (!first)
        {
            _backdrop = null;
            BackdropImage.Source = null;
        }
        if (!_sizedToSurface) SizeToSurface(w, h);
        ViewChanged();
    }

    /// <summary>Size the window to the remote desktop's shape, once.</summary>
    private void SizeToSurface(int w, int h)
    {
        _sizedToSurface = true;
        var wa = SystemParameters.WorkArea;
        double maxW = wa.Width * 0.9, maxH = wa.Height * 0.9 - 48; // 48 ~ toolbar
        double scale = Math.Min(1.0, Math.Min(maxW / w, maxH / h));
        Width = Math.Max(MinWidth, w * scale);
        Height = Math.Max(MinHeight, h * scale + 48);
    }

    // ---------------- screen decode (binary frame type 3) ----------------

    private static int ReadU16(byte[] d, ref int p) { int v = (d[p] << 8) | d[p + 1]; p += 2; return v; }
    private static long ReadU32(byte[] d, ref int p)
    {
        long v = ((long)d[p] << 24) | ((long)d[p + 1] << 16) | ((long)d[p + 2] << 8) | d[p + 3];
        p += 4;
        return v;
    }

    /// <summary>
    /// Decode one patch on the UI thread. Layout (after the frame-type byte):
    /// [seq u32][flags u8][surfW u16][surfH u16][rectCount u16], then when
    /// flags bit 1 is set the desktop rect the picture covers
    /// [x u16][y u16][w u16][h u16], then per tile [x u16][y u16][w u16][h u16]
    /// [jpegLen u32][JPEG bytes]. Bit 0 is a keyframe; bit 2 says the crop
    /// moved and what both crops share is to be carried across. Tiles are
    /// absolute pixel content, so a dropped patch only leaves regions stale —
    /// keep the canvas and ask for a keyframe to catch up.
    /// </summary>
    public void OnPatch(byte[] data)
    {
        // A patch names no sender. While the PC is away none is its.
        if (_link.Away) return;
        try
        {
            int p = 1; // skip frame-type byte
            long seq = ReadU32(data, ref p);
            int flags = data[p++];
            bool keyframe = (flags & 1) != 0;
            int w = ReadU16(data, ref p);
            int h = ReadU16(data, ref p);
            int rectCount = ReadU16(data, ref p);
            Int32Rect region;
            if ((flags & 2) != 0)
            {
                int rx = ReadU16(data, ref p), ry = ReadU16(data, ref p);
                int rw = ReadU16(data, ref p), rh = ReadU16(data, ref p);
                region = new Int32Rect(rx, ry, rw, rh);
            }
            else
            {
                // The whole desktop, perhaps shrunk.
                if (_viewport.DeskW <= 0) SetDesktop(w, h);
                region = new Int32Rect(0, 0, (int)_viewport.DeskW, (int)_viewport.DeskH);
            }
            bool scrolled = (flags & 4) != 0;

            bool placed = false;
            if (_compose != null && !region.Equals(_composeRegion))
            {
                if (scrolled && _haveKeyframe && _surfW == w && _surfH == h && CanCarry(_composeRegion, region, w, h))
                    Carry(_composeRegion, region, w, h);
                else
                {
                    // The picture on show is about to be of somewhere else.
                    if (!_keptAsBackdrop) KeepAsBackdrop();
                    _keptAsBackdrop = true;
                    if (!keyframe) { RequestKeyframe(); return; }
                    _haveKeyframe = false;
                }
                _composeRegion = region;
                _keptAsBackdrop = false;
                placed = true;
            }

            if (_compose == null || _surfW != w || _surfH != h)
            {
                if (!keyframe) { RequestKeyframe(); return; } // can't composite yet
                _surfW = w; _surfH = h;
                _compose = new WriteableBitmap(w, h, 96, 96, PixelFormats.Bgr32, null);
                ScreenImage.Source = _compose;
                _haveKeyframe = false;
                _composeRegion = region;
                placed = true;
            }

            // A full picture is only sent to a device that asked for the screen.
            if (keyframe) Answered();
            if (keyframe) _haveKeyframe = true;
            else if (!_haveKeyframe || seq != _lastSeq + 1) RequestKeyframe();
            _lastSeq = seq;

            for (int i = 0; i < rectCount; i++)
            {
                int x = ReadU16(data, ref p);
                int y = ReadU16(data, ref p);
                ReadU16(data, ref p); // tile w — implicit in the JPEG
                ReadU16(data, ref p); // tile h
                int len = (int)ReadU32(data, ref p);
                DrawTile(data, p, len, x, y);
                p += len;
            }
            if (placed) PlaceImages();
        }
        catch
        {
            RequestKeyframe();
        }
    }

    private void DrawTile(byte[] data, int offset, int len, int x, int y)
    {
        using var ms = new MemoryStream(data, offset, len, writable: false);
        var decoder = BitmapDecoder.Create(ms, BitmapCreateOptions.None, BitmapCacheOption.OnLoad);
        BitmapSource src = decoder.Frames[0];
        if (src.Format != PixelFormats.Bgr32)
            src = new FormatConvertedBitmap(src, PixelFormats.Bgr32, null, 0);

        int pw = src.PixelWidth, ph = src.PixelHeight;
        if (x + pw > _surfW || y + ph > _surfH) return; // out of bounds — skip, keyframe will fix
        int stride = pw * 4;
        var pixels = new byte[stride * ph];
        src.CopyPixels(pixels, stride, 0);
        _compose!.WritePixels(new Int32Rect(0, 0, pw, ph), pixels, stride, x, y);
    }

    /// <summary>
    /// Whether the picture can be carried from one crop to the other: the same
    /// size, a whole number of desktop pixels to each picture pixel, a move of
    /// whole picture pixels, and something in common. The host makes sure of
    /// all that, but a picture carried wrongly would be wrong without showing it.
    /// </summary>
    internal static bool CanCarry(Int32Rect from, Int32Rect to, int w, int h)
    {
        if (w <= 0 || h <= 0 || from.Width != to.Width || from.Height != to.Height) return false;
        if (to.Width % w != 0 || to.Height % h != 0) return false;
        int kx = to.Width / w, ky = to.Height / h;
        if ((to.X - from.X) % kx != 0 || (to.Y - from.Y) % ky != 0) return false;
        return Overlap(from, to) != null;
    }

    private static Int32Rect? Overlap(Int32Rect a, Int32Rect b)
    {
        int l = Math.Max(a.X, b.X), t = Math.Max(a.Y, b.Y);
        int r = Math.Min(a.X + a.Width, b.X + b.Width), btm = Math.Min(a.Y + a.Height, b.Y + b.Height);
        return r > l && btm > t ? new Int32Rect(l, t, r - l, btm - t) : null;
    }

    /// <summary>The crop moved: shift what both crops share, and the patch paints what came into view.</summary>
    private void Carry(Int32Rect from, Int32Rect to, int w, int h)
    {
        int kx = to.Width / w, ky = to.Height / h;
        var both = Overlap(from, to)!.Value;
        var src = new Int32Rect((both.X - from.X) / kx, (both.Y - from.Y) / ky, both.Width / kx, both.Height / ky);
        int stride = src.Width * 4;
        var pixels = new byte[stride * src.Height];
        _compose!.CopyPixels(src, pixels, stride, 0);
        _compose.WritePixels(new Int32Rect(0, 0, src.Width, src.Height), pixels, stride,
            (both.X - to.X) / kx, (both.Y - to.Y) / ky);
    }

    /// <summary>The picture on show goes into the backdrop before it is replaced by one of somewhere else.</summary>
    private void KeepAsBackdrop()
    {
        if (_compose == null || !_haveKeyframe || _viewport.DeskW <= 0) return;
        double s = Math.Min(1.0, BackdropMaxWidth / _viewport.DeskW);
        int bw = Math.Max(1, (int)(_viewport.DeskW * s)), bh = Math.Max(1, (int)(_viewport.DeskH * s));
        var visual = new DrawingVisual();
        using (var dc = visual.RenderOpen())
        {
            if (_backdrop is { } old && old.PixelWidth == bw && old.PixelHeight == bh)
                dc.DrawImage(old, new Rect(0, 0, bw, bh));
            dc.DrawImage(_compose, new Rect(_composeRegion.X * s, _composeRegion.Y * s,
                _composeRegion.Width * s, _composeRegion.Height * s));
        }
        var merged = new RenderTargetBitmap(bw, bh, 96, 96, PixelFormats.Pbgra32);
        merged.Render(visual);
        merged.Freeze();
        _backdrop = merged;
        BackdropImage.Source = new FormatConvertedBitmap(merged, PixelFormats.Gray8, null, 0);
        PlaceImages();
    }

    private void RequestKeyframe()
    {
        if ((DateTime.UtcNow - _lastKeyframeReq).TotalMilliseconds < 2000) return;
        _lastKeyframeReq = DateTime.UtcNow;
        Send(new JsonObject { ["type"] = "request-keyframe" });
    }

    // ---------------- zoom, pan and what is asked for ----------------

    private void ScreenArea_SizeChanged(object sender, SizeChangedEventArgs e) => UpdateView();

    protected override void OnDpiChanged(DpiScale oldDpi, DpiScale newDpi)
    {
        base.OnDpiChanged(oldDpi, newDpi);
        UpdateView();
    }

    private void UpdateView()
    {
        _dpi = VisualTreeHelper.GetDpi(this).DpiScaleX;
        _viewport.SetView(ScreenArea.ActualWidth * _dpi, ScreenArea.ActualHeight * _dpi);
        ViewChanged();
    }

    /// <summary>The zoom or pan changed, or what they apply to.</summary>
    private void ViewChanged()
    {
        PlaceImages();
        UpdateZoomControls();
        if (!_askTimer.IsEnabled) _askTimer.Start();
    }

    private void PlaceImages()
    {
        if (_compose != null)
            Place(ScreenImage, _composeRegion.X, _composeRegion.Y, _composeRegion.Width, _composeRegion.Height);
        if (_backdrop != null) Place(BackdropImage, 0, 0, _viewport.DeskW, _viewport.DeskH);
    }

    private void Place(FrameworkElement image, double x, double y, double w, double h)
    {
        if (!_viewport.Ready) return;
        var (vx, vy, vw, vh) = _viewport.ToView(x, y, w, h);
        Canvas.SetLeft(image, vx / _dpi);
        Canvas.SetTop(image, vy / _dpi);
        image.Width = Math.Max(0, vw / _dpi);
        image.Height = Math.Max(0, vh / _dpi);
    }

    /// <summary>The PC forgot what this window shows: it is back under a new id, or has just approved this PC.</summary>
    private void AskAgain()
    {
        _lastAsked = null;
        _askedZoom = 0;
        Ask();
    }

    /// <summary>
    /// Tell the PC which part of its desktop is on show, with a margin round
    /// it, and how many pixels that takes here; it streams that instead of the
    /// whole desktop. Each request costs it a fresh picture, so a pan inside
    /// the margin, or a request the same as the last, sends nothing.
    /// </summary>
    private void Ask()
    {
        _askTimer.Stop();
        // Until it answers, only asking for the screen goes; this goes once it has (see Answered).
        if (!_link.Live) return;
        if (_compose != null && !_viewport.NeedsAsking(_composeRegion.X, _composeRegion.Y,
                _composeRegion.Width, _composeRegion.Height, _askedZoom)) return;
        if (_viewport.Want() is not { } want) return;
        string key = string.Create(CultureInfo.InvariantCulture,
            $"{want.X:F4},{want.Y:F4},{want.W:F4},{want.H:F4},{want.OutW},{want.OutH}");
        if (key == _lastAsked) return;
        _lastAsked = key;
        _askedZoom = _viewport.Zoom;
        Send(new JsonObject
        {
            ["type"] = "view-region",
            ["x"] = want.X,
            ["y"] = want.Y,
            ["w"] = want.W,
            ["h"] = want.H,
            ["outW"] = want.OutW,
            ["outH"] = want.OutH,
        });
    }

    private void UpdateZoomControls()
    {
        if (!_viewport.Ready) return;
        double min = _viewport.MinZoom, top = _viewport.TopZoom;
        _settingSlider = true;
        ZoomSlider.Value = top > min ? Math.Log(_viewport.Zoom / min) / Math.Log(top / min) : 0;
        _settingSlider = false;
        ZoomText.Text = Math.Round(_viewport.Zoom * 100).ToString(CultureInfo.CurrentCulture) + "%";
        // Lit while that zoom is the one in force.
        FitButton.IsChecked = _viewport.Fitting;
        ActualSizeButton.IsChecked = Math.Abs(_viewport.Zoom - 1) < 0.005;
    }

    // The slider runs from fitting the whole desktop to 400%, evenly by ratio:
    // as far from 50% to 100% as from 100% to 200%.
    private void ZoomSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_settingSlider || !_viewport.Ready) return;
        double min = _viewport.MinZoom, top = _viewport.TopZoom;
        _viewport.ZoomAroundCentre(min * Math.Pow(top / min, e.NewValue));
        ViewChanged();
    }

    private void Fit_Click(object sender, RoutedEventArgs e)
    {
        _viewport.Fit();
        ViewChanged();
        FocusScreen();
    }

    private void ActualSize_Click(object sender, RoutedEventArgs e)
    {
        _viewport.ZoomAroundCentre(1);
        ViewChanged();
        FocusScreen();
    }

    /// <summary>How fast the pointer here pans, in screen pixels a second. In
    /// full screen the middle of the top edge is the toolbar's, not a pan's.</summary>
    private (double X, double Y) EdgeVelocity(Point at) =>
        _viewport.EdgeVelocity(at.X * _dpi, at.Y * _dpi, EdgeZone * _dpi, upAllowed: !InBarZone(at));

    private bool InBarZone(Point at) =>
        _fullScreen && Math.Abs(at.X - ScreenArea.ActualWidth / 2) < ScreenArea.ActualWidth / 6;

    private void EdgePanTick()
    {
        var now = DateTime.UtcNow;
        double seconds = Math.Min(0.1, (now - _lastPanTick).TotalSeconds);
        _lastPanTick = now;
        var at = Mouse.GetPosition(ScreenArea);
        // Held down while dragging, the pointer may be past the edge; otherwise it must be on the picture.
        var v = ScreenArea.IsMouseOver || ScreenArea.IsMouseCaptured ? EdgeVelocity(at) : (0, 0);
        if (v == (0, 0))
        {
            _panTimer.Stop();
            _edgeSince = null;
            return;
        }
        _edgeSince ??= now;
        if (now - _edgeSince < EdgeDwell) return;
        if (!_viewport.PanBy(v.X * seconds, v.Y * seconds)) return;
        ViewChanged();
        // The pointer stays put while the desktop moves under it.
        SendPointer(at);
    }

    // ---------------- full screen ----------------

    private void FullScreen_Click(object sender, RoutedEventArgs e) => SetFullScreen(!_fullScreen);

    /// <summary>
    /// Full screen covers the whole monitor, taskbar included. The toolbar
    /// slides down from the middle of the top edge, and the keys Windows keeps
    /// for itself (the Windows key, Alt+Tab) go to the remote PC.
    /// Ctrl+Alt+Break switches it on and off.
    /// </summary>
    private void SetFullScreen(bool on)
    {
        if (on == _fullScreen) return;
        _fullScreen = on;
        if (on)
        {
            _windowed = (WindowState, WindowStyle, ResizeMode);
            WindowStyle = WindowStyle.None;
            ResizeMode = ResizeMode.NoResize;
            // Maximized again once the frame has gone, so it covers the taskbar too.
            WindowState = WindowState.Normal;
            WindowState = WindowState.Maximized;
            StyleBar(floating: true);
            ToolbarHost.Visibility = Visibility.Collapsed;
            try
            {
                _keys = new FullScreenKeys(new WindowInteropHelper(this).Handle,
                    key => Dispatcher.BeginInvoke(() => OnTakenKey(key)));
            }
            catch (Exception ex)
            {
                Toast.Show("The Windows key and Alt+Tab will stay on this PC: " + ex.Message);
            }
            Toast.Show("Full screen. For the toolbar, move the pointer to the middle of the top edge. " +
                       "Ctrl+Alt+Break leaves full screen.", 6000);
        }
        else
        {
            _keys?.Dispose();
            _keys = null;
            _hideBar.Stop();
            WindowStyle = _windowed.Style;
            ResizeMode = _windowed.Resize;
            WindowState = _windowed.State;
            StyleBar(floating: false);
            ToolbarHost.Visibility = Visibility.Visible;
        }
        FocusScreen();
    }

    /// <summary>
    /// Docked, the bar runs across the top of the window above the picture.
    /// Floating, in full screen, it lies over the picture at the top middle,
    /// sized to its buttons, with rounded corners and a shadow, and without
    /// the hint, which full screen has no room for.
    /// </summary>
    private void StyleBar(bool floating)
    {
        Grid.SetRow(ToolbarHost, floating ? 1 : 0);
        ToolbarHost.HorizontalAlignment = floating ? HorizontalAlignment.Center : HorizontalAlignment.Stretch;
        ToolbarHost.VerticalAlignment = floating ? VerticalAlignment.Top : VerticalAlignment.Stretch;
        ToolbarHost.CornerRadius = floating ? new CornerRadius(0, 0, 12, 12) : new CornerRadius(0);
        ToolbarHost.BorderThickness = floating ? new Thickness(1, 0, 1, 1) : new Thickness(0, 0, 0, 1);
        ToolbarHost.Padding = floating ? new Thickness(10, 6, 10, 7) : new Thickness(6, 5, 6, 5);
        ToolbarHost.Effect = floating ? (System.Windows.Media.Effects.Effect)FindResource("FloatingBarShadow") : null;
        HintText.Visibility = floating ? Visibility.Collapsed : Visibility.Visible;
        FullScreenGlyph.Text = floating ? "\uE73F" : "\uE740"; // BackToWindow, FullScreen
        FullScreenText.Text = floating ? "Exit full screen" : "Full screen";
        FullScreenButton.ToolTip = floating ? "Back to a window (Ctrl+Alt+Break)" : "Full screen (Ctrl+Alt+Break)";
    }

    /// <summary>In a narrow window the buttons show only their icons, so the
    /// zoom controls keep their room. Their tooltips still say what they do.</summary>
    private void FitBarLabels() => ShowBarLabels(ActualWidth >= 1000);

    private void ShowBarLabels(bool shown)
    {
        var labels = shown ? Visibility.Visible : Visibility.Collapsed;
        CadText.Visibility = CopyText.Visibility = PasteText.Visibility = FullScreenText.Visibility = labels;
    }

    /// <summary>In full screen the toolbar comes down when the pointer reaches
    /// the middle of the top edge, and goes once the pointer has left it.</summary>
    private void ShowOrHideBar(Point at)
    {
        if (at.Y <= 2 && InBarZone(at))
        {
            _hideBar.Stop();
            ToolbarHost.Visibility = Visibility.Visible;
        }
        else if (ToolbarHost.IsVisible && !ToolbarHost.IsMouseOver && !_hideBar.IsEnabled) _hideBar.Start();
    }

    private void Toolbar_MouseLeave(object sender, MouseEventArgs e)
    {
        if (_fullScreen) _hideBar.Start();
    }

    /// <summary>
    /// A key taken from Windows in full screen. The modifiers held as it went
    /// down go first if they have not yet: the hook hands its keys over ahead
    /// of the ones already on their way to this window, and the PC must see
    /// Alt before the Tab that makes it Alt+Tab.
    /// </summary>
    private void OnTakenKey(FullScreenKeys.Captured key)
    {
        if (key.Down)
        {
            foreach (var (held, code) in new[] { (key.Ctrl, "CTRL"), (key.Alt, "ALT"), (key.Shift, "SHIFT") })
                if (held && _downKeys.Add(code)) SendKey(code, "down");
            _downKeys.Add(key.Code);
            SendKey(key.Code, "down");
        }
        else if (_downKeys.Remove(key.Code)) SendKey(key.Code, "up");
    }

    private void FocusScreen()
    {
        Focus();
        Keyboard.Focus(this);
    }

    // ---------------- mouse ----------------

    /// <summary>Mouse position over the screen area → normalized 0..1 desktop coords.</summary>
    private (double x, double y)? Normalize(Point pt) => _viewport.ToDesktop(pt.X * _dpi, pt.Y * _dpi);

    private static string? MouseBtn(MouseButton b) => b switch
    {
        MouseButton.Left => "left",
        MouseButton.Right => "right",
        MouseButton.Middle => "middle",
        _ => null,
    };

    private void SendMouse(string action, string button, double x, double y) =>
        Send(new JsonObject
        {
            ["type"] = "mouse",
            ["action"] = action,
            ["button"] = button,
            ["x"] = x,
            ["y"] = y,
        });

    private void SendPointer(Point at)
    {
        if ((DateTime.UtcNow - _lastMoveSent) < MoveInterval) return;
        if (Normalize(at) is not { } n) return;
        _lastMoveSent = DateTime.UtcNow;
        Send(new JsonObject { ["type"] = "mouse", ["action"] = "move", ["x"] = n.x, ["y"] = n.y });
    }

    private void Screen_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (MouseBtn(e.ChangedButton) is not { } btn) return;
        FocusScreen(); // keep keystrokes going to the remote PC
        if (Normalize(e.GetPosition(ScreenArea)) is not { } n) return;
        ScreenArea.CaptureMouse();
        SendMouse("down", btn, n.x, n.y);
        e.Handled = true;
    }

    private void Screen_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (MouseBtn(e.ChangedButton) is not { } btn) return;
        ScreenArea.ReleaseMouseCapture();
        if (Normalize(e.GetPosition(ScreenArea)) is { } n) SendMouse("up", btn, n.x, n.y);
        e.Handled = true;
    }

    private void Screen_MouseMove(object sender, MouseEventArgs e)
    {
        var at = e.GetPosition(ScreenArea);
        if (_fullScreen) ShowOrHideBar(at);
        if (!_panTimer.IsEnabled && EdgeVelocity(at) != (0, 0))
        {
            _lastPanTick = DateTime.UtcNow;
            _panTimer.Start();
        }
        SendPointer(at);
    }

    private void Screen_MouseLeave(object sender, MouseEventArgs e)
    {
        // Out of the window the edge no longer pans, unless a button is held (see EdgePanTick).
        if (!ScreenArea.IsMouseCaptured) _edgeSince = null;
    }

    private void Screen_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        // One wheel notch = 120; protocol dy is in notches, positive = up.
        Send(new JsonObject { ["type"] = "scroll", ["dx"] = 0.0, ["dy"] = e.Delta / 120.0 });
        e.Handled = true;
    }

    // ---------------- keyboard ----------------

    /// <summary>WPF <see cref="Key"/> → the protocol's key code, or null if unmapped.</summary>
    private static string? MapKey(Key key)
    {
        if (key >= Key.A && key <= Key.Z) return key.ToString();                       // "A".."Z"
        if (key >= Key.D0 && key <= Key.D9) return ((char)('0' + (key - Key.D0))).ToString();
        if (key >= Key.NumPad0 && key <= Key.NumPad9) return ((char)('0' + (key - Key.NumPad0))).ToString();
        if (key >= Key.F1 && key <= Key.F12) return "F" + (key - Key.F1 + 1);
        return key switch
        {
            Key.Enter => "ENTER",
            Key.Escape => "ESC",
            Key.Tab => "TAB",
            Key.Space => "SPACE",
            Key.Back => "BACKSPACE",
            Key.Delete => "DELETE",
            Key.Insert => "INSERT",
            Key.Home => "HOME",
            Key.End => "END",
            Key.PageUp => "PGUP",
            Key.PageDown => "PGDN",
            Key.Up => "UP",
            Key.Down => "DOWN",
            Key.Left => "LEFT",
            Key.Right => "RIGHT",
            Key.LWin or Key.RWin => "WIN",
            Key.LeftCtrl or Key.RightCtrl => "CTRL",
            Key.LeftAlt or Key.RightAlt => "ALT",
            Key.LeftShift or Key.RightShift => "SHIFT",
            Key.CapsLock => "CAPS",
            Key.PrintScreen => "PRINTSCREEN",
            Key.Apps => "MENU",
            _ => null,
        };
    }

    private static bool IsModifierCode(string code) => code is "CTRL" or "ALT" or "SHIFT" or "WIN";

    private void SendKey(string code, string action) =>
        Send(new JsonObject { ["type"] = "key", ["code"] = code, ["action"] = action });

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        // Alt-combos arrive as Key.System with the real key in SystemKey.
        var key = e.Key == Key.System ? e.SystemKey : e.Key;

        // Ctrl+Alt+Break, as in Microsoft's Remote Desktop, is this window's own.
        // Break is Pause with Ctrl held.
        if (key is Key.Cancel or Key.Pause &&
            (Keyboard.Modifiers & (ModifierKeys.Control | ModifierKeys.Alt)) == (ModifierKeys.Control | ModifierKeys.Alt))
        {
            SetFullScreen(!_fullScreen);
            e.Handled = true;
            return;
        }

        var code = MapKey(key);

        // Any key with a protocol code (letters, digits, space, navigation,
        // function keys, modifiers) is forwarded as a raw key event. Because the
        // physical Ctrl/Alt/Shift/Win are forwarded too, the host reproduces the
        // right case, symbols and shortcuts using its own keyboard layout.
        // Unmapped keys (punctuation, dead keys, IME) fall through to TextInput.
        if (code == null) return;

        // A held modifier shouldn't spam down events; real auto-repeat on a
        // character or navigation key (e.g. holding an arrow) is wanted. In
        // full screen a modifier may have gone already, ahead of a key taken
        // from Windows (see OnTakenKey).
        if (IsModifierCode(code) && (e.IsRepeat || _downKeys.Contains(code))) { e.Handled = true; return; }

        SendKey(code, "down");
        _downKeys.Add(code);
        e.Handled = true;
    }

    private void Window_PreviewKeyUp(object sender, KeyEventArgs e)
    {
        var key = e.Key == Key.System ? e.SystemKey : e.Key;
        var code = MapKey(key);
        if (code == null) return;
        if (_downKeys.Remove(code)) // only release keys we actually pressed down
        {
            SendKey(code, "up");
            e.Handled = true;
        }
    }

    private void Window_PreviewTextInput(object sender, TextCompositionEventArgs e)
    {
        var text = e.Text;
        if (string.IsNullOrEmpty(text)) return;
        if (text.Length == 1 && char.IsControl(text[0])) return; // control chars go via key events
        Send(new JsonObject { ["type"] = "text", ["text"] = text });
        e.Handled = true;
    }

    private void Window_Deactivated(object sender, EventArgs e)
    {
        // Release anything still held so the remote doesn't get a stuck modifier.
        foreach (var code in _downKeys) SendKey(code, "up");
        _downKeys.Clear();
        _panTimer.Stop();
        _edgeSince = null;
    }

    private void Cad_Click(object sender, RoutedEventArgs e)
    {
        Send(new JsonObject { ["type"] = "cad" });
        FocusScreen();
    }

    // Ctrl+C and Ctrl+V in this window go to the remote PC like any other key,
    // so they copy and paste on *its* clipboard. These buttons cross over.
    private void Copy_Click(object sender, RoutedEventArgs e)
    {
        StartClipRequest(new JsonObject { ["type"] = "clip-get" });
        Focus(); Keyboard.Focus(this);
    }

    private async void Paste_Click(object sender, RoutedEventArgs e)
    {
        Focus(); Keyboard.Focus(this);
        if (_clipReq != null) return;
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
        var (fitText, fitHtml, error) = ClipboardFormat.Fit(text, clip.Html);
        if (error != null || string.IsNullOrEmpty(fitText))
        {
            Toast.Show(error ?? "This PC's clipboard has no text to paste.");
            return;
        }
        var request = new JsonObject { ["type"] = "clip-set", ["text"] = fitText, ["paste"] = true };
        if (fitHtml != null) request["html"] = fitHtml;
        if (clip.Sensitive) request["sensitive"] = true;
        StartClipRequest(request);
    }

    private void StartClipRequest(JsonObject request)
    {
        if (_link.Away) return;
        _clipReq = (++_clipCounter).ToString();
        request["reqId"] = _clipReq;
        Send(request);
        CopyButton.IsEnabled = PasteButton.IsEnabled = false;
        _clipTimeout.Start();
    }

    /// <summary>Whether this is the answer to the request in flight, which it then ends.</summary>
    private bool EndClipRequest(JsonNode msg)
    {
        if (_clipReq == null || ClipboardService.Str(msg["reqId"]) != _clipReq) return false;
        EndClipRequest();
        return true;
    }

    private void EndClipRequest()
    {
        _clipReq = null;
        _clipTimeout.Stop();
        CopyButton.IsEnabled = PasteButton.IsEnabled = true;
    }

    /// <summary>The remote PC's answer to Copy: put it on this PC's clipboard.</summary>
    public void OnClipResult(JsonNode msg)
    {
        if (EndClipRequest(msg)) _ = ViewerClipboard.ApplyCopyAsync(this, _name, msg);
    }

    /// <summary>The remote PC's answer to Paste.</summary>
    public void OnPasteResult(JsonNode msg)
    {
        if (EndClipRequest(msg) && ClipboardService.Str(msg["error"]) is { } error) Toast.Show(error);
    }
}
