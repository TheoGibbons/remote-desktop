using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// What the phone viewer puts over the picture of a phone that is asleep,
// locked or on its unlock screen. Like PhoneMenu.cs it has no WPF types, so
// RemoteDesktopWin.PhoneViewTests compiles it in and runs it without a
// window. The Android app decides the same way in LockCaption.kt.

/// <summary>A rectangle as fractions of the phone's screen, the way a
/// <c>tap</c> gives a point.</summary>
internal readonly record struct FRect(double X, double Y, double Width, double Height)
{
    public double CenterX => X + Width / 2;
    public double CenterY => Y + Height / 2;

    /// <summary>Reads <c>[x, y, w, h]</c>, or null unless it is a rectangle
    /// with some size that lies on the screen.</summary>
    public static FRect? Parse(JsonNode? node)
    {
        if (node is not JsonArray { Count: 4 } list) return null;
        Span<double> v = stackalloc double[4];
        for (int i = 0; i < 4; i++)
        {
            if (list[i] is not JsonValue value || !value.TryGetValue<double>(out var d) || !double.IsFinite(d)) return null;
            v[i] = d;
        }
        const double slack = 0.001;
        if (v[0] < 0 || v[1] < 0 || v[2] <= 0 || v[3] <= 0 || v[0] + v[2] > 1 + slack || v[1] + v[3] > 1 + slack) return null;
        return new FRect(v[0], v[1], v[2], v[3]);
    }
}

/// <summary>Where the phone says its pattern, PIN or password is entered
/// (<c>entry</c> of <c>lock-state</c>).</summary>
internal abstract record LockEntry
{
    /// <summary>Reads <c>entry</c>, or null if there is none or it makes no
    /// sense. The viewer then says what it would for a phone that sent none.</summary>
    public static LockEntry? Parse(JsonNode? node)
    {
        if (node is not JsonObject entry || entry["kind"] is not JsonValue k || !k.TryGetValue<string>(out var kind)) return null;
        switch (kind)
        {
            case "pattern":
                return FRect.Parse(entry["area"]) is { } area ? new PatternEntry(area) : null;
            case "pin":
                if (entry["keys"] is not JsonArray list) return null;
                var keys = new List<PinKey>();
                foreach (var item in list)
                {
                    if (item is not JsonObject key || key["label"] is not JsonValue l || !l.TryGetValue<string>(out var label)) continue;
                    // A label this viewer doesn't know is a key it has no use for.
                    if (!PinEntry.IsLabel(label) || keys.Any(have => have.Label == label)) continue;
                    if (FRect.Parse(key["rect"]) is { } rect) keys.Add(new PinKey(label, rect));
                }
                // Without all ten digits it is not a pad a PIN can be entered on.
                for (char digit = '0'; digit <= '9'; digit++)
                    if (keys.All(key => key.Label != digit.ToString())) return null;
                return new PinEntry(keys);
            case "password":
                return new PasswordEntry();
            default:
                return null;
        }
    }
}

/// <summary>The pattern grid, as one square. Android does not report its
/// dots; see <see cref="LockOverlay.Dots"/>.</summary>
internal sealed record PatternEntry(FRect Area) : LockEntry;

internal sealed record PinKey(string Label, FRect Rect);

/// <summary>The keys of a PIN pad: the ten digits, and <c>delete</c> and
/// <c>ok</c> where the phone has them.</summary>
internal sealed record PinEntry(IReadOnlyList<PinKey> Keys) : LockEntry
{
    public const string Delete = "delete", Ok = "ok";

    public static bool IsLabel(string label) =>
        label is Delete or Ok || (label.Length == 1 && label[0] is >= '0' and <= '9');

    /// <summary>Where to tap for a key, or null if the pad has no such key.</summary>
    public (double X, double Y)? Tap(string label)
    {
        foreach (var key in Keys)
            if (key.Label == label) return (key.Rect.CenterX, key.Rect.CenterY);
        return null;
    }

    // Two pads are the same pad when their keys are, not when they are the same list.
    public bool Equals(PinEntry? other) => other != null && Keys.SequenceEqual(other.Keys);

    public override int GetHashCode() => Keys.Count;
}

/// <summary>A password field. There is nothing to draw; it is typed into.</summary>
internal sealed record PasswordEntry : LockEntry;

/// <summary>
/// What a phone says in <c>lock-state</c>: whether its screen is on, whether
/// it is locked, why its screen is still off if it was asked to come on, and,
/// while its unlock screen is up, where the pattern or PIN is entered and
/// what the lock screen's message line says. Phones on v1.0.53 and earlier
/// never send one.
/// </summary>
internal sealed record LockInfo(bool ScreenOn, bool Locked, string? WakeFailed = null, LockEntry? Entry = null,
    string? Message = null)
{
    /// <summary><c>wakeFailed</c> from a phone whose notifications for the
    /// app are off. A Samsung phone turns no screen on for such an app.</summary>
    public const string Notifications = "notifications";

    /// <summary>The longest message line shown; a phone sends a sentence.</summary>
    public const int MessageMax = 200;

    /// <summary>Whether the screen and the lock are as in <paramref name="other"/>,
    /// whatever else differs. What the phone can do goes by these two.</summary>
    public bool SameScreenAndLock(LockInfo? other) => other != null && ScreenOn == other.ScreenOn && Locked == other.Locked;

    /// <summary>Reads a <c>lock-state</c> message, or null if it makes no sense.</summary>
    public static LockInfo? Parse(JsonObject msg)
    {
        if (msg["screenOn"] is not JsonValue on || !on.TryGetValue<bool>(out var screenOn)) return null;
        if (msg["locked"] is not JsonValue lk || !lk.TryGetValue<bool>(out var locked)) return null;
        // A wake can't have failed on a phone whose screen is on.
        var failed = !screenOn && msg["wakeFailed"] is JsonValue w && w.TryGetValue<string>(out var why) ? why : null;
        // Only an unlock screen that is on show has anything to enter.
        bool unlockScreen = screenOn && locked;
        var entry = unlockScreen ? LockEntry.Parse(msg["entry"]) : null;
        var message = unlockScreen && msg["message"] is JsonValue m && m.TryGetValue<string>(out var text) ? text.Trim() : null;
        if (message is { Length: > MessageMax }) message = message[..MessageMax];
        return new LockInfo(screenOn, locked, failed, entry, string.IsNullOrEmpty(message) ? null : message);
    }
}

/// <summary>The words over the picture, if any.</summary>
internal static class LockCaption
{
    public const string Asleep = "The phone is asleep. Click to wake it.";
    public const string WakeFailedNotifications =
        "The phone didn't wake. Allow notifications for Remote Desktop on the phone.";
    public const string WakeFailed = "The phone didn't wake. Click to try again.";
    public const string UnlockHidden =
        "Android hides the unlock screen from the picture. Clicks and drags still reach it.";
    public const string Black =
        "The phone's screen is off, or it is on its unlock screen. Clicks and drags still reach an unlock screen.";
    public const string Password = "Type the phone's password and press Enter.";

    /// <summary>
    /// Whether it matters that the picture is black. Android leaves the
    /// unlock screen out of the picture, so on a locked phone a black picture
    /// is that screen. A phone that never says how it stands may be asleep
    /// instead. A phone known to be unlocked is just showing something dark.
    /// </summary>
    public static bool BlackMatters(LockInfo? state) => state == null || (state.ScreenOn && state.Locked);

    /// <param name="state">The phone's last <c>lock-state</c>, if it ever sent one.</param>
    /// <param name="pictureBlack">Whether the picture on show is nearly all black.</param>
    public static string? For(LockInfo? state, bool pictureBlack)
    {
        if (state == null) return pictureBlack ? Black : null;
        if (!state.ScreenOn)
            return state.WakeFailed switch
            {
                null => Asleep,
                LockInfo.Notifications => WakeFailedNotifications,
                _ => WakeFailed,
            };
        if (!state.Locked) return null;
        return state.Entry switch
        {
            // The grid or the keys are drawn, with the phone's own message: see LockOverlay.
            PatternEntry or PinEntry => null,
            // Nothing to draw. The phone's message comes first: it is how a wrong try is answered.
            PasswordEntry => state.Message == null ? Password : state.Message + "\n" + Password,
            // The lock screen itself is in the picture and needs no words.
            _ => pictureBlack ? UnlockHidden : null,
        };
    }
}

/// <summary>
/// What the viewer works out for itself about a locked phone: whether its
/// picture is black, and where to draw what Android leaves out of it.
/// </summary>
internal static class LockOverlay
{
    /// <summary>A colour channel this low or lower is black. Dark themes are lighter.</summary>
    public const int Dark = 16;

    /// <summary>How much of the picture may be lit and still leave it nearly
    /// all black: an unlock screen keeps its Back arrow, and a phone whose
    /// screen is off sends the words "Screen unavailable".</summary>
    public const int LitPercent = 1;

    public const string DrawPattern = "Draw the phone's pattern.";
    public const string EnterPin = "Enter the phone's PIN.";

    /// <summary>The line drawn above the grid or the keys: the phone's own
    /// message, which is also how it answers a wrong try, or plain words when
    /// it sends none. Null when there is nothing to draw.</summary>
    public static string? Heading(LockInfo? state) => state is not { ScreenOn: true, Locked: true }
        ? null
        : state.Entry switch
        {
            PatternEntry => state.Message ?? DrawPattern,
            PinEntry => state.Message ?? EnterPin,
            _ => null,
        };

    /// <summary>
    /// The centres of the nine dots, row by row, as fractions of the screen.
    /// The phone reports the grid as one square, and its dots sit at the
    /// centres of an even three by three split of it.
    /// </summary>
    public static (double X, double Y)[] Dots(FRect area)
    {
        var dots = new (double, double)[9];
        for (int row = 0; row < 3; row++)
            for (int column = 0; column < 3; column++)
                dots[row * 3 + column] = (area.X + area.Width * (2 * column + 1) / 6, area.Y + area.Height * (2 * row + 1) / 6);
        return dots;
    }

    /// <summary>How near a dot's centre a finger has to pass to take it, as a
    /// share of the distance between dots. It is Android's own figure.</summary>
    public const double DotReach = 0.3;

    /// <summary>Which dot the point is on, 0 to 8 row by row, or -1 for none.</summary>
    public static int DotAt(double x, double y, FRect area)
    {
        double cellW = area.Width / 3, cellH = area.Height / 3;
        var dots = Dots(area);
        for (int i = 0; i < dots.Length; i++)
        {
            // Measured in cells, so it is a circle on the phone whatever the screen's shape.
            double dx = (x - dots[i].X) / cellW, dy = (y - dots[i].Y) / cellH;
            if (dx * dx + dy * dy <= DotReach * DotReach) return i;
        }
        return -1;
    }

    /// <summary>
    /// Where to tap for digits typed on the PC's keyboard, one tap a digit;
    /// null if <paramref name="text"/> is anything but digits. Digits typed
    /// while the phone asks for a PIN are never sent as text.
    /// </summary>
    public static IReadOnlyList<(double X, double Y)>? PinTaps(PinEntry pin, string text)
    {
        if (text.Length == 0) return null;
        var taps = new List<(double, double)>();
        foreach (char c in text)
        {
            if (c is < '0' or > '9') return null;
            if (pin.Tap(c.ToString()) is { } at) taps.Add(at);
        }
        return taps;
    }

    /// <summary>
    /// Whether a decoded picture is nearly all black, from a coarse sample of
    /// it: about a hundred pixels across its shorter side.
    /// </summary>
    /// <param name="pixels">The picture's rows, top to bottom, each
    /// <paramref name="stride"/> bytes long.</param>
    /// <param name="bytesPerPixel">Up to three of them are colour and are
    /// looked at: BGR and BGRA pictures both do, and so does a grey one.</param>
    public static bool IsNearlyBlack(ReadOnlySpan<byte> pixels, int width, int height, int stride, int bytesPerPixel)
    {
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0 || stride < width * bytesPerPixel) return false;
        if (pixels.Length < (long)stride * (height - 1) + (long)width * bytesPerPixel) return false;

        int step = Math.Max(1, Math.Min(width, height) / 96);
        int colours = Math.Min(3, bytesPerPixel);
        long lit = 0, looked = 0;
        for (int y = step / 2; y < height; y += step)
        {
            var row = pixels.Slice(y * stride, width * bytesPerPixel);
            for (int x = step / 2; x < width; x += step)
            {
                looked++;
                var pixel = row.Slice(x * bytesPerPixel, colours);
                foreach (byte channel in pixel)
                    if (channel > Dark) { lit++; break; }
            }
        }
        return looked > 0 && lit * 100 <= looked * LitPercent;
    }
}

/// <summary>
/// Paces <see cref="LockOverlay.IsNearlyBlack"/> and remembers its answer.
/// Pictures are looked at twice a second at most, and only while a black one
/// would mean something.
/// </summary>
internal sealed class BlackWatch
{
    public static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(500);

    private DateTime? _lookedAt;

    /// <summary>Whether the last picture looked at was nearly all black.</summary>
    public bool IsBlack { get; private set; }

    /// <summary>Whether to look at the picture that has just been decoded.</summary>
    public bool Due(LockInfo? state, DateTime now) =>
        LockCaption.BlackMatters(state) && (_lookedAt is not { } last || now - last >= Interval);

    public void Looked(bool black, DateTime now)
    {
        _lookedAt = now;
        IsBlack = black;
    }

    /// <summary>The phone's state changed: what was seen before says nothing about now.</summary>
    public void Forget()
    {
        _lookedAt = null;
        IsBlack = false;
    }
}
