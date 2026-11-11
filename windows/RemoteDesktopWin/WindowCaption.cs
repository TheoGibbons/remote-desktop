using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Shell;
using System.Windows.Threading;

namespace RemoteDesktopWin;

/// <summary>
/// Gives a window a dark title bar it can put its own controls in, while
/// Windows goes on drawing and handling minimise, maximise and close (and with
/// them the snap-layouts flyout).
///
/// The window lays out a bar <see cref="BarHeight"/> tall across its top, with
/// no background so the system's frame shows through, and keeps
/// <see cref="ButtonsWidth"/> at the right of it clear for the system's
/// buttons. A control in the bar that should take clicks needs
/// <c>WindowChrome.IsHitTestVisibleInChrome="True"</c>; the rest of the bar
/// drags the window. The window's own Background must be Transparent, and
/// whatever sits below the bar paints itself.
///
/// The frame on the other three sides stays the system's, so the window is
/// resized from just outside its edge like any other and none of its content
/// is given up to a resize border.
/// </summary>
internal sealed class WindowCaption
{
    private readonly Window _window;
    private readonly WindowChrome _chrome = new()
    {
        UseAeroCaptionButtons = true,
        CornerRadius = default,
        NonClientFrameEdges = NonClientFrameEdges.None,
    };
    private IntPtr _hwnd;

    /// <summary>Height of the title bar, in WPF units. Windows decides it.</summary>
    public double BarHeight { get; private set; } = 30;

    /// <summary>Width at the right of the bar that belongs to minimise,
    /// maximise and close.</summary>
    public double ButtonsWidth { get; private set; } = 138;

    /// <summary>The bar's height or the buttons' width changed.</summary>
    public event Action? Changed;

    public WindowCaption(Window window)
    {
        _window = window;
        Apply(resize: 6, captionBottom: BarHeight, bar: BarHeight);
        WindowChrome.SetWindowChrome(window, _chrome);
        window.SourceInitialized += (_, _) =>
        {
            _hwnd = new WindowInteropHelper(window).Handle;
            // Added after WindowChrome's own hook: the last hook added is asked first.
            HwndSource.FromHwnd(_hwnd)?.AddHook(Hook);
            int on = 1;
            // Dark caption with white glyphs. Attribute 19 is the same thing
            // on Windows 10 before version 2004.
            if (DwmSetWindowAttribute(_hwnd, 20, ref on, sizeof(int)) != 0)
                DwmSetWindowAttribute(_hwnd, 19, ref on, sizeof(int));
            // Caption colour, Windows 11 only. Without it Windows 10 draws its own dark bar.
            int colour = 0x001E1E1E;
            DwmSetWindowAttribute(_hwnd, 35, ref colour, sizeof(int));
            // Have the frame worked out again, by the hook this time.
            SetWindowPos(_hwnd, IntPtr.Zero, 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0004 | 0x0010 | 0x0020);
            Refresh();
        };
        // The buttons are a different size maximised, and on a monitor with
        // different scaling. Their bounds are not known until the window is shown.
        window.StateChanged += (_, _) => Refresh();
        // WPF says the scaling changed before it gives the window its new
        // size, so the buttons are measured again once that has happened.
        window.DpiChanged += (_, _) =>
        {
            Refresh();
            window.Dispatcher.BeginInvoke(DispatcherPriority.Loaded, Refresh);
        };
        window.Loaded += (_, _) => Refresh();
        window.ContentRendered += (_, _) => Refresh();
    }

    /// <summary>The work area of the monitor the window is on, in WPF units.</summary>
    public Rect WorkArea
    {
        get
        {
            var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            if (_hwnd == IntPtr.Zero || !GetMonitorInfo(MonitorFromWindow(_hwnd, 2), ref info))
                return SystemParameters.WorkArea;
            double scale = GetDpiForWindow(_hwnd) / 96.0;
            var r = info.rcWork;
            return new Rect(r.Left / scale, r.Top / scale, (r.Right - r.Left) / scale, (r.Bottom - r.Top) / scale);
        }
    }

    /// <summary>
    /// The frame Windows would give the window, with the title bar's share
    /// handed to the client area. Maximised, the frame hangs past the screen's
    /// edges, so the client area starts a frame's thickness down, at the
    /// screen's edge.
    /// </summary>
    private IntPtr Hook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        const int WM_NCCALCSIZE = 0x0083;
        if (msg != WM_NCCALCSIZE) return IntPtr.Zero;
        // lParam points at a RECT either way (the first field of NCCALCSIZE_PARAMS).
        int top = Marshal.ReadInt32(lParam, 4);
        DefWindowProc(hwnd, msg, wParam, lParam);
        if (IsZoomed(hwnd)) top += FrameThickness(hwnd);
        Marshal.WriteInt32(lParam, 4, top);
        handled = true;
        return IntPtr.Zero;
    }

    private static int FrameThickness(IntPtr hwnd)
    {
        uint dpi = GetDpiForWindow(hwnd);
        return GetSystemMetricsForDpi(32 /* SM_CXSIZEFRAME */, dpi) + GetSystemMetricsForDpi(92 /* SM_CXPADDEDBORDER */, dpi);
    }

    private void Refresh()
    {
        if (_hwnd == IntPtr.Zero) return;
        double scale = GetDpiForWindow(_hwnd) / 96.0;
        int frame = FrameThickness(_hwnd);
        bool maximized = IsZoomed(_hwnd);

        // Where the client area sits in the window: the buttons' bounds come
        // relative to the window, and the bar is laid out in the client area.
        var origin = new POINT();
        if (!GetWindowRect(_hwnd, out var window) || !GetClientRect(_hwnd, out var client) ||
            !ClientToScreen(_hwnd, ref origin)) return;
        int clientTop = origin.Y - window.Top;
        int clientRight = origin.X - window.Left + client.Right;

        double bar = BarHeight, buttons = ButtonsWidth, captionBottom;
        if (DwmGetWindowAttribute(_hwnd, 5, out RECT b, Marshal.SizeOf<RECT>()) == 0 &&
            b.Right > b.Left && b.Bottom > clientTop && clientRight > b.Left)
        {
            bar = (b.Bottom - clientTop) / scale;
            buttons = (clientRight - b.Left) / scale;
            captionBottom = b.Bottom / scale;
        }
        else
        {
            captionBottom = clientTop / scale + bar;
        }

        // A maximised window can't be resized, and the top row of the screen
        // must drag it.
        Apply(maximized ? 0 : frame / scale, captionBottom, bar);
        if (Math.Abs(bar - BarHeight) < 0.01 && Math.Abs(buttons - ButtonsWidth) < 0.01) return;
        BarHeight = bar;
        ButtonsWidth = buttons;
        Changed?.Invoke();
    }

    /// <param name="resize">Thickness of the system's resize frame.</param>
    /// <param name="captionBottom">The bar's bottom edge, measured from the top
    /// of the window rather than of the client area.</param>
    /// <param name="bar">The bar's height.</param>
    private void Apply(double resize, double captionBottom, double bar)
    {
        // WindowChrome answers every hit test itself, measuring from the
        // window's edges: the outer ResizeBorderThickness resizes, and the
        // CaptionHeight below the top resize border drags. Sized like this the
        // resize borders fall on the system's frame at the sides and bottom,
        // and on the top few pixels of the bar.
        var border = new Thickness(resize);
        double caption = Math.Max(0, captionBottom - resize);
        // The system frame is extended down over the bar, which is where the
        // buttons and the bar's colour come from.
        var glass = new Thickness(0, bar, 0, 0);
        if (_chrome.ResizeBorderThickness != border) _chrome.ResizeBorderThickness = border;
        if (Math.Abs(_chrome.CaptionHeight - caption) > 0.01) _chrome.CaptionHeight = caption;
        if (_chrome.GlassFrameThickness != glass) _chrome.GlassFrameThickness = glass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT { public int Left, Top, Right, Bottom; }

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT { public int X, Y; }

    [StructLayout(LayoutKind.Sequential)]
    private struct MONITORINFO { public int cbSize; public RECT rcMonitor, rcWork; public uint dwFlags; }

    [DllImport("dwmapi.dll")] private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    [DllImport("dwmapi.dll")] private static extern int DwmGetWindowAttribute(IntPtr hwnd, int attribute, out RECT value, int size);
    [DllImport("user32.dll")] private static extern IntPtr DefWindowProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam);
    [DllImport("user32.dll")] private static extern bool IsZoomed(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern int GetSystemMetricsForDpi(int index, uint dpi);
    [DllImport("user32.dll")] private static extern bool GetWindowRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool GetClientRect(IntPtr hwnd, out RECT rect);
    [DllImport("user32.dll")] private static extern bool ClientToScreen(IntPtr hwnd, ref POINT point);
    [DllImport("user32.dll")] private static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] private static extern bool GetMonitorInfo(IntPtr monitor, ref MONITORINFO info);
}
