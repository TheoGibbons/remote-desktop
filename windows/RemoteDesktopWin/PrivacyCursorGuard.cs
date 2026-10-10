using System.Diagnostics;
using Microsoft.Win32.SafeHandles;

namespace RemoteDesktopWin;

/// <summary>A separate process restores the scheme if the host dies with blank cursors.</summary>
internal sealed class PrivacyCursorGuard : IDisposable
{
    private const string Switch = "--privacy-cursor-guard";
    private readonly EventWaitHandle _finished;
    private readonly EventWaitHandle _ready;
    private readonly EventWaitHandle _restore;
    private Process? _process;

    internal PrivacyCursorGuard()
    {
        string name = "Local\\RemoteDesktopPrivacyCursor-" + Guid.NewGuid().ToString("N");
        _finished = new EventWaitHandle(false, EventResetMode.ManualReset, name);
        _ready = new EventWaitHandle(false, EventResetMode.ManualReset, name + "-ready");
        _restore = new EventWaitHandle(false, EventResetMode.ManualReset, name + "-restore");
        try
        {
            var start = new ProcessStartInfo(Environment.ProcessPath!)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            start.ArgumentList.Add(Switch);
            start.ArgumentList.Add(Environment.ProcessId.ToString());
            start.ArgumentList.Add(name);
            _process = Process.Start(start) ?? throw new InvalidOperationException("Cannot start cursor restoration helper.");
            if (!_ready.WaitOne(TimeSpan.FromSeconds(5)))
                throw new InvalidOperationException("Cursor restoration helper did not start.");
            if (_process.HasExited) throw new InvalidOperationException("Cursor restoration helper exited.");
        }
        catch { Dispose(); throw; }
    }

    // Handled before WPF opens the main window (and by the native smoke harness).
    internal static bool TryRun(string[] args)
    {
        if (args.Length == 0 || args[0] != Switch) return false;
        if (args.Length != 3 || !int.TryParse(args[1], out int parentId)) return true;
        try
        {
            using var finished = EventWaitHandle.OpenExisting(args[2]);
            using var ready = EventWaitHandle.OpenExisting(args[2] + "-ready");
            using var restore = EventWaitHandle.OpenExisting(args[2] + "-restore");
            using var parent = Process.GetProcessById(parentId);
            using var parentExited = new EventWaitHandle(false, EventResetMode.ManualReset);
            parentExited.SafeWaitHandle = new SafeWaitHandle(parent.Handle, ownsHandle: false);
            ready.Set();
            if (WaitHandle.WaitAny([finished, parentExited]) == 1 || restore.WaitOne(0)) PrivacyCursor.Restore();
        }
        catch (ArgumentException) { PrivacyCursor.Restore(); } // parent already exited
        catch (WaitHandleCannotBeOpenedException) { } // host closed before the helper started
        return true;
    }

    internal bool IsAlive => _process is { HasExited: false };
    internal void RequestRestore() => _restore.Set();

    public void Dispose()
    {
        _finished.Set(); // normal host cleanup already restored the scheme
        _process?.WaitForExit(2000);
        _process?.Dispose();
        _process = null;
        _ready.Dispose();
        _restore.Dispose();
        _finished.Dispose();
    }
}
