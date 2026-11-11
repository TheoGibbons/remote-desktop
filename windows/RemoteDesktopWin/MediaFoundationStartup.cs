using Vortice.MediaFoundation;

namespace RemoteDesktopWin;

/// <summary>
/// Media Foundation has to be started before any of it is used. Once is
/// enough for the life of the app, whichever of the encoder (a PC's screen for
/// phones) and the decoder (a phone's screen for this PC) asks first, and it
/// is never shut down.
/// </summary>
internal static class MediaFoundationStartup
{
    private static readonly object Gate = new();
    private static bool _started;

    /// <summary>
    /// Throws when Windows has no Media Foundation (some server and N
    /// editions): callers treat that as "no codec here".
    /// </summary>
    public static void EnsureStarted()
    {
        lock (Gate)
        {
            if (_started) return;
            MediaFactory.MFStartup(false);
            _started = true;
        }
    }
}
