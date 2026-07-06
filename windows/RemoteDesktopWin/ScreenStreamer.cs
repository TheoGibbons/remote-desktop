using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// Captures the entire Windows virtual desktop (all monitors stitched into one
/// canvas, exactly as Windows arranges them) and streams JPEG frames.
/// </summary>
public class ScreenStreamer
{
    private readonly WsClient _ws;
    private CancellationTokenSource? _cts;
    private readonly ImageCodecInfo _jpegCodec;

    public bool IsStreaming => _cts != null && !_cts.IsCancellationRequested;
    public int Fps { get; set; } = 12;
    public long JpegQuality { get; set; } = 55;
    public int MaxWidth { get; set; } = 2200;

    public event Action<bool>? StreamingChanged;

    public ScreenStreamer(WsClient ws)
    {
        _ws = ws;
        _jpegCodec = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
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
        _ = Task.Run(() => CaptureLoop(_cts.Token));
        StreamingChanged?.Invoke(true);
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;
        StreamingChanged?.Invoke(false);
    }

    private async Task CaptureLoop(CancellationToken ct)
    {
        Rectangle lastBounds = Rectangle.Empty;
        Bitmap? capture = null;
        Bitmap? scaled = null;

        try
        {
            while (!ct.IsCancellationRequested)
            {
                var frameStart = Environment.TickCount64;
                var vs = VirtualScreen();

                double scale = vs.Width > MaxWidth ? (double)MaxWidth / vs.Width : 1.0;
                int outW = (int)(vs.Width * scale);
                int outH = (int)(vs.Height * scale);

                if (vs != lastBounds || capture == null)
                {
                    capture?.Dispose();
                    scaled?.Dispose();
                    capture = new Bitmap(vs.Width, vs.Height, PixelFormat.Format32bppRgb);
                    scaled = scale < 1.0 ? new Bitmap(outW, outH, PixelFormat.Format32bppRgb) : null;
                    lastBounds = vs;
                    _ws.SendJson(new JsonObject
                    {
                        ["type"] = "screen-info",
                        ["width"] = outW,
                        ["height"] = outH,
                    });
                }

                try
                {
                    using (var g = Graphics.FromImage(capture))
                        g.CopyFromScreen(vs.X, vs.Y, 0, 0, vs.Size);
                }
                catch
                {
                    // Capture can fail transiently on secure desktop (UAC prompt, lock screen).
                    await Task.Delay(500, ct);
                    continue;
                }

                Bitmap toEncode = capture;
                if (scaled != null)
                {
                    using var g = Graphics.FromImage(scaled);
                    g.InterpolationMode = System.Drawing.Drawing2D.InterpolationMode.Bilinear;
                    g.DrawImage(capture, 0, 0, outW, outH);
                    toEncode = scaled;
                }

                using var ms = new MemoryStream();
                using (var ep = new EncoderParameters(1))
                {
                    ep.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, JpegQuality);
                    toEncode.Save(ms, _jpegCodec, ep);
                }
                // Video is loss-tolerant: if the link is backed up, drop this frame.
                // WsClient adds the frame-type byte and encrypts the JPEG payload.
                _ws.SendBinary(1, ms.ToArray());

                int delay = Math.Max(1, 1000 / Math.Max(1, Fps) - (int)(Environment.TickCount64 - frameStart));
                await Task.Delay(delay, ct);
            }
        }
        catch (OperationCanceledException) { }
        finally
        {
            capture?.Dispose();
            scaled?.Dispose();
        }
    }
}
