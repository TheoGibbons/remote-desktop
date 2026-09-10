using System.Drawing;
using System.Runtime.InteropServices;

namespace RemoteDesktopWin;

/// <summary>One attached monitor, in stitched-surface coordinates.</summary>
public readonly record struct MonitorInfo(Rectangle Bounds, bool Primary);

/// <summary>
/// Monitor geometry for the streamed desktop. Read from Win32 rather than from
/// the capture source so it is the same on the DXGI and GDI paths, and
/// available before either has produced a frame.
///
/// Viewers use this to snap their viewport to a single monitor: a phone showing
/// a two-monitor desktop is looking at a 3.56:1 strip, which wastes most of a
/// portrait screen and is unreadable at fit-to-width.
/// </summary>
public static class Monitors
{
    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public uint dwFlags;
    }

    private delegate bool MonitorEnumProc(IntPtr monitor, IntPtr hdc, ref RECT rect, IntPtr data);

    [DllImport("user32.dll")]
    private static extern bool EnumDisplayMonitors(IntPtr hdc, IntPtr clip, MonitorEnumProc proc, IntPtr data);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern bool GetMonitorInfoW(IntPtr monitor, ref MONITORINFO info);

    private const uint MONITORINFOF_PRIMARY = 1;

    /// <summary>
    /// Attached monitors relative to <paramref name="vs"/>, the virtual-screen
    /// bounding box the streamer composites into. Ordered left to right, then
    /// top to bottom, so a viewer can number them the way they are arranged.
    /// </summary>
    public static List<MonitorInfo> Enumerate(Rectangle vs)
    {
        var found = new List<MonitorInfo>();
        try
        {
            EnumDisplayMonitors(IntPtr.Zero, IntPtr.Zero,
                (IntPtr monitor, IntPtr hdc, ref RECT rect, IntPtr data) =>
                {
                    var r = rect;
                    bool primary = false;
                    var mi = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
                    if (GetMonitorInfoW(monitor, ref mi))
                    {
                        r = mi.rcMonitor;
                        primary = (mi.dwFlags & MONITORINFOF_PRIMARY) != 0;
                    }
                    found.Add(new MonitorInfo(
                        new Rectangle(r.Left - vs.X, r.Top - vs.Y, r.Right - r.Left, r.Bottom - r.Top),
                        primary));
                    return true;
                }, IntPtr.Zero);
        }
        catch { /* fall through to the whole-surface answer below */ }

        if (found.Count == 0) found.Add(new MonitorInfo(new Rectangle(0, 0, vs.Width, vs.Height), true));
        found.Sort((a, b) => a.Bounds.X != b.Bounds.X
            ? a.Bounds.X.CompareTo(b.Bounds.X)
            : a.Bounds.Y.CompareTo(b.Bounds.Y));
        return found;
    }
}
