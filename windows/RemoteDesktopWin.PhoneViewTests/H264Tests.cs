using System.Diagnostics;
using System.Drawing;
using System.Drawing.Imaging;
using RemoteDesktopWin;

// Showing a phone's screen sent as video: turning decoded pictures into the
// colours a window draws (Nv12Converter.cs), and Windows' own H.264 decoder
// behind H264Decoder.cs.
//
// The converter's checks are arithmetic and run anywhere. The decoder's need
// real codecs: each makes its stream on the spot, from a drawn test pattern,
// with the app's own H264Encoder (the graphics card's encoder and Windows'
// software one, whichever this PC has), and decodes it again. No recording
// of anyone's screen is used or kept. On a machine without the codecs (a
// Windows Server without Media Foundation, as a build runner may be) those
// checks say SKIP and pass.
internal static unsafe class H264Tests
{
    // How far a colour may come back from what was drawn, out of 255, after
    // being encoded and decoded. Coding a flat patch costs a step or three.
    // Converting with one standard's numbers under a stream that names the
    // other costs 40 on pure green, which is the fault this is here to catch.
    private const int ColourTolerance = 8;
    private static int _worstColour;

    public static void Run()
    {
        CheckConverterAgainstTheSlowSum();
        CheckConverterKnownColours();
        CheckConverterInBands();
        CheckFillerRemoval();
        CheckWhatCountsAsAKeyframe();
        CheckEncoderColourNumbers();

        if (!CodecsAreHere("the decoder's checks", out var kinds)) return;
        Console.WriteLine("  (streams for the decoder's checks come from: " + string.Join(" and ", kinds.Select(Clip.EncoderName)) + ")");

        CheckRoundTrips(kinds);
        CheckColoursEitherSideOfTheGuess(kinds);
        // The rest once for each encoder there is: their streams are built
        // differently (one slice to a picture or a dozen, padding or none).
        foreach (bool hardware in kinds)
        {
            _say = hardware == kinds[^1];
            Clip small = Clip.Make(hardware, 320, 240, 12) ?? throw new Exception("setup: no 320 x 240 stream");
            Clip turned = Clip.Make(hardware, 242, 322, 12) ?? throw new Exception("setup: no 242 x 322 stream");
            Clip tall = Clip.Make(hardware, 1080, 2400, 3) ?? throw new Exception("setup: no 1080 x 2400 stream");
            CheckFillerDoesNotHoldAPictureBack(small);
            CheckAFreshStartWantsAKeyframe(small);
            CheckReset(small);
            CheckGarbage(small);
            CheckAKeyframeWithoutAPictureIsAFailure(tall);
            CheckATurnedPhone(small, turned);
            CheckTheHandler(small);
            CheckDisposeWhileDecoding(small);
            CheckThreads(small);
            CheckOneDecoderAfterAnother(small, turned);
        }
        _say = true;
    }

    /// <summary>
    /// Whether the checks that need real codecs can run. They are skipped,
    /// with a line saying so, only where Windows truly has no H.264 decoder
    /// or lists no H.264 encoder. A decoder or an encoder that is there and
    /// cannot be made to work is a failure: otherwise a mistake in setting
    /// one up would print SKIP and pass, while the app quietly stopped
    /// offering video.
    /// </summary>
    internal static bool CodecsAreHere(string what, out List<bool> kinds)
    {
        kinds = [];
        if (!H264Decoder.IsAvailable)
        {
            Check(H264Decoder.IsNotInstalled, "Windows has an H.264 decoder and it could not be set up: " + H264Decoder.WhyNotAvailable);
            Console.WriteLine($"SKIP: {H264Decoder.WhyNotAvailable}, so {what} did not run");
            return false;
        }
        kinds = Encoders();
        if (kinds.Count == 0)
        {
            Check(!H264Encoder.AnyRegistered(), "Windows lists an H.264 encoder and H264Encoder could not make one work, not even Windows' own");
            Console.WriteLine($"SKIP: Windows lists no H.264 encoder to make a stream with, so {what} did not run");
            return false;
        }
        return true;
    }

    /// <summary>Which encoders this PC has: true for the graphics card's, false for Windows' software one.</summary>
    internal static List<bool> Encoders()
    {
        var kinds = new List<bool>();
        foreach (bool hardware in new[] { true, false })
        {
            using var probe = H264Encoder.TryCreate(320, 240, 1_000_000, 30, hardware);
            if (probe != null) kinds.Add(hardware);
        }
        return kinds;
    }

    // A check that runs once for each encoder says PASS once, at the last.
    private static bool _say = true;

    private static void Pass(string what)
    {
        if (_say) Console.WriteLine("PASS: " + what);
    }

    // ---------------- the converter ----------------

    private static void CheckConverterAgainstTheSlowSum()
    {
        // A made-up picture with padding and rows wider than the picture,
        // against the same sum done in floating point for every pixel.
        const int codedW = 48, codedH = 32, stride = 64;
        var random = new Random(1);
        byte[] memory = new byte[stride * codedH * 3 / 2];
        random.NextBytes(memory);
        fixed (byte* m = memory)
        {
            foreach (var matrix in new[] { Nv12Matrix.Bt709, Nv12Matrix.Bt601 })
            foreach (bool full in new[] { false, true })
            foreach (var (x0, top, w, h) in new[] { (0, 0, 38, 22), (2, 4, 38, 22), (1, 3, 38, 22), (0, 0, 37, 21), (0, 0, 48, 32) })
            {
                var picture = new Nv12Picture
                {
                    Y = m, Uv = m + stride * codedH, Stride = stride, CodedWidth = codedW, CodedHeight = codedH,
                    X = x0, Top = top, Width = w, Height = h, Matrix = matrix, FullRange = full,
                };
                // Rows wider than the picture on the way out too, to see nothing is written past it.
                int outStride = w * 4 + 12;
                byte[] bgra = new byte[outStride * h];
                Array.Fill(bgra, (byte)0xA5);
                fixed (byte* o = bgra) Nv12Converter.ToBgra(picture, o, outStride);

                var (ys, yo, vr, ug, vg, ub) = Nv12Converter.Coefficients(matrix, full);
                for (int y = 0; y < h; y++)
                {
                    for (int x = 0; x < w; x++)
                    {
                        int sy = top + y, sx = x0 + x;
                        double yy = (memory[sy * stride + sx] - yo) * ys;
                        double u = memory[stride * codedH + (sy / 2) * stride + (sx / 2) * 2] - 128.0;
                        double v = memory[stride * codedH + (sy / 2) * stride + (sx / 2) * 2 + 1] - 128.0;
                        int r = (int)Math.Clamp(Math.Round(yy + vr * v), 0, 255);
                        int g = (int)Math.Clamp(Math.Round(yy - ug * u - vg * v), 0, 255);
                        int b = (int)Math.Clamp(Math.Round(yy + ub * u), 0, 255);
                        int i = y * outStride + x * 4;
                        string where = $"{matrix}, {(full ? "full" : "limited")} range, from ({x0},{top}), {w} x {h}, pixel ({x},{y})";
                        // One step of rounding apart at most: the tables hold 16 bits of fraction.
                        Check(Math.Abs(bgra[i] - b) <= 1 && Math.Abs(bgra[i + 1] - g) <= 1 && Math.Abs(bgra[i + 2] - r) <= 1,
                            $"The converter agrees with the sum done slowly: {where}");
                        Check(bgra[i + 3] == 255, $"Every pixel is opaque: {where}");
                    }
                    for (int pad = w * 4; pad < outStride; pad++)
                        Check(bgra[y * outStride + pad] == 0xA5, "Nothing is written past the end of a row");
                }
            }
        }
        Console.WriteLine("PASS: decoded pictures become the right colours, for both standards and both ranges, padding and odd edges included");
    }

    private static void CheckConverterKnownColours()
    {
        // Worked out by hand from the standards. Limited range: black is 16, white 235.
        (Nv12Matrix matrix, bool full, byte y, byte u, byte v, int r, int g, int b)[] known =
        {
            (Nv12Matrix.Bt709, false, 235, 128, 128, 255, 255, 255),
            (Nv12Matrix.Bt709, false, 16, 128, 128, 0, 0, 0),
            (Nv12Matrix.Bt709, false, 126, 128, 128, 128, 128, 128),
            (Nv12Matrix.Bt709, false, 63, 102, 240, 255, 0, 0),
            (Nv12Matrix.Bt709, false, 173, 42, 26, 0, 255, 0),
            (Nv12Matrix.Bt709, false, 32, 240, 118, 0, 0, 255),
            (Nv12Matrix.Bt601, false, 81, 90, 240, 255, 0, 0),
            (Nv12Matrix.Bt601, false, 145, 54, 34, 0, 255, 0),
            (Nv12Matrix.Bt601, false, 41, 240, 110, 0, 0, 255),
            (Nv12Matrix.Bt709, true, 255, 128, 128, 255, 255, 255),
            (Nv12Matrix.Bt709, true, 0, 128, 128, 0, 0, 0),
            (Nv12Matrix.Bt601, true, 76, 85, 255, 254, 0, 0),
            // Outside what a screen can show: clamped, not wrapped round.
            (Nv12Matrix.Bt709, false, 255, 128, 128, 255, 255, 255),
            (Nv12Matrix.Bt709, false, 0, 128, 128, 0, 0, 0),
            (Nv12Matrix.Bt709, false, 235, 255, 255, 255, 160, 255),
        };
        foreach (var k in known)
        {
            // Two by two pixels sharing one U and V, rows four bytes apart.
            byte[] nv12 = { k.y, k.y, 0, 0, k.y, k.y, 0, 0, k.u, k.v, 0, 0 };
            byte[] bgra = new byte[16];
            fixed (byte* s = nv12)
            fixed (byte* d = bgra)
            {
                var picture = new Nv12Picture
                {
                    Y = s, Uv = s + 8, Stride = 4, CodedWidth = 2, CodedHeight = 2, Width = 2, Height = 2,
                    Matrix = k.matrix, FullRange = k.full,
                };
                Nv12Converter.ToBgra(picture, d, 8);
            }
            for (int pixel = 0; pixel < 4; pixel++)
            {
                int i = pixel * 4;
                // The hand-made Y, U and V are themselves rounded, which is worth a step or two.
                Check(Math.Abs(bgra[i + 2] - k.r) <= 2 && Math.Abs(bgra[i + 1] - k.g) <= 2 && Math.Abs(bgra[i] - k.b) <= 2,
                    $"{k.matrix}, {(k.full ? "full" : "limited")}: Y {k.y} U {k.u} V {k.v} is ({k.r}, {k.g}, {k.b}), got ({bgra[i + 2]}, {bgra[i + 1]}, {bgra[i]})");
            }
        }
        Console.WriteLine("PASS: black, white, grey and the pure colours come out as the standards say");
    }

    private static void CheckConverterInBands()
    {
        const int codedW = 64, codedH = 48, w = 60, h = 45;
        var random = new Random(2);
        byte[] memory = new byte[codedW * codedH * 3 / 2];
        random.NextBytes(memory);
        byte[] whole = new byte[w * 4 * h], bands = new byte[w * 4 * h];
        fixed (byte* m = memory)
        fixed (byte* a = whole)
        fixed (byte* b = bands)
        {
            var picture = new Nv12Picture
            {
                Y = m, Uv = m + codedW * codedH, Stride = codedW, CodedWidth = codedW, CodedHeight = codedH,
                Top = 1, Width = w, Height = h, Matrix = Nv12Matrix.Bt709,
            };
            Nv12Converter.ToBgra(picture, a, w * 4);
            // Uneven bands, one of them starting on an odd row, last first.
            foreach (var (from, to) in new[] { (30, 45), (13, 30), (0, 13) })
                Nv12Converter.ToBgra(picture, b, w * 4, from, to);
        }
        Check(whole.AsSpan().SequenceEqual(bands), "Bands of rows give the same picture as the whole at once");
        Console.WriteLine("PASS: a picture converted in bands of rows is the same picture");
    }

    // ---------------- what the decoder is given ----------------

    private static void CheckFillerRemoval()
    {
        // Three- and four-byte start codes; filler in the middle and at the end.
        byte[] with = { 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x0C, 0xFF, 0xFF, 0x80, 0, 0, 0, 1, 0x65, 9, 9, 0, 0, 0, 1, 0x0C, 0xFF, 0x80, 0, 0, 1, 0x41, 7 };
        byte[] without = { 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x65, 9, 9, 0, 0, 1, 0x41, 7 };
        byte[] target = new byte[with.Length];
        int n = H264Decoder.CopyWithoutFiller(with, target);
        Check(target.AsSpan(0, n).SequenceEqual(without), "Filler is taken out and everything else is left as it was");

        byte[] plain = { 0, 0, 0, 1, 0x65, 1, 2, 3, 0, 0, 1, 0x41, 0 };
        n = H264Decoder.CopyWithoutFiller(plain, target);
        Check(target.AsSpan(0, n).SequenceEqual(plain), "An access unit without filler is copied whole");

        byte[] atEnd = { 0, 0, 1, 0x41, 5, 5, 0, 0, 1, 0x0C, 0xFF, 0x80 };
        n = H264Decoder.CopyWithoutFiller(atEnd, target);
        Check(target.AsSpan(0, n).SequenceEqual(new byte[] { 0, 0, 1, 0x41, 5, 5 }), "Filler at the end goes, which is where encoders put it");

        Check(H264Decoder.CopyWithoutFiller(new byte[] { 0, 0, 1, 0x0C, 0xFF, 0x80 }, target) == 0, "Nothing but filler leaves nothing");
        Check(H264Decoder.CopyWithoutFiller(ReadOnlySpan<byte>.Empty, target) == 0, "Nothing in, nothing out");
        byte[] noStartCode = { 9, 8, 7, 6, 5 };
        n = H264Decoder.CopyWithoutFiller(noStartCode, target);
        Check(target.AsSpan(0, n).SequenceEqual(noStartCode), "Bytes with no start code among them are passed on untouched");
        byte[] cutShort = { 0, 0, 1, 0x41, 5, 0, 0, 1 };
        n = H264Decoder.CopyWithoutFiller(cutShort, target);
        Check(target.AsSpan(0, n).SequenceEqual(cutShort), "A start code with nothing after it does no harm");
        Console.WriteLine("PASS: filler data is taken out of an access unit before the decoder sees it");
    }

    private static void CheckWhatCountsAsAKeyframe()
    {
        const H264Decoder.Units keyframe = H264Decoder.Units.Keyframe;
        byte[] whole = { 0, 0, 0, 1, 0x09, 0xF0, 0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3 };
        Check((H264Decoder.Scan(whole) & keyframe) == keyframe, "SPS, PPS and an IDR slice together are a keyframe");
        byte[] bare = { 0, 0, 0, 1, 0x65, 3, 4 };
        Check((H264Decoder.Scan(bare) & keyframe) != keyframe, "An IDR slice without its parameter sets is not enough to start from");
        byte[] sets = { 0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2 };
        Check((H264Decoder.Scan(sets) & keyframe) != keyframe, "Parameter sets alone are not a keyframe");
        byte[] ordinary = { 0, 0, 0, 1, 0x41, 3, 4 };
        Check(H264Decoder.Scan(ordinary) == H264Decoder.Units.Slice, "An ordinary frame is a slice and nothing more");
        Check(H264Decoder.Scan(new byte[] { 1, 2, 3 }) == H264Decoder.Units.None, "Bytes that are no stream hold nothing");
        Check(H264Decoder.Scan(new byte[] { 0, 0, 1 }) == H264Decoder.Units.None, "A start code at the very end holds nothing");
        Console.WriteLine("PASS: a keyframe is SPS, PPS and an IDR slice in one access unit, and nothing less");
    }

    // ---------------- the test pattern ----------------

    private static readonly (string name, int r, int g, int b)[] Patches =
    {
        ("top left, red", 255, 0, 0), ("top right, green", 0, 255, 0), ("bottom left, blue", 0, 0, 255), ("bottom right, yellow", 255, 255, 0),
        ("middle, white", 255, 255, 255), ("middle, black", 0, 0, 0), ("middle, grey", 128, 128, 128), ("middle, orange", 230, 120, 40),
    };

    private static int PatchSize(int w, int h) => Math.Max(16, Math.Min(w, h) / 6) & ~1;

    /// <summary>Where patch <paramref name="i"/> sits: one in each corner of the picture, four in a row across its middle.</summary>
    private static (int x, int y) PatchAt(int i, int w, int h)
    {
        int size = PatchSize(w, h);
        return i switch
        {
            0 => (0, 0),
            1 => (w - size, 0),
            2 => (0, h - size),
            3 => (w - size, h - size),
            _ => ((w - size * 4) / 2 + (i - 4) * size, (h - size) / 2),
        };
    }

    private static void Draw(Bitmap bitmap, int frame)
    {
        int w = bitmap.Width, h = bitmap.Height, size = PatchSize(w, h);
        using var g = Graphics.FromImage(bitmap);
        g.Clear(Color.FromArgb(90, 100, 110));
        // Something that moves, so that later frames are not empty.
        g.FillRectangle(Brushes.DimGray, (frame * 6) % Math.Max(1, w - size), h / 2 + size, size, 8);
        for (int i = 0; i < Patches.Length; i++)
        {
            using var brush = new SolidBrush(Color.FromArgb(Patches[i].r, Patches[i].g, Patches[i].b));
            var (x, y) = PatchAt(i, w, h);
            g.FillRectangle(brush, x, y, size, size);
        }
    }

    /// <summary>A stream made on the spot: its access units, in order, the first a keyframe.</summary>
    internal sealed class Clip
    {
        public int Width, Height;
        public bool Hardware;
        public string Encoder = "";
        public List<byte[]> Units = new();
        /// <summary>The standard a decoder reads this stream's colours by: the one it names, or the guess for its size.</summary>
        public Nv12Matrix ReadAs;

        public static string EncoderName(bool hardware) => hardware ? "the graphics card's encoder" : "Windows' software encoder";

        public static Clip? Make(bool hardware, int w, int h, int frames)
        {
            using var encoder = H264Encoder.TryCreate(w, h, 3_000_000, 30, hardware);
            if (encoder == null) return null;
            var clip = new Clip { Width = w, Height = h, Hardware = hardware, Encoder = EncoderName(hardware) };
            using var bitmap = new Bitmap(w, h, PixelFormat.Format32bppRgb);
            // An encoder may take a few pictures before it gives its first.
            for (int i = 0; i < frames + 30 && clip.Units.Count < frames; i++)
            {
                Draw(bitmap, i);
                byte[]? unit = encoder.Encode(bitmap, i == 0);
                if (unit != null) clip.Units.Add(unit);
            }
            if (clip.Units.Count < frames) return null;
            Check((H264Decoder.Scan(clip.Units[0]) & H264Decoder.Units.Keyframe) == H264Decoder.Units.Keyframe,
                $"setup: the first access unit from {clip.Encoder} is a keyframe with its parameter sets");
            if (H264Decoder.TryCreate() is { } decoder)
            {
                using (decoder)
                {
                    decoder.Decode(clip.Units[0], 0, (in Nv12Picture _) => { });
                    clip.ReadAs = decoder.Format.Matrix;
                }
            }
            return clip;
        }

        public override string ToString() => $"{Width} x {Height} from {Encoder}";
    }

    /// <summary>One picture as it came out, converted, with what the decoder said about it.</summary>
    private sealed class Shot
    {
        public Nv12Picture Info;
        public byte[] Bgra = Array.Empty<byte>();
    }

    private static (H264Decoded result, List<Shot> shots) Decode(H264Decoder decoder, byte[] unit, long tag)
    {
        var shots = new List<Shot>();
        var result = decoder.Decode(unit, tag, (in Nv12Picture p) => shots.Add(ToShot(p)));
        return (result, shots);
    }

    private static Shot ToShot(in Nv12Picture p)
    {
        byte[] bgra = new byte[p.Width * 4 * p.Height];
        fixed (byte* d = bgra) Nv12Converter.ToBgra(p, d, p.Width * 4);
        // The pointers die with the handler; only the numbers are looked at later.
        return new Shot { Info = p, Bgra = bgra };
    }

    private static H264Decoder NewDecoder()
        => H264Decoder.TryCreate(out string why) ?? throw new Exception("setup: no decoder: " + why);

    /// <summary>The picture is the pattern: the right size, every patch where it was drawn and the colour it was drawn.</summary>
    private static void CheckIsThePattern(Shot shot, int w, int h, string what)
    {
        var p = shot.Info;
        Check(p.Width == w && p.Height == h, $"{what}: the visible picture is {w} x {h}, got {p.Width} x {p.Height}");
        Check(p.X == 0 && p.Top == 0, $"{what}: the visible picture starts at the top left corner");
        Check(p.CodedWidth >= w && p.CodedWidth < w + 16 && p.CodedWidth % 16 == 0 && p.CodedHeight >= h && p.CodedHeight < h + 16 && p.CodedHeight % 16 == 0,
            $"{what}: the padded picture is the next whole 16-pixel blocks, got {p.CodedWidth} x {p.CodedHeight}");
        Check(p.Stride >= p.CodedWidth, $"{what}: a row is at least as long as the padded width");
        CheckPatches(shot.Bgra, w, h, p.Matrix, what);
    }

    /// <summary>Every patch of the pattern is where it was drawn, in the colour it was drawn, in a picture of w x h as BGRA.</summary>
    internal static void CheckPatches(byte[] bgra, int w, int h, Nv12Matrix readAs, string what)
    {
        int size = PatchSize(w, h);
        for (int i = 0; i < Patches.Length; i++)
        {
            var (x, y) = PatchAt(i, w, h);
            int at = ((y + size / 2) * w + x + size / 2) * 4;
            int r = bgra[at + 2], g = bgra[at + 1], b = bgra[at];
            var want = Expected(Patches[i].r, Patches[i].g, Patches[i].b, readAs);
            int off = Math.Max(Math.Abs(r - want.r), Math.Max(Math.Abs(g - want.g), Math.Abs(b - want.b)));
            _worstColour = Math.Max(_worstColour, off);
            Check(off <= ColourTolerance,
                $"{what}: the patch at the {Patches[i].name} ({Patches[i].r}, {Patches[i].g}, {Patches[i].b}) came back as ({r}, {g}, {b}), read as {readAs}");
        }
    }

    /// <summary>
    /// What a colour should come back as. H264Encoder codes with BT.709. A
    /// picture read as BT.709 gives the colour back. One read as BT.601 does
    /// not, and what it gives instead is worked out here, so that the
    /// decoder's checks hold for it too. That happens to a small picture
    /// whose stream does not name its standard: Windows' software encoder
    /// writes none, whatever it is asked. The app does not send that
    /// encoder's streams, and <see cref="CheckColoursEitherSideOfTheGuess"/>
    /// holds the one it does send to the true colours.
    /// </summary>
    private static (int r, int g, int b) Expected(int r, int g, int b, Nv12Matrix readAs)
    {
        if (readAs == Nv12Matrix.Bt709) return (r, g, b);
        var (ys, yo, vr, ug, vg, ub) = Nv12Converter.Coefficients(Nv12Matrix.Bt709, fullRange: false);
        // Into Y, U and V the BT.709 way: the converter's own numbers, turned round.
        double luma = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double y = yo + luma / ys, u = (b - luma) / ub, v = (r - luma) / vr;
        // And out again the BT.601 way.
        var (ys6, yo6, vr6, ug6, vg6, ub6) = Nv12Converter.Coefficients(Nv12Matrix.Bt601, fullRange: false);
        double yy = (y - yo6) * ys6;
        return ((int)Math.Clamp(Math.Round(yy + vr6 * v), 0, 255),
            (int)Math.Clamp(Math.Round(yy - ug6 * u - vg6 * v), 0, 255),
            (int)Math.Clamp(Math.Round(yy + ub6 * u), 0, 255));
    }

    // ---------------- the decoder ----------------

    private static void CheckRoundTrips(List<bool> kinds)
    {
        // Small, a size that is not whole blocks either way, a phone upright
        // (the S20's, and the S26's which pads both ways), and one on its side.
        var sizes = new[] { (320, 240), (322, 242), (1080, 2400), (1080, 2340), (2400, 1080) };
        int ran = 0;
        foreach (var (w, h) in sizes)
        {
            bool any = false;
            foreach (bool hardware in kinds)
            {
                Clip? clip = Clip.Make(hardware, w, h, w * h > 500_000 ? 5 : 12);
                if (clip == null)
                {
                    Console.WriteLine($"  (no {w} x {h} from {Clip.EncoderName(hardware)} on this PC)");
                    continue;
                }
                any = true;
                ran++;
                using var decoder = NewDecoder();
                for (int i = 0; i < clip.Units.Count; i++)
                {
                    var (result, shots) = Decode(decoder, clip.Units[i], 1000 + i);
                    string what = $"{clip}, access unit {i}";
                    Check(result == H264Decoded.Picture, $"{what}: a picture comes out ({result} {decoder.LastFailure})");
                    Check(shots.Count == 1, $"{what}: one picture for one access unit, got {shots.Count}");
                    Check(shots[0].Info.Tag == 1000 + i, $"{what}: the picture carries the number its access unit was given");
                    CheckIsThePattern(shots[0], w, h, what);
                }
                Check(decoder.SizeAnnouncements == 1, $"{clip}: the decoder announces the size once, at the first picture");
            }
            Check(any, $"Some encoder on this PC makes a {w} x {h} stream to try");
        }
        Console.WriteLine($"PASS: {ran} streams made here decode to the pictures that went in, one for each access unit, tall, wide and padded sizes alike");
    }

    private static void CheckColoursEitherSideOfTheGuess(List<bool> kinds)
    {
        // What a phone shows of this PC's screen. A decoder turns the picture
        // back into colours by the standard the stream names. Where a stream
        // names none, Android guesses by size, BT.601 up to 720 x 576 and
        // BT.709 above, and H264Decoder guesses the same way. The app sends
        // the graphics card's encoder's streams, and those must come back in
        // their true colours at a size on each side of that line.
        _worstColour = 0;
        var said = new List<string>();
        bool looked = false;
        foreach (bool hardware in kinds)
        {
            foreach (var (w, h) in new[] { (320, 240), (720, 576), (1280, 720) })
            {
                Clip? clip = Clip.Make(hardware, w, h, 3);
                if (clip == null) continue;
                using var decoder = NewDecoder();
                var (result, shots) = Decode(decoder, clip.Units[0], 1);
                Check(result == H264Decoded.Picture && shots.Count == 1, $"setup: {clip} decodes ({result} {decoder.LastFailure})");
                var f = decoder.Format;
                said.Add($"{Clip.EncoderName(hardware)} at {w} x {h}: {(f.MatrixGiven ? f.Matrix.ToString() : "no standard")}, {(f.RangeGiven ? (f.FullRange ? "full range" : "16 to 235") : "no range")}");
                // H264Encoder asks for BT.709, 16 to 235, and converts to that. An
                // encoder may leave the name out, but must not write another.
                Check(!f.MatrixGiven || f.Matrix == Nv12Matrix.Bt709, $"{clip}: the stream does not name another standard than the one its colours were made with");
                Check(!f.RangeGiven || !f.FullRange, $"{clip}: the stream does not claim the full range");
                if (!hardware) continue;
                looked = true;
                Check(f.Matrix == Nv12Matrix.Bt709, $"{clip}: the stream the app sends is read as BT.709, which is what its colours were coded with");
                CheckIsThePattern(shots[0], w, h, $"{clip}, colours");
            }
        }
        if (!looked) Console.WriteLine("  (no graphics card's encoder here, which is the only one the app sends from: its colours were not looked at)");
        Console.WriteLine($"PASS: the colours that go into the encoder the app uses come back, small pictures and large{(looked ? $" (off by {_worstColour} of 255 at most)" : "")}; the streams name: {string.Join("; ", said)}");
    }

    private static void CheckEncoderColourNumbers()
    {
        // The encoder's own step from the screen's colours to Y, U and V, with
        // no codec in the way. BT.709, 16 to 235, worked out by hand:
        //   Y = 16 + 219 (0.2126 R + 0.7152 G + 0.0722 B) / 255
        //   U = 128 + 224 (B' - Y') / 1.8556 / 255,  V = 128 + 224 (R' - Y') / 1.5748 / 255
        (int r, int g, int b, int y, int u, int v)[] known =
        {
            (255, 255, 255, 235, 128, 128), (0, 0, 0, 16, 128, 128), (128, 128, 128, 126, 128, 128),
            (255, 0, 0, 63, 102, 240), (0, 255, 0, 173, 42, 26), (0, 0, 255, 32, 240, 118),
            (255, 255, 0, 219, 16, 138), (0, 255, 255, 188, 154, 16), (230, 120, 40, 134, 82, 180),
        };
        const int block = 4;
        int w = known.Length * block, h = block;
        using var bitmap = new Bitmap(w, h, PixelFormat.Format32bppRgb);
        using (var g = Graphics.FromImage(bitmap))
        {
            for (int i = 0; i < known.Length; i++)
            {
                using var brush = new SolidBrush(Color.FromArgb(known[i].r, known[i].g, known[i].b));
                g.FillRectangle(brush, i * block, 0, block, block);
            }
        }
        byte[] nv12 = new byte[w * h * 3 / 2];
        H264Encoder.BgraToNv12(bitmap, nv12, w, h);
        for (int i = 0; i < known.Length; i++)
        {
            var k = known[i];
            int x = i * block + 1;
            int y = nv12[w + x], u = nv12[w * h + (x & ~1)], v = nv12[w * h + (x & ~1) + 1];
            // Whole-number weights: a step out at most.
            Check(Math.Abs(y - k.y) <= 1 && Math.Abs(u - k.u) <= 1 && Math.Abs(v - k.v) <= 1,
                $"The encoder codes ({k.r}, {k.g}, {k.b}) as BT.709 says, Y {k.y} U {k.u} V {k.v}; got Y {y} U {u} V {v}");

            // And the viewer's converter, told BT.709, gives the colour back.
            byte[] one = { (byte)y, (byte)y, 0, 0, (byte)y, (byte)y, 0, 0, (byte)u, (byte)v, 0, 0 };
            byte[] bgra = new byte[16];
            fixed (byte* s = one)
            fixed (byte* d = bgra)
            {
                Nv12Converter.ToBgra(new Nv12Picture { Y = s, Uv = s + 8, Stride = 4, CodedWidth = 2, CodedHeight = 2, Width = 2, Height = 2, Matrix = Nv12Matrix.Bt709 }, d, 8);
            }
            Check(Math.Abs(bgra[2] - k.r) <= 3 && Math.Abs(bgra[1] - k.g) <= 3 && Math.Abs(bgra[0] - k.b) <= 3,
                $"({k.r}, {k.g}, {k.b}) coded by the encoder and read back as BT.709 is itself again; got ({bgra[2]}, {bgra[1]}, {bgra[0]})");
        }
        Console.WriteLine("PASS: the encoder turns the screen's colours into the Y, U and V of the standard its stream names");
    }

    private static void CheckFillerDoesNotHoldAPictureBack(Clip clip)
    {
        // The decoder itself keeps a picture whose access unit ends in filler
        // until the next one arrives. With filler after every access unit,
        // each must still give its own picture at once.
        byte[] filler = new byte[4 + 1 + 200 + 1];
        filler[3] = 1; filler[4] = 0x0C;
        Array.Fill(filler, (byte)0xFF, 5, 200);
        filler[^1] = 0x80;
        using var decoder = NewDecoder();
        for (int i = 0; i < clip.Units.Count; i++)
        {
            var (result, shots) = Decode(decoder, clip.Units[i].Concat(filler).ToArray(), i);
            Check(result == H264Decoded.Picture && shots.Count == 1 && shots[0].Info.Tag == i,
                $"With filler after it, access unit {i} still gives its own picture at once ({result}, {shots.Count})");
        }
        Pass("an encoder's padding does not make the decoder run a picture behind");
    }

    private static void CheckAFreshStartWantsAKeyframe(Clip clip)
    {
        using var decoder = NewDecoder();
        Check(Decode(decoder, clip.Units[1], 1).result == H264Decoded.NeedsKeyframe, "A new decoder given an ordinary frame asks for a keyframe");
        Check(Decode(decoder, new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, 2).result == H264Decoded.NeedsKeyframe, "A new decoder given rubbish asks for a keyframe");
        Check(Decode(decoder, Array.Empty<byte>(), 3).result == H264Decoded.NeedsKeyframe, "A new decoder given nothing asks for a keyframe");

        // The keyframe's slices without its parameter sets: a decoder that
        // was fed this would show nothing and say nothing.
        byte[] key = clip.Units[0];
        byte[] slicesOnly = WithoutUnits(key, 7, 8);
        Check(slicesOnly.Length < key.Length && (H264Decoder.Scan(slicesOnly) & H264Decoder.Units.Idr) != 0, "setup: the keyframe without its parameter sets");
        Check(Decode(decoder, slicesOnly, 4).result == H264Decoded.NeedsKeyframe, "A keyframe without its parameter sets is not taken as a start");

        var (result, shots) = Decode(decoder, key, 5);
        Check(result == H264Decoded.Picture && shots.Count == 1, "The whole keyframe gives a picture");
        CheckIsThePattern(shots[0], clip.Width, clip.Height, "after frames that could not be used");
        Check(Decode(decoder, clip.Units[1], 6).result == H264Decoded.Picture, "And the frame after it gives the next");
        Pass("a decoder at a fresh start takes nothing but a whole keyframe, and says so");
    }

    private static void CheckReset(Clip clip)
    {
        using var decoder = NewDecoder();
        var first = new List<Shot>();
        for (int i = 0; i < 6; i++) first.AddRange(Decode(decoder, clip.Units[i], i).shots);
        Check(first.Count == 6, "setup: six pictures before the reset");

        decoder.Reset();
        Check(Decode(decoder, clip.Units[6], 6).result == H264Decoded.NeedsKeyframe, "After a reset the next ordinary frame is not decoded");
        Check(Decode(decoder, clip.Units[7], 7).result == H264Decoded.NeedsKeyframe, "Nor the one after: it waits for a keyframe");
        for (int i = 0; i < 6; i++)
        {
            var (result, shots) = Decode(decoder, clip.Units[i], 100 + i);
            Check(result == H264Decoded.Picture && shots.Count == 1 && shots[0].Info.Tag == 100 + i, $"After a reset the stream decodes again from its keyframe (access unit {i}: {result})");
            Check(shots[0].Bgra.AsSpan().SequenceEqual(first[i].Bgra), $"And gives the same picture as the first time (access unit {i})");
        }
        Check(decoder.SizeAnnouncements == 1, "A reset to the same size announces nothing new");
        decoder.Reset();
        decoder.Reset();
        Check(Decode(decoder, clip.Units[0], 200).result == H264Decoded.Picture, "Resets on end do no harm");
        Pass("a reset forgets the stream, and the next keyframe starts it again");
    }

    private static void CheckGarbage(Clip clip)
    {
        var random = new Random(3);
        byte[] noise = new byte[4000];
        random.NextBytes(noise);
        byte[] key = clip.Units[0];

        // Each of these goes into a running stream. Whatever the decoder
        // makes of it, nothing may throw, and a reset and a keyframe must
        // bring the true picture back.
        var rubbish = new (string name, byte[] bytes)[]
        {
            ("noise", noise),
            ("noise dressed as an ordinary frame", new byte[] { 0, 0, 0, 1, 0x41 }.Concat(noise).ToArray()),
            ("noise dressed as a keyframe", WithoutUnits(key, 5).Concat(new byte[] { 0, 0, 0, 1, 0x65 }).Concat(noise).ToArray()),
            ("a keyframe cut off half way", key.AsSpan(0, key.Length / 2).ToArray()),
            ("a keyframe with a bite out of its middle", key.AsSpan(0, key.Length / 3).ToArray().Concat(key.AsSpan(key.Length / 2).ToArray()).ToArray()),
            ("an ordinary frame cut off half way", clip.Units[3].AsSpan(0, clip.Units[3].Length / 2).ToArray()),
            ("start codes and nothing else", new byte[] { 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1 }),
            ("one byte", new byte[] { 0x65 }),
        };
        using var decoder = NewDecoder();
        long tag = 0;
        foreach (var (name, bytes) in rubbish)
        {
            Check(Decode(decoder, key, ++tag).result == H264Decoded.Picture, $"setup: a running stream before {name}");
            Check(Decode(decoder, clip.Units[1], ++tag).result == H264Decoded.Picture, $"setup: a running stream before {name}");

            var (result, _) = Decode(decoder, bytes, ++tag);
            Check(result is H264Decoded.Picture or H264Decoded.NoPicture or H264Decoded.Failed or H264Decoded.NeedsKeyframe, $"{name}: an answer, not an exception");
            // Whatever followed would be drawn over a broken picture, so the viewer resets.
            decoder.Reset();
            Check(Decode(decoder, clip.Units[2], ++tag).result == H264Decoded.NeedsKeyframe, $"After {name} and a reset, the decoder waits for a keyframe");
            var (again, shots) = Decode(decoder, key, ++tag);
            Check(again == H264Decoded.Picture && shots.Count == 1, $"After {name}, a keyframe gives a picture again ({again} {decoder.LastFailure})");
            CheckIsThePattern(shots[0], clip.Width, clip.Height, $"after {name}");
        }

        // The same without the reset: the decoder must still be usable.
        using var second = NewDecoder();
        foreach (var (name, bytes) in rubbish)
        {
            Decode(second, key, ++tag);
            Decode(second, bytes, ++tag);
            var (again, shots) = Decode(second, key, ++tag);
            Check(again == H264Decoded.Picture && shots.Count >= 1, $"After {name} with no reset, a keyframe still gives a picture ({again} {second.LastFailure})");
            CheckIsThePattern(shots[^1], clip.Width, clip.Height, $"after {name} with no reset");
        }
        Pass("rubbish in the stream throws nothing, and the next keyframe brings the picture back");
    }

    private static void CheckAKeyframeWithoutAPictureIsAFailure(Clip clip)
    {
        // A decoder that cannot take this size, as an older Windows may not
        // take a tall phone's: it reports no error, it just gives nothing.
        using var decoder = H264Decoder.TryCreate(1920, 1088, out string why);
        Check(decoder != null, "setup: a decoder limited to 1920 x 1088: " + why);
        var (result, shots) = Decode(decoder!, clip.Units[0], 1);
        Check(result == H264Decoded.Failed && shots.Count == 0, $"A keyframe that gives no picture is a failure, not silence ({result})");
        Check(decoder!.LastFailure.Contains("keyframe"), $"And the reason is kept: \"{decoder.LastFailure}\"");
        Check(Decode(decoder, clip.Units[1], 2).result == H264Decoded.NeedsKeyframe, "After a failure the decoder wants a keyframe");
        Check(Decode(decoder, clip.Units[0], 3).result == H264Decoded.Failed, "And fails again on the next, so the viewer can count to two and give up");

        // This class's own limit, on the number of pixels: a stream whose
        // size is absurd is refused before any memory is found for it.
        Check(H264Decoder.MaxPixels >= 1088 * 2640 && H264Decoder.MaxPixels <= 2400 * 2400, "The limit leaves room for any phone's screen and not for much more");
        using var small = H264Decoder.TryCreate(4096, 4096, out why, maxPixels: 600_000);
        Check(small != null, "setup: a decoder limited to 600,000 pixels: " + why);
        (result, shots) = Decode(small!, clip.Units[0], 1);
        Check(result == H264Decoded.Failed && shots.Count == 0, $"A picture with more pixels than the limit is a failure ({result})");
        Check(small!.LastFailure.Contains("larger than a phone's screen") && small.LastFailure.Contains("1088 x 2400"), $"And the reason names the size: \"{small.LastFailure}\"");
        Check(small.Format.CodedHeight != 2400, "Nothing was made ready for a picture of that size");
        Pass("a decoder that cannot decode the stream is found out at its first keyframe");
    }

    private static void CheckATurnedPhone(Clip upright, Clip turned)
    {
        using var decoder = NewDecoder();
        long tag = 0;
        void Play(Clip clip, string when)
        {
            for (int i = 0; i < 4; i++)
            {
                var (result, shots) = Decode(decoder, clip.Units[i], ++tag);
                Check(result == H264Decoded.Picture && shots.Count == 1 && shots[0].Info.Tag == tag, $"{when}, access unit {i}: a picture ({result} {decoder.LastFailure})");
                CheckIsThePattern(shots[0], clip.Width, clip.Height, when);
            }
        }
        Play(upright, "before the turn");
        // No reset and no new decoder: the stream simply carries on at another size.
        Play(turned, "after the turn");
        Check(decoder.SizeAnnouncements == 2, "The decoder announces the new size");
        Check(decoder.Format.Width == turned.Width && decoder.Format.Height == turned.Height, "And says what it is");
        Play(upright, "turned back");
        Check(decoder.SizeAnnouncements == 3, "And again when the phone is turned back");
        Pass("a stream that changes size half way goes on through the same decoder");
    }

    private sealed class HandlerTrouble : Exception { }

    private static void CheckTheHandler(Clip clip)
    {
        using var decoder = NewDecoder();
        int thread = 0;
        decoder.Decode(clip.Units[0], 1, (in Nv12Picture _) => thread = Environment.CurrentManagedThreadId);
        Check(thread == Environment.CurrentManagedThreadId, "The handler runs on the thread that asked for the decoding");

        // A handler that throws: its exception comes out, and it is not the decoder's failure.
        bool came = false;
        try { decoder.Decode(clip.Units[1], 2, (in Nv12Picture _) => throw new HandlerTrouble()); }
        catch (HandlerTrouble) { came = true; }
        Check(came, "An exception thrown in the handler comes out of Decode");
        Check(Decode(decoder, clip.Units[2], 3).result == H264Decoded.Picture, "And the decoder carries on with the next frame");

        // One call at a time: a call from inside the handler is a second call.
        bool refusedDecode = false, refusedReset = false;
        decoder.Decode(clip.Units[3], 4, (in Nv12Picture _) =>
        {
            try { decoder.Decode(clip.Units[4], 5, (in Nv12Picture _) => { }); } catch (InvalidOperationException) { refusedDecode = true; }
            try { decoder.Reset(); } catch (InvalidOperationException) { refusedReset = true; }
        });
        Check(refusedDecode && refusedReset, "A second call while one is in progress is refused");
        Check(Decode(decoder, clip.Units[4], 6).result == H264Decoded.Picture, "And the call in progress is not disturbed by it");

        // Dispose from inside the handler: the picture stays good to the end of the handler.
        byte[]? kept = null;
        var last = decoder.Decode(clip.Units[5], 7, (in Nv12Picture p) =>
        {
            decoder.Dispose();
            kept = ToShot(p).Bgra;
        });
        Check(last == H264Decoded.Picture && kept != null, "A handler may dispose the decoder it is called by");
        Check(Decode(decoder, clip.Units[6], 8).result == H264Decoded.Disposed, "After which the decoder is gone");
        Pass("pictures are handed over on the caller's thread, one call at a time");
    }

    private static void CheckDisposeWhileDecoding(Clip clip)
    {
        var decoder = NewDecoder();
        using var inside = new ManualResetEventSlim();
        using var disposed = new ManualResetEventSlim();
        Shot? before = null, after = null;
        H264Decoded result = default;
        H264Decoded next = default;
        Exception? trouble = null;
        var decoding = new Thread(() =>
        {
            try
            {
                result = decoder.Decode(clip.Units[0], 1, (in Nv12Picture p) =>
                {
                    before = ToShot(p);
                    inside.Set();
                    // The window closes while this picture is in hand.
                    disposed.Wait(TimeSpan.FromSeconds(10));
                    after = ToShot(p);
                });
                next = decoder.Decode(clip.Units[1], 2, (in Nv12Picture _) => { });
            }
            catch (Exception e) { trouble = e; }
        });
        decoding.Start();
        Check(inside.Wait(TimeSpan.FromSeconds(10)), "setup: a picture is in the handler's hands");
        var clock = Stopwatch.StartNew();
        decoder.Dispose();
        long waited = clock.ElapsedMilliseconds;
        disposed.Set();
        Check(decoding.Join(TimeSpan.FromSeconds(10)), "The decoding thread finishes");
        Check(trouble == null, "Nothing is thrown on the decoding thread: " + trouble);
        Check(waited < 1000, $"Dispose does not wait for the picture in hand ({waited} ms)");
        Check(result == H264Decoded.Picture, "The picture in hand is finished");
        Check(before != null && after != null && before.Bgra.AsSpan().SequenceEqual(after.Bgra), "And its memory stays good until the handler is done with it");
        CheckIsThePattern(after!, clip.Width, clip.Height, "the picture in hand while the decoder was disposed");
        Check(next == H264Decoded.Disposed, "The next frame finds the decoder gone and does nothing");
        decoder.Dispose();
        decoder.Reset();
        Check(Decode(decoder, clip.Units[0], 3).result == H264Decoded.Disposed, "Disposing twice, and calls afterwards, do no harm");

        // And many times over with no pause, to give a mistake the chance to show.
        for (int round = 0; round < 20; round++)
        {
            var one = NewDecoder();
            var worker = new Thread(() =>
            {
                for (int i = 0; i < clip.Units.Count; i++)
                    if (one.Decode(clip.Units[i], i, (in Nv12Picture p) => ToShot(p)) == H264Decoded.Disposed) break;
            });
            worker.Start();
            Thread.Sleep(round % 7);
            one.Dispose();
            Check(worker.Join(TimeSpan.FromSeconds(10)), "A decoder disposed at any moment lets its thread finish");
        }
        Pass("a decoder disposed while it decodes finishes the picture in hand and then goes");
    }

    private static void CheckThreads(Clip clip)
    {
        // Made on one thread, used on a second, disposed on a third; and made
        // on the kind of thread a window lives on.
        H264Decoder? decoder = null;
        var maker = new Thread(() => decoder = H264Decoder.TryCreate());
        maker.SetApartmentState(ApartmentState.STA);
        maker.Start();
        maker.Join();
        Check(decoder != null, "A decoder can be made on a window's thread");

        Shot? shot = null;
        H264Decoded result = default;
        var user = new Thread(() => { var r = Decode(decoder!, clip.Units[0], 1); result = r.result; shot = r.shots.FirstOrDefault(); });
        user.Start();
        user.Join();
        Check(result == H264Decoded.Picture && shot != null, "And used on another");
        CheckIsThePattern(shot!, clip.Width, clip.Height, "decoded on another thread than made");
        Task.Run(() => decoder!.Dispose()).Wait();
        Check(Decode(decoder!, clip.Units[1], 2).result == H264Decoded.Disposed, "And disposed on a third");

        H264Decoded pooled = Task.Run(() =>
        {
            using var mine = H264Decoder.TryCreate();
            return mine == null ? H264Decoded.Failed : Decode(mine, clip.Units[0], 1).result;
        }).Result;
        Check(pooled == H264Decoded.Picture, "A decoder made and used on a pool thread works too");
        Pass("the decoder does not care which thread makes it, uses it or lets it go");
    }

    private static void CheckOneDecoderAfterAnother(Clip clip, Clip turned)
    {
        // A window closed and another opened at once. The second decoder's
        // first picture has been seen to come a few milliseconds after its
        // keyframe went in, where the first's came at once; and so has the
        // first picture after a change of size. Neither may be taken for a
        // keyframe that gave no picture. It shows when the processor is
        // busy, so for this one check every core is kept busy.
        bool stop = false;
        var busy = Enumerable.Range(0, Environment.ProcessorCount).Select(_ => new Thread(() =>
        {
            while (!Volatile.Read(ref stop)) Thread.SpinWait(1000);
        }) { IsBackground = true }).ToList();
        foreach (var thread in busy) thread.Start();
        try
        {
            for (int round = 0; round < 12; round++)
            {
                using var decoder = NewDecoder();
                var (result, shots) = Decode(decoder, clip.Units[0], round);
                Check(result == H264Decoded.Picture && shots.Count == 1,
                    $"A decoder made straight after another was let go gives its first keyframe's picture (round {round}: {result} {decoder.LastFailure})");
                (result, shots) = Decode(decoder, turned.Units[0], round);
                Check(result == H264Decoded.Picture && shots.Count == 1 && shots[0].Info.Width == turned.Width,
                    $"And the first picture at another size (round {round}: {result} {decoder.LastFailure})");
                // The frames that follow come at once even so.
                for (int i = 1; i < turned.Units.Count; i++)
                {
                    (result, shots) = Decode(decoder, turned.Units[i], i);
                    Check(result == H264Decoded.Picture && shots.Count == 1 && shots[0].Info.Tag == i,
                        $"An ordinary frame's picture comes with its frame, busy or not (round {round}, frame {i}: {result}, {shots.Count})");
                }
            }
        }
        finally
        {
            Volatile.Write(ref stop, true);
            foreach (var thread in busy) thread.Join();
        }
        Pass("a decoder's first picture, and the first after a change of size, is waited for when it comes a moment late");
    }

    // ---------------- helpers ----------------

    /// <summary>An access unit with every NAL unit of the given types taken out.</summary>
    internal static byte[] WithoutUnits(byte[] unit, params int[] types)
    {
        // Where each unit begins (its start code's first zero) and where its header byte is.
        var units = new List<(int start, int header)>();
        for (int i = 0; i + 3 < unit.Length; i++)
        {
            if (unit[i] != 0 || unit[i + 1] != 0 || unit[i + 2] != 1) continue;
            units.Add((i > 0 && unit[i - 1] == 0 ? i - 1 : i, i + 3));
            i += 2;
        }
        var kept = new List<byte>();
        for (int n = 0; n < units.Count; n++)
        {
            int to = n + 1 < units.Count ? units[n + 1].start : unit.Length;
            if (types.Contains(unit[units[n].header] & 0x1F)) continue;
            kept.AddRange(unit.AsSpan(units[n].start, to - units[n].start).ToArray());
        }
        return kept.ToArray();
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
