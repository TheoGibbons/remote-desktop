using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// The viewer's side of a phone's pictures: which of the frames that arrive
// are that phone's, and how they are acknowledged. No WPF here:
// RemoteDesktopWin.PhoneViewTests compiles this file in. The Android app
// decides the same way in PhoneVideoStream.kt, for a phone viewing a phone.

/// <summary>What to do with a type 5 frame that has arrived.</summary>
internal enum FrameVerdict
{
    /// <summary>Of the current stream, and a whole picture this viewer can draw: a JPEG.</summary>
    Draw,
    /// <summary>Of the current stream, and the next frame of its video: to be decoded after the one before it.</summary>
    Decode,
    /// <summary>The same, and the keyframe that ends a wait: the decoder is to forget what it knew before it.</summary>
    Restart,
    /// <summary>Of the current stream, and not to be drawn: in a format this
    /// viewer did not offer, or video while a keyframe is waited for. It
    /// arrived, and is acknowledged like any other.</summary>
    Skip,
    /// <summary>Not of the current stream, or one that has been seen already.</summary>
    Refuse,
}

/// <summary>What a frame of video holds, by its bytes and not by what its header claims.</summary>
internal enum VideoKind
{
    /// <summary>A frame that leans on the one before it.</summary>
    Ordinary,
    /// <summary>A picture that needs nothing before it, with the parameter sets to decode it by.</summary>
    Keyframe,
    /// <summary>Parameter sets and no picture: nothing to decode or draw, and no break in the chain either.</summary>
    ParameterSets,
}

/// <summary>
/// A binary frame names no sender, and anyone holding the session key can
/// make one. So a phone on a current app picks a random id for its stream and
/// tells each viewer in a <c>screen-info</c> addressed to it, and from then on
/// the viewer draws only type 5 frames that carry the id, and no type 1 frames
/// at all. A phone on an older app announces nothing, and its type 1 frames
/// are drawn as they always were.
///
/// <para><b>Video.</b> A JPEG is a whole picture. An H.264 frame is the
/// difference from the one before, so its frames are decoded in order and
/// none may be missed. A keyframe is the exception: it needs nothing before
/// it. So whenever the chain is broken this class says to pass frames over
/// until a keyframe comes, and to ask the phone for one: at once, and again
/// every second while none has come (<see cref="KeyframeRequestDue"/>). The
/// chain is broken by a missing number, by a stream that this viewer comes
/// into part way, by a decoder that failed, and by frames thrown away because
/// the PC could not keep up. What is a keyframe is judged by what the frame
/// holds, which the caller says (<see cref="VideoKind"/>): a header's
/// keyframe bit is the phone's word and is not relied on. Video is given up
/// (<see cref="GivenUp"/>), and the viewer then offers JPEG only, when the
/// decoder has failed twice while a phone is viewed, and when five requests
/// in a row have brought no keyframe though the phone went on sending: a
/// phone that will not send one would otherwise leave the window frozen for
/// good. All of it is decided here so that it can be tested without a
/// decoder.</para>
///
/// Fed in the order things arrive. Not safe to share between threads by
/// itself; <see cref="PhoneFeed"/> is what guards it.
/// </summary>
internal sealed class PhoneVideoStream
{
    /// <summary>A keyframe is asked for at most this often.</summary>
    public const long KeyframeAskEveryMs = 1000;

    /// <summary>This many decoder failures while a phone is viewed, and H.264 is given up.</summary>
    public const int FailuresToGiveUp = 2;

    /// <summary>This many requests for a keyframe in a row that the phone
    /// answered with more frames and no keyframe, and H.264 is given up.</summary>
    public const int UnansweredToGiveUp = 5;

    private enum Wait
    {
        /// <summary>Video frames are decoded as they come.</summary>
        None,
        /// <summary>A stream has just been announced: its first video frame should be a keyframe, and nothing is asked yet.</summary>
        ForTheFirst,
        /// <summary>The chain is broken: frames are passed over and a keyframe is asked for.</summary>
        Asking,
    }

    private bool _paused;
    private uint? _lastSeq;
    private Wait _wait = Wait.ForTheFirst;
    private long? _askedAt;
    private int _askedSinceKeyframe;    // requests since a keyframe last ended a wait
    private bool _arrivedSinceAsk;      // the phone has sent video since the last request
    private readonly Queue<(long At, int Bytes)> _lastSecond = new();
    private long _lastSecondBytes;

    /// <summary>The current stream's id, or null while the phone has announced none.</summary>
    public ulong? Current { get; private set; }

    /// <summary>Whether a type 1 frame may be drawn: not while a stream is
    /// announced, and not while the phone has said it is sending nothing.</summary>
    public bool DrawsType1 => Current == null && !_paused;

    /// <summary>Frames of the current stream that never arrived, by the numbers of those that did.</summary>
    public long Gaps { get; private set; }

    /// <summary>Whether this viewer offered the phone <c>h264</c>. A format that was not offered is not drawn.</summary>
    public bool OffersH264 { get; set; }

    /// <summary>Video frames are being passed over until a keyframe comes.</summary>
    public bool WaitingForKeyframe => _wait == Wait.Asking;

    /// <summary>How often the decoder has failed while this phone has been viewed.</summary>
    public int Failures { get; private set; }

    /// <summary>Requests for a keyframe, one after another, that the phone answered with frames and no keyframe.</summary>
    public int Unanswered { get; private set; }

    /// <summary>The decoder has failed too often, or the phone will not send
    /// a keyframe: H.264 is no longer to be offered.</summary>
    public bool GivenUp => Failures >= FailuresToGiveUp || Unanswered >= UnansweredToGiveUp;

    /// <summary>How many times a keyframe has been asked for while this phone has been viewed.</summary>
    public int KeyframesAsked { get; private set; }

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
        if (stream != Current)
        {
            _lastSeq = null;
            // Another stream's video starts with its own keyframe.
            _wait = Wait.ForTheFirst;
        }
        Current = stream;
        _paused = stream == null && paused;
    }

    /// <summary>A type 5 frame arrived, with <paramref name="bytes"/> of picture.</summary>
    /// <param name="kind">For video, what the frame holds, read from its
    /// bytes. Left out, the header's keyframe bit is believed: for callers
    /// that have no bytes to look at.</param>
    public FrameVerdict Take(PhoneFrameHeader header, int bytes, long nowMs, VideoKind? kind = null)
    {
        if (Current is not { } current || header.Stream != current) return FrameVerdict.Refuse;
        bool gap = false;
        if (_lastSeq is { } last)
        {
            // A number seen before is a frame played back, whoever by.
            if (header.Seq <= last) return FrameVerdict.Refuse;
            gap = header.Seq - last > 1;
            Gaps += header.Seq - last - 1;
        }
        _lastSeq = header.Seq;
        Count(bytes, nowMs);
        if (header.Format == PhoneFrame.Jpeg) return FrameVerdict.Draw;
        if (header.Format != PhoneFrame.H264 || !OffersH264) return FrameVerdict.Skip;

        _arrivedSinceAsk = true;
        var holds = kind ?? (header.Keyframe ? VideoKind.Keyframe : VideoKind.Ordinary);
        // A frame is missing: whatever follows it leans on a picture this viewer never had.
        if (gap) _wait = Wait.Asking;
        // Parameter sets on their own change nothing: the feed keeps them for the keyframe they belong to.
        if (holds == VideoKind.ParameterSets) return FrameVerdict.Skip;
        if (_wait == Wait.None) return FrameVerdict.Decode;
        if (holds != VideoKind.Keyframe)
        {
            // Also a stream come into part way: its first frame here is no keyframe.
            _wait = Wait.Asking;
            return FrameVerdict.Skip;
        }
        _wait = Wait.None;
        _askedSinceKeyframe = 0;
        Unanswered = 0;
        return FrameVerdict.Restart;
    }

    /// <summary>
    /// The decoder could not use a frame it was given as the next one: it
    /// failed (an error, or a keyframe that gave no picture), which is
    /// counted, or it was not the keyframe its header said. Either way the
    /// frames after it are passed over until a keyframe comes.
    /// </summary>
    public void DecoderLost(bool failed)
    {
        if (failed) Failures++;
        _wait = Wait.Asking;
    }

    /// <summary>The PC fell behind and threw away frames that were waiting to
    /// be decoded: the ones after them cannot be decoded either.</summary>
    public void Overflowed() => _wait = Wait.Asking;

    /// <summary>
    /// Whether to send <c>request-keyframe</c> now. True at most once a
    /// second, and only while a keyframe is waited for; saying true counts
    /// as having asked.
    /// </summary>
    public bool KeyframeRequestDue(long nowMs)
    {
        if (KeyframeRequestDueIn(nowMs) != 0) return false;
        // The request before this one brought frames and no keyframe. (If it
        // brought nothing at all, the connection is the trouble, not the phone.)
        if (_askedSinceKeyframe > 0 && _arrivedSinceAsk) Unanswered++;
        if (GivenUp) return false;
        _askedAt = nowMs;
        _askedSinceKeyframe++;
        _arrivedSinceAsk = false;
        KeyframesAsked++;
        return true;
    }

    /// <summary>How long until the next request is due: 0 when one is, null when none is waited for.</summary>
    public long? KeyframeRequestDueIn(long nowMs)
    {
        if (_wait != Wait.Asking || Current == null || !OffersH264 || GivenUp) return null;
        return _askedAt is { } asked ? Math.Max(0, asked + KeyframeAskEveryMs - nowMs) : 0;
    }

    /// <summary><c>request-keyframe</c> for the current stream of the phone with that peer id.</summary>
    public JsonObject KeyframeRequest(string to) => new()
    {
        ["type"] = "request-keyframe",
        ["to"] = to,
        ["stream"] = PhoneFrame.StreamHex(Current ?? 0),
    };

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
        _wait = Wait.ForTheFirst;
        _askedAt = null;
        _askedSinceKeyframe = 0;
        _arrivedSinceAsk = false;
        Unanswered = 0;
        Failures = 0;
        KeyframesAsked = 0;
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
