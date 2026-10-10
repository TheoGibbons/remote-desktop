using System.ComponentModel;
using System.Drawing;
using System.Runtime.InteropServices;
using Forms = System.Windows.Forms;

namespace RemoteDesktopWin;

/// <summary>An opaque, non-activating native window excluded from capture.</summary>
internal sealed class PrivacyOverlayWindow : Forms.Form
{
    private IntPtr _configuredHandle;
    private readonly Font _captionFont = new("Segoe UI", 12);
    internal PrivacyOverlayWindow(Rectangle bounds)
    {
        FormBorderStyle = Forms.FormBorderStyle.None;
        StartPosition = Forms.FormStartPosition.Manual;
        AutoScaleMode = Forms.AutoScaleMode.None;
        ShowInTaskbar = false;
        BackColor = Color.Black;
        Bounds = bounds;
        TopMost = true;
        Controls.Add(new Forms.Label
        {
            Text = "type unlock",
            ForeColor = Color.FromArgb(150, 150, 150),
            BackColor = Color.Black,
            Font = _captionFont,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = Forms.DockStyle.Fill,
        });
    }

    protected override bool ShowWithoutActivation => true;
    protected override Forms.CreateParams CreateParams
    {
        get
        {
            var cp = base.CreateParams;
            // Layered + transparent makes hit testing pass through to windows
            // on other threads too. Alpha stays 255: the screen is fully black.
            cp.ExStyle |= 0x08000000 | 0x00000080 | 0x00000020 | 0x00080000;
            return cp;
        }
    }

    private void ConfigureCaptureExclusion()
    {
        if (_configuredHandle == Handle) return;
        // Check outside a window-message callback so failures propagate to
        // the manager and roll back the entire activation.
        if (!SetLayeredWindowAttributes(Handle, 0, 255, 2) ||
            !SetWindowDisplayAffinity(Handle, 0x11))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot exclude the privacy screen from capture.");
        _configuredHandle = Handle;
    }

    internal void ShowCovered(Rectangle bounds)
    {
        ConfigureCaptureExclusion(); // create/configure the HWND while still hidden
        Show();
        Cover(bounds);
    }

    internal void Cover(Rectangle bounds)
    {
        ConfigureCaptureExclusion(); // also handles any recreated HWND
        if (!SetWindowPos(Handle, new IntPtr(-1), bounds.X, bounds.Y, bounds.Width, bounds.Height,
                0x0010 | 0x0040)) // NOACTIVATE | SHOWWINDOW, physical monitor pixels
            throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing) _captionFont.Dispose();
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetLayeredWindowAttributes(IntPtr hwnd, uint color, byte alpha, uint flags);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowDisplayAffinity(IntPtr hwnd, uint affinity);
    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int width, int height, uint flags);
}
