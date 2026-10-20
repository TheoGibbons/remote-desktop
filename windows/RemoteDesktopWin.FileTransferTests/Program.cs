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
        }
        finally { Directory.Delete(dir, true); }
        CheckSortOrders();
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
            [FileSort.NameAsc] = new[] { "Backups", "Projects", "archive.zip", "empty.txt", "report.pdf" },
            [FileSort.NameDesc] = new[] { "Projects", "Backups", "report.pdf", "empty.txt", "archive.zip" },
            [FileSort.SizeDesc] = new[] { "Backups", "Projects", "archive.zip", "report.pdf", "empty.txt" },
            [FileSort.SizeAsc] = new[] { "Backups", "Projects", "empty.txt", "report.pdf", "archive.zip" },
            [FileSort.ModifiedDesc] = new[] { "Projects", "Backups", "report.pdf", "archive.zip", "empty.txt" },
            [FileSort.ModifiedAsc] = new[] { "Backups", "Projects", "empty.txt", "archive.zip", "report.pdf" },
        };
        Check(expected.Count == Enum.GetValues<FileSort>().Length, "Every sort order is covered");
        foreach (var (sort, names) in expected)
        {
            Check(sample.Sorted(sort).Select(e => e.Name).SequenceEqual(names), $"{sort} keeps folders first in the right order");
            Check(sample.Reverse().Sorted(sort).Select(e => e.Name).SequenceEqual(names), $"{sort} ignores the listing's order");
        }
        Console.WriteLine("PASS: all six sort orders keep folders first");
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
    public void SendJson(JsonObject message) => Messages.Add(message);
    public bool SendBinary(byte channel, byte[] payload) => true;
}
