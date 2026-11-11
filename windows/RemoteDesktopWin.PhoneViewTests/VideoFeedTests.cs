using System.Text.Json.Nodes;
using RemoteDesktopWin;
using Clip = H264Tests.Clip;

// A phone's screen arriving as video: the rules for when a frame may be
// decoded and when a keyframe is asked for (PhoneVideoStream.cs), the hand-over
// of decoded pictures to the window (PhoneVideoPictures.cs), and the feed
// that ties them to the decoder (PhoneFeed.cs).
//
// The rules and the hand-over are plain and run anywhere. The feed's checks
// put real video through it, made on the spot from a drawn pattern as in
// H264Tests.cs, and say SKIP where Windows has no H.264 decoder or encoder.
internal static class VideoFeedTests
{
    private const ulong StreamA = 0xaa, StreamB = 0xbb;
    private const string IdA = "00000000000000aa", IdB = "00000000000000bb";

    public static void Run()
    {
        CheckWhenAFrameIsDecoded();
        CheckAskingForAKeyframe();
        CheckKeyframesByWhatTheyHold();
        CheckAPhoneThatSendsNoKeyframe();
        CheckGivingUp();
        CheckPictureHandOver();
        CheckOffer();
        CheckReadout();

        if (!H264Tests.CodecsAreHere("the feed's video checks", out var kinds)) return;
        foreach (bool hardware in kinds)
        {
            _say = hardware == kinds[^1];
            Clip clip = Clip.Make(hardware, 320, 240, 12) ?? throw new Exception("setup: no 320 x 240 stream");
            Clip again = Clip.Make(hardware, 320, 240, 12) ?? throw new Exception("setup: no second 320 x 240 stream");
            Clip turned = Clip.Make(hardware, 242, 322, 6) ?? throw new Exception("setup: no 242 x 322 stream");
            CheckFeedShowsVideo(clip);
            CheckFeedAfterALostFrame(clip, again);
            CheckFeedComingInPartWay(clip);
            CheckFeedGoesByTheBytes(clip);
            CheckFeedGivesUpOnAPhoneThatSendsNoKeyframe(clip);
            CheckFeedFallingBehind(clip, again);
            CheckFeedDecoderFailure(clip);
            CheckFeedWaitsForALatePicture(clip);
            CheckFeedWithoutADecoder(clip);
            CheckFeedTurnedPhoneAndNewStreams(clip, turned);
            CheckFeedWhileAFrameIsDecoded(clip);
            CheckFeedOnItsOwnThread(clip);
        }
        _say = true;
    }

    private static bool _say = true;

    private static void Pass(string what)
    {
        if (_say) Console.WriteLine("PASS: " + what);
    }

    private static PhoneFrameHeader Video(ulong stream, uint seq, bool keyframe, int w = 320, int h = 240, bool repeat = false) =>
        new(stream, seq, keyframe, PhoneFrame.H264, false, repeat, w, h, 0);

    // ---------------- the rules ----------------

    private static void CheckWhenAFrameIsDecoded()
    {
        var stream = new PhoneVideoStream { OffersH264 = true };
        stream.Announce(StreamA, false);
        Check(stream.Take(Video(StreamA, 0, keyframe: true), 100, 0) == FrameVerdict.Restart, "A stream's first frame, a keyframe, starts the decoder afresh");
        Check(stream.Take(Video(StreamA, 1, keyframe: false), 100, 10) == FrameVerdict.Decode, "The frame after it is decoded");
        Check(stream.Take(Video(StreamA, 2, keyframe: true), 100, 20) == FrameVerdict.Decode, "A keyframe in the middle of a healthy stream is just the next frame");
        Check(!stream.WaitingForKeyframe && stream.KeyframeRequestDueIn(20) == null, "Nothing is asked of a healthy stream");
        Check(stream.Take(Video(StreamA, 2, keyframe: false), 100, 30) == FrameVerdict.Refuse, "A number seen before is refused, as for JPEG");

        // A frame is lost on the way.
        Check(stream.Take(Video(StreamA, 4, keyframe: false), 100, 40) == FrameVerdict.Skip, "The frame after a missing one is passed over: it leans on the one that never came");
        Check(stream.WaitingForKeyframe && stream.Gaps == 1, "And a keyframe is waited for");
        Check(stream.Take(Video(StreamA, 5, keyframe: false), 100, 50) == FrameVerdict.Skip, "So is every frame until one comes");
        Check(stream.Take(Video(StreamA, 6, keyframe: true), 100, 60) == FrameVerdict.Restart, "The keyframe starts the decoder afresh");
        Check(!stream.WaitingForKeyframe && stream.Take(Video(StreamA, 7, keyframe: false), 100, 70) == FrameVerdict.Decode, "And the stream is decoded again");
        // The frame lost was a keyframe's neighbour: a gap straight before a keyframe costs nothing.
        Check(stream.Take(Video(StreamA, 9, keyframe: true), 100, 80) == FrameVerdict.Restart && !stream.WaitingForKeyframe,
            "A keyframe that follows a gap needs nothing that was lost");

        // A JPEG is a whole picture whatever came before.
        var jpeg = new PhoneFrameHeader(StreamA, 10, true, PhoneFrame.Jpeg, false, false, 320, 240, 0);
        Check(stream.Take(jpeg, 100, 90) == FrameVerdict.Draw, "A JPEG is drawn as it always was");

        // Coming into a stream part way: its first frame here is no keyframe.
        var late = new PhoneVideoStream { OffersH264 = true };
        late.Announce(StreamB, false);
        Check(!late.WaitingForKeyframe && late.KeyframeRequestDueIn(0) == null, "A stream just announced is not asked for anything yet");
        Check(late.Take(Video(StreamB, 40, keyframe: false), 100, 0) == FrameVerdict.Skip && late.WaitingForKeyframe,
            "A stream that begins here without a keyframe is passed over, and one is waited for");
        Check(late.Gaps == 0, "Frames from before this viewer came are not counted as lost");

        // A format that was not offered is never drawn, and nothing is asked about it.
        var jpegOnly = new PhoneVideoStream();
        jpegOnly.Announce(StreamA, false);
        Check(jpegOnly.Take(Video(StreamA, 0, keyframe: true), 100, 0) == FrameVerdict.Skip &&
            jpegOnly.Take(Video(StreamA, 2, keyframe: false), 100, 10) == FrameVerdict.Skip,
            "Video that was not offered is not decoded");
        Check(!jpegOnly.WaitingForKeyframe && jpegOnly.KeyframeRequestDueIn(10) == null && !jpegOnly.KeyframeRequestDue(10),
            "And no keyframe is asked for on its account");

        // Another stream waits for its own first keyframe.
        stream.Announce(StreamB, false);
        Check(stream.Take(Video(StreamB, 0, keyframe: true), 100, 100) == FrameVerdict.Restart, "A new stream's first keyframe starts the decoder afresh");
        Console.WriteLine("PASS: video frames are decoded in order from a keyframe, and passed over while the chain is broken");
    }

    private static void CheckAskingForAKeyframe()
    {
        var stream = new PhoneVideoStream { OffersH264 = true };
        stream.Announce(StreamA, false);
        stream.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        Check(!stream.KeyframeRequestDue(0), "Nothing is asked while all is well");
        stream.Take(Video(StreamA, 2, keyframe: false), 100, 1000);
        Check(stream.KeyframeRequestDueIn(1000) == 0 && stream.KeyframeRequestDue(1000), "A keyframe is asked for the moment a frame is found missing");
        Check(stream.KeyframeRequest("7").ToJsonString() == """{"type":"request-keyframe","to":"7","stream":"00000000000000aa"}""",
            "The request names the phone and the stream: " + stream.KeyframeRequest("7").ToJsonString());
        Check(!stream.KeyframeRequestDue(1001) && !stream.KeyframeRequestDue(1999) && stream.KeyframeRequestDueIn(1400) == 600, "Not again within the second");
        Check(stream.KeyframeRequestDue(2000), "Again after a second, if none has come");
        Check(!stream.KeyframeRequestDue(2500) && stream.KeyframeRequestDue(3000) && stream.KeyframesAsked == 3, "And every second after that");
        stream.Take(Video(StreamA, 3, keyframe: true), 100, 3100);
        Check(!stream.KeyframeRequestDue(4100) && stream.KeyframeRequestDueIn(4100) == null, "Once the keyframe has come nothing more is asked");

        // Another break straight after: the pace holds across the two.
        stream.Take(Video(StreamA, 5, keyframe: false), 100, 3200);
        Check(!stream.KeyframeRequestDue(3200) && stream.KeyframeRequestDueIn(3200) == 800 && stream.KeyframeRequestDue(4000),
            "A second break is not asked about sooner than a second after the last request");

        // The decoder could not use a frame; frames were thrown away for lack of time.
        var decoder = new PhoneVideoStream { OffersH264 = true };
        decoder.Announce(StreamA, false);
        decoder.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        decoder.DecoderLost(failed: false);
        Check(decoder.WaitingForKeyframe && decoder.KeyframeRequestDue(10) && decoder.Failures == 0,
            "A frame that was not the keyframe its header said is asked about, and is nobody's failure");
        Check(decoder.Take(Video(StreamA, 1, keyframe: false), 100, 20) == FrameVerdict.Skip, "Frames are passed over until the keyframe");
        decoder.Take(Video(StreamA, 2, keyframe: true), 100, 30);
        decoder.Overflowed();
        Check(decoder.WaitingForKeyframe && decoder.KeyframeRequestDue(1010), "Frames thrown away because the PC fell behind are asked about too");
        Console.WriteLine("PASS: a keyframe is asked for at once when the chain breaks, and once a second until it comes");
    }

    private static void CheckKeyframesByWhatTheyHold()
    {
        // The caller has looked at the bytes. The bit in the header is the phone's word, and is not gone by.
        var stream = new PhoneVideoStream { OffersH264 = true };
        stream.Announce(StreamA, false);
        Check(stream.Take(Video(StreamA, 0, keyframe: false), 100, 0, VideoKind.Keyframe) == FrameVerdict.Restart,
            "A frame that holds a keyframe starts the stream, though its header does not say so");
        Check(stream.Take(Video(StreamA, 1, keyframe: true), 100, 10, VideoKind.Ordinary) == FrameVerdict.Decode,
            "A frame that holds none is the next ordinary frame, whatever its header says");
        Check(stream.Take(Video(StreamA, 3, keyframe: true), 100, 20, VideoKind.Ordinary) == FrameVerdict.Skip && stream.WaitingForKeyframe,
            "After a gap a frame that only says it is a keyframe does not end the wait");
        Check(stream.Take(Video(StreamA, 4, keyframe: false), 100, 30, VideoKind.Keyframe) == FrameVerdict.Restart && !stream.WaitingForKeyframe,
            "A frame that is one does");

        // Parameter sets in a frame of their own, as an Android encoder hands them out.
        var split = new PhoneVideoStream { OffersH264 = true };
        split.Announce(StreamA, false);
        Check(split.Take(Video(StreamA, 0, keyframe: false), 20, 0, VideoKind.ParameterSets) == FrameVerdict.Skip, "Parameter sets alone are nothing to decode");
        Check(!split.WaitingForKeyframe && split.KeyframeRequestDueIn(0) == null, "And no reason to ask for a keyframe: one is about to follow");
        Check(split.Take(Video(StreamA, 1, keyframe: true), 100, 10, VideoKind.Keyframe) == FrameVerdict.Restart, "The keyframe behind them starts the stream");
        Check(split.Take(Video(StreamA, 2, keyframe: false), 20, 20, VideoKind.ParameterSets) == FrameVerdict.Skip && !split.WaitingForKeyframe,
            "Parameter sets in the middle of a healthy stream break nothing");
        Check(split.Take(Video(StreamA, 3, keyframe: false), 100, 30, VideoKind.Ordinary) == FrameVerdict.Decode, "The frame after them is decoded");
        Check(split.Take(Video(StreamA, 5, keyframe: false), 20, 40, VideoKind.ParameterSets) == FrameVerdict.Skip && split.WaitingForKeyframe,
            "A gap in front of them is still a gap");

        // The parameter sets of an access unit, for keeping.
        byte[] unit = { 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x06, 9, 0, 0, 0, 1, 0x65, 7, 7 };
        Check(H264Decoder.ParameterSets(unit) is { } sets && sets.AsSpan().SequenceEqual(new byte[] { 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3 }),
            "The SPS and PPS of an access unit are picked out, start codes and all");
        Check(H264Decoder.ParameterSets(new byte[] { 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x65, 3 }) == null, "An SPS without a PPS is not a pair to keep");
        Check(H264Decoder.ParameterSets(new byte[] { 0, 0, 1, 0x41, 3 }) == null && H264Decoder.ParameterSets(ReadOnlySpan<byte>.Empty) == null,
            "An ordinary frame has none");
        // Put back in front of a picture that came without: behind the delimiter, if there is one.
        byte[] kept = { 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2 };
        Check(H264Decoder.WithParameterSets(new byte[] { 0, 0, 0, 1, 0x65, 7 }, kept).AsSpan().SequenceEqual(new byte[] { 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 7 }),
            "Parameter sets go in front of a picture that has none");
        Check(H264Decoder.WithParameterSets(new byte[] { 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x65, 7 }, kept).AsSpan()
                .SequenceEqual(new byte[] { 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 7 }),
            "And behind the delimiter of one that starts with a delimiter, which has to stay first");
        Check(H264Decoder.WithParameterSets(new byte[] { 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x65, 7 }, kept).AsSpan()
                .SequenceEqual(new byte[] { 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 7 }),
            "With short start codes too");
        Console.WriteLine("PASS: a keyframe is known by what the frame holds, not by the bit in its header");
    }

    private static void CheckAPhoneThatSendsNoKeyframe()
    {
        // The chain breaks, and the phone goes on sending frames that are no keyframe, whatever it is asked.
        var stream = new PhoneVideoStream { OffersH264 = true };
        stream.Announce(StreamA, false);
        stream.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        uint seq = 2;
        stream.Take(Video(StreamA, seq++, keyframe: false), 100, 1000);
        int asked = 0;
        long gaveUpAt = -1;
        for (long now = 1000; now <= 9000 && gaveUpAt < 0; now += 250)
        {
            stream.Take(Video(StreamA, seq++, keyframe: false), 100, now);
            if (stream.KeyframeRequestDue(now)) asked++;
            if (stream.GivenUp) gaveUpAt = now;
        }
        Check(asked == PhoneVideoStream.UnansweredToGiveUp && gaveUpAt == 6000,
            $"After five requests that brought frames and no keyframe, video is given up: asked {asked} times, gave up at {gaveUpAt}");
        Check(stream.KeyframeRequestDueIn(7000) == null && !stream.KeyframeRequestDue(7000), "And nothing more is asked");
        Check(stream.Failures == 0, "It is the phone that did not answer: no failure of the decoder is counted");

        // A keyframe that comes in time wipes the slate.
        var answered = new PhoneVideoStream { OffersH264 = true };
        answered.Announce(StreamA, false);
        answered.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        seq = 2;
        for (int round = 0; round < 4; round++)
        {
            long start = 10_000 * (round + 1);
            for (long now = start; now < start + 3000; now += 250)
            {
                answered.Take(Video(StreamA, seq++, keyframe: false), 100, now);
                answered.KeyframeRequestDue(now);
            }
            Check(answered.Unanswered == 2 && !answered.GivenUp, $"setup: two requests unanswered so far in wait {round}");
            answered.Take(Video(StreamA, seq++, keyframe: true), 100, start + 3000);
            Check(answered.Unanswered == 0, "A keyframe that comes after the third request wipes the count");
            seq++;   // the next frame will be found missing
        }
        Check(!answered.GivenUp, "A phone that answers late, time and again, is not given up");

        // Nothing arrives at all: the connection is the trouble, and video is not given up for it.
        var silent = new PhoneVideoStream { OffersH264 = true };
        silent.Announce(StreamA, false);
        silent.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        silent.Take(Video(StreamA, 2, keyframe: false), 100, 1000);
        asked = 0;
        for (long now = 1000; now <= 30_000; now += 500) if (silent.KeyframeRequestDue(now)) asked++;
        Check(asked == 30 && !silent.GivenUp && silent.Unanswered <= 1, $"With nothing arriving, a keyframe is asked for every second and video is kept: asked {asked}, unanswered {silent.Unanswered}");

        // A phone that starts a new stream every time and still sends no keyframe does not start the count afresh.
        var restless = new PhoneVideoStream { OffersH264 = true };
        ulong id = 1;
        for (long now = 0; now <= 12_000 && !restless.GivenUp; now += 250)
        {
            if (now % 1500 == 0) restless.Announce(++id, false);
            restless.Take(Video(id, (uint)now, keyframe: false), 100, now);
            restless.KeyframeRequestDue(now);
        }
        Check(restless.GivenUp, "Starting stream after stream without a keyframe is given up as well");
        Console.WriteLine("PASS: a phone that goes on sending and never sends a keyframe is given up after five requests, and a dead connection is not");
    }

    private static void CheckGivingUp()
    {
        var stream = new PhoneVideoStream { OffersH264 = true };
        stream.Announce(StreamA, false);
        stream.Take(Video(StreamA, 0, keyframe: true), 100, 0);
        stream.DecoderLost(failed: true);
        Check(stream.Failures == 1 && !stream.GivenUp && stream.WaitingForKeyframe, "One failure of the decoder is answered with a request for a keyframe");
        stream.Take(Video(StreamA, 1, keyframe: true), 100, 1000);
        stream.Announce(StreamB, false);
        Check(stream.Failures == 1, "Another stream of the same phone does not wipe the slate");
        stream.Take(Video(StreamB, 0, keyframe: true), 100, 2000);
        stream.DecoderLost(failed: true);
        Check(stream.GivenUp, "A second failure, and the decoder is given up");
        stream.Forget();
        Check(!stream.GivenUp && stream.Failures == 0 && stream.KeyframesAsked == 0, "The count is of one viewing; the feed remembers the giving up");
        Console.WriteLine("PASS: a decoder that fails twice while a phone is viewed is given up");
    }

    // ---------------- the hand-over ----------------

    private static void CheckPictureHandOver()
    {
        var pictures = new PhoneVideoPictures();
        Check(pictures.Take() == null, "Nothing waits at first");
        var first = pictures.Rent(320, 240);
        Check(first.Bgra.Length == 320 * 240 * 4 && first.Width == 320 && first.Height == 240 && first.Stride == 1280, "A rented picture is the size asked for");
        first.Header = Video(StreamA, 0, true);
        first.Black = true;
        pictures.Publish(first);
        var second = pictures.Rent(320, 240);
        Check(!ReferenceEquals(first, second), "A picture that is waiting to be drawn is not handed out again");
        pictures.Publish(second);
        Check(pictures.Skipped == 1, "A finished picture the window has not drawn is replaced by the next, and counted");
        var taken = pictures.Take();
        Check(ReferenceEquals(taken, second) && pictures.Take() == null, "The window is given the newest, once");

        // The array of the picture that was passed over is used again, and so is one the window gives back.
        var third = pictures.Rent(320, 240);
        Check(ReferenceEquals(third, first) && third.Black == null && third.Header == default, "A picture is used again, wiped of what it said before");
        pictures.Return(taken!);
        var fourth = pictures.Rent(320, 240);
        Check(ReferenceEquals(fourth, second), "A picture the window has drawn is used again");
        pictures.Return(third);
        pictures.Return(fourth);

        // The phone is turned: as many bytes the other way up, so the same arrays serve.
        var turned = pictures.Rent(240, 320);
        Check(turned.Width == 240 && turned.Height == 320 && (ReferenceEquals(turned, third) || ReferenceEquals(turned, fourth)),
            "A picture turned on its side goes into an array there already is");
        // Another size altogether: the old ones are let go.
        var wide = pictures.Rent(400, 300);
        Check(wide.Bgra.Length == 400 * 300 * 4 && wide.Width == 400, "Another size gets an array of its own");
        pictures.Return(turned);
        pictures.Return(wide);

        // Viewing stops with one picture waiting and one out being filled.
        var waiting = pictures.Rent(400, 300);
        pictures.Publish(waiting);
        var filling = pictures.Rent(400, 300);
        pictures.Clear();
        Check(pictures.Take() == null, "When viewing stops the picture that was waiting is not drawn");
        pictures.Publish(filling);
        Check(pictures.Take() == null, "Nor one that was still being decoded then");
        pictures.Return(filling);
        Check(!ReferenceEquals(pictures.Rent(400, 300), filling), "And pictures from before are not kept");

        // No more than three are held on to.
        var many = Enumerable.Range(0, 6).Select(_ => pictures.Rent(64, 64)).ToList();
        foreach (var one in many) pictures.Return(one);
        var back = Enumerable.Range(0, 6).Select(_ => pictures.Rent(64, 64)).ToList();
        Check(back.Count(many.Contains) == 3, "At most three pictures are kept for use again");
        Console.WriteLine("PASS: decoded pictures reach the window newest first, in arrays that are used again");
    }

    // ---------------- what is offered ----------------

    private static void CheckOffer()
    {
        Check(PhoneFeed.StartView("7", offerCodecs: true, h264: true).ToJsonString() == """{"type":"start-view","to":"7","codecs":["h264","jpeg"]}""",
            "Video is offered beside JPEG");
        Check(PhoneFeed.StartView("7", offerCodecs: true, h264: false).ToJsonString() == """{"type":"start-view","to":"7","codecs":["jpeg"]}""",
            "Without video the offer is what it was");
        Check(PhoneFeed.StartView("7", offerCodecs: false, h264: true).ToJsonString() == """{"type":"start-view","to":"7"}""",
            "A phone that is offered nothing is not offered video");

        var can = new VideoRig(canDecode: true);
        Check(can.Feed.View("7", offered: true), "A feed on a Windows that decodes H.264 offers it to an approved phone");
        Check(!can.Feed.View("7", offered: false), "And never to a phone this PC has not approved, whose word about its stream is not taken");
        var cannot = new VideoRig(canDecode: false);
        Check(!cannot.Feed.View("7", offered: true), "A feed on a Windows without a decoder offers JPEG only");
        var noVideo = new PhoneFeed(_ => { }, _ => { }, () => 0, ownThread: false);
        Check(!noVideo.View("7", offered: true), "A feed with nowhere to show video offers none");
        Console.WriteLine("PASS: video is offered only to an approved phone, and only where it can be decoded and shown");
    }

    private static void CheckReadout()
    {
        var stats = new PhoneStats();
        stats.Announce("screen-share", "h264");
        stats.Arrived(4000, Video(StreamA, 0, true, 1080, 2340), 9_500);
        stats.Decoded(9.2, 9_500);
        stats.Decoded(3.1, 9_600);
        stats.Decoded(3.3, 9_700);
        string text = stats.Text(10_000);
        Check(text.StartsWith("Smooth sharing · H264 · 1080 × 2340"), "The readout names the codec:\n" + text);
        Check(text.Contains("Decode 5.2 ms a picture · worst 9.2"), "And says how long decoding takes:\n" + text);
        Check(text.EndsWith("Frames lost: 0"), "Lost frames stay the last line:\n" + text);
        Check(stats.Text(30_000).Contains("Decode 5.2 ms a picture"), "On a still screen the last pictures' time stays on show");
        stats.Video = "Waiting for a keyframe · asked 2 times";
        Check(stats.Text(10_000).Contains("\nWaiting for a keyframe · asked 2 times" + Environment.NewLine + "Frames lost: 0"), "A line says when a keyframe is waited for");
        Check(stats.History(10_000).Contains("decode average / worst (ms) 5.2 / 9.2") && stats.History(10_000).Contains("Waiting for a keyframe"),
            "The copied figures have the decoding time too");
        Check(!new PhoneStats().Text(0).Contains("Decode"), "A readout with nothing decoded says nothing of it");
        Console.WriteLine("PASS: the readout names the codec and shows how long a picture takes to decode");
    }

    // ---------------- the feed, with real video ----------------

    /// <summary>A feed with a clock that is set by hand and no thread of its
    /// own, and everything that comes out of it.</summary>
    private sealed class VideoRig
    {
        public long Now;
        public readonly List<PhonePicture> Jpegs = [];
        public readonly List<(PhoneFrameHeader Header, int Width, int Height, byte[] Bgra)> Shown = [];
        public readonly List<string> Sent = [];
        public readonly List<(string Phone, string Why)> GaveUp = [];
        public readonly PhoneFeed Feed;
        /// <summary>The decoder the feed last made, for a check to make late.</summary>
        public H264Decoder? Decoder;
        /// <summary>How many calls each new decoder is to be late for.</summary>
        public int HoldBack;

        public VideoRig(bool canDecode = true)
        {
            Feed = new PhoneFeed(Jpegs.Add, msg => Sent.Add(msg.ToJsonString()), () => Now, ownThread: false,
                gaveUp: (phone, why) => GaveUp.Add((phone, why)),
                drawVideo: picture =>
                {
                    Shown.Add((picture.Header, picture.Width, picture.Height, picture.Bgra.AsSpan(0, picture.Stride * picture.Height).ToArray()));
                    // As the main window does: published, then taken and given back by the window's thread.
                    Feed!.Video.Publish(picture);
                    if (Feed.Video.Take() is { } taken) Feed.Video.Return(taken);
                },
                canDecodeH264: canDecode);
            Feed.DecoderMade = decoder =>
            {
                // A second is what the app allows a keyframe's picture; the checks that break keyframes need not sit it out.
                decoder.LatePictureLimitMs = 150;
                decoder.TestHoldBackCalls = HoldBack;
                Decoder = decoder;
            };
        }

        public void Info(string from, string? stream, string codec = "h264", bool paused = false)
        {
            var msg = new JsonObject { ["type"] = "screen-info", ["from"] = from, ["width"] = 320, ["height"] = 240 };
            if (stream != null) { msg["stream"] = stream; msg["codec"] = codec; msg["capture"] = "screen-share"; }
            if (paused) msg["paused"] = true;
            Feed.OnScreenInfo(msg);
        }

        /// <summary>A frame of video as the phone sends it. The keyframe flag is set as the phone would set it.</summary>
        public void Frame(ulong stream, uint seq, byte[] unit, int w = 320, int h = 240, bool? keyframe = null)
        {
            bool key = keyframe ?? (H264Decoder.Scan(unit) & H264Decoder.Units.Keyframe) == H264Decoder.Units.Keyframe;
            Feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(VideoFeedTests.Video(stream, seq, key, w, h), unit)]);
        }

        public void Jpeg(ulong stream, uint seq, params byte[] picture) =>
            Feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(new PhoneFrameHeader(stream, seq, true, PhoneFrame.Jpeg, false, false, 320, 240, 0), picture)]);

        /// <summary>
        /// Everything the feed's thread would do by now. Returns how many
        /// video pictures it showed. A picture the decoder owes is waited
        /// for, as the thread would between frames: the hand-set clock is
        /// moved on a little for that and put back.
        /// </summary>
        public int Pump()
        {
            int before = Shown.Count;
            while (Feed.Pump()) { }
            long saved = Now;
            var real = System.Diagnostics.Stopwatch.StartNew();
            while (Feed.VideoState.Owed && real.ElapsedMilliseconds < 3000)
            {
                Thread.Sleep(1);
                Now += 2;
                while (Feed.Pump()) { }
            }
            Now = saved;
            return Shown.Count - before;
        }

        /// <summary>Only what is due at this moment: a picture that is owed stays owed.</summary>
        public int PumpOnce()
        {
            int before = Shown.Count;
            while (Feed.Pump()) { }
            return Shown.Count - before;
        }

        public int Count(string type) => Sent.Count(m => m.Contains($"\"type\":\"{type}\""));
        public List<string> Of(string type) => Sent.Where(m => m.Contains($"\"type\":\"{type}\"")).ToList();
    }

    private static VideoRig Viewing(string stream = IdA)
    {
        var rig = new VideoRig();
        Check(rig.Feed.View("7", offered: true), "setup: video is offered");
        rig.Info("7", stream);
        return rig;
    }

    private static void CheckFeedShowsVideo(Clip clip)
    {
        var rig = Viewing();
        for (int i = 0; i < clip.Units.Count; i++)
        {
            rig.Now = 1000 + i * 33;
            rig.Frame(StreamA, (uint)i, clip.Units[i]);
        }
        Check(rig.Shown.Count == 0, "The thread the frames arrive on decodes nothing");
        Check(rig.Feed.VideoState.Queued == clip.Units.Count, "Every frame waits its turn: none is dropped for a newer one");
        Check(rig.Count("frame-ack") >= 1 && rig.Sent[0].Contains("\"seq\":0"), "Frames are acknowledged as they arrive, before they are decoded");
        Check(rig.Feed.Pump() && rig.Shown.Count == 1 && rig.Feed.VideoState.Queued == clip.Units.Count - 1, "The feed's thread decodes one frame at a time");
        rig.Now = 2000;
        rig.Pump();
        Check(rig.Shown.Count == clip.Units.Count, $"Every frame becomes a picture: {rig.Shown.Count} of {clip.Units.Count}");
        for (int i = 0; i < rig.Shown.Count; i++)
        {
            var shown = rig.Shown[i];
            Check(shown.Header.Seq == i && shown.Header.Stream == StreamA, $"Pictures come out in the order the frames went in ({i}: {shown.Header.Seq})");
            Check(H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height) == i,
                $"And each is its own frame's picture: the one numbered {i} shows frame {H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height)}");
            Check(shown.Width == clip.Width && shown.Height == clip.Height, "At the size of the stream");
            H264Tests.CheckPatches(shown.Bgra, clip.Width, clip.Height, clip.ReadAs, $"the feed's picture {i} of {clip}");
        }
        Check(rig.Of("frame-ack")[^1].Contains($"\"seq\":{clip.Units.Count - 1}"), "The last frame is acknowledged too, once the 30 ms are up: " + rig.Of("frame-ack")[^1]);
        Check(rig.Count("request-keyframe") == 0 && rig.Count("start-view") == 0, "A healthy stream is asked for nothing");
        Check(rig.Jpegs.Count == 0, "Video does not go the way of JPEGs");
        string text = rig.Feed.StatsText();
        Check(text.StartsWith("Smooth sharing · H264 · 320 × 240") && text.Contains("Decode "), "The readout names the codec and the decoding time:\n" + text);
        rig.Feed.Drawn(rig.Shown[^1].Header);

        // A JPEG stream through the same feed is drawn as it always was.
        rig.Info("7", IdB, codec: "jpeg");
        rig.Jpeg(StreamB, 0, 1, 2, 3);
        rig.Jpeg(StreamB, 1, 4, 5, 6);
        int video = rig.Pump();
        Check(video == 0 && rig.Jpegs.Count == 1 && rig.Jpegs[0].Data[rig.Jpegs[0].Offset] == 4, "JPEGs still go newest only, by their own way");
        Pass("the feed decodes every video frame in order on its own thread and hands on the pictures");
    }

    private static void CheckFeedAfterALostFrame(Clip clip, Clip again)
    {
        var rig = Viewing();
        rig.Now = 1000;
        for (uint i = 0; i < 4; i++) rig.Frame(StreamA, i, clip.Units[(int)i]);
        // Frame 4 never comes. Frames 0 to 3 have not even been decoded yet.
        rig.Frame(StreamA, 5, clip.Units[5]);
        Check(rig.Of("request-keyframe").SequenceEqual(["""{"type":"request-keyframe","to":"7","stream":"00000000000000aa"}"""]),
            "A keyframe is asked for the moment the gap is seen: " + string.Join(" ", rig.Of("request-keyframe")));
        Check(rig.Feed.VideoState is { Waiting: true, Queued: 4 }, "The frames from before the gap still wait to be decoded");
        rig.Frame(StreamA, 6, clip.Units[6]);
        rig.Now = 1040;
        Check(rig.Pump() == 4 && rig.Shown[^1].Header.Seq == 3, "And are shown: they are good pictures");
        Check(rig.Of("frame-ack")[^1].Contains("\"seq\":6"), "Frames that are passed over are acknowledged all the same, so the phone is not held back");
        Check(rig.Feed.StatsText().Contains("Waiting for a keyframe · asked 1 times") && rig.Feed.StatsText().EndsWith("Frames lost: 1"),
            "The readout says a keyframe is waited for:\n" + rig.Feed.StatsText());

        // No keyframe comes: asked again each second, by the feed's own thread.
        rig.Now = 1900;
        rig.Pump();
        Check(rig.Count("request-keyframe") == 1, "Not again within the second");
        rig.Now = 2000;
        rig.Pump();
        rig.Now = 3000;
        rig.Pump();
        Check(rig.Count("request-keyframe") == 3, "A keyframe is asked for again every second until one comes");

        // The phone's answer: a keyframe. A new encoder's first frame stands in for it.
        rig.Now = 3100;
        rig.Frame(StreamA, 7, again.Units[0]);
        rig.Frame(StreamA, 8, again.Units[1]);
        Check(rig.Pump() == 2 && rig.Shown[^1].Header.Seq == 8, "From the keyframe on, pictures again");
        H264Tests.CheckPatches(rig.Shown[^1].Bgra, again.Width, again.Height, again.ReadAs, $"after a lost frame and a keyframe, {again}");
        rig.Now = 5000;
        rig.Pump();
        Check(rig.Count("request-keyframe") == 3 && !rig.Feed.VideoState.Waiting, "And nothing more is asked");
        Check(rig.Feed.VideoState.Failures == 0, "A lost frame is not the decoder's failure");
        Pass("after a lost frame the feed asks for a keyframe, shows nothing broken, and carries on from the keyframe");
    }

    private static void CheckFeedComingInPartWay(Clip clip)
    {
        var rig = Viewing();
        rig.Now = 1000;
        rig.Frame(StreamA, 40, clip.Units[3]);
        rig.Frame(StreamA, 41, clip.Units[4]);
        Check(rig.Pump() == 0 && rig.Count("request-keyframe") == 1, "A stream that this viewer comes into part way is asked for a keyframe, and nothing is drawn");
        Check(rig.Count("frame-ack") >= 1, "Its frames are acknowledged meanwhile");

        // A frame whose header says keyframe but which is not one: the decoder says so, and it is asked again.
        rig.Now = 2500;
        rig.Frame(StreamA, 42, clip.Units[5], keyframe: true);
        Check(rig.Pump() == 0 && rig.Feed.VideoState is { Waiting: true, Failures: 0 }, "A frame that only claims to be a keyframe is not shown, and is nobody's failure");
        Check(rig.Count("request-keyframe") == 2, "A keyframe is asked for again");
        rig.Now = 4000;
        rig.Frame(StreamA, 43, clip.Units[0]);
        Check(rig.Pump() == 1, "The true keyframe is shown");
        Pass("a stream come into part way, or with a false keyframe, waits for a true one");
    }

    private static void CheckFeedGoesByTheBytes(Clip clip)
    {
        byte[] key = clip.Units[0];
        byte[] sets = H264Decoder.ParameterSets(key) ?? throw new Exception("setup: the keyframe's parameter sets");
        byte[] bare = H264Tests.WithoutUnits(key, 7, 8);

        // A phone that never sets the keyframe bit: its keyframes are keyframes all the same.
        var unmarked = Viewing();
        for (uint i = 0; i < 4; i++) unmarked.Frame(StreamA, i, clip.Units[(int)i], keyframe: false);
        Check(unmarked.Pump() == 4 && unmarked.Count("request-keyframe") == 0, "A stream whose keyframe bit is never set is shown from its keyframe");

        // A phone that sets it on everything: a frame that leans on a lost one is still not drawn.
        var overmarked = Viewing();
        overmarked.Frame(StreamA, 0, clip.Units[0], keyframe: true);
        overmarked.Frame(StreamA, 1, clip.Units[1], keyframe: true);
        overmarked.Frame(StreamA, 3, clip.Units[3], keyframe: true);
        Check(overmarked.Pump() == 2 && overmarked.Feed.VideoState is { Waiting: true, Failures: 0 } && overmarked.Count("request-keyframe") == 1,
            "A stream whose every frame claims to be a keyframe is still stopped at a gap, and no failure is counted for the claim");

        // Parameter sets sent as a frame of their own, the keyframe behind them without any.
        var split = Viewing();
        split.Now = 1000;
        split.Frame(StreamA, 0, sets);
        Check(split.Pump() == 0 && split.Count("request-keyframe") == 0 && split.Count("frame-ack") == 1, "Parameter sets on their own are acknowledged, and nothing is asked");
        split.Frame(StreamA, 1, bare);
        split.Frame(StreamA, 2, clip.Units[1]);
        split.Frame(StreamA, 3, clip.Units[2]);
        Check(split.Pump() == 3 && split.Shown[0].Header.Seq == 1, "The keyframe that follows them without its own is shown, and the frames after it");
        Check(split.Shown.Select(s => H264Tests.FrameShownIn(s.Bgra, clip.Width, clip.Height)).SequenceEqual([0, 1, 2]), "Each the picture of its own frame");
        H264Tests.CheckPatches(split.Shown[^1].Bgra, clip.Width, clip.Height, clip.ReadAs, "from a phone that sends its parameter sets apart");
        Check(split.Count("request-keyframe") == 0 && split.Feed.VideoState.Failures == 0, "Without a request and without a failure");

        // Parameter sets only with the first keyframe: a later keyframe without them, after a gap.
        var once = Viewing();
        once.Now = 1000;
        once.Frame(StreamA, 0, key);
        once.Frame(StreamA, 1, clip.Units[1]);
        once.Frame(StreamA, 3, clip.Units[3]);
        Check(once.Pump() == 2 && once.Feed.VideoState.Waiting, "setup: a gap, and a keyframe waited for");
        once.Frame(StreamA, 4, bare);
        once.Frame(StreamA, 5, clip.Units[1]);
        Check(once.Pump() == 2 && !once.Feed.VideoState.Waiting, "A keyframe without parameter sets is given the stream's last ones, and ends the wait");

        // Without any ever seen, a bare IDR picture is no start: a keyframe is asked for.
        var none = Viewing();
        none.Now = 1000;
        none.Frame(StreamA, 0, bare);
        Check(none.Pump() == 0 && none.Feed.VideoState.Waiting && none.Count("request-keyframe") == 1, "An IDR picture with no parameter sets anywhere is not a start");
        // And another stream's are not borrowed.
        split.Info("7", IdB);
        split.Frame(StreamB, 0, bare);
        Check(split.Pump() == 0 && split.Feed.VideoState.Waiting, "Parameter sets are kept for their own stream only");
        Pass("the feed goes by what a frame holds: unmarked keyframes, false ones, and parameter sets sent apart");
    }

    private static void CheckFeedGivesUpOnAPhoneThatSendsNoKeyframe(Clip clip)
    {
        var rig = Viewing();
        rig.Now = 1000;
        rig.Frame(StreamA, 0, clip.Units[0]);
        rig.Frame(StreamA, 1, clip.Units[1]);
        Check(rig.Pump() == 2, "setup: two pictures");
        // A frame is lost, and the phone takes no notice of what it is asked.
        uint seq = 3;
        for (long now = 1100; now <= 8000 && !rig.Feed.VideoState.GivenUp; now += 100)
        {
            rig.Now = now;
            rig.Frame(StreamA, seq++, clip.Units[1 + (int)(seq % 8)], keyframe: false);
            rig.Pump();
        }
        Check(rig.Feed.VideoState is { GivenUp: true, Offered: false }, "A phone that sends on and never sends the keyframe it is asked for is given up");
        Check(rig.Count("request-keyframe") == PhoneVideoStream.UnansweredToGiveUp, $"After five requests: {rig.Count("request-keyframe")}");
        Check(rig.Now >= 6000 && rig.Now <= 6300, $"Six seconds after the first, not for ever: at {rig.Now}");
        Check(rig.Of("start-view").SequenceEqual(["""{"type":"start-view","to":"7","codecs":["jpeg"]}"""]), "The phone is asked for JPEG");
        Check(rig.Shown.Count == 2 && rig.Feed.VideoState.Failures == 0, "Nothing broken was shown meanwhile, and the decoder is not blamed");
        Check(rig.Feed.StatsText().Contains("H.264 given up for this phone (the phone sent no keyframe when asked)"), "The readout says why:\n" + rig.Feed.StatsText());
        Check(rig.GaveUp.SequenceEqual([("7", "the phone sent no keyframe when asked")]), "And the person at the PC is told, once");
        Pass("a phone that never sends the keyframe it is asked for is asked for JPEG instead, after five requests");
    }

    private static void CheckFeedFallingBehind(Clip clip, Clip again)
    {
        // The PC cannot keep up: frames pile up undecoded.
        var rig = Viewing();
        rig.Now = 1000;
        rig.Frame(StreamA, 0, clip.Units[0]);
        uint seq = 1;
        for (; seq < PhoneFeed.MaxWaitingFrames; seq++) rig.Frame(StreamA, seq, clip.Units[1 + (int)(seq % 8)], keyframe: false);
        Check(rig.Feed.VideoState is { Queued: PhoneFeed.MaxWaitingFrames, Waiting: false } && rig.Count("request-keyframe") == 0,
            "A second's worth of frames may wait");
        rig.Frame(StreamA, seq++, clip.Units[2], keyframe: false);
        Check(rig.Feed.VideoState is { Queued: 0, Waiting: true }, "One more, and they are all let go: showing the phone a second late helps nobody");
        Check(rig.Count("request-keyframe") == 1, "And a keyframe is asked for");
        rig.Frame(StreamA, seq++, clip.Units[3], keyframe: false);
        Check(rig.Feed.VideoState.Queued == 0 && rig.Pump() == 0, "What follows is passed over until it comes");
        rig.Frame(StreamA, seq++, again.Units[0]);
        Check(rig.Pump() == 1, "The keyframe starts it again");
        H264Tests.CheckPatches(rig.Shown[^1].Bgra, again.Width, again.Height, again.ReadAs, "after falling behind");
        Check(rig.Feed.VideoState.Failures == 0, "Falling behind is not the decoder's failure");

        // Behind by a few frames when a keyframe happens to arrive: skip to it, and ask for nothing.
        var lucky = Viewing();
        lucky.Frame(StreamA, 0, clip.Units[0]);
        for (uint i = 1; i <= PhoneFeed.SkipToKeyframeAt; i++) lucky.Frame(StreamA, i, clip.Units[(int)i]);
        lucky.Frame(StreamA, PhoneFeed.SkipToKeyframeAt + 1, again.Units[0]);
        Check(lucky.Feed.VideoState is { Queued: 1, Waiting: false } && lucky.Count("request-keyframe") == 0,
            "A keyframe that arrives while frames wait lets them go, and nothing is asked");
        Check(lucky.Pump() == 1 && lucky.Shown[0].Header.Seq == PhoneFeed.SkipToKeyframeAt + 1, "It is the next picture shown");

        // Too many bytes waiting counts as behind as well.
        var heavy = Viewing();
        heavy.Frame(StreamA, 0, clip.Units[0]);
        byte[] big = new byte[PhoneFeed.MaxWaitingBytes / 3];
        clip.Units[1].CopyTo(big, 0);
        for (uint i = 1; i <= 2; i++) heavy.Frame(StreamA, i, big, keyframe: false);
        Check(!heavy.Feed.VideoState.Waiting, "setup: under the limit in bytes");
        heavy.Frame(StreamA, 3, big, keyframe: false);
        Check(heavy.Feed.VideoState is { Queued: 0, Waiting: true }, "Frames that add up to too many bytes are let go the same way");
        Pass("a PC that falls a second behind lets the waiting frames go and asks for a keyframe");
    }

    /// <summary>A keyframe the decoder can make nothing of: the parameter sets, and a picture with nothing in it.</summary>
    private static byte[] BrokenKeyframe(Clip clip) =>
        H264Tests.WithoutUnits(clip.Units[0], 5).Concat(new byte[] { 0, 0, 0, 1, 0x65 }).ToArray();

    private static void CheckFeedDecoderFailure(Clip clip)
    {
        var rig = Viewing();
        rig.Now = 1000;
        rig.Frame(StreamA, 0, clip.Units[0]);
        Check(rig.Pump() == 1, "setup: a picture");
        rig.Frame(StreamA, 1, BrokenKeyframe(clip));
        rig.Frame(StreamA, 2, clip.Units[2]);
        rig.Frame(StreamA, 3, clip.Units[3]);
        // The frames behind it would decode to something, built on nothing: that must not be shown.
        Check(rig.Pump() == 0, "A keyframe the decoder makes nothing of gives no picture, and nor do the frames behind it");
        Check(rig.Feed.VideoState is { Failures: 1, Waiting: true, GivenUp: false, Queued: 0 }, "It is counted as one failure");
        Check(rig.Count("request-keyframe") == 1 && rig.Count("start-view") == 0, "And answered with a request for a keyframe");
        rig.Now = 2000;
        rig.Frame(StreamA, 4, clip.Units[0]);
        Check(rig.Pump() == 1, "A good keyframe, and the picture is back");

        // The second failure, this time with nothing behind it: on a screen
        // that has stopped changing. It is waited for like a late picture,
        // and then it is a failure.
        rig.Now = 3000;
        rig.Frame(StreamA, 5, BrokenKeyframe(clip));
        Check(rig.PumpOnce() == 0 && rig.Feed.VideoState is { GivenUp: false, Failures: 1, Owed: true },
            "A keyframe with no picture yet is not failed on the spot: its picture may be late");
        rig.Pump();
        Check(rig.Feed.VideoState is { GivenUp: true, Offered: false }, "A second failure, and H.264 is given up");
        Check(rig.GaveUp.SequenceEqual([("7", "a keyframe gave no picture")]), "The person at the PC is told, once");
        Check(rig.Of("start-view").SequenceEqual(["""{"type":"start-view","to":"7","codecs":["jpeg"]}"""]),
            "The phone is told by a start-view that offers JPEG only: " + string.Join(" ", rig.Of("start-view")));
        int asked = rig.Count("request-keyframe");
        // The phone goes on sending video: the start-view was lost on the
        // way, or one sent a moment earlier, still offering video, came in
        // behind it. It is told again, once a second, until it stops.
        uint late = 6;
        for (long now = 3100; now <= 5900; now += 100)
        {
            rig.Now = now;
            rig.Frame(StreamA, late++, clip.Units[(int)(late % 8)]);
            rig.Pump();
        }
        Check(rig.Shown.Count == 2, "Video that still arrives is not drawn");
        Check(rig.Of("frame-ack")[^1].Contains($"\"seq\":{late - 1}"), "It is acknowledged: " + rig.Of("frame-ack")[^1]);
        Check(rig.Of("start-view").Count == 3 && rig.Of("start-view").All(m => m == """{"type":"start-view","to":"7","codecs":["jpeg"]}"""),
            $"And the phone is told again that only JPEG is taken, once a second while video keeps coming: {rig.Of("start-view").Count} times");
        rig.Now = 6000;
        rig.Pump();
        Check(rig.Count("request-keyframe") == asked, "And no more keyframes are asked for");
        Check(rig.GaveUp.Count == 1, "The person at the PC is not told again each time");
        Check(rig.Feed.StatsText().Contains("H.264 given up for this phone (a keyframe gave no picture). Tried again when its window is opened ten minutes later or more."),
            "The readout says so:\n" + rig.Feed.StatsText());

        // The phone answers with a JPEG stream, which is drawn.
        rig.Info("7", IdB, codec: "jpeg");
        rig.Jpeg(StreamB, 0, 9);
        rig.Pump();
        Check(rig.Jpegs.Count == 1, "The JPEGs that follow are drawn");

        // It is this phone's video that is given up, and not for ever.
        Check(!rig.Feed.View("7", offered: true), "While the window stays open, asking again does not bring video back");
        rig.Feed.Stop("7");
        rig.Now = 6000 + 60_000;
        Check(!rig.Feed.View("7", offered: true), "Nor does a window opened on the phone a minute later");
        rig.Feed.Stop("7");
        Check(rig.Feed.View("9", offered: true), "Another phone is offered video all the while");
        rig.Feed.Stop("9");
        rig.Now = 3000 + PhoneFeed.GiveUpWearsOffMs - 1;
        Check(!rig.Feed.View("7", offered: true), "Not quite ten minutes after, still not");
        rig.Feed.Stop("7");
        rig.Now = 3000 + PhoneFeed.GiveUpWearsOffMs + 200;
        Check(rig.Feed.View("7", offered: true), "A window opened ten minutes after it was given up offers video again");
        rig.Info("7", IdA);
        rig.Frame(StreamA, 0, clip.Units[0]);
        Check(rig.Pump() == 1 && rig.GaveUp.Count == 1, "And shows it");
        Pass("a decoder that fails twice is given up for that phone, the phone is asked for JPEG, and video is tried again later");
    }

    private static void CheckFeedWaitsForALatePicture(Clip clip)
    {
        // The decoder is made to be late with its first picture, as it sometimes is by itself.
        var rig = new VideoRig { HoldBack = 1 };
        rig.Feed.View("7", offered: true);
        rig.Info("7", IdA);
        rig.Now = 1000;
        rig.Frame(StreamA, 0, clip.Units[0]);
        Check(rig.PumpOnce() == 0 && rig.Feed.VideoState is { Owed: true, Failures: 0, Waiting: false },
            "A keyframe whose picture is not out yet is owed one: no failure, and nothing asked");
        Check(rig.Count("request-keyframe") == 0, "No keyframe is asked for on account of lateness");
        rig.Now = 1001;
        Check(rig.PumpOnce() == 0, "It is not asked for again before a moment has passed");
        rig.Now = 1002;
        Check(rig.PumpOnce() == 1 && rig.Shown[0].Header.Seq == 0 && !rig.Feed.VideoState.Owed,
            "Asked again a moment later, with no new frame, the picture is shown: a still screen does not hide it");
        H264Tests.CheckPatches(rig.Shown[0].Bgra, clip.Width, clip.Height, clip.ReadAs, "a keyframe's picture that came late");

        // An ordinary frame's picture is late, and the next frame arrives before it is asked for again.
        rig.Decoder!.TestHoldBackCalls = 1;
        rig.Frame(StreamA, 1, clip.Units[1]);
        Check(rig.PumpOnce() == 0 && rig.Feed.VideoState.Owed, "setup: frame 1's picture is owed");
        rig.Frame(StreamA, 2, clip.Units[2]);
        Check(rig.PumpOnce() == 1, "The next frame brings both pictures out, and the newer is shown");
        var shown = rig.Shown[^1];
        Check(shown.Header.Seq == 2 && H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height) == 2,
            $"Under its own frame's number: numbered {shown.Header.Seq}, showing frame {H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height)}");

        // Late again, and this time it is asked for before the next frame comes: it is frame 3's picture, under frame 3's number.
        rig.Decoder.TestHoldBackCalls = 1;
        rig.Frame(StreamA, 3, clip.Units[3]);
        Check(rig.PumpOnce() == 0, "setup: frame 3's picture is owed");
        rig.Now = 1010;
        Check(rig.PumpOnce() == 1, "It comes when asked for again");
        shown = rig.Shown[^1];
        Check(shown.Header.Seq == 3 && H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height) == 3,
            $"A picture that comes late carries the header of the frame it was decoded from: numbered {shown.Header.Seq}, showing frame {H264Tests.FrameShownIn(shown.Bgra, clip.Width, clip.Height)}");
        Check(rig.Feed.VideoState is { Failures: 0, GivenUp: false } && rig.Count("request-keyframe") == 0 && rig.GaveUp.Count == 0,
            "None of the lateness counted as a failure");

        // The window closes while a picture is owed: it is not asked for again.
        rig.Decoder.TestHoldBackCalls = 1;
        rig.Frame(StreamA, 4, clip.Units[4]);
        rig.PumpOnce();
        rig.Feed.Stop("7");
        rig.Now = 2000;
        Check(rig.PumpOnce() == 0 && !rig.Feed.VideoState.Owed, "A picture owed to a window that has closed is forgotten");
        Pass("a picture the decoder gives late is shown when it comes, under its own frame's number, and is no failure");
    }

    private static void CheckFeedWithoutADecoder(Clip clip)
    {
        // A PC with no H.264 decoder: nothing is different from before.
        var rig = new VideoRig(canDecode: false);
        Check(!rig.Feed.View("7", offered: true), "setup: no video offered");
        rig.Info("7", IdA, codec: "jpeg");
        rig.Jpeg(StreamA, 0, 1);
        rig.Pump();
        Check(rig.Jpegs.Count == 1, "JPEG frames are drawn as they were");
        // A phone that sends video all the same is not obeyed.
        rig.Frame(StreamA, 1, clip.Units[0]);
        rig.Frame(StreamA, 3, clip.Units[1]);
        Check(rig.Pump() == 0 && rig.Shown.Count == 0 && rig.Feed.VideoState.Queued == 0, "Video that was not offered is not decoded");
        Check(rig.Count("request-keyframe") == 0, "No keyframe is asked for");
        Check(rig.Of("start-view").SequenceEqual(["""{"type":"start-view","to":"7","codecs":["jpeg"]}"""]),
            "The phone is told again what this PC takes, once: " + string.Join(" ", rig.Of("start-view")));
        rig.Now = 100;
        rig.Pump();
        Check(rig.Of("frame-ack")[^1].Contains("\"seq\":3"), "It is acknowledged like any frame");
        Pass("a PC without a decoder, and a phone that sends only JPEG, see no difference");
    }

    private static void CheckFeedTurnedPhoneAndNewStreams(Clip clip, Clip turned)
    {
        var rig = Viewing();
        rig.Now = 1000;
        rig.Frame(StreamA, 0, clip.Units[0]);
        rig.Frame(StreamA, 1, clip.Units[1]);
        Check(rig.Pump() == 2, "setup: two pictures");

        // The phone is turned: the same stream goes on at another size, from a keyframe.
        rig.Frame(StreamA, 2, turned.Units[0], turned.Width, turned.Height);
        rig.Frame(StreamA, 3, turned.Units[1], turned.Width, turned.Height);
        Check(rig.Pump() == 2 && rig.Shown[^1].Width == turned.Width && rig.Shown[^1].Height == turned.Height,
            "A turned phone's pictures come at their new size, through the same stream");
        H264Tests.CheckPatches(rig.Shown[^1].Bgra, turned.Width, turned.Height, turned.ReadAs, "after the turn");
        Check(rig.Count("request-keyframe") == 0, "Nothing is asked for it");

        // A new stream id: its first frame is a keyframe, and frames of the old one are refused.
        rig.Info("7", IdB);
        rig.Frame(StreamA, 4, turned.Units[2], turned.Width, turned.Height);
        Check(rig.Pump() == 0, "A frame of the stream that ended is not drawn");
        rig.Frame(StreamB, 0, clip.Units[0]);
        rig.Frame(StreamB, 1, clip.Units[1]);
        Check(rig.Pump() == 2 && rig.Shown[^1].Header.Stream == StreamB && rig.Shown[^1].Width == clip.Width, "The new stream is shown from its keyframe");

        // A pause: nothing is drawn until the phone says more, not even frames already waiting.
        rig.Frame(StreamB, 2, clip.Units[2]);
        rig.Frame(StreamB, 3, clip.Units[3]);
        rig.Info("7", null, paused: true);
        Check(rig.Pump() == 0 && rig.Feed.VideoState.Queued == 0, "A pause lets the waiting frames go");
        rig.Frame(StreamB, 4, clip.Units[4]);
        Check(rig.Pump() == 0, "And frames that still come are nobody's");

        // The window asks again, as it does when its connection came back: nothing is forgotten by that.
        rig.Info("7", IdA);
        rig.Frame(StreamA, 0, clip.Units[0]);
        Check(rig.Feed.View("7", offered: true), "setup: start-view again");
        rig.Frame(StreamA, 1, clip.Units[1]);
        Check(rig.Pump() == 2, "Asking for the stream again does not disturb a stream that is running");
        Check(rig.Count("request-keyframe") == 0, "And asks for no keyframe");
        Pass("a turned phone, a new stream and a pause are each taken as they come");
    }

    private static void CheckFeedWhileAFrameIsDecoded(Clip clip)
    {
        // Each of these happens while a frame is in the decoder: on the
        // window's thread or the socket's, with the feed's thread busy.
        var things = new (string name, Action<VideoRig> act, Action<VideoRig> after)[]
        {
            ("the window closes", rig => rig.Feed.Stop("7"), rig =>
            {
                Check(rig.Feed.State.Stream == null && rig.Feed.VideoState.Queued == 0, "The feed has let go of the phone");
            }),
            ("the phone pauses", rig => rig.Info("7", null, paused: true), _ => { }),
            ("the phone starts another stream", rig => rig.Info("7", IdB), rig =>
            {
                rig.Frame(StreamB, 0, clip.Units[0]);
                Check(rig.Pump() == 1 && rig.Shown[^1].Header.Stream == StreamB, "The new stream is shown");
            }),
            ("another phone is viewed", rig => rig.Feed.View("9", offered: true), rig =>
            {
                rig.Info("9", IdB);
                rig.Frame(StreamB, 0, clip.Units[0]);
                Check(rig.Pump() == 1 && rig.Shown[^1].Header.Stream == StreamB, "The other phone's stream is shown");
            }),
            ("this PC stops approving the phone", rig => rig.Feed.View("7", offered: false), rig =>
            {
                Check(!rig.Feed.VideoState.Offered && rig.Feed.State.Stream == null, "Its stream is no longer believed");
            }),
            ("the feed is disposed", rig => rig.Feed.Dispose(), _ => { }),
        };
        foreach (var (name, act, after) in things)
        {
            var rig = Viewing();
            rig.Frame(StreamA, 0, clip.Units[0]);
            Check(rig.Pump() == 1, "setup: a picture");
            rig.Frame(StreamA, 1, clip.Units[1]);
            rig.Frame(StreamA, 2, clip.Units[2]);
            bool acted = false;
            rig.Feed.WhileDecoding = () =>
            {
                if (acted) return;
                acted = true;
                act(rig);
            };
            int shown = rig.Pump();
            Check(acted, $"setup: {name} while a frame was in the decoder");
            Check(shown == 0, $"When {name} while a frame is being decoded, that frame's picture is not shown, nor the one behind it");
            Check(rig.Feed.Video.Take() == null, $"When {name}, no picture is left waiting for the window");
            Check(rig.Count("request-keyframe") == 0, $"When {name}, no keyframe is asked of a stream that is over");
            rig.Feed.WhileDecoding = null;
            after(rig);
        }

        // The window's connection drops and comes back, and it asks for the
        // stream again while a frame is in the decoder. Nothing has ended:
        // that frame's picture is shown, and so is the next.
        var back = Viewing();
        back.Frame(StreamA, 0, clip.Units[0]);
        Check(back.Pump() == 1, "setup: a picture");
        back.Frame(StreamA, 1, clip.Units[1]);
        back.Frame(StreamA, 2, clip.Units[2]);
        bool asked = false;
        back.Feed.WhileDecoding = () =>
        {
            if (asked) return;
            asked = true;
            Check(back.Feed.View("7", offered: true), "setup: start-view again, with video still offered");
        };
        Check(back.Pump() == 2 && asked && back.Shown[^1].Header.Seq == 2, "Asking for the stream again while a frame is being decoded loses nothing");
        Check(back.Count("request-keyframe") == 0, "And asks for no keyframe");
        // The phone answers the reconnect with a new stream, whose frames are shown from its keyframe.
        back.Info("7", IdB);
        back.Frame(StreamB, 0, clip.Units[0]);
        Check(back.Pump() == 1 && back.Shown[^1].Header.Stream == StreamB, "The stream that follows a reconnect is shown");
        Pass("viewing may stop, pause or change while a frame is in the decoder, and its picture is then nobody's");
    }

    private static void CheckFeedOnItsOwnThread(Clip clip)
    {
        // The real thing: a thread of its own, a real clock, and the phone and
        // the window doing things at any moment.
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var shown = new List<(ulong Stream, uint Seq)>();
        var sent = new List<string>();
        Exception? trouble = null;
        PhoneFeed? feed = null;
        feed = new PhoneFeed(_ => { }, msg => { lock (sent) sent.Add(msg.ToJsonString()); }, () => clock.ElapsedMilliseconds,
            drawVideo: picture =>
            {
                try
                {
                    lock (shown) shown.Add((picture.Header.Stream, picture.Header.Seq));
                    feed!.Video.Publish(picture);
                    if (feed.Video.Take() is { } taken) feed.Video.Return(taken);
                }
                catch (Exception e) { trouble = e; }
            },
            canDecodeH264: true);
        using (feed)
        {
            byte[] FrameOf(ulong stream, uint seq, byte[] unit) => [Crypto.ChPhone, .. PhoneFrame.Build(
                Video(stream, seq, (H264Decoder.Scan(unit) & H264Decoder.Units.Keyframe) == H264Decoder.Units.Keyframe), unit)];
            JsonObject Info(string stream) => new() { ["type"] = "screen-info", ["from"] = "7", ["stream"] = stream, ["codec"] = "h264" };

            feed.View("7", offered: true);
            feed.OnScreenInfo(Info(IdA));
            for (int i = 0; i < clip.Units.Count; i++) feed.OnFrame(FrameOf(StreamA, (uint)i, clip.Units[i]));
            bool Seen(ulong stream, uint seq) { lock (shown) return shown.Contains((stream, seq)); }
            Check(SpinWait.SpinUntil(() => Seen(StreamA, (uint)clip.Units.Count - 1), TimeSpan.FromSeconds(20)), "The feed's own thread decodes the stream to its last frame");
            lock (shown) Check(shown.Select(s => s.Seq).SequenceEqual(Enumerable.Range(0, clip.Units.Count).Select(i => (uint)i)), "Every frame, in order");

            // Now with everything happening at once, for a while.
            var random = new Random(4);
            var until = clock.ElapsedMilliseconds + 1500;
            ulong stream = 0x100;
            while (clock.ElapsedMilliseconds < until)
            {
                stream++;
                feed.View("7", offered: true);
                feed.OnScreenInfo(Info(PhoneFrame.StreamHex(stream)));
                int frames = random.Next(1, clip.Units.Count);
                for (int i = 0; i < frames; i++) feed.OnFrame(FrameOf(stream, (uint)i, clip.Units[i]));
                Thread.Sleep(random.Next(0, 12));
                switch (random.Next(4))
                {
                    case 0: feed.Stop("7"); break;
                    case 1: feed.OnScreenInfo(new JsonObject { ["type"] = "screen-info", ["from"] = "7", ["paused"] = true }); break;
                    case 2: feed.View("9", offered: true); feed.Stop("9"); break;
                    default: break;   // the next stream simply begins
                }
            }
            // After all that, a stream is still shown.
            stream++;
            feed.View("7", offered: true);
            feed.OnScreenInfo(Info(PhoneFrame.StreamHex(stream)));
            for (int i = 0; i < 3; i++) feed.OnFrame(FrameOf(stream, (uint)i, clip.Units[i]));
            Check(SpinWait.SpinUntil(() => Seen(stream, 2), TimeSpan.FromSeconds(20)), "After starts, stops and pauses at every moment, the next stream is shown");
            Check(trouble == null, "Nothing went wrong on the feed's thread: " + trouble);
            Check(!feed.VideoState.GivenUp && feed.VideoState.Failures == 0, "And none of it was taken for a failure of the decoder");
            lock (sent) Check(!sent.Any(m => m.Contains("\"codecs\":[\"jpeg\"]")), "Nor was the phone asked for JPEG");
        }
        Pass("on its own thread the feed shows video while windows open and close and streams come and go");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
