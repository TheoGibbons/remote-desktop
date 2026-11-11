using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// What the phone viewer puts over the picture of a phone that is asleep,
// locked or on its unlock screen. Like PhoneMenu.cs it has no WPF types, so
// RemoteDesktopWin.PhoneViewTests compiles it in and runs it without a
// window. The Android app decides the same way in LockCaption.kt.

/// <summary>
/// What a phone says in <c>lock-state</c>: whether its screen is on, whether
/// it is locked, and why its screen is still off if it was asked to come on.
/// Phones on v1.0.53 and earlier never send one.
/// </summary>
internal sealed record LockInfo(bool ScreenOn, bool Locked, string? WakeFailed = null)
{
    /// <summary><c>wakeFailed</c> from a phone whose notifications for the
    /// app are off. A Samsung phone turns no screen on for such an app.</summary>
    public const string Notifications = "notifications";

    /// <summary>Reads a <c>lock-state</c> message, or null if it makes no sense.</summary>
    public static LockInfo? Parse(JsonObject msg)
    {
        if (msg["screenOn"] is not JsonValue on || !on.TryGetValue<bool>(out var screenOn)) return null;
        if (msg["locked"] is not JsonValue lk || !lk.TryGetValue<bool>(out var locked)) return null;
        // A wake can't have failed on a phone whose screen is on.
        var failed = !screenOn && msg["wakeFailed"] is JsonValue w && w.TryGetValue<string>(out var why) ? why : null;
        return new LockInfo(screenOn, locked, failed);
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
        // The lock screen itself is in the picture and needs no words.
        return state.Locked && pictureBlack ? UnlockHidden : null;
    }
}

/// <summary>What the viewer works out from the picture itself.</summary>
internal static class LockOverlay
{
    /// <summary>A colour channel this low or lower is black. Dark themes are lighter.</summary>
    public const int Dark = 16;

    /// <summary>How much of the picture may be lit and still leave it nearly
    /// all black: an unlock screen keeps its Back arrow, and a phone whose
    /// screen is off sends the words "Screen unavailable".</summary>
    public const int LitPercent = 1;

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
