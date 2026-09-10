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
/// All outgoing frames go through a single ordered outbox so control messages
/// and binary frames are sent in the exact order they were queued.
/// </summary>
public class WsClient : IDisposable
{
    private readonly struct OutFrame
    {
        public readonly byte[]? Text;
        public readonly byte[]? Binary;
        public OutFrame(byte[]? text, byte[]? binary) { Text = text; Binary = binary; }
    }

    private ClientWebSocket? _ws;
    private CancellationTokenSource? _cts;
    private volatile bool _running;
    private Channel<OutFrame>? _outbox;
    private long _queuedBinaryBytes;
    private int _backoffMs = MinBackoffMs;
    private string? _lastCloseReason;

    // Interactive screen updates become actively misleading when several
    // seconds of old patches sit in front of the latest input response. Keep at
    // most a small burst; ScreenStreamer then replaces dropped deltas with a
    // fresh keyframe as soon as the sender catches up.
    private const long MaxQueuedBinaryBytes = 2 * 1024 * 1024;

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
    public long QueuedBinaryBytes => Math.Max(0, Interlocked.Read(ref _queuedBinaryBytes));
    public bool IsVideoBacklogged => QueuedBinaryBytes > MaxQueuedBinaryBytes;

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
        _outbox?.Writer.TryComplete();
        try { _ws?.Dispose(); } catch { }
        _ws = null;
        MyId = null;
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
                // reaped its side. Matches OkHttp's pingInterval on Android.
                _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
                _ws.Options.KeepAliveTimeout = TimeSpan.FromSeconds(20);
                await _ws.ConnectAsync(new Uri(_url), ct);

                _outbox = Channel.CreateUnbounded<OutFrame>(new UnboundedChannelOptions { SingleReader = true });
                Interlocked.Exchange(ref _queuedBinaryBytes, 0);

                // hello is cleartext and carries only the derived pairing id.
                EnqueueText(new JsonObject
                {
                    ["type"] = "hello",
                    ["session"] = _pairId,
                    ["device"] = "windows",
                    ["name"] = _deviceName,
                    ["uid"] = _deviceUid,
                });

                var pump = Task.Run(() => SendPump(_ws, _outbox, ct), ct);
                await ReceiveLoop(ct);
                reason = _lastCloseReason ?? "connection closed";
                _outbox.Writer.TryComplete();
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
                _outbox?.Writer.TryComplete();
                _outbox = null;
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

    private async Task SendPump(ClientWebSocket ws, Channel<OutFrame> outbox, CancellationToken ct)
    {
        try
        {
            await foreach (var frame in outbox.Reader.ReadAllAsync(ct))
            {
                if (ws.State != WebSocketState.Open) break;
                if (frame.Text != null)
                {
                    await ws.SendAsync(frame.Text, WebSocketMessageType.Text, true, ct);
                }
                else if (frame.Binary != null)
                {
                    Interlocked.Add(ref _queuedBinaryBytes, -frame.Binary.Length);
                    await ws.SendAsync(frame.Binary, WebSocketMessageType.Binary, true, ct);
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
    {
        _outbox?.Writer.TryWrite(new OutFrame(Encoding.UTF8.GetBytes(obj.ToJsonString()), null));
    }

    /// <summary>Queue a JSON message, encrypted end-to-end. Ordered with all other sends.</summary>
    public void SendJson(JsonObject obj)
    {
        var blob = Crypto.Encrypt(_encKey, Encoding.UTF8.GetBytes(obj.ToJsonString()), Crypto.ChJson);
        var env = new JsonObject { ["type"] = "enc", ["d"] = Convert.ToBase64String(blob) };
        var to = obj["to"]?.GetValue<string>();
        if (to != null) env["to"] = to; // lift routing target into the cleartext envelope
        EnqueueText(env);
    }

    /// <summary>
    /// Queue an encrypted binary frame (frameType 1 = video, 2 = file chunk).
    /// Returns false (frame dropped) if the socket backlog is too large — callers
    /// that must deliver (file chunks) should retry.
    /// </summary>
    public bool SendBinary(byte frameType, byte[] payload)
    {
        var outbox = _outbox;
        if (outbox == null || _ws?.State != WebSocketState.Open) return false;
        if (IsVideoBacklogged) return false;

        var blob = Crypto.Encrypt(_encKey, payload, frameType);
        var wire = new byte[1 + blob.Length];
        wire[0] = frameType;
        Buffer.BlockCopy(blob, 0, wire, 1, blob.Length);

        Interlocked.Add(ref _queuedBinaryBytes, wire.Length);
        if (!outbox.Writer.TryWrite(new OutFrame(null, wire)))
        {
            Interlocked.Add(ref _queuedBinaryBytes, -wire.Length);
            return false;
        }
        return true;
    }

    public void Dispose() => Stop();
}
