using System.Drawing;
using System.Drawing.Imaging;

namespace RemoteDesktopWin;

/// <summary>
/// A screen-capture backend. The streamer owns one surface bitmap sized to the
/// stitched virtual desktop; each tick the source brings it up to date and
/// reports which regions changed.
/// </summary>
public interface ICaptureSource : IDisposable
{
    string Name { get; }

    /// <summary>
    /// Update <paramref name="surface"/> (virtual-desktop-sized, 32bppRgb)
    /// with the latest desktop content and append the changed regions, in
    /// surface coordinates, to <paramref name="dirty"/>.
    /// Returns false on a transient failure (secure desktop) — retry later.
    /// Throws when the source is lost (display mode change, duplication
    /// access lost) — dispose and recreate.
    /// </summary>
    bool CaptureInto(Bitmap surface, List<Rectangle> dirty);
}

/// <summary>
/// Fallback capture: GDI CopyFromScreen of the whole desktop, then a block
/// diff against the previous frame to synthesize dirty rects. Works everywhere
/// (including where DXGI duplication is unavailable) at a higher CPU cost.
/// </summary>
public sealed class GdiCaptureSource : ICaptureSource
{
    private const int BlockSize = 64;
    private const int MaxRects = 64;

    private readonly Rectangle _vs;
    private readonly Bitmap _prev;
    private bool _prevValid;

    public string Name => "GDI";

    public GdiCaptureSource(Rectangle vs)
    {
        _vs = vs;
        _prev = new Bitmap(vs.Width, vs.Height, PixelFormat.Format32bppRgb);
    }

    public bool CaptureInto(Bitmap surface, List<Rectangle> dirty)
    {
        try
        {
            using var g = Graphics.FromImage(surface);
            g.CopyFromScreen(_vs.X, _vs.Y, 0, 0, _vs.Size);
        }
        catch
        {
            // Transient on the secure desktop (UAC prompt, lock screen).
            return false;
        }

        if (!_prevValid)
        {
            var full = new Rectangle(0, 0, _vs.Width, _vs.Height);
            dirty.Add(full);
            SyncPrev(surface, new List<Rectangle> { full });
            _prevValid = true;
            return true;
        }

        var rects = Diff(_prev, surface);
        dirty.AddRange(rects);
        SyncPrev(surface, rects);
        return true;
    }

    /// <summary>Bring the reference frame up to date in the changed regions only.</summary>
    private void SyncPrev(Bitmap surface, List<Rectangle> rects)
    {
        if (rects.Count == 0) return;
        using var g = Graphics.FromImage(_prev);
        foreach (var r in rects) g.DrawImage(surface, r, r, GraphicsUnit.Pixel);
    }

    /// <summary>
    /// Compare two equally-sized frames on a 64px block grid and merge changed
    /// blocks into rectangles. Rows are compared with vectorized SequenceEqual,
    /// so an unchanged screen costs one linear scan.
    /// </summary>
    private static List<Rectangle> Diff(Bitmap a, Bitmap b)
    {
        int w = a.Width, h = a.Height;
        int cols = (w + BlockSize - 1) / BlockSize;
        int rows = (h + BlockSize - 1) / BlockSize;
        var dirty = new bool[rows, cols];

        var bounds = new Rectangle(0, 0, w, h);
        var da = a.LockBits(bounds, ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        var db = b.LockBits(bounds, ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        try
        {
            unsafe
            {
                for (int y = 0; y < h; y++)
                {
                    var ra = new ReadOnlySpan<byte>((byte*)da.Scan0 + (long)y * da.Stride, w * 4);
                    var rb = new ReadOnlySpan<byte>((byte*)db.Scan0 + (long)y * db.Stride, w * 4);
                    if (ra.SequenceEqual(rb)) continue;
                    int gr = y / BlockSize;
                    for (int gc = 0; gc < cols; gc++)
                    {
                        if (dirty[gr, gc]) continue;
                        int x0 = gc * BlockSize * 4;
                        int len = Math.Min(BlockSize * 4, w * 4 - x0);
                        if (!ra.Slice(x0, len).SequenceEqual(rb.Slice(x0, len)))
                            dirty[gr, gc] = true;
                    }
                }
            }
        }
        finally
        {
            a.UnlockBits(da);
            b.UnlockBits(db);
        }

        // Greedy merge: horizontal runs, extended downward while the identical
        // run repeats. Produces few, reasonably tight rectangles.
        var rects = new List<Rectangle>();
        var consumed = new bool[rows, cols];
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                if (!dirty[r, c] || consumed[r, c]) continue;
                int c2 = c;
                while (c2 + 1 < cols && dirty[r, c2 + 1] && !consumed[r, c2 + 1]) c2++;
                int r2 = r;
                while (r2 + 1 < rows && RunIsDirty(dirty, consumed, r2 + 1, c, c2)) r2++;
                for (int rr = r; rr <= r2; rr++)
                    for (int cc = c; cc <= c2; cc++)
                        consumed[rr, cc] = true;
                int x = c * BlockSize, y = r * BlockSize;
                rects.Add(new Rectangle(
                    x, y,
                    Math.Min((c2 - c + 1) * BlockSize, w - x),
                    Math.Min((r2 - r + 1) * BlockSize, h - y)));
            }
        }

        // Pathological fragmentation: fall back to one bounding box.
        if (rects.Count > MaxRects)
        {
            int minX = rects.Min(r => r.X), minY = rects.Min(r => r.Y);
            int maxX = rects.Max(r => r.Right), maxY = rects.Max(r => r.Bottom);
            rects.Clear();
            rects.Add(new Rectangle(minX, minY, maxX - minX, maxY - minY));
        }
        return rects;
    }

    private static bool RunIsDirty(bool[,] dirty, bool[,] consumed, int row, int c1, int c2)
    {
        for (int c = c1; c <= c2; c++)
            if (!dirty[row, c] || consumed[row, c]) return false;
        return true;
    }

    public void Dispose() => _prev.Dispose();
}
