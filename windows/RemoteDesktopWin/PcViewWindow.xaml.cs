using System.IO;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
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
///  - forwards real mouse input as <c>mouse</c> / <c>scroll</c> messages
///    (coordinates normalized 0..1 over the stitched desktop);
///  - forwards the physical keyboard while focused: every mapped key (letters,
///    digits, space, navigation, function and modifier keys) goes as <c>key</c>
///    down/up, so the host reproduces case and shortcuts with its own layout;
///    unmapped keys (punctuation, dead keys, IME) fall back to a <c>text</c>
///    message composed on this side.
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
    private long _lastSeq = -1;
    private bool _haveKeyframe;
    private DateTime _lastKeyframeReq;
    private bool _sizedToSurface;

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

        Send(new JsonObject { ["type"] = "start-view" }, always: true);
        Closed += (_, _) =>
        {
            _clipTimeout.Stop();
            Send(new JsonObject { ["type"] = "stop-view" }, always: true);
        };
        Loaded += (_, _) => { Focusable = true; Focus(); Keyboard.Focus(this); };
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
    /// the host hasn't approved this device yet.</summary>
    public void ResendStartView() => Send(new JsonObject { ["type"] = "start-view" }, always: true);

    /// <summary>The PC has sent its screen's size or a full picture. If it had
    /// been away, that is its answer to being asked again.</summary>
    private void Answered()
    {
        if (_link.Answered()) HintText.Text = _hint;
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
        Send(new JsonObject { ["type"] = "start-view" }, always: true);
    }

    /// <summary>The wait for the PC is over: the window says so and closes.</summary>
    void IDeviceWindow.GaveUp()
    {
        Toast.Show(_link.GaveUpText(_name), 10000);
        Close();
    }

    /// <summary>Optional: size the window to the remote desktop's aspect ratio.</summary>
    public void OnScreenInfo(int width, int height)
    {
        Answered();
        if (width <= 0 || height <= 0 || _sizedToSurface) return;
        SizeToSurface(width, height);
    }

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
    /// [seq u32][flags u8: bit0 keyframe][surfW u16][surfH u16][rectCount u16],
    /// then per rect [x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes].
    /// Tiles are absolute pixel content, so a dropped patch only leaves regions
    /// stale — keep the canvas and ask for a keyframe to catch up.
    /// </summary>
    public void OnPatch(byte[] data)
    {
        // A patch names no sender. While the PC is away none is its.
        if (_link.Away) return;
        try
        {
            int p = 1; // skip frame-type byte
            long seq = ReadU32(data, ref p);
            bool keyframe = (data[p++] & 1) != 0;
            int w = ReadU16(data, ref p);
            int h = ReadU16(data, ref p);
            int rectCount = ReadU16(data, ref p);

            if (_compose == null || _surfW != w || _surfH != h)
            {
                if (!keyframe) { RequestKeyframe(); return; } // can't composite yet
                _surfW = w; _surfH = h;
                _compose = new WriteableBitmap(w, h, 96, 96, PixelFormats.Bgr32, null);
                ScreenImage.Source = _compose;
                _haveKeyframe = false;
                if (!_sizedToSurface) SizeToSurface(w, h);
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

    private void RequestKeyframe()
    {
        if ((DateTime.UtcNow - _lastKeyframeReq).TotalMilliseconds < 2000) return;
        _lastKeyframeReq = DateTime.UtcNow;
        Send(new JsonObject { ["type"] = "request-keyframe" });
    }

    // ---------------- mouse ----------------

    /// <summary>Mouse position over the (letterboxed) image → normalized 0..1 desktop coords.</summary>
    private (double x, double y)? Normalize(Point pt)
    {
        if (_compose == null) return null;
        double iw = _compose.PixelWidth, ih = _compose.PixelHeight;
        double cw = ScreenImage.ActualWidth, ch = ScreenImage.ActualHeight;
        if (cw <= 0 || ch <= 0 || iw <= 0 || ih <= 0) return null;

        double scale = Math.Min(cw / iw, ch / ih); // Stretch=Uniform
        double dw = iw * scale, dh = ih * scale;
        double ox = (cw - dw) / 2, oy = (ch - dh) / 2;
        double nx = (pt.X - ox) / dw, ny = (pt.Y - oy) / dh;
        if (nx < 0 || nx > 1 || ny < 0 || ny > 1) return null;
        return (nx, ny);
    }

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

    private void Screen_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (MouseBtn(e.ChangedButton) is not { } btn) return;
        Focus(); Keyboard.Focus(this); // keep keystrokes going to the remote PC
        if (Normalize(e.GetPosition(ScreenImage)) is not { } n) return;
        ScreenImage.CaptureMouse();
        SendMouse("down", btn, n.x, n.y);
        e.Handled = true;
    }

    private void Screen_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (MouseBtn(e.ChangedButton) is not { } btn) return;
        ScreenImage.ReleaseMouseCapture();
        if (Normalize(e.GetPosition(ScreenImage)) is { } n) SendMouse("up", btn, n.x, n.y);
        e.Handled = true;
    }

    private void Screen_MouseMove(object sender, MouseEventArgs e)
    {
        if ((DateTime.UtcNow - _lastMoveSent) < MoveInterval) return;
        if (Normalize(e.GetPosition(ScreenImage)) is not { } n) return;
        _lastMoveSent = DateTime.UtcNow;
        Send(new JsonObject { ["type"] = "mouse", ["action"] = "move", ["x"] = n.x, ["y"] = n.y });
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
        var code = MapKey(key);

        // Any key with a protocol code (letters, digits, space, navigation,
        // function keys, modifiers) is forwarded as a raw key event. Because the
        // physical Ctrl/Alt/Shift/Win are forwarded too, the host reproduces the
        // right case, symbols and shortcuts using its own keyboard layout.
        // Unmapped keys (punctuation, dead keys, IME) fall through to TextInput.
        if (code == null) return;

        // A held modifier shouldn't spam down events; real auto-repeat on a
        // character or navigation key (e.g. holding an arrow) is wanted.
        if (IsModifierCode(code) && e.IsRepeat) { e.Handled = true; return; }

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
    }

    private void Cad_Click(object sender, RoutedEventArgs e)
    {
        Send(new JsonObject { ["type"] = "cad" });
        Focus(); Keyboard.Focus(this);
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
        if (EndClipRequest(msg)) _ = ApplyCopyAsync(msg);
    }

    private async Task ApplyCopyAsync(JsonNode msg)
    {
        // This arrives inside the socket thread's blocking Dispatcher.Invoke;
        // the question below must not hold up the relay while it waits.
        await Task.Yield();
        try
        {
            if (ClipboardService.Str(msg["error"]) is { } error)
            {
                Toast.Show(error);
                return;
            }
            if (msg["files"] is JsonArray { Count: > 0 } files)
            {
                Toast.Show($"{_name} copied {files.Count} file{(files.Count == 1 ? "" : "s")}. Use Files to transfer them.");
                return;
            }
            var (text, html, fitError) = ClipboardFormat.Fit(ClipboardService.Str(msg["text"]), ClipboardService.Str(msg["html"]));
            if (fitError != null || string.IsNullOrEmpty(text))
            {
                Toast.Show(fitError ?? $"{_name} sent nothing to copy.");
                return;
            }
            bool sensitive = ClipboardService.Flag(msg["sensitive"], false);
            // Nothing new was copied there, so ask before replacing what's here.
            if (!ClipboardService.Flag(msg["fresh"], true) && MessageBox.Show(this,
                    $"Nothing new was copied on {_name}. Use what's already on its clipboard?\n\n" +
                    (sensitive ? "(Hidden: it's marked private.)" : text.Length <= 300 ? text : text[..300] + "…"),
                    "Copy to this PC", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes)
                return;
            await ClipboardService.WriteAsync(text, html, sensitive);
            Toast.Show($"Copied from {_name}");
        }
        catch (Exception ex)
        {
            Toast.Show("Couldn't copy to this PC's clipboard: " + ex.Message);
        }
    }

    /// <summary>The remote PC's answer to Paste.</summary>
    public void OnPasteResult(JsonNode msg)
    {
        if (EndClipRequest(msg) && ClipboardService.Str(msg["error"]) is { } error) Toast.Show(error);
    }
}
