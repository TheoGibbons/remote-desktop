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

    private const long MaxQueuedBinaryBytes = 8 * 1024 * 1024;

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

    public void Start(string url, string sessionKey, string deviceName, string deviceUid)
    {
        Stop();
        _url = url;
        (_encKey, _pairId) = Crypto.DeriveKeys(sessionKey);
        _deviceName = deviceName;
        _deviceUid = deviceUid;
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
        int backoffMs = 2000;
        while (_running && !ct.IsCancellationRequested)
        {
            try
            {
                StateChanged?.Invoke("connecting");
                _ws = new ClientWebSocket();
                _ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(20);
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
                backoffMs = 2000;
                await ReceiveLoop(ct);
                _outbox.Writer.TryComplete();
                await pump;
            }
            catch (OperationCanceledException) { break; }
            catch (Exception ex)
            {
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
            StateChanged?.Invoke("disconnected: retrying...");
            try { await Task.Delay(backoffMs, ct); } catch { break; }
            backoffMs = Math.Min(backoffMs * 2, 30000);
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
                    StateChanged?.Invoke("disconnected: closed by server");
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
                StateChanged?.Invoke("connected");
            }
            JsonReceived?.Invoke(node);
        }
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
        if (Interlocked.Read(ref _queuedBinaryBytes) > MaxQueuedBinaryBytes) return false;

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
