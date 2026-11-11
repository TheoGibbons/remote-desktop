namespace RemoteDesktopWin;

/// <summary>
/// One decoded video picture on its way to the window: BGRA, top row first,
/// <see cref="Width"/> * 4 bytes a row. The array may be longer than the
/// picture; it belongs to <see cref="PhoneVideoPictures"/> and is used again.
/// </summary>
internal sealed class PhoneVideoPicture
{
    public byte[] Bgra = [];
    public int Width, Height;
    public int Stride => Width * 4;
    /// <summary>The header of the frame this picture was decoded from.</summary>
    public PhoneFrameHeader Header;
    /// <summary>Whether it is nearly all black, if it was looked at, and how
    /// many times the phone's lock had changed by then (as for a JPEG).</summary>
    public bool? Black;
    public int AsOf;
    /// <summary>The window it was decoded for. It is not shown in another.</summary>
    public object? Owner;
    internal int Generation;
}

/// <summary>
/// Hands decoded video pictures from the thread that decodes them to the
/// thread that draws them, without making a new 10 MB array for each.
///
/// The decoding thread rents a picture (<see cref="Rent"/>), fills it and
/// publishes it (<see cref="Publish"/>). The window's thread takes the newest
/// published one (<see cref="Take"/>), copies it to the screen and gives it
/// back (<see cref="Return"/>). A picture published while an earlier one has
/// not been taken yet replaces it: that is the only place a picture is
/// skipped, and only a finished one that nobody has seen. Three arrays at
/// most are alive: one being filled, one waiting, one being drawn.
///
/// Safe from any thread. No WPF here.
/// </summary>
internal sealed class PhoneVideoPictures
{
    private readonly object _gate = new();
    private readonly Stack<PhoneVideoPicture> _free = new();
    private PhoneVideoPicture? _waiting;
    private int _generation;

    /// <summary>Finished pictures that were replaced before the window drew them.</summary>
    public long Skipped { get; private set; }

    /// <summary>A picture of that size to fill. Never null.</summary>
    public PhoneVideoPicture Rent(int width, int height)
    {
        int bytes = checked(width * 4 * height);
        lock (_gate)
        {
            while (_free.Count > 0)
            {
                var free = _free.Pop();
                // One of another size is let go: the phone was turned.
                if (free.Bgra.Length != bytes) continue;
                Prepare(free, width, height);
                return free;
            }
            var made = new PhoneVideoPicture { Bgra = new byte[bytes] };
            Prepare(made, width, height);
            return made;
        }
    }

    private void Prepare(PhoneVideoPicture picture, int width, int height)
    {
        picture.Width = width;
        picture.Height = height;
        picture.Header = default;
        picture.Black = null;
        picture.AsOf = 0;
        picture.Owner = null;
        picture.Generation = _generation;
    }

    /// <summary>The picture is finished: it is the one to draw next.</summary>
    public void Publish(PhoneVideoPicture picture)
    {
        lock (_gate)
        {
            if (picture.Generation != _generation) return;   // viewing stopped while it was being filled
            if (_waiting != null)
            {
                Skipped++;
                _free.Push(_waiting);
            }
            _waiting = picture;
        }
    }

    /// <summary>The newest finished picture that has not been drawn, or null. Give it back with <see cref="Return"/>.</summary>
    public PhoneVideoPicture? Take()
    {
        lock (_gate)
        {
            var picture = _waiting;
            _waiting = null;
            return picture;
        }
    }

    /// <summary>A rented or taken picture is done with.</summary>
    public void Return(PhoneVideoPicture picture)
    {
        lock (_gate)
        {
            if (picture.Generation != _generation || _free.Count >= 3) return;
            _free.Push(picture);
        }
    }

    /// <summary>
    /// Viewing stopped, or the stream did: the picture that was waiting is not
    /// to be drawn, and the arrays are let go. Pictures still out are dropped
    /// when they come back.
    /// </summary>
    public void Clear()
    {
        lock (_gate)
        {
            _generation++;
            _waiting = null;
            _free.Clear();
        }
    }
}
