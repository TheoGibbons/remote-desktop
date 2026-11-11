using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace RemoteDesktopWin;

/// <summary>
/// H.264 encoder built on a Media Foundation Transform: whatever hardware
/// encoder the GPU registers (NVENC, Quick Sync, AMF all appear here as MFTs).
///
/// Windows' own software encoder can be driven too, and the tests do, but
/// <see cref="TryCreate(int, int, int, int)"/> does not hand it out. It was
/// meant as the fall-back from the start; a slip kept it from ever being
/// returned, and its half of the code had never run. So a PC without a
/// hardware encoder has always been sent as JPEG, and still is. Measured on
/// a 12-core desktop, the software encoder takes 4 to 8 ms a frame by the
/// clock and 15 to 25 ms of processor time, against next to none for a
/// graphics card. A PC with no hardware encoder is seldom a fast one, and
/// whether it should pay that is a decision nobody has taken. The software
/// encoder also names no colour standard in its stream, whatever it is
/// asked, so a picture of 720 x 576 or less would be shown in the wrong
/// colours by a phone (see <see cref="BgraToNv12"/>).
///
/// Why bother, given the dirty-rect JPEG path: JPEG re-encodes every changed
/// pixel from scratch. A window playing video is one large rectangle changing
/// every frame, which is the worst case for that and the best case for
/// inter-frame prediction — the encoder sends what moved rather than what is
/// there.
///
/// **Hardware MFTs are asynchronous**, which is a different contract from the
/// usual ProcessInput/ProcessOutput pair: the transform must be unlocked, and
/// it then tells you when it wants a frame and when one is ready via
/// <see cref="IMFMediaEventGenerator"/>. Software MFTs are synchronous. Both
/// are driven here behind one blocking <see cref="Encode"/> call, which keeps
/// the streamer loop the shape it already had.
///
/// The output resolution is fixed for the life of the encoder. Encoders do not
/// take kindly to a mid-stream resolution change, so the streamer disposes and
/// recreates this when the viewport zoom changes — a pan keeps the same size
/// and only moves the crop, which the encoder handles as ordinary motion.
/// </summary>
public sealed class H264Encoder : IDisposable
{
    // Codec API, for forcing an IDR on demand. Not every encoder honours it as
    // a plain attribute, so a failure here is not fatal: the GOP length below
    // guarantees a recovery point regardless.
    private static readonly Guid CodecAvEncVideoForceKeyFrame =
        new("398c1b98-8353-475a-9ef2-8f265d260345");
    private static readonly Guid CodecAvEncMPVGopSize =
        new("95f31b26-95a4-41aa-9303-246a7fc6eef1");
    private static readonly Guid CodecAvEncCommonRateControlMode =
        new("1c0608e9-370c-4710-8a58-cb6181c42423");
    private static readonly Guid CodecAvEncCommonMeanBitRate =
        new("f7222374-2144-4815-b550-a37f8e12ee52");
    private static readonly Guid MFLowLatency =
        new("9c27891a-ed7a-40e1-88e8-b22727a024ee");

    // How the colours are coded, said on both media types so that the encoder
    // writes it into the stream: BT.709, 16 to 235. A phone's decoder turns
    // the picture back into colours by what the stream says, and takes a
    // guess by the picture's size where it says nothing.
    private static readonly Guid MtYuvMatrix = new("3e23d450-2c75-4d25-a00e-b91670d12327");
    private static readonly Guid MtVideoNominalRange = new("c21b8ee5-b956-4071-8daf-325edf5cab11");
    private static readonly Guid MtVideoPrimaries = new("dbfbe4d7-0740-4ee0-8192-850ab0e21935");
    private static readonly Guid MtTransferFunction = new("5fb0fce9-be5c-4935-a811-ec838f8eed93");

    private const int MftEnumFlagSyncMft = 0x1;
    private const int MftEnumFlagHardware = 0x4;
    private const int MftEnumFlagSortAndFilter = 0x40;

    // MF_E_TRANSFORM_NEED_MORE_INPUT
    private const int NeedMoreInput = unchecked((int)0xC00D6D72);
    // MF_E_NOTACCEPTING
    private const int NotAccepting = unchecked((int)0xC00D36B5);

    private readonly IMFTransform _mft;
    private readonly IMFMediaEventGenerator? _events; // async MFTs only
    private long _sampleTime;
    private readonly long _frameDuration;
    private readonly byte[] _nv12;
    // A software encoder brings no samples for its output: this one is
    // filled again for every access unit.
    private IMFSample? _outSample;
    private IMFMediaBuffer? _outBuffer;
    private bool _disposed;

    public int Width { get; }
    public int Height { get; }
    public bool IsHardware { get; }
    public string Name { get; }

    private H264Encoder(IMFTransform mft, IMFMediaEventGenerator? events, bool hardware,
        string name, int width, int height, int fps)
    {
        _mft = mft;
        _events = events;
        IsHardware = hardware;
        Name = name;
        Width = width;
        Height = height;
        _frameDuration = 10_000_000L / Math.Max(1, fps);
        _nv12 = new byte[width * height * 3 / 2];
    }

    /// <summary>
    /// Build an encoder for this exact frame size, or null when none of the
    /// registered transforms will take it. Dimensions are rounded down to even
    /// numbers — 4:2:0 chroma is sampled in 2x2 blocks and an odd edge has no
    /// meaning.
    /// </summary>
    public static H264Encoder? TryCreate(int width, int height, int bitrate, int fps)
    {
        width &= ~1;
        height &= ~1;
        if (width < 16 || height < 16) return null;

        // The graphics card's encoder or none: see the note at the top on
        // the software one.
        return TryCreate(width, height, bitrate, fps, hardware: true);
    }

    /// <summary>
    /// The same, from one kind of encoder: the graphics card's, or Windows'
    /// own in software. The tests use the second to make their streams, so
    /// that they run on a PC whose graphics card has no encoder.
    /// </summary>
    internal static H264Encoder? TryCreate(int width, int height, int bitrate, int fps, bool hardware)
    {
        width &= ~1;
        height &= ~1;
        if (width < 16 || height < 16) return null;
        // Throws where Windows has no Media Foundation: then there is no encoder.
        try { MediaFoundationStartup.EnsureStarted(); }
        catch { return null; }
        return TryCreateFrom(hardware, width, height, bitrate, fps);
    }

    /// <summary>
    /// Whether Windows lists any H.264 encoder at all, a graphics card's or
    /// its own. For the tests: with none listed they have nothing to make a
    /// stream with and skip; with one listed that cannot be made to work,
    /// something is broken and they fail.
    /// </summary>
    internal static bool AnyRegistered()
    {
        try { MediaFoundationStartup.EnsureStarted(); }
        catch { return false; }
        var input = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.NV12 };
        var output = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 };
        foreach (int kind in new[] { MftEnumFlagHardware, MftEnumFlagSyncMft })
        {
            try
            {
                using var listed = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, (uint)(MftEnumFlagSortAndFilter | kind), input, output);
                bool any = false;
                foreach (var activate in listed) { any = true; activate.Dispose(); }
                if (any) return true;
            }
            catch { }
        }
        return false;
    }

    private static H264Encoder? TryCreateFrom(bool hardware, int width, int height, int bitrate, int fps)
    {
        int flags = MftEnumFlagSortAndFilter | (hardware ? MftEnumFlagHardware : MftEnumFlagSyncMft);
        var input = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.NV12 };
        var output = new RegisterTypeInfo { GuidMajorType = MediaTypeGuids.Video, GuidSubtype = VideoFormatGuids.H264 };

        IMFActivateCollection? activates = null;
        try
        {
            activates = MediaFactory.MFTEnumEx(TransformCategoryGuids.VideoEncoder, (uint)flags, input, output);
        }
        catch { return null; }

        using (activates)
        {
            foreach (var activate in activates)
            {
                IMFTransform? mft = null;
                try
                {
                    string name = SafeName(activate);
                    mft = activate.ActivateObject<IMFTransform>();
                    // Throws if the transform will not take these formats.
                    var events = Configure(mft, hardware, width, height, bitrate, fps);
                    // A graphics card's encoder is driven by its events and
                    // cannot be used without them. The software one has none.
                    if (hardware && events == null) { mft.Dispose(); continue; }
                    return new H264Encoder(mft, events, hardware, name, width, height, fps);
                }
                catch
                {
                    try { mft?.Dispose(); } catch { }
                }
            }
        }
        return null;
    }

    private static string SafeName(IMFActivate activate)
    {
        try { return activate.FriendlyName ?? "H.264 MFT"; }
        catch { return "H.264 MFT"; }
    }

    /// <summary>
    /// Returns the event generator for an async transform, or null for a sync
    /// one. Throws if the transform will not take these formats, which is the
    /// signal to move on to the next candidate.
    /// </summary>
    private static IMFMediaEventGenerator? Configure(IMFTransform mft, bool hardware,
        int width, int height, int bitrate, int fps)
    {
        IMFMediaEventGenerator? events = null;
        // Reading Attributes takes a reference on the store each time, which
        // is ours to give back.
        using var attributes = mft.Attributes;
        if (attributes != null)
        {
            // An async MFT stays locked until this is set, and every call on it
            // fails until it is.
            if (hardware) attributes.Set(TransformAttributeKeys.TransformAsyncUnlock, 1u);
            attributes.Set(MFLowLatency, 1u);
            // Ask for one IDR every couple of seconds. Even where the
            // force-keyframe control below is ignored, that bounds how long a
            // viewer that joined late or lost a frame stays broken.
            attributes.Set(CodecAvEncMPVGopSize, (uint)Math.Max(1, fps * 2));
            attributes.Set(CodecAvEncCommonRateControlMode, 0u); // CBR
            attributes.Set(CodecAvEncCommonMeanBitRate, (uint)bitrate);
        }

        // The types say how the colours are coded. An encoder that will not
        // take types that say so gets them without, rather than being lost.
        try { SetTypes(mft, width, height, bitrate, fps, sayColours: true); }
        catch (SharpGenException) { SetTypes(mft, width, height, bitrate, fps, sayColours: false); }

        if (hardware) events = mft.QueryInterfaceOrNull<IMFMediaEventGenerator>();
        mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
        return events;
    }

    private static void SetTypes(IMFTransform mft, int width, int height, int bitrate, int fps, bool sayColours)
    {
        // Output type must be set before input type: the encoder needs to know
        // what it is producing before it can say what it will accept.
        using (var outType = MediaFactory.MFCreateMediaType())
        {
            outType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            outType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.H264);
            outType.Set(MediaTypeAttributeKeys.AvgBitrate, (uint)bitrate);
            outType.Set(MediaTypeAttributeKeys.FrameSize, Pack(width, height));
            outType.Set(MediaTypeAttributeKeys.FrameRate, Pack(fps, 1));
            outType.Set(MediaTypeAttributeKeys.PixelAspectRatio, Pack(1, 1));
            outType.Set(MediaTypeAttributeKeys.InterlaceMode, 2u); // progressive
            outType.Set(MediaTypeAttributeKeys.Mpeg2Profile, 77u); // Main
            if (sayColours) SayColours(outType);
            mft.SetOutputType(0, outType, 0);
        }

        using (var inType = MediaFactory.MFCreateMediaType())
        {
            inType.Set(MediaTypeAttributeKeys.MajorType, MediaTypeGuids.Video);
            inType.Set(MediaTypeAttributeKeys.Subtype, VideoFormatGuids.NV12);
            inType.Set(MediaTypeAttributeKeys.FrameSize, Pack(width, height));
            inType.Set(MediaTypeAttributeKeys.FrameRate, Pack(fps, 1));
            inType.Set(MediaTypeAttributeKeys.PixelAspectRatio, Pack(1, 1));
            inType.Set(MediaTypeAttributeKeys.InterlaceMode, 2u);
            if (sayColours) SayColours(inType);
            mft.SetInputType(0, inType, 0);
        }
    }

    /// <summary>BT.709, 16 to 235: what <see cref="BgraToNv12"/> makes.</summary>
    private static void SayColours(IMFMediaType type)
    {
        type.Set(MtYuvMatrix, 1u);          // MFVideoTransferMatrix_BT709
        type.Set(MtVideoNominalRange, 2u);  // MFNominalRange_16_235
        type.Set(MtVideoPrimaries, 2u);     // MFVideoPrimaries_BT709
        type.Set(MtTransferFunction, 5u);   // MFVideoTransFunc_709
    }

    private static ulong Pack(int high, int low) => ((ulong)(uint)high << 32) | (uint)low;

    /// <summary>
    /// Encode one frame. Returns the access unit, or null when the encoder has
    /// taken the frame but has nothing to emit yet — encoders pipeline, so the
    /// first call or two can legitimately produce nothing.
    /// </summary>
    public byte[]? Encode(Bitmap frame, bool forceKeyframe)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (frame.Width < Width || frame.Height < Height) return null;

        if (forceKeyframe)
        {
            // Best effort: where it is ignored, the GOP length still bounds the
            // wait for a recovery point.
            try
            {
                using var attributes = _mft.Attributes;
                attributes?.Set(CodecAvEncVideoForceKeyFrame, 1u);
            }
            catch { }
        }

        BgraToNv12(frame, _nv12, Width, Height);
        using var sample = BuildSample(_nv12);
        sample.SampleTime = _sampleTime;
        sample.SampleDuration = _frameDuration;
        _sampleTime += _frameDuration;

        return _events != null ? EncodeAsync(sample) : EncodeSync(sample);
    }

    private byte[]? EncodeSync(IMFSample sample)
    {
        byte[]? held = null;
        try
        {
            _mft.ProcessInput(0, sample, 0);
        }
        catch (SharpGenException e) when (e.ResultCode.Code == NotAccepting)
        {
            // It still holds an access unit. Take that, and it has room.
            held = DrainOne();
            _mft.ProcessInput(0, sample, 0);
        }
        return held ?? DrainOne();
    }

    /// <summary>
    /// Async MFTs are event driven: the transform asks for input, then
    /// announces output. Both events are consumed here so the call still looks
    /// synchronous to the streamer.
    /// </summary>
    private byte[]? EncodeAsync(IMFSample sample)
    {
        bool delivered = false;
        for (int i = 0; i < 16; i++)
        {
            IMFMediaEvent evt;
            try { evt = _events!.GetEvent(0); }
            catch { return null; }

            using (evt)
            {
                switch (evt.EventType)
                {
                    case MediaEventTypes.TransformNeedInput:
                        if (delivered) continue; // it wants the next frame, not this one
                        _mft.ProcessInput(0, sample, 0);
                        delivered = true;
                        continue;

                    case MediaEventTypes.TransformHaveOutput:
                        var au = DrainOne();
                        if (au != null) return au;
                        continue;

                    default:
                        continue;
                }
            }
        }
        return null;
    }

    private byte[]? DrainOne()
    {
        var buffers = new OutputDataBuffer[1];
        buffers[0].StreamID = 0;
        // A hardware encoder hands out its own sample. The software one
        // writes into ours.
        bool ours = _events == null;
        if (ours)
        {
            if (_outSample == null)
            {
                int size = Math.Max(_mft.GetOutputStreamInfo(0).Size, Width * Height * 3 / 2);
                _outBuffer = MediaFactory.MFCreateMemoryBuffer(size);
                _outSample = MediaFactory.MFCreateSample();
                _outSample.AddBuffer(_outBuffer);
            }
            _outBuffer!.CurrentLength = 0;
            buffers[0].Sample = _outSample;
        }
        var result = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref buffers[0], out _);
        try { buffers[0].Events?.Dispose(); } catch { }
        if (result.Failure)
        {
            // Needing more input is the normal pipelining case, not an error.
            if (result.Code == NeedMoreInput) return null;
            return null;
        }

        var outSample = buffers[0].Sample;
        if (outSample == null) return null;
        try
        {
            using var contiguous = outSample.ConvertToContiguousBuffer();
            contiguous.Lock(out IntPtr data, out _, out int length);
            try
            {
                var bytes = new byte[length];
                Marshal.Copy(data, bytes, 0, length);
                return bytes;
            }
            finally { contiguous.Unlock(); }
        }
        finally { if (!ours) outSample.Dispose(); }
    }

    private static IMFSample BuildSample(byte[] nv12)
    {
        var buffer = MediaFactory.MFCreateMemoryBuffer(nv12.Length);
        buffer.Lock(out IntPtr data, out _, out _);
        try { Marshal.Copy(nv12, 0, data, nv12.Length); }
        finally { buffer.Unlock(); }
        buffer.CurrentLength = nv12.Length;

        var sample = MediaFactory.MFCreateSample();
        sample.AddBuffer(buffer);
        buffer.Dispose();
        return sample;
    }

    /// <summary>
    /// BGRA to NV12 (BT.709, 16 to 235), 2x2 chroma averaged. This runs on
    /// the CPU, which is affordable only because viewport streaming keeps the
    /// frame down to roughly the viewer's screen size; it would not be at a
    /// full 4K desktop.
    ///
    /// BT.709 because that is what the stream says (see <see
    /// cref="SayColours"/>), and what a graphics card's encoder says of its
    /// own accord. The two must agree: this was BT.601 once under a stream
    /// that said BT.709, and a phone showed the PC's pure green as 0, 215, 0.
    /// The weights are the standard's, times 219 / 255 for Y and 224 / 255
    /// for U and V, times 256. Each row of U and V weights sums to nothing,
    /// so greys stay grey.
    /// </summary>
    internal static unsafe void BgraToNv12(Bitmap src, byte[] dst, int width, int height)
    {
        var data = src.LockBits(new Rectangle(0, 0, width, height),
            ImageLockMode.ReadOnly, PixelFormat.Format32bppRgb);
        try
        {
            byte* base0 = (byte*)data.Scan0;
            int stride = data.Stride;
            int ySize = width * height;

            fixed (byte* out0 = dst)
            {
                for (int y = 0; y < height; y++)
                {
                    byte* row = base0 + (long)y * stride;
                    byte* yOut = out0 + (long)y * width;
                    for (int x = 0; x < width; x++)
                    {
                        byte b = row[x * 4], g = row[x * 4 + 1], r = row[x * 4 + 2];
                        yOut[x] = (byte)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);
                    }
                }

                byte* uv = out0 + ySize;
                for (int y = 0; y < height; y += 2)
                {
                    byte* r0 = base0 + (long)y * stride;
                    byte* r1 = base0 + (long)(y + 1) * stride;
                    byte* uvRow = uv + (long)(y / 2) * width;
                    for (int x = 0; x < width; x += 2)
                    {
                        int b = r0[x * 4] + r0[x * 4 + 4] + r1[x * 4] + r1[x * 4 + 4];
                        int g = r0[x * 4 + 1] + r0[x * 4 + 5] + r1[x * 4 + 1] + r1[x * 4 + 5];
                        int r = r0[x * 4 + 2] + r0[x * 4 + 6] + r1[x * 4 + 2] + r1[x * 4 + 6];
                        b >>= 2; g >>= 2; r >>= 2;
                        uvRow[x] = (byte)(((-26 * r - 86 * g + 112 * b + 128) >> 8) + 128);
                        uvRow[x + 1] = (byte)(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
                    }
                }
            }
        }
        finally { src.UnlockBits(data); }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); } catch { }
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero); } catch { }
        try { _events?.Dispose(); } catch { }
        try { _outSample?.Dispose(); } catch { }
        try { _outBuffer?.Dispose(); } catch { }
        try { _mft.Dispose(); } catch { }
    }
}
