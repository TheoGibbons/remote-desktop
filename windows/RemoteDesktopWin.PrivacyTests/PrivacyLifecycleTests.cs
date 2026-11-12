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
            // Whether this Windows decodes H.264 is found out on the feed's
            // thread as the app starts, and decides what a phone is offered.
            var feed = Field<PhoneFeed>(host, "_phoneFeed");
            for (int i = 0; i < 500 && typeof(PhoneFeed).GetField("_canDecode", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(feed) == null; i++)
                Pump(20);
            bool video = H264Decoder.IsAvailable;
            // No decoder is one thing; a decoder that is there and could not be set up is a fault.
            if (!video && !H264Decoder.IsNotInstalled)
                throw new Exception("Windows has an H.264 decoder and it could not be set up: " + H264Decoder.WhyNotAvailable);
            string codecs = video ? "[\"h264\",\"jpeg\"]" : "[\"jpeg\"]";
            var view = new PhoneViewWindow(ws, phone, "Test phone", feed, auth.IsTrusted);
            typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, view);
            try
            {
                var screen = (System.Windows.Controls.Image)view.FindName("ScreenImage");
                var asked = Outgoing();
                if (asked.Count == 0 || asked[0].ToJsonString() != "{\"type\":\"start-view\",\"to\":\"phone-7\",\"codecs\":" + codecs + "}")
                    throw new Exception("The phone window did not offer codecs to an approved phone: " + (asked.Count > 0 ? asked[0].ToJsonString() : "nothing"));
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
                string offer = "{\"type\":\"start-view\",\"to\":\"phone-7\",\"codecs\":" + codecs + "}";
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

                // Video, all the way: an H.264 stream made here by the app's own
                // encoder from a drawn picture, off the socket, through the
                // feed and the decoder on the feed's thread, into the window.
                // The pictures are greys, which every colour standard reads alike.
                var encoded = video ? VideoOf(96, 160, System.Drawing.Color.FromArgb(40, 40, 40)) : null;
                var encodedAgain = video ? VideoOf(96, 160, System.Drawing.Color.FromArgb(220, 220, 220)) : null;
                var encodedTurned = video ? VideoOf(160, 96, System.Drawing.Color.FromArgb(128, 128, 128)) : null;
                if (encoded == null || encodedAgain == null || encodedTurned == null)
                {
                    if (video && H264Encoder.AnyRegistered())
                        throw new Exception("Windows lists an H.264 encoder and H264Encoder could not make a stream with one");
                    Console.WriteLine("SKIP: no H.264 decoder or encoder in this Windows, so video was not put through the phone window");
                }
                else
                {
                    byte[] Video(ulong stream, uint seq, List<byte[]> units, int unit, int w = 96, int h = 160) => Frame(Crypto.ChPhone, PhoneFrame.Build(
                        new PhoneFrameHeader(stream, seq, unit == 0, PhoneFrame.H264, false, false, w, h, 0), units[unit]));
                    (int r, int g, int b) Middle()
                    {
                        var source = (System.Windows.Media.Imaging.BitmapSource)screen.Source;
                        var pixel = new byte[4];
                        source.CopyPixels(new Int32Rect(source.PixelWidth / 2, source.PixelHeight / 2, 1, 1), pixel, 4, 0);
                        return (pixel[2], pixel[1], pixel[0]);
                    }
                    bool Near((int r, int g, int b) colour, int r, int g, int b) =>
                        Math.Abs(colour.r - r) <= 12 && Math.Abs(colour.g - g) <= 12 && Math.Abs(colour.b - b) <= 12;
                    List<JsonObject> Of(List<JsonObject> messages, string type) => messages.Where(m => m["type"]?.GetValue<string>() == type).ToList();
                    string VideoInfo(string stream) => "{\"type\":\"screen-info\",\"width\":96,\"height\":160,\"typing\":true," +
                        "\"stream\":\"" + stream + "\",\"codec\":\"h264\",\"capture\":\"screen-share\"}";

                    // The window offers video again now that it is approved; the stream is named, and its keyframe is right behind.
                    Outgoing();
                    OffSocket(Sealed(VideoInfo("00000000000000bb"), phone), binary: false);
                    OffSocket(Video(0xbb, 0, encoded, 0), binary: true);
                    Until(() => screen.Source is System.Windows.Media.Imaging.WriteableBitmap, "A video keyframe that followed its announcement was not shown");
                    if (screen.Source is not System.Windows.Media.Imaging.WriteableBitmap { PixelWidth: 96, PixelHeight: 160 })
                        throw new Exception("The video picture shown is not the size that was sent");
                    Until(() => Near(Middle(), 40, 40, 40), "The video picture shown is not the dark one that was sent: " + Middle());
                    var videoBitmap = screen.Source;
                    OffSocket(Video(0xbb, 1, encoded, 1), binary: true);
                    OffSocket(Video(0xbb, 2, encoded, 2), binary: true);
                    Pump(200);
                    if (!ReferenceEquals(videoBitmap, screen.Source)) throw new Exception("Later video frames are not drawn into the bitmap that is already on show");
                    var videoSent = Outgoing();
                    var acked = Of(videoSent, "frame-ack");
                    if (acked.Count == 0 || acked[^1]["seq"]!.GetValue<long>() != 2 || acked[^1]["stream"]!.GetValue<string>() != "00000000000000bb")
                        throw new Exception("The video frames were not acknowledged up to the last");
                    if (Of(videoSent, "request-keyframe").Count != 0) throw new Exception("A keyframe was asked of a healthy stream");
                    view.ToggleStats();
                    Until(() => readout.Text.StartsWith("Smooth sharing · H264 · 96 × 160") && readout.Text.Contains("Decode "),
                        "The readout does not name the codec and the decoding time: " + readout.Text);
                    view.ToggleStats();

                    // A frame is lost: the next is not drawn, a keyframe is asked for, and the one that comes is drawn.
                    Outgoing();
                    OffSocket(Video(0xbb, 4, encoded, 4), binary: true);
                    Pump(100);
                    var request = Of(Outgoing(), "request-keyframe");
                    if (request.Count != 1 || request[0].ToJsonString() != "{\"type\":\"request-keyframe\",\"to\":\"phone-7\",\"stream\":\"00000000000000bb\"}")
                        throw new Exception("A lost video frame was not answered with a request for a keyframe");
                    if (!Near(Middle(), 40, 40, 40)) throw new Exception("Something was drawn from a frame that leaned on a lost one");
                    OffSocket(Video(0xbb, 5, encodedAgain, 0), binary: true);
                    Until(() => Near(Middle(), 220, 220, 220), "The keyframe that was asked for was not shown: " + Middle());

                    // The phone is turned: the same stream at another size.
                    OffSocket(Video(0xbb, 6, encodedTurned, 0, 160, 96), binary: true);
                    Until(() => screen.Source is System.Windows.Media.Imaging.WriteableBitmap { PixelWidth: 160, PixelHeight: 96 } && Near(Middle(), 128, 128, 128),
                        "A turned phone's video was not shown at its new size");

                    // A pause: what arrives is nobody's. Then a JPEG stream, as after the decoder was given up.
                    OffSocket(Sealed("{\"type\":\"screen-info\",\"width\":96,\"height\":160,\"typing\":true,\"paused\":true}", phone), binary: false);
                    var turnedBitmap = screen.Source;
                    OffSocket(Video(0xbb, 7, encoded, 0), binary: true);
                    Pump(200);
                    if (!ReferenceEquals(turnedBitmap, screen.Source) || !Near(Middle(), 128, 128, 128)) throw new Exception("A video frame was drawn while the phone had paused");
                    OffSocket(Sealed("{\"type\":\"screen-info\",\"width\":8,\"height\":16,\"typing\":true," +
                        "\"stream\":\"00000000000000cc\",\"codec\":\"jpeg\",\"capture\":\"accessibility\"}", phone), binary: false);
                    OffSocket(Frame(Crypto.ChPhone, PhoneFrame.Build(new PhoneFrameHeader(0xcc, 0, true, PhoneFrame.Jpeg, false, false, 8, 16, 0), jpeg)), binary: true);
                    Until(() => screen.Source is System.Windows.Media.Imaging.BitmapSource { PixelWidth: 8, PixelHeight: 16 }, "A JPEG stream after video was not shown");

                    // And video again, with the window closing while frames are still being decoded.
                    var closing = new PhoneViewWindow(ws, phone, "Test phone", feed, auth.IsTrusted);
                    typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, closing);
                    OffSocket(Sealed(VideoInfo("00000000000000dd"), phone), binary: false);
                    for (int i = 0; i < encoded.Count; i++)
                    {
                        byte[] frame = Video(0xdd, (uint)i, encoded, i);
                        Task.Run(() => ws.Dispatch(frame, true)).Wait();
                    }
                    closing.Close();
                    typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, view);
                    Pump(300);
                    if (feed.Video.Take() != null) throw new Exception("A picture was left waiting for a window that had closed");
                    // The first window is still there and asks again; the phone answers with a new stream.
                    view.ResendStartView();
                    OffSocket(Sealed(VideoInfo("00000000000000ee"), phone), binary: false);
                    OffSocket(Video(0xee, 0, encodedAgain, 0), binary: true);
                    Until(() => screen.Source is System.Windows.Media.Imaging.WriteableBitmap { PixelWidth: 96 } && Near(Middle(), 220, 220, 220),
                        "After a window closed in the middle of a stream, the next stream was not shown");
                    Outgoing();
                    Console.WriteLine("PASS: a phone's video goes from the socket through the decoder to its window, through a lost frame, a turn, a pause and a closing window");
                }

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
                    // Nor is the phone's own word, while this PC does not approve it. (The
                    // check above never got this far: it is turned away for not being the phone.)
                    trusted.Remove(phone);
                    OffSocket(Sealed(Status("\"state\":\"started\""), phone), binary: false);
                    trusted.Add(phone);
                    if (told.Count != 0 || tick.Visibility == Visibility.Visible)
                        throw new Exception("A smooth-status from a phone this PC has not approved was believed");
                    // A state from some later app says nothing to this one, and is not
                    // the answer: the phone is still waited for, to be reported if it says no more.
                    var waiting = Field<System.Windows.Threading.DispatcherTimer>(view, "_smoothTimer");
                    if (!waiting.IsEnabled) throw new Exception("Asking the phone did not start the wait for its answer");
                    OffSocket(Sealed(Status("\"state\":\"later\""), phone), binary: false);
                    if (told.Count != 0 || !waiting.IsEnabled)
                        throw new Exception("A state this PC does not know ended the wait for the phone's answer");
                    OffSocket(Sealed(Status("\"state\":\"prompting\""), phone), binary: false);
                    if (!told.SequenceEqual(new[] { SmoothVideo.PromptText })) throw new Exception("The person was not told the phone is asking");
                    if (waiting.IsEnabled) throw new Exception("The phone answered and was still being waited for");
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

            // A window that is open on a device follows that device through
            // reconnections, by the key it proved and never by its id or its
            // name. First the relay restarts, as it does for every release:
            // this PC drops, everyone is back under new ids, and no "left"
            // message ever comes for the old ones. The devices here prove
            // their keys as real ones do, by signing this PC's challenge.
            var approvedKeys = Field<Dictionary<string, PeerAuth.TrustedDevice>>(auth, "_trusted");
            var challenges = Field<Dictionary<string, byte[]>>(auth, "_outstandingNonce");
            DeviceIdentity KeyOf(string name)
            {
                var key = DeviceIdentity.LoadOrCreate(Path.Combine(storage, name + ".bin"));
                approvedKeys[key.Fingerprint] = new PeerAuth.TrustedDevice(key.Fingerprint, name, "android", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
                return key;
            }
            var phoneKey = KeyOf("Test phone");
            var otherKey = KeyOf("Other phone");
            JsonObject Peer(string id, string name) => new() { ["id"] = id, ["device"] = "android", ["name"] = name };
            void Welcome(params JsonObject[] peers) => Invoke(host, "OnJson", new JsonObject
                { ["type"] = "welcome", ["id"] = "this-pc", ["peers"] = new JsonArray(peers.Cast<JsonNode?>().ToArray()) });
            void Proves(string id, DeviceIdentity key)
            {
                var nonce = challenges[id];
                Invoke(host, "OnJson", new JsonObject
                {
                    ["type"] = "auth-response", ["from"] = id, ["nonce"] = Convert.ToBase64String(nonce),
                    ["pub"] = Convert.ToBase64String(key.PublicKey), ["sig"] = Convert.ToBase64String(key.Sign(nonce, "")),
                });
            }
            void WaitFor(Func<bool> done, string what)
            {
                for (int i = 0; i < 100 && !done(); i++) Pump(20);
                if (!done()) throw new Exception(what);
            }
            byte[] picture;
            using (var bitmap = new System.Drawing.Bitmap(8, 16))
            using (var bytes = new MemoryStream())
            {
                bitmap.Save(bytes, System.Drawing.Imaging.ImageFormat.Jpeg);
                picture = bytes.ToArray();
            }
            byte[] PictureOf(ulong stream, uint seq) => Frame(Crypto.ChPhone, PhoneFrame.Build(
                new PhoneFrameHeader(stream, seq, true, PhoneFrame.Jpeg, false, false, 8, 16, 0), picture));
            string Announce(string stream, string extra = "") => "{\"type\":\"screen-info\",\"width\":8,\"height\":16,\"typing\":true," +
                "\"stream\":\"" + stream + "\",\"codec\":\"jpeg\",\"capture\":\"accessibility\"" + extra + "}";
            // What the windows send. The main window tells every approved device where it
            // stands with this PC as it joins, whichever device that is, and that is left out.
            List<JsonObject> FromWindows() => Outgoing()
                .Where(m => m["type"]!.GetValue<string>() is not ("file-access" or "privacy-state")).ToList();
            var words = new List<string>();
            Toast.Capture = words.Add;
            PhoneViewWindow? follower = null;
            FileExplorerWindow? files = null;
            bool closed = false;
            var explorers = Field<List<FileExplorerWindow>>(host, "_fileExplorers");
            try
            {
                Invoke(host, "OnState", "connected");
                Welcome(Peer("p1", "Test phone"));
                Proves("p1", phoneKey);
                if (!auth.IsTrusted("p1")) throw new Exception("setup: the phone that proved an approved key is not approved");
                Outgoing();
                follower = new PhoneViewWindow(ws, "p1", "Test phone", feed, auth.IsTrusted, phoneKey.Fingerprint);
                follower.Closed += (_, _) => closed = true;
                typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, follower);
                files = new FileExplorerWindow(ws, Field<FsService>(host, "_fs"), "p1", "Test phone", Field<AppSettings>(host, "_settings"),
                    () => { }, _ => { }, phoneKey.Fingerprint);
                explorers.Add(files);
                var shownAt = (System.Windows.Controls.Image)follower.FindName("ScreenImage");
                var away = (System.Windows.Controls.Border)follower.FindName("AwayBox");
                var awayText = (System.Windows.Controls.TextBlock)follower.FindName("AwayText");
                var caption = (System.Windows.Controls.Border)follower.FindName("CaptionBox");
                var smoothRow = (System.Windows.Controls.Button)follower.FindName("SmoothButton");
                var fileStatus = (System.Windows.Controls.TextBlock)files.FindName("StatusText");
                var opened = Outgoing();
                if (!opened.Any(m => m["type"]!.GetValue<string>() == "start-view" && m["to"]!.GetValue<string>() == "p1" && m["codecs"] != null) ||
                    !opened.Any(m => m["type"]!.GetValue<string>() == "fs-list" && m["to"]!.GetValue<string>() == "p1"))
                    throw new Exception("setup: the windows did not ask the phone for its screen and its files");

                // The phone is viewed: a picture, smooth video on, and asleep behind its lock.
                OffSocket(Sealed(Announce("00000000000000a1", ",\"smooth\":\"on\""), "p1"), binary: false);
                OffSocket(PictureOf(0xa1, 0), binary: true);
                OffSocket(Sealed("{\"type\":\"lock-state\",\"screenOn\":false,\"locked\":true}", "p1"), binary: false);
                WaitFor(() => shownAt.Source != null, "setup: the phone's picture was not shown");
                if (smoothRow.Visibility != Visibility.Visible || caption.Visibility != Visibility.Visible)
                    throw new Exception("setup: the window does not show what the phone said about itself");
                var lastPicture = shownAt.Source;
                Outgoing();

                // This PC drops off the relay.
                Invoke(host, "OnState", "disconnected: connection closed");
                if (follower.PeerId != null || files.PeerId != null) throw new Exception("A window kept the id of a device after this PC lost its connection");
                if (away.Visibility != Visibility.Visible || awayText.Text != "This PC has lost its connection. Reconnecting…")
                    throw new Exception("The viewer does not say that this PC is reconnecting: " + awayText.Text);
                if (smoothRow.Visibility == Visibility.Visible || caption.Visibility == Visibility.Visible)
                    throw new Exception("What the phone said under its old id was kept after it went away");
                if (fileStatus.Text != "This PC has lost its connection. Reconnecting…")
                    throw new Exception("The files window does not say that this PC is reconnecting: " + fileStatus.Text);
                Invoke(host, "OnState", "connecting");

                // Back on the relay. Another approved phone has been handed the id
                // the first one had, and calls itself by the same name.
                Invoke(host, "OnState", "connected");
                if (awayText.Text != "Test phone has lost its connection. Waiting for it to come back…")
                    throw new Exception("The viewer does not say that it is waiting for the phone: " + awayText.Text);
                Welcome(Peer("p1", "Test phone"), Peer("p9", "Test phone"));
                Proves("p1", otherKey);
                if (!auth.IsTrusted("p1")) throw new Exception("setup: the other phone is not approved");
                if (follower.PeerId != null || files.PeerId != null)
                    throw new Exception("A window was attached to another device because it has the old id and the same name");
                // Whatever is typed, clicked or asked for meanwhile goes nowhere, and what
                // the device with the old id sends is not the phone's.
                Invoke(follower, "SendText", "secret");
                Invoke(follower, "SendAction", "homebtn");
                follower.ResendStartView();
                Invoke(files, "RequestList", "");
                OffSocket(Sealed(Announce("00000000000000a2"), "p1"), binary: false);
                OffSocket(PictureOf(0xa2, 0), binary: true);
                OffSocket(PictureOf(0xa1, 1), binary: true);
                OffSocket(Sealed("{\"type\":\"lock-state\",\"screenOn\":false,\"locked\":true}", "p1"), binary: false);
                Pump(200);
                var leaked = FromWindows();
                if (leaked.Count != 0) throw new Exception("Something was sent while the phone was away: " + leaked[0].ToJsonString());
                if (!ReferenceEquals(lastPicture, shownAt.Source) || caption.Visibility == Visibility.Visible)
                    throw new Exception("A device that has the phone's old id was drawn or believed in the phone's window");

                // The phone itself is back, under a new id, and proves its key.
                Proves("p9", phoneKey);
                if (follower.PeerId != "p9" || files.PeerId != "p9") throw new Exception("The windows were not attached to the phone under its new id");
                if (away.Visibility == Visibility.Visible) throw new Exception("The viewer still says the phone is away");
                var again = FromWindows();
                if (again.Any(m => m["to"]?.GetValue<string>() != "p9"))
                    throw new Exception("Something went to an id other than the phone's new one: " + again.First(m => m["to"]?.GetValue<string>() != "p9").ToJsonString());
                if (!again.Any(m => m["type"]!.GetValue<string>() == "start-view" && m["codecs"] != null) ||
                    !again.Any(m => m["type"]!.GetValue<string>() == "get-phone-state") ||
                    !again.Any(m => m["type"]!.GetValue<string>() == "fs-list"))
                    throw new Exception("The phone was not asked for its screen, its state and its files again under its new id");
                // Its new stream is shown, and its word is taken again.
                OffSocket(Sealed(Announce("00000000000000a3", ",\"smooth\":\"available\""), "p9"), binary: false);
                OffSocket(PictureOf(0xa3, 0), binary: true);
                WaitFor(() => !ReferenceEquals(lastPicture, shownAt.Source), "The phone's pictures were not shown under its new id");
                if (smoothRow.Visibility != Visibility.Visible) throw new Exception("The phone's word about smooth video was not taken under its new id");
                if (FromWindows().Any(m => m["to"]?.GetValue<string>() != "p9")) throw new Exception("A picture was acknowledged to the wrong id");
                Console.WriteLine("PASS: when this PC reconnects, its windows wait and carry on with the same device under its new id, and with no other");

                // Only the phone's connection drops: it leaves, and joins again under another id.
                Invoke(host, "OnJson", new JsonObject { ["type"] = "peer-left", ["id"] = "p9" });
                if (closed || follower.PeerId != null) throw new Exception("The viewer did not wait for a phone that left");
                if (awayText.Text != "Test phone has lost its connection. Waiting for it to come back…")
                    throw new Exception("The viewer does not say that the phone has lost its connection: " + awayText.Text);
                Invoke(host, "OnJson", new JsonObject { ["type"] = "peer-joined", ["peer"] = Peer("p12", "Test phone") });
                if (follower.PeerId != null) throw new Exception("The viewer was attached to a device that has proved nothing yet");
                Proves("p12", phoneKey);
                if (follower.PeerId != "p12" || away.Visibility == Visibility.Visible)
                    throw new Exception("The viewer did not carry on with a phone that reconnected");
                if (!Outgoing().Any(m => m["type"]!.GetValue<string>() == "start-view" && m["to"]!.GetValue<string>() == "p12"))
                    throw new Exception("The phone that reconnected was not asked for its screen again");

                // It leaves and stays away: the viewer waits a minute, says so, and closes. The files window stays.
                Invoke(host, "OnJson", new JsonObject { ["type"] = "peer-left", ["id"] = "p12" });
                words.Clear();
                Invoke(host, "CheckLinks", DateTime.UtcNow + DeviceLink.Wait - TimeSpan.FromSeconds(5));
                if (closed) throw new Exception("The viewer gave up on its phone before the wait was over");
                Invoke(host, "CheckLinks", DateTime.UtcNow + DeviceLink.Wait + TimeSpan.FromSeconds(5));
                if (!closed) throw new Exception("The viewer stayed open on a phone that did not come back");
                if (!words.SequenceEqual(new[] { "Test phone did not come back online, so its window was closed." }))
                    throw new Exception("The person was not told why the viewer closed: " + string.Join(" | ", words));
                if (!explorers.Contains(files) || files.PeerId != null) throw new Exception("The files window did not go on waiting");
                Console.WriteLine("PASS: a viewer waits for a phone that reconnects, and closes with a word of why when it stays away");

                // Hanging up on a device from its row, or revoking it, closes this PC's
                // own windows on it. The device stops sending its screen here when it
                // hears, so a viewer left open would show its last picture for good.
                typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, null);
                Invoke(host, "OnJson", new JsonObject { ["type"] = "peer-joined", ["peer"] = Peer("p20", "Test phone") });
                Proves("p20", phoneKey);
                if (files.PeerId != "p20") throw new Exception("setup: the files window was not attached to the phone that came back");
                PhoneViewWindow Viewer()
                {
                    var window = new PhoneViewWindow(ws, "p20", "Test phone", feed, auth.IsTrusted, phoneKey.Fingerprint);
                    window.Closed += (_, _) => typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, null);
                    typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, window);
                    return window;
                }
                bool Viewing() => typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(host) != null;
                var onRow = new MainWindow.DeviceRow { PeerId = "p20", Fingerprint = phoneKey.Fingerprint, Name = "Test phone" };
                Viewer();
                Invoke(host, "DisconnectRow", new MainWindow.DeviceRow { PeerId = "p1", Name = "Other phone" });
                if (!Viewing()) throw new Exception("Hanging up on one device closed the viewer of another");
                Invoke(host, "DisconnectRow", onRow);
                if (Viewing()) throw new Exception("The viewer stayed open on a device this PC hung up on");
                if (!explorers.Contains(files) || files.PeerId != "p20") throw new Exception("Hanging up on a device closed its files window, which still works");
                // Revoked, after the question has been answered yes: no window stays on a device that is not approved.
                Viewer();
                files.Closed += (_, _) => explorers.Remove(files);
                Invoke(host, "Revoke", onRow, phoneKey.Fingerprint);
                if (Viewing() || explorers.Contains(files)) throw new Exception("A window stayed open on a device whose approval was taken away");
                if (auth.IsTrusted("p20")) throw new Exception("setup: the device is still approved");
                Console.WriteLine("PASS: hanging up on a device closes this PC's viewer of it, and revoking it closes every window on it");
            }
            finally
            {
                Toast.Capture = null;
                if (!closed) follower?.Close();
                typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.SetValue(host, null);
                if (files != null && explorers.Contains(files)) files.Close();
                explorers.Clear();
            }

            // The device list. View and Files are for a device this PC has
            // approved: what is typed in a viewer and the files that are sent
            // go to the device the row names, and until its key is approved
            // that name is only its own word. Four devices are online, one of
            // each standing short of revoked, set up as the handshake leaves them.
            var keys = Field<Dictionary<string, string>>(auth, "_peerFingerprint");
            var asking = Field<List<PeerAuth.PendingRequest>>(auth, "_pending");
            var refused = Field<HashSet<string>>(auth, "_deniedFingerprints");
            var said = new List<string>();
            Toast.Capture = said.Add;
            try
            {
                JsonObject Device(string id, string name) => new() { ["id"] = id, ["device"] = "android", ["name"] = name };
                Invoke(host, "OnJson", new JsonObject
                {
                    ["type"] = "welcome",
                    ["id"] = "this-pc",
                    ["peers"] = new JsonArray(Device("row-approved", "Approved phone"), Device("row-waiting", "Waiting phone"),
                        Device("row-refused", "Refused phone"), Device("row-new", "New phone")),
                });
                trusted.Add("row-approved");
                keys["row-approved"] = new string('a', 64);
                keys["row-waiting"] = new string('b', 64);
                asking.Add(new PeerAuth.PendingRequest("row-waiting", new string('b', 64), "Waiting phone", "android"));
                keys["row-refused"] = new string('c', 64);
                refused.Add(new string('c', 64));
                Invoke(host, "RefreshDeviceList");
                // The devices that are online; approved ones that are not have rows too.
                var rows = ((IEnumerable<MainWindow.DeviceRow>)host.DevicesList.ItemsSource).Where(r => r.PeerId != null)
                    .ToDictionary(r => r.PeerId!);
                if (!rows["row-approved"].CanOpen || rows["row-approved"].ViewTip != DeviceActions.ViewTip ||
                    rows["row-approved"].FilesTip != DeviceActions.FilesTip)
                    throw new Exception("An approved device's row does not offer View and Files");
                foreach (var (id, word) in new[] { ("row-waiting", "approve it"), ("row-refused", "refused"), ("row-new", "has not shown") })
                {
                    var row = rows[id];
                    if (row.CanOpen) throw new Exception("View and Files are offered on a device this PC has not approved: " + id);
                    if (!row.ViewTip.Contains(word) || !row.ViewTip.Contains("viewed") || !row.FilesTip.Contains(word) || !row.FilesTip.Contains("browsed"))
                        throw new Exception($"The row of {id} does not say why it can't be opened: {row.ViewTip} / {row.FilesTip}");
                    // Asked for all the same, nothing opens, and the reason is said.
                    said.Clear();
                    var button = new FrameworkElement { DataContext = row };
                    Invoke(host, "RowView_Click", button, new RoutedEventArgs());
                    Invoke(host, "RowFiles_Click", button, new RoutedEventArgs());
                    if (typeof(MainWindow).GetField("_phoneView", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(host) != null ||
                        typeof(MainWindow).GetField("_pcView", BindingFlags.Instance | BindingFlags.NonPublic)!.GetValue(host) != null ||
                        Field<System.Collections.ICollection>(host, "_fileExplorers").Count != 0)
                        throw new Exception("A window was opened on a device this PC has not approved: " + id);
                    if (!said.SequenceEqual(new[] { row.ViewTip, row.FilesTip }))
                        throw new Exception("Asking to open a device that is not approved did not say why: " + string.Join(" | ", said));
                }
                // Only the waiting one can be approved or refused from its row.
                if (!rows["row-waiting"].ShowApprove || rows["row-refused"].ShowApprove || rows["row-new"].ShowApprove || rows["row-approved"].ShowApprove)
                    throw new Exception("Approve is offered on the wrong rows");
                Console.WriteLine("PASS: a device's row offers View and Files only once this PC has approved it, and says why until then");
            }
            finally
            {
                Toast.Capture = null;
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

        // Six frames of H.264 from the app's own encoder: one flat colour, with a small bar that moves.
        List<byte[]>? VideoOf(int w, int h, System.Drawing.Color colour)
        {
            using var encoder = H264Encoder.TryCreate(w, h, 1_000_000, 30, hardware: false) ?? H264Encoder.TryCreate(w, h, 1_000_000, 30, hardware: true);
            if (encoder == null) return null;
            var units = new List<byte[]>();
            using var bitmap = new System.Drawing.Bitmap(w, h, System.Drawing.Imaging.PixelFormat.Format32bppRgb);
            using var g = System.Drawing.Graphics.FromImage(bitmap);
            using var brush = new System.Drawing.SolidBrush(colour);
            for (int i = 0; i < 40 && units.Count < 6; i++)
            {
                g.FillRectangle(brush, 0, 0, w, h);
                g.FillRectangle(System.Drawing.Brushes.Gray, (i * 5) % (w - 8), 4, 8, 8);
                if (encoder.Encode(bitmap, i == 0) is { } unit) units.Add(unit);
            }
            return units.Count == 6 ? units : null;
        }

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
