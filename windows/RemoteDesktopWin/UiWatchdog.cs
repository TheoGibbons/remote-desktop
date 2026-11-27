namespace RemoteDesktopWin;

/// <summary>
/// Notices when the window's thread stops keeping up, and says so in the log
/// while it is still happening.
///
/// Everything a device asks of this PC is answered on that thread. When it
/// stalls the PC stays online and answers nobody, and afterwards nothing
/// shows what it was doing: Windows records a hang only if somebody is there
/// to close the window. Two things are watched, once a second:
///
/// whether the thread runs at all, by asking it to answer ahead of everything
/// it has waiting. No answer for <see cref="StallMs"/> means it is stuck
/// inside one thing;
///
/// and whether it keeps up with the relay's messages. It can be answering
/// and still be far behind, each message taking long, which is what a PC did
/// for hours on 2026-11-26.
///
/// Either is logged once when it starts and once when it ends, with what the
/// thread was handling, and the first time in a run <c>stalled</c> is called
/// so the app's memory can be saved while the cause is still in it.
/// </summary>
internal sealed class UiWatchdog : IDisposable
{
    internal const int StallMs = 10_000;
    private const int LookEveryMs = 1000;

    private readonly Action<Action> _ask;
    private readonly Func<MessageInbox.State> _look;
    private readonly Action<string> _log;
    private readonly Action? _stalled;
    private readonly Func<long> _clock;
    private readonly ManualResetEventSlim _stop = new();

    private long _askedAt;      // when the thread was last asked and has not answered; 0 once it has
    private long _silentSince;  // when the ask that it never answered was made; 0 while it answers
    private bool _behind;
    private long _lastLook;
    private long _backAt;       // when this thread last found that it had itself not been running; 0 if never

    /// <param name="ask">Runs the action on the window's thread, ahead of what
    /// is already waiting there: Dispatcher.BeginInvoke at Send priority.</param>
    /// <param name="look">How far the window is behind with its messages.</param>
    /// <param name="stalled">Called when either kind of stall starts.</param>
    /// <param name="ownThread">False for a test, which calls <see cref="LookNow"/> itself.</param>
    internal UiWatchdog(Action<Action> ask, Func<MessageInbox.State> look, Action<string> log,
        Action? stalled = null, Func<long>? clock = null, bool ownThread = true)
    {
        _ask = ask;
        _look = look;
        _log = log;
        _stalled = stalled;
        _clock = clock ?? MessageInbox.Now;
        if (ownThread) new Thread(Run) { IsBackground = true, Name = "Window watchdog" }.Start();
    }

    private void Run()
    {
        while (!_stop.Wait(LookEveryMs))
        {
            // Whatever goes wrong with the watching must not end the app it watches.
            try { LookNow(); }
            catch { }
        }
    }

    internal void LookNow()
    {
        long now = _clock();
        // This thread was itself not running: the PC slept, or the whole app
        // was held up. The window was not asked anything it failed to answer
        // in that time, so start again from here. It goes in the log because
        // anything timed across it reads as having taken that long.
        if (_lastLook != 0 && now - _lastLook > StallMs / 2)
        {
            Interlocked.Exchange(ref _askedAt, 0);
            _silentSince = 0;
            _backAt = now;
            _log($"The app was not running for {Seconds(now - _lastLook)}: the PC slept, or the whole app was held up.");
        }
        _lastLook = now;

        var state = _look();
        long asked = Interlocked.Read(ref _askedAt);
        if (asked == 0)
        {
            if (_silentSince != 0)
            {
                _log($"The window's thread is running again, {Seconds(now - _silentSince)} after it stopped.");
                _silentSince = 0;
            }
            Interlocked.Exchange(ref _askedAt, now);
            _ask(() => Interlocked.Exchange(ref _askedAt, 0));
        }
        else if (_silentSince == 0 && now - asked >= StallMs)
        {
            _silentSince = asked;
            _log($"The window's thread has not run for {Seconds(now - asked)}. {Doing(state)} {Waiting(state)}");
            _stalled?.Invoke();
        }

        // Only while it runs: stuck inside one thing, it is behind as well, and that is already said.
        if (_silentSince != 0) return;
        // A message that was waiting while the app was not running has not
        // waited that long on the window: the window gets as long again to
        // reach it before it counts as behind.
        bool justBack = _backAt != 0 && now - _backAt < StallMs;
        if (!_behind && !justBack && state.OldestWaitingMs >= StallMs)
        {
            _behind = true;
            _log($"The window is behind with the relay's messages: the next has waited {Seconds(state.OldestWaitingMs)}. " +
                 $"{Doing(state)} {Waiting(state)}");
            _stalled?.Invoke();
        }
        else if (_behind && state.OldestWaitingMs < LookEveryMs)
        {
            _behind = false;
            _log("The window has caught up with the relay's messages.");
        }
    }

    private static string Seconds(long ms) => $"{ms / 1000.0:0.#} s";

    private static string Doing(MessageInbox.State state) => state.Handling == null
        ? "It is not handling one of the relay's messages."
        : $"It has been handling \"{state.Handling}\" for {Seconds(state.HandlingMs)}.";

    private static string Waiting(MessageInbox.State state) => state.Waiting switch
    {
        0 => "No messages are waiting.",
        1 => "1 message is waiting.",
        _ => $"{state.Waiting} messages are waiting.",
    };

    public void Dispose() => _stop.Set();
}
