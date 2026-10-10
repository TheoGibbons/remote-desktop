using System.Windows;

namespace RemoteDesktopWin;

public partial class App : Application
{
    private SingleInstanceGuard? _instanceGuard;

    // The elevated replacement waits for the original app to finish cleanup.
    internal const string RestartSwitch = "--restart";

    /// <summary>True when launched by the Windows startup entry, which passes
    /// --minimized so the app comes up in the tray instead of on screen.</summary>
    public static bool StartMinimized { get; private set; }

    /// <summary>Command-line switch the startup registration appends.</summary>
    public const string MinimizedSwitch = "--minimized";

    protected override void OnStartup(StartupEventArgs e)
    {
        if (PrivacyCursorGuard.TryRun(e.Args))
        {
            Shutdown();
            return;
        }

        var timeout = e.Args.Contains(RestartSwitch, StringComparer.OrdinalIgnoreCase)
            ? TimeSpan.FromSeconds(10)
            : TimeSpan.Zero;
        _instanceGuard = SingleInstanceGuard.TryAcquire(timeout);
        if (_instanceGuard == null)
        {
            MessageBox.Show(
                "Remote Desktop is already running. Open it from the system tray, or exit the existing instance before starting it again.",
                "Remote Desktop is already running",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
            Shutdown(1);
            return;
        }

        StartMinimized = e.Args.Any(a =>
            a.Equals(MinimizedSwitch, StringComparison.OrdinalIgnoreCase) ||
            a.Equals("/minimized", StringComparison.OrdinalIgnoreCase));

        // Catch any drift from the shared cross-language crypto vectors.
        System.Diagnostics.Debug.Assert(Crypto.SelfTest(), "Crypto self-test failed");
        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        try { base.OnExit(e); }
        finally
        {
            _instanceGuard?.Dispose();
            _instanceGuard = null;
        }
    }
}
