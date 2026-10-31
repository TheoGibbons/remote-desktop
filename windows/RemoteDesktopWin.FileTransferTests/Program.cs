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
            Check(Reply(108)["error"]?.GetValue<string>() == "File access is disabled on the remote device",
                "A refused push tells the phone at once, so it doesn't wait to hear");
            Chunk(108, new byte[] { 1 });
            End(108);
            Check(!File.Exists(Path.Combine(dir, "denied.jpg")), "Denied file access never writes a file");
            Console.WriteLine("PASS: complete, duplicate, incomplete, cancelled, unknown-size, empty, denied and failed-save transfers");

            CheckCancelling(ws, fs, dir, Begin, Chunk, End);
            CheckUploadsThatBreak(dir);
            CheckCaptureOffers(dir);
            CheckTransfersPerDevice(dir);
            CheckQueuedDownloads(dir);
            CheckOpeningDownloads(dir);
        }
        finally { Directory.Delete(dir, true); }
        CheckSortOrders();
    }

    private static void CheckTransfersPerDevice(string dir)
    {
        var ws = new WsClient();
        var fs = new FsService(ws) { IncomingDir = dir };
        var updates = new List<TransferUpdate>();
        // This thread stands in for the UI thread the list lives on.
        var list = new TransferList(fs, id => id == "s20" ? "Galaxy S20" : "Galaxy S26");
        fs.TransferChanged += u =>
        {
            updates.Add(u);
            list.Apply(u);
        };
        JsonObject Last(string type, uint id) => ws.Messages.Last(m => m["type"]?.GetValue<string>() == type &&
            m["xferId"]?.GetValue<uint>() == id);

        // A file to each phone at once, as two files windows send them: the
        // second starts while the first is under way.
        var source = Path.Combine(dir, "both.bin");
        File.WriteAllBytes(source, new byte[3 * 256 * 1024]);
        Task<TransferUpdate>? second = null;
        ws.OnChunk = n => { if (n == 1) second = fs.SendFileAsync("s26", source, 401); };
        Check(fs.SendFileAsync("s20", source, 400).GetAwaiter().GetResult().State == TransferState.Done &&
              second!.GetAwaiter().GetResult().State == TransferState.Done,
            "Two phones can each be sent a file at once");
        ws.OnChunk = null;
        Check(ws.ChunkRecipients.Count(r => r == "s20") == 3 && ws.ChunkRecipients.Count(r => r == "s26") == 3,
            "Each phone is sent every chunk of its own file");
        Check(Last("fs-end", 400)["to"]?.GetValue<string>() == "s20" && Last("fs-end", 401)["to"]?.GetValue<string>() == "s26",
            "Each file ends at the phone it was for");
        Check(updates.Where(u => u.XferId == 400).All(u => u.Peer == "s20" && u.Sending) &&
              updates.Where(u => u.XferId == 401).All(u => u.Peer == "s26" && u.Sending),
            "Every update names the device its file goes to, so each files window shows only its own");
        Check(list.Rows.Select(r => r.Detail).SequenceEqual(new[] { "To Galaxy S26 · Sent, 768.0 KB", "To Galaxy S20 · Sent, 768.0 KB" }),
            "The transfers window lists each file with its device, newest first");
        Console.WriteLine("PASS: two phones sent a file at once each get their own, and every update names its device");

        // A download, cancelled in the transfers window while its chunks arrive.
        var saved = new List<string>();
        var failed = new List<string>();
        fs.ExpectDownload(402, "s20", "photo.jpg", saved.Add, failed.Add);
        var photo = list.Rows[0];
        Check(photo.XferId == 402 && photo.Detail == "From Galaxy S20 · Waiting" && photo.CanCancel,
            "A download is listed as soon as it is asked for");
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-begin", ["from"] = "s20", ["xferId"] = 402L, ["name"] = "photo.jpg", ["size"] = 10L,
        }, false);
        fs.HandleFileChunk(new byte[] { 2, 0, 0, 1, 146, 1, 2, 3, 4 });
        Check(photo.ShowProgress && photo.Detail == "From Galaxy S20 · 40% of 10 B", "Its progress moves on");
        list.Cancel(photo);
        Check(photo.State == TransferState.Cancelled && !photo.CanCancel && !fs.IsActive(402) &&
              Last("fs-cancel", 402)["to"]?.GetValue<string>() == "s20", "Cancel stops that one file and tells its phone");
        Check(failed.Count == 0 && saved.Count == 0, "A cancelled download isn't reported as a failure");
        Check(list.Apply(new TransferUpdate(402, "s20", false, "photo.jpg", 10, TransferState.Running, 70)) == null &&
              photo.State == TransferState.Cancelled, "Progress that arrives late doesn't bring it back");

        // An upload cancelled in the transfers window, after the finished files are cleared.
        ws.Chunks = 0;
        ws.OnChunk = n =>
        {
            if (n != 1) return;
            list.ClearFinished();
            Check(list.Rows.Count == 1 && list.Rows[0].XferId == 403 && !list.HasFinished,
                "Clearing keeps the file still on its way");
            list.Cancel(list.Rows[0]);
        };
        Check(fs.SendFileAsync("s26", source, 403).GetAwaiter().GetResult().State == TransferState.Cancelled &&
              Last("fs-end", 403)["error"]?.GetValue<string>() == "Cancelled by the sender", "An upload can be cancelled there too");
        ws.OnChunk = null;
        Check(list.Rows.Single().Detail == "To Galaxy S26 · Cancelled", "And it says so");
        Console.WriteLine("PASS: the transfers window cancels one file at a time, and late progress can't revive it");
    }

    private static void CheckQueuedDownloads(string dir)
    {
        var ws = new WsClient();
        var fs = new FsService(ws) { IncomingDir = dir };
        var list = new TransferList(fs, _ => "Galaxy S20");
        fs.TransferChanged += u => list.Apply(u);
        var saved = new List<string>();
        var failed = new List<string>();
        List<uint> Gets(string to) => ws.Messages.Where(m => m["type"]?.GetValue<string>() == "fs-get" &&
            m["to"]?.GetValue<string>() == to).Select(m => m["xferId"]!.GetValue<uint>()).ToList();
        bool Cancelled(uint id) => ws.Messages.Any(m => m["type"]?.GetValue<string>() == "fs-cancel" &&
            m["xferId"]?.GetValue<uint>() == id);
        void Send(uint id, string from, string name, byte[] bytes)
        {
            fs.HandleJson(new JsonObject
            {
                ["type"] = "fs-begin", ["from"] = from, ["xferId"] = (long)id, ["name"] = name, ["size"] = (long)bytes.Length,
            }, false);
            fs.HandleFileChunk(new byte[] { 2, (byte)(id >> 24), (byte)(id >> 16), (byte)(id >> 8), (byte)id }.Concat(bytes).ToArray());
            fs.HandleJson(new JsonObject { ["type"] = "fs-end", ["from"] = from, ["xferId"] = (long)id, ["ok"] = true }, false);
        }

        // A folder picked in a files window: each of its files is queued for the
        // folder made for it below Downloads.
        var camera = Path.Combine(dir, "DCIM", "Camera");
        for (uint id = 501; id <= 505; id++)
            fs.QueueDownload(id, "s20", $"/storage/emulated/0/DCIM/Camera/{id}.jpg", $"{id}.jpg", camera, saved.Add, failed.Add);
        fs.QueueDownload(601, "s26", "/sdcard/a.txt", "a.txt", dir, saved.Add, failed.Add);
        Check(Gets("s20").SequenceEqual(new uint[] { 501, 502 }) && Gets("s26").SequenceEqual(new uint[] { 601 }),
            "Queued downloads are asked for two at a time from each device, oldest first");
        Check(ws.Messages.Single(m => m["xferId"]?.GetValue<uint>() == 501)["path"]?.GetValue<string>() ==
            "/storage/emulated/0/DCIM/Camera/501.jpg", "Each is asked for by its path on the device");
        Check(list.ActiveCount == 6 && list.Rows.Count(r => r.Detail == "From Galaxy S20 · Waiting") == 6,
            "Every queued file is listed as waiting from the start");
        Check(fs.IsActive(505), "A file waiting its turn is active");

        Send(501, "s20", "501.jpg", new byte[] { 1, 2 });
        Check(saved.Single() == Path.Combine(camera, "501.jpg") && File.ReadAllBytes(saved[0]).SequenceEqual(new byte[] { 1, 2 }),
            "A queued file is saved in its own folder, which is made for it");
        Check(Gets("s20").SequenceEqual(new uint[] { 501, 502, 503 }), "The next file is asked for as one arrives");

        fs.Cancel(504);
        Check(!Cancelled(504) && !fs.IsActive(504) && list.Rows.Single(r => r.XferId == 504).State == TransferState.Cancelled,
            "A file cancelled while it waits is listed as cancelled, and its device is told nothing");
        fs.Cancel(502);
        Check(Cancelled(502) && Gets("s20").SequenceEqual(new uint[] { 501, 502, 503, 505 }),
            "Cancelling a file under way asks for the next, skipping one cancelled while it waited");
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-end", ["from"] = "s20", ["xferId"] = 503L, ["ok"] = false, ["error"] = "No such file",
        }, false);
        Check(failed.SequenceEqual(new[] { "No such file" }), "A refused queued file reports why");
        Send(505, "s20", "505.jpg", new byte[] { 5 });
        Check(list.ActiveCount == 1 && !list.Rows.Single(r => r.XferId == 601).Finished,
            "Only the other device's file is still under way");
        Send(601, "s26", "a.txt", new byte[] { 6 });
        Check(list.ActiveCount == 0 && saved.Count == 3 && saved[2] == Path.Combine(dir, "a.txt"),
            "Nothing is under way once every queued file has ended");

        // The phone leaves with files still to come: they fail rather than wait for good.
        for (uint id = 510; id <= 512; id++)
            fs.QueueDownload(id, "s20", $"/storage/emulated/0/DCIM/Camera/{id}.jpg", $"{id}.jpg", camera, saved.Add, failed.Add);
        fs.QueueDownload(602, "s26", "/sdcard/b.txt", "b.txt", dir, saved.Add, failed.Add);
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-begin", ["from"] = "s20", ["xferId"] = 510L, ["name"] = "510.jpg", ["size"] = 4L,
        }, false);
        fs.HandleFileChunk(new byte[] { 2, 0, 0, 1, 254, 1 });
        fs.PeerLeft("s20", "Galaxy S20 went offline");
        Check(failed.Count(f => f == "Galaxy S20 went offline") == 3 && list.ActiveCount == 1 &&
              list.Rows.Single(r => r.XferId == 512).Detail == "From Galaxy S20 · Failed: Galaxy S20 went offline",
            "A device that leaves fails every file still to come from it, and no other device's");
        Check(!Directory.EnumerateFiles(camera, "*.part").Any(), "A file cut off part way leaves nothing behind");
        fs.PeerLeft(null, "The connection to the relay was lost");
        Check(list.ActiveCount == 0 && failed[^1] == "The connection to the relay was lost", "Losing the relay fails the rest");

        Directory.CreateDirectory(Path.Combine(dir, "Pictures"));
        Check(FsService.UniquePath(Path.Combine(dir, "Pictures")) == Path.Combine(dir, "Pictures (1)"),
            "A folder's name that is taken gets a counter too");
        Console.WriteLine("PASS: queued downloads go two at a time per device into their own folders, can be cancelled while they wait, and fail when their device leaves");
    }

    private static void CheckOpeningDownloads(string dir)
    {
        var fs = new FsService(new WsClient()) { IncomingDir = dir };
        var list = new TransferList(fs, _ => "Galaxy S20");
        fs.TransferChanged += u => list.Apply(u);
        var now = new DateTime(2026, 10, 30, 12, 0, 0, DateTimeKind.Utc);
        var shown = false;
        var selected = new List<string>();
        var asked = new List<string>();
        // Stands in for the UI thread's queue.
        var posted = new Queue<Action>();
        var opener = new DownloadsFolderOpener(list, dir, posted.Enqueue, folder =>
        {
            asked.Add(folder);
            return Task.FromResult(shown);
        }, selected.Add, () => now);
        void Download(uint id, string name)
        {
            fs.ExpectDownload(id, "s20", name, _ => { }, _ => { });
            fs.HandleJson(new JsonObject
            {
                ["type"] = "fs-begin", ["from"] = "s20", ["xferId"] = (long)id, ["name"] = name, ["size"] = 0L,
            }, false);
            fs.HandleJson(new JsonObject { ["type"] = "fs-end", ["from"] = "s20", ["xferId"] = (long)id, ["ok"] = true }, false);
            // As a files window hears of it: after the transfers list.
            opener.Saved(Path.Combine(dir, name));
            while (posted.TryDequeue(out var action)) action();
        }

        fs.ExpectDownload(700, "s20", "last.jpg", _ => { }, _ => { });
        Download(701, "first.jpg");
        Check(selected.Count == 0 && asked.Count == 0, "Downloads isn't shown while another file is still on its way");
        Download(700, "last.jpg");
        Check(asked.SequenceEqual(new[] { dir }) && selected.SequenceEqual(new[] { Path.Combine(dir, "last.jpg") }),
            "Once nothing is under way, Downloads is shown once with the last file selected");

        now += TimeSpan.FromSeconds(59);
        Download(702, "soon.jpg");
        Check(selected.Count == 1 && asked.Count == 1, "Not again within a minute");
        now += TimeSpan.FromSeconds(2);
        list.Apply(new TransferUpdate(799, "s20", true, "upload.bin", 1, TransferState.Done));
        while (posted.TryDequeue(out var action)) action();
        Check(selected.Count == 1, "A download held back for the minute isn't shown later on");
        Download(703, "later.jpg");
        Check(selected.Count == 2 && selected[1] == Path.Combine(dir, "later.jpg"), "But a download after the minute is");

        now += TimeSpan.FromMinutes(5);
        shown = true;
        Download(704, "open.jpg");
        Check(asked.Count == 3 && selected.Count == 2, "Nor while Explorer shows Downloads already");
        shown = false;
        Download(705, "closed.jpg");
        Check(selected.Count == 3, "And again once it's closed, since nothing was opened meanwhile");

        // A file the phone sends unasked, as from its share sheet.
        var pushed = new List<string>();
        fs.PushSaved += path =>
        {
            pushed.Add(path);
            opener.Saved(path);
        };
        now += TimeSpan.FromMinutes(5);
        fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-begin", ["from"] = "s20", ["xferId"] = 706L, ["name"] = "shared.pdf", ["size"] = 1L,
        }, true);
        fs.HandleFileChunk(new byte[] { 2, 0, 0, 2, 194, 7 });
        fs.HandleJson(new JsonObject { ["type"] = "fs-end", ["from"] = "s20", ["xferId"] = 706L, ["ok"] = true }, true);
        while (posted.TryDequeue(out var action)) action();
        Check(pushed.SequenceEqual(new[] { Path.Combine(dir, "shared.pdf") }) && selected[^1] == pushed[0],
            "A file sent unasked shows Downloads too, selected");
        now += TimeSpan.FromMinutes(5);
        Download(707, "asked.jpg");
        Check(pushed.Count == 1 && selected[^1] == Path.Combine(dir, "asked.jpg"),
            "A download asked for isn't taken for one sent unasked");
        Console.WriteLine("PASS: Downloads opens once a batch or a file sent unasked has landed, at most once a minute, and not while Explorer shows it");
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
        Check(clip.Status == CaptureOffer.Stage.Downloading, "The card's own cancel isn't taken for a failure");

        var pic = Offer(new JsonArray { File("/storage/emulated/0/DCIM/Camera/p.jpg", "photo", 4) })!;
        pic.Download();
        fs.Cancel(Gets().Last()["xferId"]!.GetValue<uint>()); // as the transfers window does
        RunPosted();
        Check(pic.Status == CaptureOffer.Stage.Failed && pic.Error == "Cancelled",
            "A download cancelled in the transfers window fails its card, which can try again");

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

        fs.ExpectDownload(201, "phone", "big.bin", saved.Add, failed.Add);
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

        fs.ExpectDownload(202, "phone", "late.bin", saved.Add, failed.Add);
        fs.Cancel(202);
        Check(Any("fs-cancel", 202), "A download can be cancelled before it begins");
        begin(202, "late.bin", 1, true);
        chunk(202, new byte[] { 1 });
        end(202, true);
        Check(!Directory.EnumerateFiles(dir, "late*").Any() && !Any("fs-saved", 202),
            "A download cancelled before it began is not kept as a push when an older phone sends it anyway");

        fs.ExpectDownload(203, "phone", "missing.bin", saved.Add, failed.Add);
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
        Check(fs.SendFileAsync("phone", source, 301).GetAwaiter().GetResult().State == TransferState.Cancelled,
            "The phone's fs-cancel stops an upload");
        Check(ws.Chunks == 2, "Only the device receiving the file can cancel it");
        Check(ws.ChunkRecipients.Count > 0 && ws.ChunkRecipients.All(r => r == "phone"),
            "File chunks are addressed to the receiving device alone");
        Check(Last("fs-end", 301)["ok"]?.GetValue<bool>() == false &&
            Last("fs-end", 301)["error"]?.GetValue<string>() == "Cancelled by the receiver", "The phone is told its cancel took effect");
        Check(!fs.IsActive(301), "A cancelled upload is no longer active");

        ws.Chunks = 0;
        ws.OnChunk = _ => fs.Cancel(302);
        Check(fs.SendFileAsync("phone", source, 302).GetAwaiter().GetResult().State == TransferState.Cancelled && ws.Chunks == 1,
            "This PC can cancel its own upload");
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

    // 2026-10-29: the PC reconnected halfway through a file to two phones. Both
    // dropped the file when its old peer id left, and the PC sent the rest over
    // the new connection and said it was uploaded.
    private static void CheckUploadsThatBreak(string dir)
    {
        var ws = new WsClient();
        var fs = new FsService(ws) { IncomingDir = dir };
        var list = new TransferList(fs, _ => "Galaxy S20");
        fs.TransferChanged += u => list.Apply(u);
        var source = Path.Combine(dir, "upload.bin");
        File.WriteAllBytes(source, new byte[3 * 256 * 1024]);
        JsonObject Last(string type, uint id) => ws.Messages.Last(m => m["type"]?.GetValue<string>() == type &&
            m["xferId"]?.GetValue<uint>() == id);
        int Count(string type, uint id) => ws.Messages.Count(m => m["type"]?.GetValue<string>() == type &&
            m["xferId"]?.GetValue<uint>() == id);
        void Saved(uint id, string from, string? path = null, string? error = null) => fs.HandleJson(new JsonObject
        {
            ["type"] = "fs-saved", ["from"] = from, ["xferId"] = (long)id, ["path"] = path, ["error"] = error,
        }, false);
        TransferUpdate Send(uint id, bool awaitSaved = true) =>
            fs.SendFileAsync("phone", source, id, awaitSaved).GetAwaiter().GetResult();

        ws.OnChunk = n => { if (n == 1) ws.Generation++; };
        var reconnected = Send(501);
        Check(reconnected.State == TransferState.Failed && reconnected.Error == "The connection to the relay was lost" &&
              ws.Chunks == 1, "An upload fails when the relay connection drops under it, rather than carrying on over the next one");
        Check(list.Rows[0].Detail == "To Galaxy S20 · Failed: The connection to the relay was lost" && !fs.IsActive(501),
            "And it is listed as failed");

        // The socket refuses chunks while it reconnects. They used to be counted
        // as sent after 30 s of that.
        ws.Chunks = 0;
        ws.OnChunk = null;
        var refused = 0;
        // Two chunks go, then the third is refused until the connection is replaced.
        ws.Accept = () => ++refused < 3 || (refused > 5 && ws.Generation++ < 0);
        Check(Send(502).Error == "The connection to the relay was lost" && ws.Chunks == 2 && refused == 6,
            "A chunk that can't be sent is never counted as sent");
        ws.Accept = null;

        ws.Chunks = 0;
        ws.OnChunk = n => { if (n == 1) fs.PeerLeft("phone", "Galaxy S20 went offline"); };
        Check(Send(503).Error == "Galaxy S20 went offline" && ws.Chunks == 1, "An upload fails when its phone leaves");
        ws.OnChunk = null;

        ws.IsConnected = false;
        Check(Send(504).Error == "Not connected to the relay", "An upload can't start while the relay is away");
        ws.IsConnected = true;

        // The phone saves it: only then is it sent. Another device can't say so for it.
        ws.OnJson = m =>
        {
            if (m["type"]?.GetValue<string>() != "fs-end" || m["xferId"]?.GetValue<uint>() != 505) return;
            Check(fs.IsActive(505) && list.Rows[0].State == TransferState.Running, "Sent in full isn't done yet");
            Saved(505, "someone-else", path: "/elsewhere");
            Check(fs.IsActive(505), "Only the device the file went to can confirm it");
            Saved(505, "phone", path: "/storage/emulated/0/Download/upload.bin");
        };
        Check(Send(505).State == TransferState.Done && list.Rows[0].Detail == "To Galaxy S20 · Sent, 768.0 KB",
            "An upload is sent once its phone says it saved it");

        // The PC drops after the last byte, before the phone's answer.
        ws.OnJson = m =>
        {
            if (m["type"]?.GetValue<string>() == "fs-end" && m["xferId"]?.GetValue<uint>() == 506)
                fs.PeerLeft(null, "The connection to the relay was lost");
        };
        var unconfirmed = Send(506);
        Check(unconfirmed.State == TransferState.Failed &&
              unconfirmed.Error == "The connection to the relay was lost before the file was confirmed saved" &&
              Count("fs-end", 506) == 1 && Last("fs-end", 506)["ok"]?.GetValue<bool>() == true,
            "Losing the answer fails the upload without taking back the fs-end");

        // A phone that refuses a push says so as it begins.
        ws.Chunks = 0;
        ws.OnJson = m =>
        {
            if (m["type"]?.GetValue<string>() == "fs-begin" && m["xferId"]?.GetValue<uint>() == 507)
                Saved(507, "phone", error: "File access is disabled on the remote device");
        };
        var denied = Send(507);
        Check(denied.Error == "File access is disabled on the remote device" && ws.Chunks == 0 &&
              Last("fs-end", 507)["ok"]?.GetValue<bool>() == false, "A refused upload stops at once and says why");

        // The phone couldn't save it.
        ws.OnJson = m =>
        {
            if (m["type"]?.GetValue<string>() == "fs-end" && m["xferId"]?.GetValue<uint>() == 508)
                Saved(508, "phone", error: "Only 3 of 5 bytes arrived. Try again on a faster connection.");
        };
        Check(Send(508).Error == "Only 3 of 5 bytes arrived. Try again on a faster connection.",
            "The phone's reason for not saving an upload is what the PC shows");
        ws.OnJson = null;

        // fs-get replies don't wait: the device that asked for the file decides what became of it.
        Check(Send(509, awaitSaved: false).State == TransferState.Done && !fs.IsActive(509), "A file asked for is done once sent");
        Console.WriteLine("PASS: uploads fail when the relay or phone goes, and are only sent once the phone saves them");
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

        // A phone's storage volumes show, and sort, by what Android calls them.
        var volumes = new[]
        {
            new FileEntry { Name = "0000-0000", Label = "SD card", Dir = true },
            new FileEntry { Name = "emulated/0", Label = "Internal storage", Dir = true },
        };
        Check(volumes.Sorted(FileSort.ModifiedDesc).Select(e => e.DisplayName).SequenceEqual(new[] { "Internal storage", "SD card" }),
            "Storage volumes sort by their labels");
        Console.WriteLine("PASS: storage volumes sort by their labels");

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
    public Func<bool>? Accept; // whether the socket has room for the next chunk
    public int Generation;
    public bool IsConnected = true;

    public void SendJson(JsonObject message)
    {
        lock (Messages) Messages.Add(message);
        OnJson?.Invoke(message);
    }

    public List<string> ChunkRecipients { get; } = new();

    public bool SendBinary(byte channel, byte[] payload, IReadOnlyCollection<string>? to = null)
    {
        if (Accept?.Invoke() == false) return false;
        lock (ChunkRecipients) ChunkRecipients.Add(to == null ? "everyone" : string.Join(",", to));
        // Counted before the callback, which `?.` would skip along with its argument.
        var sent = Interlocked.Increment(ref Chunks);
        OnChunk?.Invoke(sent);
        return true;
    }
}
