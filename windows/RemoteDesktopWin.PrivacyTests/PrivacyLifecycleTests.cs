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
        // Use the host's styles without App.OnStartup, which opens the main
        // window. Pumping the dispatcher must not start a second host or use
        // saved settings.
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

            // Clipboard requests follow the input rules: no answer at all for
            // unapproved peers, and every request, even a plain copy to this
            // PC, needs the control switch. Only refusals run here; a real copy
            // would press keys on this desktop.
            var sent = new List<JsonObject>();
            typeof(MainWindow).GetField("_clip", BindingFlags.Instance | BindingFlags.NonPublic)!
                .SetValue(host, new ClipboardService(sent.Add,
                    p => host.AllowStreamCheck.IsChecked == true && auth.IsTrusted(p),
                    () => host.AllowFilesCheck.IsChecked == true));
            Invoke(host, "OnJson", new JsonObject { ["type"] = "clip-get", ["from"] = "unapproved", ["reqId"] = "1" });
            Invoke(host, "OnJson", new JsonObject { ["type"] = "clip-set", ["from"] = "unapproved", ["reqId"] = "2", ["text"] = "x" });
            if (sent.Count != 0) throw new Exception("An unapproved peer got a clipboard answer");
            // Keep the switch below out of the saved settings.
            typeof(MainWindow).GetField("_settingsInitialized", BindingFlags.Instance | BindingFlags.NonPublic)!
                .SetValue(host, false);
            host.AllowStreamCheck.IsChecked = false;
            trusted.Add(peer);
            Invoke(host, "OnJson", new JsonObject { ["type"] = "clip-get", ["from"] = peer, ["reqId"] = "3" });
            Invoke(host, "OnJson", new JsonObject
                { ["type"] = "clip-set", ["from"] = peer, ["reqId"] = "4", ["text"] = "x", ["paste"] = true });
            Invoke(host, "OnJson", new JsonObject
                { ["type"] = "clip-set", ["from"] = peer, ["reqId"] = "5", ["text"] = "x" });
            if (sent.Count != 3 ||
                sent[0]["type"]?.GetValue<string>() != "clip-get-result" || sent[0]["reqId"]?.GetValue<string>() != "3" ||
                sent[1]["type"]?.GetValue<string>() != "clip-set-result" || sent[1]["reqId"]?.GetValue<string>() != "4" ||
                sent[2]["type"]?.GetValue<string>() != "clip-set-result" || sent[2]["reqId"]?.GetValue<string>() != "5" ||
                sent.Any(m => m["error"]?.GetValue<string>() != ClipboardService.ControlOffError))
                throw new Exception("Clipboard requests went through with remote control turned off");
            // A malformed message is dropped, not rethrown into the socket loop.
            Invoke(host, "OnJson", new JsonObject { ["type"] = 5, ["from"] = peer });
            Console.WriteLine("PASS: clipboard requests need an approved peer and the control switch, and bad messages are dropped");

            // Messages as they come off the socket, on a thread that is not
            // the window's. None of these may get out as an exception: the
            // receive loop would end, and the PC would drop off the relay
            // each time any device in the session sent one.
            // The control switch stays off, so the clip-get at the end is refused and presses no keys.
            sent.Clear();
            foreach (var text in new[]
            {
                "null", "5", "[1]", "\"x\"", "{", "", "{\"type\":5}", "{\"type\":[\"welcome\"]}", "{\"type\":\"enc\"}",
                "{\"type\":\"enc\",\"d\":5}", "{\"type\":\"enc\",\"d\":\"not base64!\"}", "{\"type\":\"enc\",\"d\":\"AAAA\"}",
                "{\"type\":\"enc\",\"d\":\"AAAA\",\"from\":7}",
            })
                OffSocket(text, binary: false);
            // Encrypted with the session key, as an unapproved device in the session can, and still no message.
            foreach (var inner in new[] { "null", "[1]", "7", "not json", "{\"type\":5}", "{\"type\":{\"a\":1}}", "{\"type\":\"screen-info\",\"stream\":5}",
                         "{\"type\":\"fs-begin\",\"xferId\":\"x\"}", "{\"type\":\"peer-joined\"}", "{\"type\":\"screen-info\",\"width\":\"wide\"}" })
                OffSocket(Sealed(inner, "unapproved"), binary: false);
            foreach (var frame in new[] { Array.Empty<byte>(), new byte[] { 1 }, new byte[] { 2, 0, 0 }, new byte[] { 5, 1, 2, 3 }, new byte[40],
                         Frame(2, new byte[2]), Frame(3, new byte[3]), Frame(5, new byte[5]), Frame(1, new byte[4]) })
                OffSocket(frame, binary: true);
            // And the next good one is still heard.
            OffSocket(Sealed("{\"type\":\"clip-get\",\"reqId\":\"6\"}", peer), binary: false);
            if (sent.Count != 1 || sent[0]["reqId"]?.GetValue<string>() != "6")
                throw new Exception("A message that makes no sense stopped the ones after it");
            Console.WriteLine("PASS: a message that makes no sense is skipped on the socket's thread, and the next one is heard");

            // A device's message that is not encrypted is not heard, though
            // the relay names an approved device as its sender: without this
            // the session key would not be needed to be answered.
            sent.Clear();
            OffSocket("{\"type\":\"clip-get\",\"reqId\":\"7\",\"from\":\"" + peer + "\"}", binary: false);
            if (sent.Count != 0) throw new Exception("A message that was not encrypted was answered");
            // The sender is who the relay says, whatever the message itself claims.
            OffSocket(Sealed("{\"type\":\"clip-get\",\"reqId\":\"8\",\"from\":\"" + peer + "\"}", "unapproved"), binary: false);
            if (sent.Count != 0) throw new Exception("A device was taken for the one it said it was");
            // A device posing as the relay, encrypted or not, to have a viewer dropped.
            streamer.Regions.AddViewer(peer);
            OffSocket("{\"type\":\"peer-left\",\"id\":\"" + peer + "\",\"from\":\"unapproved\"}", binary: false);
            OffSocket(Sealed("{\"type\":\"peer-left\",\"id\":\"" + peer + "\"}", "unapproved"), binary: false);
            OffSocket(Sealed("{\"type\":\"welcome\",\"id\":\"9\",\"peers\":[]}", "unapproved"), binary: false);
            if (!streamer.Regions.IsViewing(peer)) throw new Exception("A device posing as the relay had a viewer dropped");
            // The relay's own word, which has no sender.
            OffSocket("{\"type\":\"peer-left\",\"id\":\"" + peer + "\"}", binary: false);
            if (streamer.Regions.IsViewing(peer)) throw new Exception("The relay's own peer-left was not heard");
            Console.WriteLine("PASS: only an encrypted message is a device's, and only the relay says who is in the session");
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

        // What the relay hands this PC: an envelope round a message encrypted with the session key.
        byte[] Sealed(string inner, string from) => System.Text.Encoding.UTF8.GetBytes(new JsonObject
        {
            ["type"] = "enc",
            ["from"] = from,
            ["d"] = Convert.ToBase64String(Crypto.Encrypt(Field<byte[]>(ws, "_encKey"), System.Text.Encoding.UTF8.GetBytes(inner), Crypto.ChJson)),
        }.ToJsonString());

        byte[] Frame(byte type, byte[] payload) => [type, .. Crypto.Encrypt(Field<byte[]>(ws, "_encKey"), payload, type)];

        // Hands a message to the socket client on another thread, as the
        // receive loop does, and lets the window deal with what it posts.
        void OffSocket(object message, bool binary)
        {
            var data = message as byte[] ?? System.Text.Encoding.UTF8.GetBytes((string)message);
            var done = Task.Run(() => ws.Dispatch(data, binary));
            while (!done.IsCompleted) Pump(5);
            if (done.IsFaulted) throw new Exception("A message got out of the socket client as an exception", done.Exception);
            Pump(20);
        }

        void Pump(int ms)
        {
            var frame = new System.Windows.Threading.DispatcherFrame();
            var timer = new System.Windows.Threading.DispatcherTimer(System.Windows.Threading.DispatcherPriority.Background)
                { Interval = TimeSpan.FromMilliseconds(ms) };
            timer.Tick += (_, _) => { timer.Stop(); frame.Continue = false; };
            timer.Start();
            System.Windows.Threading.Dispatcher.PushFrame(frame);
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
