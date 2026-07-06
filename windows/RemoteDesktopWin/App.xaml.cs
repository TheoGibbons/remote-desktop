using System.Windows;

namespace RemoteDesktopWin;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // Catch any drift from the shared cross-language crypto vectors.
        System.Diagnostics.Debug.Assert(Crypto.SelfTest(), "Crypto self-test failed");
        base.OnStartup(e);
    }
}
