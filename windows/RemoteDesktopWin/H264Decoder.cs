using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace RemoteDesktopWin;

/// <summary>What became of one access unit handed to <see cref="H264Decoder.Decode"/>.</summary>
internal enum H264Decoded
{
    /// <summary>At least one picture was handed to the caller.</summary>
    Picture,
    /// <summary>Taken, and nothing came out yet. Not a fault.</summary>
    NoPicture,
    /// <summary>
    /// Not looked at: the decoder is at a fresh start (new, reset, or after a
    /// failure) and this was not a keyframe with its parameter sets.
    /// </summary>
    NeedsKeyframe,
    /// <summary>
    /// The decoder reported an error, or was given a keyframe and produced no
    /// picture. It has been reset and wants a keyframe. <see
    /// cref="H264Decoder.LastFailure"/> says what happened.
    /// </summary>
    Failed,
    /// <summary>The decoder has been disposed. Nothing was done.</summary>
    Disposed,
}

/// <summary>Called with each picture decoded. See <see cref="H264Decoder.Decode"/>.</summary>
internal delegate void H264PictureHandler(in Nv12Picture picture);

/// <summary>
/// Windows' own H.264 decoder (a Media Foundation transform), fed one access
/// unit at a time and giving back NV12 pictures. In software: no Direct3D
/// device is given to it. It is what shows a phone's screen sent as video.
///
///     using var decoder = H264Decoder.TryCreate();   // null: this Windows has none
///     switch (decoder.Decode(accessUnit, seq, (in Nv12Picture p) => Nv12Converter.ToBgra(p, ...)))
///     {
///         case H264Decoded.Failed: ...               // ask for a keyframe; twice means give up
///         case H264Decoded.NeedsKeyframe: ...        // ask for a keyframe
///     }
///     decoder.Reset();                               // after a gap in the sequence numbers
///
/// <b>Threads.</b>
/// <list type="bullet">
/// <item><see cref="Decode"/> and <see cref="Reset"/> may be called from any
/// thread, but by one caller at a time: the viewer's decoding thread. A
/// second call while one is in progress throws.</item>
/// <item><see cref="Dispose"/> may be called from any thread at any time,
/// also while a picture is being decoded, and never waits. If a call is in
/// progress the decoder is torn down as that call returns, on its thread,
/// after the picture handler has finished; otherwise at once. Calls made
/// afterwards answer <see cref="H264Decoded.Disposed"/> and do nothing.</item>
/// <item>A picture's memory belongs to the decoder and is good only inside
/// the handler it is passed to. Because of the rule above it cannot be taken
/// away under the handler. Copy or convert it there; keep no pointer.</item>
/// <item>The handler runs on the thread that called <see cref="Decode"/>. An
/// exception it throws comes out of <see cref="Decode"/> and is not counted
/// as a decoder failure.</item>
/// </list>
///
/// <b>What the decoder does not tell.</b> It reports no error for a missing
/// frame, for a stream that starts without a keyframe, for a keyframe without
/// its parameter sets, or for a picture size it cannot take: it just gives
/// nothing. So this class asks for a whole keyframe (SPS, PPS and an IDR
/// slice in one access unit) at every fresh start, and counts a keyframe that
/// yields no picture as a failure. Gaps are the caller's to notice, from the
/// sequence numbers: call <see cref="Reset"/> and ask the phone for a
/// keyframe.
///
/// <b>A picture can be a moment late.</b> As a rule the picture is there as
/// soon as its access unit has gone in. Not always at a change of size, the
/// first frame included: the decoder announces the size, the output type is
/// chosen again, and for a millisecond or ten it then has nothing to give,
/// most often when another decoder has just been let go. So after a size
/// announcement, and after any keyframe, this class asks again for up to
/// <see cref="LatePictureMs"/> before it believes there is no picture.
/// Without that, a window opened straight after another was closed would
/// count its first keyframe as a failure of the decoder.
///
/// Nothing is allocated on the managed heap per picture.
/// </summary>
internal sealed unsafe class H264Decoder : IDisposable
{
    // Microsoft's decoder, asked for by its class id so that it is this one,
    // which works in software, and not whatever a graphics driver registered.
    private static readonly Guid ClsidMsH264Decoder = new("62CE7E72-4C71-4D20-B15D-452831A87D9D");
    private static readonly Guid IidIMFTransform = new("bf94c121-5b05-4e6f-8000-ba598961414d");

    // codecapi.h. The first is the GUID H264Encoder.cs calls MFLowLatency.
    private static readonly Guid CodecApiLowLatencyMode = new("9c27891a-ed7a-40e1-88e8-b22727a024ee");
    private static readonly Guid CodecApiMaxCodedWidth = new("5ae557b8-77af-41f5-9fa6-4db2fe1d4bca");
    private static readonly Guid CodecApiMaxCodedHeight = new("7262a16a-d2dc-4e75-9ba8-65c0c6d32b13");

    // mfapi.h
    private static readonly Guid MtMajorType = new("48eba18e-f8c9-4687-bf11-0a74c9f96a8f");
    private static readonly Guid MtSubtype = new("f7e34c9a-42e8-4714-b74b-cb29d72c35e5");
    private static readonly Guid MtFrameSize = new("1652c33d-d6b2-4012-b834-72030849a37d");
    private static readonly Guid MtInterlaceMode = new("e2724bb8-e676-4806-b4b2-a8d6efb44ccd");
    private static readonly Guid MtDefaultStride = new("644b4e48-1e02-4516-b0eb-c01ca9d49ac6");
    private static readonly Guid MtMinimumDisplayAperture = new("d7388766-18fe-48c6-a177-ee894867c8c4");
    private static readonly Guid MtGeometricAperture = new("66758743-7e5f-400d-980a-aa8596c85696");
    private static readonly Guid MtYuvMatrix = new("3e23d450-2c75-4d25-a00e-b91670d12327");
    private static readonly Guid MtVideoNominalRange = new("c21b8ee5-b956-4071-8daf-325edf5cab11");

    private const int NeedMoreInput = unchecked((int)0xC00D6D72);  // MF_E_TRANSFORM_NEED_MORE_INPUT
    private const int StreamChange = unchecked((int)0xC00D6D61);   // MF_E_TRANSFORM_STREAM_CHANGE
    private const int NotAccepting = unchecked((int)0xC00D36B5);   // MF_E_NOTACCEPTING

    /// <summary>
    /// The largest padded picture asked of the decoder, each way. Windows 11
    /// takes this without being told (check C1); an older decoder with a lower
    /// limit of its own may need telling, and that part is untried.
    /// </summary>
    internal const int MaxCodedSize = 4096;

    /// <summary>
    /// How long a picture is waited for where one must come: after a size
    /// announcement, and after a keyframe. Up to 10 ms has been seen. A
    /// keyframe the decoder really cannot decode costs this long once, before
    /// it is called a failure.
    /// </summary>
    internal const int LatePictureMs = 150;

    /// <summary>What an access unit holds, as far as the decoder's rules care.</summary>
    [Flags]
    internal enum Units { None = 0, Sps = 1, Pps = 2, Idr = 4, Slice = 8, Keyframe = Sps | Pps | Idr }

    /// <summary>What the decoder says about its pictures, read again at each change of size.</summary>
    internal readonly record struct OutputFormat(
        int CodedWidth, int CodedHeight, int X, int Top, int Width, int Height, int Stride,
        Nv12Matrix Matrix, bool MatrixGiven, bool FullRange, bool RangeGiven);

    private readonly IMFTransform _mft;
    private IMFSample? _sample;         // ours, used for every picture
    private IMFMediaBuffer? _buffer;
    private OutputFormat _format;
    private bool _needsKeyframe = true;
    private bool _announced;            // the decoder announced a size during the call in progress

    // Who is inside, and whether to tear down. Only these four are shared
    // between threads, and only under the gate.
    private readonly object _gate = new();
    private bool _busy;
    private bool _disposeAsked;
    private bool _released;

    private static readonly object ProbeGate = new();
    private static bool? _available;

    /// <summary>The last picture's format. Read it on the decoding thread.</summary>
    public OutputFormat Format => _format;

    /// <summary>How often the decoder announced a size: once at the first picture, once more for every turn of the phone.</summary>
    public int SizeAnnouncements { get; private set; }

    /// <summary>What went wrong the last time <see cref="Decode"/> answered Failed. Read it on the decoding thread.</summary>
    public string LastFailure { get; private set; } = "";

    private H264Decoder(IMFTransform mft) => _mft = mft;

    /// <summary>
    /// Whether this Windows can decode H.264 at all, found out once by making
    /// a decoder and letting it go. The N editions without the Media Feature
    /// Pack cannot, nor can a Windows Server without Media Foundation. A
    /// viewer that gets false never offers "h264".
    /// </summary>
    public static bool IsAvailable
    {
        get
        {
            lock (ProbeGate)
            {
                if (_available is { } known) return known;
                using var probe = TryCreate(out _);
                _available = probe != null;
                return probe != null;
            }
        }
    }

    public static H264Decoder? TryCreate() => TryCreate(out _);

    /// <summary>A decoder, or null with the reason in <paramref name="why"/>.</summary>
    public static H264Decoder? TryCreate(out string why) => TryCreate(MaxCodedSize, MaxCodedSize, out why);

    /// <summary>For tests: a decoder that takes pictures only up to a given padded size.</summary>
    internal static H264Decoder? TryCreate(int maxCodedWidth, int maxCodedHeight, out string why)
    {
        why = "";
        IMFTransform? mft = null;
        try
        {
            // Both throw where Media Foundation is missing altogether.
            MediaFoundationStartup.EnsureStarted();
            int hr = CoCreateInstance(ClsidMsH264Decoder, IntPtr.Zero, 1 /* in-process */, IidIMFTransform, out IntPtr raw);
            if (hr == unchecked((int)0x800401F0)) // CO_E_NOTINITIALIZED: a thread nobody set up for COM
            {
                CoInitializeEx(IntPtr.Zero, 0 /* multithreaded */);
                hr = CoCreateInstance(ClsidMsH264Decoder, IntPtr.Zero, 1, IidIMFTransform, out raw);
            }
            if (hr < 0) { why = $"no H.264 decoder (0x{hr:X8})"; return null; }
            mft = new IMFTransform(raw);

            // Before the types, or they are not looked at.
            var attributes = mft.Attributes;
            // A picture out for every access unit in. Without it the decoder
            // keeps some thirty pictures back, a second of delay.
            attributes.Set(CodecApiLowLatencyMode, 1u);
            attributes.Set(CodecApiMaxCodedWidth, (uint)maxCodedWidth);
            attributes.Set(CodecApiMaxCodedHeight, (uint)maxCodedHeight);

            // No size on the input type: the decoder takes it from the
            // stream, at the start and whenever it changes.
            using (var input = MediaFactory.MFCreateMediaType())
            {
                input.Set(MtMajorType, MediaTypeGuids.Video);
                input.Set(MtSubtype, VideoFormatGuids.H264);
                input.Set(MtInterlaceMode, 2u); // progressive
                mft.SetInputType(0, input, 0);
            }

            var decoder = new H264Decoder(mft);
            mft = null; // the decoder's to release from here on
            try
            {
                decoder.ChooseOutputType();
                decoder._mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
                decoder._mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
                return decoder;
            }
            catch
            {
                decoder.Dispose();
                throw;
            }
        }
        catch (SharpGenException e)
        {
            why = $"the H.264 decoder refused its set-up (0x{e.ResultCode.Code:X8})";
        }
        catch (Exception e)
        {
            // DllNotFoundException and its kin: no Media Foundation here.
            why = $"no Media Foundation ({e.GetType().Name})";
        }
        try { mft?.Dispose(); } catch { }
        return null;
    }

    /// <summary>
    /// Decodes one access unit (Annex B: start codes, and SPS and PPS in
    /// front of a keyframe's slices). <paramref name="tag"/> comes back on
    /// the picture made from it; the viewer passes the frame's sequence
    /// number. <paramref name="onPicture"/> is called, on this thread and
    /// before this returns, with each picture that comes out: one, as a rule.
    /// The picture's memory is good only inside that call.
    /// </summary>
    public H264Decoded Decode(ReadOnlySpan<byte> accessUnit, long tag, H264PictureHandler onPicture)
    {
        if (!Enter()) return H264Decoded.Disposed;
        try
        {
            Units units = Scan(accessUnit);
            bool keyframe = (units & Units.Keyframe) == Units.Keyframe;
            if (_needsKeyframe && !keyframe) return H264Decoded.NeedsKeyframe;

            int pictures;
            try
            {
                pictures = Feed(accessUnit, tag, keyframe, onPicture);
            }
            catch (DecoderError e)
            {
                return Fail(e.Message);
            }

            if (keyframe)
            {
                // Silence is how the decoder says it cannot decode this
                // stream at all: a size over its limit, for one.
                if (pictures == 0) return Fail("a keyframe gave no picture");
                _needsKeyframe = false;
            }
            return pictures > 0 ? H264Decoded.Picture : H264Decoded.NoPicture;
        }
        finally
        {
            Leave();
        }
    }

    /// <summary>
    /// Forgets everything decoded so far. The next access unit that counts
    /// must be a keyframe. For a gap in the sequence numbers, and for a feed
    /// that overflowed.
    /// </summary>
    public void Reset()
    {
        if (!Enter()) return;
        try
        {
            _needsKeyframe = true;
            try { _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); }
            catch (SharpGenException) { }
        }
        finally
        {
            Leave();
        }
    }

    /// <summary>
    /// Lets go of the decoder. From any thread, at any time; see the notes on
    /// threads at the top.
    /// </summary>
    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposeAsked) return;
            _disposeAsked = true;
            if (!_busy) Release();
        }
    }

    // ---------------------------------------------------------------- inside

    private bool Enter()
    {
        lock (_gate)
        {
            if (_disposeAsked) return false;
            if (_busy) throw new InvalidOperationException("H264Decoder: one call at a time.");
            _busy = true;
            return true;
        }
    }

    private void Leave()
    {
        lock (_gate)
        {
            _busy = false;
            if (_disposeAsked) Release();
        }
    }

    /// <summary>Under the gate, with nobody inside.</summary>
    private void Release()
    {
        if (_released) return;
        _released = true;
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero); } catch { }
        try { _sample?.Dispose(); } catch { }
        try { _buffer?.Dispose(); } catch { }
        try { _mft.Dispose(); } catch { }
        _sample = null;
        _buffer = null;
    }

    private H264Decoded Fail(string what)
    {
        LastFailure = what;
        _needsKeyframe = true;
        try { _mft.ProcessMessage(TMessageType.MessageCommandFlush, UIntPtr.Zero); }
        catch (SharpGenException) { }
        return H264Decoded.Failed;
    }

    private sealed class DecoderError : Exception
    {
        public DecoderError(string step, int hr) : base($"{step} failed (0x{hr:X8})") { }
    }

    /// <summary>Hands the access unit to the decoder and passes on every picture that comes out. Returns how many.</summary>
    private int Feed(ReadOnlySpan<byte> accessUnit, long tag, bool keyframe, H264PictureHandler onPicture)
    {
        _announced = false;
        // A new buffer for every access unit. The decoder keeps the sample
        // after ProcessInput returns and reads it during ProcessOutput, so
        // one buffer written over for the next frame would corrupt this one.
        // It is native memory of a few kilobytes, not garbage.
        using var buffer = MediaFactory.MFCreateMemoryBuffer(Math.Max(1, accessUnit.Length));
        int length;
        buffer.Lock(out IntPtr data, out _, out _);
        try { length = CopyWithoutFiller(accessUnit, new Span<byte>((void*)data, accessUnit.Length)); }
        finally { buffer.Unlock(); }
        if (length == 0) return 0;
        buffer.CurrentLength = length;

        using var sample = MediaFactory.MFCreateSample();
        sample.AddBuffer(buffer);
        sample.SampleTime = tag;

        int pictures = 0;
        try
        {
            _mft.ProcessInput(0, sample, 0);
        }
        catch (SharpGenException e) when (e.ResultCode.Code == NotAccepting)
        {
            // It still holds pictures. Take them, then it has room.
            pictures += Drain(onPicture);
            try { _mft.ProcessInput(0, sample, 0); }
            catch (SharpGenException again) { throw new DecoderError("ProcessInput", again.ResultCode.Code); }
        }
        catch (SharpGenException e)
        {
            throw new DecoderError("ProcessInput", e.ResultCode.Code);
        }
        pictures += Drain(onPicture);

        // A picture that must come and has not yet: see the note at the top.
        if (pictures == 0 && (keyframe || _announced))
        {
            long started = System.Diagnostics.Stopwatch.GetTimestamp();
            while (pictures == 0)
            {
                double waited = System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds;
                if (waited >= LatePictureMs) break;
                // It is usually there within a millisecond: look closely at
                // first, then leave the processor to the decoder's threads.
                if (waited < 2) Thread.SpinWait(200);
                else Thread.Sleep(1);
                pictures = Drain(onPicture);
            }
        }
        return pictures;
    }

    /// <summary>Asks for output until the decoder wants more input.</summary>
    private int Drain(H264PictureHandler onPicture)
    {
        int pictures = 0;
        if (_sample == null || _buffer == null)
        {
            // An earlier choice of output type failed half way.
            try { ChooseOutputType(); }
            catch (SharpGenException e) { throw new DecoderError("choosing the output type", e.ResultCode.Code); }
        }
        // One picture is the rule. The bounds only stop a decoder gone wrong
        // from keeping this thread for ever.
        for (int asked = 0, changes = 0; asked < 64; asked++)
        {
            _buffer!.CurrentLength = 0;
            var output = new OutputDataBuffer { StreamID = 0, Sample = _sample };
            Result result;
            try
            {
                result = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref output, out _);
            }
            catch (SharpGenException e)
            {
                throw new DecoderError("ProcessOutput", e.ResultCode.Code);
            }
            try { output.Events?.Dispose(); } catch { }

            if (result.Code == NeedMoreInput) return pictures;
            if (result.Code == StreamChange)
            {
                // The first picture, and every change of size: the decoder
                // now knows the real size and wants the output type chosen
                // again. Nothing is lost; the picture comes on the next ask.
                if (++changes > 4) throw new DecoderError("ProcessOutput (the size kept changing)", StreamChange);
                SizeAnnouncements++;
                _announced = true;
                try { ChooseOutputType(); }
                catch (SharpGenException e) { throw new DecoderError("choosing the output type", e.ResultCode.Code); }
                continue;
            }
            if (result.Failure) throw new DecoderError("ProcessOutput", result.Code);

            var f = _format;
            long tag = _sample!.SampleTime;
            _buffer.Lock(out IntPtr data, out _, out int length);
            try
            {
                long needed = (long)f.Stride * f.CodedHeight * 3 / 2;
                if (length < needed) throw new DecoderError("ProcessOutput (a short picture)", unchecked((int)0x80004005));
                var picture = new Nv12Picture
                {
                    Y = (byte*)data,
                    Uv = (byte*)data + (long)f.Stride * f.CodedHeight,
                    Stride = f.Stride,
                    CodedWidth = f.CodedWidth, CodedHeight = f.CodedHeight,
                    X = f.X, Top = f.Top, Width = f.Width, Height = f.Height,
                    Matrix = f.Matrix, FullRange = f.FullRange, Tag = tag,
                };
                pictures++;
                onPicture(in picture);
            }
            finally
            {
                _buffer.Unlock();
            }
        }
        return pictures;
    }

    /// <summary>
    /// Picks NV12 among the output types on offer and reads what the decoder
    /// says about the pictures: padded size, visible part, stride, colours.
    /// Before the first picture this only repeats what the input type said;
    /// the true answer comes when the decoder announces a size.
    /// </summary>
    private void ChooseOutputType()
    {
        IMFMediaType? chosen = null;
        for (int i = 0; chosen == null; i++)
        {
            // Throws at the end of the list: no NV12, no decoder.
            IMFMediaType type = _mft.GetOutputAvailableType(0, i);
            if (type.GetGUID(MtSubtype) == VideoFormatGuids.NV12) chosen = type;
            else type.Dispose();
        }

        using (chosen)
        {
            _mft.SetOutputType(0, chosen, 0);

            ulong size = chosen.GetUInt64(MtFrameSize);
            int codedWidth = (int)(size >> 32), codedHeight = (int)(uint)size;

            // The visible part. Without one the whole frame is picture.
            int x = 0, top = 0, width = codedWidth, height = codedHeight;
            foreach (var key in new[] { MtMinimumDisplayAperture, MtGeometricAperture })
            {
                byte[]? area = TryGetBlob(chosen, key);
                if (area == null || area.Length < 16) continue;
                // MFVideoArea: two MFOffset (fraction u16, value i16), then a SIZE.
                int ax = BitConverter.ToInt16(area, 2), ay = BitConverter.ToInt16(area, 6);
                int aw = BitConverter.ToInt32(area, 8), ah = BitConverter.ToInt32(area, 12);
                if (ax < 0 || ay < 0 || aw <= 0 || ah <= 0 || ax + aw > codedWidth || ay + ah > codedHeight) continue;
                (x, top, width, height) = (ax, ay, aw, ah);
                break;
            }

            uint? strideGiven = TryGetUInt32(chosen, MtDefaultStride);
            int stride = strideGiven is { } s && (int)s >= codedWidth ? (int)s : codedWidth;

            // MFVideoTransferMatrix: 1 is BT.709, 2 is BT.601. A stream that
            // does not say gets what Android assumes for one: BT.601 up to
            // 720 x 576, BT.709 above.
            uint? matrixGiven = TryGetUInt32(chosen, MtYuvMatrix);
            Nv12Matrix matrix = matrixGiven switch
            {
                1 => Nv12Matrix.Bt709,
                2 => Nv12Matrix.Bt601,
                _ => (width <= 720 && height <= 576) || (height <= 720 && width <= 576) ? Nv12Matrix.Bt601 : Nv12Matrix.Bt709,
            };
            // MFNominalRange: 1 is 0 to 255, 2 is 16 to 235. Not said: 16 to 235.
            uint? rangeGiven = TryGetUInt32(chosen, MtVideoNominalRange);

            _format = new OutputFormat(codedWidth, codedHeight, x, top, width, height, stride,
                matrix, matrixGiven is 1 or 2, rangeGiven == 1, rangeGiven is 1 or 2);

            // In software the decoder does not bring its own samples, so one
            // of ours is used for every picture and made again when the size
            // changes. 64-byte alignment suits vector code in a converter.
            var info = _mft.GetOutputStreamInfo(0);
            int bytes = Math.Max(info.Size, stride * codedHeight * 3 / 2);
            _sample?.Dispose(); _sample = null;
            _buffer?.Dispose(); _buffer = null;
            _buffer = MediaFactory.MFCreateAlignedMemoryBuffer(bytes, 63);
            _sample = MediaFactory.MFCreateSample();
            _sample.AddBuffer(_buffer);
        }
    }

    /// <summary>Which kinds of NAL unit an access unit holds.</summary>
    internal static Units Scan(ReadOnlySpan<byte> accessUnit)
    {
        ReadOnlySpan<byte> startCode = stackalloc byte[] { 0, 0, 1 };
        Units units = Units.None;
        int search = 0;
        while (true)
        {
            int found = accessUnit.Slice(search).IndexOf(startCode);
            if (found < 0 || search + found + 3 >= accessUnit.Length) return units;
            search += found + 3;
            units |= (accessUnit[search] & 0x1F) switch
            {
                7 => Units.Sps,
                8 => Units.Pps,
                5 => Units.Idr | Units.Slice,
                1 => Units.Slice,
                _ => Units.None,
            };
        }
    }

    /// <summary>
    /// Copies an access unit, leaving out filler data (NAL units of type 12).
    /// An encoder holding a constant bitrate pads with them, and the decoder
    /// will not give up a picture whose access unit ends in one until the
    /// next access unit arrives (check C1). Returns the bytes written;
    /// <paramref name="target"/> must be as long as the source.
    /// </summary>
    internal static int CopyWithoutFiller(ReadOnlySpan<byte> source, Span<byte> target)
    {
        ReadOnlySpan<byte> startCode = stackalloc byte[] { 0, 0, 1 };
        int written = 0, unitStart = 0, search = 0;
        bool filler = false;
        while (true)
        {
            int found = source.Slice(search).IndexOf(startCode);
            int next = found < 0 ? source.Length : search + found;
            // The first zero of a four-byte start code belongs to the unit it begins.
            int boundary = found >= 0 && next > unitStart && source[next - 1] == 0 ? next - 1 : next;
            if (!filler && boundary > unitStart)
            {
                source.Slice(unitStart, boundary - unitStart).CopyTo(target.Slice(written));
                written += boundary - unitStart;
            }
            if (found < 0) return written;
            unitStart = boundary;
            filler = next + 3 < source.Length && (source[next + 3] & 0x1F) == 12;
            search = next + 3;
        }
    }

    private static uint? TryGetUInt32(IMFAttributes a, Guid key)
    {
        try { return a.GetUInt32(key); } catch (SharpGenException) { return null; }
    }

    private static byte[]? TryGetBlob(IMFAttributes a, Guid key)
    {
        try { return a.GetBlob(key); } catch (SharpGenException) { return null; }
    }

    [DllImport("ole32.dll")]
    private static extern int CoCreateInstance(in Guid clsid, IntPtr outer, int context, in Guid iid, out IntPtr obj);

    [DllImport("ole32.dll")]
    private static extern int CoInitializeEx(IntPtr reserved, int model);
}
