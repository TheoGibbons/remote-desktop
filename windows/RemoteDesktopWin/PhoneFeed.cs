using System.Diagnostics;
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
/// nothing. A thread of the feed's own turns what is waiting into pictures
/// and hands them on to be drawn, so neither the socket nor the window ever
/// waits for a decoder.
///
/// <para><b>JPEG and video.</b> Every JPEG is a whole picture, so only the
/// newest one waiting is decoded. A video frame (H.264) is the difference
/// from the frame before it, so those wait in a queue and every one is
/// decoded, in order, by an <see cref="H264Decoder"/> that lives on the
/// feed's thread; what may be skipped is only a finished picture the window
/// has not drawn yet (<see cref="Video"/>). If the queue grows to a second's
/// worth because the PC cannot keep up, it is emptied and the phone is asked
/// for a keyframe, as it is after a lost frame and after a decoder failure
/// (<see cref="PhoneVideoStream"/> has the rules). A decoder that fails twice
/// is given up for as long as the app runs: the phone is told so by a
/// <c>start-view</c> that offers JPEG only.</para>
///
/// <para><b>Threads.</b> One lock guards everything here. The socket's thread
/// and the window's thread only ever hold it for a few comparisons. Decoding
/// is done outside it, so viewing can stop, the phone can leave, and its
/// stream can pause or change while a frame is being decoded: when the
/// decoder is done, its picture is shown only if it is still of the current
/// stream, and is dropped otherwise. The decoder is let go when viewing stops
/// (<see cref="H264Decoder.Dispose"/> waits for nobody).</para>
///
/// It also keeps the figures for the Stats readout (<see cref="PhoneStats"/>),
/// since it is where things arrive: while the readout is open its thread
/// pings the phone every two seconds, and the answer is timed here, on the
/// socket's thread, and not whenever the window gets round to it.
///
/// No WPF here: RemoteDesktopWin.PhoneViewTests compiles this file in.
/// </summary>
internal sealed class PhoneFeed : IDisposable
{
    /// <summary>What this viewer can draw, as <c>codecs</c> in <c>start-view</c> names it.</summary>
    public const string Jpeg = "jpeg", H264 = "h264";

    /// <summary>
    /// The most video that may wait to be decoded: a second's worth of
    /// frames, or this many bytes. Past either the PC is not keeping up, and
    /// showing the phone a second late is worse than starting again from a
    /// keyframe.
    /// </summary>
    public const int MaxWaitingFrames = 30;
    public const int MaxWaitingBytes = 4 * 1024 * 1024;

    /// <summary>
    /// A keyframe that arrives while this many frames still wait lets them
    /// go: it needs none of them, and decoding them would only show the
    /// phone later than it has to be.
    /// </summary>
    public const int SkipToKeyframeAt = 5;

    private readonly object _gate = new();
    private readonly Action<PhonePicture> _draw;
    private readonly Action<PhoneVideoPicture>? _drawVideo;
    private readonly Action<JsonObject> _send;
    private readonly Func<long> _nowMs;
    private readonly PhoneVideoStream _stream = new();
    private readonly AckPacer _acks = new();
    private readonly PhoneStats _stats = new();
    private bool _statsShown;       // the readout is open: the phone is pinged

    private string? _phoneId;       // the phone being viewed
    private bool _offered;          // it was offered codecs: this PC has approved it
    private PhonePicture? _waiting; // the newest JPEG not yet decoded
    private bool _closed;

    // Video. Frames wait here in the order they arrived.
    private readonly record struct VideoFrame(byte[] Data, int Offset, int Length, PhoneFrameHeader Header, bool Restart);
    private readonly Queue<VideoFrame> _video = new();
    private long _videoBytes;
    private bool? _canDecode;       // whether this Windows decodes H.264; null until found out
    private bool _givenUp;          // the decoder failed too often: JPEG only until the app is restarted
    private string _givenUpWhy = "";
    private H264Decoder? _decoder;  // made and used on the feed's thread, let go from any
    private readonly H264PictureHandler _onDecoded;
    private PhoneVideoPicture? _decodedInto;    // the feed's thread only

    /// <summary>For tests: called on the feed's thread when a frame has been
    /// through the decoder and before anything is made of the result, which
    /// is where another thread's doings during the decoding land.</summary>
    internal Action? WhileDecoding;

    /// <summary>Decoded video pictures on their way to the window.</summary>
    public PhoneVideoPictures Video { get; } = new();

    /// <param name="draw">Decodes a JPEG and hands it on to be shown. Called on the feed's own thread.</param>
    /// <param name="send">Sends a message to the phone. Called on the thread
    /// that reads the socket, and on the feed's own.</param>
    /// <param name="nowMs">A steady clock.</param>
    /// <param name="ownThread">False in tests, which call <see cref="Pump"/> where the thread would run.</param>
    /// <param name="drawVideo">Hands a decoded video picture on to be shown:
    /// it is to be published to <see cref="Video"/>, or returned to it.
    /// Called on the feed's own thread. Without it no video is offered.</param>
    /// <param name="canDecodeH264">Whether this Windows decodes H.264. Left
    /// out, the feed's thread finds out as it starts, which takes a moment
    /// and so is kept off the window's thread; a feed with no thread never
    /// finds out and offers none.</param>
    public PhoneFeed(Action<PhonePicture> draw, Action<JsonObject> send, Func<long> nowMs, bool ownThread = true,
        Action<PhoneVideoPicture>? drawVideo = null, bool? canDecodeH264 = null)
    {
        _draw = draw;
        _send = send;
        _nowMs = nowMs;
        _drawVideo = drawVideo;
        _onDecoded = OnDecoded;
        _canDecode = drawVideo == null ? false : canDecodeH264 ?? (ownThread ? null : false);
        if (ownThread) new Thread(Run) { IsBackground = true, Name = "phone-feed" }.Start();
    }

    /// <summary>
    /// <c>start-view</c> for a phone. <c>codecs</c> says this viewer draws
    /// type 5 frames and acknowledges them, and is offered only to a phone
    /// this PC has approved: the stream's id is believed on that phone's
    /// word. Every start-view to such a phone carries it, since each one
    /// replaces what the phone was told before. <paramref name="h264"/> is
    /// what <see cref="View"/> answered.
    /// </summary>
    public static JsonObject StartView(string phoneId, bool offerCodecs, bool h264 = false)
    {
        var msg = new JsonObject { ["type"] = "start-view", ["to"] = phoneId };
        if (offerCodecs) msg["codecs"] = h264 ? new JsonArray(H264, Jpeg) : new JsonArray(Jpeg);
        return msg;
    }

    /// <summary>The window is asking this phone for its screen, and has, or
    /// has not, offered it <c>codecs</c>. Called before the start-view goes.</summary>
    /// <returns>Whether the offer is to include video: only to a phone that
    /// is offered codecs at all, on a Windows that can decode it, and while
    /// the decoder has not been given up.</returns>
    public bool View(string phoneId, bool offered)
    {
        lock (_gate)
        {
            // Another phone, or another arrangement with this one: what was announced no longer holds.
            if (phoneId != _phoneId || offered != _offered) Reset();
            _phoneId = phoneId;
            _offered = offered;
            _stream.OffersH264 = offered && _canDecode == true && !_givenUp;
            return _stream.OffersH264;
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
            _stream.OffersH264 = false;
            // The readout went with the window. The next window starts with it
            // closed, and is not to ping its phone until it is opened there.
            _statsShown = false;
        }
    }

    /// <summary>Under the lock.</summary>
    private void Reset()
    {
        _stream.Forget();
        _acks.Forget();
        _stats.Reset();
        _waiting = null;
        DropVideo();
        // A frame may be in the decoder at this moment, on the feed's thread.
        // That is allowed for: it is finished there and then thrown away.
        _decoder?.Dispose();
        _decoder = null;
    }

    /// <summary>Under the lock: the video that waits, decoded or not, is not to be shown.</summary>
    private void DropVideo()
    {
        _video.Clear();
        _videoBytes = 0;
        Video.Clear();
    }

    private static string? Text(JsonNode? node) => node is JsonValue v && v.TryGetValue<string>(out var text) ? text : null;

    /// <summary>A whole number, however the JSON came by it; null for anything else.</summary>
    private static long? Number(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<long>(out var whole)) return whole;
        if (v.TryGetValue<int>(out var small)) return small;
        return v.TryGetValue<double>(out var real) && Math.Abs(real) < 1e15 ? (long)real : null;
    }

    /// <summary>
    /// A <c>screen-info</c> arrived. Its word about a stream is taken only
    /// from the phone being viewed, and only if this PC has approved that
    /// phone, which is when it was offered <c>codecs</c>. Called on the
    /// thread that reads the socket, before the message goes to the window.
    /// </summary>
    public void OnScreenInfo(JsonNode msg)
    {
        if (msg is not JsonObject info || Text(info["from"]) is not { } from) return;
        var (stream, paused) = PhoneVideoStream.Read(msg);
        lock (_gate)
        {
            if (_phoneId == null || from != _phoneId) return;
            _stats.Size((int)(Number(info["width"]) ?? 0), (int)(Number(info["height"]) ?? 0));
            if (!_offered) return;
            _stats.Announce(Text(info["capture"]), Text(info["codec"]));
            bool another = stream != _stream.Current;
            _stream.Announce(stream, paused);
            // A picture still waiting is of what was announced before.
            if (!_stream.DrawsType1 && _waiting is { Header: null }) _waiting = null;
            // And so is video, of another stream or of one that has been paused.
            if (another) DropVideo();
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
        JsonObject? ack = null, keyframe = null;
        lock (_gate)
        {
            if (_phoneId == null || _closed) return;
            long now = _nowMs();
            if (data[0] == Crypto.ChVideo)
            {
                if (!_stream.DrawsType1) return;
                _stream.Count(data.Length - 1, now);
                _stats.Arrived(data.Length - 1, null, now);
                _waiting = new PhonePicture(data, 1, data.Length - 1, null, now);
            }
            else if (data[0] == Crypto.ChPhone)
            {
                if (!PhoneFrame.TryParse(data.AsSpan(1), out var header)) return;
                int bytes = data.Length - 1 - PhoneFrame.HeaderSize;
                var verdict = _stream.Take(header, bytes, now);
                if (verdict == FrameVerdict.Refuse) return;
                _stats.Arrived(bytes, header, now);
                ack = _acks.Received(header.Stream, header.Seq, now)?.Message(_phoneId);
                switch (verdict)
                {
                    case FrameVerdict.Draw:
                        // Every JPEG is a whole picture, so the newest replaces any that is still waiting.
                        _waiting = new PhonePicture(data, 1 + PhoneFrame.HeaderSize, bytes, header, now);
                        break;
                    case FrameVerdict.Decode:
                    case FrameVerdict.Restart:
                        QueueVideo(new VideoFrame(data, 1 + PhoneFrame.HeaderSize, bytes, header, verdict == FrameVerdict.Restart));
                        break;
                    // Skip: it arrived, and that is all. Frames from before a
                    // break in the chain are still good and stay in the queue.
                }
                if (_stream.KeyframeRequestDue(now)) keyframe = _stream.KeyframeRequest(_phoneId);
            }
            else return;
            Monitor.Pulse(_gate);
        }
        if (ack != null) _send(ack);
        if (keyframe != null) _send(keyframe);
    }

    /// <summary>Under the lock.</summary>
    private void QueueVideo(VideoFrame frame)
    {
        if (frame.Restart)
        {
            // Nothing before a keyframe that ends a wait is of any use.
            _video.Clear();
            _videoBytes = 0;
        }
        else if (frame.Header.Keyframe && _video.Count >= SkipToKeyframeAt)
        {
            // Behind, and here is a picture that needs none of what waits.
            _video.Clear();
            _videoBytes = 0;
            frame = frame with { Restart = true };
        }
        else if (_video.Count >= MaxWaitingFrames || _videoBytes + frame.Length > MaxWaitingBytes)
        {
            // The PC is not keeping up. This frame goes with the rest: it
            // could only be decoded after them.
            _video.Clear();
            _videoBytes = 0;
            _stream.Overflowed();
            return;
        }
        _video.Enqueue(frame);
        _videoBytes += frame.Length;
    }

    /// <summary>
    /// A <c>diagnostic-pong</c> arrived. Timed here, on the thread that reads
    /// the socket; only the viewed phone's answer to a ping of ours counts.
    /// </summary>
    public void OnPong(JsonNode msg)
    {
        if (msg is not JsonObject pong || Text(pong["from"]) is not { } from) return;
        if (Number(pong["nonce"]) is not { } nonce || Number(pong["hostTimeMs"]) is not { } hostTime) return;
        lock (_gate)
        {
            if (from != _phoneId) return;
            _stats.Pong(nonce, hostTime, Number(pong["hostQueueBytes"]) ?? 0, _nowMs());
        }
    }

    /// <summary>The window has put a picture on show. <paramref name="header"/>
    /// is the header it came behind, if it had one.</summary>
    public void Drawn(PhoneFrameHeader? header)
    {
        if (header is not { } drawn) return;
        lock (_gate)
            if (_stream.Current == drawn.Stream) _stats.Drawn(drawn, _nowMs());
    }

    /// <summary>The readout was opened or closed. While it is open the phone
    /// is pinged every two seconds, which is what the delay is worked out from.</summary>
    public void ShowStats(bool shown)
    {
        lock (_gate)
        {
            _statsShown = shown;
            Monitor.Pulse(_gate);
        }
    }

    /// <summary>The readout's text, as things stand now.</summary>
    public string StatsText()
    {
        lock (_gate)
        {
            NoteVideo();
            return _stats.Text(_nowMs());
        }
    }

    /// <summary>The last minute's figures as text, for the clipboard.</summary>
    public string StatsHistory()
    {
        lock (_gate)
        {
            NoteVideo();
            return _stats.History(_nowMs());
        }
    }

    /// <summary>Under the lock: what the readout says about the video itself.</summary>
    private void NoteVideo()
    {
        _stats.Lost = _stream.Gaps;
        _stats.Video =
            _givenUp ? $"H.264 given up ({_givenUpWhy}). JPEG until the app is restarted."
            : _stream.WaitingForKeyframe ? $"Waiting for a keyframe · asked {_stream.KeyframesAsked} times"
            : _stream.KeyframesAsked > 0 ? $"Keyframes asked for: {_stream.KeyframesAsked}"
            : null;
    }

    /// <summary>The stream of the phone being viewed, for a test to look at.</summary>
    internal (ulong? Stream, bool DrawsType1, long Gaps) State
    {
        get { lock (_gate) return (_stream.Current, _stream.DrawsType1, _stream.Gaps); }
    }

    /// <summary>The video of the phone being viewed, for a test to look at.</summary>
    internal (bool Offered, bool Waiting, int Queued, int Failures, bool GivenUp) VideoState
    {
        get { lock (_gate) return (_stream.OffersH264, _stream.WaitingForKeyframe, _video.Count, _stream.Failures, _givenUp); }
    }

    /// <summary>Does what the feed's thread would do now: sends what has
    /// come due and decodes one picture that is waiting. False if there was
    /// nothing to do.</summary>
    internal bool Pump()
    {
        Work work;
        lock (_gate)
            if (!TakeWork(out work)) return false;
        Do(work);
        return true;
    }

    private void Run()
    {
        FindOutAboutH264();
        while (true)
        {
            Work work;
            lock (_gate)
            {
                while (true)
                {
                    if (_closed) return;
                    if (TakeWork(out work)) break;
                    // Asleep until a frame arrives, or until an acknowledgement
                    // that is owed may go, or until the next ping or the next
                    // request for a keyframe is due.
                    long now = _nowMs();
                    long? wait = _acks.DueIn(now);
                    if (_statsShown && _phoneId != null) wait = Math.Min(wait ?? long.MaxValue, _stats.PingDueIn(now));
                    if (_phoneId != null && _stream.KeyframeRequestDueIn(now) is { } ask) wait = Math.Min(wait ?? long.MaxValue, ask);
                    if (wait is { } ms) Monitor.Wait(_gate, (int)Math.Clamp(ms, 1, 1000));
                    else Monitor.Wait(_gate);
                }
            }
            Do(work);
        }
    }

    /// <summary>
    /// On the feed's thread, once: whether this Windows decodes H.264. If it
    /// does and a window got its start-view out first, the phone is asked
    /// again with video in the offer.
    /// </summary>
    private void FindOutAboutH264()
    {
        bool known;
        lock (_gate) known = _canDecode != null;
        if (known) return;
        bool can = false;
        try { can = H264Decoder.IsAvailable; }
        catch { /* then it cannot */ }
        JsonObject? again = null;
        lock (_gate)
        {
            _canDecode = can;
            if (can && !_closed && _phoneId != null && _offered && !_givenUp)
            {
                _stream.OffersH264 = true;
                again = StartView(_phoneId, offerCodecs: true, h264: true);
            }
        }
        if (again != null) Send(again);
    }

    private readonly record struct Work(PhonePicture? Picture, VideoFrame? Frame, JsonObject? Ack, JsonObject? Ping, JsonObject? Keyframe);

    /// <summary>Under the lock.</summary>
    private bool TakeWork(out Work work)
    {
        long now = _nowMs();
        var picture = _waiting;
        _waiting = null;
        VideoFrame? frame = null;
        if (_video.Count > 0)
        {
            frame = _video.Dequeue();
            _videoBytes -= frame.Value.Length;
        }
        JsonObject? ack = _phoneId != null ? _acks.Due(now)?.Message(_phoneId) : null;
        JsonObject? ping = null;
        if (_statsShown && _phoneId != null && _stats.PingDueIn(now) == 0)
            // The time is noted as it is handed to the socket, a line from here.
            ping = new JsonObject { ["type"] = "diagnostic-ping", ["to"] = _phoneId, ["nonce"] = _stats.Ping(now) };
        // Asked again every second for as long as no keyframe has come.
        JsonObject? keyframe = _phoneId != null && _stream.KeyframeRequestDue(now) ? _stream.KeyframeRequest(_phoneId) : null;
        work = new Work(picture, frame, ack, ping, keyframe);
        return picture != null || frame != null || ack != null || ping != null || keyframe != null;
    }

    private void Do(Work work)
    {
        if (work.Ping != null) Send(work.Ping);
        if (work.Ack != null) Send(work.Ack);
        if (work.Keyframe != null) Send(work.Keyframe);
        // Nothing here may end the thread: a picture that won't decode is one picture lost.
        try
        {
            if (work.Picture is { } waiting)
            {
                long started = Stopwatch.GetTimestamp();
                _draw(waiting);
                double ms = Stopwatch.GetElapsedTime(started).TotalMilliseconds;
                lock (_gate) if (_phoneId != null) _stats.Decoded(ms, _nowMs());
            }
        }
        catch (Exception ex)
        {
            Debug.WriteLine($"Phone feed: {ex.Message}");
        }
        if (work.Frame is { } frame) DecodeVideo(frame);
    }

    private void Send(JsonObject message)
    {
        try { _send(message); }
        catch (Exception ex) { Debug.WriteLine($"Phone feed: {ex.Message}"); }
    }

    /// <summary>
    /// The feed's thread, outside the lock: one video frame through the
    /// decoder, its colours converted, and the picture handed on.
    /// </summary>
    private void DecodeVideo(VideoFrame frame)
    {
        H264Decoder? decoder;
        lock (_gate)
        {
            // Of a stream that ended while it waited its turn.
            if (_stream.Current != frame.Header.Stream || _phoneId == null) return;
            decoder = _decoder;
        }
        string failure = "";
        H264Decoded result;
        PhoneVideoPicture? picture = null;
        long started = Stopwatch.GetTimestamp();
        try
        {
            if (decoder == null)
            {
                decoder = H264Decoder.TryCreate(out failure);
                lock (_gate)
                {
                    // Viewing may have stopped while it was being made.
                    if (decoder != null && (_closed || _phoneId == null || _stream.Current != frame.Header.Stream))
                    {
                        decoder.Dispose();
                        return;
                    }
                    _decoder = decoder;
                }
            }
            if (decoder == null) result = H264Decoded.Failed;
            else
            {
                if (frame.Restart) decoder.Reset();
                _decodedInto = null;
                result = decoder.Decode(frame.Data.AsSpan(frame.Offset, frame.Length), frame.Header.Seq, _onDecoded);
                picture = _decodedInto;
                _decodedInto = null;
                if (result == H264Decoded.Failed) failure = decoder.LastFailure;
            }
        }
        catch (Exception ex)
        {
            // Not expected from the decoder; counted like a failure of its own.
            result = H264Decoded.Failed;
            failure = ex.Message;
            picture = _decodedInto;
            _decodedInto = null;
        }
        double ms = Stopwatch.GetElapsedTime(started).TotalMilliseconds;
        WhileDecoding?.Invoke();

        JsonObject? keyframe = null, jpegOnly = null;
        bool show = false;
        lock (_gate)
        {
            // While it was in the decoder the stream may have ended, paused
            // or changed, or the window closed. Then this picture is nobody's.
            bool current = !_closed && _phoneId != null && _stream.Current == frame.Header.Stream;
            if (current)
            {
                long now = _nowMs();
                switch (result)
                {
                    case H264Decoded.Picture:
                        _stats.Decoded(ms, now);
                        show = picture != null;
                        break;
                    case H264Decoded.NeedsKeyframe:
                    case H264Decoded.Failed:
                        _stream.DecoderLost(failed: result == H264Decoded.Failed);
                        _video.Clear();
                        _videoBytes = 0;
                        if (_stream.GivenUp)
                        {
                            // Twice: this PC is not going to decode this. JPEG from here on.
                            _givenUp = true;
                            _givenUpWhy = failure.Length > 0 ? failure : "the decoder failed";
                            _stream.OffersH264 = false;
                            _decoder?.Dispose();
                            _decoder = null;
                            Video.Clear();
                            jpegOnly = StartView(_phoneId!, offerCodecs: true, h264: false);
                        }
                        else if (_stream.KeyframeRequestDue(now)) keyframe = _stream.KeyframeRequest(_phoneId!);
                        break;
                }
            }
            if (show) picture!.Header = frame.Header;
        }
        if (jpegOnly != null) Send(jpegOnly);
        if (keyframe != null) Send(keyframe);
        if (picture == null) return;
        if (!show || _drawVideo == null) { Video.Return(picture); return; }
        try { _drawVideo(picture); }
        catch (Exception ex)
        {
            Debug.WriteLine($"Phone feed: {ex.Message}");
            Video.Return(picture);
        }
    }

    /// <summary>The decoder's handler: the picture's colours are converted
    /// here, while its memory is still good.</summary>
    private unsafe void OnDecoded(in Nv12Picture decoded)
    {
        // More than one picture from one frame is not expected; the last is the one to show.
        if (_decodedInto != null) Video.Return(_decodedInto);
        var picture = Video.Rent(decoded.Width, decoded.Height);
        fixed (byte* target = picture.Bgra) Nv12Converter.ToBgra(decoded, target, picture.Stride);
        _decodedInto = picture;
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _closed = true;
            _waiting = null;
            DropVideo();
            _decoder?.Dispose();
            _decoder = null;
            Monitor.PulseAll(_gate);
        }
    }
}
