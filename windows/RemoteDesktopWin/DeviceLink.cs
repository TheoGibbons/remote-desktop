namespace RemoteDesktopWin;

/// <summary>
/// Which device a window is open on, through reconnections.
///
/// A window that views a device, or browses its files, sends everything to
/// the id the relay gave that device. The id does not last. When this PC's
/// connection drops, every device has a new one when the PC is back, and no
/// "left" message ever comes for the old ones: a relay that restarts, as it
/// does for every release, does this to everyone at once. When only the
/// device's connection drops, it leaves and joins again a moment later under
/// a new id. Ids are also used again: after a relay restarts, the old id of
/// one device may be handed to another.
///
/// So a window knows its device by its <see cref="Identity"/>, the key this
/// PC approved, and holds an id only while that device is in the session
/// under it. While it has none the device is <see cref="Away"/>: the window
/// sends nothing, says so, and waits. It is attached again only to a device
/// that proves the same key and is approved, never by a name or by an id,
/// and then it asks for everything afresh under the new id. If the device is
/// not back <see cref="Wait"/> after this PC could have seen it, the window
/// stops waiting.
///
/// Being back in the session is not yet being viewed again. The device may
/// have stopped approving this PC while it was away, or had control switched
/// off, and then it ignores what it is asked without a word. So a viewer
/// that has found its device is <see cref="Resuming"/> until the device
/// answers: the window still says that its picture is not live, still sends
/// no input, and stops waiting after the same <see cref="Wait"/>.
///
/// The wait does not run while this PC is itself off the relay: nothing
/// can be seen from there, and the main window says that it is reconnecting.
///
/// No WPF here: RemoteDesktopWin.PhoneViewTests compiles this file in.
/// </summary>
internal sealed class DeviceLink
{
    /// <summary>
    /// How long a device that has gone is waited for. A device whose
    /// connection dropped tries again after 2, 4, 8, 16 and then every 30
    /// seconds, and at once when its network comes back.
    /// </summary>
    public static readonly TimeSpan Wait = TimeSpan.FromSeconds(60);

    private readonly TimeSpan? _wait;

    /// <param name="peerId">The id the device has now.</param>
    /// <param name="identity">The fingerprint of the key it proved and this
    /// PC approved; null for a device that has proved none, which can then
    /// never be found again.</param>
    /// <param name="wait">How long it is waited for; null to wait for good.</param>
    public DeviceLink(string peerId, string? identity, TimeSpan? wait)
    {
        PeerId = peerId;
        Identity = identity;
        _wait = wait;
    }

    public DeviceLink(string peerId, string? identity) : this(peerId, identity, Wait) { }

    /// <summary>The id to send to; null while the device is away.</summary>
    public string? PeerId { get; private set; }

    /// <summary>The fingerprint of the device's approved key.</summary>
    public string? Identity { get; }

    /// <summary>The device is not in the session, as far as this PC can tell.</summary>
    public bool Away => PeerId == null;

    /// <summary>The device is back and has been asked for its screen again,
    /// and has not answered yet. Never so for a window that waits for good.</summary>
    public bool Resuming { get; private set; }

    /// <summary>The device is there and answering: what is typed and clicked may be sent to it.</summary>
    public bool Live => !Away && !Resuming;

    /// <summary>This PC is off the relay.</summary>
    public bool Offline { get; private set; }

    /// <summary>When the wait for the device ends; null while it is not away,
    /// while this PC is off the relay, and for a window that waits for good.</summary>
    public DateTime? GiveUpAt { get; private set; }

    /// <summary>This PC's connection has dropped: every id is dead.</summary>
    /// <returns>Whether that is news.</returns>
    public bool ConnectionLost()
    {
        if (Away && Offline) return false;
        PeerId = null;
        Resuming = false;
        Offline = true;
        GiveUpAt = null;
        return true;
    }

    /// <summary>This PC is on the relay again. A device that is away is waited for from now.</summary>
    /// <returns>Whether that is news.</returns>
    public bool Connected(DateTime now)
    {
        if (!Offline) return false;
        Offline = false;
        if (Away) GiveUpAt = now + _wait;
        return true;
    }

    /// <summary>The relay says a device has left the session.</summary>
    /// <returns>Whether it was this window's device.</returns>
    public bool Left(string peerId, DateTime now)
    {
        if (Away || peerId != PeerId) return false;
        PeerId = null;
        Resuming = false;
        GiveUpAt = now + _wait;
        return true;
    }

    /// <summary>
    /// A device in the session has proved a key and is approved. If it is
    /// this window's device come back, the window is attached to it.
    /// </summary>
    /// <param name="identity">The fingerprint of the key it proved.</param>
    /// <returns>Whether it was.</returns>
    public bool Returned(string peerId, string? identity, DateTime now)
    {
        if (!Away || Offline || Identity == null || identity != Identity) return false;
        PeerId = peerId;
        // A viewer waits for its answer as long again; a window that waits for good has nothing to wait out.
        Resuming = _wait != null;
        GiveUpAt = now + _wait;
        return true;
    }

    /// <summary>The device has answered what it was asked on coming back:
    /// it has said what it is sending.</summary>
    /// <returns>Whether that is news.</returns>
    public bool Answered()
    {
        if (!Resuming) return false;
        Resuming = false;
        GiveUpAt = null;
        return true;
    }

    /// <summary>The device says it is asking its user to approve this PC:
    /// that takes a person a while, so the wait for its answer starts again.</summary>
    public void StillAsking(string peerId, DateTime now)
    {
        if (Resuming && peerId == PeerId) GiveUpAt = now + _wait;
    }

    /// <summary>Whether the device has been waited for long enough: to come
    /// back, or to answer once it had.</summary>
    public bool GaveUp(DateTime now) => (Away || Resuming) && !Offline && GiveUpAt is { } at && now >= at;

    /// <summary>What a window says while its picture is not live, or null while it is.</summary>
    public string? Notice(string name) =>
        Resuming ? $"{name} is back. Asking for its screen again…" :
        !Away ? null :
        Offline ? "This PC has lost its connection. Reconnecting…" :
        $"{name} has lost its connection. Waiting for it to come back…";

    /// <summary>What is said when the wait is over: for a device that stayed
    /// away, or for one that came back and did not answer.</summary>
    public string GaveUpText(string name) => Resuming ? NoAnswerText(name) : GoneText(name);

    public static string GoneText(string name) => $"{name} did not come back online, so its window was closed.";

    public static string NoAnswerText(string name) =>
        $"{name} is back online but did not send its screen, so its window was closed. " +
        "Control may have been switched off on it, or it may no longer approve this PC.";
}

/// <summary>A window that is open on one device, and follows it through
/// reconnections. The main window, which hears who is in the session, makes
/// the changes to each one's <see cref="Link"/> and then tells the window.</summary>
internal interface IDeviceWindow
{
    DeviceLink Link { get; }

    /// <summary>The device's name, for what is said about it.</summary>
    string DeviceName { get; }

    /// <summary>The link has changed: the device has gone away, this PC is
    /// back on the relay and waiting for it, or it is back under a new id.</summary>
    /// <param name="was">The id it had before the change; null if it was away.</param>
    void LinkChanged(string? was);

    /// <summary>The device has been waited for long enough (<see cref="DeviceLink.GaveUp"/>).</summary>
    void GaveUp();
}
