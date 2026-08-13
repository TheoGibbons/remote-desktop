using System.Reflection;

namespace RemoteDesktopWin;

internal static class AppDefaults
{
    private const string FallbackRelayServerUrl = "wss://relay.remote-desktop.co/ws";

    public static string RelayServerUrl
    {
        get
        {
            var configured = Assembly.GetExecutingAssembly()
                .GetCustomAttributes<AssemblyMetadataAttribute>()
                .FirstOrDefault(attribute => attribute.Key == "DefaultRelayServerUrl")
                ?.Value;

            return string.IsNullOrWhiteSpace(configured) ? FallbackRelayServerUrl : configured;
        }
    }
}
