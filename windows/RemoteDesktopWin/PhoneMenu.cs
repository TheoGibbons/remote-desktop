using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// The decisions behind the phone viewer's title-bar menu and window size,
// kept free of WPF types so RemoteDesktopWin.PhoneViewTests can compile this
// file in and run them without a window.

/// <summary>
/// When the "⋯" menu is open. It opens once the pointer has rested on the
/// button, or at a click, and closes a little after the pointer has left both
/// the button and the menu. The window tells it what the pointer does and
/// calls <see cref="Tick"/> when <see cref="NextDue"/> comes round; the clock
/// is always passed in.
/// </summary>
internal sealed class MenuHover
{
    public static readonly TimeSpan OpenDelay = TimeSpan.FromMilliseconds(150);
    public static readonly TimeSpan CloseDelay = TimeSpan.FromMilliseconds(350);
    /// <summary>A click this soon after the menu opened by itself was meant to
    /// open it, so it doesn't close it again.</summary>
    public static readonly TimeSpan ClickGrace = TimeSpan.FromMilliseconds(600);

    private bool _onButton, _onMenu, _dragging;
    private DateTime? _openAt, _closeAt;
    private DateTime _openedAt;

    public bool IsOpen { get; private set; }

    /// <summary>When <see cref="Tick"/> next has something to do, if ever.</summary>
    public DateTime? NextDue => _openAt ?? (_dragging ? null : _closeAt);

    public void PointerOnButton(bool on, DateTime now)
    {
        if (_onButton == on) return;
        bool wasOver = Over;
        _onButton = on;
        _openAt = on && !IsOpen ? now + OpenDelay : null;
        PointerMoved(wasOver, now);
    }

    public void PointerOnMenu(bool on, DateTime now)
    {
        if (_onMenu == on) return;
        bool wasOver = Over;
        _onMenu = on;
        PointerMoved(wasOver, now);
    }

    /// <summary>A click on the button: opens the menu, or closes one that has
    /// been open a while.</summary>
    public void Click(DateTime now)
    {
        if (!IsOpen) Open(now);
        else if (now - _openedAt >= ClickGrace) Dismiss();
    }

    /// <summary>A slider in the menu is being dragged. The pointer may wander
    /// off the menu without closing it.</summary>
    public void DragStarted() => _dragging = true;

    public void DragEnded(DateTime now)
    {
        if (!_dragging) return;
        _dragging = false;
        // The countdown starts when the drag ends, not when the pointer left.
        if (_closeAt != null) _closeAt = now + CloseDelay;
    }

    /// <summary>Closes at once: Esc, or the window lost focus, moved or was resized.</summary>
    public void Dismiss()
    {
        IsOpen = false;
        // The menu is gone, so the pointer is no longer on it, whether or not
        // the window gets to say so.
        _onMenu = false;
        _dragging = false;
        _openAt = _closeAt = null;
    }

    /// <summary>Opens or closes the menu if its time has come.</summary>
    public void Tick(DateTime now)
    {
        if (_openAt is { } open && now >= open)
        {
            _openAt = null;
            if (_onButton && !IsOpen) Open(now);
        }
        if (_closeAt is { } close && now >= close && !_dragging) Dismiss();
    }

    private bool Over => _onButton || _onMenu;

    private void Open(DateTime now)
    {
        IsOpen = true;
        _openedAt = now;
        _openAt = _closeAt = null;
    }

    // The countdown to closing starts when the pointer leaves. A menu opened
    // without the pointer on it (by UI Automation, or a touch) has nothing to
    // leave, so it stays until it is dismissed or clicked shut.
    private void PointerMoved(bool wasOver, DateTime now)
    {
        if (Over) _closeAt = null;
        else if (wasOver && IsOpen) _closeAt = now + CloseDelay;
    }
}

/// <summary>A window's place and size, or a monitor's work area.</summary>
internal readonly record struct FitRect(double Left, double Top, double Width, double Height)
{
    public double Right => Left + Width;
    public double Bottom => Top + Height;
}

/// <summary>
/// Sizes the viewer window round the phone's picture. The window is fitted
/// when the first picture arrives and again only when the picture's shape
/// changes, so a size the user chose survives.
/// </summary>
internal static class PhoneFit
{
    public const double MinWindowWidth = 280;
    /// <summary>How tall the window starts out for a phone held upright.</summary>
    public const double DefaultWindowHeight = 820;
    /// <summary>Room left above and below a window at its starting size.</summary>
    public const double DefaultMargin = 60;

    /// <summary>Whether two picture sizes have the same shape. A phone sends
    /// the same shape at different sizes as its stream changes scale.</summary>
    public static bool SameShape(int w1, int h1, int w2, int h2)
    {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false;
        double a = (double)w1 / h1, b = (double)w2 / h2;
        return Math.Abs(a - b) <= 0.01 * Math.Max(a, b);
    }

    /// <summary>The longer side of a picture as it is drawn, letterboxed, in a
    /// host of the given size.</summary>
    public static double ShownLongSide(int pictureW, int pictureH, double hostW, double hostH)
    {
        if (pictureW <= 0 || pictureH <= 0 || hostW <= 0 || hostH <= 0) return 0;
        double scale = Math.Min(hostW / pictureW, hostH / pictureH);
        return Math.Max(pictureW, pictureH) * scale;
    }

    /// <summary>
    /// Where the window goes for a picture of this shape.
    /// </summary>
    /// <param name="longSide">The picture's longer side on screen before it
    /// changed shape, which it keeps, so a phone that turns stays the same
    /// size. Zero for the first picture.</param>
    /// <param name="frameW">Window width less the picture host's width.</param>
    /// <param name="frameH">Window height less the picture host's height.</param>
    /// <param name="window">The window now.</param>
    /// <param name="work">The work area of the monitor it is on.</param>
    public static FitRect Fit(int pictureW, int pictureH, double longSide, double frameW, double frameH,
        FitRect window, FitRect work)
    {
        if (pictureW <= 0 || pictureH <= 0) return window;
        if (longSide <= 0)
            longSide = Math.Min(DefaultWindowHeight, work.Height - DefaultMargin) - frameH;

        double w, h;
        if (pictureH >= pictureW) { h = longSide; w = longSide * pictureW / pictureH; }
        else { w = longSide; h = longSide * pictureH / pictureW; }

        // Never larger than the work area: shrink the picture, keeping its shape.
        double roomW = Math.Max(1, work.Width - frameW), roomH = Math.Max(1, work.Height - frameH);
        double shrink = Math.Min(1, Math.Min(roomW / w, roomH / h));
        w *= shrink;
        h *= shrink;

        // The title bar needs its width whatever the picture's; a very narrow
        // picture is centred in it.
        double width = Math.Max(MinWindowWidth, w + frameW);
        double height = h + frameH;

        // Stay where the user put it, moved only as far as keeps it on screen.
        double left = Math.Max(work.Left, Math.Min(window.Left, work.Right - width));
        double top = Math.Max(work.Top, Math.Min(window.Top, work.Bottom - height));
        return new FitRect(left, top, width, height);
    }
}

/// <summary>
/// Paces the messages a dragged slider sends: at most one per interval while
/// it moves, and always the value it was let go at.
/// </summary>
internal sealed class SendThrottle(TimeSpan interval)
{
    private DateTime? _lastSent;
    private int? _sent, _held;

    /// <summary>The slider moved to <paramref name="value"/>. Returns it if it
    /// should be sent now; otherwise it is held for <see cref="Finish"/>.</summary>
    public int? Offer(int value, DateTime now)
    {
        if (value == _sent) { _held = null; return null; }
        if (_lastSent is { } last && now - last < interval) { _held = value; return null; }
        return Send(value, now);
    }

    /// <summary>The drag ended. Returns the last value if the phone hasn't had it.</summary>
    public int? Finish(DateTime now)
    {
        if (_held is not { } value) return null;
        return Send(value, now);
    }

    private int Send(int value, DateTime now)
    {
        _lastSent = now;
        _sent = value;
        _held = null;
        return value;
    }
}

/// <summary>
/// What a phone says about itself in <c>phone-state</c>: its media volume, how
/// it is turned, and which of the menu's rows it can act on right now. Phones
/// on v1.0.53 and earlier never send one.
/// </summary>
internal sealed record PhoneState(int Volume, int VolumeMax, bool Muted, string Rotation, IReadOnlySet<string> Can)
{
    public const string Auto = "auto", Portrait = "portrait", Landscape = "landscape";

    /// <summary>Reads a <c>phone-state</c> message, or null if it makes no
    /// sense. Anything the phone leaves out of <c>can</c> is not offered.</summary>
    public static PhoneState? Parse(JsonObject msg)
    {
        try
        {
            var can = new HashSet<string>();
            if (msg["can"] is JsonArray list)
                foreach (var item in list)
                    if (item is JsonValue v && v.TryGetValue<string>(out var name)) can.Add(name);

            int max = Int(msg["volumeMax"]) ?? 0;
            int volume = Int(msg["volume"]) ?? 0;
            if (max <= 0) can.Remove("volume");
            volume = Math.Clamp(volume, 0, Math.Max(0, max));

            var rotation = msg["rotation"] is JsonValue r && r.TryGetValue<string>(out var s) ? s : Auto;
            if (rotation is not (Auto or Portrait or Landscape)) rotation = Auto;

            bool muted = msg["muted"] is JsonValue m && m.TryGetValue<bool>(out var b) && b;
            return new PhoneState(volume, Math.Max(0, max), muted, rotation, can);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static int? Int(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<int>(out var i)) return i;
        if (v.TryGetValue<double>(out var d) && d is >= int.MinValue and <= int.MaxValue) return (int)d;
        return null;
    }
}
