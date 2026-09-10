using System.IO;
using System.Runtime.InteropServices;
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

    // Straight into Downloads, not a subfolder of it: received files should
    // land where the user already looks for downloads. UniquePath below keeps
    // them from colliding with what is already there.
    public string IncomingDir { get; set; } = DefaultDownloadsDir();

    // FOLDERID_Downloads. There is no Environment.SpecialFolder for it, and
    // assuming %USERPROFILE%\Downloads is wrong for anyone who has relocated
    // the folder — files would land somewhere they never look.
    private static readonly Guid FolderIdDownloads = new("374DE290-123F-4565-9164-39C4925E467B");

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern int SHGetKnownFolderPath(in Guid rfid, uint dwFlags, IntPtr hToken, out IntPtr path);

    private static string DefaultDownloadsDir()
    {
        try
        {
            if (SHGetKnownFolderPath(FolderIdDownloads, 0, IntPtr.Zero, out IntPtr path) == 0)
            {
                try
                {
                    var resolved = Marshal.PtrToStringUni(path);
                    if (!string.IsNullOrWhiteSpace(resolved)) return resolved;
                }
                finally
                {
                    Marshal.FreeCoTaskMem(path);
                }
            }
        }
        catch { /* fall through to the profile-relative guess */ }

        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
    }

    public event Action<string>? TransferStatus;

    public FsService(WsClient ws)
    {
        _ws = ws;
    }

    // Random id (kept within positive int range so it round-trips through both
    // JSON and the 4-byte chunk header on the Kotlin side without sign issues).
    public uint NextXferId() => (uint)Random.Shared.Next(1, int.MaxValue);

    // ---------- serving requests from the peer ----------

    /// <param name="peerAccessAllowed">Whether the user consents to peers reading
    /// this machine's files or pushing files to it. Replies to transfers this
    /// device initiated itself (fs-get downloads) are always processed.</param>
    public void HandleJson(JsonNode msg, bool peerAccessAllowed)
    {
        switch (msg["type"]?.GetValue<string>())
        {
            case "fs-list": if (peerAccessAllowed) HandleList(msg); else DenyList(msg); break;
            case "fs-get": if (peerAccessAllowed) HandleGet(msg); else DenyGet(msg); break;
            case "fs-begin": HandleBegin(msg, peerAccessAllowed); break;
            case "fs-end": HandleEnd(msg); break;
        }
    }

    private const string DeniedError = "File access is disabled on the remote device";

    private void DenyList(JsonNode msg)
    {
        _ws.SendJson(new JsonObject
        {
            ["type"] = "fs-list-result",
            ["to"] = msg["from"]!.GetValue<string>(),
            ["reqId"] = msg["reqId"]?.GetValue<string>() ?? "",
            ["path"] = msg["path"]?.GetValue<string>() ?? "",
            ["entries"] = new JsonArray(),
            ["error"] = DeniedError,
        });
    }

    private void DenyGet(JsonNode msg)
    {
        _ws.SendJson(new JsonObject
        {
            ["type"] = "fs-end",
            ["to"] = msg["from"]!.GetValue<string>(),
            ["xferId"] = msg["xferId"]?.GetValue<long>() ?? 0,
            ["ok"] = false,
            ["error"] = DeniedError,
        });
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

    /// <returns>true if the whole file was sent and fs-end ok was signalled.</returns>
    public async Task<bool> SendFileAsync(string toPeer, string localPath, uint xferId)
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
            return true;
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
            return false;
        }
    }

    // ---------- receiving files ----------

    /// <summary>Register expectations for a download we initiated (fs-get).</summary>
    private readonly Dictionary<uint, (Action<string> onDone, Action<string> onError)> _pendingGets = new();

    public void ExpectDownload(uint xferId, Action<string> onDone, Action<string> onError)
    {
        lock (_incoming) _pendingGets[xferId] = (onDone, onError);
    }

    private void HandleBegin(JsonNode msg, bool peerAccessAllowed)
    {
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        var name = SanitizeFileName(msg["name"]?.GetValue<string>() ?? "file.bin");
        var size = msg["size"]?.GetValue<long>() ?? 0;

        // An unsolicited push (no matching fs-get from us) needs consent;
        // without a registered transfer its chunks are dropped on arrival.
        if (!peerAccessAllowed)
        {
            lock (_incoming)
            {
                if (!_pendingGets.ContainsKey(xferId)) return;
            }
        }

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
