using System.Drawing;

namespace RemoteDesktopWin;

/// <summary>
/// One viewer request for the part of the desktop it can actually show, in
/// normalized desktop coordinates (0..1) plus the pixel size it wants that
/// region delivered at.
/// </summary>
public readonly record struct ViewRegion(double X, double Y, double W, double H, int OutW, int OutH);

/// <summary>
/// Tracks which peers are viewing and what each one is looking at, and resolves
/// them into the single region the streamer should send.
///
/// The point of the exercise: a phone showing a 7680x2160 desktop displays
/// about 1080 columns of it, so roughly 85% of the pixels we encode can never
/// be seen. A viewer that reports its viewport gets that region at a resolution
/// it can actually use instead.
///
/// Region streaming is only used when **every** viewer has asked for one. A
/// viewer that never sends `view-region` (an older build, or the Windows PC
/// viewer) keeps the whole-desktop stream it expects, and its presence puts
/// everyone back on that path — patches are broadcast, so there is one stream
/// for the session and it has to be one that every viewer can read.
/// </summary>
public sealed class ViewRegionTracker
{
    private readonly object _lock = new();
    private readonly Dictionary<string, ViewRegion?> _viewers = new();
    private readonly HashSet<string> _h264 = new();

    /// <summary>
    /// Every viewer can decode H.264. Like region mode this is all-or-nothing:
    /// one stream is broadcast to the session, so a single viewer that cannot
    /// decode it keeps everyone on JPEG tiles.
    /// </summary>
    public bool AllSupportH264
    {
        get
        {
            lock (_lock) return _viewers.Count > 0 && _viewers.Keys.All(_h264.Contains);
        }
    }

    public void SetH264(string peerId, bool supported)
    {
        lock (_lock)
        {
            if (supported) _h264.Add(peerId);
            else _h264.Remove(peerId);
        }
    }

    public void AddViewer(string peerId)
    {
        lock (_lock)
        {
            if (!_viewers.ContainsKey(peerId)) _viewers[peerId] = null;
        }
    }

    public void RemoveViewer(string peerId)
    {
        lock (_lock)
        {
            _viewers.Remove(peerId);
            _h264.Remove(peerId);
        }
    }

    public void Clear()
    {
        lock (_lock)
        {
            _viewers.Clear();
            _h264.Clear();
        }
    }

    /// <summary>A viewer reported its viewport. Implies it is viewing.</summary>
    public void SetRegion(string peerId, ViewRegion region)
    {
        lock (_lock) _viewers[peerId] = region;
    }

    /// <summary>
    /// The region to stream, in desktop pixels, plus the pixel size to deliver
    /// it at. False means whole-desktop mode: no viewers, or at least one that
    /// has not asked for a region.
    /// </summary>
    public bool TryResolve(int deskW, int deskH, out Rectangle region, out int outW, out int outH)
    {
        region = new Rectangle(0, 0, deskW, deskH);
        outW = deskW;
        outH = deskH;

        List<ViewRegion> wanted;
        lock (_lock)
        {
            if (_viewers.Count == 0) return false;
            if (_viewers.Values.Any(v => v == null)) return false;
            wanted = _viewers.Values.Select(v => v!.Value).ToList();
        }

        double left = wanted.Min(v => v.X);
        double top = wanted.Min(v => v.Y);
        double right = wanted.Max(v => v.X + v.W);
        double bottom = wanted.Max(v => v.Y + v.H);

        // Slide the rect back inside the desktop rather than truncating it.
        // Truncating changes its *size*, and a size change costs a keyframe on
        // the tile path and an encoder teardown on the codec path — so simply
        // panning up to an edge used to be as expensive as a zoom.
        double wantW = Math.Min(1.0, right - left);
        double wantH = Math.Min(1.0, bottom - top);
        double x0 = Math.Clamp(left, 0.0, 1.0 - wantW);
        double y0 = Math.Clamp(top, 0.0, 1.0 - wantH);

        int x = (int)Math.Floor(x0 * deskW);
        int y = (int)Math.Floor(y0 * deskH);
        int r = (int)Math.Ceiling((x0 + wantW) * deskW);
        int b = (int)Math.Ceiling((y0 + wantH) * deskH);
        r = Math.Min(r, deskW);
        b = Math.Min(b, deskH);

        // Round out to even pixels: JPEG chroma subsampling works on 2x2 blocks,
        // and an odd-sized crop makes tile edges shift colour between patches.
        x &= ~1; y &= ~1;
        r = Math.Min(deskW, r + (r & 1));
        b = Math.Min(deskH, b + (b & 1));

        if (r - x < 16 || b - y < 16) return false; // degenerate; not worth it
        region = new Rectangle(x, y, r - x, b - y);

        // Preserve the densest pixels-per-desktop-pixel any viewer asked for,
        // applied across the resolved region. Never upscale past the source.
        double density = wanted.Max(v => v.W > 0 ? v.OutW / (v.W * deskW) : 0);
        if (density <= 0) return false;
        outW = Math.Clamp((int)Math.Round(region.Width * density), 16, region.Width);
        outH = Math.Clamp((int)Math.Round(outW * (double)region.Height / region.Width), 16, region.Height);
        return true;
    }
}
