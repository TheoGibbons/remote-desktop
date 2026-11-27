using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// The pieces that keep a window that has fallen behind from taking the PC
/// with it, and that say so when it happens: the inbox the relay's messages
/// wait in, the queue a viewer's input is carried out from, the watchdog, and
/// the account the PC gives of itself.
/// </summary>
internal static class StallChecks
{
    internal static void Run()
    {
        CheckInbox();
        CheckRemoteInput();
        CheckWatchdog();
        CheckHostReport();
    }

    private static JsonObject Move(string from, int x) =>
        new() { ["type"] = "mouse", ["action"] = "move", ["from"] = from, ["x"] = x, ["y"] = 0 };

    private static JsonObject Click(string from) =>
        new() { ["type"] = "mouse", ["action"] = "down", ["from"] = from, ["x"] = 0, ["y"] = 0 };

    private static string Said(MessageInbox.Item item) =>
        item.State != null ? "state:" + item.State :
        item.Message!["type"]!.GetValue<string>() +
        (item.Message["action"] is { } action ? ":" + action.GetValue<string>() : "") +
        (item.Message["x"] is { } x ? ":" + x.GetValue<int>() : "") +
        (item.Message["from"] is { } from ? "@" + from.GetValue<string>() : "");

    private static void CheckInbox()
    {
        long now = 0;
        var turns = new Queue<Action>();
        var heard = new List<string>();
        var inbox = new MessageInbox(item => heard.Add(Said(item)), turns.Enqueue, () => now);
        void RunTurns() { while (turns.Count > 0) turns.Dequeue()(); }

        inbox.Post(new JsonObject { ["type"] = "welcome" });
        inbox.PostState("connected");
        inbox.Post(new JsonObject { ["type"] = "peer-joined" });
        if (turns.Count != 1) throw new Exception("Waiting messages should share one scheduled turn of the window");
        turns.Dequeue()();
        if (!heard.SequenceEqual(new[] { "welcome" }) || turns.Count != 1)
            throw new Exception("The window should be handed one message a turn, with the next turn already scheduled");
        RunTurns();
        if (!heard.SequenceEqual(new[] { "welcome", "state:connected", "peer-joined" }))
            throw new Exception("Messages and changes of state came out of order: " + string.Join(", ", heard));
        Console.WriteLine("PASS: the window is handed the relay's messages and the connection's changes of state one at a time, in order");

        // A window that has fallen behind is handed the newest pointer
        // position, not every one it missed, and nothing out of its order.
        heard.Clear();
        inbox.Post(Move("a", 1));
        inbox.Post(Move("a", 2));
        inbox.Post(Move("a", 3));
        inbox.Post(Click("a"));
        inbox.Post(Move("a", 4));
        inbox.Post(Move("b", 5));
        inbox.Post(Move("a", 6));
        inbox.PostState("disconnected: gone");
        inbox.Post(Move("a", 7), connection: 1);
        inbox.Post(Move("a", 8), connection: 2);
        inbox.Post(new JsonObject { ["type"] = "view-region", ["from"] = "a", ["x"] = 1 });
        inbox.Post(new JsonObject { ["type"] = "view-region", ["from"] = "a", ["x"] = 2 });
        if (inbox.Look().Waiting != 9) throw new Exception("Wrong number waiting after merging: " + inbox.Look().Waiting);
        RunTurns();
        if (!heard.SequenceEqual(new[]
            {
                "mouse:move:3@a", "mouse:down:0@a", "mouse:move:4@a", "mouse:move:5@b", "mouse:move:6@a",
                "state:disconnected: gone", "mouse:move:7@a", "mouse:move:8@a", "view-region:2@a",
            }))
            throw new Exception("A waiting pointer position was not replaced by the next one alone: " + string.Join(", ", heard));
        Console.WriteLine("PASS: a pointer position or a viewport replaces the one waiting just ahead of it, from the same device on the same connection, and nothing else");

        heard.Clear();
        inbox.Post(Move("a", 1));
        RunTurns();
        inbox.Post(Move("a", 2));
        RunTurns();
        if (!heard.SequenceEqual(new[] { "mouse:move:1@a", "mouse:move:2@a" }))
            throw new Exception("A window that keeps up should be handed every pointer position");
        Console.WriteLine("PASS: a window that keeps up is handed every pointer position");

        // A handler that puts up a box runs the window's message loop until
        // the box is answered. The messages behind it are handled meanwhile.
        heard.Clear();
        MessageInbox? nested = null;
        nested = new MessageInbox(item =>
        {
            heard.Add(Said(item));
            if (Said(item) == "ask")
            {
                RunTurns(); // the box's message loop
                heard.Add("answered");
            }
        }, turns.Enqueue, () => now);
        nested.Post(new JsonObject { ["type"] = "ask" });
        nested.Post(new JsonObject { ["type"] = "one" });
        nested.Post(new JsonObject { ["type"] = "two" });
        RunTurns();
        if (!heard.SequenceEqual(new[] { "ask", "one", "two", "answered" }))
            throw new Exception("Messages waited for a box to be answered: " + string.Join(", ", heard));
        Console.WriteLine("PASS: messages behind one that is waiting for an answer at the PC are still handled");

        // How far behind the window is, for the watchdog and the PC's report.
        heard.Clear();
        now = 1000;
        inbox.Post(Move("a", 1));
        now = 4000;
        inbox.Post(Move("a", 2));
        inbox.Post(new JsonObject { ["type"] = "start-view" });
        var behind = inbox.Look();
        if (behind.Waiting != 2 || behind.OldestWaitingMs != 3000 || behind.Handling != null)
            throw new Exception($"The inbox misreported what is waiting: {behind}");
        RunTurns();
        var slow = new List<string>();
        var timed = new MessageInbox(_ => now += 2500, turns.Enqueue, () => now) { Slow = (what, ms) => slow.Add($"{what} {ms}") };
        MessageInbox.State during = default;
        MessageInbox? looked = null;
        looked = new MessageInbox(_ => { now += 700; during = looked!.Look(); }, turns.Enqueue, () => now);
        looked.Post(new JsonObject { ["type"] = "start-view" });
        RunTurns();
        if (during.Handling != "start-view" || during.HandlingMs != 700 || looked.Look().Handling != null)
            throw new Exception($"The inbox misreported what is being handled: {during}");
        timed.Post(new JsonObject { ["type"] = "fs-list" });
        timed.PostState("connected");
        timed.Post(new JsonObject { ["type"] = 5 });
        RunTurns();
        if (!slow.SequenceEqual(new[] { "fs-list 2500", "state: connected 2500", "a message with no type 2500" }))
            throw new Exception("Slow handling was not reported: " + string.Join(", ", slow));
        Console.WriteLine("PASS: the inbox says how many messages wait, for how long, what is being handled, and what took long");

        // Whatever a device sends is some JSON; none of it may throw on the socket's thread.
        foreach (var text in new[] { "5", "[1]", "\"x\"", "{}", "{\"type\":5}", "{\"type\":\"mouse\",\"action\":7}",
                     "{\"type\":\"mouse\",\"action\":\"move\",\"from\":9}", "{\"type\":[\"view-region\"]}" })
            inbox.Post(JsonNode.Parse(text)!);
        Console.WriteLine("PASS: a message that makes no sense is queued like any other, without an exception");
    }

    private static void CheckRemoteInput()
    {
        long now = 0;
        var done = new List<string>();
        var dropped = new List<int>();
        var queue = new RemoteInputQueue(dropped.Add, () => now, ownThread: false);
        void Post(RemoteInputQueue.Kind kind, string from, string what, long arrived = -1) =>
            queue.Post(kind, from, arrived < 0 ? now : arrived, () => done.Add(what));

        Post(RemoteInputQueue.Kind.Move, "a", "move1");
        Post(RemoteInputQueue.Kind.Move, "a", "move2");
        Post(RemoteInputQueue.Kind.Other, "a", "down");
        Post(RemoteInputQueue.Kind.Move, "a", "move3");
        Post(RemoteInputQueue.Kind.Move, "b", "move4");
        Post(RemoteInputQueue.Kind.Release, "a", "up");
        queue.RunWaiting();
        if (!done.SequenceEqual(new[] { "move2", "down", "move3", "move4", "up" }) || dropped.Count != 0)
            throw new Exception("Input was not carried out in order, newest position only: " + string.Join(", ", done));
        Console.WriteLine("PASS: a viewer's input is carried out in order, and a pointer position replaces the one waiting just ahead of it");

        // Input that has waited is not replayed onto whatever is there now.
        done.Clear();
        now = 100_000;
        Post(RemoteInputQueue.Kind.Other, "a", "old click", arrived: now - RemoteInputQueue.StaleMs - 1);
        Post(RemoteInputQueue.Kind.Move, "a", "old move", arrived: now - 60_000);
        Post(RemoteInputQueue.Kind.Release, "a", "old release", arrived: now - 60_000);
        Post(RemoteInputQueue.Kind.Other, "a", "fresh click", arrived: now - RemoteInputQueue.StaleMs);
        queue.RunWaiting();
        if (!done.SequenceEqual(new[] { "old release", "fresh click" }) || !dropped.SequenceEqual(new[] { 2 }))
            throw new Exception("Stale input was handled wrongly: " + string.Join(", ", done) + " / " + string.Join(", ", dropped));
        Console.WriteLine("PASS: input that has waited too long is dropped and counted, except a release, which is always carried out");

        done.Clear();
        dropped.Clear();
        Post(RemoteInputQueue.Kind.Other, "a", "a down");
        Post(RemoteInputQueue.Kind.Release, "a", "a up");
        Post(RemoteInputQueue.Kind.Other, "b", "b down");
        queue.Forget("a");
        queue.RunWaiting();
        Post(RemoteInputQueue.Kind.Other, "a", "a down again");
        Post(RemoteInputQueue.Kind.Release, "b", "b up");
        Post(RemoteInputQueue.Kind.Move, "b", "b move");
        queue.Forget(null);
        queue.RunWaiting();
        if (!done.SequenceEqual(new[] { "a up", "b down", "b up" }) || dropped.Count != 0)
            throw new Exception("Forgetting a device's input went wrong: " + string.Join(", ", done));
        Console.WriteLine("PASS: a device that has gone, or may no longer control the PC, has only its releases carried out");

        done.Clear();
        queue.Post(RemoteInputQueue.Kind.Other, "a", now, () => throw new InvalidOperationException("Windows refused it"));
        Post(RemoteInputQueue.Kind.Other, "a", "the next");
        queue.RunWaiting();
        if (!done.SequenceEqual(new[] { "the next" })) throw new Exception("Input that failed stopped the input after it");
        Console.WriteLine("PASS: input that fails does not stop the input after it");

        // And with a thread of its own, started by the first input there is.
        using var threaded = new RemoteInputQueue();
        using var carriedOut = new ManualResetEventSlim();
        int thread = 0;
        threaded.Post(RemoteInputQueue.Kind.Other, "a", MessageInbox.Now(), () =>
        {
            thread = Environment.CurrentManagedThreadId;
            carriedOut.Set();
        });
        if (!carriedOut.Wait(TimeSpan.FromSeconds(5)) || thread == Environment.CurrentManagedThreadId)
            throw new Exception("Input was not carried out on a thread of its own");
        Console.WriteLine("PASS: input is carried out on a thread of its own");
    }

    private static void CheckWatchdog()
    {
        long now = 1_000_000;
        var asks = new Queue<Action>();
        var log = new List<string>();
        int stalls = 0;
        var state = new MessageInbox.State(0, 0, null, 0);
        var watchdog = new UiWatchdog(asks.Enqueue, () => state, log.Add, () => stalls++, () => now, ownThread: false);
        void Second(bool windowRuns)
        {
            now += 1000;
            watchdog.LookNow();
            if (windowRuns) while (asks.Count > 0) asks.Dequeue()();
        }

        for (int i = 0; i < 30; i++) Second(windowRuns: true);
        if (log.Count != 0 || stalls != 0) throw new Exception("A window that answers was reported: " + string.Join(" | ", log));
        Console.WriteLine("PASS: a window that keeps answering is left alone");

        // Stuck inside one thing.
        state = new MessageInbox.State(4, 9000, "mouse", 9500);
        for (int i = 0; i < 9; i++) Second(windowRuns: false);
        if (log.Count != 0) throw new Exception("A stall was reported too soon");
        for (int i = 0; i < 40; i++) Second(windowRuns: false);
        if (log.Count != 1 || stalls != 1 || !log[0].Contains("has not run for") || !log[0].Contains("\"mouse\"") ||
            !log[0].Contains("4 messages are waiting"))
            throw new Exception("A stalled window was not reported once, with what it was doing: " + string.Join(" | ", log));
        state = new MessageInbox.State(0, 0, null, 0);
        Second(windowRuns: true);
        Second(windowRuns: true);
        if (log.Count != 2 || !log[1].Contains("running again"))
            throw new Exception("The end of a stall was not reported: " + string.Join(" | ", log));
        Console.WriteLine("PASS: a window whose thread stops is reported once, with what it was handling, and again when it runs");

        // Answering, and still far behind with its messages.
        log.Clear();
        state = new MessageInbox.State(350, 12_000, "mouse", 300);
        for (int i = 0; i < 5; i++) Second(windowRuns: true);
        if (log.Count != 1 || stalls != 2 || !log[0].Contains("behind") || !log[0].Contains("350 messages are waiting"))
            throw new Exception("A window that is behind was not reported once: " + string.Join(" | ", log));
        state = new MessageInbox.State(0, 0, null, 0);
        Second(windowRuns: true);
        if (log.Count != 2 || !log[1].Contains("caught up")) throw new Exception("Catching up was not reported");
        Console.WriteLine("PASS: a window that answers but is far behind with its messages is reported, and again when it has caught up");

        // The PC slept, or the whole app was held up: nothing was asked of the window in that time.
        log.Clear();
        watchdog.LookNow(); // asked, and not answered before the gap
        now += 3_600_000;
        // A message that arrived just before the gap has waited all through it.
        state = new MessageInbox.State(1, 3_600_200, null, 0);
        watchdog.LookNow();
        state = new MessageInbox.State(0, 0, null, 0);
        Second(windowRuns: true);
        Second(windowRuns: true);
        if (log.Count != 1 || !log[0].Contains("was not running for 3600 s") || stalls != 2)
            throw new Exception("Time the watchdog itself was not running was taken for a stall: " + string.Join(" | ", log));
        Console.WriteLine("PASS: time in which the watchdog itself was not running is logged, and not taken for a stall");

        // Still behind ten seconds after such a gap is behind.
        log.Clear();
        now += 3_600_000;
        state = new MessageInbox.State(5, 3_600_000, "mouse", 300);
        watchdog.LookNow();
        while (asks.Count > 0) asks.Dequeue()();
        for (int i = 0; i < 9; i++) Second(windowRuns: true);
        if (log.Count != 1 || stalls != 2)
            throw new Exception("A window was called behind before it had ten seconds after a gap: " + string.Join(" | ", log));
        Second(windowRuns: true);
        if (log.Count != 2 || stalls != 3 || !log[1].Contains("behind"))
            throw new Exception("A window still behind ten seconds after a gap was not reported: " + string.Join(" | ", log));
        Console.WriteLine("PASS: a window still behind ten seconds after such a gap is reported");
    }

    private static void CheckHostReport()
    {
        string report = HostReport.Build(new HostReport.Facts(
            "THEO", "1.0.69", true, TimeSpan.FromMinutes(135), new MessageInbox.State(37, 11_900, "mouse", 12_400),
            2, 7680, 2160, 3, "sending this screen to 1 device as JPEG 75, 12 FPS, 640 px wide", "off: it gave nothing back",
            1024, 5, new[] { "2026-11-27 09:00:00.000 -07:00  Started version 1.0.69" }));
        foreach (var part in new[]
                 {
                     "Remote Desktop 1.0.69 on Windows, \"THEO\", as administrator, running 2 h 15 min",
                     "Window: handling \"mouse\" for 12.4 s; 37 messages waiting, the next for 11.9 s",
                     "Screen: 2 monitors, 7680x2160 in all; last mouse or keyboard input 3 s ago",
                     "Stream: sending this screen to 1 device as JPEG 75, 12 FPS, 640 px wide; 1024 bytes waiting to go, 5 frames dropped",
                     "H.264: off: it gave nothing back",
                     "  2026-11-27 09:00:00.000 -07:00  Started version 1.0.69",
                 })
            if (!report.Contains(part)) throw new Exception($"The PC's report lacks \"{part}\":\n{report}");
        string quiet = HostReport.Build(new HostReport.Facts(
            "THEO", "1.0.69", false, TimeSpan.FromSeconds(40), new MessageInbox.State(0, 0, null, 0),
            1, 1920, 1080, null, "not sending this screen to anybody", null, 0, 0, Array.Empty<string>()));
        if (!quiet.Contains("running 40 s") || !quiet.Contains("Window: no messages waiting") ||
            !quiet.Contains("Screen: 1 monitor, 1920x1080 in all\n") || quiet.Contains("H.264") ||
            !quiet.Contains("Log: nothing since the app started"))
            throw new Exception("The PC's report of nothing happening reads wrongly:\n" + quiet);
        var (monitors, width, height) = HostReport.Screen();
        if (monitors < 0 || width < 0 || height < 0) throw new Exception("Windows gave no screen size");
        Console.WriteLine("PASS: the PC's account of itself names the app, its window, its screen, its stream and the end of its log");
    }
}
