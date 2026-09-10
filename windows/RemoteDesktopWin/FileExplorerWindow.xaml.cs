using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;

namespace RemoteDesktopWin;

/// <summary>Browses the phone's filesystem; downloads land in Downloads\RemoteDesktop.</summary>
public partial class FileExplorerWindow : Window
{
    private readonly WsClient _ws;
    private readonly FsService _fs;
    private readonly string _phoneId;
    private string _path = "";
    private int _reqCounter;
    private uint _uploadId;
    private string? _uploadStatus;
    private string? _uploadPath;

    public class Entry
    {
        public required string Name { get; init; }
        public bool Dir { get; init; }
        public long Size { get; init; }
        public long MTime { get; init; }
        public string Display => (Dir ? "📁 " : "📄 ") + Name;
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

    public FileExplorerWindow(WsClient ws, FsService fs, string phoneId)
    {
        InitializeComponent();
        _ws = ws;
        _fs = fs;
        _phoneId = phoneId;
        _fs.TransferStatus += OnTransferStatus;
        Closed += (_, _) => _fs.TransferStatus -= OnTransferStatus;
        RequestList("");
    }

    private void SetStatus(string text)
    {
        StatusText.Text = text;
        StatusText.ToolTip = text == _uploadStatus ? _uploadPath : null;
    }

    private void OnTransferStatus(string s) => Dispatcher.BeginInvoke(() => SetStatus(s));

    public void OnUploadResult(JsonNode msg)
    {
        if (msg["from"]?.GetValue<string>() != _phoneId ||
            msg["xferId"]?.GetValue<long>() != _uploadId) return;
        _uploadPath = msg["path"]?.GetValue<string>();
        SetStatus(StatusText.Text);
    }

    private void RequestList(string path)
    {
        SetStatus("Loading...");
        _ws.SendJson(new JsonObject
        {
            ["type"] = "fs-list",
            ["to"] = _phoneId,
            ["path"] = path,
            ["reqId"] = (++_reqCounter).ToString(),
        });
    }

    public void OnListResult(JsonNode msg)
    {
        var error = msg["error"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(error))
        {
            SetStatus("Error: " + error);
            return;
        }
        _path = msg["path"]?.GetValue<string>() ?? "";
        PathBox.Text = _path;
        var entries = new List<Entry>();
        foreach (var e in msg["entries"]!.AsArray())
        {
            entries.Add(new Entry
            {
                Name = e!["name"]!.GetValue<string>(),
                Dir = e["dir"]?.GetValue<bool>() ?? false,
                Size = e["size"]?.GetValue<long>() ?? 0,
                MTime = e["mtime"]?.GetValue<long>() ?? 0,
            });
        }
        EntryList.ItemsSource = entries.OrderByDescending(x => x.Dir).ThenBy(x => x.Name).ToList();
        SetStatus($"{entries.Count} items");
    }

    private string Join(string dir, string name) =>
        string.IsNullOrEmpty(dir) ? name : dir.TrimEnd('/') + "/" + name;

    private void Entry_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (EntryList.SelectedItem is not Entry entry) return;
        if (entry.Dir) RequestList(Join(_path, entry.Name));
    }

    private void Up_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_path)) return;
        var trimmed = _path.TrimEnd('/');
        var idx = trimmed.LastIndexOf('/');
        RequestList(idx <= 0 ? "" : trimmed[..idx]);
    }

    private void Refresh_Click(object sender, RoutedEventArgs e) => RequestList(_path);

    private void PathBox_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) RequestList(PathBox.Text.Trim());
    }

    private void Download_Click(object sender, RoutedEventArgs e)
    {
        if (EntryList.SelectedItem is not Entry entry || entry.Dir)
        {
            SetStatus("Select a file first");
            return;
        }
        var xferId = _fs.NextXferId();
        _fs.ExpectDownload(xferId,
            path => Dispatcher.BeginInvoke(() => SetStatus("Saved to " + path)),
            err => Dispatcher.BeginInvoke(() => SetStatus("Failed: " + err)));
        _ws.SendJson(new JsonObject
        {
            ["type"] = "fs-get",
            ["to"] = _phoneId,
            ["path"] = Join(_path, entry.Name),
            ["xferId"] = xferId,
        });
    }

    private async void Upload_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Send file to phone" };
        if (dlg.ShowDialog() != true) return;
        var xferId = _fs.NextXferId();
        var name = System.IO.Path.GetFileName(dlg.FileName);
        _uploadId = xferId;
        _uploadStatus = $"Sent {name}";
        _uploadPath = null;
        StatusText.ToolTip = null;
        if (await _fs.SendFileAsync(_phoneId, dlg.FileName, xferId))
            Toast.Show($"“{name}” uploaded to the phone's Downloads folder " +
                       "(not the folder you are browsing).");
    }
}
