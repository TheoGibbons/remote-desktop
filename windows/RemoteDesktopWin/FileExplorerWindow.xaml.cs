using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;

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
    private readonly Queue<string> _uploadQueue = new();
    private int _uploadIndex;
    private int _uploadTotal;
    private uint _lastUploadId;
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
        if (MainWindow.IsElevated()) Loaded += (_, _) => AcceptElevatedFileDrops();
        RequestList("");
    }

    private void SetStatus(string text)
    {
        StatusText.Text = text;
        StatusText.ToolTip = text == _uploadStatus ? _uploadPath : null;
    }

    private string UploadProgress => _uploadTotal > 1 ? $" ({_uploadIndex}/{_uploadTotal})" : "";

    private void OnTransferStatus(string s)
    {
        // Uploads report on this thread. Apply them now: a queued update would
        // land after the batch moves on and overwrite its newer status.
        if (Dispatcher.CheckAccess()) SetStatus(s + UploadProgress);
        else Dispatcher.BeginInvoke(() => SetStatus(s));
    }

    public void OnUploadResult(JsonNode msg)
    {
        if (msg["from"]?.GetValue<string>() != _phoneId ||
            msg["xferId"]?.GetValue<long>() is not long id ||
            id < 0 || id > uint.MaxValue || !_uploadIds.Remove((uint)id)) return;
        if (id == _lastUploadId)
        {
            _uploadPath = msg["path"]?.GetValue<string>();
            SetStatus(StatusText.Text);
        }
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
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Send files to phone", Multiselect = true };
        if (dlg.ShowDialog(this) == true) await UploadFilesAsync(dlg.FileNames);
    }

    private static string[]? DroppedPaths(DragEventArgs e) =>
        e.Data.GetDataPresent(DataFormats.FileDrop) ? e.Data.GetData(DataFormats.FileDrop) as string[] : null;

    private void Window_DragOver(object sender, DragEventArgs e)
    {
        var accept = DroppedPaths(e)?.Any(System.IO.File.Exists) == true;
        e.Effects = accept ? DragDropEffects.Copy : DragDropEffects.None;
        DropOverlay.Visibility = accept ? Visibility.Visible : Visibility.Collapsed;
        e.Handled = true;
    }

    // Moving between child elements raises a leave and an enter together, so
    // the overlay only stays hidden once the drag has left the window.
    private void Window_DragLeave(object sender, DragEventArgs e) =>
        DropOverlay.Visibility = Visibility.Collapsed;

    private async void Window_Drop(object sender, DragEventArgs e)
    {
        e.Handled = true;
        DropOverlay.Visibility = Visibility.Collapsed;
        if (DroppedPaths(e) is string[] paths) await UploadFilesAsync(paths);
    }

    // ---------- drops while elevated ----------

    // UIPI blocks OLE drag-and-drop from Explorer into a higher-integrity
    // window, so "Run as administrator" would otherwise refuse every drop.
    // The legacy WM_DROPFILES route can be let through the message filter, but
    // only if the window is not also registered as an OLE drop target.

    private const uint WM_COPYGLOBALDATA = 0x0049;
    private const uint WM_COPYDATA = 0x004A;
    private const int WM_DROPFILES = 0x0233;
    private const uint MSGFLT_ALLOW = 1;

    [DllImport("ole32.dll")]
    private static extern int RevokeDragDrop(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool ChangeWindowMessageFilterEx(IntPtr hwnd, uint message, uint action, IntPtr changeFilterStruct);

    [DllImport("shell32.dll")]
    private static extern void DragAcceptFiles(IntPtr hwnd, bool accept);

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    private static extern uint DragQueryFile(IntPtr hDrop, uint file, StringBuilder? buffer, uint size);

    [DllImport("shell32.dll")]
    private static extern void DragFinish(IntPtr hDrop);

    private void AcceptElevatedFileDrops()
    {
        if (PresentationSource.FromVisual(this) is not HwndSource source) return;
        var hwnd = source.Handle;
        RevokeDragDrop(hwnd);
        foreach (var message in new[] { (uint)WM_DROPFILES, WM_COPYDATA, WM_COPYGLOBALDATA })
            ChangeWindowMessageFilterEx(hwnd, message, MSGFLT_ALLOW, IntPtr.Zero);
        DragAcceptFiles(hwnd, true);
        source.AddHook(DropFilesHook);
    }

    private IntPtr DropFilesHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WM_DROPFILES) return IntPtr.Zero;
        handled = true;
        var paths = new List<string>();
        try
        {
            var count = DragQueryFile(wParam, uint.MaxValue, null, 0);
            for (uint i = 0; i < count; i++)
            {
                var buffer = new StringBuilder((int)DragQueryFile(wParam, i, null, 0) + 1);
                if (DragQueryFile(wParam, i, buffer, (uint)buffer.Capacity) > 0) paths.Add(buffer.ToString());
            }
        }
        finally
        {
            DragFinish(wParam);
        }
        Dispatcher.BeginInvoke(() => UploadFilesAsync(paths));
        return IntPtr.Zero;
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
            if (!_uploading) SetStatus("Drop files to upload; folders are not supported.");
            return;
        }
        foreach (var file in files) _uploadQueue.Enqueue(file);
        _uploadTotal += files.Length;
        // Files added while an upload runs join its batch.
        if (_uploading) return;
        _uploading = true;
        var sent = 0;
        string? sentName = null;
        try
        {
            await NavigateToDownloads().WaitAsync(TimeSpan.FromSeconds(15));
            while (_uploadQueue.TryDequeue(out var file))
            {
                _uploadIndex++;
                var xferId = _fs.NextXferId();
                var name = System.IO.Path.GetFileName(file);
                _uploadIds.Add(xferId);
                _lastUploadId = xferId;
                _uploadStatus = $"Sent {name}{UploadProgress}";
                _uploadPath = null;
                SetStatus($"Uploading {name}{UploadProgress} to Downloads...");
                if (await _fs.SendFileAsync(_phoneId, file, xferId))
                {
                    sent++;
                    sentName = name;
                }
                else
                {
                    _uploadIds.Remove(xferId);
                }
            }
            if (_uploadTotal == 1)
            {
                if (sent == 1) Toast.Show($"“{sentName}” uploaded to the phone's Downloads folder.");
            }
            else if (sent > 0)
            {
                var count = sent == _uploadTotal ? $"{sent} files" : $"{sent} of {_uploadTotal} files";
                SetStatus($"Sent {count} to Downloads");
                Toast.Show($"{count} uploaded to the phone's Downloads folder.");
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
            _uploadQueue.Clear();
            _uploadIndex = _uploadTotal = 0;
            _uploading = false;
        }
    }
}
