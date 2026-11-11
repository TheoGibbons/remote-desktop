using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>A picture that arrived for the phone being viewed: a JPEG,
/// <paramref name="Length"/> bytes of <paramref name="Data"/> from
/// <paramref name="Offset"/>. <paramref name="Header"/> is null for a type 1
/// frame, which has none.</summary>
internal readonly record struct PhonePicture(byte[] Data, int Offset, int Length, PhoneFrameHeader? Header, long ArrivedMs);

/// <summary>
/// Takes the frames of the phone being viewed from the socket to the screen.
///
/// The thread that reads the socket hands everything in, in the order it
/// arrived: what the phone's <c>screen-info</c> says about its stream
/// (<see cref="OnScreenInfo"/>), and each frame (<see cref="OnFrame"/>). That
/// order is the point. The window hears of <c>screen-info</c> later, on its
/// own thread, and a stream's first frame is right behind the message that
/// names the stream: had the name gone by way of the window, the frame would
/// have got here first and been thrown away as some other stream's.
///
/// On that thread a frame is only checked and acknowledged, which costs
/// nothing. A thread of the feed's own decodes the newest picture that is
/// waiting and hands it on to be drawn, so neither the socket nor the window
/// ever waits for a JPEG.
///
/// No WPF here: RemoteDesktopWin.PhoneViewTests compiles this file in.
/// </summary>
internal sealed class PhoneFeed : IDisposable
{
    /// <summary>What this viewer can draw, as <c>codecs</c> in <c>start-view</c> names it.</summary>
    public const string Jpeg = "jpeg";

    private readonly object _gate = new();
    private readonly Action<PhonePicture> _draw;
    private readonly Action<JsonObject> _send;
    private readonly Func<long> _nowMs;
    private readonly PhoneVideoStream _stream = new();
    private readonly AckPacer _acks = new();

    private string? _phoneId;       // the phone being viewed
    private bool _offered;          // it was offered codecs: this PC has approved it
    private PhonePicture? _waiting; // the newest picture not yet decoded
    private bool _closed;

    /// <param name="draw">Decodes a picture and hands it on to be shown. Called on the feed's own thread.</param>
    /// <param name="send">Sends a message to the phone. Called on the thread
    /// that reads the socket, and on the feed's own.</param>
    /// <param name="nowMs">A steady clock.</param>
    /// <param name="ownThread">False in tests, which call <see cref="Pump"/> where the thread would run.</param>
    public PhoneFeed(Action<PhonePicture> draw, Action<JsonObject> send, Func<long> nowMs, bool ownThread = true)
    {
        _draw = draw;
        _send = send;
        _nowMs = nowMs;
        if (ownThread) new Thread(Run) { IsBackground = true, Name = "phone-feed" }.Start();
    }

    /// <summary>
    /// <c>start-view</c> for a phone. <c>codecs</c> says this viewer draws
    /// type 5 frames and acknowledges them, and is offered only to a phone
    /// this PC has approved: the stream's id is believed on that phone's
    /// word. Every start-view to such a phone carries it, since each one
    /// replaces what the phone was told before.
    /// </summary>
    public static JsonObject StartView(string phoneId, bool offerCodecs)
    {
        var msg = new JsonObject { ["type"] = "start-view", ["to"] = phoneId };
        if (offerCodecs) msg["codecs"] = new JsonArray(Jpeg);
        return msg;
    }

    /// <summary>The window is asking this phone for its screen, and has, or
    /// has not, offered it <c>codecs</c>. Called before the start-view goes.</summary>
    public void View(string phoneId, bool offered)
    {
        lock (_gate)
        {
            // Another phone, or another arrangement with this one: what was announced no longer holds.
            if (phoneId != _phoneId || offered != _offered) Reset();
            _phoneId = phoneId;
            _offered = offered;
        }
    }

    /// <summary>The window on this phone has closed.</summary>
    public void Stop(string phoneId)
    {
        lock (_gate)
        {
            if (phoneId != _phoneId) return;
            Reset();
            _phoneId = null;
            _offered = false;
        }
    }

    private void Reset()
    {
        _stream.Forget();
        _acks.Forget();
        _waiting = null;
    }

    /// <summary>
    /// A <c>screen-info</c> arrived. Its word about a stream is taken only
    /// from the phone being viewed, and only if this PC has approved that
    /// phone, which is when it was offered <c>codecs</c>. Called on the
    /// thread that reads the socket, before the message goes to the window.
    /// </summary>
    public void OnScreenInfo(JsonNode msg)
    {
        if (msg is not JsonObject info || info["from"] is not JsonValue f || !f.TryGetValue<string>(out var from)) return;
        var (stream, paused) = PhoneVideoStream.Read(msg);
        lock (_gate)
        {
            if (_phoneId == null || from != _phoneId || !_offered) return;
            _stream.Announce(stream, paused);
            // A picture still waiting is of what was announced before.
            if (!_stream.DrawsType1 && _waiting is { Header: null }) _waiting = null;
        }
    }

    /// <summary>
    /// A type 1 or type 5 frame arrived, as <c>[type][payload]</c>. Called on
    /// the thread that reads the socket. A type 5 frame is drawn only if it
    /// carries the id the phone announced, and is acknowledged from here, as
    /// it arrives; a type 1 frame is drawn only while no stream is announced.
    /// </summary>
    public void OnFrame(byte[] data)
    {
        if (data.Length < 2) return;
        JsonObject? ack = null;
        lock (_gate)
        {
            if (_phoneId == null || _closed) return;
            long now = _nowMs();
            if (data[0] == Crypto.ChVideo)
            {
                if (!_stream.DrawsType1) return;
                _stream.Count(data.Length - 1, now);
                _waiting = new PhonePicture(data, 1, data.Length - 1, null, now);
            }
            else if (data[0] == Crypto.ChPhone)
            {
                if (!PhoneFrame.TryParse(data.AsSpan(1), out var header)) return;
                int bytes = data.Length - 1 - PhoneFrame.HeaderSize;
                var verdict = _stream.Take(header, bytes, now);
                if (verdict == FrameVerdict.Refuse) return;
                ack = _acks.Received(header.Stream, header.Seq, now)?.Message(_phoneId);
                // Every JPEG is a whole picture, so the newest replaces any that is still waiting.
                if (verdict == FrameVerdict.Draw)
                    _waiting = new PhonePicture(data, 1 + PhoneFrame.HeaderSize, bytes, header, now);
            }
            else return;
            Monitor.Pulse(_gate);
        }
        if (ack != null) _send(ack);
    }

    /// <summary>The stream of the phone being viewed, for a test to look at.</summary>
    internal (ulong? Stream, bool DrawsType1, long Gaps) State
    {
        get { lock (_gate) return (_stream.Current, _stream.DrawsType1, _stream.Gaps); }
    }

    /// <summary>Does what the feed's thread would do now: sends an
    /// acknowledgement that has come due and draws the picture that is
    /// waiting. False if there was nothing to do.</summary>
    internal bool Pump()
    {
        PhonePicture? picture;
        JsonObject? ack;
        lock (_gate)
            if (!TakeWork(out picture, out ack)) return false;
        Do(picture, ack);
        return true;
    }

    private void Run()
    {
        while (true)
        {
            PhonePicture? picture;
            JsonObject? ack;
            lock (_gate)
            {
                while (true)
                {
                    if (_closed) return;
                    if (TakeWork(out picture, out ack)) break;
                    // Asleep until a frame arrives, or until an acknowledgement that is owed may go.
                    if (_acks.DueIn(_nowMs()) is { } ms) Monitor.Wait(_gate, (int)Math.Clamp(ms, 1, 1000));
                    else Monitor.Wait(_gate);
                }
            }
            Do(picture, ack);
        }
    }

    /// <summary>Under the lock.</summary>
    private bool TakeWork(out PhonePicture? picture, out JsonObject? ack)
    {
        picture = _waiting;
        _waiting = null;
        ack = _phoneId != null ? _acks.Due(_nowMs())?.Message(_phoneId) : null;
        return picture != null || ack != null;
    }

    private void Do(PhonePicture? picture, JsonObject? ack)
    {
        // Nothing here may end the thread: a picture that won't decode is one picture lost.
        try
        {
            if (ack != null) _send(ack);
            if (picture is { } waiting) _draw(waiting);
        }
        catch (Exception ex)
        {
            System.Diagnostics.Debug.WriteLine($"Phone feed: {ex.Message}");
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _closed = true;
            _waiting = null;
            Monitor.PulseAll(_gate);
        }
    }
}
