using System.Runtime.InteropServices;
using System.Text;

namespace RemoteDesktopWin;

/// <summary>
/// What this PC says about itself to an approved device that asks, for the
/// export of a viewer's Stats (see PROTOCOL.md, <c>diagnostic-report</c>): a
/// few lines on the app, its window, its screen and its stream, and the end
/// of its log.
///
/// It is put together and sent from the socket's thread, not the window's.
/// The time it is wanted most is when the PC is online and answering nothing,
/// which is the window's thread fallen behind, and an answer that waited for
/// that thread would not come. So everything here is read from what any
/// thread may read.
/// </summary>
internal static class HostReport
{
    /// <param name="Stream">What the screen stream is doing, in a few words.</param>
    /// <param name="Log">The end of the app's log, oldest line first.</param>
    internal readonly record struct Facts(
        string Name, string Version, bool Elevated, TimeSpan Running, MessageInbox.State Window,
        int Monitors, int DesktopWidth, int DesktopHeight, long? SecondsSinceInput,
        string Stream, string? H264Note, long QueuedBytes, long VideoFramesDropped, IReadOnlyList<string> Log);

    internal static string Build(Facts f)
    {
        var text = new StringBuilder();
        text.Append("Remote Desktop ").Append(f.Version).Append(" on Windows, \"").Append(f.Name).Append('"');
        if (f.Elevated) text.Append(", as administrator");
        text.Append(", running ").Append(Span(f.Running)).Append('\n');

        text.Append("Window: ");
        if (f.Window.Handling != null)
            text.Append("handling \"").Append(f.Window.Handling).Append("\" for ").Append(Seconds(f.Window.HandlingMs)).Append("; ");
        text.Append(f.Window.Waiting switch
        {
            0 => "no messages waiting",
            1 => $"1 message waiting, for {Seconds(f.Window.OldestWaitingMs)}",
            _ => $"{f.Window.Waiting} messages waiting, the next for {Seconds(f.Window.OldestWaitingMs)}",
        }).Append('\n');

        text.Append("Screen: ").Append(f.Monitors == 1 ? "1 monitor" : $"{f.Monitors} monitors")
            .Append(", ").Append(f.DesktopWidth).Append('x').Append(f.DesktopHeight).Append(" in all");
        if (f.SecondsSinceInput is { } idle) text.Append("; last mouse or keyboard input ").Append(Seconds(idle * 1000)).Append(" ago");
        text.Append('\n');

        text.Append("Stream: ").Append(f.Stream).Append("; ")
            .Append(f.QueuedBytes).Append(" bytes waiting to go, ")
            .Append(f.VideoFramesDropped).Append(" frames dropped unsent since the app started\n");
        if (f.H264Note != null) text.Append("H.264: ").Append(f.H264Note).Append('\n');

        if (f.Log.Count == 0) text.Append("Log: nothing since the app started\n");
        else
        {
            text.Append("Log, newest last:\n");
            foreach (var line in f.Log) text.Append("  ").Append(line).Append('\n');
        }
        return text.ToString();
    }

    private static string Seconds(long ms) => $"{ms / 1000.0:0.#} s";

    private static string Span(TimeSpan span) =>
        span.TotalHours >= 1 ? $"{(int)span.TotalHours} h {span.Minutes} min" :
        span.TotalMinutes >= 1 ? $"{span.Minutes} min" : $"{span.Seconds} s";

    private const int SM_CXVIRTUALSCREEN = 78;
    private const int SM_CYVIRTUALSCREEN = 79;
    private const int SM_CMONITORS = 80;

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [StructLayout(LayoutKind.Sequential)]
    private struct LastInputInfo
    {
        public uint Size;
        public uint Time;
    }

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LastInputInfo info);

    /// <summary>How many monitors Windows has now, and the size of the desktop across them.</summary>
    internal static (int Monitors, int Width, int Height) Screen() =>
        (GetSystemMetrics(SM_CMONITORS), GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN));

    /// <summary>
    /// Seconds since the mouse or keyboard was last used here, a viewer's
    /// input included, or null if Windows won't say.
    /// </summary>
    internal static long? SecondsSinceInput()
    {
        var info = new LastInputInfo { Size = (uint)Marshal.SizeOf<LastInputInfo>() };
        if (!GetLastInputInfo(ref info)) return null;
        return unchecked((uint)Environment.TickCount - info.Time) / 1000;
    }
}
