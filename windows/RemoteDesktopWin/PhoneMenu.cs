using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

// The decisions behind the phone viewer's title-bar menu and window size,
// kept free of WPF types so RemoteDesktopWin.PhoneViewTests can compile this
// file in and run them without a window.

/// <summary>
/// When the "⋯" menu is open. It opens once the pointer has rested on the
/// button, or at a click, and closes a little after the pointer has left both
/// the button and the menu. The window tells it what the pointer does and
/// calls <see cref="Tick"/> when <see cref="NextDue"/> comes round; the clock
/// is always passed in.
/// </summary>
internal sealed class MenuHover
{
    public static readonly TimeSpan OpenDelay = TimeSpan.FromMilliseconds(150);
    public static readonly TimeSpan CloseDelay = TimeSpan.FromMilliseconds(350);
    /// <summary>A click this soon after the menu opened by itself was meant to
    /// open it, so it doesn't close it again.</summary>
    public static readonly TimeSpan ClickGrace = TimeSpan.FromMilliseconds(600);

    private bool _onButton, _onMenu, _dragging;
    private bool _active = true;
    private DateTime? _openAt, _closeAt;
    private DateTime _openedAt;

    public bool IsOpen { get; private set; }

    /// <summary>When <see cref="Tick"/> next has something to do, if ever.</summary>
    public DateTime? NextDue => _openAt ?? (_dragging ? null : _closeAt);

    public void PointerOnButton(bool on, DateTime now)
    {
        if (_onButton == on) return;
        bool wasOver = Over;
        _onButton = on;
        // Passing over a window in the background shouldn't pop a menu up
        // over whatever is being worked in.
        _openAt = on && !IsOpen && _active ? now + OpenDelay : null;
        PointerMoved(wasOver, now);
    }

    /// <summary>The window became the active one, or stopped being it. Resting
    /// on the button only counts while it is active, so a pointer already
    /// there when the window comes to the front starts its rest then. Leaving
    /// the window closes the menu. A window that never says is taken as active.</summary>
    public void WindowActive(bool active, DateTime now)
    {
        if (_active == active) return;
        _active = active;
        if (!active) Dismiss();
        else if (_onButton && !IsOpen) _openAt = now + OpenDelay;
    }

    public void PointerOnMenu(bool on, DateTime now)
    {
        if (_onMenu == on) return;
        bool wasOver = Over;
        _onMenu = on;
        PointerMoved(wasOver, now);
    }

    /// <summary>A click on the button: opens the menu, or closes one that has
    /// been open a while.</summary>
    public void Click(DateTime now)
    {
        if (!IsOpen) Open(now);
        else if (now - _openedAt >= ClickGrace) Dismiss();
    }

    /// <summary>A slider in the menu is being dragged. The pointer may wander
    /// off the menu without closing it.</summary>
    public void DragStarted() => _dragging = true;

    public void DragEnded(DateTime now)
    {
        if (!_dragging) return;
        _dragging = false;
        // The countdown starts when the drag ends, not when the pointer left.
        if (_closeAt != null) _closeAt = now + CloseDelay;
    }

    /// <summary>Closes at once: Esc, or the window lost focus, moved or was resized.</summary>
    public void Dismiss()
    {
        IsOpen = false;
        // The menu is gone, so the pointer is no longer on it, whether or not
        // the window gets to say so.
        _onMenu = false;
        _dragging = false;
        _openAt = _closeAt = null;
    }

    /// <summary>Opens or closes the menu if its time has come.</summary>
    public void Tick(DateTime now)
    {
        if (_openAt is { } open && now >= open)
        {
            _openAt = null;
            if (_onButton && _active && !IsOpen) Open(now);
        }
        if (_closeAt is { } close && now >= close && !_dragging) Dismiss();
    }

    private bool Over => _onButton || _onMenu;

    private void Open(DateTime now)
    {
        IsOpen = true;
        _openedAt = now;
        _openAt = _closeAt = null;
    }

    // The countdown to closing starts when the pointer leaves. A menu opened
    // without the pointer on it (by UI Automation, or a touch) has nothing to
    // leave, so it stays until it is dismissed or clicked shut.
    private void PointerMoved(bool wasOver, DateTime now)
    {
        if (Over) _closeAt = null;
        else if (wasOver && IsOpen) _closeAt = now + CloseDelay;
    }
}

/// <summary>A window's place and size, or a monitor's work area.</summary>
internal readonly record struct FitRect(double Left, double Top, double Width, double Height)
{
    public double Right => Left + Width;
    public double Bottom => Top + Height;
}

/// <summary>
/// Sizes the viewer window round the phone's picture. The window is fitted
/// when the first picture arrives and again only when the picture's shape
/// changes, so a size the user chose survives.
/// </summary>
internal static class PhoneFit
{
    public const double MinWindowWidth = 280;
    /// <summary>How tall the window starts out for a phone held upright.</summary>
    public const double DefaultWindowHeight = 820;
    /// <summary>Room left above and below a window at its starting size.</summary>
    public const double DefaultMargin = 60;

    /// <summary>Whether two picture sizes have the same shape. A phone sends
    /// the same shape at different sizes as its stream changes scale.</summary>
    public static bool SameShape(int w1, int h1, int w2, int h2)
    {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false;
        double a = (double)w1 / h1, b = (double)w2 / h2;
        return Math.Abs(a - b) <= 0.01 * Math.Max(a, b);
    }

    /// <summary>The longer side of a picture as it is drawn, letterboxed, in a
    /// host of the given size.</summary>
    public static double ShownLongSide(int pictureW, int pictureH, double hostW, double hostH)
    {
        if (pictureW <= 0 || pictureH <= 0 || hostW <= 0 || hostH <= 0) return 0;
        double scale = Math.Min(hostW / pictureW, hostH / pictureH);
        return Math.Max(pictureW, pictureH) * scale;
    }

    /// <summary>How far the picture's long side, measured from the window, may
    /// be from what the last fit gave it and still count as untouched.</summary>
    public const double LongSideSlack = 3;

    /// <summary>
    /// The long side to carry over when the picture changes shape. A window's
    /// size lands on whole pixels, so the long side measured back from it is a
    /// pixel or two short of what the last fit asked for, and a phone turned
    /// back and forth would shrink a little each time. If the measurement is
    /// that close to the last fit's own long side, nobody has resized the
    /// window since, and the fit's figure is kept.
    /// </summary>
    public static double KeptLongSide(double measured, double lastFitted) =>
        lastFitted > 0 && Math.Abs(measured - lastFitted) <= LongSideSlack ? lastFitted : measured;

    /// <summary>
    /// Where the window goes for a picture of this shape.
    /// </summary>
    /// <param name="longSide">The picture's longer side on screen before it
    /// changed shape, which it keeps, so a phone that turns stays the same
    /// size. Zero for the first picture.</param>
    /// <param name="frameW">Window width less the picture host's width.</param>
    /// <param name="frameH">Window height less the picture host's height.</param>
    /// <param name="window">The window now.</param>
    /// <param name="work">The work area of the monitor it is on.</param>
    public static FitRect Fit(int pictureW, int pictureH, double longSide, double frameW, double frameH,
        FitRect window, FitRect work)
    {
        if (pictureW <= 0 || pictureH <= 0) return window;
        if (longSide <= 0)
            longSide = Math.Min(DefaultWindowHeight, work.Height - DefaultMargin) - frameH;

        double w, h;
        if (pictureH >= pictureW) { h = longSide; w = longSide * pictureW / pictureH; }
        else { w = longSide; h = longSide * pictureH / pictureW; }

        // Never larger than the work area: shrink the picture, keeping its shape.
        double roomW = Math.Max(1, work.Width - frameW), roomH = Math.Max(1, work.Height - frameH);
        double shrink = Math.Min(1, Math.Min(roomW / w, roomH / h));
        w *= shrink;
        h *= shrink;

        // The title bar needs its width whatever the picture's; a very narrow
        // picture is centred in it.
        double width = Math.Max(MinWindowWidth, w + frameW);
        double height = h + frameH;

        // Stay where the user put it, moved only as far as keeps it on screen.
        double left = Math.Max(work.Left, Math.Min(window.Left, work.Right - width));
        double top = Math.Max(work.Top, Math.Min(window.Top, work.Bottom - height));
        return new FitRect(left, top, width, height);
    }
}

/// <summary>
/// Paces the messages a dragged slider sends: at most one per interval while
/// it moves, and always the value it was let go at.
/// </summary>
internal sealed class SendThrottle(TimeSpan interval)
{
    private DateTime? _lastSent;
    private int? _sent, _held;

    /// <summary>What was last sent no longer says what the phone has: its
    /// value may have changed by other means. Call it when a drag starts and
    /// when the phone reports its state, so the next value is sent even if it
    /// is the one sent last.</summary>
    public void Forget() => _sent = null;

    /// <summary>The slider moved to <paramref name="value"/>. Returns it if it
    /// should be sent now; otherwise it is held for <see cref="Finish"/>.</summary>
    public int? Offer(int value, DateTime now)
    {
        if (value == _sent) { _held = null; return null; }
        if (_lastSent is { } last && now - last < interval) { _held = value; return null; }
        return Send(value, now);
    }

    /// <summary>The drag ended. Returns the last value if the phone hasn't had it.</summary>
    public int? Finish(DateTime now)
    {
        if (_held is not { } value) return null;
        return Send(value, now);
    }

    /// <summary>When a held value may go, so one the slider rests on mid-drag
    /// isn't kept back until the drag ends.</summary>
    public DateTime? NextDue => _held != null && _lastSent is { } last ? last + interval : null;

    /// <summary>Returns the held value if its time has come.</summary>
    public int? Tick(DateTime now) => NextDue is { } due && now >= due ? Finish(now) : null;

    private int Send(int value, DateTime now)
    {
        _lastSent = now;
        _sent = value;
        _held = null;
        return value;
    }
}

/// <summary>
/// The volume slider's dealings with the phone: what to send as it moves, and
/// which of the phone's answers to take its level from. The phone answers
/// every <c>volume</c> with its state, so the answers to a drag's earlier
/// messages arrive after the drag has ended, each with a level the slider has
/// since left. Only the answer to the last message sent says where the phone
/// is. Everything the phone answers with its state is counted here, asking
/// for the state included, so that the answers can be told apart by counting.
/// Only the volume in a state is ever held back: the rest of it always applies.
/// </summary>
internal sealed class VolumeSender
{
    public static readonly TimeSpan Interval = TimeSpan.FromMilliseconds(100);
    /// <summary>Answers still owed this long after the last message are given
    /// up on, so a phone that stopped answering doesn't deafen the slider.</summary>
    public static readonly TimeSpan AnswerWait = TimeSpan.FromSeconds(2);

    private readonly SendThrottle _throttle = new(Interval);
    private int _owed;
    private DateTime _lastSent;

    public bool Dragging { get; private set; }

    public void DragStarted()
    {
        Dragging = true;
        _throttle.Forget();
    }

    /// <summary>The slider moved, by a drag or a click on its track. Returns
    /// the level to send now, if any.</summary>
    public int? Moved(int level, DateTime now)
    {
        if (!Dragging) _throttle.Forget();
        return Counted(_throttle.Offer(level, now), now);
    }

    /// <summary>Returns the level the drag ended on, if the phone hasn't had it.</summary>
    public int? DragEnded(DateTime now)
    {
        Dragging = false;
        return Counted(_throttle.Finish(now), now);
    }

    /// <summary>When <see cref="Tick"/> next has something to send, if ever.</summary>
    public DateTime? NextDue => _throttle.NextDue;

    public int? Tick(DateTime now) => Counted(_throttle.Tick(now), now);

    /// <summary>A message went out that the slider didn't send, and that the
    /// phone also answers with its state: a mute, a rotation, or asking for
    /// the state. Its answer is owed like any other. Answers that never came
    /// (the phone hadn't let this PC in yet, or runs an older app) are
    /// forgotten here, so they don't hold back the answer to this one.</summary>
    public void Sent(DateTime now)
    {
        if (now - _lastSent >= AnswerWait) _owed = 0;
        _owed++;
        _lastSent = now;
    }

    /// <summary>The phone reported its state. Returns whether to show the
    /// volume in it: not during a drag, which would pull the thumb from under
    /// the pointer, and not while a later message of ours is still to be
    /// answered.</summary>
    public bool Heard(DateTime now)
    {
        if (_owed > 0 && now - _lastSent < AnswerWait) _owed--;
        else _owed = 0;
        if (Dragging || _owed > 0) return false;
        _throttle.Forget();
        return true;
    }

    private int? Counted(int? level, DateTime now)
    {
        if (level != null) Sent(now);
        return level;
    }
}

/// <summary>
/// How often the phone is asked for its state. The menu asks each time it
/// opens, and a pointer wandering on and off its button opens it again and
/// again; once a second is as fresh as a menu needs.
/// </summary>
internal sealed class AskPace
{
    public static readonly TimeSpan Interval = TimeSpan.FromSeconds(1);

    private DateTime? _last;

    /// <summary>Whether to ask now. <paramref name="always"/> is for the asks
    /// that must go whatever came before: the window has just opened, or the
    /// phone has just let this PC in and dropped whatever was asked earlier.</summary>
    public bool Ask(DateTime now, bool always = false)
    {
        if (!always && _last is { } last && now - last < Interval) return false;
        _last = now;
        return true;
    }
}

/// <summary>
/// Which rows the menu shows below Back, Home and Recents. Notifications is
/// always there. The rest need a phone that says it can do them, in
/// <c>phone-state</c>; a phone that is being viewed and has never sent one is
/// running an older app, and gets a line saying to update it instead.
/// </summary>
internal readonly record struct MenuRows(bool Volume, bool Rotation, bool QuickSettings, bool Screenshot, bool Lock,
    bool UpdateLine)
{
    /// <summary>How long a phone has to answer before it is taken for an older app.</summary>
    public static readonly TimeSpan AnswerWait = TimeSpan.FromSeconds(2);

    /// <param name="state">The phone's last <c>phone-state</c>, if it ever sent one.</param>
    /// <param name="viewingSince">When the phone first sent a picture's size,
    /// which is when it began answering this PC; null if it hasn't.</param>
    public static MenuRows For(PhoneState? state, DateTime? viewingSince, DateTime now)
    {
        if (state == null)
            return new MenuRows(false, false, false, false, false, UpdateLine: UpdateLineDue(state, viewingSince) <= now);
        return new MenuRows(state.Can.Contains("volume"), state.Can.Contains("rotate"), state.Can.Contains("quick-settings"),
            state.Can.Contains("screenshot"), state.Can.Contains("lock"), UpdateLine: false);
    }

    /// <summary>When the update line appears if the phone goes on saying nothing.</summary>
    public static DateTime? UpdateLineDue(PhoneState? state, DateTime? viewingSince) =>
        state == null && viewingSince is { } since ? since + AnswerWait : null;
}

/// <summary>
/// Notices a phone that was asked to turn and didn't. After Portrait or
/// Landscape is chosen the picture should take that shape; if it hasn't a few
/// seconds later, the app on the phone's screen is probably one that only
/// draws one way, and the person at the PC is told.
/// </summary>
internal sealed class RotationCheck
{
    public static readonly TimeSpan Wait = TimeSpan.FromSeconds(3);

    private string? _wanted;
    private DateTime _due;

    /// <summary>Whether a picture of this size is the shape an orientation gives.</summary>
    public static bool Matches(string orientation, int width, int height) => orientation switch
    {
        PhoneState.Landscape => width > height,
        PhoneState.Portrait => height > width,
        _ => true,
    };

    /// <summary>An orientation was chosen, with the picture the size it is now
    /// (0 if there is none yet). Auto asks for no shape, so nothing is waited for.</summary>
    public void Chose(string orientation, int pictureWidth, int pictureHeight, DateTime now)
    {
        _wanted = null;
        if (pictureWidth <= 0 || pictureHeight <= 0 || Matches(orientation, pictureWidth, pictureHeight)) return;
        _wanted = orientation;
        _due = now + Wait;
    }

    /// <summary>The phone sent a picture size.</summary>
    public void Picture(int width, int height)
    {
        if (_wanted != null && Matches(_wanted, width, height)) _wanted = null;
    }

    /// <summary>When <see cref="Tick"/> should be called, if anything is waited for.</summary>
    public DateTime? NextDue => _wanted != null ? _due : null;

    /// <summary>Returns true, once, if the phone has had its time and the
    /// picture is still the wrong shape.</summary>
    public bool Tick(DateTime now)
    {
        if (_wanted == null || now < _due) return false;
        _wanted = null;
        return true;
    }
}

/// <summary>
/// What a phone says about itself in <c>phone-state</c>: its media volume, how
/// it is turned, and which of the menu's rows it can act on right now. Phones
/// on v1.0.53 and earlier never send one.
/// </summary>
internal sealed record PhoneState(int Volume, int VolumeMax, bool Muted, string Rotation, IReadOnlySet<string> Can)
{
    public const string Auto = "auto", Portrait = "portrait", Landscape = "landscape";

    /// <summary>No sound either way: muted, or turned right down. Android
    /// reports a volume of 0 while muted.</summary>
    public bool IsSilent => Muted || Volume == 0;

    /// <summary>This state with the volume and mute of the one on show, for
    /// when its own are an answer the slider has since moved on from. What
    /// the phone can do, and how its screen is held, are taken as they come.</summary>
    public PhoneState WithVolumeOf(PhoneState shown) =>
        this with { Volume = Math.Clamp(shown.Volume, 0, VolumeMax), Muted = shown.Muted };

    /// <summary>
    /// What a click on the speaker icon asks of the phone: to mute, to
    /// unmute, or a level. A phone turned right down is not muted, and
    /// unmuting it would change nothing, so it goes back to the last level
    /// it was heard at (<paramref name="lastAudible"/>, 0 if none is known),
    /// or to the quietest step, as the icon on Android's own volume panel does.
    /// </summary>
    public (bool? Mute, int? Level) SpeakerClick(int lastAudible)
    {
        if (!IsSilent) return (true, null);
        if (Muted) return (false, null);
        return (null, Math.Clamp(lastAudible, 1, Math.Max(1, VolumeMax)));
    }

    /// <summary>Reads a <c>phone-state</c> message, or null if it makes no
    /// sense. Anything the phone leaves out of <c>can</c> is not offered.</summary>
    public static PhoneState? Parse(JsonObject msg)
    {
        try
        {
            var can = new HashSet<string>();
            if (msg["can"] is JsonArray list)
                foreach (var item in list)
                    if (item is JsonValue v && v.TryGetValue<string>(out var name)) can.Add(name);

            int max = Int(msg["volumeMax"]) ?? 0;
            int volume = Int(msg["volume"]) ?? 0;
            if (max <= 0) can.Remove("volume");
            volume = Math.Clamp(volume, 0, Math.Max(0, max));

            var rotation = msg["rotation"] is JsonValue r && r.TryGetValue<string>(out var s) ? s : Auto;
            if (rotation is not (Auto or Portrait or Landscape)) rotation = Auto;

            bool muted = msg["muted"] is JsonValue m && m.TryGetValue<bool>(out var b) && b;
            return new PhoneState(volume, Math.Max(0, max), muted, rotation, can);
        }
        catch (Exception)
        {
            return null;
        }
    }

    private static int? Int(JsonNode? node)
    {
        if (node is not JsonValue v) return null;
        if (v.TryGetValue<int>(out var i)) return i;
        if (v.TryGetValue<double>(out var d) && d is >= int.MinValue and <= int.MaxValue) return (int)d;
        return null;
    }
}

/// <summary>How the menu's Smooth video row looks right now.</summary>
internal readonly record struct SmoothRow(bool Shown, bool Enabled, bool Ticked, string ToolTip);

/// <summary>
/// The menu's Smooth video row: asking the phone to share its screen as
/// video, and to stop. The phone says whether it can in <c>smooth</c> of its
/// <c>screen-info</c>: <c>on</c>, <c>available</c> or <c>unavailable</c>. A
/// phone on an app from before this never says, and gets no row. What comes
/// of a request arrives as <c>smooth-status</c>, and is told to the person as
/// it comes.
///
/// Android shows a prompt on the phone, and somebody has to approve it: on
/// the phone, or here, by clicking its button in the picture like anything
/// else on the phone's screen. This app never presses it.
/// </summary>
internal sealed class SmoothVideo
{
    public const string On = "on", Available = "available", Unavailable = "unavailable";

    /// <summary>How long the phone has to say anything before the person is
    /// told it did not answer. A phone that refuses the asker says nothing.</summary>
    public static readonly TimeSpan AnswerWait = TimeSpan.FromSeconds(5);

    /// <summary>The most of the phone's own words that is shown.</summary>
    public const int MaxReason = 200;

    public const string AskTip =
        "Asks the phone to share its screen as video. Approve the prompt on the phone; you can click it here. " +
        "If the phone is on its side, scroll the prompt up to reach its button.";
    public const string OnTip =
        "Smooth video is on. Choose this to go back to ordinary viewing. " +
        "Android blanks password fields and one-time codes while the screen is shared as video.";
    public const string UnavailableTip =
        "This phone can't be asked from here right now. Smooth screen sharing can be turned on in its Permissions screen.";
    public const string PromptText =
        "The phone is asking. Click Share screen in the picture, or approve it on the phone. " +
        "If the phone is on its side, scroll the prompt up first.";
    public const string NoAnswerText = "The phone didn't answer.";

    private string? _offer;     // smooth, from the phone's last screen-info; null if it did not say
    private DateTime? _asked;   // when the row was chosen, until the phone says something

    /// <summary>The phone's <c>screen-info</c> said this about smooth
    /// sharing, or nothing (null). A word this app does not know is taken as
    /// nothing, so no row is shown that might do something else.</summary>
    public void Offer(string? smooth)
    {
        var offer = smooth is On or Available or Unavailable ? smooth : null;
        // What was being waited for has happened, or can no longer.
        if (offer != _offer) _asked = null;
        _offer = offer;
    }

    public SmoothRow Row => new(
        Shown: _offer != null,
        Enabled: _offer is On or Available,
        Ticked: _offer == On,
        ToolTip: _offer switch { On => OnTip, Unavailable => UnavailableTip, _ => AskTip });

    /// <summary>The row was chosen. Returns the message to send the phone, or
    /// null when there is nothing to ask.</summary>
    public string? Choose(DateTime now)
    {
        if (_offer is not (On or Available)) return null;
        _asked = now;
        return _offer == On ? "stop-smooth" : "request-smooth";
    }

    /// <summary>When <see cref="Tick"/> should be called, if an answer is being waited for.</summary>
    public DateTime? NextDue => _asked + AnswerWait;

    /// <summary>Returns what to tell the person, once, when the phone has
    /// had its time and said nothing.</summary>
    public string? Tick(DateTime now)
    {
        if (_asked is not { } asked || now < asked + AnswerWait) return null;
        _asked = null;
        return NoAnswerText;
    }

    /// <summary>A <c>smooth-status</c> from the phone being viewed. Returns
    /// what to tell the person, or null for a state this app does not know.</summary>
    public string? Status(JsonObject msg)
    {
        var state = Text(msg["state"]);
        var reason = Clean(Text(msg["reason"]));
        if (state is not ("prompting" or "started" or "declined" or "ended" or "unavailable")) return null;
        _asked = null;
        switch (state)
        {
            case "prompting":
                // With words of its own, the phone is asking some other way: by a notification.
                return reason ?? PromptText;
            case "started":
                _offer = On;
                return "Smooth video is on.";
            case "declined":
                return reason ?? "The prompt was declined on the phone.";
            case "ended":
                // A phone that has no other way to show its screen sends no screen-info to say so.
                if (_offer == On) _offer = Available;
                return reason ?? "Smooth video has ended.";
            default:
                return reason ?? "The phone can't start smooth video right now.";
        }
    }

    private static string? Text(JsonNode? node) => node is JsonValue v && v.TryGetValue<string>(out var text) ? text : null;

    /// <summary>The phone's words, made fit to show: one line, and not a long one.</summary>
    private static string? Clean(string? reason)
    {
        if (reason == null) return null;
        var line = new string(reason.Select(c => char.IsControl(c) ? ' ' : c).ToArray()).Trim();
        if (line.Length == 0) return null;
        return line.Length <= MaxReason ? line : line[..MaxReason].TrimEnd() + "…";
    }
}
