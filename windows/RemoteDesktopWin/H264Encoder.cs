using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice.MediaFoundation;

namespace RemoteDesktopWin;

/// <summary>
/// H.264 encoder built on a Media Foundation Transform, preferring whatever
/// hardware encoder the GPU registers (NVENC, Quick Sync, AMF all appear here
/// as MFTs) and falling back to the software one.
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

    private const int MftEnumFlagSyncMft = 0x1;
    private const int MftEnumFlagHardware = 0x4;
    private const int MftEnumFlagSortAndFilter = 0x40;

    // MF_E_TRANSFORM_NEED_MORE_INPUT
    private const int NeedMoreInput = unchecked((int)0xC00D6D72);

    private static int _startupCount;
    private static readonly object StartupLock = new();

    private readonly IMFTransform _mft;
    private readonly IMFMediaEventGenerator? _events; // async MFTs only
    private long _sampleTime;
    private readonly long _frameDuration;
    private readonly byte[] _nv12;
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

        Startup();
        // Hardware first. Failing that the software MFT, which is slower but
        // still beats re-encoding a moving region as fresh JPEG every frame.
        foreach (var hardware in new[] { true, false })
        {
            var enc = TryCreateFrom(hardware, width, height, bitrate, fps);
            if (enc != null) return enc;
        }
        Shutdown();
        return null;
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
                    var configured = Configure(mft, hardware, width, height, bitrate, fps);
                    if (configured == null) { mft.Dispose(); continue; }
                    return new H264Encoder(mft, configured, hardware, name, width, height, fps);
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
        var attributes = mft.Attributes;
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
            mft.SetInputType(0, inType, 0);
        }

        if (hardware) events = mft.QueryInterfaceOrNull<IMFMediaEventGenerator>();
        mft.ProcessMessage(TMessageType.MessageNotifyBeginStreaming, UIntPtr.Zero);
        mft.ProcessMessage(TMessageType.MessageNotifyStartOfStream, UIntPtr.Zero);
        return events;
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
            try { _mft.Attributes?.Set(CodecAvEncVideoForceKeyFrame, 1u); } catch { }
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
        _mft.ProcessInput(0, sample, 0);
        return DrainOne();
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
        var result = _mft.ProcessOutput(ProcessOutputFlags.None, 1, ref buffers[0], out _);
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
        finally { outSample.Dispose(); }
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
    /// BGRA to NV12 (BT.601 studio range), 2x2 chroma averaged. This runs on
    /// the CPU, which is affordable only because viewport streaming keeps the
    /// frame down to roughly the viewer's screen size; it would not be at a
    /// full 4K desktop.
    /// </summary>
    private static unsafe void BgraToNv12(Bitmap src, byte[] dst, int width, int height)
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
                        yOut[x] = (byte)(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
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
                        uvRow[x] = (byte)(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                        uvRow[x + 1] = (byte)(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                    }
                }
            }
        }
        finally { src.UnlockBits(data); }
    }

    private static void Startup()
    {
        lock (StartupLock)
        {
            if (_startupCount++ == 0) MediaFactory.MFStartup(false);
        }
    }

    private static void Shutdown()
    {
        lock (StartupLock)
        {
            if (_startupCount > 0) _startupCount--;
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndOfStream, UIntPtr.Zero); } catch { }
        try { _mft.ProcessMessage(TMessageType.MessageNotifyEndStreaming, UIntPtr.Zero); } catch { }
        try { _events?.Dispose(); } catch { }
        try { _mft.Dispose(); } catch { }
        Shutdown();
    }
}
