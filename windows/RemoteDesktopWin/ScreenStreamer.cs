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
/// zero bandwidth.
///
/// Capture comes from an <see cref="ICaptureSource"/>: DXGI Desktop
/// Duplication when available (the OS reports dirty rects, frames stay on the
/// GPU until mapped), with automatic fallback to GDI + frame diffing — and a
/// periodic retry of DXGI while on the fallback.
///
/// **Congestion is answered with damage, not keyframes.** When the socket lane
/// is still busy the tick is captured but not encoded, and the regions that
/// changed are accumulated in a <see cref="DamageMap"/>; the next frame that
/// does go out repaints their union. Answering a full lane with a full-screen
/// keyframe — the single most expensive frame there is — is what turns a brief
/// backlog into a permanent one, because the keyframe refills the lane it was
/// waiting on. Keyframes are therefore reserved for the cases that genuinely
/// need one: stream start, a surface or output-size change, an explicit
/// `request-keyframe`, a periodic safety net, and accumulated damage large
/// enough that one JPEG beats many tiles.
///
/// A <see cref="Ladder">quality ladder</see> then keeps frames inside what the
/// link can actually carry, stepping resolution and JPEG quality down under
/// sustained pressure and back up when the lane stays clear.
/// </summary>
public class ScreenStreamer
{
    private const int KeyframeIntervalMs = 10_000;
    private const int DxgiRetryMs = 10_000;
    // Above this fraction of changed area just send a keyframe: one big JPEG
    // beats many tiles, and it resets staleness for free.
    private const double KeyframeAreaFraction = 0.5;
    private const int MaxRects = 64;

    /// <summary>
    /// Output scale and JPEG quality by congestion level. Resolution goes
    /// before frame rate: on a desktop, a smaller sharp image that keeps up
    /// beats a native-resolution one that arrives late.
    /// </summary>
    private static readonly (double Scale, int Quality)[] Ladder =
    {
        (1.00, 95),
        (1.00, 75),
        (0.66, 75),
        (0.50, 70),
        (0.33, 65),
        (0.25, 55),
    };

    // Start partway down: the first frame goes out before any measurement
    // exists, and on a 7680x2160 desktop a native keyframe is ~1.5 MB — enough
    // on its own to bury a modest uplink for a second. Climbing up from here
    // costs a few seconds; recovering from that first stall costs far more.
    private const int StartLevel = 3;

    private const int StepDownSkips = 4;      // consecutive stalled ticks before easing off
    private const int StepUpClearSeconds = 6; // sustained clear time before trying harder
    private const int LevelDwellMs = 2500;    // every change costs a keyframe, so settle first

    // Two keyframe requests inside this window mean a viewer keeps detecting
    // gaps, i.e. something between here and there is discarding frames.
    private const int RepeatKeyframeRequestMs = 5000;

    // A level we just fell out of rarely works again a few seconds later, so
    // each retry of the same one waits longer. Without this the ladder hunts:
    // step up, stall, step down, wait out the dwell, step up again, on a ~10 s
    // cycle. Every one of those costs a keyframe and visibly changes the
    // picture, which is worse than simply sitting one rung lower.
    private const int MinLevelRetryMs = 8_000;
    private const int MaxLevelRetryMs = 60_000;

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

    // What the stream is actually doing right now, after adaptation — this is
    // what the viewer diagnostics should show, not the configured ceiling.
    public int ActiveFps => Math.Max(1, Fps / Math.Max(1, _fpsDivider));
    public int ActiveQuality => _activeQuality;
    public int ActiveWidth => _activeWidth;

    public event Action<bool>? StreamingChanged;

    /// <summary>Who is viewing and what part of the desktop each one can show.</summary>
    public ViewRegionTracker Regions { get; } = new();

    private uint _seq;
    private volatile bool _keyframeRequested;
    private long _keyframeRequestCount;
    private volatile int _activeQuality = 80;
    private volatile int _activeWidth;
    private volatile int _fpsDivider = 1;

    public ScreenStreamer(WsClient ws)
    {
        _ws = ws;
        _jpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
    }

    /// <summary>Ask for a full frame (new viewer joined or one detected a seq gap).</summary>
    public void RequestKeyframe()
    {
        _keyframeRequested = true;
        Interlocked.Increment(ref _keyframeRequestCount);
    }

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
        // Viewports belong to a streaming session; a later viewer must ask
        // again rather than inherit whatever the last one was looking at.
        Regions.Clear();
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
        Bitmap? output = null;       // cropped/scaled frame when it differs from the surface
        ICaptureSource? source = null;
        bool sourceIsDxgi = false;
        long lastDxgiRetry = 0;
        long lastKeyframeAt = 0;
        bool deltaSinceKeyframe = false;
        bool forceKeyframe = true;
        Rectangle lastRegion = Rectangle.Empty;
        int lastOutW = -1, lastOutH = -1;
        bool lastRegionMode = false;
        var dirty = new List<Rectangle>();
        var damage = new DamageMap();
        var monitors = new List<MonitorInfo>();

        // Congestion state.
        int level = StartLevel;
        long lastLevelChange = 0;
        int skipStreak = 0, clearStreak = 0;
        long lastDrops = _ws.VideoFramesDropped;
        long seenKeyframeRequests = Interlocked.Read(ref _keyframeRequestCount);
        long lastKeyframeRequestAt = 0;
        int failedLevel = -1;
        int levelRetryMs = MinLevelRetryMs;
        long levelRetryAt = 0;

        EncoderParameters? encParams = null;
        int encQuality = -1;

        try
        {
            while (!ct.IsCancellationRequested)
            {
                if (Gate is { } gate && !gate())
                {
                    // Stream paused: the viewer state is unknown, so resume with
                    // a full frame rather than deltas against a stale canvas.
                    forceKeyframe = true;
                    await Task.Delay(250, ct);
                    continue;
                }

                var frameStart = Environment.TickCount64;
                var now = frameStart;
                var vs = VirtualScreen();

                if (vs != lastBounds || source == null || surface == null)
                {
                    source?.Dispose();
                    surface?.Dispose();
                    output?.Dispose();
                    output = null;
                    surface = new Bitmap(vs.Width, vs.Height, PixelFormat.Format32bppRgb);
                    source = CreateSource(vs, out sourceIsDxgi);
                    monitors = Monitors.Enumerate(vs);
                    damage.Resize(vs.Width, vs.Height);
                    lastDxgiRetry = now;
                    lastBounds = vs;
                    lastRegion = Rectangle.Empty; // force the geometry to be re-evaluated
                    lastOutW = lastOutH = -1;
                    forceKeyframe = true;
                }

                // ---- adaptation ------------------------------------------------
                // Decided from the streaks the previous ticks recorded. Only a
                // tick that actually tried to move a frame counts as evidence:
                // an idle desktop proves nothing about the link, and letting it
                // climb the ladder just guarantees a stall the moment the user
                // does something.
                long drops = _ws.VideoFramesDropped;
                if (drops != lastDrops)
                {
                    // The lane had to discard a frame outright: treat it as a
                    // hard congestion signal, not a gentle one.
                    lastDrops = drops;
                    skipStreak = StepDownSkips;
                    clearStreak = 0;
                }

                long kfRequests = Interlocked.Read(ref _keyframeRequestCount);
                if (kfRequests != seenKeyframeRequests)
                {
                    // A viewer only asks out of band when it detected a sequence
                    // gap. Repeat requests are the only evidence we get that the
                    // relay is dropping video for a backed-up peer — those drops
                    // happen past our socket and never reach our own counters, so
                    // without this the ladder would happily climb while the far
                    // end saw nothing. One request is just a viewer attaching.
                    if (lastKeyframeRequestAt != 0 && now - lastKeyframeRequestAt < RepeatKeyframeRequestMs)
                    {
                        skipStreak = StepDownSkips;
                        clearStreak = 0;
                    }
                    seenKeyframeRequests = kfRequests;
                    lastKeyframeRequestAt = now;
                }

                if (now - lastLevelChange > LevelDwellMs)
                {
                    if (skipStreak >= StepDownSkips)
                    {
                        if (level < Ladder.Length - 1)
                        {
                            // Remember which rung just failed, and back off
                            // further each time the same one fails again.
                            levelRetryMs = level == failedLevel
                                ? Math.Min(levelRetryMs * 2, MaxLevelRetryMs)
                                : MinLevelRetryMs;
                            failedLevel = level;
                            levelRetryAt = now + levelRetryMs;

                            level++;
                            lastLevelChange = now;
                            skipStreak = 0;
                        }
                        else if (_fpsDivider < 4) { _fpsDivider *= 2; lastLevelChange = now; skipStreak = 0; }
                    }
                    else if (clearStreak >= Math.Max(1, ActiveFps) * StepUpClearSeconds)
                    {
                        if (_fpsDivider > 1) { _fpsDivider /= 2; lastLevelChange = now; clearStreak = 0; }
                        else if (level > 0 && (level - 1 != failedLevel || now >= levelRetryAt))
                        {
                            level--;
                            lastLevelChange = now;
                            clearStreak = 0;
                        }
                    }
                }

                // ---- region and output sizing -----------------------------------
                // What the viewers can actually see, and how many pixels they
                // asked for. Whole-desktop mode resolves to the full surface at
                // native size, which is exactly the pre-region behaviour.
                bool regionMode = Regions.TryResolve(vs.Width, vs.Height,
                    out var srcRegion, out int reqOutW, out int reqOutH);

                double userScale = MaxWidth > 0 && reqOutW > MaxWidth ? (double)MaxWidth / reqOutW : 1.0;
                double scale = Math.Min(userScale, Ladder[level].Scale);
                // Never encode more pixels than the source region holds: past
                // 1:1 the extra pixels are interpolation, not detail.
                int outW = Math.Clamp((int)(reqOutW * scale), 16, srcRegion.Width);
                int outH = Math.Clamp((int)(reqOutH * scale), 16, srcRegion.Height);

                if (srcRegion != lastRegion || outW != lastOutW || outH != lastOutH)
                {
                    output?.Dispose();
                    // Only the whole desktop at native size can skip the
                    // intermediate: BuildPatch takes surfW/surfH from the
                    // bitmap it encodes, so any crop has to go through a
                    // correctly-sized frame or the header would describe the
                    // full surface while the rects describe the region.
                    bool wholeDesktopNative =
                        srcRegion.X == 0 && srcRegion.Y == 0 &&
                        srcRegion.Width == vs.Width && srcRegion.Height == vs.Height &&
                        outW == vs.Width && outH == vs.Height;
                    output = wholeDesktopNative
                        ? null
                        : new Bitmap(outW, outH, PixelFormat.Format32bppRgb);
                    bool geometryChanged = outW != lastOutW || outH != lastOutH;
                    lastRegion = srcRegion;
                    lastOutW = outW;
                    lastOutH = outH;
                    _activeWidth = outW;
                    // A new region or size means the viewer canvas no longer
                    // describes what we are sending: it can only resume from a
                    // keyframe.
                    forceKeyframe = true;
                    if (geometryChanged || regionMode != lastRegionMode)
                    {
                        lastRegionMode = regionMode;
                        var monitorList = new JsonArray();
                        foreach (var m in monitors)
                        {
                            monitorList.Add(new JsonObject
                            {
                                ["x"] = m.Bounds.X,
                                ["y"] = m.Bounds.Y,
                                ["w"] = m.Bounds.Width,
                                ["h"] = m.Bounds.Height,
                                ["primary"] = m.Primary,
                            });
                        }
                        _ws.SendJson(new JsonObject
                        {
                            ["type"] = "screen-info",
                            ["width"] = outW,
                            ["height"] = outH,
                            // The desktop the region coordinates are expressed
                            // in, so a viewer can normalize input against it.
                            ["desktopWidth"] = vs.Width,
                            ["desktopHeight"] = vs.Height,
                            // So a viewer can offer "show just this monitor"
                            // instead of a letterboxed strip of all of them.
                            ["monitors"] = monitorList,
                        });
                    }
                }

                int quality = Math.Clamp((int)Math.Min(JpegQuality, Ladder[level].Quality), 40, 95);
                if (quality != encQuality)
                {
                    encParams?.Dispose();
                    encParams = new EncoderParameters(1);
                    encParams.Param[0] = new EncoderParameter(
                        System.Drawing.Imaging.Encoder.Quality, (long)quality);
                    encQuality = quality;
                    _activeQuality = quality;
                }

                // Prefer DXGI: while on the GDI fallback, retry it occasionally
                // (e.g. after leaving the secure desktop).
                if (!sourceIsDxgi && now - lastDxgiRetry > DxgiRetryMs)
                {
                    lastDxgiRetry = now;
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

                // ---- capture ---------------------------------------------------
                dirty.Clear();
                bool captured;
                try
                {
                    // Only the streamed region needs to be current: with a
                    // viewport on one monitor, the source can skip the others
                    // entirely rather than copying a 4K texture per frame for
                    // pixels nobody will receive.
                    captured = source.CaptureInto(surface, dirty, srcRegion);
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

                // Fold this tick into the outstanding damage. Everything below
                // may decline to send; nothing below may discard this.
                foreach (var d in dirty) damage.Add(d);

                // The lane still holds a frame the network has not taken. Skip
                // the encode — the damage stays queued and the next frame that
                // does go out will carry it.
                if (_ws.VideoLaneBusy)
                {
                    skipStreak++;
                    clearStreak = 0;
                    await PaceAsync(frameStart, ct);
                    continue;
                }

                // ---- frame selection -------------------------------------------
                bool keyframe = forceKeyframe || _keyframeRequested
                    || (deltaSinceKeyframe && now - lastKeyframeAt > KeyframeIntervalMs);

                if (!keyframe && !damage.Any)
                {
                    // Nothing changed: send nothing, wait for the next tick.
                    await PaceAsync(frameStart, ct);
                    continue;
                }

                List<Rectangle> rects;
                if (keyframe)
                {
                    rects = new List<Rectangle> { srcRegion };
                }
                else
                {
                    // Only damage inside the streamed region counts, and the
                    // keyframe threshold is measured against that region rather
                    // than the whole desktop — with a small viewport, activity
                    // on the far monitor is not this frame's problem.
                    rects = damage.ToRects(MaxRects);
                    long changed = 0;
                    var clipped = new List<Rectangle>(rects.Count);
                    foreach (var r in rects)
                    {
                        var c = r;
                        c.Intersect(srcRegion);
                        if (c.Width <= 0 || c.Height <= 0) continue;
                        clipped.Add(c);
                        changed += (long)c.Width * c.Height;
                    }
                    if (clipped.Count == 0) { await PaceAsync(frameStart, ct); continue; }

                    if (changed > (long)srcRegion.Width * srcRegion.Height * KeyframeAreaFraction)
                    {
                        keyframe = true;
                        rects = new List<Rectangle> { srcRegion };
                    }
                    else
                    {
                        rects = clipped;
                    }
                }

                var sendSurface = surface;
                if (output != null)
                {
                    rects = RenderRegion(surface, output, rects, srcRegion);
                    sendSurface = output;
                    if (rects.Count == 0) { await PaceAsync(frameStart, ct); continue; }
                }

                // Seq increments per built patch; the lane can still drop one
                // under extreme pressure, and the resulting gap is what tells
                // the viewer to ask for a keyframe.
                var payload = BuildPatch(sendSurface, rects, keyframe, encParams!,
                    regionMode ? srcRegion : null);
                if (!_ws.SendBinary(Crypto.ChPatch, payload))
                {
                    // Socket is gone. Keep the damage: it is still owed to the
                    // viewer, and reconnecting forces a keyframe anyway.
                    forceKeyframe = true;
                    await PaceAsync(frameStart, ct);
                    continue;
                }

                damage.Clear();
                clearStreak++;
                skipStreak = 0;
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
            encParams?.Dispose();
            source?.Dispose();
            surface?.Dispose();
            output?.Dispose();
        }
    }

    private async Task PaceAsync(long frameStart, CancellationToken ct)
    {
        int delay = Math.Max(1, 1000 / ActiveFps - (int)(Environment.TickCount64 - frameStart));
        await Task.Delay(delay, ct);
    }

    /// <summary>
    /// Regions changed since the last patch actually went out, on a block grid.
    /// Accumulating here rather than in a rect list is what lets an arbitrary
    /// number of skipped ticks collapse into one bounded repaint set: the union
    /// of N frames of damage costs no more to represent than one.
    /// </summary>
    private sealed class DamageMap
    {
        private const int BaseBlock = 64;

        private int _w, _h, _cols, _rows, _marked;
        private bool[] _bits = Array.Empty<bool>();

        public bool Any => _marked > 0;

        /// <summary>Upper bound on changed pixels (block-aligned).</summary>
        public long MarkedPixels => (long)_marked * BaseBlock * BaseBlock;

        public void Resize(int w, int h)
        {
            if (w == _w && h == _h) return;
            _w = w; _h = h;
            _cols = Math.Max(1, (w + BaseBlock - 1) / BaseBlock);
            _rows = Math.Max(1, (h + BaseBlock - 1) / BaseBlock);
            _bits = new bool[_cols * _rows];
            _marked = 0;
        }

        public void Clear()
        {
            if (_marked == 0) return;
            Array.Clear(_bits);
            _marked = 0;
        }

        public void Add(Rectangle r)
        {
            if (r.Width <= 0 || r.Height <= 0 || _bits.Length == 0) return;
            int c0 = Math.Max(0, r.Left / BaseBlock);
            int c1 = Math.Min(_cols - 1, (r.Right - 1) / BaseBlock);
            int r0 = Math.Max(0, r.Top / BaseBlock);
            int r1 = Math.Min(_rows - 1, (r.Bottom - 1) / BaseBlock);
            for (int row = r0; row <= r1; row++)
            {
                int rowBase = row * _cols;
                for (int col = c0; col <= c1; col++)
                {
                    int i = rowBase + col;
                    if (!_bits[i]) { _bits[i] = true; _marked++; }
                }
            }
        }

        /// <summary>
        /// Merge the marked blocks into at most <paramref name="maxRects"/>
        /// rectangles. When the greedy merge still leaves too many, the grid is
        /// coarsened and retried — unlike collapsing to one bounding box, that
        /// keeps two changes on opposite monitors from dragging the whole
        /// stitched desktop into the patch.
        /// </summary>
        public List<Rectangle> ToRects(int maxRects)
        {
            var bits = _bits;
            int cols = _cols, rows = _rows, block = BaseBlock;
            while (true)
            {
                var rects = Merge(bits, cols, rows, block, _w, _h);
                if (rects.Count <= maxRects || (cols <= 1 && rows <= 1)) return rects;
                (bits, cols, rows) = Coarsen(bits, cols, rows);
                block *= 2;
            }
        }

        private static (bool[] Bits, int Cols, int Rows) Coarsen(bool[] bits, int cols, int rows)
        {
            int nc = Math.Max(1, (cols + 1) / 2), nr = Math.Max(1, (rows + 1) / 2);
            var next = new bool[nc * nr];
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++)
                    if (bits[r * cols + c]) next[(r / 2) * nc + (c / 2)] = true;
            return (next, nc, nr);
        }

        /// <summary>Greedy merge: horizontal runs, extended downward while the
        /// identical run repeats. Produces few, reasonably tight rectangles.</summary>
        private static List<Rectangle> Merge(bool[] bits, int cols, int rows, int block, int w, int h)
        {
            var rects = new List<Rectangle>();
            var consumed = new bool[cols * rows];
            for (int r = 0; r < rows; r++)
            {
                for (int c = 0; c < cols; c++)
                {
                    int i = r * cols + c;
                    if (!bits[i] || consumed[i]) continue;

                    int c2 = c;
                    while (c2 + 1 < cols && bits[r * cols + c2 + 1] && !consumed[r * cols + c2 + 1]) c2++;
                    int r2 = r;
                    while (r2 + 1 < rows && RunIsSet(bits, consumed, cols, r2 + 1, c, c2)) r2++;

                    for (int rr = r; rr <= r2; rr++)
                        for (int cc = c; cc <= c2; cc++)
                            consumed[rr * cols + cc] = true;

                    int x = c * block, y = r * block;
                    int rw = Math.Min((c2 - c + 1) * block, w - x);
                    int rh = Math.Min((r2 - r + 1) * block, h - y);
                    if (rw > 0 && rh > 0) rects.Add(new Rectangle(x, y, rw, rh));
                }
            }
            return rects;
        }

        private static bool RunIsSet(bool[] bits, bool[] consumed, int cols, int row, int c1, int c2)
        {
            int rowBase = row * cols;
            for (int c = c1; c <= c2; c++)
                if (!bits[rowBase + c] || consumed[rowBase + c]) return false;
            return true;
        }
    }

    /// <summary>
    /// Crop and scale: re-render the changed regions (slightly inflated so
    /// bilinear edges stay seamless) from the native surface into the output
    /// frame, mapping desktop coordinates to output coordinates through
    /// <paramref name="region"/>, and return the output-space rects to encode.
    /// </summary>
    private static List<Rectangle> RenderRegion(Bitmap native, Bitmap output, List<Rectangle> rects, Rectangle region)
    {
        var outRects = new List<Rectangle>(rects.Count);
        double sx = (double)output.Width / region.Width;
        double sy = (double)output.Height / region.Height;
        var outputBounds = new Rectangle(0, 0, output.Width, output.Height);
        using var g = Graphics.FromImage(output);
        g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
        foreach (var r in rects)
        {
            var src = Rectangle.Inflate(r, 2, 2);
            src.Intersect(region);
            if (src.Width <= 0 || src.Height <= 0) continue;
            var dest = Rectangle.FromLTRB(
                (int)Math.Floor((src.Left - region.X) * sx),
                (int)Math.Floor((src.Top - region.Y) * sy),
                (int)Math.Ceiling((src.Right - region.X) * sx),
                (int)Math.Ceiling((src.Bottom - region.Y) * sy));
            dest.Intersect(outputBounds);
            if (dest.Width <= 0 || dest.Height <= 0) continue;
            g.DrawImage(native, dest, src, GraphicsUnit.Pixel);
            outRects.Add(dest);
        }
        return outRects;
    }

    /// <summary>
    /// Patch payload (before encryption):
    /// [seq u32][flags u8: bit0 keyframe, bit1 region][surfW u16][surfH u16][rectCount u16]
    /// then, when bit 1 is set, [regionX u16][regionY u16][regionW u16][regionH u16]
    /// giving the desktop rect this frame covers; then per rect:
    /// [x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes].
    /// All integers big-endian.
    ///
    /// The region fields are only written for viewers that asked for a region,
    /// so a viewer that never sends `view-region` keeps the exact byte layout
    /// it already parses.
    /// </summary>
    private byte[] BuildPatch(Bitmap surface, List<Rectangle> rects, bool keyframe,
        EncoderParameters encParams, Rectangle? region)
    {
        using var ms = new MemoryStream();
        void W16(int v) { ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }
        void W32(uint v) { ms.WriteByte((byte)(v >> 24)); ms.WriteByte((byte)(v >> 16)); ms.WriteByte((byte)(v >> 8)); ms.WriteByte((byte)v); }

        W32(_seq++);
        ms.WriteByte((byte)((keyframe ? 1 : 0) | (region.HasValue ? 2 : 0)));
        W16(surface.Width);
        W16(surface.Height);
        W16(rects.Count);
        if (region is { } rg) { W16(rg.X); W16(rg.Y); W16(rg.Width); W16(rg.Height); }

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
