using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// Photos, videos, screenshots or screen recordings a phone has just saved
/// (fs-offer), and their download into this PC's Downloads with fs-get.
/// Used from one thread; transfer callbacks are posted back to it.
/// </summary>
public sealed class CaptureOffer
{
    /// <summary>One file on offer. <see cref="Path"/> is the phone's own, for fs-get.</summary>
    public sealed record Capture(string Path, string Name, long Size, string Kind);

    public enum Stage { Offered, Downloading, Saved, Failed }

    // Bounds what one message can make this PC do.
    private const int MaxFiles = 200;
    private const int MaxThumbnailBytes = 256 * 1024;

    private readonly WsClient _ws;
    private readonly FsService _fs;
    private readonly Action<Action> _post;
    private readonly Dictionary<uint, Capture> _running = new();
    private readonly Dictionary<uint, int> _percent = new();
    private readonly HashSet<Capture> _done = new(); // saved, so a retry skips them
    private readonly List<string> _saved = new();
    private int _attempted;
    private int _finished;

    public string PeerId { get; }
    public string PeerName { get; }
    public IReadOnlyList<Capture> Files { get; }
    /// <summary>A small JPEG of the newest file, if the phone could make one.</summary>
    public byte[]? Thumbnail { get; }
    public Stage Status { get; private set; } = Stage.Offered;
    /// <summary>Why the last download failed.</summary>
    public string? Error { get; private set; }
    /// <summary>False once the phone has left: nothing more can be asked of it.</summary>
    public bool PeerOnline { get; private set; } = true;
    /// <summary>Where the files were saved, in the order they finished.</summary>
    public IReadOnlyList<string> SavedPaths => _saved;
    /// <summary>Raised on this offer's thread whenever anything above changes.</summary>
    public event Action? Changed;

    private CaptureOffer(WsClient ws, FsService fs, string peerId, string peerName,
        List<Capture> files, byte[]? thumbnail, Action<Action> post)
    {
        _ws = ws;
        _fs = fs;
        _post = post;
        PeerId = peerId;
        PeerName = peerName;
        Files = files;
        Thumbnail = thumbnail;
    }

    /// <param name="post">Runs an action on the thread this offer is used from.</param>
    /// <returns>null when the message offers nothing that could be downloaded.</returns>
    public static CaptureOffer? Parse(WsClient ws, FsService fs, string peerId, string peerName,
        JsonNode msg, Action<Action> post)
    {
        if (msg["files"] is not JsonArray list) return null;
        var files = new List<Capture>();
        foreach (var item in list.Take(MaxFiles))
        {
            var path = Text(item?["path"]);
            if (string.IsNullOrEmpty(path)) continue;
            var name = Text(item!["name"]);
            if (string.IsNullOrEmpty(name)) name = path[(path.LastIndexOf('/') + 1)..];
            var size = item["size"] is JsonValue s && s.TryGetValue<long>(out var bytes) ? bytes : 0;
            files.Add(new Capture(path, name, size, Text(item["kind"]) ?? ""));
        }
        if (files.Count == 0) return null;
        return new CaptureOffer(ws, fs, peerId, peerName, files, Decode(Text(msg["thumb"])), post);
    }

    private static string? Text(JsonNode? node) =>
        node is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    private static byte[]? Decode(string? base64)
    {
        if (string.IsNullOrEmpty(base64) || base64.Length > MaxThumbnailBytes / 3 * 4 + 4) return null;
        var bytes = new byte[base64.Length * 3 / 4];
        return Convert.TryFromBase64String(base64, bytes, out var length) && length > 0 ? bytes[..length] : null;
    }

    /// <summary>"New screenshot", "3 new photos", or "2 new files" for a mix.</summary>
    public string Title
    {
        get
        {
            var kinds = Files.Select(f => f.Kind).Distinct().ToList();
            var (one, many) = kinds.Count == 1 ? Noun(kinds[0]) : ("file", "files");
            return Files.Count == 1 ? $"New {one}" : $"{Files.Count} new {many}";
        }
    }

    private static (string One, string Many) Noun(string kind) => kind switch
    {
        "photo" => ("photo", "photos"),
        "video" => ("video", "videos"),
        "screenshot" => ("screenshot", "screenshots"),
        "screen-recording" => ("screen recording", "screen recordings"),
        _ => ("file", "files"),
    };

    /// <summary>The first file's name, and how many more there are.</summary>
    public string Names => Files.Count == 1 ? Files[0].Name : $"{Files[0].Name} and {Files.Count - 1} more";

    /// <summary>What the phone said the files add up to; 0 if it didn't.</summary>
    public long TotalSize => Files.Sum(f => Math.Max(0, f.Size));

    /// <summary>How far the current download has got, 0-100.</summary>
    public int Percent => _attempted == 0 ? 0 : (_finished * 100 + _percent.Values.Sum()) / _attempted;

    /// <summary>Asks the phone for every file not saved yet, which also retries after a failure.</summary>
    public void Download()
    {
        if (Status is Stage.Downloading or Stage.Saved || !PeerOnline) return;
        var wanted = Files.Where(f => !_done.Contains(f)).ToList();
        Status = Stage.Downloading;
        Error = null;
        _attempted = wanted.Count;
        _finished = 0;
        _fs.TransferProgress += OnProgress;
        foreach (var file in wanted)
        {
            var id = _fs.NextXferId();
            _running[id] = file;
            _percent[id] = 0;
            _fs.ExpectDownload(id, PeerId,
                path => _post(() => Finished(id, path, null)),
                error => _post(() => Finished(id, null, error)));
            _ws.SendJson(new JsonObject { ["type"] = "fs-get", ["to"] = PeerId, ["path"] = file.Path, ["xferId"] = id });
        }
        Changed?.Invoke();
    }

    // On the socket thread, for every transfer in progress.
    private void OnProgress(uint xferId, bool sending, int percent, string name)
    {
        if (sending) return;
        _post(() =>
        {
            if (!_percent.ContainsKey(xferId)) return;
            _percent[xferId] = percent;
            Changed?.Invoke();
        });
    }

    private void Finished(uint xferId, string? path, string? error)
    {
        if (!_running.Remove(xferId, out var file)) return; // cancelled meanwhile
        _percent.Remove(xferId);
        _finished++;
        if (path != null)
        {
            _saved.Add(path);
            _done.Add(file);
        }
        else Error = error ?? "unknown error";
        if (_running.Count == 0)
        {
            _fs.TransferProgress -= OnProgress;
            Status = _done.Count == Files.Count ? Stage.Saved : Stage.Failed;
        }
        Changed?.Invoke();
    }

    /// <summary>Stops the downloads still running. Files already saved stay.</summary>
    public void Cancel()
    {
        foreach (var id in _running.Keys) _fs.Cancel(id);
        _running.Clear();
        _percent.Clear();
        _fs.TransferProgress -= OnProgress;
    }

    /// <summary>The phone left, or this PC lost the relay: a download can't finish.</summary>
    public void PeerGone()
    {
        PeerOnline = false;
        if (Status != Stage.Downloading) return;
        Cancel();
        Status = Stage.Failed;
        Error = $"{PeerName} went offline";
        Changed?.Invoke();
    }
}
