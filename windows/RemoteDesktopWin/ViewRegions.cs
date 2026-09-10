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

    public void AddViewer(string peerId)
    {
        lock (_lock)
        {
            if (!_viewers.ContainsKey(peerId)) _viewers[peerId] = null;
        }
    }

    public void RemoveViewer(string peerId)
    {
        lock (_lock) _viewers.Remove(peerId);
    }

    public void Clear()
    {
        lock (_lock) _viewers.Clear();
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

        int x = (int)Math.Floor(Math.Clamp(left, 0, 1) * deskW);
        int y = (int)Math.Floor(Math.Clamp(top, 0, 1) * deskH);
        int r = (int)Math.Ceiling(Math.Clamp(right, 0, 1) * deskW);
        int b = (int)Math.Ceiling(Math.Clamp(bottom, 0, 1) * deskH);

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
