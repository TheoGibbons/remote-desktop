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
    private readonly Dictionary<uint, OutgoingTransfer> _outgoing = new();
    // Downloads given up before their fs-begin. An older sender ignores
    // fs-cancel and sends the file anyway, which must not be kept as a push.
    private readonly HashSet<uint> _cancelledGets = new();

    private class IncomingTransfer
    {
        public required FileStream Stream;
        public required string FinalPath;
        public required string TempPath;
        public required string Peer;
        public long Expected;
        public long Received;
        public int Percent = -1; // last reported, so each whole percent is reported once
        public Action<string>? OnDone; // final path or null via OnError
        public Action<string>? OnError;
    }

    /// <summary>A file this device is sending, until its fs-end.</summary>
    private class OutgoingTransfer
    {
        public required string Peer;
        public readonly CancellationTokenSource Cancel = new();
        public volatile string? CancelReason; // what fs-end tells the receiver
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

    /// <param name="sending">true while this PC sends the file, false while it receives it.</param>
    public delegate void TransferProgressHandler(uint xferId, bool sending, int percent, string name);

    public event TransferProgressHandler? TransferProgress;

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
            // Only the device receiving the file can call it off.
            case "fs-cancel": StopSending(XferId(msg), msg["from"]?.GetValue<string>() ?? "", "Cancelled by the receiver"); break;
        }
    }

    private static uint XferId(JsonNode msg) => (uint)(msg["xferId"]?.GetValue<long>() ?? 0);

    /// <summary>Whether a transfer is still waiting to begin, sending or receiving.</summary>
    public bool IsActive(uint xferId)
    {
        lock (_outgoing) if (_outgoing.ContainsKey(xferId)) return true;
        lock (_incoming) return _pendingGets.ContainsKey(xferId) || _incoming.ContainsKey(xferId);
    }

    /// <summary>
    /// Stops a transfer this device started. A download is dropped and its
    /// sender asked to stop with fs-cancel; an upload ends with a failed fs-end.
    /// </summary>
    public void Cancel(uint xferId)
    {
        if (StopSending(xferId, null, "Cancelled by the sender")) return;
        string? peer = null;
        IncomingTransfer? t;
        lock (_incoming)
        {
            if (_pendingGets.Remove(xferId, out var get))
            {
                peer = get.peer;
                _cancelledGets.Add(xferId);
            }
            if (_incoming.Remove(xferId, out t)) peer = t.Peer;
        }
        if (peer == null) return;
        if (t != null)
        {
            t.Stream.Dispose();
            try { File.Delete(t.TempPath); } catch { /* Nothing more to do for a cancelled file. */ }
            TransferStatus?.Invoke($"Cancelled {Path.GetFileName(t.FinalPath)}");
        }
        _ws.SendJson(new JsonObject { ["type"] = "fs-cancel", ["to"] = peer, ["xferId"] = xferId });
    }

    /// <param name="peer">Only stop the send if it goes to this peer; null for any.</param>
    /// <returns>true if this device was sending that file.</returns>
    private bool StopSending(uint xferId, string? peer, string reason)
    {
        OutgoingTransfer? t;
        lock (_outgoing)
        {
            if (!_outgoing.TryGetValue(xferId, out t)) return false;
        }
        if (peer != null && t.Peer != peer) return false;
        t.CancelReason = reason;
        t.Cancel.Cancel();
        return true;
    }

    private OutgoingTransfer Track(uint xferId, string peer)
    {
        lock (_outgoing)
        {
            if (!_outgoing.TryGetValue(xferId, out var t)) _outgoing[xferId] = t = new OutgoingTransfer { Peer = peer };
            return t;
        }
    }

    private void Untrack(uint xferId)
    {
        lock (_outgoing) _outgoing.Remove(xferId);
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
        int percent;
        string name;
        lock (_incoming)
        {
            if (!_incoming.TryGetValue(id, out var t)) return;
            t.Stream.Write(frame, 5, frame.Length - 5);
            t.Received += frame.Length - 5;
            if (t.Expected <= 0) return;
            percent = (int)Math.Min(100, t.Received * 100 / t.Expected);
            if (percent == t.Percent) return;
            t.Percent = percent;
            name = Path.GetFileName(t.FinalPath);
        }
        // Outside the lock: handlers wait on the UI thread, which takes it in ExpectDownload.
        TransferProgress?.Invoke(id, false, percent, name);
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
            ["downloadsPath"] = IncomingDir,
            ["entries"] = entries,
            ["error"] = error,
        });
    }

    private void HandleGet(JsonNode msg)
    {
        var from = msg["from"]!.GetValue<string>();
        var path = msg["path"]?.GetValue<string>() ?? "";
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        // Tracked before the worker starts, so an fs-cancel right behind the request still stops it.
        Track(xferId, from);
        _ = Task.Run(() => SendFileAsync(from, path, xferId));
    }

    /// <returns>true if the whole file was sent and fs-end ok was signalled.</returns>
    public async Task<bool> SendFileAsync(string toPeer, string localPath, uint xferId)
    {
        var transfer = Track(xferId, toPeer);
        var cancel = transfer.Cancel.Token;
        try
        {
            cancel.ThrowIfCancellationRequested();
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
            while ((n = await fs.ReadAsync(buf, cancel)) > 0)
            {
                var payload = new byte[4 + n];
                Buffer.BlockCopy(idbytes, 0, payload, 0, 4);
                Buffer.BlockCopy(buf, 0, payload, 4, n);
                // File data must not be dropped: retry while the socket is backed up.
                int tries = 0;
                while (!_ws.SendBinary(2, payload) && tries++ < 600) await Task.Delay(50, cancel);
                sent += n;
                TransferProgress?.Invoke(xferId, true, (int)(sent * 100 / Math.Max(1, fi.Length)), fi.Name);
            }
            // A read already under way finishes despite the token.
            cancel.ThrowIfCancellationRequested();

            Untrack(xferId);
            _ws.SendJson(new JsonObject { ["type"] = "fs-end", ["to"] = toPeer, ["xferId"] = xferId, ["ok"] = true });
            TransferStatus?.Invoke($"Sent {fi.Name}");
            return true;
        }
        catch (Exception ex)
        {
            Untrack(xferId);
            var cancelled = transfer.CancelReason;
            _ws.SendJson(new JsonObject
            {
                ["type"] = "fs-end",
                ["to"] = toPeer,
                ["xferId"] = xferId,
                ["ok"] = false,
                ["error"] = cancelled ?? ex.Message,
            });
            TransferStatus?.Invoke(cancelled != null ? $"Cancelled {Path.GetFileName(localPath)}" : $"Send failed: {ex.Message}");
            return false;
        }
    }

    // ---------- receiving files ----------

    /// <summary>Register expectations for a download we initiated (fs-get).</summary>
    private readonly Dictionary<uint, (string peer, Action<string> onDone, Action<string> onError)> _pendingGets = new();

    /// <param name="peer">The device the file is asked from; Cancel tells it to stop.</param>
    public void ExpectDownload(uint xferId, string peer, Action<string> onDone, Action<string> onError)
    {
        lock (_incoming) _pendingGets[xferId] = (peer, onDone, onError);
    }

    private void HandleBegin(JsonNode msg, bool peerAccessAllowed)
    {
        var xferId = (uint)(msg["xferId"]?.GetValue<long>() ?? 0);
        var name = SanitizeFileName(msg["name"]?.GetValue<string>() ?? "file.bin");
        var size = msg["size"]?.GetValue<long>() ?? 0;

        lock (_incoming)
        {
            if (_cancelledGets.Remove(xferId)) return;
            // An unsolicited push (no matching fs-get from us) needs consent;
            // without a registered transfer its chunks are dropped on arrival.
            if (!peerAccessAllowed && !_pendingGets.ContainsKey(xferId)) return;
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
                Peer = msg["from"]?.GetValue<string>() ?? "",
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
        Action<string>? onRefused = null;
        lock (_incoming)
        {
            _cancelledGets.Remove(xferId);
            // A refused or missing file ends without fs-begin. Tell whoever asked
            // for it, or its download waits forever.
            if (!_incoming.Remove(xferId, out t) && !ok && _pendingGets.Remove(xferId, out var get)) onRefused = get.onError;
        }
        if (t == null)
        {
            if (onRefused == null) return;
            var refusal = msg["error"]?.GetValue<string>() ?? "unknown error";
            TransferStatus?.Invoke($"Receive failed: {refusal}");
            onRefused(refusal);
            return;
        }
        try
        {
            t.Stream.Dispose();
            if (!ok) throw new IOException(msg["error"]?.GetValue<string>() ?? "unknown error");
            if (t.Expected >= 0 && t.Received != t.Expected) throw new IOException("Incomplete file transfer");
            File.Move(t.TempPath, t.FinalPath);
            _ws.SendJson(new JsonObject
            {
                ["type"] = "fs-saved", ["to"] = msg["from"]?.GetValue<string>(),
                ["xferId"] = xferId, ["path"] = t.FinalPath,
            });
            TransferStatus?.Invoke($"Received {Path.GetFileName(t.FinalPath)}");
            t.OnDone?.Invoke(t.FinalPath);
        }
        catch (Exception ex)
        {
            try { File.Delete(t.TempPath); } catch { /* Report the original save failure. */ }
            var err = ex.Message;
            _ws.SendJson(new JsonObject
            {
                ["type"] = "fs-saved", ["to"] = msg["from"]?.GetValue<string>(),
                ["xferId"] = xferId, ["error"] = err,
            });
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
