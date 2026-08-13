using System.Windows;

namespace RemoteDesktopWin;

public partial class App : Application
{
    /// <summary>True when launched by the Windows startup entry, which passes
    /// --minimized so the app comes up in the tray instead of on screen.</summary>
    public static bool StartMinimized { get; private set; }

    /// <summary>Command-line switch the startup registration appends.</summary>
    public const string MinimizedSwitch = "--minimized";

    protected override void OnStartup(StartupEventArgs e)
    {
        StartMinimized = e.Args.Any(a =>
            a.Equals(MinimizedSwitch, StringComparison.OrdinalIgnoreCase) ||
            a.Equals("/minimized", StringComparison.OrdinalIgnoreCase));

        // Catch any drift from the shared cross-language crypto vectors.
        System.Diagnostics.Debug.Assert(Crypto.SelfTest(), "Crypto self-test failed");
        base.OnStartup(e);
    }
}
