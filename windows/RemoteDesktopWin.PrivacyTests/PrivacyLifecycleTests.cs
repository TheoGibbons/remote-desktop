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
        var host = new MainWindow(new AppSettings { AutoConnect = false }, auth, trayIcon: false);
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
            // Three of them do throw where the socket client handles them,
            // shown by leaving out the guard it has round that. If one stops
            // doing so, it proves nothing below and another has to be found.
            foreach (var bad in new object[] { "{", "{\"type\":\"enc\",\"d\":\"not base64!\"}", Sealed("not json", "unapproved") })
                if (!ThrowsWhereItIsHandled(bad, binary: false))
                    throw new Exception("A message meant to make the socket client throw no longer does");
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
            // The same for whatever goes wrong further on, in a window that
            // is listening, with a message or with a picture.
            int choked = 0;
            Action<JsonNode> chokeOnMessage = _ => { Interlocked.Increment(ref choked); throw new InvalidOperationException("A window choked on a message"); };
            Action<byte[]> chokeOnFrame = _ => { Interlocked.Increment(ref choked); throw new InvalidOperationException("A window choked on a frame"); };
            ws.JsonReceived += chokeOnMessage;
            ws.BinaryReceived += chokeOnFrame;
            try
            {
                OffSocket(Sealed("{\"type\":\"something-new\"}", "unapproved"), binary: false);
                OffSocket(Frame(1, new byte[4]), binary: true);
            }
            finally
            {
                ws.JsonReceived -= chokeOnMessage;
                ws.BinaryReceived -= chokeOnFrame;
            }
            if (choked != 2) throw new Exception("The message and the frame did not reach the listener that was to choke on them");
            // None of all that was answered, and the next good one is still heard.
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

            // A phone's pictures, all the way: off the socket on one thread,
            // through the feed, decoded on its thread, shown by the window.
            // The window is never put on screen. What it sends is kept in a
            // queue of the socket client's own kind, to be read back.
            const string phone = "phone-7";
            trusted.Add(phone);
            var outbox = Activator.CreateInstance(typeof(Queue<>).MakeGenericType(
                typeof(WsClient).GetNestedType("OutFrame", BindingFlags.NonPublic)!))!;
            typeof(WsClient).GetField("_reliable", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(ws, outbox);
            List<JsonObject> Outgoing()
            {
                var list = new List<JsonObject>();
                lock (Field<object>(ws, "_outLock"))
                {
                    foreach (var frame in (System.Collections.IEnumerable)outbox)
                    {
                        var envelope = JsonNode.Parse((byte[])frame.GetType().GetField("Data")!.GetValue(frame)!)!;
                        var inner = Crypto.Decrypt(Field<byte[]>(ws, "_encKey"), Convert.FromBase64String(envelope["d"]!.GetValue<string>()), Crypto.ChJson)!;
                        list.Add(JsonNode.Parse(inner)!.AsObject());
                    }
                    outbox.GetType().GetMethod("Clear")!.Invoke(outbox, null);
                }
                return list;
            }
            var view = new PhoneViewWindow(ws, phone, "Test phone", Field<PhoneFeed>(host, "_phoneFeed"), () => auth.IsTrusted(phone));
            typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, view);
            try
            {
                var screen = (System.Windows.Controls.Image)view.FindName("ScreenImage");
                var asked = Outgoing();
                if (asked.Count == 0 || asked[0].ToJsonString() != "{\"type\":\"start-view\",\"to\":\"phone-7\",\"codecs\":[\"jpeg\"]}")
                    throw new Exception("The phone window did not offer codecs to an approved phone");
                byte[] jpeg;
                using (var bitmap = new System.Drawing.Bitmap(8, 16))
                using (var bytes = new MemoryStream())
                {
                    bitmap.Save(bytes, System.Drawing.Imaging.ImageFormat.Jpeg);
                    jpeg = bytes.ToArray();
                }
                byte[] Type5(uint seq, uint captureMs) => Frame(Crypto.ChPhone, PhoneFrame.Build(
                    new PhoneFrameHeader(0xaa, seq, true, PhoneFrame.Jpeg, false, false, 8, 16, captureMs), jpeg));
                void Until(Func<bool> done, string what)
                {
                    for (int i = 0; i < 100 && !done(); i++) Pump(20);
                    if (!done()) throw new Exception(what);
                }

                // The stream is named, and its first frame is right behind.
                OffSocket(Sealed("{\"type\":\"screen-info\",\"width\":8,\"height\":16,\"typing\":true," +
                    "\"stream\":\"00000000000000aa\",\"codec\":\"jpeg\",\"capture\":\"accessibility\"}", phone), binary: false);
                OffSocket(Type5(0, 0), binary: true);
                Until(() => screen.Source != null, "A type 5 frame that followed its announcement was not shown");
                if (screen.Source is not System.Windows.Media.Imaging.BitmapSource { PixelWidth: 8, PixelHeight: 16 })
                    throw new Exception("The picture shown is not the one that was sent");
                var acks = Outgoing();
                if (acks.Count != 1 || acks[0].ToJsonString() != "{\"type\":\"frame-ack\",\"to\":\"phone-7\",\"stream\":\"00000000000000aa\",\"seq\":0}")
                    throw new Exception("The frame was not acknowledged");
                // A bare JPEG, which names no sender, is not drawn beside a stream.
                var shown = screen.Source;
                OffSocket(Frame(Crypto.ChVideo, jpeg), binary: true);
                Pump(200);
                if (!ReferenceEquals(shown, screen.Source)) throw new Exception("A type 1 frame was drawn while a stream was announced");
                Console.WriteLine("PASS: a phone's type 5 frame goes from the socket to its window, and is acknowledged");

                // The readout: opening it pings the phone, and the answer and
                // the next picture give a delay.
                var readout = (System.Windows.Controls.TextBlock)view.FindName("StatsText");
                view.ToggleStats();
                JsonObject? ping = null;
                Until(() => (ping ??= Outgoing().FirstOrDefault(m => m["type"]?.GetValue<string>() == "diagnostic-ping")) != null,
                    "Opening the readout did not ping the phone");
                // The phone's clock reads a million, and its next picture was captured 40 ms before that.
                OffSocket(Sealed("{\"type\":\"diagnostic-pong\",\"nonce\":" + ping!["nonce"]!.GetValue<long>() +
                    ",\"hostTimeMs\":1000000,\"hostQueueBytes\":2048,\"codec\":\"jpeg\",\"capture\":\"accessibility\",\"fps\":2}", phone), binary: false);
                OffSocket(Type5(1, 999_960), binary: true);
                Until(() => !ReferenceEquals(shown, screen.Source), "The second frame was not shown");
                Until(() => System.Text.RegularExpressions.Regex.IsMatch(readout.Text, @"Delay \d+ ms · average \d+ · worst \d+ \(± \d+ ms\)"),
                    "The readout shows no delay: " + readout.Text);
                if (!readout.Text.StartsWith("Accessibility viewing · JPEG · 8 × 16") || !readout.Text.Contains("phone's queue 2.0 KB") ||
                    !readout.Text.EndsWith("Frames lost: 0"))
                    throw new Exception("The readout is not what was measured: " + readout.Text);
                string? copied = null;
                if (view.CopyStats(text => copied = text) != "Copied the last minute of figures." ||
                    copied == null || !copied.StartsWith("Remote Desktop phone viewer, the last") || !copied.Contains("Accessibility viewing · JPEG · 8 × 16"))
                    throw new Exception("Copying the figures gave: " + copied);
                view.ToggleStats();
                Console.WriteLine("PASS: the readout pings the phone and shows the delay of the pictures that are drawn");

                // Every start-view the window sends says what it can draw, the
                // later ones too: the phone takes the latest as the whole of it.
                List<string> StartViews() => Outgoing().Where(m => m["type"]?.GetValue<string>() == "start-view")
                    .Select(m => m.ToJsonString()).ToList();
                const string offer = "{\"type\":\"start-view\",\"to\":\"phone-7\",\"codecs\":[\"jpeg\"]}";
                const string plain = "{\"type\":\"start-view\",\"to\":\"phone-7\"}";
                Outgoing();
                view.ResendStartView();
                if (!StartViews().SequenceEqual(new[] { offer }))
                    throw new Exception("Asking for the stream again took back the offer of type 5 frames");
                // Nothing about the phone's approval has changed, so it is not asked again.
                view.TrustChanged();
                if (StartViews().Count != 0) throw new Exception("The phone was asked again though its approval had not changed");
                // This PC stops approving the phone: it is asked again, as a phone that is not approved is.
                trusted.Remove(phone);
                view.TrustChanged();
                if (!StartViews().SequenceEqual(new[] { plain }))
                    throw new Exception("A phone that is no longer approved was not asked again without the offer");
                view.ResendStartView();
                if (!StartViews().SequenceEqual(new[] { plain }))
                    throw new Exception("A phone that is not approved was offered type 5 frames when asked again");
                trusted.Add(phone);
                view.TrustChanged();
                if (!StartViews().SequenceEqual(new[] { offer }))
                    throw new Exception("A phone that is approved again was not offered type 5 frames");
                Console.WriteLine("PASS: each start-view from the phone window says what it can draw, and a change of approval asks again");

                // Smooth video: the row is there only for a phone that says it
                // can be asked, and only an approved phone is believed. What
                // the person would be told is taken here, not put on the screen.
                var told = new List<string>();
                Toast.Capture = told.Add;
                try
                {
                    var row = (System.Windows.Controls.Button)view.FindName("SmoothButton");
                    var tick = (System.Windows.Controls.TextBlock)view.FindName("SmoothTick");
                    string Info(string smooth) => "{\"type\":\"screen-info\",\"width\":8,\"height\":16,\"typing\":true," +
                        "\"capture\":\"accessibility\",\"smooth\":\"" + smooth + "\"}";
                    string Status(string rest) => "{\"type\":\"smooth-status\"," + rest + "}";
                    void Click() => row.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
                    List<string> Asked() => Outgoing().Select(m => m.ToJsonString()).Where(m => m.Contains("smooth")).ToList();

                    if (row.Visibility == Visibility.Visible) throw new Exception("Smooth video was offered before the phone said it could be asked");
                    trusted.Remove(phone);
                    OffSocket(Sealed(Info("available"), phone), binary: false);
                    if (row.Visibility == Visibility.Visible) throw new Exception("A phone this PC has not approved put a row in the menu");
                    trusted.Add(phone);
                    OffSocket(Sealed(Info("available"), phone), binary: false);
                    if (row.Visibility != Visibility.Visible || !row.IsEnabled || tick.Visibility == Visibility.Visible)
                        throw new Exception("Smooth video was not offered, unticked, for a phone that says it is available");

                    Outgoing();
                    Click();
                    if (!Asked().SequenceEqual(new[] { "{\"type\":\"request-smooth\",\"to\":\"phone-7\"}" }))
                        throw new Exception("Choosing Smooth video did not ask the phone to start");
                    // Another device's word about it is not the phone's.
                    OffSocket(Sealed(Status("\"state\":\"started\""), "unapproved"), binary: false);
                    if (told.Count != 0 || tick.Visibility == Visibility.Visible) throw new Exception("A smooth-status from another device was believed");
                    OffSocket(Sealed(Status("\"state\":\"prompting\""), phone), binary: false);
                    if (!told.SequenceEqual(new[] { SmoothVideo.PromptText })) throw new Exception("The person was not told the phone is asking");
                    OffSocket(Sealed(Status("\"state\":\"started\""), phone), binary: false);
                    if (tick.Visibility != Visibility.Visible || told[^1] != "Smooth video is on.")
                        throw new Exception("Smooth video was not ticked when the phone said it started");

                    Click();
                    if (!Asked().SequenceEqual(new[] { "{\"type\":\"stop-smooth\",\"to\":\"phone-7\"}" }))
                        throw new Exception("Choosing the ticked row did not ask the phone to stop");
                    OffSocket(Sealed(Status("\"state\":\"ended\",\"reason\":\"Smooth video was stopped from Test PC.\""), phone), binary: false);
                    if (tick.Visibility == Visibility.Visible || told[^1] != "Smooth video was stopped from Test PC.")
                        throw new Exception("The end of smooth video, and why, was not shown");

                    OffSocket(Sealed(Info("unavailable"), phone), binary: false);
                    if (row.Visibility != Visibility.Visible || row.IsEnabled) throw new Exception("An unavailable Smooth video row was not greyed");
                    Click();
                    if (Asked().Count != 0) throw new Exception("A greyed row asked the phone anyway");
                    Console.WriteLine("PASS: the Smooth video row follows what an approved phone says, asks it to start and stop, and says what came of it");
                }
                finally
                {
                    Toast.Capture = null;
                }
            }
            finally
            {
                view.Close();
                typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, null);
            }
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

        // Whether the socket client throws on this message where it handles
        // it, with the guard it has round that left out.
        bool ThrowsWhereItIsHandled(object message, bool binary)
        {
            var data = message as byte[] ?? System.Text.Encoding.UTF8.GetBytes((string)message);
            var handle = typeof(WsClient).GetMethod("Handle", BindingFlags.Instance | BindingFlags.NonPublic)!;
            var done = Task.Run(() => handle.Invoke(ws, new object[] { data, binary }));
            while (!done.IsCompleted) Pump(5);
            Pump(20);
            return done.IsFaulted;
        }

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
