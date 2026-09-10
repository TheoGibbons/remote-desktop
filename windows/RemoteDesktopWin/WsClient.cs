using System.IO;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;

namespace RemoteDesktopWin;

/// <summary>
/// WebSocket client with automatic reconnect. Once configured it keeps the
/// device joined to the session forever — no re-authentication.
///
/// End-to-end encryption: the raw session key never leaves the device. The
/// server is joined using a derived pairing id, and every peer message / binary
/// frame is encrypted with a key derived from the same session key (see
/// <see cref="Crypto"/>). Only cleartext server messages (welcome / peer-* /
/// error) and the routing envelope (type "enc", optional "to") are visible to
/// the relay.
///
/// **Outgoing frames are split across two lanes** so a screen-sharing burst can
/// never delay a keystroke:
///
/// * *reliable* — control JSON and file chunks, strict FIFO. Order here is
///   load-bearing (`fs-begin` must precede its chunks and `fs-end` must follow
///   them), so the two share one queue and are only ever taken from its head.
/// * *video* — screen frames, droppable. It holds at most
///   <see cref="MaxQueuedVideoFrames"/> frames / <see cref="MaxQueuedVideoBytes"/>
///   bytes, and an overflow discards the **oldest** frame: a stale screen update
///   is worthless the moment a newer one exists, and queueing it only adds
///   latency to everything behind it.
///
/// The pump prefers a control message at the head of the reliable lane, then
/// video, then a file chunk — so input and diagnostics overtake bulk transfers
/// and screen data without ever reordering the reliable lane against itself.
/// </summary>
public class WsClient : IDisposable
{
    private enum Lane { Control, File, Video }

    private readonly struct OutFrame
    {
        public readonly byte[] Data;
        public readonly Lane Kind;
        public OutFrame(byte[] data, Lane kind) { Data = data; Kind = kind; }
    }

    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;
    private volatile bool _running;
    private int _backoffMs = MinBackoffMs;
    private string? _lastCloseReason;

    private readonly object _outLock = new();
    private Queue<OutFrame>? _reliable;
    private Queue<byte[]>? _video;
    private Channel<bool>? _wake;
    private long _queuedVideoBytes;
    private long _queuedFileBytes;
    private long _videoFramesDropped;
    private long _videoBytesSent;

    // Screen updates are worth queueing only while they are still current. Two
    // frames is enough to keep the socket busy while the next one encodes;
    // beyond that every queued byte is pure added latency on the round trip.
    // ScreenStreamer additionally declines to encode while the lane is
    // occupied, so in normal operation nothing is ever dropped — the cap is a
    // safety valve, not the mechanism.
    private const long MaxQueuedVideoBytes = 256 * 1024;
    private const int MaxQueuedVideoFrames = 2;

    // File chunks cannot be dropped (a hole silently corrupts the transfer), so
    // they are throttled by refusal instead: FsService retries until there is
    // room. Kept small so a transfer cannot park seconds of data in front of
    // the next control message.
    private const long MaxQueuedFileBytes = 512 * 1024;

    // Reconnect backoff. The reset lives in the "welcome" handler, not next to
    // ConnectAsync: the relay rejects a connection (rate limit, ban, too many
    // connections) by accepting the WebSocket upgrade and then closing it, so
    // "the socket opened" is not evidence that we are getting anywhere. Resetting
    // on connect turned every such rejection into a 2s hammer loop, which is
    // exactly the traffic that earns the ban in the first place. Keep this in
    // sync with ConnectionManager.kt (Android).
    private const int MinBackoffMs = 2000;
    private const int MaxBackoffMs = 30000;

    public string? MyId { get; private set; }

    public event Action<JsonNode>? JsonReceived;   // always the decrypted inner message
    public event Action<byte[]>? BinaryReceived;   // decrypted [frameType][payload]
    public event Action<string>? StateChanged;     // "connecting" | "connected" | "disconnected: <why>"

    private string _url = "";
    private byte[] _encKey = Array.Empty<byte>();
    private string _pairId = "";
    private string _deviceName = "";
    private string _deviceUid = "";

    public bool IsConnected => _ws?.State == WebSocketState.Open && MyId != null;

    /// <summary>Video bytes waiting in the app-level lane (excludes whatever the
    /// socket itself is still draining).</summary>
    public long QueuedVideoBytes { get { lock (_outLock) return _queuedVideoBytes; } }

    /// <summary>Total queued binary backlog, reported to viewers in diagnostic-pong.</summary>
    public long QueuedBinaryBytes { get { lock (_outLock) return _queuedVideoBytes + _queuedFileBytes; } }

    /// <summary>A frame is already waiting to go out. The streamer uses this to
    /// skip encoding rather than pile up work the network cannot take.</summary>
    public bool VideoLaneBusy { get { lock (_outLock) return _video is { Count: > 0 }; } }

    /// <summary>Frames discarded by the drop-oldest rule — a congestion signal
    /// for the streamer quality ladder.</summary>
    public long VideoFramesDropped { get { lock (_outLock) return _videoFramesDropped; } }

    /// <summary>Video bytes handed to the socket, for throughput estimation.</summary>
    public long VideoBytesSent { get { lock (_outLock) return _videoBytesSent; } }

    public void Start(string url, string sessionKey, string deviceName, string deviceUid)
    {
        Stop();
        _url = url;
        (_encKey, _pairId) = Crypto.DeriveKeys(sessionKey);
        _deviceName = deviceName;
        _deviceUid = deviceUid;
        _backoffMs = MinBackoffMs;
        _running = true;
        _cts = new CancellationTokenSource();
        _ = Task.Run(() => RunLoop(_cts.Token));
    }

    public void Stop()
    {
        _running = false;
        _cts?.Cancel();
        CloseOutbox();
        try { _ws?.Dispose(); } catch { }
        _ws = null;
        MyId = null;
    }

    private void OpenOutbox()
    {
        lock (_outLock)
        {
            _reliable = new Queue<OutFrame>();
            _video = new Queue<byte[]>();
            _wake = Channel.CreateBounded<bool>(new BoundedChannelOptions(1)
            {
                FullMode = BoundedChannelFullMode.DropWrite,
                SingleReader = true,
            });
            _queuedVideoBytes = 0;
            _queuedFileBytes = 0;
        }
    }

    private void CloseOutbox()
    {
        Channel<bool>? wake;
        lock (_outLock)
        {
            wake = _wake;
            _wake = null;
            _reliable = null;
            _video = null;
            _queuedVideoBytes = 0;
            _queuedFileBytes = 0;
        }
        wake?.Writer.TryComplete();
    }

    private async Task RunLoop(CancellationToken ct)
    {
        while (_running && !ct.IsCancellationRequested)
        {
            var reason = "connection closed";
            try
            {
                StateChanged?.Invoke("connecting");
                _lastCloseReason = null;
                _ws = new ClientWebSocket();
                // Ping every 20s AND require a pong. Without KeepAliveTimeout the
                // default is infinite: pings go out, unanswered pings are ignored,
                // and a connection killed without a FIN/RST reaching us (sleep/wake,
                // router reboot, ISP re-IP) leaves ReceiveAsync blocked forever with
                // the app still reporting "connected" while the relay has already
                // reaped its side. Matches OkHttp pingInterval on Android.
                _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
                _ws.Options.KeepAliveTimeout = TimeSpan.FromSeconds(20);
                await _ws.ConnectAsync(new Uri(_url), ct);

                OpenOutbox();
                var wake = _wake!.Reader;

                // hello is cleartext and carries only the derived pairing id.
                EnqueueText(new JsonObject
                {
                    ["type"] = "hello",
                    ["session"] = _pairId,
                    ["device"] = "windows",
                    ["name"] = _deviceName,
                    ["uid"] = _deviceUid,
                });

                var pump = Task.Run(() => SendPump(_ws, wake, ct), ct);
                await ReceiveLoop(ct);
                reason = _lastCloseReason ?? "connection closed";
                CloseOutbox();
                await pump;
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
                reason = ex.Message;
                StateChanged?.Invoke("disconnected: " + ex.Message);
            }
            finally
            {
                CloseOutbox();
                try { _ws?.Dispose(); } catch { }
                _ws = null;
                MyId = null;
            }

            if (!_running) break;
            StateChanged?.Invoke($"disconnected: {reason} — retrying in {_backoffMs / 1000}s");
            try { await Task.Delay(_backoffMs, ct); } catch { break; }
            _backoffMs = Math.Min(_backoffMs * 2, MaxBackoffMs);
        }
    }

    private void Signal() => _wake?.Writer.TryWrite(true);

    /// <summary>Queue on the ordered lane. File chunks are refused (rather than
    /// dropped) once the lane is full so the caller can retry.</summary>
    private bool EnqueueReliable(OutFrame frame)
    {
        lock (_outLock)
        {
            if (_reliable == null) return false;
            if (frame.Kind == Lane.File)
            {
                if (_queuedFileBytes >= MaxQueuedFileBytes) return false;
                _queuedFileBytes += frame.Data.Length;
            }
            _reliable.Enqueue(frame);
        }
        Signal();
        return true;
    }

    private bool EnqueueVideo(byte[] wire)
    {
        lock (_outLock)
        {
            if (_video == null) return false;
            _video.Enqueue(wire);
            _queuedVideoBytes += wire.Length;
            // Drop the oldest, never the newest: the freshest frame is the only
            // one the viewer actually wants. One frame always survives, however
            // large, so an oversized keyframe still gets through.
            while (_video.Count > 1 &&
                   (_video.Count > MaxQueuedVideoFrames || _queuedVideoBytes > MaxQueuedVideoBytes))
            {
                _queuedVideoBytes -= _video.Dequeue().Length;
                _videoFramesDropped++;
            }
        }
        Signal();
        return true;
    }

    /// <summary>
    /// Pick the next frame: a control message at the head of the reliable lane
    /// first, then video, then a file chunk. Only ever taking the reliable lane
    /// head preserves its internal ordering.
    /// </summary>
    private bool TryDequeue(out OutFrame frame)
    {
        lock (_outLock)
        {
            if (_reliable is { Count: > 0 } && _reliable.Peek().Kind == Lane.Control)
            {
                frame = _reliable.Dequeue();
                return true;
            }
            if (_video is { Count: > 0 })
            {
                var data = _video.Dequeue();
                _queuedVideoBytes -= data.Length;
                frame = new OutFrame(data, Lane.Video);
                return true;
            }
            if (_reliable is { Count: > 0 })
            {
                frame = _reliable.Dequeue();
                _queuedFileBytes -= frame.Data.Length;
                return true;
            }
        }
        frame = default;
        return false;
    }

    private async Task SendPump(ClientWebSocket ws, ChannelReader<bool> wake, CancellationToken ct)
    {
        try
        {
            while (!ct.IsCancellationRequested && ws.State == WebSocketState.Open)
            {
                await wake.ReadAsync(ct);
                while (TryDequeue(out var frame))
                {
                    if (ws.State != WebSocketState.Open) return;
                    await ws.SendAsync(
                        frame.Data,
                        frame.Kind == Lane.Control ? WebSocketMessageType.Text : WebSocketMessageType.Binary,
                        true, ct);
                    if (frame.Kind == Lane.Video)
                        lock (_outLock) _videoBytesSent += frame.Data.Length;
                }
            }
        }
        catch { /* pump ends on disconnect/cancel */ }
    }

    private async Task ReceiveLoop(CancellationToken ct)
    {
        var ws = _ws!;
        var buffer = new byte[64 * 1024];
        using var ms = new MemoryStream();

        while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            ms.SetLength(0);
            WebSocketReceiveResult result;
            do
            {
                result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    _lastCloseReason = DescribeClose(result);
                    StateChanged?.Invoke("disconnected: " + _lastCloseReason);
                    return;
                }
                ms.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            var data = ms.ToArray();
            if (result.MessageType == WebSocketMessageType.Binary)
            {
                if (data.Length < 1) continue;
                byte frameType = data[0];
                var payload = Crypto.Decrypt(_encKey, data[1..], frameType);
                if (payload == null) continue; // drop undecryptable frames
                var deliver = new byte[1 + payload.Length];
                deliver[0] = frameType;
                Buffer.BlockCopy(payload, 0, deliver, 1, payload.Length);
                BinaryReceived?.Invoke(deliver);
                continue;
            }

            var node = JsonNode.Parse(Encoding.UTF8.GetString(data));
            if (node == null) continue;
            var type = node["type"]?.GetValue<string>();

            if (type == "enc")
            {
                var blob = Convert.FromBase64String(node["d"]?.GetValue<string>() ?? "");
                var pt = Crypto.Decrypt(_encKey, blob, Crypto.ChJson);
                if (pt == null) continue;
                var inner = JsonNode.Parse(Encoding.UTF8.GetString(pt));
                if (inner == null) continue;
                var from = node["from"]?.GetValue<string>();
                if (from != null) inner["from"] = from;
                JsonReceived?.Invoke(inner);
                continue;
            }

            // Cleartext server messages.
            if (type == "welcome")
            {
                MyId = node["id"]?.GetValue<string>();
                _backoffMs = MinBackoffMs; // joined the session: this URL is working
                StateChanged?.Invoke("connected");
            }
            else if (type == "error")
            {
                // The relay sends this immediately before closing, so hold on to it:
                // it is more specific than the close code that follows.
                var code = node["code"]?.GetValue<string>();
                var message = node["message"]?.GetValue<string>();
                _lastCloseReason = string.IsNullOrWhiteSpace(message) ? code : $"{code}: {message}";
            }
            JsonReceived?.Invoke(node);
        }
    }

    /// <summary>
    /// Why the relay hung up. It rejects connections with application close codes
    /// (4001 banned, 4002 too-many-connections, 4000 + an "error" message for a
    /// bad or missing hello); without surfacing them a rejection is
    /// indistinguishable from an ordinary network drop.
    /// </summary>
    private string DescribeClose(WebSocketReceiveResult result)
    {
        var code = result.CloseStatus.HasValue ? ((int)result.CloseStatus.Value).ToString() : "no code";
        var reason = result.CloseStatusDescription;
        if (string.IsNullOrWhiteSpace(reason)) reason = _lastCloseReason;
        return string.IsNullOrWhiteSpace(reason)
            ? $"closed by server ({code})"
            : $"closed by server ({code}: {reason})";
    }

    private void EnqueueText(JsonObject obj)
        => EnqueueReliable(new OutFrame(Encoding.UTF8.GetBytes(obj.ToJsonString()), Lane.Control));

    /// <summary>Queue a JSON message, encrypted end-to-end. Ordered against
    /// other control messages and file chunks; overtakes queued video.</summary>
    public void SendJson(JsonObject obj)
    {
        var blob = Crypto.Encrypt(_encKey, Encoding.UTF8.GetBytes(obj.ToJsonString()), Crypto.ChJson);
        var env = new JsonObject { ["type"] = "enc", ["d"] = Convert.ToBase64String(blob) };
        var to = obj["to"]?.GetValue<string>();
        if (to != null) env["to"] = to; // lift routing target into the cleartext envelope
        EnqueueText(env);
    }

    /// <summary>
    /// Queue an encrypted binary frame. Video (1 = full frame, 3 = dirty-rect
    /// patch) goes to the droppable lane and always returns true; file chunks
    /// (2) go to the ordered lane and return false when it is full, which is the
    /// cue for the caller to retry.
    /// </summary>
    public bool SendBinary(byte frameType, byte[] payload)
    {
        if (_ws?.State != WebSocketState.Open) return false;

        var blob = Crypto.Encrypt(_encKey, payload, frameType);
        var wire = new byte[1 + blob.Length];
        wire[0] = frameType;
        Buffer.BlockCopy(blob, 0, wire, 1, blob.Length);

        return frameType == Crypto.ChFile
            ? EnqueueReliable(new OutFrame(wire, Lane.File))
            : EnqueueVideo(wire);
    }

    public void Dispose() => Stop();
}
