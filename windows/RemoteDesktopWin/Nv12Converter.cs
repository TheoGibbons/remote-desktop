namespace RemoteDesktopWin;

/// <summary>Which numbers turn Y, U and V into red, green and blue.</summary>
internal enum Nv12Matrix { Bt709, Bt601 }

/// <summary>
/// One decoded picture as a decoder leaves it: a plane of Y, then a plane of
/// U and V interleaved at half size each way (NV12), padded to whole 16-pixel
/// blocks. The memory is the decoder's: see <see cref="H264Decoder.Decode"/>
/// for how long it lasts.
/// </summary>
internal readonly unsafe struct Nv12Picture
{
    /// <summary>First byte of the Y plane's first row (row 0 of the padded picture).</summary>
    public byte* Y { get; init; }
    /// <summary>First byte of the UV plane's first row.</summary>
    public byte* Uv { get; init; }
    /// <summary>Bytes from one row to the next, the same for both planes.</summary>
    public int Stride { get; init; }
    /// <summary>The padded size the decoder works in.</summary>
    public int CodedWidth { get; init; }
    public int CodedHeight { get; init; }
    /// <summary>The part of it that is picture.</summary>
    public int X { get; init; }
    public int Top { get; init; }
    public int Width { get; init; }
    public int Height { get; init; }
    public Nv12Matrix Matrix { get; init; }
    /// <summary>True: Y runs 0 to 255. False: 16 to 235, which is what phones send.</summary>
    public bool FullRange { get; init; }
    /// <summary>The number given with the access unit this picture was decoded from.</summary>
    public long Tag { get; init; }
}

/// <summary>
/// Turns a decoded picture into the BGRA a WriteableBitmap wants. Only the
/// visible part is written; the decoder's padding is skipped.
///
/// Plain code with look-up tables: every multiplication is done once per
/// table entry, so a pixel costs three additions and three clamps. A picture
/// of 1080 x 2340 takes 3 to 6 ms on one thread of a desktop (check C1 in
/// PHONE-VIDEO-PLAN.md), which is about what decoding it takes.
///
/// Two ways to make it quicker, neither needed yet. Rows do not depend on
/// each other, so <see cref="ToBgra(in Nv12Picture, byte*, int, int, int)"/>
/// can be given bands of rows on several threads (four bands: a third of the
/// time by the clock). And the inner loop handles one pair of pixels with
/// nothing carried over to the next, which is the shape vector code needs.
///
/// No state but the tables, which are built once and never change, so it may
/// be called from any thread, and from several at once.
/// </summary>
internal static unsafe class Nv12Converter
{
    private const int Shift = 16;
    private const int ClampOffset = 384;   // sums run from about -290 to 550
    private const int ClampSize = 1024;

    private sealed class Tables
    {
        public readonly int[] Y = GC.AllocateArray<int>(256, pinned: true);
        public readonly int[] VToR = GC.AllocateArray<int>(256, pinned: true);
        public readonly int[] UToG = GC.AllocateArray<int>(256, pinned: true);
        public readonly int[] VToG = GC.AllocateArray<int>(256, pinned: true);
        public readonly int[] UToB = GC.AllocateArray<int>(256, pinned: true);
    }

    private static readonly byte[] Clamp = BuildClamp();
    private static readonly Tables?[] Cache = new Tables?[4];

    /// <summary>
    /// The numbers behind a conversion: R = s(Y - o) + vr V, G = s(Y - o) -
    /// ug U - vg V, B = s(Y - o) + ub U, with U and V measured from 128. They
    /// follow from the standard's two weights (Kr and Kb) and the range. Here
    /// so that a test can do the same sum the slow way.
    /// </summary>
    public static (double yScale, int yOffset, double vToR, double uToG, double vToG, double uToB)
        Coefficients(Nv12Matrix matrix, bool fullRange)
    {
        double kr = matrix == Nv12Matrix.Bt709 ? 0.2126 : 0.299;
        double kb = matrix == Nv12Matrix.Bt709 ? 0.0722 : 0.114;
        double kg = 1 - kr - kb;
        double yScale = fullRange ? 1.0 : 255.0 / 219.0;
        double cScale = fullRange ? 1.0 : 255.0 / 224.0;
        return (yScale, fullRange ? 0 : 16,
            2 * (1 - kr) * cScale,
            2 * (1 - kb) * kb / kg * cScale,
            2 * (1 - kr) * kr / kg * cScale,
            2 * (1 - kb) * cScale);
    }

    private static byte[] BuildClamp()
    {
        var t = GC.AllocateArray<byte>(ClampSize, pinned: true);
        for (int i = 0; i < ClampSize; i++) t[i] = (byte)Math.Clamp(i - ClampOffset, 0, 255);
        return t;
    }

    private static Tables TablesFor(Nv12Matrix matrix, bool fullRange)
    {
        int slot = (matrix == Nv12Matrix.Bt709 ? 0 : 2) + (fullRange ? 1 : 0);
        var t = Volatile.Read(ref Cache[slot]);
        if (t != null) return t;

        var (yScale, yOffset, vToR, uToG, vToG, uToB) = Coefficients(matrix, fullRange);
        t = new Tables();
        double one = 1 << Shift;
        for (int i = 0; i < 256; i++)
        {
            // The rounding half and the clamp table's offset ride in the Y table.
            t.Y[i] = (int)Math.Round((i - yOffset) * yScale * one) + (1 << (Shift - 1)) + (ClampOffset << Shift);
            t.VToR[i] = (int)Math.Round((i - 128) * vToR * one);
            t.UToG[i] = -(int)Math.Round((i - 128) * uToG * one);
            t.VToG[i] = -(int)Math.Round((i - 128) * vToG * one);
            t.UToB[i] = (int)Math.Round((i - 128) * uToB * one);
        }
        // Two threads may both build a table the first time; either will do.
        Volatile.Write(ref Cache[slot], t);
        return t;
    }

    /// <summary>
    /// The whole visible picture into <paramref name="dst"/>, top row first,
    /// four bytes a pixel (blue, green, red, 255). <paramref name="dstStride"/>
    /// is at least Width * 4.
    /// </summary>
    public static void ToBgra(in Nv12Picture src, byte* dst, int dstStride)
        => ToBgra(src, dst, dstStride, 0, src.Height);

    /// <summary>
    /// Rows <paramref name="rowFrom"/> up to, not including,
    /// <paramref name="rowTo"/> of the visible picture, written at their own
    /// place in <paramref name="dst"/>. Bands of rows may be converted on
    /// different threads at once.
    /// </summary>
    public static void ToBgra(in Nv12Picture src, byte* dst, int dstStride, int rowFrom, int rowTo)
    {
        var t = TablesFor(src.Matrix, src.FullRange);
        int width = src.Width;
        int pairs = width / 2;
        // U and V are shared by 2 x 2 pixels of the padded picture, so the
        // quick loop needs the visible part to start on an even column.
        bool evenStart = (src.X & 1) == 0;

        fixed (int* yT = t.Y, vr = t.VToR, ug = t.UToG, vg = t.VToG, ub = t.UToB)
        fixed (byte* clamp = Clamp)
        {
            for (int row = rowFrom; row < rowTo; row++)
            {
                int srcRow = src.Top + row;
                byte* y = src.Y + (long)srcRow * src.Stride + src.X;
                byte* uv = src.Uv + (long)(srcRow >> 1) * src.Stride + (src.X & ~1);
                uint* d = (uint*)(dst + (long)row * dstStride);

                if (!evenStart)
                {
                    for (int x = 0; x < width; x++)
                    {
                        int c = ((src.X + x) & ~1) - (src.X & ~1);
                        int u = uv[c], v = uv[c + 1];
                        int yy = yT[y[x]];
                        d[x] = 0xFF000000u
                            | (uint)clamp[(yy + vr[v]) >> Shift] << 16
                            | (uint)clamp[(yy + ug[u] + vg[v]) >> Shift] << 8
                            | clamp[(yy + ub[u]) >> Shift];
                    }
                    continue;
                }

                for (int p = 0; p < pairs; p++)
                {
                    int u = uv[0], v = uv[1];
                    int r = vr[v], g = ug[u] + vg[v], b = ub[u];
                    int y0 = yT[y[0]], y1 = yT[y[1]];
                    d[0] = 0xFF000000u
                        | (uint)clamp[(y0 + r) >> Shift] << 16
                        | (uint)clamp[(y0 + g) >> Shift] << 8
                        | clamp[(y0 + b) >> Shift];
                    d[1] = 0xFF000000u
                        | (uint)clamp[(y1 + r) >> Shift] << 16
                        | (uint)clamp[(y1 + g) >> Shift] << 8
                        | clamp[(y1 + b) >> Shift];
                    y += 2; uv += 2; d += 2;
                }
                if ((width & 1) != 0)
                {
                    int u = uv[0], v = uv[1];
                    int y0 = yT[y[0]];
                    d[0] = 0xFF000000u
                        | (uint)clamp[(y0 + vr[v]) >> Shift] << 16
                        | (uint)clamp[(y0 + ug[u] + vg[v]) >> Shift] << 8
                        | clamp[(y0 + ub[u]) >> Shift];
                }
            }
        }
    }
}
