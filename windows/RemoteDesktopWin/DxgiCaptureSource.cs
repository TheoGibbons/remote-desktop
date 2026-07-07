using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using SharpGen.Runtime;
using Vortice;
using Vortice.Direct3D;
using Vortice.Direct3D11;
using Vortice.DXGI;

namespace RemoteDesktopWin;

/// <summary>
/// Preferred capture: the DXGI Desktop Duplication API. The OS tells us
/// exactly which regions changed (dirty + move rects) and hands frames over
/// on the GPU, so an idle desktop costs neither bandwidth nor a full-frame
/// CPU diff.
///
/// One duplication per attached output, composited into the stitched
/// virtual-desktop surface at each output's desktop coordinates. Throws from
/// the constructor when duplication is unavailable (secure desktop, some RDP
/// sessions, rotated outputs) and from <see cref="CaptureInto"/> when access
/// is lost (display mode change) — the streamer then recreates the source or
/// falls back to <see cref="GdiCaptureSource"/>.
///
/// Not composited: the mouse cursor (duplication delivers it as separate
/// metadata). Same behavior as the GDI path, and the phone viewer draws its
/// own virtual pointer anyway.
/// </summary>
public sealed class DxgiCaptureSource : ICaptureSource
{
    private sealed class Output
    {
        public required ID3D11DeviceContext Context;
        public required IDXGIOutputDuplication Duplication;
        public required ID3D11Texture2D Staging;
        public Rectangle Bounds; // position within the stitched surface
        public bool Primed;      // has delivered at least one frame
    }

    private readonly List<ID3D11Device> _devices = new();
    private readonly List<ID3D11DeviceContext> _contexts = new();
    private readonly List<Output> _outputs = new();
    private int _unprimedTicks;

    public string Name => "DXGI";

    public DxgiCaptureSource(Rectangle vs)
    {
        try
        {
            using var factory = DXGI.CreateDXGIFactory1<IDXGIFactory1>();
            for (uint a = 0; factory.EnumAdapters1(a, out IDXGIAdapter1 adapter).Success; a++)
            {
                using (adapter)
                {
                    ID3D11Device? device = null;
                    ID3D11DeviceContext? context = null;
                    for (uint o = 0; adapter.EnumOutputs(o, out IDXGIOutput output).Success; o++)
                    {
                        using (output)
                        {
                            var desc = output.Description;
                            if (!desc.AttachedToDesktop) continue;
                            // Rotated outputs would need a swizzled copy; let GDI handle them.
                            if (desc.Rotation != ModeRotation.Identity)
                                throw new NotSupportedException("rotated output");

                            if (device == null)
                            {
                                D3D11.D3D11CreateDevice(adapter, DriverType.Unknown,
                                    DeviceCreationFlags.BgraSupport, Array.Empty<FeatureLevel>(),
                                    out device, out context).CheckError();
                                _devices.Add(device!);
                                _contexts.Add(context!);
                            }

                            using var output1 = output.QueryInterface<IDXGIOutput1>();
                            var duplication = output1.DuplicateOutput(device!);

                            var dc = desc.DesktopCoordinates;
                            var bounds = new Rectangle(dc.Left - vs.X, dc.Top - vs.Y,
                                dc.Right - dc.Left, dc.Bottom - dc.Top);

                            var staging = device!.CreateTexture2D(new Texture2DDescription
                            {
                                Width = (uint)bounds.Width,
                                Height = (uint)bounds.Height,
                                MipLevels = 1,
                                ArraySize = 1,
                                Format = Format.B8G8R8A8_UNorm,
                                SampleDescription = new SampleDescription(1, 0),
                                Usage = ResourceUsage.Staging,
                                CPUAccessFlags = CpuAccessFlags.Read,
                                BindFlags = BindFlags.None,
                            });

                            _outputs.Add(new Output
                            {
                                Context = context!,
                                Duplication = duplication,
                                Staging = staging,
                                Bounds = bounds,
                            });
                        }
                    }
                }
            }
            if (_outputs.Count == 0) throw new NotSupportedException("no duplicable outputs");
        }
        catch
        {
            Dispose();
            throw;
        }
    }

    public bool CaptureInto(Bitmap surface, List<Rectangle> dirty)
    {
        foreach (var o in _outputs)
        {
            var result = o.Duplication.AcquireNextFrame(0, out OutduplFrameInfo info, out IDXGIResource? resource);
            if (result == Vortice.DXGI.ResultCode.WaitTimeout) continue; // nothing new on this output
            if (result.Failure)
                throw new InvalidOperationException("duplication lost: " + result); // recreate / fall back

            try
            {
                // LastPresentTime == 0 means a cursor-only update: no image.
                if (info.LastPresentTime == 0 && o.Primed) continue;

                using var tex = resource!.QueryInterface<ID3D11Texture2D>();
                o.Context.CopyResource(o.Staging, tex);

                var rects = new List<Rectangle>(); // output-local coordinates
                if (!o.Primed)
                {
                    rects.Add(new Rectangle(0, 0, o.Bounds.Width, o.Bounds.Height));
                }
                else if (info.TotalMetadataBufferSize > 0)
                {
                    int bufBytes = (int)info.TotalMetadataBufferSize;

                    // Move rects: the destination now shows moved content (the
                    // staging copy already has the final pixels); the source
                    // region gets its own dirty rect from the API if it changed.
                    int moveSize = Marshal.SizeOf<OutduplMoveRect>();
                    var moves = new OutduplMoveRect[bufBytes / moveSize + 1];
                    o.Duplication.GetFrameMoveRects((uint)(moves.Length * moveSize), moves, out uint moveBytes);
                    for (int i = 0; i < (int)moveBytes / moveSize; i++)
                    {
                        var d = moves[i].DestinationRect;
                        rects.Add(new Rectangle(d.Left, d.Top, d.Right - d.Left, d.Bottom - d.Top));
                    }

                    int rectSize = Marshal.SizeOf<RawRect>();
                    var raw = new RawRect[bufBytes / rectSize + 1];
                    o.Duplication.GetFrameDirtyRects((uint)(raw.Length * rectSize), raw, out uint dirtyBytes);
                    for (int i = 0; i < (int)dirtyBytes / rectSize; i++)
                    {
                        var d = raw[i];
                        rects.Add(new Rectangle(d.Left, d.Top, d.Right - d.Left, d.Bottom - d.Top));
                    }
                }

                CopyRects(o, surface, rects, dirty);
                o.Primed = true;
            }
            finally
            {
                resource?.Dispose();
                o.Duplication.ReleaseFrame();
            }
        }

        // Until every output has delivered its first frame the surface has
        // black holes; report "not ready" briefly, then give up to GDI.
        if (_outputs.Exists(o => !o.Primed))
        {
            if (++_unprimedTicks > 6)
                throw new InvalidOperationException("duplication never delivered a first frame");
            return false;
        }
        _unprimedTicks = 0;
        return true;
    }

    /// <summary>Copy the given output-local regions from the staging texture
    /// into the stitched surface, appending surface-coordinate rects to dirtyOut.</summary>
    private static void CopyRects(Output o, Bitmap surface, List<Rectangle> localRects, List<Rectangle> dirtyOut)
    {
        if (localRects.Count == 0) return;
        var mapped = o.Context.Map(o.Staging, 0, MapMode.Read);
        try
        {
            var bd = surface.LockBits(new Rectangle(0, 0, surface.Width, surface.Height),
                ImageLockMode.WriteOnly, PixelFormat.Format32bppRgb);
            try
            {
                unsafe
                {
                    byte* src0 = (byte*)mapped.DataPointer;
                    byte* dst0 = (byte*)bd.Scan0;
                    var outputBounds = new Rectangle(0, 0, o.Bounds.Width, o.Bounds.Height);
                    foreach (var lr in localRects)
                    {
                        var r = lr;
                        r.Intersect(outputBounds);
                        if (r.Width <= 0 || r.Height <= 0) continue;
                        for (int y = 0; y < r.Height; y++)
                        {
                            byte* src = src0 + (long)(r.Y + y) * mapped.RowPitch + r.X * 4L;
                            byte* dst = dst0 + (long)(o.Bounds.Y + r.Y + y) * bd.Stride + (o.Bounds.X + r.X) * 4L;
                            Buffer.MemoryCopy(src, dst, r.Width * 4L, r.Width * 4L);
                        }
                        dirtyOut.Add(new Rectangle(o.Bounds.X + r.X, o.Bounds.Y + r.Y, r.Width, r.Height));
                    }
                }
            }
            finally
            {
                surface.UnlockBits(bd);
            }
        }
        finally
        {
            o.Context.Unmap(o.Staging, 0);
        }
    }

    public void Dispose()
    {
        foreach (var o in _outputs)
        {
            try { o.Duplication.Dispose(); } catch { }
            try { o.Staging.Dispose(); } catch { }
        }
        _outputs.Clear();
        foreach (var c in _contexts) { try { c.Dispose(); } catch { } }
        _contexts.Clear();
        foreach (var d in _devices) { try { d.Dispose(); } catch { } }
        _devices.Clear();
    }
}
