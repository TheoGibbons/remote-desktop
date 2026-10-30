using System.Diagnostics;
using System.IO;

namespace RemoteDesktopWin;

/// <summary>
/// Shows Downloads in Explorer after a files window downloads something, or a
/// device sends this PC a file unasked, once every transfer in the transfers
/// list has ended, so a batch opens it once
/// rather than for each file. It opens at most once a minute, and not at all
/// while Explorer already shows Downloads. Used on the UI thread.
/// </summary>
public sealed class DownloadsFolderOpener
{
    private static readonly TimeSpan MinInterval = TimeSpan.FromMinutes(1);

    private readonly TransferList _transfers;
    private readonly string _folder;
    private readonly Func<string, Task<bool>> _shownInExplorer;
    private readonly Action<string> _select;
    private readonly Func<DateTime> _now;
    private string? _saved; // the last file or folder saved, which Explorer selects
    private DateTime? _opened;
    private bool _checking;

    /// <param name="post">Runs an action on this thread once the work queued on it is done.</param>
    public DownloadsFolderOpener(TransferList transfers, string folder, Action<Action> post)
        : this(transfers, folder, post, ShownInExplorerAsync, Select, () => DateTime.UtcNow) { }

    internal DownloadsFolderOpener(TransferList transfers, string folder, Action<Action> post,
        Func<string, Task<bool>> shownInExplorer, Action<string> select, Func<DateTime> now)
    {
        _transfers = transfers;
        _folder = folder;
        _shownInExplorer = shownInExplorer;
        _select = select;
        _now = now;
        // The list hears that a file has arrived before its files window says
        // where it went, so the last file of a batch is selected rather than
        // the one before it.
        transfers.Changed += () =>
        {
            if (_saved != null && transfers.ActiveCount == 0) post(() => _ = OpenWhenIdleAsync());
        };
    }

    /// <param name="path">A file just downloaded or sent into Downloads, or the folder in it
    /// that a folder's files are downloaded into.</param>
    public void Saved(string path)
    {
        _saved = path;
        _ = OpenWhenIdleAsync();
    }

    private async Task OpenWhenIdleAsync()
    {
        if (_saved is not { } saved || _transfers.ActiveCount > 0 || _checking) return;
        _saved = null;
        if (_opened is { } last && _now() - last < MinInterval) return;
        _checking = true;
        try
        {
            if (await _shownInExplorer(_folder)) return;
        }
        catch { /* Can't tell, so open it. */ }
        finally { _checking = false; }
        _opened = _now();
        _select(saved);
    }

    /// <summary>Whether a File Explorer window or tab shows <paramref name="folder"/>.</summary>
    private static Task<bool> ShownInExplorerAsync(string folder)
    {
        // Shell windows are asked on a thread of their own: a busy Explorer
        // must not hold up this app's window.
        var shown = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var thread = new Thread(() =>
        {
            try { shown.SetResult(ShownInExplorer(folder)); }
            catch (Exception ex) { shown.SetException(ex); }
        }) { IsBackground = true };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return shown.Task.WaitAsync(TimeSpan.FromSeconds(3));
    }

    private static bool ShownInExplorer(string folder)
    {
        if (Type.GetTypeFromProgID("Shell.Application") is not { } type) return false;
        dynamic shell = Activator.CreateInstance(type)!;
        // Every Explorer window and tab, and any other window the shell hosts.
        foreach (dynamic window in shell.Windows())
        {
            string? path;
            try { path = window.Document?.Folder?.Self?.Path; }
            catch { continue; } // not showing a folder
            // Places such as This PC have a ::{guid} path instead.
            if (path != null && Path.IsPathFullyQualified(path) && string.Equals(
                    Path.TrimEndingDirectorySeparator(path), Path.TrimEndingDirectorySeparator(folder),
                    StringComparison.OrdinalIgnoreCase))
                return true;
        }
        return false;
    }

    // Through Explorer, so it opens as the signed-in user even while this app
    // runs as administrator.
    private static void Select(string path)
    {
        var args = Path.Exists(path) ? $"/select,\"{path}\"" : $"\"{Path.GetDirectoryName(path)}\"";
        try { Process.Start(new ProcessStartInfo("explorer.exe", args) { UseShellExecute = false }); }
        catch { /* Nothing to show it in, and nothing the user asked for. */ }
    }
}
