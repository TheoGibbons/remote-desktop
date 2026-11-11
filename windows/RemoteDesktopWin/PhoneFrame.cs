using System.Buffers.Binary;
using System.Globalization;

namespace RemoteDesktopWin;

// Frame type 5: one picture of a phone's screen behind a header that says
// which stream it belongs to, where it comes in that stream, how big it is
// and when it was captured (PROTOCOL.md, "Phone frames"). The Android app
// writes and reads the same bytes in PhoneFrame.kt, and both are held to the
// vectors below. No WPF here: RemoteDesktopWin.PhoneViewTests compiles this
// file in.
//
//   [stream u64][seq u32][flags u8][width u16][height u16][captureMs u32][picture bytes]
//
// Integers are big-endian.

/// <param name="Stream">The stream's id.</param>
/// <param name="Seq">0 for a stream's first frame and one more for each after it.</param>
/// <param name="Keyframe">A picture that needs no earlier one. Every JPEG is.</param>
/// <param name="Format"><see cref="PhoneFrame.Jpeg"/> or <see cref="PhoneFrame.H264"/>; 2 and 3 are not used yet.</param>
/// <param name="Sideways">The picture is the screen turned 90° clockwise. Not used yet.</param>
/// <param name="Repeat">The same screen as an earlier frame, sent again.</param>
/// <param name="CaptureMs">The low 32 bits of the phone's uptime clock when the screen was captured.</param>
internal readonly record struct PhoneFrameHeader(ulong Stream, uint Seq, bool Keyframe, int Format, bool Sideways,
    bool Repeat, int Width, int Height, uint CaptureMs);

internal static class PhoneFrame
{
    public const int HeaderSize = 21;

    /// <summary><c>flags</c> bits 1 and 2: what the picture bytes are.</summary>
    public const int Jpeg = 0, H264 = 1;

    private const int KeyframeBit = 0x01, FormatShift = 1, FormatMask = 0x03, SidewaysBit = 0x08, RepeatBit = 0x10;

    /// <summary>Reads the header at the start of <paramref name="frame"/>;
    /// false if it is too short to hold one.</summary>
    public static bool TryParse(ReadOnlySpan<byte> frame, out PhoneFrameHeader header)
    {
        header = default;
        if (frame.Length < HeaderSize) return false;
        int flags = frame[12];
        header = new PhoneFrameHeader(
            Stream: BinaryPrimitives.ReadUInt64BigEndian(frame),
            Seq: BinaryPrimitives.ReadUInt32BigEndian(frame[8..]),
            Keyframe: (flags & KeyframeBit) != 0,
            Format: (flags >> FormatShift) & FormatMask,
            Sideways: (flags & SidewaysBit) != 0,
            Repeat: (flags & RepeatBit) != 0,
            Width: BinaryPrimitives.ReadUInt16BigEndian(frame[13..]),
            Height: BinaryPrimitives.ReadUInt16BigEndian(frame[15..]),
            CaptureMs: BinaryPrimitives.ReadUInt32BigEndian(frame[17..]));
        return true;
    }

    /// <summary>The frame as it is encrypted: the header, then the picture.
    /// The PC sends none; this is what the tests and the shared vectors are
    /// built with.</summary>
    public static byte[] Build(PhoneFrameHeader header, ReadOnlySpan<byte> picture)
    {
        var frame = new byte[HeaderSize + picture.Length];
        BinaryPrimitives.WriteUInt64BigEndian(frame, header.Stream);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(8), header.Seq);
        frame[12] = (byte)((header.Keyframe ? KeyframeBit : 0) | ((header.Format & FormatMask) << FormatShift) |
            (header.Sideways ? SidewaysBit : 0) | (header.Repeat ? RepeatBit : 0));
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(13), (ushort)header.Width);
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(15), (ushort)header.Height);
        BinaryPrimitives.WriteUInt32BigEndian(frame.AsSpan(17), header.CaptureMs);
        picture.CopyTo(frame.AsSpan(HeaderSize));
        return frame;
    }

    /// <summary>A stream's id as <c>screen-info</c> and <c>frame-ack</c> carry it: 16 hex digits.</summary>
    public static string StreamHex(ulong stream) => stream.ToString("x16");

    /// <summary>Reads those 16 hex digits, or null for anything else.</summary>
    public static ulong? ParseStream(string? text)
    {
        // The digits are checked first: the parser alone would let spaces by.
        if (text is not { Length: 16 } || !text.All(Uri.IsHexDigit)) return null;
        return ulong.TryParse(text, NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out var value) ? value : null;
    }

    /// <summary>
    /// A whole frame, as hex, that both apps build and read back in their
    /// tests: stream <c>fedcba9876543210</c>, seq 4026531841, a JPEG
    /// keyframe, 2340 x 1080, captured at 4294967280, and four bytes of
    /// picture. The values have their top bits set, which is where two
    /// languages disagree.
    /// </summary>
    public const string Vector = "fedcba9876543210f00000010109240438fffffff0ffd8ffd9";

    /// <summary>The same header with every flag set and the format H.264, over no picture.</summary>
    public const string VectorFlags = "fedcba9876543210f00000011b0438096000000000";

    /// <summary>Checks this code against the vectors PhoneFrame.kt is checked against.</summary>
    public static bool SelfTest()
    {
        static string Hex(byte[] bytes) => Convert.ToHexString(bytes).ToLowerInvariant();

        if (ParseStream("fedcba9876543210") is not { } stream) return false;
        var header = new PhoneFrameHeader(stream, 4026531841, Keyframe: true, Jpeg, Sideways: false, Repeat: false,
            Width: 2340, Height: 1080, CaptureMs: 4294967280);
        if (Hex(Build(header, Convert.FromHexString("ffd8ffd9"))) != Vector) return false;
        if (!TryParse(Convert.FromHexString(Vector), out var read) || read != header) return false;
        if (StreamHex(header.Stream) != "fedcba9876543210") return false;

        var flags = header with { Format = H264, Sideways = true, Repeat = true, Width = 1080, Height = 2400, CaptureMs = 0 };
        if (Hex(Build(flags, [])) != VectorFlags) return false;
        if (!TryParse(Convert.FromHexString(VectorFlags), out read) || read != flags) return false;
        // Too short to hold a header.
        return !TryParse(Convert.FromHexString(VectorFlags).AsSpan(0, HeaderSize - 1), out _);
    }
}
