namespace RemoteDesktopWin;

/// <summary>One entry of a remote folder listing.</summary>
public class FileEntry
{
    public required string Name { get; init; }
    public bool Dir { get; init; }
    public long Size { get; init; }
    public long MTime { get; init; }
    public string SizeText => Dir ? "" : FormatSize(Size);
    public string MTimeText => MTime == 0 ? "" : DateTimeOffset.FromUnixTimeMilliseconds(MTime).LocalDateTime.ToString("yyyy-MM-dd HH:mm");

    private static string FormatSize(long s) => s switch
    {
        < 1024 => $"{s} B",
        < 1024 * 1024 => $"{s / 1024.0:F1} KB",
        < 1024L * 1024 * 1024 => $"{s / 1024.0 / 1024:F1} MB",
        _ => $"{s / 1024.0 / 1024 / 1024:F2} GB",
    };
}

/// <summary>Folder listing orders, the same ones the Android file browser offers.</summary>
public enum FileSort { NameAsc, NameDesc, SizeDesc, SizeAsc, ModifiedDesc, ModifiedAsc }

public static class FileSorting
{
    public static string Label(this FileSort sort) => sort switch
    {
        FileSort.NameAsc => "Name (A–Z)",
        FileSort.NameDesc => "Name (Z–A)",
        FileSort.SizeDesc => "Size (largest first)",
        FileSort.SizeAsc => "Size (smallest first)",
        FileSort.ModifiedDesc => "Modified (newest first)",
        _ => "Modified (oldest first)",
    };

    public static string Summary(this FileSort sort) => sort switch
    {
        FileSort.NameAsc => "Name ↑",
        FileSort.NameDesc => "Name ↓",
        FileSort.SizeDesc => "Size ↓",
        FileSort.SizeAsc => "Size ↑",
        FileSort.ModifiedDesc => "Modified ↓",
        _ => "Modified ↑",
    };

    /// <summary>Folders sort among files unless <paramref name="foldersFirst"/>; ties fall back to name order.</summary>
    public static List<FileEntry> Sorted(this IEnumerable<FileEntry> entries, FileSort sort, bool foldersFirst = false)
    {
        // Folder sizes aren't calculated, so they rank below empty files.
        static long SizeKey(FileEntry e) => e.Dir ? -1 : e.Size;
        var grouped = entries.OrderByDescending(e => foldersFirst && e.Dir);
        var ordered = sort switch
        {
            FileSort.NameAsc => grouped.ThenBy(e => e.Name.ToLowerInvariant(), StringComparer.Ordinal),
            FileSort.NameDesc => grouped.ThenByDescending(e => e.Name.ToLowerInvariant(), StringComparer.Ordinal),
            FileSort.SizeDesc => grouped.ThenByDescending(SizeKey),
            FileSort.SizeAsc => grouped.ThenBy(SizeKey),
            FileSort.ModifiedDesc => grouped.ThenByDescending(e => e.MTime),
            _ => grouped.ThenBy(e => e.MTime),
        };
        return ordered
            .ThenBy(e => e.Name.ToLowerInvariant(), StringComparer.Ordinal)
            .ThenBy(e => e.Name, StringComparer.Ordinal)
            .ToList();
    }
}
