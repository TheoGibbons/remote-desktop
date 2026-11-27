namespace RemoteDesktopWin;

/// <summary>
/// Carries out a viewer's mouse and keyboard input on a thread of its own, in
/// the order it was asked for.
///
/// It used to be carried out on the window's thread as each message was
/// handled. Injecting input is a call into Windows that waits on every program
/// with a hook on the mouse or keyboard, so a slow one made the window slow,
/// and with it everything else the window answers. Whether a device may send
/// input at all is still decided on the window's thread, message by message;
/// only the doing of it comes here.
///
/// Input is worth carrying out only while it is fresh. A click that has
/// waited is a click on whatever has since come to be under it, so what has
/// waited longer than <see cref="StaleMs"/> is dropped, not replayed. A
/// release is the exception: a button or key that went down must come up,
/// however late. And as with the messages themselves (<see
/// cref="MessageInbox"/>), a pointer position replaces the one still waiting
/// just ahead of it.
/// </summary>
internal sealed class RemoteInputQueue : IDisposable
{
    internal enum Kind
    {
        /// <summary>Where the pointer is now.</summary>
        Move,
        /// <summary>A button or key coming up.</summary>
        Release,
        /// <summary>Anything else: a button or key going down, a wheel turn, text.</summary>
        Other,
    }

    internal const int StaleMs = 5000;

    private readonly record struct Entry(Kind Kind, string? From, long ArrivedAt, Action Act);

    private readonly LinkedList<Entry> _waiting = new();
    private readonly Func<long> _clock;
    private readonly Action<int>? _dropped;
    private readonly bool _ownThread;
    private Thread? _thread;
    private bool _closed;
    private int _droppedSoFar;

    /// <param name="dropped">Told how many were dropped as stale, each time the queue empties.</param>
    /// <param name="ownThread">False for a test, which calls <see cref="RunWaiting"/> itself.</param>
    internal RemoteInputQueue(Action<int>? dropped = null, Func<long>? clock = null, bool ownThread = true)
    {
        _dropped = dropped;
        _clock = clock ?? MessageInbox.Now;
        _ownThread = ownThread;
    }

    /// <param name="from">The device that sent it.</param>
    /// <param name="arrivedAt">When its message reached this PC, by <see cref="MessageInbox.Now"/>.</param>
    internal void Post(Kind kind, string? from, long arrivedAt, Action act)
    {
        lock (_waiting)
        {
            if (_closed) return;
            var entry = new Entry(kind, from, arrivedAt, act);
            if (kind == Kind.Move && _waiting.Last is { } last && last.Value.Kind == Kind.Move && last.Value.From == from)
                last.Value = entry;
            else _waiting.AddLast(entry);
            // Started by the first input there is: most windows a test builds never see any.
            if (_ownThread && _thread == null)
            {
                _thread = new Thread(Run) { IsBackground = true, Name = "Remote input" };
                _thread.Start();
            }
            Monitor.Pulse(_waiting);
        }
    }

    /// <summary>
    /// A device has gone, or may no longer control this PC: what it asked for
    /// and has not been done yet is not done. Null forgets every device's.
    /// Releases stay, so nothing it pressed is left held down.
    /// </summary>
    internal void Forget(string? from)
    {
        lock (_waiting)
        {
            for (var node = _waiting.First; node != null;)
            {
                var next = node.Next;
                if (node.Value.Kind != Kind.Release && (from == null || node.Value.From == from)) _waiting.Remove(node);
                node = next;
            }
        }
    }

    private void Run()
    {
        while (true)
        {
            lock (_waiting)
            {
                while (_waiting.Count == 0 && !_closed) Monitor.Wait(_waiting);
                if (_closed) return;
            }
            RunWaiting();
        }
    }

    /// <summary>Carries out, or drops, everything waiting.</summary>
    internal void RunWaiting()
    {
        while (true)
        {
            Entry entry;
            int dropped = 0;
            lock (_waiting)
            {
                if (_waiting.First is not { } first || _closed)
                {
                    dropped = _droppedSoFar;
                    _droppedSoFar = 0;
                    entry = default;
                }
                else
                {
                    entry = first.Value;
                    _waiting.RemoveFirst();
                }
            }
            if (entry.Act == null)
            {
                if (dropped > 0) _dropped?.Invoke(dropped);
                return;
            }
            if (entry.Kind != Kind.Release && _clock() - entry.ArrivedAt > StaleMs)
            {
                lock (_waiting) _droppedSoFar++;
                continue;
            }
            // Whatever goes wrong with one must not end the thread that does the rest.
            try { entry.Act(); }
            catch { }
        }
    }

    public void Dispose()
    {
        lock (_waiting)
        {
            _closed = true;
            _waiting.Clear();
            Monitor.PulseAll(_waiting);
        }
    }
}
