using System.Diagnostics;
using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// What the socket thread has heard and the window has not handled yet: the
/// relay's messages and the connection's changes of state, in the order they
/// came, handed to the window one at a time.
///
/// Each used to be queued to the window's thread as it arrived, where nothing
/// could be known about the queue. On 2026-11-26 a PC's window fell hours
/// behind it, replaying a phone's mouse moves long after they were made while
/// the answers other devices were waiting for sat behind them. So the queue is
/// kept here instead, where two things can be done about it.
///
/// A message that only says where something is now replaces the one of its
/// kind still waiting just ahead of it: the pointer's position, and the part
/// of the desktop a viewer is looking at. Only the one immediately ahead, from
/// the same device, so nothing is ever handled out of order, and a window that
/// keeps up is handed every one of them. One that has fallen behind is handed
/// the newest.
///
/// And it can be asked how far behind the window is (<see cref="Look"/>),
/// which is what the watchdog reports when the window stops keeping up.
///
/// Changes of state travel with the messages because their order matters:
/// "disconnected" forgets every device, and must not overtake, or be overtaken
/// by, a message from either side of it.
/// </summary>
public sealed class MessageInbox
{
    /// <summary>One thing for the window: a message, or a change of state.</summary>
    /// <param name="ArrivedAt">When it reached this PC, by <see cref="Now"/>.</param>
    /// <param name="Connection">Which connection to the relay it came in on (WsClient.Generation
    /// as it arrived). A device's id means that device only on that connection.</param>
    public readonly record struct Item(JsonNode? Message, string? State, long ArrivedAt, int Connection);

    /// <summary>How the window is getting on with its messages.</summary>
    /// <param name="Waiting">Messages not yet handed over.</param>
    /// <param name="OldestWaitingMs">How long the first of them has waited.</param>
    /// <param name="Handling">What is being handled now, or null between two.</param>
    /// <param name="HandlingMs">For how long.</param>
    public readonly record struct State(int Waiting, long OldestWaitingMs, string? Handling, long HandlingMs);

    private sealed record InHand(string What, long Since);
    private readonly record struct Slot(Item Item, string? MergeKey, long QueuedAt);

    private readonly Action<Item> _deliver;
    private readonly Action<Action> _schedule;
    private readonly Func<long> _clock;
    private readonly LinkedList<Slot> _waiting = new();
    private bool _scheduled;
    private volatile InHand? _inHand;

    /// <summary>Told of anything that took this long to handle, with how long it took.</summary>
    public const int SlowMs = 1000;
    public Action<string, long>? Slow { get; set; }

    /// <summary>Milliseconds on a clock that only goes forward.</summary>
    public static long Now() => Stopwatch.GetTimestamp() * 1000 / Stopwatch.Frequency;

    /// <param name="deliver">Handles one item, on the window's thread.</param>
    /// <param name="schedule">Runs a delivery later on the window's thread, e.g.
    /// Dispatcher.BeginInvoke. Deliveries must run in the order scheduled.</param>
    public MessageInbox(Action<Item> deliver, Action<Action> schedule, Func<long>? clock = null)
    {
        _deliver = deliver;
        _schedule = schedule;
        _clock = clock ?? Now;
    }

    /// <summary>A message from the relay or a device. Any thread; never throws.</summary>
    public void Post(JsonNode message, int connection = 0) =>
        Add(new Item(message, null, _clock(), connection), MergeKeyOf(message));

    /// <summary>The connection changed state. Any thread.</summary>
    public void PostState(string state, int connection = 0) => Add(new Item(null, state, _clock(), connection), null);

    private void Add(Item item, string? mergeKey)
    {
        lock (_waiting)
        {
            if (mergeKey != null && _waiting.Last is { } last && last.Value.MergeKey == mergeKey &&
                last.Value.Item.Connection == item.Connection)
                // Its place in the queue, and how long that place has waited, stay as they were.
                last.Value = last.Value with { Item = item };
            else _waiting.AddLast(new Slot(item, mergeKey, item.ArrivedAt));
            if (_scheduled) return;
            _scheduled = true;
        }
        _schedule(DeliverNext);
    }

    /// <summary>
    /// What makes two messages the same news, the later one replacing the
    /// earlier, or null for a message that must be handled itself.
    /// </summary>
    internal static string? MergeKeyOf(JsonNode message)
    {
        // On the socket thread nothing may throw: a message can be any JSON.
        if (message is not JsonObject obj) return null;
        string? type = Text(obj, "type");
        if (type == "view-region" || (type == "mouse" && Text(obj, "action") == "move"))
            return type + "|" + Text(obj, "from");
        return null;
    }

    private static string? Text(JsonObject obj, string name) =>
        obj.TryGetPropertyValue(name, out var node) && node is JsonValue value && value.TryGetValue<string>(out var text)
            ? text
            : null;

    private void DeliverNext()
    {
        Item item;
        bool more;
        lock (_waiting)
        {
            if (_waiting.First is not { } first)
            {
                _scheduled = false;
                return;
            }
            item = first.Value.Item;
            _waiting.RemoveFirst();
            more = _waiting.Count > 0;
            if (!more) _scheduled = false;
        }
        // Before this one is handled, not after: a handler that puts up a box
        // runs a message loop of its own until the box is answered, and the
        // messages behind it must go on being handled meanwhile.
        if (more) _schedule(DeliverNext);

        string what = item.State != null ? "state: " + item.State : TypeOf(item.Message);
        long started = _clock();
        var outer = _inHand; // a box's message loop can bring one delivery inside another
        _inHand = new InHand(what, started);
        try { _deliver(item); }
        finally
        {
            _inHand = outer;
            long took = _clock() - started;
            if (took >= SlowMs) Slow?.Invoke(what, took);
        }
    }

    private static string TypeOf(JsonNode? message) =>
        message is JsonObject obj && Text(obj, "type") is { } type ? type : "a message with no type";

    /// <summary>How far behind the window is. Any thread.</summary>
    public State Look()
    {
        long now = _clock();
        var inHand = _inHand;
        lock (_waiting)
        {
            long oldest = _waiting.First is { } first ? Math.Max(0, now - first.Value.QueuedAt) : 0;
            return new State(_waiting.Count, oldest, inHand?.What, inHand == null ? 0 : Math.Max(0, now - inHand.Since));
        }
    }
}
