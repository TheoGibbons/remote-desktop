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

    /// <summary>Passed by the copy that just installed an update, so this one says so.</summary>
    internal const string UpdatedSwitch = "--updated";

    public static bool JustUpdated { get; private set; }

    protected override void OnStartup(StartupEventArgs e)
    {
#if DEBUG
        // A test harness that loads this assembly for its styles creates an
        // App, and WPF then starts it the first time the harness pumps
        // messages. Left alone that is the whole app, running as the installed
        // one: it takes its lock, opens the main window, joins the session
        // with this PC's identity and rewrites the startup entry.
        if (System.Reflection.Assembly.GetEntryAssembly() != typeof(App).Assembly)
        {
            base.OnStartup(e);
            return;
        }
#endif
        if (PrivacyCursorGuard.TryRun(e.Args))
        {
            Shutdown();
            return;
        }

        var timeout = e.Args.Contains(RestartSwitch, StringComparer.OrdinalIgnoreCase)
            ? TimeSpan.FromSeconds(10)
            : TimeSpan.Zero;
        _instanceGuard = SingleInstanceGuard.TryAcquire(timeout, AppProfile.InstanceLock(AppProfile.Name));
        if (_instanceGuard == null)
        {
#if DEBUG
            // A debug build is started by scripts as often as by hand, and a
            // box would land on the screen of whoever is using the installed
            // app. Say it where a script can read it.
            Console.Error.WriteLine("Remote Desktop is already running: another copy holds " +
                AppProfile.InstanceLock(AppProfile.Name) + ".");
#else
            MessageBox.Show(
                "Remote Desktop is already running. Open it from the system tray, or exit the existing instance before starting it again.",
                "Remote Desktop is already running",
                MessageBoxButton.OK,
                MessageBoxImage.Error);
#endif
            Shutdown(1);
            return;
        }

        StartMinimized = e.Args.Any(a =>
            a.Equals(MinimizedSwitch, StringComparison.OrdinalIgnoreCase) ||
            a.Equals("/minimized", StringComparison.OrdinalIgnoreCase));
        JustUpdated = e.Args.Contains(UpdatedSwitch, StringComparer.OrdinalIgnoreCase);
        // The copy this one replaced, once it has exited.
        Updater.CleanUp(Environment.ProcessPath!);

        // Catch any drift from the shared cross-language crypto vectors.
        System.Diagnostics.Debug.Assert(Crypto.SelfTest(), "Crypto self-test failed");
        base.OnStartup(e);

        // The main window is made here, not by a StartupUri in App.xaml: WPF
        // opens a StartupUri even after one of the returns above has asked to
        // shut down, and building the main window rewrites the startup entry
        // to point at this copy and begins to connect.
        new MainWindow().Show();
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
