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

/// <summary>Browses one device's filesystem; transfers land in Downloads.</summary>
public partial class FileExplorerWindow : Window
{
    private readonly WsClient _ws;
    private readonly FsService _fs;
    private readonly string _phoneId;
    private readonly string _phoneName;
    private readonly Action _showTransfers;
    private readonly Action<string> _downloaded;
    private string _path = "";
    private int _reqCounter;
    private readonly HashSet<uint> _uploadIds = new();
    private readonly List<uint> _downloadIds = new(); // downloads not yet ended, oldest first, which Cancel stops
    // The downloads started since the last batch ended, and how they went.
    private int _downloadTotal;
    private int _downloadsEnded;
    private int _downloadsSaved;
    private int _downloadsFailed;
    private string? _downloadError;
    // While a download lists its folders to count their files.
    private CancellationTokenSource? _counting;
    private readonly Dictionary<string, TaskCompletionSource<JsonNode>> _countListings = new();
    private int _countReqs;
    private readonly Queue<string> _uploadQueue = new();
    private int _uploadIndex;
    private int _uploadTotal;
    private uint _lastUploadId;
    private string? _uploadStatus;
    private string? _uploadPath;
    // The last batch's files that didn't arrive, which Retry sends again, and why the first didn't.
    private readonly List<string> _failedUploads = new();
    private string? _uploadError;
    private string? _downloadsPath;
    private string _requestedPath = "";
    private bool _uploading;
    private bool _uploadCancelled;
    private bool _navigateToDownloads;
    private TaskCompletionSource<bool>? _downloadsNavigation;
    private readonly AppSettings _settings;
    private FileSort _sort;
    private bool _foldersFirst;
    private List<FileEntry> _entries = new();

    /// <param name="showTransfers">Opens the window listing every device's transfers.</param>
    /// <param name="downloaded">Told of each file a download saves in Downloads, or of the
    /// folder there that a folder's file went in.</param>
    public FileExplorerWindow(WsClient ws, FsService fs, string phoneId, string phoneName, AppSettings settings,
        Action showTransfers, Action<string> downloaded)
    {
        InitializeComponent();
        _ws = ws;
        _fs = fs;
        _phoneId = phoneId;
        _phoneName = phoneName;
        _showTransfers = showTransfers;
        _downloaded = downloaded;
        _settings = settings;
        Title = phoneName;
        _sort = Enum.TryParse(settings.FileBrowserSort, out FileSort sort) && Enum.IsDefined(sort) ? sort : FileSort.ModifiedDesc;
        _foldersFirst = settings.FileBrowserFoldersFirst;
        UpdateSortButton();
        _fs.TransferChanged += OnTransferChanged;
        // Downloads already queued carry on without the window.
        Closed += (_, _) =>
        {
            _fs.TransferChanged -= OnTransferChanged;
            _counting?.Cancel();
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

    private string DownloadProgress =>
        _downloadTotal > 1 ? $" ({Math.Min(_downloadsEnded + 1, _downloadTotal)}/{_downloadTotal})" : "";

    private void OnTransferChanged(TransferUpdate u)
    {
        // Other devices' transfers show in their own windows.
        if (u.Peer != _phoneId) return;
        // Uploads report on this thread. Apply them now: a queued update would
        // land after the batch moves on and overwrite its newer status.
        if (Dispatcher.CheckAccess()) ShowUpdate(u, u.Sending ? UploadProgress : DownloadProgress);
        else Dispatcher.BeginInvoke(() => ShowUpdate(u, u.Sending ? "" : DownloadProgress));
    }

    private void ShowUpdate(TransferUpdate u, string batchProgress)
    {
        if (u.HasProgress)
        {
            // By the time a queued update runs, its transfer may have finished or been cancelled.
            if (_fs.IsActive(u.XferId)) SetStatus($"{u.Percent}% {u.Name}{batchProgress}", u.Sending);
        }
        else if (u.Text is { } text) SetStatus(text + batchProgress);
        // Cancelled here or in the transfers window; its own end never comes.
        if (u.State == TransferState.Cancelled) DownloadEnded(u.XferId, null, null);
    }

    private void UpdateCancelButton() =>
        CancelButton.Visibility = _downloadIds.Count > 0 || _uploading || _counting != null
            ? Visibility.Visible : Visibility.Collapsed;

    private void Transfers_Click(object sender, RoutedEventArgs e) => _showTransfers();

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        _counting?.Cancel();
        // A copy: each cancel reports back here, which takes it off the list.
        // Newest first, so a file cancelled under way doesn't make room for
        // one that is about to be cancelled too.
        var downloads = _downloadIds.ToList();
        downloads.Reverse();
        foreach (var id in downloads) _fs.Cancel(id);
        if (_uploading)
        {
            // The batch reports how far it got once the current file stops.
            _uploadCancelled = true;
            _uploadQueue.Clear();
            _fs.Cancel(_lastUploadId);
        }
        UpdateCancelButton();
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
        if (msg["from"]?.GetValue<string>() != _phoneId) return;
        var reqId = msg["reqId"]?.GetValue<string>();
        if (reqId != null && _countListings.TryGetValue(reqId, out var counted))
        {
            counted.TrySetResult(msg);
            return;
        }
        if (reqId != _reqCounter.ToString()) return;
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
                Label = e["label"]?.GetValue<string>(),
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
        var selected = EntryList.SelectedItems.Cast<FileEntry>().ToList();
        EntryList.ItemsSource = _entries.Sorted(_sort, _foldersFirst);
        if (selected.Count == 0) return;
        foreach (var entry in selected) EntryList.SelectedItems.Add(entry);
        EntryList.ScrollIntoView(selected[0]);
    }

    private string Join(string dir, string name) =>
        string.IsNullOrEmpty(dir) ? name : dir.TrimEnd('/') + "/" + name;

    private void Entry_DoubleClick(object sender, MouseButtonEventArgs e)
    {
        // The row clicked rather than the selection, which may hold several;
        // a double-click on the scroll bar is on no row at all.
        if (ItemsControl.ContainerFromElement(EntryList, (DependencyObject)e.OriginalSource)
            is not ListBoxItem { DataContext: FileEntry entry }) return;
        if (entry.Dir) RequestList(Join(_path, entry.Name));
        else _ = DownloadAsync(new List<FileEntry> { entry });
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

    // More than this asks first, unless it's one file picked on its own.
    private const int ConfirmFiles = 10;
    private const long ConfirmBytes = 100L * 1024 * 1024;

    /// <summary>A file a download asks for: <paramref name="Top"/> is the folder picked
    /// that holds it, if any, and <paramref name="Folder"/> its folder below that one.</summary>
    private record DownloadItem(string RemotePath, string Name, FileEntry? Top, string Folder, long Size);

    private void Download_Click(object sender, RoutedEventArgs e)
    {
        // In the order shown, not the order they were picked in.
        var selected = EntryList.SelectedItems.Cast<FileEntry>().ToHashSet();
        _ = DownloadAsync(EntryList.Items.Cast<FileEntry>().Where(selected.Contains).ToList());
    }

    /// <summary>
    /// Downloads files, and every file inside folders, into Downloads, each
    /// folder into a folder of its own there. A download of more than a few
    /// files, or a big one, is counted and confirmed first.
    /// </summary>
    private async Task DownloadAsync(List<FileEntry> picked)
    {
        if (picked.Count == 0)
        {
            SetStatus("Select files or folders first");
            return;
        }
        if (_counting != null)
        {
            SetStatus("Still counting the files in the last folder chosen");
            return;
        }
        // Where they are, even if the window moves on while folders are counted.
        var from = _path;
        var files = picked.Where(p => !p.Dir)
            .Select(p => new DownloadItem(Join(from, p.Name), p.Name, null, "", p.Size)).ToList();
        var folders = picked.Where(p => p.Dir).ToList();
        var unreadable = 0;
        if (folders.Count > 0)
        {
            _counting = new CancellationTokenSource();
            UpdateCancelButton();
            try
            {
                foreach (var folder in folders)
                    unreadable += await CountAsync(folder, Join(from, folder.Name), files, _counting.Token);
            }
            catch (OperationCanceledException)
            {
                SetStatus("Download cancelled");
                return;
            }
            catch (TimeoutException)
            {
                SetStatus($"Couldn't count the files: {_phoneName} didn't answer");
                return;
            }
            finally
            {
                _counting.Dispose();
                _counting = null;
                UpdateCancelButton();
            }
        }

        var size = files.Sum(f => f.Size);
        var skipped = unreadable switch
        {
            0 => "",
            1 => "One folder couldn't be opened, so its files are left out.",
            _ => $"{unreadable} folders couldn't be opened, so their files are left out.",
        };
        if (files.Count == 0)
        {
            SetStatus(unreadable > 0 ? "Nothing to download. " + skipped
                : $"Nothing to download: there are no files in {(folders.Count == 1 ? "that folder" : "those folders")}");
            return;
        }
        var ask = (picked.Count > 1 || folders.Count > 0) && (files.Count > ConfirmFiles || size > ConfirmBytes);
        if (ask)
        {
            var question = $"Download {files.Count} {(files.Count == 1 ? "file" : "files")} " +
                $"({FileEntry.FormatSize(size)}) from {_phoneName} to your Downloads folder?";
            if (skipped != "") question += "\n\n" + skipped;
            if (MessageBox.Show(this, question, "Download", MessageBoxButton.YesNo, MessageBoxImage.Question) !=
                MessageBoxResult.Yes)
            {
                SetStatus("Download cancelled");
                return;
            }
        }

        // Each folder is named after the one picked, with a counter if Downloads has one already.
        var roots = new Dictionary<FileEntry, string>();
        try
        {
            foreach (var folder in folders)
            {
                var name = FsService.SanitizeFileName(folder.DisplayName.TrimEnd('/', '\\'));
                var root = FsService.UniquePath(System.IO.Path.Combine(_fs.IncomingDir, name));
                System.IO.Directory.CreateDirectory(root);
                roots[folder] = root;
            }
        }
        catch (Exception ex)
        {
            SetStatus("Download failed: " + ex.Message);
            return;
        }
        _downloadTotal += files.Count;
        foreach (var file in files)
        {
            var xferId = _fs.NextXferId();
            var root = file.Top == null ? null : roots[file.Top];
            _downloadIds.Add(xferId);
            _fs.QueueDownload(xferId, _phoneId, file.RemotePath, file.Name,
                root == null ? _fs.IncomingDir : System.IO.Path.Combine(root, file.Folder),
                path => Dispatcher.BeginInvoke(() => DownloadSaved(xferId, path, root ?? path)),
                err => Dispatcher.BeginInvoke(() => DownloadEnded(xferId, null, err)));
        }
        SetStatus(files.Count == 1 ? $"Downloading {files[0].Name}..." : $"Downloading {files.Count} files...");
        // The question said so already.
        if (skipped != "" && !ask) Toast.Show(skipped);
        UpdateCancelButton();
    }

    /// <summary>Adds every file in a folder of the device, and in its folders, to <paramref name="files"/>.</summary>
    /// <returns>How many folders couldn't be listed, whose files are left out.</returns>
    private async Task<int> CountAsync(FileEntry top, string path, List<DownloadItem> files, CancellationToken cancel)
    {
        var unreadable = 0;
        var folders = new Queue<(string Path, string Local)>();
        folders.Enqueue((path, ""));
        while (folders.TryDequeue(out var folder))
        {
            var listing = await ListAsync(folder.Path, cancel);
            if (!string.IsNullOrEmpty(listing["error"]?.GetValue<string>()))
            {
                unreadable++;
                continue;
            }
            foreach (var e in listing["entries"]!.AsArray())
            {
                var name = e!["name"]!.GetValue<string>();
                if (e["dir"]?.GetValue<bool>() ?? false)
                    folders.Enqueue((Join(folder.Path, name),
                        System.IO.Path.Combine(folder.Local, FsService.SanitizeFileName(name))));
                else
                    files.Add(new DownloadItem(Join(folder.Path, name), name, top, folder.Local,
                        e["size"]?.GetValue<long>() ?? 0));
            }
            SetStatus($"Counting files to download: {files.Count} so far, {FileEntry.FormatSize(files.Sum(f => f.Size))}");
        }
        return unreadable;
    }

    /// <summary>Lists a folder without showing it.</summary>
    private async Task<JsonNode> ListAsync(string path, CancellationToken cancel)
    {
        var reqId = "count-" + ++_countReqs;
        var reply = new TaskCompletionSource<JsonNode>(TaskCreationOptions.RunContinuationsAsynchronously);
        _countListings[reqId] = reply;
        try
        {
            _ws.SendJson(new JsonObject { ["type"] = "fs-list", ["to"] = _phoneId, ["path"] = path, ["reqId"] = reqId });
            return await reply.Task.WaitAsync(TimeSpan.FromSeconds(15), cancel);
        }
        finally
        {
            _countListings.Remove(reqId);
        }
    }

    /// <param name="shown">The file, or the folder in Downloads that it went in.</param>
    private void DownloadSaved(uint xferId, string path, string shown)
    {
        if (_downloadIds.Contains(xferId)) _downloaded(shown);
        DownloadEnded(xferId, path, null);
    }

    /// <param name="saved">Where it was saved; null if it failed or was cancelled.</param>
    /// <param name="error">Why it failed; null if it was saved or cancelled.</param>
    private void DownloadEnded(uint xferId, string? saved, string? error)
    {
        // Already ended: cancelled before its end was posted here.
        if (!_downloadIds.Remove(xferId)) return;
        _downloadsEnded++;
        if (saved != null) _downloadsSaved++;
        else if (error != null)
        {
            _downloadsFailed++;
            _downloadError = error;
        }
        UpdateCancelButton();
        if (_downloadIds.Count > 0) return;
        // The last of the batch says how it went; one cancelled has said so already.
        if (_downloadTotal == 1)
        {
            if (saved != null) SetStatus("Saved to " + saved);
            else if (error != null) SetStatus("Failed: " + error);
        }
        else if (_downloadsSaved == _downloadTotal) SetStatus($"Saved {_downloadTotal} files to Downloads");
        else if (_downloadsSaved > 0)
            SetStatus($"Saved {_downloadsSaved} of {_downloadTotal} files to Downloads" +
                (_downloadsFailed > 0 ? $"; {_downloadsFailed} failed: {_downloadError}" : ""));
        else if (_downloadsFailed > 0) SetStatus($"{_downloadsFailed} of {_downloadTotal} downloads failed: {_downloadError}");
        else SetStatus("Download cancelled");
        _downloadTotal = _downloadsEnded = _downloadsSaved = _downloadsFailed = 0;
        _downloadError = null;
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
        _uploadCancelled = false;
        // A new batch takes over from the last one's failures.
        _failedUploads.Clear();
        _uploadError = null;
        RetryButton.Visibility = Visibility.Collapsed;
        UpdateCancelButton();
        var sent = 0;
        string? sentName = null;
        try
        {
            await NavigateToDownloads().WaitAsync(TimeSpan.FromSeconds(15));
            while (!_uploadCancelled && _uploadQueue.TryDequeue(out var file))
            {
                _uploadIndex++;
                var xferId = _fs.NextXferId();
                var name = System.IO.Path.GetFileName(file);
                _uploadIds.Add(xferId);
                _lastUploadId = xferId;
                _uploadStatus = $"Sent {name}{UploadProgress}";
                _uploadPath = null;
                SetStatus($"Uploading {name}{UploadProgress} to Downloads...");
                // Sent only once the device says it saved it: a file can go in full and still be lost.
                var result = await _fs.SendFileAsync(_phoneId, file, xferId, awaitSaved: true);
                if (result.State == TransferState.Done)
                {
                    sent++;
                    sentName = name;
                }
                else
                {
                    _uploadIds.Remove(xferId);
                    // A file cancelled in the transfers window isn't one to retry.
                    if (result.State == TransferState.Failed)
                    {
                        _failedUploads.Add(file);
                        _uploadError ??= result.Error;
                    }
                }
            }
            if (_uploadCancelled)
            {
                SetStatus(sent > 0 ? $"Upload cancelled after sending {sent} of {_uploadTotal} files" : "Upload cancelled");
            }
            else if (_failedUploads.Count > 0)
            {
                ShowUploadFailure(sent);
            }
            else if (_uploadTotal == 1)
            {
                if (sent == 1) Toast.Show($"“{sentName}” uploaded to the Downloads folder on {_phoneName}.");
            }
            else if (sent > 0)
            {
                var count = sent == _uploadTotal ? $"{sent} files" : $"{sent} of {_uploadTotal} files";
                SetStatus($"Sent {count} to Downloads");
                Toast.Show($"{count} uploaded to the Downloads folder on {_phoneName}.");
            }
        }
        catch (Exception ex)
        {
            _navigateToDownloads = false;
            _downloadsNavigation = null;
            // Only finding Downloads throws, before any file has gone.
            _failedUploads.AddRange(_uploadQueue);
            _uploadError = ex is TimeoutException ? $"{_phoneName} didn't answer" : ex.Message;
            ShowUploadFailure(sent);
        }
        finally
        {
            _uploadQueue.Clear();
            _uploadIndex = _uploadTotal = 0;
            _uploading = false;
            UpdateCancelButton();
        }
    }

    /// <summary>Says which files didn't arrive and why, and offers to send them again.</summary>
    private void ShowUploadFailure(int sent)
    {
        var what = _failedUploads.Count == 1
            ? $"“{System.IO.Path.GetFileName(_failedUploads[0])}”"
            : $"{_failedUploads.Count} files";
        var text = sent > 0
            ? $"Sent {sent} of {_uploadTotal} files. Couldn't upload {_failedUploads.Count}: {_uploadError}"
            : $"Couldn't upload {what}: {_uploadError}";
        SetStatus(text);
        StatusText.ToolTip = text; // the reason may not fit
        RetryButton.Visibility = Visibility.Visible;
        Toast.Show($"Couldn't upload {what} to {_phoneName}: {_uploadError}", 6000);
    }

    private async void Retry_Click(object sender, RoutedEventArgs e) => await UploadFilesAsync(_failedUploads.ToList());
}
