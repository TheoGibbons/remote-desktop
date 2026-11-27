using System.IO;
using System.Text;

namespace RemoteDesktopWin;

/// <summary>
/// The app's own record of what went wrong with it, for whoever looks into it
/// afterwards: app.log beside settings.json, a line when the app starts and a
/// few when its window stops keeping up (see <see cref="UiWatchdog"/>). Until
/// this there was nothing: a PC that could not be reached for an evening left
/// no trace of why.
///
/// It says what happened and when, never what was in a message.
///
/// Nothing is written until <see cref="Start"/> names the file, which only
/// the app itself does. A test that builds the main window must not write
/// into the installed app's folder.
/// </summary>
internal static class AppLog
{
    // Past this the file becomes app.log.1, replacing the one before it.
    private const long MaxBytes = 512 * 1024;
    private static readonly object Gate = new();
    private static string? _path;
    // The last few lines, for the report a viewer's Stats can ask this PC for.
    private const int Remembered = 30;
    private static readonly Queue<string> RecentLines = new();

    /// <summary>Takes the lines in place of writing them. For a test.</summary>
    internal static Action<string>? Capture;

    internal static void Start(string path)
    {
        lock (Gate) _path = path;
    }

    /// <summary>The last lines written, oldest first, each with its time. Any thread.</summary>
    internal static string[] Recent()
    {
        lock (Gate) return RecentLines.ToArray();
    }

    /// <summary>Any thread; never throws.</summary>
    internal static void Write(string line)
    {
        if (Capture is { } capture)
        {
            capture(line);
            return;
        }
        string stamped = $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz}  {line}";
        lock (Gate)
        {
            if (RecentLines.Count >= Remembered) RecentLines.Dequeue();
            RecentLines.Enqueue(stamped);
            if (_path == null) return;
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
                var file = new FileInfo(_path);
                if (file.Exists && file.Length > MaxBytes) File.Move(_path, _path + ".1", overwrite: true);
                File.AppendAllText(_path, stamped + Environment.NewLine, Encoding.UTF8);
            }
            catch { } // a log that can't be written must not be what breaks the app
        }
    }
}
