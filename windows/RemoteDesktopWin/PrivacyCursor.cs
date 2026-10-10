using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RemoteDesktopWin;

/// <summary>Temporarily blanks the desktop's standard pointer shapes.</summary>
internal sealed class PrivacyCursor : IDisposable
{
    // Includes help, location and person selection in addition to the older
    // normal/text/busy/resize/link cursors. These IDs are supported on Win10+.
    internal static readonly uint[] CursorIds =
        [32512, 32513, 32514, 32515, 32516, 32642, 32643, 32644, 32645, 32646,
         32648, 32649, 32650, 32651, 32671, 32672];
    private bool _changed;

    internal PrivacyCursor()
    {
        var andMask = new byte[128]; // 32 x 32 monochrome, AND=1 / XOR=0: transparent
        Array.Fill(andMask, (byte)0xFF);
        var xorMask = new byte[128];
        try
        {
            foreach (uint id in CursorIds)
            {
                var cursor = CreateCursor(IntPtr.Zero, 0, 0, 32, 32, andMask, xorMask);
                if (cursor == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
                // SetSystemCursor takes ownership and destroys the supplied cursor.
                if (!SetSystemCursor(cursor, id))
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                _changed = true;
            }
        }
        catch { Dispose(); throw; }
    }

    // Reload the configured scheme without changing any persisted settings.
    internal static bool Restore() => SystemParametersInfo(0x0057, 0, IntPtr.Zero, 0); // SPI_SETCURSORS

    public void Dispose()
    {
        if (!_changed) return;
        _changed = false;
        if (!Restore()) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot restore the Windows cursor scheme.");
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CreateCursor(IntPtr instance, int x, int y, int width, int height, byte[] andMask, byte[] xorMask);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetSystemCursor(IntPtr cursor, uint id);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SystemParametersInfo(uint action, uint parameter, IntPtr value, uint flags);
}
