using System.IO;
using System.Reflection;
using System.Text.Json.Nodes;
using System.Windows;
using System.Windows.Markup;
using System.Xml.Linq;
using Microsoft.Win32;
using RemoteDesktopWin;

internal static class PrivacyLifecycleTests
{
    internal static void Run()
    {
        // Exercise real host event handlers without connecting to a relay,
        // altering saved settings, covering monitors, or installing input hooks.
        // Use the host's styles without App.OnStartup or its StartupUri. Pumping
        // the dispatcher must not start a second host or use saved settings.
        var app = new System.Windows.Application { ShutdownMode = ShutdownMode.OnExplicitShutdown };
        XNamespace presentation = "http://schemas.microsoft.com/winfx/2006/xaml/presentation";
        var markup = XDocument.Load(Path.Combine(AppContext.BaseDirectory, "AppResources.xaml"));
        var resources = new XElement(presentation + "ResourceDictionary",
            new XAttribute(XNamespace.Xmlns + "x", "http://schemas.microsoft.com/winfx/2006/xaml"),
            markup.Root!.Element(presentation + "Application.Resources")!.Elements());
        app.Resources = (ResourceDictionary)XamlReader.Parse(resources.ToString());
        string storage = Path.Combine(Path.GetTempPath(), "privacy-lifecycle-" + Guid.NewGuid().ToString("N"));
        var auth = new PeerAuth(storage);
        var host = new MainWindow(new AppSettings { AutoConnect = false }, auth);
        var ws = Field<WsClient>(host, "_ws");
        // Allow privacy acknowledgements to encrypt normally; no socket or
        // outgoing queue is opened, so the messages stay on this machine.
        typeof(WsClient).GetField("_encKey", BindingFlags.Instance | BindingFlags.NonPublic)!
            .SetValue(ws, Crypto.DeriveKeys("privacy-lifecycle-test-key").encKey);
        var privacy = Field<PrivacyOverlayManager>(host, "_privacy");
        var streamer = Field<ScreenStreamer>(host, "_streamer");
        var trusted = Field<HashSet<string>>(auth, "_trustedPeers");
        const string peer = "viewer";
        const string reconnected = "reconnected-viewer";
        try
        {
            // Seed only enabled state: Disable still executes real cleanup if a
            // session-ending path accidentally restores the physical desktop.
            typeof(PrivacyOverlayManager).GetProperty("IsEnabled", BindingFlags.Instance | BindingFlags.NonPublic)!
                .SetValue(privacy, true);
            trusted.Add(peer);
            streamer.Regions.AddViewer(peer);

            Json("stop-view", peer);
            ExpectPrivate("viewer closes");
            if (streamer.Regions.IsViewing(peer)) throw new Exception("Closed viewer retained its viewport");
            streamer.Stop();
            ExpectPrivate("stream stops");
            Invoke(host, "DisconnectRow", new MainWindow.DeviceRow { PeerId = peer });
            ExpectPrivate("host disconnects a device");
            Invoke(host, "OnAuthResult", peer, "disconnected");
            ExpectPrivate("peer ends the session");
            Json("peer-left", id: peer);
            ExpectPrivate("privacy-enabling peer leaves and authentication changes");
            Invoke(host, "OnState", "disconnected: network lost");
            ExpectPrivate("relay drops and authentication resets");
            Invoke(host, "OnState", "connecting");
            ExpectPrivate("host reconnects");
            auth.OnPeerJoined(reconnected, "Phone", "android");
            ExpectPrivate("reconnecting peer awaits authentication");
            Invoke(host, "OnAuthResult", reconnected, "revoked");
            ExpectPrivate("approval is revoked");
            Invoke(privacy, "SessionChanged", host, new SessionSwitchEventArgs(SessionSwitchReason.RemoteDisconnect));
            Invoke(privacy, "SessionChanged", host, new SessionSwitchEventArgs(SessionSwitchReason.ConsoleDisconnect));
            // Drain queued session cleanup callbacks before checking privacy.
            host.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            ExpectPrivate("Windows desktop session disconnects");

            Json("privacy-mode", reconnected, enabled: false);
            ExpectPrivate("unapproved peer tries to turn privacy off");
            trusted.Add(reconnected);
            Json("privacy-mode", reconnected, enabled: false);
            if (privacy.IsEnabled) throw new Exception("Approved reconnected peer could not restore the desktop");
            Console.WriteLine("PASS: privacy survives session endings and an approved peer can disable it after reconnect");

            // Never capture the real desktop in these lifecycle tests.
            streamer.Gate = () => false;
            foreach (var end in new Action[] {
                () => Json("stop-view", peer),
                () => Json("peer-left", id: peer),
                () => Invoke(host, "OnState", "disconnected: network lost"),
                () => Invoke(host, "DisconnectRow", new MainWindow.DeviceRow { PeerId = peer }),
                () => Invoke(host, "OnAuthResult", peer, "disconnected"),
                () => Invoke(host, "OnAuthResult", peer, "revoked"),
            })
            {
                trusted.Add(peer);
                Json("start-view", peer, autoUnprivacy: true);
                SeedPrivacy();
                end();
                if (privacy.IsEnabled) throw new Exception("Auto un-privacy did not restore desktop on session ending");
                streamer.Stop();
            }

            trusted.Add(peer);
            Json("start-view", peer, autoUnprivacy: true);
            SeedPrivacy();
            Json("stop-view", "unapproved");
            ExpectPrivate("unapproved peer sends stop-view");
            // The latest start-view policy replaces any previous opt-in.
            Json("start-view", peer, autoUnprivacy: false);
            Json("stop-view", peer);
            ExpectPrivate("auto un-privacy is turned off");
            Json("start-view", "unapproved", autoUnprivacy: true);
            Invoke(host, "OnState", "disconnected: network lost");
            ExpectPrivate("unapproved peer tries to register auto un-privacy");
            Console.WriteLine("PASS: opted-in viewers restore privacy on close, disconnect, revocation and relay loss");
        }
        finally
        {
            streamer.Stop();
            privacy.Dispose();
            Field<WsClient>(host, "_ws").Dispose();
            Field<System.Windows.Forms.NotifyIcon>(host, "_tray").Dispose();
            typeof(MainWindow).GetField("_exiting", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, true);
            host.Close();
            foreach (Window window in app.Windows.Cast<Window>().ToArray()) window.Close();
            Directory.Delete(storage, recursive: true);
        }

        void ExpectPrivate(string action)
        {
            if (!privacy.IsEnabled) throw new Exception("Privacy cleared when " + action);
        }

        void SeedPrivacy() => typeof(PrivacyOverlayManager)
            .GetProperty("IsEnabled", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(privacy, true);

        void Json(string type, string? from = null, string? id = null, bool? enabled = null, bool? autoUnprivacy = null) =>
            Invoke(host, "OnJson", new JsonObject { ["type"] = type, ["from"] = from, ["id"] = id,
                ["enabled"] = enabled, ["autoUnprivacy"] = autoUnprivacy });
    }

    private static T Field<T>(object instance, string name) =>
        (T)instance.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(instance)!;

    private static void Invoke(object instance, string name, params object?[] args) =>
        instance.GetType().GetMethod(name, BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(instance, args);
}
