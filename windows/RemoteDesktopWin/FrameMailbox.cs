namespace RemoteDesktopWin;

/// <summary>
/// Hands frames from the socket thread to the window without letting them pile
/// up there. Each frame used to be queued to the UI thread as it arrived, with
/// no limit, so a phone streaming faster than the PC could decode it built a
/// backlog that starved input and drawing and froze the window.
///
/// Past <c>capacity</c> the oldest waiting frame is dropped. One is right for
/// full-frame JPEGs, where only the newest matters. Dirty-rect patches need a
/// few: each is a delta, and a dropped one leaves a sequence gap that the
/// viewer repairs with a keyframe request.
///
/// Delivery is batched. A batch holds whatever was waiting when it starts, so
/// frames that keep arriving cannot keep the UI thread busy.
/// </summary>
public sealed class FrameMailbox
{
    private readonly int _capacity;
    private readonly Action<byte[]> _deliver;
    private readonly Action<Action> _schedule;
    private readonly Queue<byte[]> _waiting = new();
    private bool _scheduled;

    /// <param name="schedule">Runs a batch later on the thread that draws, e.g.
    /// a low-priority Dispatcher.BeginInvoke.</param>
    public FrameMailbox(int capacity, Action<byte[]> deliver, Action<Action> schedule)
    {
        _capacity = Math.Max(1, capacity);
        _deliver = deliver;
        _schedule = schedule;
    }

    public void Post(byte[] frame)
    {
        lock (_waiting)
        {
            while (_waiting.Count >= _capacity) _waiting.Dequeue();
            _waiting.Enqueue(frame);
            if (_scheduled) return;
            _scheduled = true;
        }
        _schedule(DeliverWaiting);
    }

    private void DeliverWaiting()
    {
        byte[][] batch;
        lock (_waiting)
        {
            batch = _waiting.ToArray();
            _waiting.Clear();
            _scheduled = false;
        }
        foreach (var frame in batch) _deliver(frame);
    }
}
