using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;

namespace RemoteDesktopWin;

/// <summary>Browses the phone's filesystem; transfers land in Downloads.</summary>
public partial class FileExplorerWindow : Window
{
    private readonly WsClient _ws;
    private readonly FsService _fs;
    private readonly string _phoneId;
    private string _path = "";
    private int _reqCounter;
    private readonly HashSet<uint> _uploadIds = new();
    private string? _uploadStatus;
    private string? _uploadPath;
    private string? _downloadsPath;
    private string _requestedPath = "";
    private bool _uploading;
    private bool _navigateToDownloads;
    private TaskCompletionSource<bool>? _downloadsNavigation;

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
            msg["xferId"]?.GetValue<long>() is not long id ||
            id < 0 || id > uint.MaxValue || !_uploadIds.Remove((uint)id)) return;
        _uploadPath = msg["path"]?.GetValue<string>();
        SetStatus(StatusText.Text);
        if (_path == _downloadsPath) RequestList(_path);
    }

    private void RequestList(string path)
    {
        _requestedPath = path;
        if (!_uploading) SetStatus("Loading...");
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
        if (msg["from"]?.GetValue<string>() != _phoneId ||
            msg["reqId"]?.GetValue<string>() != _reqCounter.ToString()) return;
        var downloadsPath = msg["downloadsPath"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(downloadsPath)) _downloadsPath = downloadsPath;
        var error = msg["error"]?.GetValue<string>();
        if (!string.IsNullOrEmpty(error))
        {
            SetStatus("Error: " + error);
            _navigateToDownloads = false;
            _downloadsNavigation?.TrySetException(new InvalidOperationException(error));
            _downloadsNavigation = null;
            return;
        }
        _path = msg["path"]?.GetValue<string>() ?? "";
        // Older phones don't advertise Downloads; their first listing is the storage root.
        if (_downloadsPath == null && _requestedPath == "") _downloadsPath = Join(_path, "Download");
        if (_navigateToDownloads && _downloadsPath != null)
        {
            _ = NavigateToDownloads();
            return;
        }
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
        if (!_uploading) SetStatus($"{entries.Count} items");
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
        if (_uploading) return;
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Send files to phone", Multiselect = true };
        if (dlg.ShowDialog(this) == true) await UploadFilesAsync(dlg.FileNames);
    }

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        e.Effects = !_uploading && e.Data.GetDataPresent(DataFormats.FileDrop) &&
                    e.Data.GetData(DataFormats.FileDrop) is string[] paths &&
                    paths.Any(System.IO.File.Exists)
            ? DragDropEffects.Copy : DragDropEffects.None;
        e.Handled = true;
    }

    private async void Window_Drop(object sender, DragEventArgs e)
    {
        e.Handled = true;
        if (!_uploading && e.Data.GetDataPresent(DataFormats.FileDrop) &&
            e.Data.GetData(DataFormats.FileDrop) is string[] paths)
            await UploadFilesAsync(paths);
    }

    private Task NavigateToDownloads()
    {
        _navigateToDownloads = _downloadsPath == null;
        if (_downloadsPath == null)
        {
            _downloadsNavigation = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            RequestList("");
            return _downloadsNavigation.Task;
        }
        _path = _downloadsPath;
        PathBox.Text = _path;
        EntryList.ItemsSource = null;
        RequestList(_path);
        _downloadsNavigation?.TrySetResult(true);
        _downloadsNavigation = null;
        return Task.CompletedTask;
    }

    private async Task UploadFilesAsync(IEnumerable<string> paths)
    {
        var files = paths.Where(System.IO.File.Exists).ToArray();
        if (files.Length == 0)
        {
            SetStatus("Drop files to upload; folders are not supported.");
            return;
        }
        _uploading = true;
        UploadButton.IsEnabled = false;
        try
        {
            await NavigateToDownloads().WaitAsync(TimeSpan.FromSeconds(15));
            foreach (var file in files)
            {
                var xferId = _fs.NextXferId();
                var name = System.IO.Path.GetFileName(file);
                _uploadIds.Add(xferId);
                _uploadStatus = $"Sent {name}";
                _uploadPath = null;
                SetStatus($"Uploading {name} to Downloads...");
                if (await _fs.SendFileAsync(_phoneId, file, xferId))
                    Toast.Show($"“{name}” uploaded to the phone's Downloads folder.");
                else
                    _uploadIds.Remove(xferId);
            }
        }
        catch (Exception ex)
        {
            _navigateToDownloads = false;
            _downloadsNavigation = null;
            SetStatus("Upload failed: " + ex.Message);
        }
        finally
        {
            _uploading = false;
            UploadButton.IsEnabled = true;
        }
    }
}
