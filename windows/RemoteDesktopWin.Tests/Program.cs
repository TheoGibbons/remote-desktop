using System.Drawing;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace RemoteDesktopWin;

internal static class Program
{
    private static async Task Main()
    {
        CheckFrameMailbox();
        CheckViewport();
        CheckFullScreenKeys();
        StallChecks.Run();

        var ws = new WsClient();
        var streamer = new ScreenStreamer(ws);
        streamer.Regions.SetRegion("viewer", new ViewRegion(0, 0, 0.1, 0.1, 100, 100));
        streamer.Regions.SetH264("viewer", true);
        using var cts = new CancellationTokenSource();
        var loop = (Task)typeof(ScreenStreamer).GetMethod("CaptureLoop", BindingFlags.NonPublic | BindingFlags.Instance)!
            .Invoke(streamer, new object[] { cts.Token })!;
        try
        {
            await ExpectFrame(ws, keyframe: true);
            await ExpectIdle(ws);
            Console.WriteLine("PASS: frames are addressed to the viewer");

            // No capture damage and no viewport change: the request alone must
            // cause a frame, even if congestion delays it for a few ticks.
            ws.VideoLaneBusy = true;
            streamer.RequestKeyframe();
            await ExpectIdle(ws);
            ws.VideoLaneBusy = false;
            await ExpectFrame(ws, keyframe: true);
            await ExpectIdle(ws);
            Console.WriteLine("PASS: idle recovery request survives a busy video lane");

            // The last viewer is gone, as when the connection drops. A frame
            // with nobody to address is one the relay sends to everybody, so
            // none is made, whatever is asked for meanwhile.
            streamer.Regions.Clear();
            streamer.RequestKeyframe();
            await ExpectIdle(ws);
            streamer.Regions.SetRegion("viewer", new ViewRegion(0, 0, 0.1, 0.1, 100, 100));
            streamer.Regions.SetH264("viewer", true);
            await ExpectFrame(ws, keyframe: true);
            await ExpectIdle(ws);
            Console.WriteLine("PASS: nothing is sent while nobody is viewing");

            // A final inter frame can be dropped downstream with no next seq
            // to reveal the gap. The periodic refresh must still repair it.
            DxgiCaptureSource.DamageNextCapture = true;
            await ExpectFrame(ws, keyframe: false);
            await ExpectFrame(ws, keyframe: true, timeoutMs: 12_000);
            await ExpectIdle(ws);
            Console.WriteLine("PASS: periodic recovery after the desktop becomes idle");
            await ExpectFrame(ws, keyframe: true, timeoutMs: 12_000);
            Console.WriteLine("PASS: idle keyframe refresh continues without desktop damage");
        }
        finally
        {
            cts.Cancel();
            await loop.WaitAsync(TimeSpan.FromSeconds(2));
        }
    }

    private static void CheckFrameMailbox()
    {
        var batches = new Queue<Action>();
        var drawn = new List<byte>();
        var phone = new FrameMailbox(1, f => drawn.Add(f[0]), batches.Enqueue);
        foreach (byte b in new byte[] { 1, 2, 3 }) phone.Post(new[] { b });
        if (batches.Count != 1) throw new Exception("Waiting frames should share one scheduled draw");
        batches.Dequeue()();
        if (!drawn.SequenceEqual(new byte[] { 3 })) throw new Exception("Only the newest phone frame should be drawn");
        Console.WriteLine("PASS: a window that falls behind draws only the newest phone frame");

        drawn.Clear();
        var patches = new FrameMailbox(3, f => drawn.Add(f[0]), batches.Enqueue);
        foreach (byte b in new byte[] { 1, 2, 3, 4, 5 }) patches.Post(new[] { b });
        batches.Dequeue()();
        if (!drawn.SequenceEqual(new byte[] { 3, 4, 5 })) throw new Exception("Patches past the limit should drop the oldest");
        Console.WriteLine("PASS: waiting patches are capped, oldest dropped, the rest in order");

        // A frame that arrives mid-batch gets a batch of its own, so a stream
        // that never pauses still lets the UI thread do other work in between.
        drawn.Clear();
        FrameMailbox? feeding = null;
        feeding = new FrameMailbox(3, f => { drawn.Add(f[0]); if (f[0] == 1) feeding!.Post(new byte[] { 2 }); }, batches.Enqueue);
        feeding.Post(new byte[] { 1 });
        batches.Dequeue()();
        if (!drawn.SequenceEqual(new byte[] { 1 }) || batches.Count != 1) throw new Exception("A batch should not grow while it runs");
        batches.Dequeue()();
        if (!drawn.SequenceEqual(new byte[] { 1, 2 })) throw new Exception("The later frame should be drawn next");
        Console.WriteLine("PASS: frames arriving during a draw wait for the next batch");
    }

    /// <summary>Two 4K screens side by side, viewed in a 1600x900 window.</summary>
    private static void CheckViewport()
    {
        static void Near(double actual, double expected, string what)
        {
            if (Math.Abs(actual - expected) > 1e-6 * Math.Max(1, Math.Abs(expected)))
                throw new Exception($"{what}: {actual}, expected {expected}");
        }

        var v = new PcViewport();
        v.SetView(1600, 900);
        v.SetDesktop(7680, 2160);
        Near(v.Zoom, 1600.0 / 7680, "fitting zoom");
        var whole = v.Want()!.Value;
        if (whole is not { X: 0, Y: 0, W: 1, H: 1, OutW: 1600, OutH: 450 })
            throw new Exception("Fitting, the whole desktop was not asked for at the pixels it takes here: " + whole);
        if (v.EdgeVelocity(0, 450, 30) != (0, 0)) throw new Exception("The whole desktop on show still panned");
        Console.WriteLine("PASS: fitting, the whole desktop is asked for at the size it is shown, and nothing pans");

        v.ZoomAroundCentre(1);
        Near(v.X, 3840 - 800, "1:1 left");
        Near(v.Y, 1080 - 450, "1:1 top");
        var c = v.ToDesktop(800, 450)!.Value;
        Near(c.X, 0.5, "middle of the view, across");
        Near(c.Y, 0.5, "middle of the view, down");
        var one = v.Want()!.Value;
        Near(one.W * 7680, 1600 * 1.3, "asked-for width at 1:1, with its margin");
        Near(one.H * 2160, 900 * 1.3, "asked-for height at 1:1, with its margin");
        if (one.OutW != 2080 || one.OutH != 1170) throw new Exception($"At 1:1 the pixels asked for are not the desktop's own: {one}");
        var before = v.ToDesktop(300, 200)!.Value;
        v.ZoomTo(2, 300, 200);
        var after = v.ToDesktop(300, 200)!.Value;
        Near(after.X, before.X, "point under the pointer while zooming, across");
        Near(after.Y, before.Y, "point under the pointer while zooming, down");
        v.ZoomAroundCentre(PcViewport.MaxZoom);
        var close = v.Want()!.Value;
        if (close.OutW > Math.Floor(close.W * 7680) || close.OutH > Math.Floor(close.H * 2160))
            throw new Exception("Zoomed past 1:1, more pixels were asked for than the desktop holds: " + close);
        Console.WriteLine("PASS: 1:1 and closer ask for the part on show at the desktop's own detail, and zooming keeps the point under the pointer");

        v.ZoomAroundCentre(1);
        var (left, still) = v.EdgeVelocity(0, 450, 30);
        Near(left, -PcViewport.MaxPanSpeed, "speed right at the left edge");
        Near(still, 0, "up or down at the middle of the left edge");
        Near(v.EdgeVelocity(15, 450, 30).X, -PcViewport.MaxPanSpeed / 4, "speed halfway into the edge zone");
        if (v.EdgeVelocity(-40, 450, 30).X != -PcViewport.MaxPanSpeed) throw new Exception("A pointer dragged past the edge did not pan at full speed");
        if (v.EdgeVelocity(100, 450, 30) != (0, 0)) throw new Exception("Away from the edges the picture panned");
        int steps = 0;
        while (v.PanBy(-500, 0)) if (++steps > 100) throw new Exception("Panning left never stopped");
        Near(v.X, 0, "panned to the left end");
        if (v.EdgeVelocity(0, 450, 30).X != 0) throw new Exception("With nothing left to the left, the left edge still held the pointer");
        if (v.EdgeVelocity(1599, 450, 30).X <= 0) throw new Exception("At the left end the right edge did not pan");
        if (v.EdgeVelocity(800, 0, 30, upAllowed: false).Y != 0) throw new Exception("The top edge panned where it was kept for the toolbar");
        if (v.EdgeVelocity(800, 0, 30).Y >= 0) throw new Exception("The top edge did not pan up");
        var atEdge = v.Want()!.Value;
        Near(atEdge.X, 0, "asked-for left at the left end");
        Near(atEdge.W * 7680, 2080, "asked-for width at the left end: slid inside, not cut short");
        Console.WriteLine("PASS: the edges pan faster the nearer the pointer, and let it go once there is no more that way");

        double sx = atEdge.X * 7680, sy = atEdge.Y * 2160, sw = atEdge.W * 7680, sh = atEdge.H * 2160;
        if (v.NeedsAsking(sx, sy, sw, sh, 1)) throw new Exception("The PC was asked again for what it already streams");
        v.PanBy(400, 0);
        if (v.NeedsAsking(sx, sy, sw, sh, 1)) throw new Exception("A pan inside the margin asked the PC again");
        v.PanBy(200, 0);
        if (!v.NeedsAsking(sx, sy, sw, sh, 1)) throw new Exception("A pan past what is streamed did not ask the PC again");
        if (!v.NeedsAsking(0, 0, 7680, 2160, 1.2)) throw new Exception("A zoom well away from the one asked for did not ask again");
        if (v.NeedsAsking(0, 0, 7680, 2160, 1.05)) throw new Exception("A small change of zoom asked the PC again");
        Console.WriteLine("PASS: the PC is asked again only once the view leaves what it streams, or the zoom has moved well away");

        v.Fit();
        v.SetView(1200, 900);
        Near(v.Zoom, 1200.0 / 7680, "fitting after the window shrank");
        v.ZoomAroundCentre(1);
        v.SetView(1600, 900);
        Near(v.Zoom, 1, "zoom kept when the window grows");
        var small = new PcViewport();
        small.SetView(1600, 900);
        small.SetDesktop(800, 600);
        Near(small.Zoom, 1.5, "a small desktop is fitted");
        if (small.ToDesktop(10, 450) != null) throw new Exception("The bar beside a small desktop was taken for part of it");
        if (small.ToDesktop(800, 450) is not { } mid || Math.Abs(mid.X - 0.5) > 1e-9) throw new Exception("A small desktop is not centred");
        Console.WriteLine("PASS: fitting follows the window, a chosen zoom stays, and a small desktop is centred with nothing beside it");
    }

    private static void CheckFullScreenKeys()
    {
        var cases = new (uint Vk, bool Alt, bool Ctrl, string? Code)[]
        {
            (0x5B, false, false, "WIN"), (0x5C, false, false, "WIN"), (0x5B, true, true, "WIN"),
            (0x09, true, false, "TAB"), (0x09, false, false, null), (0x09, false, true, null),
            (0x1B, true, false, "ESC"), (0x1B, false, true, "ESC"), (0x1B, false, false, null),
            (0x41, true, true, null), (0x73, true, false, null), (0x20, true, false, null),
        };
        foreach (var (vk, alt, ctrl, code) in cases)
            if (FullScreenKeys.Takes(vk, alt, ctrl) != code)
                throw new Exception($"Key 0x{vk:X2} (alt {alt}, ctrl {ctrl}) taken as {FullScreenKeys.Takes(vk, alt, ctrl) ?? "nothing"}, expected {code ?? "nothing"}");
        Console.WriteLine("PASS: full screen takes the Windows key, Alt+Tab, Alt+Esc and Ctrl+Esc from Windows, and nothing else");
    }

    private static async Task ExpectFrame(WsClient ws, bool keyframe, int timeoutMs = 2_000)
    {
        var frame = await ws.Frames.Reader.ReadAsync().AsTask().WaitAsync(TimeSpan.FromMilliseconds(timeoutMs));
        if (frame[4] != (keyframe ? 1 : 0)) throw new Exception("Unexpected H.264 keyframe flag");
    }

    private static async Task ExpectIdle(WsClient ws)
    {
        await Task.Delay(250);
        if (ws.Frames.Reader.TryRead(out _)) throw new Exception("Idle desktop sent an unnecessary frame");
    }
}

// Only the capture, codec and transport edges are replaced. Frame selection,
// damage accounting, pacing, requests and recovery run from production source.
public sealed class WsClient
{
    public long VideoFramesDropped => 0;
    public volatile bool VideoLaneBusy;
    public Channel<byte[]> Frames { get; } = Channel.CreateUnbounded<byte[]>();
    public void SendJson(JsonObject _) { }
    public bool SendBinary(byte channel, byte[] payload, IReadOnlyCollection<string>? to = null)
    {
        if (channel != Crypto.ChH264) throw new Exception("Expected H.264 output");
        // Broadcast would also reach devices that are watching some other host.
        if (to == null || !to.SequenceEqual(new[] { "viewer" })) throw new Exception("Frame not addressed to the viewer");
        return Frames.Writer.TryWrite(payload);
    }
}

public static class Crypto
{
    public const byte ChPatch = 3, ChH264 = 4;
}

public interface ICaptureSource : IDisposable
{
    bool CaptureInto(Bitmap surface, List<Rectangle> dirty, Rectangle interest);
}

public sealed class DxgiCaptureSource : ICaptureSource
{
    public DxgiCaptureSource(Rectangle _) { }
    public static volatile bool DamageNextCapture;
    public bool CaptureInto(Bitmap surface, List<Rectangle> dirty, Rectangle interest)
    {
        if (DamageNextCapture)
        {
            DamageNextCapture = false;
            dirty.Add(interest);
        }
        return true;
    }
    public void Dispose() { }
}

public sealed class GdiCaptureSource : ICaptureSource
{
    public GdiCaptureSource(Rectangle _) { }
    public bool CaptureInto(Bitmap surface, List<Rectangle> dirty, Rectangle interest) => true;
    public void Dispose() { }
}
  public sealed class H264Encoder : IDisposable
  {
    public static string? Starting => null;
    public string Name => "Test H.264 encoder";
    public string Activity => "test double";
    public static H264Encoder TryCreate(int width, int height, int bitrate, int fps) => new();
    public byte[] Encode(Bitmap frame, bool forceKeyframe) =>
        new byte[] { 0, 0, 0, 1, forceKeyframe ? (byte)0x65 : (byte)0x41, 0 };
    public void Dispose() { }
}
