using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// Streams the Windows virtual desktop (all monitors stitched, exactly as
/// Windows arranges them) as **dirty-rect patches**: only changed regions are
/// sent, as JPEG tiles (binary frame type 3). A static screen costs (near)
/// zero bandwidth, which in turn makes native resolution and higher JPEG
/// quality affordable.
///
/// Capture comes from an <see cref="ICaptureSource"/>: DXGI Desktop
/// Duplication when available (the OS reports dirty rects, frames stay on the
/// GPU until mapped), with automatic fallback to GDI + frame diffing — and a
/// periodic retry of DXGI while on the fallback.
///
/// Recovery model: every patch carries a sequence number; patches may be
/// dropped under backpressure (sender backlog or a slow relay peer), which
/// only leaves regions stale — tiles are absolute pixel content, so there is
/// no codec-style corruption. Viewers detect the gap and ask for a keyframe
/// (`request-keyframe`); keyframes also go out on start, on resize, and
/// periodically as a safety net while changes are being streamed.
/// </summary>
public class ScreenStreamer
{
    private const int KeyframeIntervalMs = 10_000;
    private const int DxgiRetryMs = 10_000;
    // Above this fraction of changed area just send a keyframe: one big JPEG
    // beats many tiles, and it resets staleness for free.
    private const double KeyframeAreaFraction = 0.5;
    private const int MaxRects = 64;

    private readonly WsClient _ws;
    private CancellationTokenSource? _cts;
    private readonly ImageCodecInfo _jpegCodec;

    public bool IsStreaming => _cts != null && !_cts.IsCancellationRequested;
    // Checked before every send: patches are broadcast to the whole session and
    // any key holder can decrypt them, so the stream must pause while an
    // unapproved device is present (see PROTOCOL.md, "Device authentication").
    public Func<bool>? Gate { get; set; }
    public int Fps { get; set; } = 12;
    public long JpegQuality { get; set; } = 80;
    public int MaxWidth { get; set; } = 0; // 0 = stream at native resolution

    public event Action<bool>? StreamingChanged;

    private uint _seq;
    private volatile bool _keyframeRequested;

    public ScreenStreamer(WsClient ws)
    {
        _ws = ws;
        _jpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
    }

    /// <summary>Ask for a full frame (new viewer joined or one detected a seq gap).</summary>
    public void RequestKeyframe() => _keyframeRequested = true;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int nIndex);

    private const int SM_XVIRTUALSCREEN = 76;
    private const int SM_YVIRTUALSCREEN = 77;
    private const int SM_CXVIRTUALSCREEN = 78;
    private const int SM_CYVIRTUALSCREEN = 79;

    // Bounding box of all monitors, in physical pixels (the "stitched" desktop).
    private static Rectangle VirtualScreen() => new(
        GetSystemMetrics(SM_XVIRTUALSCREEN),
        GetSystemMetrics(SM_YVIRTUALSCREEN),
        GetSystemMetrics(SM_CXVIRTUALSCREEN),
        GetSystemMetrics(SM_CYVIRTUALSCREEN));

    public void Start()
    {
        if (IsStreaming) return;
        _cts = new CancellationTokenSource();
        _keyframeRequested = true;
        _ = Task.Run(() => CaptureLoop(_cts.Token));
        StreamingChanged?.Invoke(true);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
        StreamingChanged?.Invoke(false);
    }

    private static ICaptureSource CreateSource(Rectangle vs, out bool isDxgi)
    {
        try
        {
            var dxgi = new DxgiCaptureSource(vs);
            isDxgi = true;
            return dxgi;
        }
        catch
        {
            // Duplication unavailable (secure desktop, RDP session, rotated
            // output, no compatible GPU) — GDI works everywhere.
            isDxgi = false;
            return new GdiCaptureSource(vs);
        }
    }

    private async Task CaptureLoop(CancellationToken ct)
    {
        Rectangle lastBounds = Rectangle.Empty;
        Bitmap? surface = null;      // native stitched desktop, kept current by the source
        Bitmap? scaled = null;       // downscaled copy when MaxWidth clamps
        ICaptureSource? source = null;
        bool sourceIsDxgi = false;
        long lastDxgiRetry = 0;
        long lastKeyframeAt = 0;
        bool deltaSinceKeyframe = false;
        bool forceKeyframe = true;
        var dirty = new List<Rectangle>();

        using var encParams = new EncoderParameters(1);
        // Tiles are small, so quality is affordable; below ~70 text smears.
        encParams.Param[0] = new EncoderParameter(
            System.Drawing.Imaging.Encoder.Quality, Math.Clamp(JpegQuality, 70, 95));

        try
        {
            while (!ct.IsCancellationRequested)
            {
                if (Gate is { } gate && !gate())
                {
                    forceKeyframe = true; // resume with a full frame
                    await Task.Delay(250, ct);
                    continue;
                }

                var frameStart = Environment.TickCount64;
                var vs = VirtualScreen();

                double scale = MaxWidth > 0 && vs.Width > MaxWidth ? (double)MaxWidth / vs.Width : 1.0;
                int outW = (int)(vs.Width * scale);
                int outH = (int)(vs.Height * scale);

                if (vs != lastBounds || source == null || surface == null)
                {
                    source?.Dispose();
                    surface?.Dispose();
                    scaled?.Dispose();
                    scaled = null;
                    surface = new Bitmap(vs.Width, vs.Height, PixelFormat.Format32bppRgb);
                    if (scale < 1.0) scaled = new Bitmap(outW, outH, PixelFormat.Format32bppRgb);
                    source = CreateSource(vs, out sourceIsDxgi);
                    lastDxgiRetry = Environment.TickCount64;
                    lastBounds = vs;
                    forceKeyframe = true;
                    _ws.SendJson(new JsonObject
                    {
                        ["type"] = "screen-info",
                        ["width"] = outW,
                        ["height"] = outH,
                    });
                }

                // Prefer DXGI: while on the GDI fallback, retry it occasionally
                // (e.g. after leaving the secure desktop).
                if (!sourceIsDxgi && Environment.TickCount64 - lastDxgiRetry > DxgiRetryMs)
                {
                    lastDxgiRetry = Environment.TickCount64;
                    try
                    {
                        var dxgi = new DxgiCaptureSource(vs);
                        source.Dispose();
                        source = dxgi;
                        sourceIsDxgi = true;
                        forceKeyframe = true; // new source has no diff history
                    }
                    catch { }
                }

                dirty.Clear();
                bool captured;
                try
                {
                    captured = source.CaptureInto(surface, dirty);
                }
                catch
                {
                    // Source lost (mode change, duplication access lost):
                    // recreate on the next tick. The small delay keeps a
                    // repeatedly-failing source from spinning the loop.
                    source.Dispose();
                    source = null;
                    await Task.Delay(250, ct);
                    continue;
                }
                if (!captured)
                {
                    await Task.Delay(500, ct);
                    continue;
                }

                var now = Environment.TickCount64;
                bool keyframe = forceKeyframe || _keyframeRequested
                    || (deltaSinceKeyframe && now - lastKeyframeAt > KeyframeIntervalMs);

                var fullRect = new Rectangle(0, 0, vs.Width, vs.Height);
                List<Rectangle> rects;
                if (keyframe)
                {
                    rects = new List<Rectangle> { fullRect };
                }
                else
                {
                    rects = Normalize(dirty, vs.Size);
                    if (rects.Count == 0)
                    {
                        // Nothing changed: send nothing, wait for the next tick.
                        await PaceAsync(frameStart, ct);
                        continue;
                    }
                    if (rects.Sum(r => (long)r.Width * r.Height) >
                        (long)vs.Width * vs.Height * KeyframeAreaFraction)
                    {
                        keyframe = true;
                        rects = new List<Rectangle> { fullRect };
                    }
                }

                var sendSurface = surface;
                if (scaled != null)
                {
                    rects = RescaleInto(surface, scaled, rects, scale);
                    sendSurface = scaled;
                    if (rects.Count == 0) { await PaceAsync(frameStart, ct); continue; }
                }

                // Seq increments even if the send is dropped: the resulting gap
                // is what tells the viewer to request a keyframe.
                var payload = BuildPatch(sendSurface, rects, keyframe, encParams);
                _ws.SendBinary(Crypto.ChPatch, payload);

                if (keyframe)
                {
                    lastKeyframeAt = now;
                    deltaSinceKeyframe = false;
                    _keyframeRequested = false;
                    forceKeyframe = false;
                }
                else
                {
                    deltaSinceKeyframe = true;
                }

                await PaceAsync(frameStart, ct);
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            source?.Dispose();
            surface?.Dispose();
            scaled?.Dispose();
        }
    }

    private async Task PaceAsync(long frameStart, CancellationToken ct)
    {
        int delay = Math.Max(1, 1000 / Math.Max(1, Fps) - (int)(Environment.TickCount64 - frameStart));
        await Task.Delay(delay, ct);
    }

    /// <summary>Clip to bounds, drop empties/duplicates, cap the rect count
    /// (DXGI can report many small accumulated rects).</summary>
    private static List<Rectangle> Normalize(List<Rectangle> dirty, Size bounds)
    {
        var full = new Rectangle(Point.Empty, bounds);
        var rects = new List<Rectangle>(dirty.Count);
        foreach (var d in dirty)
        {
            var r = d;
            r.Intersect(full);
            if (r.Width > 0 && r.Height > 0 && !rects.Contains(r)) rects.Add(r);
        }
        if (rects.Count > MaxRects)
        {
            int minX = rects.Min(r => r.X), minY = rects.Min(r => r.Y);
            int maxX = rects.Max(r => r.Right), maxY = rects.Max(r => r.Bottom);
            rects.Clear();
            rects.Add(new Rectangle(minX, minY, maxX - minX, maxY - minY));
        }
        return rects;
    }

    /// <summary>
    /// MaxWidth clamp: re-render the changed regions (slightly inflated so
    /// bilinear edges stay seamless) from the native surface into the scaled
    /// one and return the scaled-space rects to encode.
    /// </summary>
    private static List<Rectangle> RescaleInto(Bitmap native, Bitmap scaled, List<Rectangle> rects, double scale)
    {
        var outRects = new List<Rectangle>(rects.Count);
        var nativeBounds = new Rectangle(0, 0, native.Width, native.Height);
        var scaledBounds = new Rectangle(0, 0, scaled.Width, scaled.Height);
        using var g = Graphics.FromImage(scaled);
        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
        foreach (var r in rects)
        {
            var src = Rectangle.Inflate(r, 2, 2);
            src.Intersect(nativeBounds);
            var dest = Rectangle.FromLTRB(
                (int)Math.Floor(src.Left * scale),
                (int)Math.Floor(src.Top * scale),
                (int)Math.Ceiling(src.Right * scale),
                (int)Math.Ceiling(src.Bottom * scale));
            dest.Intersect(scaledBounds);
            if (dest.Width <= 0 || dest.Height <= 0) continue;
            g.DrawImage(native, dest, src, GraphicsUnit.Pixel);
            outRects.Add(dest);
        }
        return outRects;
    }

    /// <summary>
    /// Patch payload (before encryption):
    /// [seq u32][flags u8: bit0 keyframe][surfW u16][surfH u16][rectCount u16]
    /// then per rect: [x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes].
    /// All integers big-endian.
    /// </summary>
    private byte[] BuildPatch(Bitmap surface, List<Rectangle> rects, bool keyframe, EncoderParameters encParams)
    {
        using var ms = new MemoryStream();
        void W16(int v) { ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }
        void W32(uint v) { ms.WriteByte((byte)(v >> 24)); ms.WriteByte((byte)(v >> 16)); ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }

        W32(_seq++);
        ms.WriteByte((byte)(keyframe ? 1 : 0));
        W16(surface.Width);
        W16(surface.Height);
        W16(rects.Count);

        foreach (var r in rects)
        {
            using var tile = surface.Clone(r, PixelFormat.Format32bppRgb);
            using var tms = new MemoryStream();
            tile.Save(tms, _jpegCodec, encParams);
            W16(r.X); W16(r.Y); W16(r.Width); W16(r.Height);
            W32((uint)tms.Length);
            tms.Position = 0;
            tms.CopyTo(ms);
        }
        return ms.ToArray();
    }
}
