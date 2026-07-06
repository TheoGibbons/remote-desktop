using System.IO;
using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// Serves the local filesystem to peers (listing + transfers) and receives
/// files pushed by peers. See PROTOCOL.md, "File system".
/// </summary>
public class FsService
{
    private const int ChunkSize = 256 * 1024;

    private readonly WsClient _ws;
    private readonly Dictionary<uint, IncomingTransfer> _incoming = new();

    private class IncomingTransfer
    {
        public required FileStream Stream;
        public required string FinalPath;
        public required string TempPath;
        public long Expected;
        public long Received;
        public Action<string>? OnDone; // final path or null via OnError
        public Action<string>? OnError;
    }

    public string IncomingDir { get; set; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "RemoteDesktop");

    public event Action<string>? TransferStatus;

    public FsService(WsClient ws)
    {
        _ws = ws;
    }

    // Random id (kept within positive int range so it round-trips through both
    // JSON and the 4-byte chunk header on the Kotlin side without sign issues).
    public uint NextXferId() => (uint)Random.Shared.Next(1, int.MaxValue);

    // ---------- serving requests from the peer ----------

    public void HandleJson(JsonNode msg)
    {
        switch (msg["type"]?.GetValue<string>())
        {
            case "fs-list": HandleList(msg); break;
            case "fs-get": HandleGet(msg); break;
            case "fs-begin": HandleBegin(msg); break;
            case "fs-end": HandleEnd(msg); break;
        }
    }

    public void HandleFileChunk(byte[] frame)
    {
        // [2][xferId uint32 BE][data]
        if (frame.Length < 5) return;
        uint id = (uint)((frame[1] << 24) | (frame[2] << 16) | (frame[3] << 8) | frame[4]);
        lock (_incoming)
        {
            if (!_incoming.TryGetValue(id, out var t)) return;
            t.Stream.Write(frame, 5, frame.Length - 5);
            t.Received += frame.Length - 5;
        }
    }

    private void HandleList(JsonNode msg)
    {
        var from = msg["from"]!.GetValue<string>();
        var reqId = msg["reqId"]?.GetValue<string>() ?? "";
        var path = msg["path"]?.GetValue<string>() ?? "";
        var entries = new JsonArray();
        string? error = null;

        try
        {
            if (string.IsNullOrEmpty(path))
            {
                foreach (var drive in DriveInfo.GetDrives().Where(d => d.IsReady))
                    entries.Add(new JsonObject { ["name"] = drive.Name, ["dir"] = true, ["size"] = 0, ["mtime"] = 0 });
            }
            else
            {
                var di = new DirectoryInfo(path);
                foreach (var d in di.GetDirectories())
                    entries.Add(new JsonObject
                    {
                        ["name"] = d.Name,
                        ["dir"] = true,
                        ["size"] = 0,
                        ["mtime"] = new DateTimeOffset(d.LastWriteTimeUtc).ToUnixTimeMilliseconds(),
                    });
                foreach (var f in di.GetFiles())
                    entries.Add(new JsonObject
                    {
                        ["name"] = f.Name,
                        ["dir"] = false,
                        ["size"] = f.Length,
                        ["mtime"] = new DateTimeOffset(f.LastWriteTimeUtc).ToUnixTimeMilliseconds(),
                    });
            }
        }
        catch (Exception ex)
        {
            error = ex.Message;
        }

        _ws.SendJson(new JsonObject
        {
            ["type"] = "fs-list-result",
            ["to"] = from,
            ["reqId"] = reqId,
            ["path"] = path,
            ["entries"] = entries,
            ["error"] = error,
        });
    }

    private void HandleGet(JsonNode msg)
    {
        var from = msg["from"]!.GetValue<string>();
        var path = msg["path"]?.GetValue<string>() ?? "";
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        _ = Task.Run(() => SendFileAsync(from, path, xferId));
    }

    public async Task SendFileAsync(string toPeer, string localPath, uint xferId)
    {
        try
        {
            var fi = new FileInfo(localPath);
            _ws.SendJson(new JsonObject
            {
                ["type"] = "fs-begin",
                ["to"] = toPeer,
                ["xferId"] = xferId,
                ["name"] = fi.Name,
                ["size"] = fi.Length,
            });

            // Payload layout (before encryption): [xferId(4 BE)][chunk bytes].
            // WsClient prepends the frame-type byte (2) and encrypts the payload.
            var idbytes = new byte[4];
            idbytes[0] = (byte)(xferId >> 24);
            idbytes[1] = (byte)(xferId >> 16);
            idbytes[2] = (byte)(xferId >> 8);
            idbytes[3] = (byte)xferId;

            using var fs = fi.OpenRead();
            var buf = new byte[ChunkSize];
            int n;
            long sent = 0;
            while ((n = await fs.ReadAsync(buf)) > 0)
            {
                var payload = new byte[4 + n];
                Buffer.BlockCopy(idbytes, 0, payload, 0, 4);
                Buffer.BlockCopy(buf, 0, payload, 4, n);
                // File data must not be dropped: retry while the socket is backed up.
                int tries = 0;
                while (!_ws.SendBinary(2, payload) && tries++ < 600) await Task.Delay(50);
                sent += n;
                TransferStatus?.Invoke($"Sending {fi.Name}: {sent * 100 / Math.Max(1, fi.Length)}%");
            }

            _ws.SendJson(new JsonObject { ["type"] = "fs-end", ["to"] = toPeer, ["xferId"] = xferId, ["ok"] = true });
            TransferStatus?.Invoke($"Sent {fi.Name}");
        }
        catch (Exception ex)
        {
            _ws.SendJson(new JsonObject
            {
                ["type"] = "fs-end",
                ["to"] = toPeer,
                ["xferId"] = xferId,
                ["ok"] = false,
                ["error"] = ex.Message,
            });
            TransferStatus?.Invoke($"Send failed: {ex.Message}");
        }
    }

    // ---------- receiving files ----------

    /// <summary>Register expectations for a download we initiated (fs-get).</summary>
    private readonly Dictionary<uint, (Action<string> onDone, Action<string> onError)> _pendingGets = new();

    public void ExpectDownload(uint xferId, Action<string> onDone, Action<string> onError)
    {
        lock (_incoming) _pendingGets[xferId] = (onDone, onError);
    }

    private void HandleBegin(JsonNode msg)
    {
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        var name = SanitizeFileName(msg["name"]?.GetValue<string>() ?? "file.bin");
        var size = msg["size"]?.GetValue<long>() ?? 0;

        try
        {
            Directory.CreateDirectory(IncomingDir);
            var finalPath = UniquePath(Path.Combine(IncomingDir, name));
            var tempPath = finalPath + ".part";
            var t = new IncomingTransfer
            {
                Stream = File.Create(tempPath),
                FinalPath = finalPath,
                TempPath = tempPath,
                Expected = size,
            };
            lock (_incoming)
            {
                if (_pendingGets.Remove(xferId, out var cbs))
                {
                    t.OnDone = cbs.onDone;
                    t.OnError = cbs.onError;
                }
                _incoming[xferId] = t;
            }
            TransferStatus?.Invoke($"Receiving {name}...");
        }
        catch (Exception ex)
        {
            TransferStatus?.Invoke($"Receive failed: {ex.Message}");
        }
    }

    private void HandleEnd(JsonNode msg)
    {
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        var ok = msg["ok"]?.GetValue<bool>() ?? false;
        IncomingTransfer? t;
        lock (_incoming)
        {
            if (!_incoming.Remove(xferId, out t)) return;
        }
        t.Stream.Dispose();
        if (ok)
        {
            File.Move(t.TempPath, t.FinalPath);
            TransferStatus?.Invoke($"Received {Path.GetFileName(t.FinalPath)}");
            t.OnDone?.Invoke(t.FinalPath);
        }
        else
        {
            File.Delete(t.TempPath);
            var err = msg["error"]?.GetValue<string>() ?? "unknown error";
            TransferStatus?.Invoke($"Receive failed: {err}");
            t.OnError?.Invoke(err);
        }
    }

    private static string SanitizeFileName(string name)
    {
        foreach (var c in Path.GetInvalidFileNameChars()) name = name.Replace(c, '_');
        return string.IsNullOrWhiteSpace(name) ? "file.bin" : name;
    }

    private static string UniquePath(string path)
    {
        if (!File.Exists(path)) return path;
        var dir = Path.GetDirectoryName(path)!;
        var stem = Path.GetFileNameWithoutExtension(path);
        var ext = Path.GetExtension(path);
        for (int i = 1; ; i++)
        {
            var candidate = Path.Combine(dir, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
    }
}
