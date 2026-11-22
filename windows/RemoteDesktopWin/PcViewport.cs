namespace RemoteDesktopWin;

/// <summary>
/// What part of a remote desktop the PC viewer shows, and how large. The view
/// is measured in pixels of this screen, everything else in pixels of the
/// remote desktop, and the zoom is screen pixels per desktop pixel, so 1 is
/// 1:1. Plain arithmetic, so it is tested without a window.
/// </summary>
public sealed class PcViewport
{
    public const double MaxZoom = 4.0;
    /// <summary>Asked for around what is visible, so a short pan needs no round trip.</summary>
    public const double Margin = 0.15;
    /// <summary>A zoom this far from the one last asked for asks again: the pixel density no longer fits.</summary>
    public const double ZoomTolerance = 0.15;
    /// <summary>Edge panning at its fastest, with the pointer right at the edge, in screen pixels a second.</summary>
    public const double MaxPanSpeed = 2400;

    /// <summary>What to ask the PC to stream: a desktop rect as fractions of the
    /// desktop, and the pixel size worth delivering it at (as <c>view-region</c>).</summary>
    public readonly record struct Request(double X, double Y, double W, double H, int OutW, int OutH);

    public double DeskW { get; private set; }
    public double DeskH { get; private set; }
    public double ViewW { get; private set; }
    public double ViewH { get; private set; }
    public double Zoom { get; private set; } = 1;
    /// <summary>The desktop point at the view's top-left corner. On an axis the
    /// desktop does not fill it is negative, which centres the desktop.</summary>
    public double X { get; private set; }
    public double Y { get; private set; }
    /// <summary>Following the view's size at whatever zoom fits the whole desktop.</summary>
    public bool Fitting { get; private set; } = true;

    public bool Ready => DeskW > 0 && DeskH > 0 && ViewW > 0 && ViewH > 0;
    public double FitZoom => Ready ? Math.Min(ViewW / DeskW, ViewH / DeskH) : 1;
    /// <summary>The slider's ends: no smaller than fits (or 1:1, for a desktop smaller than the view).</summary>
    public double MinZoom => Math.Min(FitZoom, 1);
    public double TopZoom => Math.Max(MaxZoom, FitZoom);

    public void SetDesktop(double w, double h)
    {
        if (w == DeskW && h == DeskH) return;
        DeskW = w;
        DeskH = h;
        Settle();
    }

    /// <summary>The view changed size. What was in its middle stays there.</summary>
    public void SetView(double w, double h)
    {
        if (w == ViewW && h == ViewH) return;
        double cx = X + ViewW / 2 / Zoom, cy = Y + ViewH / 2 / Zoom;
        ViewW = w;
        ViewH = h;
        X = cx - ViewW / 2 / Zoom;
        Y = cy - ViewH / 2 / Zoom;
        Settle();
    }

    public void Fit()
    {
        Fitting = true;
        Settle();
    }

    /// <summary>Zoom, keeping the desktop point under this point of the view where it is.</summary>
    public void ZoomTo(double zoom, double anchorX, double anchorY)
    {
        if (!Ready) return;
        double dx = X + anchorX / Zoom, dy = Y + anchorY / Zoom;
        Zoom = Math.Clamp(zoom, MinZoom, TopZoom);
        Fitting = Math.Abs(Zoom - FitZoom) < 1e-9;
        X = dx - anchorX / Zoom;
        Y = dy - anchorY / Zoom;
        Clamp();
    }

    public void ZoomAroundCentre(double zoom) => ZoomTo(zoom, ViewW / 2, ViewH / 2);

    private void Settle()
    {
        if (!Ready) return;
        if (Fitting) Zoom = FitZoom;
        Zoom = Math.Clamp(Zoom, MinZoom, TopZoom);
        Clamp();
    }

    private void Clamp()
    {
        X = ClampAxis(X, ViewW / Zoom, DeskW);
        Y = ClampAxis(Y, ViewH / Zoom, DeskH);
    }

    /// <summary>A desktop narrower than the view is centred; a wider one stays covering it.</summary>
    private static double ClampAxis(double at, double seen, double size) =>
        seen >= size ? (size - seen) / 2 : Math.Clamp(at, 0, size - seen);

    /// <summary>Moves the view by this many screen pixels, as far as the desktop goes.</summary>
    /// <returns>Whether it moved at all.</returns>
    public bool PanBy(double dx, double dy)
    {
        if (!Ready) return false;
        double x = X, y = Y;
        X += dx / Zoom;
        Y += dy / Zoom;
        Clamp();
        return X != x || Y != y;
    }

    /// <summary>Whether there is more desktop beyond that side of the view.</summary>
    public bool CanPan(int dx, int dy)
    {
        if (!Ready) return false;
        const double slack = 1e-6;
        double seenW = ViewW / Zoom, seenH = ViewH / Zoom;
        if (dx < 0) return seenW < DeskW && X > slack;
        if (dx > 0) return seenW < DeskW && X + seenW < DeskW - slack;
        if (dy < 0) return seenH < DeskH && Y > slack;
        return dy > 0 && seenH < DeskH && Y + seenH < DeskH - slack;
    }

    /// <summary>
    /// How fast to pan with the pointer here: faster the nearer the edge, and
    /// nothing towards a side with no more desktop, so the pointer can leave.
    /// A pointer past the edge (the mouse is held while dragging) pans at full speed.
    /// </summary>
    /// <param name="zone">How far from an edge panning starts, in screen pixels.</param>
    /// <param name="upAllowed">False where the top edge is kept for something else.</param>
    public (double X, double Y) EdgeVelocity(double px, double py, double zone, bool upAllowed = true)
    {
        if (!Ready || zone <= 0) return (0, 0);
        return (Axis(px, ViewW, zone, CanPan(-1, 0), CanPan(1, 0)),
                Axis(py, ViewH, zone, upAllowed && CanPan(0, -1), CanPan(0, 1)));
    }

    private static double Axis(double at, double size, double zone, bool towardStart, bool towardEnd)
    {
        if (towardStart && at < zone) return -Speed((zone - at) / zone);
        if (towardEnd && at > size - zone) return Speed((at - (size - zone)) / zone);
        return 0;
    }

    private static double Speed(double depth)
    {
        depth = Math.Clamp(depth, 0, 1);
        return MaxPanSpeed * depth * depth;
    }

    /// <summary>A point of the view as fractions of the desktop; null off the desktop.</summary>
    public (double X, double Y)? ToDesktop(double viewX, double viewY)
    {
        if (!Ready) return null;
        double nx = (X + viewX / Zoom) / DeskW, ny = (Y + viewY / Zoom) / DeskH;
        return nx is < 0 or > 1 || ny is < 0 or > 1 ? null : (nx, ny);
    }

    /// <summary>Where a desktop rect is drawn, in screen pixels of the view.</summary>
    public (double X, double Y, double W, double H) ToView(double x, double y, double w, double h) =>
        ((x - X) * Zoom, (y - Y) * Zoom, w * Zoom, h * Zoom);

    /// <summary>The part of the desktop in view, in desktop pixels.</summary>
    public (double X, double Y, double W, double H) Visible
    {
        get
        {
            double l = Math.Max(0, X), t = Math.Max(0, Y);
            double r = Math.Min(DeskW, X + ViewW / Zoom), b = Math.Min(DeskH, Y + ViewH / Zoom);
            return (l, t, Math.Max(0, r - l), Math.Max(0, b - t));
        }
    }

    /// <summary>
    /// What is visible plus a margin, slid back inside the desktop rather than
    /// cut short, so panning up to an edge keeps the size the PC can carry
    /// across; and one image pixel per screen pixel, the most this screen can
    /// use, never more than the desktop holds.
    /// </summary>
    public Request? Want()
    {
        if (!Ready) return null;
        var (l, t, w, h) = Visible;
        if (w < 1 || h < 1) return null;
        double rw = Math.Min(DeskW, w * (1 + 2 * Margin)), rh = Math.Min(DeskH, h * (1 + 2 * Margin));
        double rx = Math.Clamp(l - (rw - w) / 2, 0, DeskW - rw), ry = Math.Clamp(t - (rh - h) / 2, 0, DeskH - rh);
        int outW = (int)Math.Clamp(Math.Ceiling(rw * Zoom), 16, Math.Max(16, Math.Floor(rw)));
        int outH = (int)Math.Clamp(Math.Ceiling(rh * Zoom), 16, Math.Max(16, Math.Floor(rh)));
        return new Request(rx / DeskW, ry / DeskH, rw / DeskW, rh / DeskH, outW, outH);
    }

    /// <summary>
    /// Whether to ask the PC again: the view has gone past what it streams, or
    /// the zoom has moved well away from the one it was asked for. Every
    /// request costs the PC a fresh picture, so a pan inside the margin does not.
    /// </summary>
    /// <param name="askedZoom">The zoom of the last request; 0 if none.</param>
    public bool NeedsAsking(double sx, double sy, double sw, double sh, double askedZoom)
    {
        if (!Ready) return false;
        if (askedZoom <= 0 || Math.Abs(Zoom / askedZoom - 1) >= ZoomTolerance) return true;
        var (l, t, w, h) = Visible;
        const double slack = 0.5;
        return l < sx - slack || t < sy - slack || l + w > sx + sw + slack || t + h > sy + sh + slack;
    }
}
