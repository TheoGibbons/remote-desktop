using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace RemoteDesktopWin;

/// <summary>
/// Saves the app's memory to a file when its window stalls (see <see
/// cref="UiWatchdog"/>), so that what every thread was doing can be read from
/// it afterwards. A stall that has passed leaves nothing else behind.
///
/// A second copy of the app does the writing, started with <see
/// cref="Switch"/>: a process that saves its own memory can stop on a lock
/// one of its own held threads has. At most one file a run, none within
/// <see cref="Apart"/> of the last, and only the newest is kept: each is
/// about as large as the app is in memory, several hundred megabytes.
///
/// The file holds whatever the app had in memory, the session key included.
/// It stays on this PC, under %LocalAppData%, and nothing sends it anywhere.
/// </summary>
internal static class StallDump
{
    internal const string Switch = "--write-dump";
    private static readonly TimeSpan Apart = TimeSpan.FromHours(12);
    private const long FreeSpaceNeeded = 4L * 1024 * 1024 * 1024;
    private static int _asked;

    internal static string Folder => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        AppProfile.StorageFolder(AppProfile.Name), "dumps");

    /// <summary>
    /// Asks for this app's memory to be saved, and says in the log what came
    /// of asking. Any thread; does not wait for the file.
    /// </summary>
    internal static void Request()
    {
        if (Interlocked.Exchange(ref _asked, 1) != 0) return;
        try
        {
            string folder = Folder;
            Directory.CreateDirectory(folder);
            var earlier = new DirectoryInfo(folder).GetFiles("*.dmp");
            if (earlier.Any(f => DateTime.UtcNow - f.LastWriteTimeUtc < Apart))
            {
                AppLog.Write($"Not saving the app's memory: there is a file from the last {Apart.TotalHours:0} hours in {folder}.");
                return;
            }
            if (new DriveInfo(Path.GetPathRoot(folder)!).AvailableFreeSpace < FreeSpaceNeeded)
            {
                AppLog.Write("Not saving the app's memory: the disk has under 4 GB free.");
                return;
            }
            foreach (var file in earlier) file.Delete();
            foreach (var part in new DirectoryInfo(folder).GetFiles("*.part")) part.Delete();

            string path = Path.Combine(folder, $"stall-{DateTime.Now:yyyyMMdd-HHmmss}.dmp");
            var start = new ProcessStartInfo(Environment.ProcessPath!)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden,
            };
            start.ArgumentList.Add(Switch);
            start.ArgumentList.Add(Environment.ProcessId.ToString());
            start.ArgumentList.Add(path);
            using var helper = Process.Start(start);
            AppLog.Write($"Saving the app's memory to {path}");
        }
        catch (Exception ex)
        {
            AppLog.Write("Couldn't save the app's memory: " + ex.Message);
        }
    }

    // Handled before WPF opens the main window, like the cursor guard.
    internal static bool TryRun(string[] args)
    {
        if (args.Length == 0 || args[0] != Switch) return false;
        if (args.Length == 3 && int.TryParse(args[1], out int processId)) Write(processId, args[2]);
        return true;
    }

    /// <summary>Saves another process's memory. False if that failed, leaving no file.</summary>
    internal static bool Write(int processId, string path)
    {
        // Under another name until it is whole, so a half-written file is never taken for one.
        string part = path + ".part";
        try
        {
            using var process = Process.GetProcessById(processId);
            bool written;
            using (var file = new FileStream(part, FileMode.Create, FileAccess.ReadWrite, FileShare.None))
                written = MiniDumpWriteDump(process.Handle, (uint)processId, file.SafeFileHandle, FullMemory,
                    IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
            if (written)
            {
                File.Move(part, path, overwrite: true);
                return true;
            }
        }
        catch { }
        try { File.Delete(part); } catch { }
        return false;
    }

    // MiniDumpWithFullMemory | WithHandleData | WithUnloadedModules | WithFullMemoryInfo | WithThreadInfo:
    // everything a debugger needs to name what each thread was in, .NET code included.
    private const uint FullMemory = 0x2 | 0x4 | 0x20 | 0x800 | 0x1000;

    [DllImport("dbghelp.dll", SetLastError = true)]
    private static extern bool MiniDumpWriteDump(IntPtr process, uint processId, SafeFileHandle file, uint type,
        IntPtr exception, IntPtr userStream, IntPtr callback);
}
