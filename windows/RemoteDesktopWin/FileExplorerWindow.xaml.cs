using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

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
    private readonly AppSettings _settings;
    private FileSort _sort;
    private bool _foldersFirst;
    private List<FileEntry> _entries = new();

    public FileExplorerWindow(WsClient ws, FsService fs, string phoneId, AppSettings settings)
    {
        InitializeComponent();
        _ws = ws;
        _fs = fs;
        _phoneId = phoneId;
        _settings = settings;
        _sort = Enum.TryParse(settings.FileBrowserSort, out FileSort sort) && Enum.IsDefined(sort) ? sort : FileSort.ModifiedDesc;
        _foldersFirst = settings.FileBrowserFoldersFirst;
        UpdateSortButton();
        _fs.TransferStatus += OnTransferStatus;
        _fs.TransferProgress += OnTransferProgress;
        Closed += (_, _) =>
        {
            _fs.TransferStatus -= OnTransferStatus;
            _fs.TransferProgress -= OnTransferProgress;
        };
        if (MainWindow.IsElevated()) Loaded += (_, _) => AcceptElevatedFileDrops();
        // Open in Downloads, which the first listing says where to find.
        _navigateToDownloads = true;
        RequestList("");
    }

    /// <param name="sending">Set for transfer progress, which gets an up or down arrow.</param>
    private void SetStatus(string text, bool? sending = null)
    {
        if (sending is bool up) StatusText.ShowTransfer(up, text);
        else StatusText.Text = text;
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

    private void OnTransferProgress(bool sending, int percent, string name)
    {
        var text = $"{percent}% {name}";
        if (Dispatcher.CheckAccess()) SetStatus(text + UploadProgress, sending);
        else Dispatcher.BeginInvoke(() => SetStatus(text, sending));
    }

    public void OnUploadResult(JsonNode msg)
    {
        if (msg["from"]?.GetValue<string>() != _phoneId ||
            msg["xferId"]?.GetValue<long>() is not long id ||
            id < 0 || id > uint.MaxValue || !_uploadIds.Remove((uint)id)) return;
        if (id == _lastUploadId)
        {
            _uploadPath = msg["path"]?.GetValue<string>();
            if (StatusText.Text == _uploadStatus) StatusText.ToolTip = _uploadPath;
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
        // Older phones don't advertise Downloads; their first listing is the storage
        // root. A PC's is its drive list, with no path to guess from.
        if (_downloadsPath == null && _requestedPath == "" && _path != "") _downloadsPath = Join(_path, "Download");
        if (_navigateToDownloads && _downloadsPath != null)
        {
            _ = NavigateToDownloads();
            return;
        }
        if (_navigateToDownloads && _requestedPath == "")
        {
            // A PC older than 1.0.31 doesn't say where Downloads is. Stay at its
            // drives; an upload still goes ahead, since it lands in Downloads anyway.
            _navigateToDownloads = false;
            _downloadsNavigation?.TrySetResult(true);
            _downloadsNavigation = null;
        }
        PathBox.Text = _path;
        _entries = new List<FileEntry>();
        foreach (var e in msg["entries"]!.AsArray())
        {
            _entries.Add(new FileEntry
            {
                Name = e!["name"]!.GetValue<string>(),
                Dir = e["dir"]?.GetValue<bool>() ?? false,
                Size = e["size"]?.GetValue<long>() ?? 0,
                MTime = e["mtime"]?.GetValue<long>() ?? 0,
            });
        }
        EntryList.ItemsSource = _entries.Sorted(_sort, _foldersFirst);
        if (!_uploading) SetStatus($"{_entries.Count} items");
    }

    private void UpdateSortButton()
    {
        SortText.Text = _sort.Summary();
        SortButton.ToolTip = $"Sort: {_sort.Label()}" + (_foldersFirst ? ", folders first" : "");
    }

    private void Sort_Click(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu { Style = (Style)FindResource("RdMenu") };
        foreach (var sort in Enum.GetValues<FileSort>())
        {
            // Check mark beside the current order.
            var item = SortMenuItem(sort == _sort ? "" : "", sort.Label());
            item.Click += (_, _) => ApplySort(sort, _foldersFirst);
            menu.Items.Add(item);
        }
        menu.Items.Add(new Separator { Style = (Style)FindResource("RdMenuSeparator") });
        // Ticked or empty checkbox.
        var foldersFirst = SortMenuItem(_foldersFirst ? "" : "", "Folders first");
        foldersFirst.Click += (_, _) => ApplySort(_sort, !_foldersFirst);
        menu.Items.Add(foldersFirst);
        menu.PlacementTarget = (UIElement)sender;
        menu.Placement = PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    private MenuItem SortMenuItem(string glyph, string label)
    {
        var header = new StackPanel { Orientation = Orientation.Horizontal };
        header.Children.Add(new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            Foreground = (Brush)FindResource("RdPrimaryBrush"),
            Width = 24,
            VerticalAlignment = VerticalAlignment.Center,
        });
        header.Children.Add(new TextBlock { Text = label });
        return new MenuItem { Header = header, Style = (Style)FindResource("RdMenuItem") };
    }

    private void ApplySort(FileSort sort, bool foldersFirst)
    {
        _sort = sort;
        _foldersFirst = foldersFirst;
        _settings.FileBrowserSort = sort.ToString();
        _settings.FileBrowserFoldersFirst = foldersFirst;
        _settings.Save();
        UpdateSortButton();
        // ItemsSource is null while a folder loads; that listing sorts itself.
        if (EntryList.ItemsSource == null) return;
        var selected = EntryList.SelectedItem;
        EntryList.ItemsSource = _entries.Sorted(_sort, _foldersFirst);
        if (selected == null) return;
        EntryList.SelectedItem = selected;
        EntryList.ScrollIntoView(selected);
    }

    private string Join(string dir, string name) =>
        string.IsNullOrEmpty(dir) ? name : dir.TrimEnd('/') + "/" + name;

    private void Entry_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        if (EntryList.SelectedItem is not FileEntry entry) return;
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
        if (EntryList.SelectedItem is not FileEntry entry || entry.Dir)
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
    private const int VK_LBUTTON = 0x01;
    private const int VK_RBUTTON = 0x02;
    private const uint GA_ROOT = 2;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }

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

    [DllImport("user32.dll")]
    private static extern short GetAsyncKeyState(int key);

    [DllImport("user32.dll")]
    private static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll")]
    private static extern IntPtr WindowFromPoint(POINT point);

    [DllImport("user32.dll")]
    private static extern IntPtr GetAncestor(IntPtr hwnd, uint flags);

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    private bool _heldOutside;

    private void AcceptElevatedFileDrops()
    {
        if (PresentationSource.FromVisual(this) is not HwndSource source) return;
        var hwnd = source.Handle;
        RevokeDragDrop(hwnd);
        foreach (var message in new[] { (uint)WM_DROPFILES, WM_COPYDATA, WM_COPYGLOBALDATA })
            ChangeWindowMessageFilterEx(hwnd, message, MSGFLT_ALLOW, IntPtr.Zero);
        DragAcceptFiles(hwnd, true);
        source.AddHook(DropFilesHook);
        var dragPoll = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(40) };
        dragPoll.Tick += (_, _) => ShowDropZoneWhileDragging(hwnd);
        dragPoll.Start();
        Closed += (_, _) => dragPoll.Stop();
    }

    // WM_DROPFILES brings no drag-over messages, only the drop itself, so the
    // drop zone shows while a mouse button that went down outside this window
    // is held over it. Moving or resizing this window can also carry a held
    // button over it, but those leave this window in front.
    private void ShowDropZoneWhileDragging(IntPtr hwnd)
    {
        var held = GetAsyncKeyState(VK_LBUTTON) < 0 || GetAsyncKeyState(VK_RBUTTON) < 0;
        var over = GetCursorPos(out var point) && GetAncestor(WindowFromPoint(point), GA_ROOT) == hwnd;
        _heldOutside = held && (_heldOutside || !over);
        var dragging = _heldOutside && over && GetForegroundWindow() != hwnd;
        DropOverlay.Visibility = dragging ? Visibility.Visible : Visibility.Collapsed;
    }

    private IntPtr DropFilesHook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg != WM_DROPFILES) return IntPtr.Zero;
        handled = true;
        DropOverlay.Visibility = Visibility.Collapsed;
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
