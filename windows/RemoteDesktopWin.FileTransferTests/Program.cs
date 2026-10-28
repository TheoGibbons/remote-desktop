using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

internal static class Program
{
    private static void Main()
    {
        var dir = Path.Combine(Path.GetTempPath(), "remote-desktop-file-tests-" + Guid.NewGuid());
        Directory.CreateDirectory(dir);
        try
        {
            var ws = new WsClient();
            var fs = new FsService(ws) { IncomingDir = dir };
            void Begin(int id, string name, long size, bool allowed = true) => fs.HandleJson(new JsonObject
            {
                ["type"] = "fs-begin", ["from"] = "phone", ["xferId"] = (long)id, ["name"] = name, ["size"] = size,
            }, allowed);
            void Chunk(int id, byte[] bytes) => fs.HandleFileChunk(new byte[]
            {
                2, (byte)(id >> 24), (byte)(id >> 16), (byte)(id >> 8), (byte)id,
            }.Concat(bytes).ToArray());
            void End(int id, bool ok = true) => fs.HandleJson(new JsonObject
            {
                ["type"] = "fs-end", ["from"] = "phone", ["xferId"] = (long)id, ["ok"] = ok,
            }, true);
            JsonObject Reply(int id) => ws.Messages.Single(m => m["type"]?.GetValue<string>() == "fs-saved" &&
                m["xferId"]?.GetValue<uint>() == (uint)id);

            Begin(101, "photo.jpg", 3);
            Chunk(101, new byte[] { 1, 2, 3 });
            Check(ws.Messages.Count == 0, "No confirmation before fs-end");
            End(101);
            Check(Reply(101)["to"]?.GetValue<string>() == "phone", "Confirmation is routed to the sending phone");
            var path = Reply(101)["path"]!.GetValue<string>();
            Check(File.ReadAllBytes(path).SequenceEqual(new byte[] { 1, 2, 3 }), "Confirmed file contains all bytes");
            Begin(102, "photo.jpg", 1);
            Chunk(102, new byte[] { 9 });
            End(102);
            var duplicate = Reply(102)["path"]!.GetValue<string>();
            Check(path != duplicate && File.Exists(path) && File.Exists(duplicate), "Duplicate names preserve both files");

            Begin(103, "partial.jpg", 3);
            Chunk(103, new byte[] { 1 });
            End(103);
            Check(Reply(103)["error"]?.GetValue<string>() == "Incomplete file transfer", "Dropped chunks report a failure");
            Check(!File.Exists(Path.Combine(dir, "partial.jpg")), "Incomplete file is never saved as complete");
            Begin(104, "cancelled.jpg", 3);
            End(104, false);
            Check(Reply(104)["error"] != null, "Cancellation reports failure");

            Begin(105, "cloud.jpg", -1);
            Chunk(105, new byte[] { 7, 8 });
            End(105);
            Check(File.Exists(Reply(105)["path"]!.GetValue<string>()), "Unknown sizes are supported");
            Begin(106, "empty.txt", 0);
            End(106);
            Check(new FileInfo(Reply(106)["path"]!.GetValue<string>()).Length == 0, "Empty files are supported");

            Begin(107, "blocked.jpg", 1);
            Chunk(107, new byte[] { 1 });
            Directory.CreateDirectory(Path.Combine(dir, "blocked.jpg"));
            End(107);
            Check(Reply(107)["error"] != null, "Save failures reach the phone");
            Check(!Directory.EnumerateFiles(dir, "*.part").Any(), "Failed transfers clean up temporary files");

            Begin(108, "denied.jpg", 1, false);
            Chunk(108, new byte[] { 1 });
            End(108);
            Check(!File.Exists(Path.Combine(dir, "denied.jpg")), "Denied file access never writes a file");
            Console.WriteLine("PASS: complete, duplicate, incomplete, cancelled, unknown-size, empty, denied and failed-save transfers");

            CheckCancelling(ws, fs, dir, Begin, Chunk, End);
            CheckCaptureOffers(dir);
        }
        finally { Directory.Delete(dir, true); }
        CheckSortOrders();
    }

    private static void CheckCaptureOffers(string dir)
    {
        var ws = new WsClient();
        var fs = new FsService(ws) { IncomingDir = dir };
        var posted = new Queue<Action>();
        void RunPosted() { while (posted.TryDequeue(out var action)) action(); }
        CaptureOffer? Offer(JsonArray files, string? thumb = null) => CaptureOffer.Parse(ws, fs, "phone", "Pixel",
            new JsonObject { ["type"] = "fs-offer", ["files"] = files, ["thumb"] = thumb }, posted.Enqueue);
        JsonObject File(string path, string kind, long size = 0, string? name = null) => new()
        {
            ["path"] = path, ["name"] = name, ["size"] = size, ["kind"] = kind,
        };
        List<JsonObject> Gets() => ws.Messages.Where(m => m["type"]?.GetValue<string>() == "fs-get").ToList();
        // The phone answering an fs-get, as FsHandler does.
        void Send(JsonObject get, byte[]? bytes, string? error = null)
        {
            // As a long, the way a parsed message holds it.
            long id = get["xferId"]!.GetValue<uint>();
            if (bytes != null)
            {
                var name = get["path"]!.GetValue<string>().Split('/')[^1];
                fs.HandleJson(new JsonObject
                {
                    ["type"] = "fs-begin", ["from"] = "phone", ["xferId"] = id, ["name"] = name, ["size"] = (long)bytes.Length,
                }, peerAccessAllowed: false); // fetched, not pushed, so file access on this PC doesn't matter
                fs.HandleFileChunk(new byte[] { 2, (byte)(id >> 24), (byte)(id >> 16), (byte)(id >> 8), (byte)id }.Concat(bytes).ToArray());
            }
            fs.HandleJson(new JsonObject
            {
                ["type"] = "fs-end", ["from"] = "phone", ["xferId"] = id, ["ok"] = error == null, ["error"] = error,
            }, true);
            RunPosted();
        }

        var burst = Offer(new JsonArray
        {
            File("/storage/emulated/0/DCIM/Camera/a.jpg", "photo", 3, "a.jpg"),
            File("/storage/emulated/0/DCIM/Camera/b.jpg", "photo", 2),
            new JsonObject { ["name"] = "no-path.jpg", ["kind"] = "photo" },
        }, Convert.ToBase64String(new byte[] { 0xFF, 0xD8, 0xFF }))!;
        Check(burst.Files.Count == 2 && burst.Files[1].Name == "b.jpg", "Offered files without a path are skipped, and names default to the path's");
        Check(burst.Title == "2 new photos" && burst.Names == "a.jpg and 1 more" && burst.TotalSize == 5, "A burst is described as one offer");
        Check(burst.Thumbnail!.SequenceEqual(new byte[] { 0xFF, 0xD8, 0xFF }), "The preview arrives as sent");

        var changes = 0;
        burst.Changed += () => changes++;
        burst.Download();
        burst.Download(); // a second click while it runs asks for nothing more
        var gets = Gets();
        Check(gets.Count == 2 && gets.All(g => g["to"]?.GetValue<string>() == "phone") &&
              gets[0]["path"]?.GetValue<string>() == "/storage/emulated/0/DCIM/Camera/a.jpg",
            "Clicking asks the phone for each file once");
        Check(burst.Status == CaptureOffer.Stage.Downloading && changes == 1, "The card hears the download start");
        Send(gets[0], new byte[] { 1, 2, 3 });
        Check(burst.Status == CaptureOffer.Stage.Downloading && burst.SavedPaths.Count == 1 && burst.Percent == 50,
            "One file of two arrived");
        Check(System.IO.File.ReadAllBytes(burst.SavedPaths[0]).SequenceEqual(new byte[] { 1, 2, 3 }), "It was saved whole");
        Send(gets[1], null, "No such file");
        Check(burst.Status == CaptureOffer.Stage.Failed && burst.Error == "No such file", "A refused file fails the offer, saying why");

        burst.Download();
        var retry = Gets().Skip(2).ToList();
        Check(retry.Count == 1 && retry[0]["path"]?.GetValue<string>() == "/storage/emulated/0/DCIM/Camera/b.jpg",
            "Trying again asks only for the file still missing");
        Send(retry[0], new byte[] { 4, 5 });
        Check(burst.Status == CaptureOffer.Stage.Saved && burst.SavedPaths.Count == 2 &&
              burst.SavedPaths.All(p => Path.GetDirectoryName(p) == dir), "Both files end up in Downloads");
        Console.WriteLine("PASS: an offer downloads each file on click, says why one failed, and retries only that one");

        var shot = Offer(new JsonArray { File("/storage/emulated/0/Pictures/Screenshots/s.png", "screenshot", 10) })!;
        Check(shot.Title == "New screenshot" && shot.Thumbnail == null, "One screenshot, without a preview");
        shot.Download();
        var get = Gets().Last();
        var id = get["xferId"]!.GetValue<uint>();
        Check(fs.IsActive(id), "Its download is under way");
        shot.PeerGone();
        Check(shot.Status == CaptureOffer.Stage.Failed && shot.Error == "Pixel went offline" && !fs.IsActive(id),
            "A phone that leaves fails the download and drops it");
        Check(ws.Messages.Any(m => m["type"]?.GetValue<string>() == "fs-cancel" && m["xferId"]?.GetValue<uint>() == id),
            "The phone is asked to stop, in case it is still there");
        var asked = Gets().Count;
        shot.Download();
        Check(Gets().Count == asked && shot.Status == CaptureOffer.Stage.Failed, "Nothing is asked of a phone that has gone");

        var clip = Offer(new JsonArray { File("/storage/emulated/0/DCIM/Screen recordings/r.mp4", "screen-recording") })!;
        clip.Download();
        clip.Cancel();
        var clipId = Gets().Last()["xferId"]!.GetValue<uint>();
        Send(Gets().Last(), new byte[] { 9 });
        Check(!fs.IsActive(clipId) && clip.SavedPaths.Count == 0 && !Directory.EnumerateFiles(dir, "r*.mp4").Any(),
            "A cancelled download is never saved");

        Check(Offer(new JsonArray { File("/x/a.jpg", "photo"), File("/x/b.mp4", "video") })!.Title == "2 new files" &&
              clip.Title == "New screen recording" &&
              Offer(new JsonArray { File("/x/c", "something-new") })!.Title == "New file", "Titles name the kind of file");
        Check(Offer(new JsonArray())  == null && Offer(new JsonArray { new JsonObject { ["path"] = 7 } }) == null &&
              CaptureOffer.Parse(ws, fs, "phone", "Pixel", new JsonObject { ["type"] = "fs-offer" }, posted.Enqueue) == null,
            "An offer of nothing usable shows nothing");
        Check(Offer(new JsonArray { File("/x/a.jpg", "photo") }, "not base64!")!.Thumbnail == null &&
              Offer(new JsonArray { File("/x/a.jpg", "photo") }, "")!.Thumbnail == null, "A broken or empty preview is dropped");
        Console.WriteLine("PASS: offers fail cleanly when the phone leaves or the download is cancelled, and name what they hold");
    }

    private static void CheckCancelling(WsClient ws, FsService fs, string dir,
        Action<int, string, long, bool> begin, Action<int, byte[]> chunk, Action<int, bool> end)
    {
        var saved = new List<string>();
        var failed = new List<string>();
        JsonObject Last(string type, int id) => ws.Messages.Last(m => m["type"]?.GetValue<string>() == type &&
            m["xferId"]?.GetValue<uint>() == (uint)id);
        bool Any(string type, int id) => ws.Messages.Any(m => m["type"]?.GetValue<string>() == type &&
            m["xferId"]?.GetValue<uint>() == (uint)id);
        void Cancel(string from, int id) => fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-cancel", ["from"] = from, ["xferId"] = (long)id,
        }, true);

        fs.ExpectDownload(201, "phone", saved.Add, failed.Add);
        begin(201, "big.bin", 10, false);
        chunk(201, new byte[] { 1, 2, 3 });
        Check(fs.IsActive(201), "A download is active while it arrives");
        fs.Cancel(201);
        Check(!fs.IsActive(201), "A cancelled download is no longer active");
        Check(Last("fs-cancel", 201)["to"]?.GetValue<string>() == "phone", "Cancelling a download asks the phone to stop");
        // An older phone keeps sending; the rest is ignored.
        chunk(201, new byte[] { 4, 5, 6, 7, 8, 9, 10 });
        end(201, true);
        Check(!Any("fs-saved", 201) && saved.Count == 0 && failed.Count == 0, "A cancelled download is never saved or reported");
        Check(!Directory.EnumerateFiles(dir, "big*").Any(), "A cancelled download leaves no partial file");

        fs.ExpectDownload(202, "phone", saved.Add, failed.Add);
        fs.Cancel(202);
        Check(Any("fs-cancel", 202), "A download can be cancelled before it begins");
        begin(202, "late.bin", 1, true);
        chunk(202, new byte[] { 1 });
        end(202, true);
        Check(!Directory.EnumerateFiles(dir, "late*").Any() && !Any("fs-saved", 202),
            "A download cancelled before it began is not kept as a push when an older phone sends it anyway");

        fs.ExpectDownload(203, "phone", saved.Add, failed.Add);
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-end", ["from"] = "phone", ["xferId"] = 203L, ["ok"] = false, ["error"] = "No such file",
        }, true);
        Check(failed.SequenceEqual(new[] { "No such file" }) && !fs.IsActive(203), "A refused download reports why instead of waiting");
        Console.WriteLine("PASS: cancelled downloads, before and after they begin, and refused downloads");

        // Three chunks, so a cancel after the first leaves some unsent.
        var source = Path.Combine(dir, "source.bin");
        File.WriteAllBytes(source, new byte[3 * 256 * 1024]);
        ws.OnChunk = n => Cancel(n == 1 ? "someone-else" : "phone", 301);
        Check(!fs.SendFileAsync("phone", source, 301).GetAwaiter().GetResult(), "The phone's fs-cancel stops an upload");
        Check(ws.Chunks == 2, "Only the device receiving the file can cancel it");
        Check(ws.ChunkRecipients.Count > 0 && ws.ChunkRecipients.All(r => r == "phone"),
            "File chunks are addressed to the receiving device alone");
        Check(Last("fs-end", 301)["ok"]?.GetValue<bool>() == false &&
            Last("fs-end", 301)["error"]?.GetValue<string>() == "Cancelled by the receiver", "The phone is told its cancel took effect");
        Check(!fs.IsActive(301), "A cancelled upload is no longer active");

        ws.Chunks = 0;
        ws.OnChunk = _ => fs.Cancel(302);
        Check(!fs.SendFileAsync("phone", source, 302).GetAwaiter().GetResult() && ws.Chunks == 1, "This PC can cancel its own upload");
        Check(Last("fs-end", 302)["error"]?.GetValue<string>() == "Cancelled by the sender", "The phone learns the PC cancelled");

        // fs-cancel right behind the request: the send starts on a worker, which
        // waits on its first chunk until the cancel has been handled.
        ws.Chunks = 0;
        var gate = new ManualResetEventSlim();
        var ended = new TaskCompletionSource<JsonObject>();
        ws.OnChunk = _ => gate.Wait();
        ws.OnJson = m => { if (m["type"]?.GetValue<string>() == "fs-end") ended.TrySetResult(m); };
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-get", ["from"] = "phone", ["path"] = source, ["xferId"] = 303L,
        }, true);
        Cancel("phone", 303);
        gate.Set();
        Check(ended.Task.Wait(TimeSpan.FromSeconds(10)), "A cancelled request still ends");
        Check(ended.Task.Result["error"]?.GetValue<string>() == "Cancelled by the receiver" && ws.Chunks <= 1,
            "fs-cancel right after fs-get stops the file");
        ws.OnChunk = null;
        ws.OnJson = null;
        Console.WriteLine("PASS: cancelled uploads, from either end, only by the receiving device");
    }

    // Same listing and expectations as the Android FileExplorerActivityTest.
    private static void CheckSortOrders()
    {
        FileEntry F(string name, bool dir, long size, long mtime) => new() { Name = name, Dir = dir, Size = size, MTime = mtime };
        var sample = new[]
        {
            F("report.pdf", false, 1536, 1_791_998_400_000),
            F("Projects", true, 0, 1_791_912_000_000),
            F("archive.zip", false, 4_194_304, 1_791_825_600_000),
            F("Backups", true, 0, 1_791_739_200_000),
            F("empty.txt", false, 0, 0),
        };
        var expected = new Dictionary<FileSort, string[]>
        {
            [FileSort.NameAsc] = new[] { "archive.zip", "Backups", "empty.txt", "Projects", "report.pdf" },
            [FileSort.NameDesc] = new[] { "report.pdf", "Projects", "empty.txt", "Backups", "archive.zip" },
            [FileSort.SizeDesc] = new[] { "archive.zip", "report.pdf", "empty.txt", "Backups", "Projects" },
            [FileSort.SizeAsc] = new[] { "Backups", "Projects", "empty.txt", "report.pdf", "archive.zip" },
            [FileSort.ModifiedDesc] = new[] { "report.pdf", "Projects", "archive.zip", "Backups", "empty.txt" },
            [FileSort.ModifiedAsc] = new[] { "empty.txt", "Backups", "archive.zip", "Projects", "report.pdf" },
        };
        Check(expected.Count == Enum.GetValues<FileSort>().Length, "Every sort order is covered");
        foreach (var (sort, names) in expected)
        {
            Check(sample.Sorted(sort).Select(e => e.Name).SequenceEqual(names), $"{sort} mixes folders with files in the right order");
            Check(sample.Reverse().Sorted(sort).Select(e => e.Name).SequenceEqual(names), $"{sort} ignores the listing's order");
        }
        Console.WriteLine("PASS: all six sort orders mix folders with files");

        var foldersFirst = new Dictionary<FileSort, string[]>
        {
            [FileSort.NameAsc] = new[] { "Backups", "Projects", "archive.zip", "empty.txt", "report.pdf" },
            [FileSort.NameDesc] = new[] { "Projects", "Backups", "report.pdf", "empty.txt", "archive.zip" },
            [FileSort.SizeDesc] = new[] { "Backups", "Projects", "archive.zip", "report.pdf", "empty.txt" },
            [FileSort.SizeAsc] = new[] { "Backups", "Projects", "empty.txt", "report.pdf", "archive.zip" },
            [FileSort.ModifiedDesc] = new[] { "Projects", "Backups", "report.pdf", "archive.zip", "empty.txt" },
            [FileSort.ModifiedAsc] = new[] { "Backups", "Projects", "empty.txt", "archive.zip", "report.pdf" },
        };
        Check(foldersFirst.Count == Enum.GetValues<FileSort>().Length, "Every folders-first order is covered");
        foreach (var (sort, names) in foldersFirst)
        {
            Check(sample.Sorted(sort, foldersFirst: true).Select(e => e.Name).SequenceEqual(names), $"{sort} keeps folders first when asked");
            Check(sample.Reverse().Sorted(sort, foldersFirst: true).Select(e => e.Name).SequenceEqual(names), $"{sort} folders first ignores the listing's order");
        }
        Console.WriteLine("PASS: all six sort orders keep folders first when ticked");

        var settings = new AppSettings();
        Check(settings.FileBrowserSort == nameof(FileSort.ModifiedDesc) && !settings.FileBrowserFoldersFirst,
            "The file browser starts newest first, with folders mixed in");
        Console.WriteLine("PASS: the file browser starts newest first, with folders mixed in");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}

// Capture only the transport edge; all receiving and saving use production FsService.
public sealed class WsClient
{
    public List<JsonObject> Messages { get; } = new();
    public int Chunks;
    public Action<int>? OnChunk; // with the number of chunks sent so far
    public Action<JsonObject>? OnJson;

    public void SendJson(JsonObject message)
    {
        lock (Messages) Messages.Add(message);
        OnJson?.Invoke(message);
    }

    public List<string> ChunkRecipients { get; } = new();

    public bool SendBinary(byte channel, byte[] payload, IReadOnlyCollection<string>? to = null)
    {
        lock (ChunkRecipients) ChunkRecipients.Add(to == null ? "everyone" : string.Join(",", to));
        OnChunk?.Invoke(Interlocked.Increment(ref Chunks));
        return true;
    }
}
