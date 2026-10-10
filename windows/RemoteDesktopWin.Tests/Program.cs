using System.Drawing;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace RemoteDesktopWin;

internal static class Program
{
    private static async Task Main()
    {
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

            // No capture damage and no viewport change: the request alone must
            // cause a frame, even if congestion delays it for a few ticks.
            ws.VideoLaneBusy = true;
            streamer.RequestKeyframe();
            await ExpectIdle(ws);
            ws.VideoLaneBusy = false;
            await ExpectFrame(ws, keyframe: true);
            await ExpectIdle(ws);
            Console.WriteLine("PASS: idle recovery request survives a busy video lane");

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
    public bool SendBinary(byte channel, byte[] payload)
    {
        if (channel != Crypto.ChH264) throw new Exception("Expected H.264 output");
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
    public static H264Encoder TryCreate(int width, int height, int bitrate, int fps) => new();
    public byte[] Encode(Bitmap frame, bool forceKeyframe) =>
        new byte[] { 0, 0, 0, 1, forceKeyframe ? (byte)0x65 : (byte)0x41, 0 };
    public void Dispose() { }
}
