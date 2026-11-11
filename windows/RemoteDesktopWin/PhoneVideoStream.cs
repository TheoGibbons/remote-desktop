using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// The viewer's side of a phone's pictures: which of the frames that arrive
// are that phone's, and how they are acknowledged. No WPF here:
// RemoteDesktopWin.PhoneViewTests compiles this file in. The Android app
// decides the same way in PhoneVideoStream.kt, for a phone viewing a phone.

/// <summary>What to do with a type 5 frame that has arrived.</summary>
internal enum FrameVerdict
{
    /// <summary>Of the current stream, and a picture this viewer can draw.</summary>
    Draw,
    /// <summary>Of the current stream, in a format this viewer did not offer. It arrived, and is not drawn.</summary>
    Skip,
    /// <summary>Not of the current stream, or one that has been seen already.</summary>
    Refuse,
}

/// <summary>
/// A binary frame names no sender, and anyone holding the session key can
/// make one. So a phone on a current app picks a random id for its stream and
/// tells each viewer in a <c>screen-info</c> addressed to it, and from then on
/// the viewer draws only type 5 frames that carry the id, and no type 1 frames
/// at all. A phone on an older app announces nothing, and its type 1 frames
/// are drawn as they always were.
///
/// Fed in the order things arrive. Not safe to share between threads by
/// itself; <see cref="PhoneFeed"/> is what guards it.
/// </summary>
internal sealed class PhoneVideoStream
{
    private bool _paused;
    private uint? _lastSeq;
    private readonly Queue<(long At, int Bytes)> _lastSecond = new();
    private long _lastSecondBytes;

    /// <summary>The current stream's id, or null while the phone has announced none.</summary>
    public ulong? Current { get; private set; }

    /// <summary>Whether a type 1 frame may be drawn: not while a stream is
    /// announced, and not while the phone has said it is sending nothing.</summary>
    public bool DrawsType1 => Current == null && !_paused;

    /// <summary>Frames of the current stream that never arrived, by the numbers of those that did.</summary>
    public long Gaps { get; private set; }

    /// <summary>What a <c>screen-info</c> says about the stream: its
    /// <c>stream</c>, if it has one that reads as an id, and whether it says
    /// <c>paused</c>.</summary>
    public static (ulong? Stream, bool Paused) Read(JsonNode msg)
    {
        if (msg is not JsonObject info) return (null, false);
        var stream = info["stream"] is JsonValue s && s.TryGetValue<string>(out var text) ? PhoneFrame.ParseStream(text) : null;
        bool paused = info["paused"] is JsonValue p && p.TryGetValue<bool>(out var yes) && yes;
        return (stream, paused);
    }

    /// <summary>
    /// A <c>screen-info</c> from the phone being viewed said this. The caller
    /// has checked that it is from that phone, and that this PC has approved
    /// it and offered it <c>codecs</c>.
    /// </summary>
    public void Announce(ulong? stream, bool paused)
    {
        if (stream != Current) _lastSeq = null;
        Current = stream;
        _paused = stream == null && paused;
    }

    /// <summary>A type 5 frame arrived, with <paramref name="bytes"/> of picture.</summary>
    public FrameVerdict Take(PhoneFrameHeader header, int bytes, long nowMs)
    {
        if (Current is not { } current || header.Stream != current) return FrameVerdict.Refuse;
        if (_lastSeq is { } last)
        {
            // A number seen before is a frame played back, whoever by.
            if (header.Seq <= last) return FrameVerdict.Refuse;
            Gaps += header.Seq - last - 1;
        }
        _lastSeq = header.Seq;
        Count(bytes, nowMs);
        return header.Format == PhoneFrame.Jpeg ? FrameVerdict.Draw : FrameVerdict.Skip;
    }

    /// <summary>A type 1 frame arrived and is to be drawn: counted with the rest.</summary>
    public void Count(int bytes, long nowMs)
    {
        _lastSecond.Enqueue((nowMs, bytes));
        _lastSecondBytes += bytes;
        Trim(nowMs);
    }

    /// <summary>How many pictures arrived in the second up to
    /// <paramref name="nowMs"/>, and how many bytes they were.</summary>
    public (int Frames, long Bytes) LastSecond(long nowMs)
    {
        Trim(nowMs);
        return (_lastSecond.Count, _lastSecondBytes);
    }

    private void Trim(long nowMs)
    {
        while (_lastSecond.Count > 0 && nowMs - _lastSecond.Peek().At >= 1000)
            _lastSecondBytes -= _lastSecond.Dequeue().Bytes;
    }

    /// <summary>Viewing stopped, or nothing was offered: whatever was announced is over.</summary>
    public void Forget()
    {
        Current = null;
        _paused = false;
        _lastSeq = null;
        Gaps = 0;
        _lastSecond.Clear();
        _lastSecondBytes = 0;
    }
}

/// <summary>The highest frame of a stream that has arrived.</summary>
internal readonly record struct FrameAck(ulong Stream, uint Seq)
{
    /// <summary><c>frame-ack</c> for the phone with that peer id.</summary>
    public JsonObject Message(string to) => new()
    {
        ["type"] = "frame-ack",
        ["to"] = to,
        ["stream"] = PhoneFrame.StreamHex(Stream),
        ["seq"] = Seq,
    };
}

/// <summary>
/// Paces <c>frame-ack</c>: the viewer tells the phone the highest frame
/// number that has arrived, at most once every 30 ms. A frame that arrives
/// sooner than that after the last acknowledgement is still acknowledged, a
/// moment later (<see cref="Due"/>), because the phone holds back on what it
/// has not heard about. Like <see cref="PhoneVideoStream"/>, guarded by its user.
/// </summary>
internal sealed class AckPacer
{
    public const long MinGapMs = 30;

    private long? _sentAt;
    private FrameAck? _owed;

    /// <summary>A frame of the current stream arrived. The acknowledgement to
    /// send now, or null when one went out too recently and this one is owed.</summary>
    public FrameAck? Received(ulong stream, uint seq, long nowMs)
    {
        _owed = new FrameAck(stream, seq);
        return Due(nowMs);
    }

    /// <summary>The acknowledgement that is owed, once it may go.</summary>
    public FrameAck? Due(long nowMs)
    {
        if (_owed is not { } ack) return null;
        if (_sentAt is { } sent && nowMs - sent < MinGapMs) return null;
        _owed = null;
        _sentAt = nowMs;
        return ack;
    }

    /// <summary>How long until <see cref="Due"/> has something to send, or null when nothing is owed.</summary>
    public long? DueIn(long nowMs)
    {
        if (_owed == null) return null;
        return _sentAt is { } sent ? Math.Max(0, sent + MinGapMs - nowMs) : 0;
    }

    public void Forget()
    {
        _sentAt = null;
        _owed = null;
    }
}
