using System.Globalization;
using System.Text;

namespace RemoteDesktopWin;

/// <summary>
/// The figures behind the phone viewer's Stats readout (F8): how many
/// pictures arrive, how big they are, and how far behind the phone's screen
/// they are when they are drawn. It keeps the last minute, which Shift+F8
/// copies as text.
///
/// <para><b>How the delay is worked out.</b> A type 5 frame says when its
/// picture was captured, on the phone's clock (<c>captureMs</c>). The PC's
/// clock is another clock, so the difference between the two has to be
/// found first. Every two seconds while the readout is open the viewer sends
/// the phone a <c>diagnostic-ping</c> and notes when; the phone answers with
/// its own clock (<c>hostTimeMs</c>). The phone's time minus the time the ping
/// was sent is the difference between the clocks plus however long the ping
/// took to get there. The smallest such figure in the last minute is from the
/// ping that travelled fastest, and half the smallest round trip is taken off
/// it as that ping's travel time. Only the ping's own trip enters this, and
/// pings travel towards the phone, the direction pictures do not load: half
/// of a round trip measured through the phone's busy send queue would
/// understate the delay by half that queue.</para>
///
/// <para>Then: delay = (PC's clock when the picture is drawn + difference) −
/// <c>captureMs</c>, in 32-bit arithmetic that wraps as <c>captureMs</c>
/// does. It can be off by up to half the smallest round trip either way,
/// which is shown beside it as "±".</para>
///
/// No WPF and no clock of its own: every call is given the time, on the
/// steady clock the feed uses. <see cref="PhoneFeed"/> guards it.
/// </summary>
internal sealed class PhoneStats
{
    public const long PingEveryMs = 2000;
    public const long KeptMs = 60_000;

    private readonly Queue<(long At, int Bytes)> _arrived = new();
    private readonly Queue<(long At, uint CaptureMs)> _drawn = new();
    private readonly Queue<(long At, long Offset, long RoundTrip, long QueueBytes)> _pongs = new();
    private readonly Queue<(long At, double Ms)> _decoded = new();
    private readonly Dictionary<long, long> _pings = new();   // nonce -> when it was sent
    private long _nonce;
    private long? _pingedAt;

    private string? _capture, _codec;
    private (int W, int H) _size;
    private bool? _headers;     // whether the last picture came behind a header (type 5); null before the first

    /// <summary>Frames of the current stream that never arrived.</summary>
    public long Lost { get; set; }

    /// <summary>What the phone's <c>screen-info</c> says about how it captures and sends.</summary>
    public void Announce(string? capture, string? codec)
    {
        _capture = capture;
        _codec = codec;
    }

    /// <summary>The picture's size as <c>screen-info</c> gives it, for a
    /// phone whose frames do not say.</summary>
    public void Size(int width, int height)
    {
        if (width > 0 && height > 0) _size = (width, height);
    }

    /// <summary>A picture arrived. <paramref name="header"/> is null for a type 1 frame.</summary>
    public void Arrived(int bytes, PhoneFrameHeader? header, long nowMs)
    {
        _arrived.Enqueue((nowMs, bytes));
        _headers = header != null;
        if (header is { Width: > 0, Height: > 0 } h) _size = (h.Width, h.Height);
        Trim(nowMs);
    }

    /// <summary>A picture was put on show. Repeats are left out: they show
    /// a screen that was captured long before.</summary>
    public void Drawn(PhoneFrameHeader header, long nowMs)
    {
        if (header.Repeat) return;
        _drawn.Enqueue((nowMs, header.CaptureMs));
        Trim(nowMs);
    }

    /// <summary>A picture was turned from what the phone sent into pixels,
    /// and it took this long: a JPEG read, or a video frame decoded and its
    /// colours converted. The drawing itself is not in it.</summary>
    public void Decoded(double ms, long nowMs)
    {
        _decoded.Enqueue((nowMs, ms));
        Trim(nowMs);
    }

    /// <summary>The bitrate the phone says its encoder is aiming at, in bits
    /// a second: <c>bitrate</c> in its <c>diagnostic-pong</c>, which it sends
    /// only with video. Null when it has not said.</summary>
    public long? TargetBitrate { get; set; }

    /// <summary>A line about the video itself, when there is something to
    /// say: that a keyframe is waited for, or that H.264 was given up.</summary>
    public string? Video { get; set; }

    /// <summary>How long until the next ping is due; 0 when one is.</summary>
    public long PingDueIn(long nowMs) => _pingedAt is { } at ? Math.Max(0, at + PingEveryMs - nowMs) : 0;

    /// <summary>The nonce for a ping that is being sent now.</summary>
    public long Ping(long nowMs)
    {
        _pingedAt = nowMs;
        _pings[++_nonce] = nowMs;
        // One that was never answered is forgotten.
        foreach (var old in _pings.Where(p => nowMs - p.Value > KeptMs).Select(p => p.Key).ToList()) _pings.Remove(old);
        return _nonce;
    }

    /// <summary>The phone answered a ping. One that answers no ping of ours is ignored.</summary>
    public void Pong(long nonce, long hostTimeMs, long queueBytes, long nowMs)
    {
        if (!_pings.Remove(nonce, out long sentAt)) return;
        _pongs.Enqueue((nowMs, hostTimeMs - sentAt, nowMs - sentAt, queueBytes));
        Trim(nowMs);
    }

    private void Trim(long nowMs)
    {
        while (_arrived.Count > 0 && nowMs - _arrived.Peek().At >= KeptMs) _arrived.Dequeue();
        while (_drawn.Count > 0 && nowMs - _drawn.Peek().At >= KeptMs) _drawn.Dequeue();
        while (_pongs.Count > 0 && nowMs - _pongs.Peek().At >= KeptMs) _pongs.Dequeue();
        while (_decoded.Count > 0 && nowMs - _decoded.Peek().At >= KeptMs) _decoded.Dequeue();
    }

    /// <summary>The average and the worst decoding time between two moments, or null when nothing was decoded then.</summary>
    public (double Average, double Worst)? DecodeTime(long fromMs, long toMs)
    {
        var times = _decoded.Where(d => d.At >= fromMs && d.At < toMs).Select(d => d.Ms).ToList();
        return times.Count > 0 ? (times.Average(), times.Max()) : null;
    }

    private static string Ms(double ms) => ms.ToString(ms < 10 ? "0.0" : "0", CultureInfo.InvariantCulture);

    /// <summary>
    /// The phone's clock minus the PC's, and how far off that may be either
    /// way; null until a ping has been answered in the last minute.
    /// </summary>
    public (long Difference, long PlusMinus)? Clock(long nowMs)
    {
        Trim(nowMs);
        if (_pongs.Count == 0) return null;
        long travel = _pongs.Min(p => p.RoundTrip) / 2;
        return (_pongs.Min(p => p.Offset) - travel, travel);
    }

    /// <summary>How old a picture was when it was drawn, in ms. Both clocks
    /// are cut to 32 bits, as <c>captureMs</c> is, so the phone's clock
    /// wrapping round between the capture and the drawing changes nothing.</summary>
    public static int Delay(long drawnMs, long difference, uint captureMs) =>
        unchecked((int)((uint)(drawnMs + difference) - captureMs));

    /// <summary>The figures for a stretch of time: the last second for the
    /// readout, each second of the last minute and the whole of it for the copy.</summary>
    public readonly record struct Figures(int Pictures, long Bytes, int? DelayLatest, int? DelayAverage, int? DelayMedian,
        int? DelayWorst, long? RoundTrip, long? QueueBytes);

    /// <summary>What happened between <paramref name="fromMs"/> (inclusive)
    /// and <paramref name="toMs"/> (exclusive), with delays worked out from
    /// the clock difference as it is best known now.</summary>
    public Figures Between(long fromMs, long toMs, long nowMs)
    {
        var pictures = _arrived.Where(a => a.At >= fromMs && a.At < toMs).ToList();
        var pongs = _pongs.Where(p => p.At >= fromMs && p.At < toMs).ToList();
        int? latest = null, average = null, median = null, worst = null;
        if (Clock(nowMs) is { } clock)
        {
            var delays = _drawn.Where(d => d.At >= fromMs && d.At < toMs).Select(d => Delay(d.At, clock.Difference, d.CaptureMs)).ToList();
            if (delays.Count > 0)
            {
                latest = delays[^1];
                average = (int)Math.Round(delays.Average());
                worst = delays.Max();
                median = delays.Order().ElementAt(delays.Count / 2);
            }
        }
        return new Figures(pictures.Count, pictures.Sum(p => (long)p.Bytes), latest, average, median, worst,
            pongs.Count > 0 ? pongs[^1].RoundTrip : null, pongs.Count > 0 ? pongs.Max(p => p.QueueBytes) : null);
    }

    private string Mode() => _capture switch
    {
        "accessibility" => "Accessibility viewing",
        "screen-share" => "Smooth sharing",
        // A phone on an older app does not say.
        _ => "Capture not known",
    };

    private string Format() => (_codec ?? "jpeg").ToUpperInvariant() + (_headers == false ? ", type 1" : "");

    private string SizeText() => _size.W > 0 ? $"{_size.W} × {_size.H}" : "size not known";

    private static string Kb(long bytes) => (bytes / 1024.0).ToString(bytes < 10 * 1024 ? "0.0" : "0", CultureInfo.InvariantCulture);

    /// <summary>The readout: what the last second was like.</summary>
    public string Text(long nowMs)
    {
        var second = Between(nowMs - 1000, nowMs + 1, nowMs);
        var clock = Clock(nowMs);
        // The round trip and the phone's queue come every two seconds, so the last one is shown.
        var pong = _pongs.Count > 0 ? _pongs.Last() : ((long At, long Offset, long RoundTrip, long QueueBytes)?)null;
        var text = new StringBuilder();
        text.Append(Mode()).Append(" · ").Append(Format()).Append(" · ").AppendLine(SizeText());
        text.Append(second.Pictures).Append(second.Pictures == 1 ? " picture/s · " : " pictures/s · ")
            .Append((second.Bytes * 8 / 1000).ToString(CultureInfo.InvariantCulture)).AppendLine(" kbit/s");
        if (_headers == false)
            // Type 1 frames carry no capture time: from a phone on an older app, or one that has an older viewer too.
            text.AppendLine("Delay: not known. These pictures don't say when they were taken.");
        else if (clock == null)
            text.AppendLine("Delay: measuring");
        else if (second.DelayLatest == null)
            text.AppendLine($"Delay: no new picture in the last second (± {clock.Value.PlusMinus} ms)");
        else
            text.AppendLine($"Delay {second.DelayLatest} ms · average {second.DelayAverage} · worst {second.DelayWorst} (± {clock.Value.PlusMinus} ms)");
        text.Append("Round trip ").Append(pong is { } p ? $"{p.RoundTrip} ms" : "not known")
            .Append(" · phone's queue ").AppendLine(pong is { } q ? $"{Kb(q.QueueBytes)} KB" : "not known");
        if (TargetBitrate is { } target)
            text.Append("Phone's encoder aims at ").Append((target / 1000).ToString(CultureInfo.InvariantCulture)).AppendLine(" kbit/s");
        // Of the last second, or of the last pictures there were if that second had none.
        var decode = DecodeTime(nowMs - 1000, nowMs + 1)
            ?? (_decoded.Count > 0 ? DecodeTime(_decoded.Last().At - 1000, nowMs + 1) : null);
        if (decode is { } d)
            text.Append("Decode ").Append(Ms(d.Average)).Append(" ms a picture · worst ").AppendLine(Ms(d.Worst));
        if (Video is { } video) text.AppendLine(video);
        text.Append("Frames lost: ").Append(Lost);
        return text.ToString();
    }

    /// <summary>The last minute as text, for pasting somewhere: one line
    /// for the whole of it, then one for each second, newest last.</summary>
    public string History(long nowMs)
    {
        var all = Between(nowMs - KeptMs, nowMs + 1, nowMs);
        var clock = Clock(nowMs);
        // Counted over the time there is, so a readout opened ten seconds ago is not averaged over sixty.
        long oldest = new[] { _arrived.Count > 0 ? _arrived.Peek().At : nowMs, _pongs.Count > 0 ? _pongs.Peek().At : nowMs }.Min();
        double seconds = Math.Clamp((nowMs - oldest) / 1000.0, 1, KeptMs / 1000.0);
        var text = new StringBuilder();
        text.AppendLine($"Remote Desktop phone viewer, the last {Math.Round(seconds)} s");
        text.Append(Mode()).Append(" · ").Append(Format()).Append(" · ").AppendLine(SizeText());
        text.Append("Pictures/s ").Append((all.Pictures / seconds).ToString("0.0", CultureInfo.InvariantCulture));
        text.Append(" · KB per picture ").Append(all.Pictures > 0 ? Kb(all.Bytes / all.Pictures) : "-");
        text.Append(" · delay median / worst (ms) ").Append(all.DelayMedian is { } m ? $"{m} / {all.DelayWorst}" : "not known");
        if (clock is { } c) text.Append($" (± {c.PlusMinus})");
        text.Append(" · round trip (ms) ").Append(_pongs.Count > 0 ? _pongs.Min(p => p.RoundTrip).ToString(CultureInfo.InvariantCulture) : "not known");
        text.Append(" · phone's queue, worst (KB) ").Append(all.QueueBytes is { } queue ? Kb(queue) : "not known");
        text.Append(" · frames lost ").Append(Lost);
        if (DecodeTime(nowMs - KeptMs, nowMs + 1) is { } decode)
            text.Append(" · decode average / worst (ms) ").Append(Ms(decode.Average)).Append(" / ").Append(Ms(decode.Worst));
        text.AppendLine();
        if (Video is { } video) text.AppendLine(video);
        text.AppendLine();
        text.AppendLine("s ago\tpictures\tkbit/s\tdelay avg (ms)\tdelay worst (ms)\tround trip (ms)\tqueue (KB)");
        for (int ago = (int)Math.Ceiling(seconds); ago >= 1; ago--)
        {
            var s = Between(nowMs - ago * 1000L, ago == 1 ? nowMs + 1 : nowMs - (ago - 1) * 1000L, nowMs);
            text.Append(ago).Append('\t').Append(s.Pictures).Append('\t').Append(s.Bytes * 8 / 1000).Append('\t')
                .Append(s.DelayAverage?.ToString(CultureInfo.InvariantCulture) ?? "-").Append('\t')
                .Append(s.DelayWorst?.ToString(CultureInfo.InvariantCulture) ?? "-").Append('\t')
                .Append(s.RoundTrip?.ToString(CultureInfo.InvariantCulture) ?? "-").Append('\t')
                .AppendLine(s.QueueBytes is { } waiting ? Kb(waiting) : "-");
        }
        return text.ToString();
    }

    /// <summary>Another phone, or the same one asked afresh: nothing measured before applies.</summary>
    public void Reset()
    {
        _arrived.Clear();
        _drawn.Clear();
        _pongs.Clear();
        _pings.Clear();
        _decoded.Clear();
        Video = null;
        TargetBitrate = null;
        _pingedAt = null;
        _capture = _codec = null;
        _size = default;
        _headers = null;
        Lost = 0;
    }
}
