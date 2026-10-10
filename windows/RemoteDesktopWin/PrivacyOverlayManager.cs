using System.Windows.Threading;
using Microsoft.Win32;
using Forms = System.Windows.Forms;

namespace RemoteDesktopWin;

internal sealed class PrivacyOverlayManager : IDisposable
{
    private readonly Dispatcher _dispatcher;
    private readonly List<PrivacyOverlayWindow> _windows = new();
    private readonly DispatcherTimer _refresh;
    private PrivacyInputBlocker? _blocker;
    internal string? OwnerPeerId { get; private set; }
    internal bool IsEnabled => OwnerPeerId != null;
    internal event Action<string?>? Changed;

    internal PrivacyOverlayManager(Dispatcher dispatcher)
    {
        _dispatcher = dispatcher;
        _refresh = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background,
            (_, _) => Refresh(), dispatcher);
        _refresh.Stop();
        SystemEvents.DisplaySettingsChanged += DisplayChanged;
        SystemEvents.SessionSwitch += SessionChanged;
    }

    internal void Enable(string peerId)
    {
        _dispatcher.VerifyAccess();
        if (IsEnabled) return;
        if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 19041))
            throw new NotSupportedException("Privacy mode requires Windows 10 version 2004 or newer.");
        try
        {
            CreateOverlays();
            _blocker = new PrivacyInputBlocker(() => _dispatcher.BeginInvoke(() => Disable()));
            OwnerPeerId = peerId;
            _refresh.Start();
        }
        catch { Disable(); throw; }
        Changed?.Invoke(null);
    }

    internal void Disable(string? error = null)
    {
        _dispatcher.VerifyAccess();
        bool wasEnabled = IsEnabled;
        OwnerPeerId = null;
        _refresh.Stop();
        _blocker?.Dispose();
        _blocker = null;
        foreach (var window in _windows) window.Dispose();
        _windows.Clear();
        if (wasEnabled) Changed?.Invoke(error);
    }

    private void CreateOverlays()
    {
        foreach (var monitor in Forms.Screen.AllScreens)
        {
            var window = new PrivacyOverlayWindow(monitor.Bounds);
            _windows.Add(window); // includes partially created windows if Show fails
            window.ShowCovered(monitor.Bounds);
        }
        if (_windows.Count == 0) throw new InvalidOperationException("No monitors are available.");
    }

    private void Refresh()
    {
        if (!IsEnabled) return;
        try
        {
            var screens = Forms.Screen.AllScreens;
            if (screens.Length != _windows.Count)
            {
                // Recreate on hot-plug; retain hooks and session ownership.
                foreach (var window in _windows) window.Dispose();
                _windows.Clear();
                CreateOverlays();
            }
            else for (int i = 0; i < screens.Length; i++) _windows[i].Cover(screens[i].Bounds);
        }
        catch (Exception ex) { Disable("Privacy screen closed: " + ex.Message); }
    }

    private void DisplayChanged(object? sender, EventArgs e) => _dispatcher.BeginInvoke(Refresh);
    private void SessionChanged(object sender, SessionSwitchEventArgs e)
    {
        if (e.Reason is SessionSwitchReason.SessionLock or SessionSwitchReason.SessionLogoff
            or SessionSwitchReason.ConsoleDisconnect or SessionSwitchReason.RemoteDisconnect)
            _dispatcher.BeginInvoke(() => Disable());
    }

    public void Dispose()
    {
        SystemEvents.DisplaySettingsChanged -= DisplayChanged;
        SystemEvents.SessionSwitch -= SessionChanged;
        Disable();
    }
}
