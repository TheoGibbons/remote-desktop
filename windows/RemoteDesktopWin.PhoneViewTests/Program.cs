using System.Text.Json.Nodes;
using RemoteDesktopWin;

// The decisions behind the phone viewer: when its "⋯" menu is open, how the
// window is sized round the phone's picture, how a dragged slider is paced and
// what a phone says about itself (PhoneMenu.cs), and what is said over the
// picture of a phone that is asleep or locked (LockOverlay.cs); and which of
// the frames that arrive are the phone's own, how they are acknowledged and
// how they reach the window (PhoneFrame.cs, PhoneVideoStream.cs,
// PhoneFeed.cs); and the figures of the Stats readout, the delay above all
// (PhoneStats.cs). None of these files has any WPF in it, so none of this
// needs a window.
internal static class Program
{
    private static readonly DateTime T0 = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private static DateTime At(int ms) => T0.AddMilliseconds(ms);

    private static void Main()
    {
        CheckHoverOpens();
        CheckLeavingCloses();
        CheckClicks();
        CheckDrag();
        CheckDismiss();
        CheckBackgroundWindow();
        CheckFirstFit();
        CheckTurning();
        CheckManyTurns();
        CheckSmallWorkArea();
        CheckStaysOnScreen();
        CheckThrottle();
        CheckSecondDrag();
        CheckRestingMidDrag();
        CheckVolumeAnswers();
        CheckAnswersToAsking();
        CheckAskPace();
        CheckPhoneState();
        CheckMenuRows();
        CheckSmoothVideo();
        CheckRotation();
        CheckLockState();
        CheckCaptions();
        CheckBlackPicture();
        CheckBlackWatch();
        CheckLockEntry();
        CheckUnlockCaptions();
        CheckPatternDots();
        CheckPinTaps();
        CheckFrameHeader();
        CheckAnnouncedStream();
        CheckSequenceNumbers();
        CheckAcknowledgements();
        CheckFeedDrawsAnnouncedFrames();
        CheckFeedBelievesOnlyAnApprovedPhone();
        CheckFeedKeepsTheNewest();
        CheckFeedOnItsOwnThread();
        CheckStartView();
        CheckDelayArithmetic();
        CheckReadoutText();
        CheckFeedKeepsTheFigures();
    }

    // ---------------- the menu ----------------

    private static void CheckHoverOpens()
    {
        var menu = new MenuHover();
        menu.PointerOnButton(true, At(0));
        Check(menu.NextDue == At(150), "Resting on the button sets a time to open");
        menu.Tick(At(149));
        Check(!menu.IsOpen, "The menu doesn't open before the delay");
        menu.Tick(At(150));
        Check(menu.IsOpen, "The menu opens once the pointer has rested on the button");
        Check(menu.NextDue == null, "An open menu under the pointer has nothing to wait for");

        var passing = new MenuHover();
        passing.PointerOnButton(true, At(0));
        passing.PointerOnButton(false, At(100));
        passing.Tick(At(500));
        Check(!passing.IsOpen && passing.NextDue == null, "Passing over the button opens nothing");

        // The pointer came back, so the rest is counted from then.
        passing.PointerOnButton(true, At(600));
        passing.Tick(At(700));
        Check(!passing.IsOpen, "A second visit starts its own delay");
        passing.Tick(At(750));
        Check(passing.IsOpen, "A second visit opens after its own delay");
        Console.WriteLine("PASS: the menu opens after the pointer rests on the button, and not before");
    }

    private static MenuHover OpenByHover()
    {
        var menu = new MenuHover();
        menu.PointerOnButton(true, At(0));
        menu.Tick(At(150));
        Check(menu.IsOpen, "setup: the menu is open");
        return menu;
    }

    private static void CheckLeavingCloses()
    {
        var menu = OpenByHover();
        menu.PointerOnButton(false, At(1000));
        Check(menu.NextDue == At(1350), "Leaving sets a time to close");
        menu.Tick(At(1349));
        Check(menu.IsOpen, "The menu stays open until the delay has passed");
        menu.Tick(At(1350));
        Check(!menu.IsOpen, "The menu closes after the pointer has left");

        // Button to menu crosses a gap; reaching the menu in time keeps it open.
        menu = OpenByHover();
        menu.PointerOnButton(false, At(1000));
        menu.PointerOnMenu(true, At(1200));
        Check(menu.NextDue == null, "Reaching the menu cancels the close");
        menu.Tick(At(5000));
        Check(menu.IsOpen, "The menu stays open while the pointer is on it");

        // And back to the button.
        menu.PointerOnMenu(false, At(6000));
        menu.PointerOnButton(true, At(6100));
        menu.Tick(At(9000));
        Check(menu.IsOpen, "Going back to the button cancels the close");

        menu.PointerOnButton(false, At(9000));
        menu.Tick(At(9350));
        Check(!menu.IsOpen, "Leaving both closes it");

        menu.Tick(At(20000));
        Check(!menu.IsOpen && menu.NextDue == null, "A closed menu stays closed");
        Console.WriteLine("PASS: leaving closes the menu after the delay, and coming back in time cancels that");
    }

    private static void CheckClicks()
    {
        var menu = new MenuHover();
        menu.Click(At(0));
        Check(menu.IsOpen, "A click opens the menu at once");

        // Opened by UI Automation or a touch: the pointer was never on it.
        menu.Tick(At(60000));
        Check(menu.IsOpen && menu.NextDue == null, "A menu opened without the pointer stays open");
        menu.Click(At(60000));
        Check(!menu.IsOpen, "A second click closes it");

        // The menu opened by itself a moment before the click landed.
        menu = OpenByHover();
        menu.Click(At(300));
        Check(menu.IsOpen, "A click just after the menu opened by itself doesn't close it");
        menu.Click(At(150 + (int)MenuHover.ClickGrace.TotalMilliseconds));
        Check(!menu.IsOpen, "A later click does close it");
        menu.Tick(At(5000));
        Check(!menu.IsOpen, "Closed by a click, it doesn't reopen under a pointer that hasn't moved");

        // A click beats the hover delay, and the delay doesn't then misfire.
        menu = new MenuHover();
        menu.PointerOnButton(true, At(0));
        menu.Click(At(50));
        Check(menu.IsOpen && menu.NextDue == null, "A click during the delay opens the menu");
        menu.PointerOnButton(false, At(400));
        menu.Tick(At(750));
        Check(!menu.IsOpen, "Opened by a click with the mouse, it closes when the pointer leaves");
        Console.WriteLine("PASS: a click opens the menu, and closes one that has been open a while");
    }

    private static void CheckDrag()
    {
        var menu = OpenByHover();
        menu.PointerOnButton(false, At(1000));
        menu.PointerOnMenu(true, At(1100));
        menu.DragStarted();
        menu.PointerOnMenu(false, At(2000));
        Check(menu.NextDue == null, "Nothing is due while a slider is dragged");
        menu.Tick(At(10000));
        Check(menu.IsOpen, "A drag holds the menu open with the pointer off it");

        menu.DragEnded(At(10000));
        Check(menu.NextDue == At(10350), "The countdown starts when the drag ends");
        menu.Tick(At(10349));
        Check(menu.IsOpen, "The menu is still open just after the drag");
        menu.Tick(At(10350));
        Check(!menu.IsOpen, "It closes once the drag has ended with the pointer off it");

        menu = OpenByHover();
        menu.PointerOnButton(false, At(1000));
        menu.PointerOnMenu(true, At(1100));
        menu.DragStarted();
        menu.PointerOnMenu(false, At(2000));
        menu.PointerOnMenu(true, At(3000));
        menu.DragEnded(At(3100));
        menu.Tick(At(60000));
        Check(menu.IsOpen, "A drag that ends on the menu leaves it open");
        Console.WriteLine("PASS: dragging a slider holds the menu open");
    }

    private static void CheckDismiss()
    {
        var menu = OpenByHover();
        menu.Dismiss();
        Check(!menu.IsOpen && menu.NextDue == null, "Esc closes the menu at once");
        menu.Tick(At(5000));
        Check(!menu.IsOpen, "It doesn't reopen while the pointer stays on the button");
        menu.PointerOnButton(false, At(5000));
        menu.PointerOnButton(true, At(5100));
        menu.Tick(At(5250));
        Check(menu.IsOpen, "Leaving and coming back opens it again");

        // The popup is gone before the window hears the pointer left it.
        menu.PointerOnButton(false, At(6000));
        menu.PointerOnMenu(true, At(6100));
        menu.Dismiss();
        menu.PointerOnButton(true, At(7000));
        menu.Tick(At(7150));
        Check(menu.IsOpen, "setup: open again");
        menu.PointerOnButton(false, At(8000));
        menu.Tick(At(8350));
        Check(!menu.IsOpen, "A menu dismissed under the pointer doesn't count the pointer as still on it");

        menu = OpenByHover();
        menu.DragStarted();
        menu.Dismiss();
        Check(!menu.IsOpen, "Losing the window ends a drag and closes the menu");
        menu.PointerOnButton(false, At(1000));
        menu.Click(At(2000));
        menu.PointerOnMenu(true, At(2100));
        menu.PointerOnMenu(false, At(2200));
        menu.Tick(At(2550));
        Check(!menu.IsOpen, "The ended drag doesn't hold the next menu open");
        Console.WriteLine("PASS: Esc and leaving the window close the menu at once");
    }

    private static void CheckBackgroundWindow()
    {
        var menu = new MenuHover();
        menu.WindowActive(false, At(0));
        menu.PointerOnButton(true, At(100));
        Check(menu.NextDue == null, "Nothing is due for a pointer over a window in the background");
        menu.Tick(At(5000));
        Check(!menu.IsOpen, "Passing over a window in the background opens nothing");

        // Alt+Tab to the window with the pointer already resting on the button.
        menu.WindowActive(true, At(6000));
        Check(menu.NextDue == At(6150), "The rest is counted from when the window came to the front");
        menu.Tick(At(6149));
        Check(!menu.IsOpen, "Not before the delay");
        menu.Tick(At(6150));
        Check(menu.IsOpen, "A pointer already on the button opens the menu once the window is active");

        menu.WindowActive(false, At(7000));
        Check(!menu.IsOpen && menu.NextDue == null, "Leaving the window closes the menu at once");
        menu.Tick(At(9000));
        Check(!menu.IsOpen, "And it stays closed while the window is in the background");
        menu.WindowActive(true, At(10000));
        menu.Tick(At(10150));
        Check(menu.IsOpen, "Coming back with the pointer still there opens it again");

        // The pointer left while the window was in the background.
        menu.WindowActive(false, At(11000));
        menu.PointerOnButton(false, At(11100));
        menu.WindowActive(true, At(12000));
        menu.Tick(At(13000));
        Check(!menu.IsOpen && menu.NextDue == null, "Nothing opens if the pointer is no longer on the button");

        // A click opens it whatever the window thinks it is.
        var clicked = new MenuHover();
        clicked.WindowActive(false, At(0));
        clicked.Click(At(10));
        Check(clicked.IsOpen, "A click opens the menu of a window in the background");

        // Becoming active between the pointer arriving and the click.
        var late = new MenuHover();
        late.WindowActive(false, At(0));
        late.PointerOnButton(true, At(100));
        late.WindowActive(true, At(200));
        late.Click(At(260));
        Check(late.IsOpen && late.NextDue == null, "The click that brings the window forward opens the menu once");
        late.Tick(At(350));
        late.Click(At(400));
        Check(late.IsOpen, "And the rest that was being counted doesn't turn the click into a close");
        Console.WriteLine("PASS: resting on the button counts only while the window is active, from when it became active");
    }

    // ---------------- window size ----------------

    // A frame like the real one: the invisible resize border either side and
    // below, and the title bar.
    private const double FrameW = 14.67, FrameH = 36.67;
    private static readonly FitRect BigScreen = new(0, 0, 2560, 1392);

    private static void CheckFirstFit()
    {
        var start = new FitRect(200, 120, 400, 760);
        var fit = PhoneFit.Fit(1080, 2340, 0, FrameW, FrameH, start, BigScreen);
        Near(fit.Height, 820, "An upright phone starts at the usual height");
        Near((fit.Width - FrameW) / (fit.Height - FrameH), 1080.0 / 2340, "The picture fills the window below the title bar");
        Check(fit.Left == 200 && fit.Top == 120, "The window stays where it was");

        var wide = PhoneFit.Fit(2340, 1080, 0, FrameW, FrameH, start, BigScreen);
        Near(wide.Width - FrameW, 820 - FrameH, "A phone first seen on its side is as long as an upright one is tall");
        Near((wide.Width - FrameW) / (wide.Height - FrameH), 2340.0 / 1080, "The sideways picture fills the window too");

        var narrow = PhoneFit.Fit(300, 2340, 0, FrameW, FrameH, start, BigScreen);
        Check(narrow.Width == PhoneFit.MinWindowWidth, "A very narrow picture still leaves room for the title bar");

        Check(PhoneFit.Fit(0, 0, 0, FrameW, FrameH, start, BigScreen) == start, "No picture, no change");
        Console.WriteLine("PASS: the first picture sizes the window to the phone's shape");
    }

    private static void CheckTurning()
    {
        Check(PhoneFit.SameShape(1080, 2340, 1080, 2340), "The same size is the same shape");
        Check(PhoneFit.SameShape(1080, 2340, 720, 1560), "A smaller stream of the same screen is the same shape");
        Check(PhoneFit.SameShape(1080, 2340, 540, 1171), "Rounding doesn't make a new shape");
        Check(!PhoneFit.SameShape(1080, 2340, 2340, 1080), "A turned phone is a new shape");
        Check(!PhoneFit.SameShape(1080, 2340, 1080, 2100), "A different screen is a new shape");
        Check(!PhoneFit.SameShape(0, 0, 1080, 2340), "Nothing has no shape");

        // The user made the window smaller: the picture is 300 x 650 in a host of that size.
        var window = new FitRect(500, 200, 300 + FrameW, 650 + FrameH);
        double longSide = PhoneFit.ShownLongSide(1080, 2340, 300, 650);
        Near(longSide, 650, "The long side is the picture's height when it stands");
        var turned = PhoneFit.Fit(2340, 1080, longSide, FrameW, FrameH, window, BigScreen);
        Near(turned.Width - FrameW, 650, "Turned, the phone is as long as it was tall");
        Near(turned.Height - FrameH, 300, "And as tall as it was wide");

        var back = PhoneFit.Fit(1080, 2340, PhoneFit.ShownLongSide(2340, 1080, turned.Width - FrameW, turned.Height - FrameH),
            FrameW, FrameH, turned, BigScreen);
        Near(back.Width, window.Width, "Turned back, the window is the width it was");
        Near(back.Height, window.Height, "And the height it was");

        // The picture doesn't fill a window the user stretched; what counts is the picture.
        Near(PhoneFit.ShownLongSide(1080, 2340, 900, 650), 650, "Bars at the sides don't count towards the long side");
        Near(PhoneFit.ShownLongSide(1080, 2340, 300, 2000), 650, "Nor do bars above and below");
        Near(PhoneFit.ShownLongSide(2340, 1080, 650, 900), 650, "The same for a phone on its side");
        Check(PhoneFit.ShownLongSide(1080, 2340, 0, 0) == 0, "A host with no size shows nothing");
        Console.WriteLine("PASS: a phone that turns stays the same size on screen");
    }

    private static void CheckManyTurns()
    {
        // A window's size lands on whole pixels: two thirds of a unit apart at 150 % scaling.
        static double Snap(double units) => Math.Floor(units * 1.5) / 1.5;

        // The S26 as phones up to v1.0.53 send it: upright, and on its side at the same
        // limit on the width, which is not quite the same shape turned round.
        (int W, int H) upright = (1080, 2340), sideways = (1080, 498);

        double Turns(bool keep) => TurnsOf(sideways, keep);

        double TurnsOf((int W, int H) sideways, bool keep)
        {
            var window = PhoneFit.Fit(upright.W, upright.H, 0, FrameW, FrameH, new FitRect(200, 100, 400, 760), BigScreen);
            double fitted = PhoneFit.ShownLongSide(upright.W, upright.H, window.Width - FrameW, window.Height - FrameH);
            var shown = upright;
            for (int turn = 0; turn < 40; turn++)
            {
                var next = shown == upright ? sideways : upright;
                double hostW = Snap(window.Width) - FrameW, hostH = Snap(window.Height) - FrameH;
                double measured = PhoneFit.ShownLongSide(shown.W, shown.H, hostW, hostH);
                double longSide = keep ? PhoneFit.KeptLongSide(measured, fitted) : measured;
                window = PhoneFit.Fit(next.W, next.H, longSide, FrameW, FrameH, window, BigScreen);
                fitted = PhoneFit.ShownLongSide(next.W, next.H, window.Width - FrameW, window.Height - FrameH);
                shown = next;
            }
            return fitted;
        }

        double start = 820 - FrameH;
        Check(Turns(keep: false) < start - 10, "setup: measured from whole pixels, the long side shrinks turn by turn");
        Near(Turns(keep: true), start, "A phone turned forty times is the size it started at");
        // Newer phones send the same pixels turned.
        Near(TurnsOf((2340, 1080), keep: true), start, "The same for a phone that sends its side view at full size");

        Check(PhoneFit.KeptLongSide(781.2, 783.33) == 783.33, "A measurement a pixel or two short is the last fit's own long side");
        Check(PhoneFit.KeptLongSide(650, 783.33) == 650, "A window the user made smaller keeps the size they chose");
        Check(PhoneFit.KeptLongSide(900, 783.33) == 900, "And one they made larger");
        Check(PhoneFit.KeptLongSide(650, 0) == 650, "With no fit to go by, the measurement stands");
        Console.WriteLine("PASS: turning the phone back and forth doesn't shrink the window");
    }

    private static void CheckSmallWorkArea()
    {
        // A small laptop: 1280 x 720 at 150 % leaves about 853 x 440 to work in.
        var work = new FitRect(0, 0, 853, 440);
        var start = new FitRect(100, 60, 400, 760);

        var upright = PhoneFit.Fit(1080, 2340, 0, FrameW, FrameH, start, work);
        Check(Inside(upright, work), "An upright phone fits a small work area");
        Check(upright.Height <= work.Height - PhoneFit.DefaultMargin + 0.01, "With room left above and below at first");

        var sideways = PhoneFit.Fit(2340, 1080, 0, FrameW, FrameH, start, work);
        Check(Inside(sideways, work), "A phone on its side fits a small work area");

        // A window the user made as tall as a tall screen, then the phone turns
        // on a screen that is not that wide.
        var tall = new FitRect(0, 0, 1080, 1880);
        var turned = PhoneFit.Fit(2340, 1080, 1800, FrameW, FrameH, new FitRect(100, 20, 845, 1837), tall);
        Check(Inside(turned, tall), "A turned phone is made smaller rather than wider than the screen");
        Near(turned.Width, tall.Width, "It takes the full width");
        Near((turned.Width - FrameW) / (turned.Height - FrameH), 2340.0 / 1080, "And keeps its shape");

        // The same on a second monitor to the left of the first.
        var left = new FitRect(-1920, 0, 1920, 1040);
        var there = PhoneFit.Fit(2340, 1080, 1000, FrameW, FrameH, new FitRect(-600, 100, 500, 1037), left);
        Check(Inside(there, left), "A window on another monitor stays on that monitor");
        Console.WriteLine("PASS: upright and sideways phones both fit a small work area");
    }

    private static void CheckStaysOnScreen()
    {
        // Near the right edge, upright; turned, it would hang off the screen.
        var window = new FitRect(2100, 300, 400, 820);
        var turned = PhoneFit.Fit(2340, 1080, 783, FrameW, FrameH, window, BigScreen);
        Check(Inside(turned, BigScreen), "A window that grows wider is moved back on screen");
        Near(turned.Right, BigScreen.Right, "Only as far as it has to be");
        Check(turned.Top == 300, "And not up or down");

        // Near the bottom, on its side; upright, it would hang off the bottom.
        window = new FitRect(300, 900, 800, 400);
        var upright = PhoneFit.Fit(1080, 2340, 785, FrameW, FrameH, window, BigScreen);
        Check(Inside(upright, BigScreen), "A window that grows taller is moved back on screen");
        Near(upright.Bottom, BigScreen.Bottom, "Only as far as it has to be");
        Check(upright.Left == 300, "And not sideways");

        // Half off the left of the screen to begin with.
        window = new FitRect(-150, 100, 400, 820);
        var pulled = PhoneFit.Fit(2340, 1080, 783, FrameW, FrameH, window, BigScreen);
        Check(pulled.Left == 0, "A window off the left edge is brought back when it is resized");
        Console.WriteLine("PASS: the window stays on screen when the phone turns");
    }

    private static bool Inside(FitRect window, FitRect work) =>
        window.Left >= work.Left - 0.01 && window.Top >= work.Top - 0.01 &&
        window.Right <= work.Right + 0.01 && window.Bottom <= work.Bottom + 0.01;

    // ---------------- the volume slider ----------------

    private static void CheckThrottle()
    {
        var throttle = new SendThrottle(TimeSpan.FromMilliseconds(100));
        Check(throttle.Offer(5, At(0)) == 5, "The first value goes at once");
        Check(throttle.Offer(6, At(30)) == null, "A value too soon after is held");
        Check(throttle.Offer(7, At(60)) == null, "And the next");
        Check(throttle.Offer(8, At(100)) == 8, "After the interval the newest value goes");
        Check(throttle.Offer(9, At(150)) == null, "Then the interval starts again");
        Check(throttle.Finish(At(160)) == 9, "Letting go sends the value the slider stopped at");
        Check(throttle.Finish(At(170)) == null, "Once");

        Check(throttle.Offer(9, At(1000)) == null, "A value the phone already has isn't sent again");
        Check(throttle.Offer(10, At(1000)) == 10, "A new one is");
        Check(throttle.Offer(11, At(1010)) == null, "Held");
        Check(throttle.Offer(10, At(1020)) == null, "Back to the value last sent: nothing is owed");
        Check(throttle.Finish(At(1030)) == null, "So letting go sends nothing");
        Console.WriteLine("PASS: a dragged slider sends at most one value per interval, and always the last");
    }

    private static void CheckSecondDrag()
    {
        var throttle = new SendThrottle(TimeSpan.FromMilliseconds(100));
        // The first drag ends on 9.
        throttle.Forget();
        Check(throttle.Offer(8, At(0)) == 8, "setup: the first drag sends");
        Check(throttle.Offer(9, At(50)) == null, "setup: held");
        Check(throttle.Finish(At(60)) == 9, "setup: the first drag ends on 9");

        // The volume keys on the phone take it to 5, and the phone says so.
        throttle.Forget();
        // The second drag goes straight to 9, where the first one ended.
        throttle.Forget();
        Check(throttle.Offer(9, At(5000)) == 9, "A second drag that ends where the first did is still sent");
        Check(throttle.Finish(At(5010)) == null, "Once");

        // Within one drag nothing is forgotten: the value just sent isn't repeated.
        Check(throttle.Offer(9, At(6000)) == null, "The same value again in the same drag is not sent twice");

        // Forgetting keeps a value that is still waiting to go.
        throttle.Forget();
        Check(throttle.Offer(10, At(7000)) == 10, "setup: sent");
        Check(throttle.Offer(11, At(7010)) == null, "setup: held");
        throttle.Forget();
        Check(throttle.Finish(At(7020)) == 11, "A held value survives the phone reporting in mid-drag");

        // And the pacing carries on across it.
        throttle.Forget();
        Check(throttle.Offer(11, At(7050)) == null, "A new drag straight after still waits its turn");
        Check(throttle.Finish(At(7060)) == 11, "And sends its value when let go");
        Console.WriteLine("PASS: a second drag is sent even when it ends where the first one did");
    }

    private static void CheckRestingMidDrag()
    {
        var throttle = new SendThrottle(TimeSpan.FromMilliseconds(100));
        Check(throttle.NextDue == null && throttle.Tick(At(0)) == null, "Nothing is due before anything moves");
        Check(throttle.Offer(5, At(0)) == 5, "setup: sent");
        Check(throttle.NextDue == null, "Nothing is due when nothing is held");
        Check(throttle.Offer(6, At(40)) == null, "setup: held");
        Check(throttle.NextDue == At(100), "A held value is due when the interval is up");
        Check(throttle.Tick(At(99)) == null, "Not before");
        Check(throttle.Tick(At(100)) == 6, "A value the slider rests on goes without waiting for the drag to end");
        Check(throttle.NextDue == null && throttle.Tick(At(300)) == null, "Once");
        Check(throttle.Finish(At(400)) == null, "And letting go has nothing left to send");
        Console.WriteLine("PASS: a value the slider rests on mid-drag is sent when its turn comes");
    }

    private static void CheckVolumeAnswers()
    {
        // A drag from 5 to 8. The phone answers each message a little later.
        var volume = new VolumeSender();
        Check(volume.Heard(At(0)), "A state nobody is waiting on is shown");
        volume.DragStarted();
        Check(volume.Dragging, "setup: dragging");
        Check(volume.Moved(6, At(1000)) == 6, "The first level goes at once");
        Check(volume.Moved(7, At(1050)) == null, "The next is held");
        Check(volume.NextDue == At(1100), "And is due when the interval is up");
        Check(!volume.Heard(At(1080)), "The phone's answers are not shown during a drag");
        Check(volume.Tick(At(1100)) == 7, "The held level goes when its turn comes");
        Check(volume.Moved(8, At(1150)) == null, "Held again");
        Check(volume.DragEnded(At(1160)) == 8, "Letting go sends where the slider stopped");
        Check(!volume.Dragging, "The drag is over");
        // Three went out and one answer came during the drag. The answer to 7
        // arrives after the drag: the thumb must not jump back to 7.
        Check(!volume.Heard(At(1200)), "An answer to an earlier message doesn't pull the thumb back");
        Check(volume.Heard(At(1260)), "The answer to the last message is shown");
        Check(volume.Heard(At(5000)), "And so is anything the phone says later");

        // A click on the track, with no drag.
        Check(volume.Moved(3, At(6000)) == 3, "A click on the track is sent at once");
        Check(volume.Heard(At(6100)), "And its answer is shown");
        Check(volume.Moved(8, At(7000)) == 8, "setup: clicked to 8");
        Check(volume.Heard(At(7100)), "setup: answered");
        Check(volume.Moved(3, At(8000)) == 3, "A click back on a level sent before is sent again");
        Check(volume.Heard(At(8100)), "setup: answered");

        // Mute counts as a message of the slider's.
        volume.Sent(At(9000));
        Check(volume.Moved(4, At(9010)) == 4, "setup: a level straight after a mute");
        Check(!volume.Heard(At(9100)), "The answer to the mute is overtaken by the level");
        Check(volume.Heard(At(9110)), "The answer to the level is shown");

        // A phone that stops answering doesn't leave the slider deaf.
        Check(volume.Moved(9, At(20000)) == 9, "setup: sent");
        Check(volume.Moved(10, At(20200)) == 10, "setup: sent again");
        Check(volume.Heard(At(20200) + VolumeSender.AnswerWait), "Answers owed for too long are given up on");
        Check(volume.Heard(At(30000)), "And the next state is shown");

        // The menu closes under a drag (the window was left): the drag ends there.
        volume.DragStarted();
        Check(volume.Moved(12, At(40000)) == 12, "setup: sent");
        Check(volume.Moved(13, At(40020)) == null, "setup: held");
        Check(volume.DragEnded(At(40030)) == 13, "A drag cut short still sends where it had got to");
        Console.WriteLine("PASS: the slider shows the phone's answer to its last message, not to earlier ones");
    }

    private static void CheckAnswersToAsking()
    {
        // The menu opens, which asks the phone how things stand, and the user is
        // on the slider before the answer is back.
        var volume = new VolumeSender();
        volume.Sent(At(0));                                   // get-phone-state
        volume.DragStarted();
        Check(volume.Moved(6, At(100)) == 6, "setup: the drag's first level");
        Check(!volume.Heard(At(150)), "The answer to the asking is not shown mid-drag");
        Check(volume.Moved(7, At(250)) == 7, "setup: the drag's last level");
        Check(volume.DragEnded(At(300)) == null, "setup: nothing was held");
        Check(!volume.Heard(At(320)), "The answer to the first level is not the last word");
        Check(volume.Heard(At(380)), "The answer to the last level is");

        // The same without a drag: a click on the track straight after the menu opened.
        volume.Sent(At(5000));                                // get-phone-state
        Check(volume.Moved(3, At(5050)) == 3, "setup: clicked");
        Check(!volume.Heard(At(5100)), "The state from before the click doesn't pull the thumb back");
        Check(volume.Heard(At(5150)), "The answer to the click is shown");

        // The phone hadn't let this PC in when the window opened, so the first asking
        // was dropped. It is asked again once it has, ten seconds on.
        var late = new VolumeSender();
        late.Sent(At(0));
        late.Sent(At(10000));
        Check(late.Heard(At(10100)), "An asking that was never answered doesn't hold back the next answer");

        // A phone that drops what it is sent for a while (its switch was off).
        late.Sent(At(20000));
        late.Sent(At(20500));
        late.Sent(At(21000));
        late.Sent(At(30000));
        Check(late.Heard(At(30100)), "However many went unanswered");

        // Only the volume is ever held back.
        var shown = PhoneState.Parse(Json("""{"volume":9,"volumeMax":15,"muted":false,"rotation":"auto","can":["volume","rotate"]}"""))!;
        var heard = PhoneState.Parse(Json("""{"volume":4,"volumeMax":15,"muted":true,"rotation":"landscape","can":["volume","rotate","lock"]}"""))!;
        var merged = heard.WithVolumeOf(shown);
        Check(merged is { Volume: 9, Muted: false }, "A state whose volume is held back keeps the volume on show");
        Check(merged.Rotation == PhoneState.Landscape && merged.Can.SetEquals(["volume", "rotate", "lock"]),
            "What the phone can do and how its screen is held are taken from it all the same");
        var smaller = PhoneState.Parse(Json("""{"volume":2,"volumeMax":7,"can":["volume"]}"""))!;
        Check(smaller.WithVolumeOf(shown).Volume == 7, "The volume on show never passes the top of the range");
        Console.WriteLine("PASS: asking the phone how things stand is counted like any message it answers");
    }

    private static void CheckAskPace()
    {
        var asks = new AskPace();
        Check(asks.Ask(At(0)), "The first asking goes");
        Check(!asks.Ask(At(300)) && !asks.Ask(At(600)) && !asks.Ask(At(999)), "A menu opened again and again within a second asks once");
        Check(asks.Ask(At(1000)), "A second later it asks again");
        Check(!asks.Ask(At(1500)), "And the second starts over");
        Check(asks.Ask(At(1600), always: true), "The phone has just let this PC in: that asking always goes");
        Check(!asks.Ask(At(2000)), "And counts towards the pace");
        Check(asks.Ask(At(2600)), "Until its second is up");
        Console.WriteLine("PASS: the phone is asked how things stand at most once a second");
    }

    // ---------------- what the phone says ----------------

    private static void CheckPhoneState()
    {
        // The same text is built by the phone in PhoneControlsTest
        // (phoneStateIsBuiltAsTheWindowsAppReadsIt), so the two apps can't come to
        // disagree about a field's name or type. Change both together.
        var shared = PhoneState.Parse(Json("""
            {"type":"phone-state","to":"pc","volume":7,"volumeMax":15,"muted":false,"rotation":"auto","can":["volume","rotate","quick-settings","lock","screenshot"]}
            """));
        Check(shared is { Volume: 7, VolumeMax: 15, Muted: false, Rotation: PhoneState.Auto, IsSilent: false }, "What the phone builds is read");
        Check(shared!.Can.SetEquals(["volume", "rotate", "quick-settings", "lock", "screenshot"]), "With the rows it offers");

        var muted = PhoneState.Parse(Json("""{"volume":0,"volumeMax":15,"muted":true,"can":["volume"]}"""));
        Check(muted is { Muted: true, IsSilent: true }, "A muted phone is silent");
        var down = PhoneState.Parse(Json("""{"volume":0,"volumeMax":15,"muted":false,"can":["volume"]}"""));
        Check(down is { Muted: false, IsSilent: true }, "So is one turned right down");

        // The speaker icon.
        Check(shared.SpeakerClick(7) == (true, null), "A click on the speaker mutes a phone that is making sound");
        Check(muted!.SpeakerClick(7) == (false, null), "And unmutes a muted one, which goes back to its own level");
        Check(down!.SpeakerClick(9) == (null, 9), "A phone turned right down goes back to the last level it was heard at");
        Check(down.SpeakerClick(0) == (null, 1), "Or to the quietest step when none is known");
        Check(down.SpeakerClick(40) == (null, 15), "Never past the top");

        var state = PhoneState.Parse(Json("""
            {"type":"phone-state","to":"pc","volume":7,"volumeMax":15,"muted":false,"rotation":"landscape",
             "can":["volume","rotate","quick-settings","lock","screenshot"]}
            """));
        Check(state is { Volume: 7, VolumeMax: 15, Muted: false, Rotation: PhoneState.Landscape }, "A full state is read");
        Check(state!.Can.SetEquals(["volume", "rotate", "quick-settings", "lock", "screenshot"]), "With everything the phone can do");

        var old = PhoneState.Parse(Json("""{"type":"phone-state","volume":3,"volumeMax":15,"can":["volume","lock"]}"""));
        Check(old is { Volume: 3, Muted: false, Rotation: PhoneState.Auto }, "Missing fields take the quiet answer");
        Check(old!.Can.SetEquals(["volume", "lock"]), "Only what the phone lists is offered");

        var bare = PhoneState.Parse(Json("""{"type":"phone-state"}"""));
        Check(bare is { VolumeMax: 0 } && bare.Can.Count == 0, "A phone that lists nothing is offered nothing");

        var odd = PhoneState.Parse(Json("""
            {"volume":99,"volumeMax":15,"muted":"yes","rotation":"upside-down","can":["volume",7,null,"teleport"]}
            """));
        Check(odd is { Volume: 15, Muted: false, Rotation: PhoneState.Auto }, "Values that make no sense are put right");
        Check(odd!.Can.SetEquals(["volume", "teleport"]), "Entries that aren't names are dropped; unknown names are harmless");

        var fixedVolume = PhoneState.Parse(Json("""{"volume":0,"volumeMax":0,"can":["volume","rotate"]}"""));
        Check(fixedVolume!.Can.SetEquals(["rotate"]), "A phone with no volume range gets no slider");

        var negative = PhoneState.Parse(Json("""{"volume":-4,"volumeMax":15.0,"can":"volume"}"""));
        Check(negative is { Volume: 0, VolumeMax: 15 } && negative.Can.Count == 0, "Numbers and lists in other forms don't break it");
        Console.WriteLine("PASS: phone-state is read, and anything odd in it is made safe");
    }

    private static void CheckMenuRows()
    {
        var none = new MenuRows(false, false, false, false, false, false);

        // Nothing has come from the phone: it may not have approved this PC yet.
        Check(MenuRows.For(null, null, At(0)) == none, "Before the phone sends anything there are no extra rows");
        Check(MenuRows.For(null, null, At(60000)) == none, "However long it says nothing, a phone that shows no picture isn't called old");
        Check(MenuRows.UpdateLineDue(null, null) == null, "And nothing is waited for");

        // A picture arrived at 1000, so the phone is answering this PC.
        Check(MenuRows.For(null, At(1000), At(1500)) == none, "A phone gets a moment to say what it can do");
        Check(MenuRows.UpdateLineDue(null, At(1000)) == At(1000) + MenuRows.AnswerWait, "The update line is due after the wait");
        Check(MenuRows.For(null, At(1000), At(1000) + MenuRows.AnswerWait) == none with { UpdateLine = true },
            "A phone that shows a picture and never says what it can do gets the update line, and no rows");

        var full = PhoneState.Parse(Json("""{"volume":7,"volumeMax":15,"can":["volume","rotate","quick-settings","lock","screenshot"]}"""));
        Check(MenuRows.For(full, At(1000), At(1100)) == new MenuRows(true, true, true, true, true, false), "A phone that can do everything gets every row");
        Check(MenuRows.For(full, null, At(1100)) == new MenuRows(true, true, true, true, true, false), "Whether or not a picture has come yet");

        // The phone app as first released with the menu: everything but rotation.
        var unturning = PhoneState.Parse(Json("""{"volume":7,"volumeMax":15,"can":["volume","quick-settings","lock","screenshot"]}"""));
        Check(MenuRows.For(unturning, At(1000), At(1100)) == new MenuRows(true, false, true, true, true, false), "A phone that can't turn its screen gets no rotation row");
        Check(MenuRows.For(full, At(1000), At(90000)) is { UpdateLine: false }, "And never the update line");
        Check(MenuRows.UpdateLineDue(full, At(1000)) == null, "Nor a wait for it");

        // Android 8: no lock, no screenshot.
        var partial = PhoneState.Parse(Json("""{"volume":7,"volumeMax":15,"can":["volume","quick-settings"]}"""));
        Check(MenuRows.For(partial, At(1000), At(5000)) == new MenuRows(true, false, true, false, false, false), "Only the rows the phone lists are shown");

        // The accessibility service is off and the volume is fixed.
        var nothing = PhoneState.Parse(Json("""{"volume":0,"volumeMax":0,"can":["volume"]}"""));
        Check(MenuRows.For(nothing, At(1000), At(5000)) == none, "A phone that can do none of it gets no rows and no update line");

        // A newer phone lists something this app has no row for.
        var newer = PhoneState.Parse(Json("""{"volume":3,"volumeMax":15,"can":["rotate","torch","lock"]}"""));
        Check(MenuRows.For(newer, At(1000), At(5000)) == none with { Rotation = true, Lock = true }, "Names this app doesn't know add nothing");
        Console.WriteLine("PASS: the menu shows the rows the phone lists, or says to update a phone that lists nothing");
    }

    private static void CheckSmoothVideo()
    {
        static JsonObject Status(string state, string? reason = null)
        {
            var msg = new JsonObject { ["type"] = "smooth-status", ["state"] = state };
            if (reason != null) msg["reason"] = reason;
            return msg;
        }

        // A phone that has said nothing about smooth sharing has no row: an
        // app from before this, or a phone this PC has not approved.
        var smooth = new SmoothVideo();
        Check(!smooth.Row.Shown, "no row until the phone says");
        Check(smooth.Choose(At(0)) == null && smooth.NextDue == null, "nothing to ask of such a phone");
        // A word from some later app is not a row either.
        smooth.Offer("sometimes");
        Check(!smooth.Row.Shown, "an unknown word is no offer");

        smooth.Offer(SmoothVideo.Available);
        Check(smooth.Row == new SmoothRow(true, true, false, SmoothVideo.AskTip), "available: shown, not ticked");
        Check(smooth.Row.ToolTip.Contains("scroll the prompt"), "the tooltip says a sideways phone's prompt may need scrolling");
        smooth.Offer(SmoothVideo.Unavailable);
        Check(smooth.Row == new SmoothRow(true, false, false, SmoothVideo.UnavailableTip), "unavailable: shown greyed");
        Check(smooth.Choose(At(0)) == null, "a greyed row asks nothing");
        smooth.Offer(SmoothVideo.On);
        Check(smooth.Row == new SmoothRow(true, true, true, SmoothVideo.OnTip), "on: ticked");
        // The phone stops saying: this PC no longer approves it, say.
        smooth.Offer(null);
        Check(!smooth.Row.Shown, "the row goes when the phone stops saying");

        // Asking, and the phone answering in time.
        smooth.Offer(SmoothVideo.Available);
        Check(smooth.Choose(At(0)) == "request-smooth", "choosing the row asks the phone to start");
        Check(smooth.NextDue == At(5000), "an answer is waited for, five seconds");
        Check(smooth.Tick(At(4999)) == null, "not reported before its time");
        Check(smooth.Status(Status("prompting")) == SmoothVideo.PromptText, "the prompt is up: say where to click");
        Check(smooth.NextDue == null && smooth.Tick(At(6000)) == null, "an answer ends the wait");
        Check(!smooth.Row.Ticked, "not on until the phone says it started");
        Check(smooth.Status(Status("started")) == "Smooth video is on." && smooth.Row.Ticked, "started: ticked");

        // Stopping, and being told why it ended.
        Check(smooth.Choose(At(10_000)) == "stop-smooth", "choosing the ticked row asks the phone to stop");
        Check(smooth.Status(Status("ended", "Smooth video was stopped from RIG-PC.")) == "Smooth video was stopped from RIG-PC.",
            "the reason is shown as the phone gave it");
        Check(smooth.Row is { Shown: true, Ticked: false, Enabled: true }, "ended: it can be asked for again");
        Check(smooth.NextDue == null, "the end is the answer to a stop");

        // A phone that says nothing: it dropped the request, as it does from a device that may not ask.
        smooth.Choose(At(20_000));
        Check(smooth.Tick(At(25_000)) == SmoothVideo.NoAnswerText, "no answer in five seconds is reported");
        Check(smooth.Tick(At(26_000)) == null && smooth.NextDue == null, "and only once");
        // The phone's screen-info can be the answer too.
        smooth.Choose(At(30_000));
        smooth.Offer(SmoothVideo.On);
        Check(smooth.NextDue == null, "a change in what the phone offers ends the wait");
        smooth.Choose(At(31_000));
        smooth.Offer(SmoothVideo.On);
        Check(smooth.NextDue == At(36_000), "a repeat of what it offered does not");

        // Each state has words, with the phone's own where it gave some.
        smooth = new SmoothVideo();
        smooth.Offer(SmoothVideo.Available);
        Check(smooth.Status(Status("prompting", "Tap the notification on the phone to see the prompt."))
            == "Tap the notification on the phone to see the prompt.", "a prompt that needs a tap first says so, and nothing else");
        Check(smooth.Status(Status("declined")) == "The prompt was declined on the phone.", "declined");
        Check(smooth.Status(Status("declined", "Nobody answered the prompt on the phone.")) == "Nobody answered the prompt on the phone.",
            "declined, with why");
        Check(smooth.Status(Status("unavailable", "Unlock the phone first.")) == "Unlock the phone first.", "unavailable, with why");
        Check(smooth.Status(Status("unavailable")) == "The phone can't start smooth video right now.", "unavailable");
        Check(smooth.Status(Status("ended")) == "Smooth video has ended.", "ended");
        Check(!smooth.Row.Ticked, "none of those turned it on");

        // Odd messages: an unknown state is nothing, and it is not an answer.
        smooth.Choose(At(40_000));
        Check(smooth.Status(Status("later")) == null && smooth.NextDue == At(45_000), "an unknown state says nothing");
        Check(smooth.Status(new JsonObject { ["state"] = 7 }) == null, "a state that is no text");
        // The phone's words are shown on one line, and not at any length.
        var said = smooth.Status(Status("unavailable", "  one\ntwo\u0007 " + new string('x', 400)))!;
        Check(said.StartsWith("one two  xxx") && said.Length == SmoothVideo.MaxReason + 1 && said.EndsWith("…"), "a long reason is cut: " + said);
        Check(smooth.Status(Status("unavailable", " \n ")) == "The phone can't start smooth video right now.", "an empty reason is none");
        Check(smooth.Status(new JsonObject { ["state"] = "declined", ["reason"] = 5 }) == "The prompt was declined on the phone.",
            "a reason that is no text is none");

        Console.WriteLine("PASS: Smooth video is offered only by a phone that says it can be, asks and stops, and says what came of it");
    }

    private static void CheckRotation()
    {
        Check(RotationCheck.Matches(PhoneState.Landscape, 2340, 1080), "A wide picture is a phone on its side");
        Check(!RotationCheck.Matches(PhoneState.Landscape, 1080, 2340), "A tall one is not");
        Check(RotationCheck.Matches(PhoneState.Portrait, 1080, 2340), "A tall picture is an upright phone");
        Check(!RotationCheck.Matches(PhoneState.Portrait, 2340, 1080), "A wide one is not");
        Check(RotationCheck.Matches(PhoneState.Auto, 2340, 1080) && RotationCheck.Matches(PhoneState.Auto, 1080, 2340), "Auto asks for no shape");

        // Landscape chosen with the phone upright, and the phone turns.
        var check = new RotationCheck();
        Check(check.NextDue == null && !check.Tick(At(0)), "Nothing is waited for before anything is chosen");
        check.Chose(PhoneState.Landscape, 1080, 2340, At(1000));
        Check(check.NextDue == At(1000) + RotationCheck.Wait, "The phone gets a few seconds to turn");
        check.Picture(1080, 2340);
        Check(check.NextDue != null, "The same picture again is not a turn");
        check.Picture(1080, 498);
        Check(check.NextDue == null, "The picture turned: nothing more to wait for");
        Check(!check.Tick(At(9000)), "A phone that turned is not complained about");

        // The app on screen only draws upright.
        check.Chose(PhoneState.Landscape, 1080, 2340, At(10000));
        Check(!check.Tick(At(12999)), "Not before the wait is up");
        Check(check.Tick(At(13000)), "A phone that hasn't turned in time is reported");
        Check(!check.Tick(At(14000)) && check.NextDue == null, "Once");

        // Already the shape that was asked for: nothing can be learnt from the picture.
        check.Chose(PhoneState.Portrait, 1080, 2340, At(20000));
        Check(check.NextDue == null, "Choosing the shape the picture already has waits for nothing");
        check.Chose(PhoneState.Landscape, 2340, 1080, At(20000));
        Check(check.NextDue == null, "The same for a phone already on its side");

        // Auto: the phone may stay as it is or turn; either is right.
        check.Chose(PhoneState.Landscape, 1080, 2340, At(30000));
        check.Chose(PhoneState.Auto, 1080, 2340, At(31000));
        Check(check.NextDue == null && !check.Tick(At(40000)), "Going back to Auto cancels the wait");

        // A second choice starts the wait again.
        check.Chose(PhoneState.Landscape, 1080, 2340, At(50000));
        check.Picture(1080, 498);
        check.Chose(PhoneState.Portrait, 1080, 498, At(51000));
        Check(check.NextDue == At(51000) + RotationCheck.Wait, "Each choice has its own wait");
        Check(!check.Tick(At(53000)), "The first choice's time doesn't count for the second");
        Check(check.Tick(At(54000)), "The second is reported when its own time is up");

        // No picture yet: there is nothing to compare with.
        check.Chose(PhoneState.Landscape, 0, 0, At(60000));
        Check(check.NextDue == null, "Before any picture nothing is waited for");
        Console.WriteLine("PASS: a phone that was asked to turn and didn't is reported once, after a few seconds");
    }

    // ---------------- asleep, locked, unlock screen ----------------

    private static void CheckLockState()
    {
        // The same texts are built by the phone in LockStateTest
        // (lockStateIsBuiltAsTheViewersReadIt), so the two apps can't come to
        // disagree about a field's name or type. Change both together.
        var awake = LockInfo.Parse(Json("""{"type":"lock-state","to":"pc","screenOn":true,"locked":false}"""));
        Check(awake == new LockInfo(true, false), "What an awake, unlocked phone builds is read");
        var refused = LockInfo.Parse(Json(
            """{"type":"lock-state","to":"pc","screenOn":false,"locked":true,"wakeFailed":"notifications"}"""));
        Check(refused == new LockInfo(false, true, LockInfo.Notifications), "And what a phone that didn't wake builds");

        var later = LockInfo.Parse(Json("""
            {"type":"lock-state","screenOn":true,"locked":true,"biometrics":{"face":true},"attempts":3}
            """));
        Check(later == new LockInfo(true, true), "Fields a later phone adds are ignored");
        Check(LockInfo.Parse(Json("""{"screenOn":true,"locked":false,"wakeFailed":"notifications"}""")) == new LockInfo(true, false),
            "A wake can't have failed on a phone whose screen is on");
        Check(LockInfo.Parse(Json("""{"screenOn":false,"locked":false,"wakeFailed":7}""")) == new LockInfo(false, false),
            "A reason that isn't a name is dropped");

        Check(LockInfo.Parse(Json("""{"type":"lock-state"}""")) == null, "A message with neither field is refused");
        Check(LockInfo.Parse(Json("""{"screenOn":true}""")) == null, "So is one without locked");
        Check(LockInfo.Parse(Json("""{"screenOn":"yes","locked":false}""")) == null, "And one whose screenOn isn't true or false");
        Check(LockInfo.Parse(Json("""{"screenOn":1,"locked":0}""")) == null, "Numbers are not true or false");
        Check(LockInfo.Parse(Json("""{"screenOn":null,"locked":[true]}""")) == null, "Nor is anything else");
        Console.WriteLine("PASS: lock-state is read as the phone builds it, and anything odd in it is refused");
    }

    private static void CheckCaptions()
    {
        var awake = new LockInfo(true, false);
        Check(LockCaption.For(awake, false) == null, "An awake, unlocked phone has no caption");
        Check(LockCaption.For(awake, true) == null, "However dark the app on its screen is");

        foreach (bool locked in new[] { false, true })
            foreach (bool black in new[] { false, true })
            {
                Check(LockCaption.For(new LockInfo(false, locked), black) == LockCaption.Asleep,
                    "A sleeping phone says so, whatever the picture");
                Check(LockCaption.For(new LockInfo(false, locked, "notifications"), black) == LockCaption.WakeFailedNotifications,
                    "A phone that didn't wake with notifications off says to allow them");
                Check(LockCaption.For(new LockInfo(false, locked, "unknown"), black) == LockCaption.WakeFailed,
                    "A phone that didn't wake for no known reason says to try again");
                Check(LockCaption.For(new LockInfo(false, locked, "something-new"), black) == LockCaption.WakeFailed,
                    "A reason a later phone might give is still a wake that failed");
            }

        var lockScreen = new LockInfo(true, true);
        Check(LockCaption.For(lockScreen, false) == null, "The lock screen is in the picture and needs no words");
        Check(LockCaption.For(lockScreen, true) == LockCaption.UnlockHidden, "A black picture from a locked phone is its unlock screen");

        Check(LockCaption.For(null, false) == null, "A phone that never says how it stands has no caption over a picture");
        Check(LockCaption.For(null, true) == LockCaption.Black, "Its black picture gets words that cover asleep and unlock screen");

        Check(LockCaption.BlackMatters(null) && LockCaption.BlackMatters(lockScreen), "Black matters on a locked phone or one that never says");
        Check(!LockCaption.BlackMatters(awake) && !LockCaption.BlackMatters(new LockInfo(false, true)),
            "Not on an unlocked phone, nor on one known to be asleep");

        Check(LockCaption.Asleep == "The phone is asleep. Click to wake it.", "The asleep wording");
        Check(LockCaption.WakeFailedNotifications == "The phone didn't wake. Allow notifications for Remote Desktop on the phone.",
            "The notifications wording");
        Check(LockCaption.UnlockHidden == "Android hides the unlock screen from the picture. Clicks and drags still reach it.",
            "The unlock screen wording");
        Check(LockCaption.Black == "The phone's screen is off, or it is on its unlock screen. Clicks and drags still reach an unlock screen.",
            "The wording for a phone that sends no lock-state");
        Console.WriteLine("PASS: each state of the phone has its caption, and an awake, unlocked phone has none");
    }

    /// <summary>A picture of one colour, as BGRA rows with <paramref name="padding"/> spare bytes after each.</summary>
    private static (byte[] Pixels, int Stride) Picture(int width, int height, byte grey, int bytesPerPixel = 4, int padding = 0)
    {
        int stride = width * bytesPerPixel + padding;
        var pixels = new byte[stride * height];
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
                for (int c = 0; c < bytesPerPixel; c++)
                    // The fourth byte is alpha, or unused: full either way.
                    pixels[y * stride + x * bytesPerPixel + c] = c == 3 ? (byte)255 : grey;
            // Whatever lies between rows must not be read as picture.
            for (int c = 0; c < padding; c++) pixels[y * stride + width * bytesPerPixel + c] = 255;
        }
        return (pixels, stride);
    }

    private static void Fill(byte[] pixels, int stride, int left, int top, int right, int bottom, byte b, byte g, byte r)
    {
        for (int y = top; y < bottom; y++)
            for (int x = left; x < right; x++)
            {
                pixels[y * stride + x * 4] = b;
                pixels[y * stride + x * 4 + 1] = g;
                pixels[y * stride + x * 4 + 2] = r;
            }
    }

    private static void CheckBlackPicture()
    {
        static bool Black((byte[] Pixels, int Stride) p, int w, int h, int bpp = 4) =>
            LockOverlay.IsNearlyBlack(p.Pixels, w, h, p.Stride, bpp);

        // The fourth byte of each pixel is alpha, at full: it is not colour.
        Check(Black(Picture(1080, 2340, 0), 1080, 2340), "A black picture is black");
        Check(Black(Picture(1080, 2340, 4), 1080, 2340), "So is what a JPEG leaves of black");
        Check(Black(Picture(2340, 1080, 0), 2340, 1080), "Sideways too");
        Check(Black(Picture(40, 90, 0), 40, 90), "And small");

        // The unlock screen keeps its Back arrow; a phone whose screen is off sends "Screen unavailable".
        var arrow = Picture(1080, 2340, 0);
        Fill(arrow.Pixels, arrow.Stride, 900, 2200, 960, 2260, 255, 255, 255);
        Check(Black(arrow, 1080, 2340), "A Back arrow on black is still nearly all black");
        var words = Picture(1080, 2340, 0);
        Fill(words.Pixels, words.Stride, 300, 1150, 780, 1190, 255, 255, 255);
        Check(Black(words, 1080, 2340), "So is a line of words on black");

        // A lock screen on a black wallpaper: a clock and a few lines.
        var clock = Picture(1080, 2340, 0);
        Fill(clock.Pixels, clock.Stride, 300, 400, 780, 600, 255, 255, 255);
        Check(!Black(clock, 1080, 2340), "A clock on a black wallpaper is a picture");
        Check(!Black(Picture(1080, 2340, 0x17), 1080, 2340), "A dark theme is dark grey, not black");
        var blue = Picture(1080, 2340, 0);
        Fill(blue.Pixels, blue.Stride, 0, 0, 1080, 2340, 90, 0, 0);
        Check(!Black(blue, 1080, 2340), "One colour is enough to be lit");
        Check(!Black(Picture(1080, 2340, 255), 1080, 2340), "A white picture is not black");

        // Other layouts of the same pixels.
        Check(Black(Picture(360, 800, 0, padding: 3), 360, 800), "Spare bytes after each row are not picture");
        Check(Black(Picture(360, 800, 0, bytesPerPixel: 3), 360, 800, 3), "Three bytes to a pixel");
        Check(Black(Picture(360, 800, 0, bytesPerPixel: 1), 360, 800, 1), "A grey picture, one byte to a pixel");
        Check(!Black(Picture(360, 800, 200, bytesPerPixel: 1), 360, 800, 1), "A light grey one is not black");

        // Nothing to go on: say it is a picture, so no caption is put up over it.
        Check(!LockOverlay.IsNearlyBlack([], 0, 0, 0, 4), "No picture is not a black picture");
        Check(!LockOverlay.IsNearlyBlack(new byte[100], 1080, 2340, 4320, 4), "Nor is a buffer too short for its size");
        Check(!LockOverlay.IsNearlyBlack(new byte[400], 10, 10, 20, 4), "Nor rows shorter than the picture is wide");
        Console.WriteLine("PASS: a picture is black when nearly all of it is, whatever the layout of its pixels");
    }

    private static void CheckBlackWatch()
    {
        var unlocked = new LockInfo(true, false);
        var locked = new LockInfo(true, true);
        var watch = new BlackWatch();
        Check(!watch.Due(unlocked, At(0)), "An unlocked phone's pictures are never looked at");
        Check(!watch.Due(new LockInfo(false, true), At(0)), "Nor a sleeping phone's: it is known to be asleep");
        Check(watch.Due(locked, At(0)), "A locked phone's first picture is looked at");
        Check(watch.Due(null, At(0)), "So is that of a phone that never says how it stands");

        watch.Looked(true, At(0));
        Check(watch.IsBlack, "The answer is remembered");
        Check(!watch.Due(locked, At(499)), "The next pictures are not looked at");
        Check(watch.Due(locked, At(500)), "Until half a second has gone");
        watch.Looked(false, At(500));
        Check(!watch.IsBlack, "The new answer replaces the old");

        watch.Looked(true, At(1000));
        watch.Forget();
        Check(!watch.IsBlack, "A change in the phone's state forgets the answer");
        Check(watch.Due(locked, At(1001)), "And the next picture is looked at straight away");
        Console.WriteLine("PASS: pictures are looked at twice a second at most, and only while black would mean something");
    }

    // ---------------- the unlock screen, drawn ----------------

    private const string SharedPattern = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1454,0.5141,0.7083,0.3269]},"message":"For your security, draw your pattern.","to":"pc"}""";
    private const string SharedPin = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[{"label":"1","rect":[0.1389,0.375,0.2037,0.0917]},{"label":"0","rect":[0.3981,0.7,0.2037,0.0917]},{"label":"delete","rect":[0.1389,0.7,0.2037,0.0917]},{"label":"ok","rect":[0.6574,0.7,0.2037,0.0917]}]},"message":"Enter PIN","to":"pc"}""";
    private const string SharedPassword = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"password"},"to":"pc"}""";

    /// <summary>A whole pad: twelve keys in four rows, as Android's own lock screen lays them out on a 1080 x 2400 screen.</summary>
    private static string PinPad(bool withDelete = true, bool withOk = true)
    {
        string[][] rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["delete", "0", "ok"]];
        var keys = new List<string>();
        for (int r = 0; r < 4; r++)
            for (int c = 0; c < 3; c++)
            {
                var label = rows[r][c];
                if (label == "delete" && !withDelete || label == "ok" && !withOk) continue;
                double x = (150 + c * 280) / 1080.0, y = (900 + r * 260) / 2400.0;
                keys.Add(FormattableString.Invariant($$"""{"label":"{{label}}","rect":[{{x:0.####}},{{y:0.####}},0.2037,0.0917]}"""));
            }
        return $$"""{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[{{string.Join(",", keys)}}]},"message":"Enter PIN"}""";
    }

    private static void CheckLockEntry()
    {
        // The same texts are built by the phone in LockStateTest
        // (theUnlockScreenIsDescribedAsTheViewersReadIt). Change them together.
        var pattern = LockInfo.Parse(Json(SharedPattern));
        Check(pattern is { ScreenOn: true, Locked: true, Message: "For your security, draw your pattern." }, "A pattern screen is read");
        Check(pattern!.Entry == new PatternEntry(new FRect(0.1454, 0.5141, 0.7083, 0.3269)), "With the S26's grid where the phone says it is");

        // Four keys are not a pad: all ten digits have to be there.
        Check(LockInfo.Parse(Json(SharedPin)) is { Entry: null, Message: "Enter PIN" }, "A pad without all ten digits is not drawn");
        var pin = LockInfo.Parse(Json(PinPad()));
        Check(pin?.Entry is PinEntry { Keys.Count: 12 }, "A whole pad is read");
        var pad = (PinEntry)pin!.Entry!;
        Check(pad.Keys[0] == new PinKey("1", new FRect(0.1389, 0.375, 0.2037, 0.0917)), "Key by key, as the phone gives them");
        Check(LockInfo.Parse(Json(PinPad())) == pin, "The same pad read twice is the same state");
        Check(LockInfo.Parse(Json(PinPad(withOk: false))) != pin, "A pad without its OK key is another");
        Check(LockInfo.Parse(Json(PinPad(withOk: false)))?.Entry is PinEntry { Keys.Count: 11 }, "And is still a pad");

        Check(LockInfo.Parse(Json(SharedPassword)) is { Entry: PasswordEntry, Message: null }, "A password screen is read");

        // What the phone can do goes by the screen and the lock, not by the message.
        var wrong = LockInfo.Parse(Json(SharedPattern.Replace("For your security, draw your pattern.", "Incorrect pattern drawn")));
        Check(wrong != pattern && wrong!.SameScreenAndLock(pattern), "A new message is a new state with the same screen and lock");
        Check(!new LockInfo(true, false).SameScreenAndLock(pattern) && !pattern.SameScreenAndLock(null), "Unlocking is not");

        // An unlock screen is only ever on a phone that is locked with its screen on.
        Check(LockInfo.Parse(Json(SharedPattern.Replace("\"locked\":true", "\"locked\":false"))) == new LockInfo(true, false),
            "Nothing is entered on an unlocked phone");
        Check(LockInfo.Parse(Json(SharedPattern.Replace("\"screenOn\":true", "\"screenOn\":false"))) == new LockInfo(false, true),
            "Nor on a sleeping one");

        // Anything odd: no entry, and the viewer says what it would without one.
        string With(string entry) => $$"""{"screenOn":true,"locked":true,"entry":{{entry}}}""";
        foreach (var odd in new[]
        {
            "null", "7", "\"pattern\"", "{}", """{"kind":"swipe"}""", """{"kind":7}""",
            """{"kind":"pattern"}""", """{"kind":"pattern","area":[0.1,0.5,0.7]}""", """{"kind":"pattern","area":[0.1,0.5,0.7,"0.3"]}""",
            """{"kind":"pattern","area":[0.1,0.5,0,0.3]}""", """{"kind":"pattern","area":[-0.1,0.5,0.7,0.3]}""",
            """{"kind":"pattern","area":[0.5,0.5,0.7,0.3]}""", """{"kind":"pattern","area":[0.1,0.9,0.7,0.3]}""",
            """{"kind":"pin"}""", """{"kind":"pin","keys":[]}""", """{"kind":"pin","keys":"1234567890"}""",
        })
            Check(LockInfo.Parse(Json(With(odd))) == new LockInfo(true, true), "An entry that makes no sense is no entry: " + odd);

        // Keys this viewer has no use for are left out; the pad still stands.
        var extra = PinPad().Replace("""{"label":"1",""", """{"label":"emergency","rect":[0,0,0.1,0.1]},{"label":7,"rect":[0,0,0.1,0.1]},{"label":"1","rect":[2,2,2,2]},{"label":"1",""");
        Check(LockInfo.Parse(Json(extra)) == pin, "Unknown keys, and a key that isn't on the screen, are dropped");

        Check(LockInfo.Parse(Json("""{"screenOn":true,"locked":true,"message":"   "}""")) == new LockInfo(true, true), "A blank message is none");
        Check(LockInfo.Parse(Json("""{"screenOn":true,"locked":true,"message":7}""")) == new LockInfo(true, true), "Nor is one that isn't words");
        var longOne = LockInfo.Parse(Json($$"""{"screenOn":true,"locked":true,"message":"{{new string('x', 5000)}}"}"""));
        Check(longOne!.Message!.Length == LockInfo.MessageMax, "A message is a line, however much a phone sends");
        Console.WriteLine("PASS: where the pattern, PIN or password is entered is read as the phone builds it, or not at all");
    }

    private static void CheckUnlockCaptions()
    {
        var pattern = LockInfo.Parse(Json(SharedPattern))!;
        var pin = LockInfo.Parse(Json(PinPad()))!;
        var password = LockInfo.Parse(Json(SharedPassword))!;

        // The grid and the keys are drawn, so the words about a hidden screen go.
        foreach (bool black in new[] { false, true })
        {
            Check(LockCaption.For(pattern, black) == null, "No caption over a drawn pattern grid");
            Check(LockCaption.For(pin, black) == null, "Nor over drawn PIN keys");
            Check(LockCaption.For(password, black) == "Type the phone's password and press Enter.", "A password has nothing to draw: it is said");
        }
        var wrong = password with { Message = "Wrong password" };
        Check(LockCaption.For(wrong, true) == "Wrong password\nType the phone's password and press Enter.", "With the phone's own message first");

        Check(LockOverlay.Heading(pattern) == "For your security, draw your pattern.", "The phone's message heads the grid");
        Check(LockOverlay.Heading(pattern with { Message = null }) == "Draw the phone's pattern.", "Or plain words when it sends none");
        Check(LockOverlay.Heading(pin) == "Enter PIN", "The phone's message heads the keys");
        Check(LockOverlay.Heading(pin with { Message = null }) == "Enter the phone's PIN.", "Or plain words");
        Check(LockOverlay.Heading(password) == null && LockOverlay.Heading(new LockInfo(true, true)) == null && LockOverlay.Heading(null) == null,
            "Nothing is drawn for a password, a lock screen, or a phone that never says");
        Console.WriteLine("PASS: a drawn unlock screen replaces the caption, and a password is asked for in words");
    }

    private static void CheckPatternDots()
    {
        // The S26: a 765 pixel square at (157, 1203) on a 1080 x 2340 screen.
        var area = new FRect(157 / 1080.0, 1203 / 2340.0, 765 / 1080.0, 765 / 2340.0);
        var dots = LockOverlay.Dots(area);
        Check(dots.Length == 9, "Nine dots");
        // In pixels: the first is half a cell in from the corner, and they are a cell (255) apart.
        Near(dots[0].X * 1080, 157 + 127.5, "The first dot's x");
        Near(dots[0].Y * 2340, 1203 + 127.5, "The first dot's y");
        Near(dots[4].X * 1080, 157 + 382.5, "The middle dot is the middle of the square, across");
        Near(dots[4].Y * 2340, 1203 + 382.5, "And down");
        Near(dots[8].X * 1080, 922 - 127.5, "The last dot's x");
        Near(dots[8].Y * 2340, 1968 - 127.5, "The last dot's y");
        Near((dots[1].X - dots[0].X) * 1080, 255, "Dots are a cell apart across");
        Near((dots[3].Y - dots[0].Y) * 2340, 255, "And down");
        Check(dots[2].Y == dots[0].Y && dots[6].X == dots[0].X, "Rows and columns are straight");

        // Which dot a point is on: within 0.3 of a cell (76 pixels here), as on the phone.
        int At(double px, double py) => LockOverlay.DotAt(px / 1080, py / 2340, area);
        for (int i = 0; i < 9; i++) Check(LockOverlay.DotAt(dots[i].X, dots[i].Y, area) == i, "Each dot's centre is on that dot");
        Check(At(157 + 127.5 + 70, 1203 + 127.5) == 0, "70 pixels from a dot is still on it");
        Check(At(157 + 127.5 + 80, 1203 + 127.5) == -1, "80 pixels is not");
        Check(At(157 + 127.5, 1203 + 127.5 + 70) == 0 && At(157 + 127.5, 1203 + 127.5 + 80) == -1, "The same down the screen, though it is taller than wide");
        Check(At(157 + 255, 1203 + 255) == -1, "Between four dots is on none");
        Check(At(10, 10) == -1 && At(540, 2300) == -1, "Nor is anywhere off the grid");
        Console.WriteLine("PASS: the nine dots sit at the centres of an even split of the grid, and a drag takes a dot only when close");
    }

    private static void CheckPinTaps()
    {
        var pad = (PinEntry)LockInfo.Parse(Json(PinPad()))!.Entry!;
        var four = pad.Tap("4");
        // The 4 key: second row, first column; its middle.
        Near(four!.Value.X * 1080, 150 + 110, "A digit is tapped in the middle of its key, across");
        Near(four.Value.Y * 2400, 1160 + 110, "And down");

        var taps = LockOverlay.PinTaps(pad, "4071");
        Check(taps is { Count: 4 }, "Typed digits become one tap each");
        Check(taps[0] == pad.Tap("4") && taps[1] == pad.Tap("0") && taps[2] == pad.Tap("7") && taps[3] == pad.Tap("1"), "In the order typed");
        // What a keyboard can hand over in one go: digits with something else among them.
        var mixed = LockOverlay.PinTaps(pad, "12a 3");
        Check(mixed.Count == 3 && mixed[0] == pad.Tap("1") && mixed[1] == pad.Tap("2") && mixed[2] == pad.Tap("3"),
            "The digits are tapped and the rest is left out");
        Check(LockOverlay.PinTaps(pad, "abc").Count == 0 && LockOverlay.PinTaps(pad, " ").Count == 0 && LockOverlay.PinTaps(pad, "").Count == 0,
            "Nothing but letters taps nothing, and the caller sends none of it as text");
        Check(LockOverlay.PinTaps(pad, "\u0664").Count == 0, "Digits of another script are not keys: the keys say 0 to 9");

        var tap = LockOverlay.PinTapMessage(0.25, 0.5);
        Check(tap.ToJsonString() == """{"type":"tap","x":0.25,"y":0.5,"onlyIfLocked":true}""",
            "A tap on a key is marked for the unlock screen only, so the phone drops it once unlocked");

        Check(pad.Tap(PinEntry.Delete) is { } delete && Math.Abs(delete.X * 1080 - 260) < 0.5, "Backspace is the pad's delete key");
        Check(pad.Tap(PinEntry.Ok) is { } ok && Math.Abs(ok.X * 1080 - 820) < 0.5, "Enter is its OK key");
        var bare = (PinEntry)LockInfo.Parse(Json(PinPad(withDelete: false, withOk: false)))!.Entry!;
        Check(bare.Tap(PinEntry.Delete) == null && bare.Tap(PinEntry.Ok) == null, "A pad without them has nowhere to tap");
        Check(PinEntry.IsLabel("7") && PinEntry.IsLabel("delete") && PinEntry.IsLabel("ok") && !PinEntry.IsLabel("77") && !PinEntry.IsLabel("x"),
            "The keys a pad may have");
        Console.WriteLine("PASS: digits, Backspace and Enter become taps on the phone's own keys");
    }

    // ---------------- type 5 frames ----------------

    private const ulong StreamA = 0xaa, StreamB = 0xab;
    private const string IdA = "00000000000000aa", IdB = "00000000000000ab";

    private static PhoneFrameHeader Header(ulong stream, uint seq, int format = PhoneFrame.Jpeg) =>
        new(stream, seq, Keyframe: true, format, Sideways: false, Repeat: false, Width: 1080, Height: 2340, CaptureMs: 5);

    private static void CheckFrameHeader()
    {
        // The same vectors are in PhoneFrame.kt, checked by PhoneFrameTest: what
        // one app builds, the other reads, in both directions.
        Check(PhoneFrame.SelfTest(), "The header is the bytes the Android app writes and reads");
        // And encrypted on channel 5, the same blob comes out of all three implementations.
        Check(Crypto.SelfTest(), "A phone frame encrypts on channel 5 as it does on the phone");

        var vector = Convert.FromHexString(PhoneFrame.Vector);
        Check(PhoneFrame.TryParse(vector, out var read), "The vector holds a header");
        Check(read.Stream == 0xfedcba9876543210 && read.Seq == 4026531841 && read.Keyframe && read.Format == PhoneFrame.Jpeg &&
            !read.Sideways && !read.Repeat && read.CaptureMs == 4294967280, "Each field is read from its own bytes");
        Check(read.Width == 2340 && read.Height == 1080, "A phone on its side fits: the long side is the width");
        Check(vector.Length == PhoneFrame.HeaderSize + 4, "The picture follows the 21 bytes of header");

        var picture = new byte[] { 1, 2, 3 };
        foreach (var header in new[]
        {
            new PhoneFrameHeader(ulong.MaxValue, uint.MaxValue, true, 3, true, true, 65535, 65535, uint.MaxValue),
            new PhoneFrameHeader(0, 0, false, PhoneFrame.Jpeg, false, false, 0, 0, 0),
            new PhoneFrameHeader(0x8000000000000000, 0x80000000, false, PhoneFrame.H264, true, false, 2400, 1080, 0x80000000),
        })
        {
            var frame = PhoneFrame.Build(header, picture);
            Check(PhoneFrame.TryParse(frame, out var back) && back == header, "Every field survives the trip at its largest and smallest");
            Check(frame.AsSpan(PhoneFrame.HeaderSize).SequenceEqual(picture), "The picture is behind the header, untouched");
        }
        Check(!PhoneFrame.TryParse(new byte[PhoneFrame.HeaderSize - 1], out _), "Too short to hold a header is no frame");

        Check(PhoneFrame.StreamHex(1) == "0000000000000001" && PhoneFrame.StreamHex(ulong.MaxValue) == "ffffffffffffffff",
            "A stream's id is written as 16 hex digits");
        Check(PhoneFrame.ParseStream("ffffffffffffffff") == ulong.MaxValue && PhoneFrame.ParseStream("FFFFFFFFFFFFFFFF") == ulong.MaxValue &&
            PhoneFrame.ParseStream("0123456789abcdef") == 0x0123456789abcdef, "And read back, in either case");
        foreach (var bad in new[] { null, "", "123", "0123456789abcdef0", "0123456789abcdeg", "+123456789abcdef", "-123456789abcdef", " 123456789abcdef" })
            Check(PhoneFrame.ParseStream(bad) == null, $"\"{bad}\" is not a stream id");
        Console.WriteLine("PASS: a phone frame's header is read and written as the Android app does");
    }

    private static void CheckAnnouncedStream()
    {
        var stream = new PhoneVideoStream();
        Check(stream.DrawsType1, "A phone on an older app announces nothing, and its type 1 frames are drawn");
        Check(stream.Take(Header(StreamA, 0), 100, 0) == FrameVerdict.Refuse, "A type 5 frame nobody announced is not drawn");

        stream.Announce(StreamA, paused: false);
        Check(stream.Current == StreamA && !stream.DrawsType1, "Type 1 is refused while a stream is announced");
        Check(stream.Take(Header(StreamA, 0), 100, 0) == FrameVerdict.Draw, "A frame that follows its announcement is drawn");
        Check(stream.Take(Header(StreamB, 1), 100, 0) == FrameVerdict.Refuse, "A frame with another id is not");
        Check(stream.Take(Header(StreamA, 1), 100, 0) == FrameVerdict.Draw, "The next frame of the stream is drawn");
        Check(stream.Take(Header(StreamA, 2, PhoneFrame.H264), 100, 0) == FrameVerdict.Skip,
            "H.264 was not offered: it arrived, and is not drawn");
        Check(stream.Gaps == 0, "Nothing was lost");

        // Another viewer needs type 1, so everyone gets it.
        stream.Announce(null, paused: false);
        Check(stream.DrawsType1 && stream.Take(Header(StreamA, 3), 100, 0) == FrameVerdict.Refuse,
            "A stream that ends lets type 1 back in, and its id is dead");

        // Nothing is being sent, e.g. while a device waits to be approved.
        stream.Announce(StreamA, paused: false);
        stream.Announce(null, paused: true);
        Check(!stream.DrawsType1 && stream.Current == null && stream.Take(Header(StreamA, 4), 100, 0) == FrameVerdict.Refuse,
            "While the phone says it is sending nothing, nothing that arrives is its own");
        stream.Announce(StreamA, paused: true);
        Check(stream.Take(Header(StreamA, 5), 100, 0) == FrameVerdict.Draw, "A message that names a stream means the stream");

        stream.Forget();
        Check(stream.DrawsType1 && stream.Current == null, "A viewer that stops forgets what was announced");

        // What a screen-info says, however it is written.
        Check(PhoneVideoStream.Read(Json($$"""{"type":"screen-info","stream":"{{IdA}}","codec":"jpeg"}""")) == (StreamA, false), "A stream");
        Check(PhoneVideoStream.Read(Json("""{"type":"screen-info","width":1080,"height":2340}""")) == (null, false), "None, as an older phone sends");
        Check(PhoneVideoStream.Read(Json("""{"type":"screen-info","paused":true}""")) == (null, true), "Paused");
        Check(PhoneVideoStream.Read(Json("""{"type":"screen-info","stream":12,"paused":"yes"}""")) == (null, false), "Nonsense is no stream");
        Check(PhoneVideoStream.Read(Json("""{"type":"screen-info","stream":"abc"}""")) == (null, false), "Nor is an id of the wrong length");
        Console.WriteLine("PASS: only frames of the stream the phone announced are drawn, and type 1 only while there is none");
    }

    private static void CheckSequenceNumbers()
    {
        var stream = new PhoneVideoStream();
        stream.Announce(StreamA, paused: false);
        Check(stream.Take(Header(StreamA, 40), 100, 0) == FrameVerdict.Draw && stream.Gaps == 0,
            "A viewer that joins a stream part-way starts counting where it came in");
        Check(stream.Take(Header(StreamA, 43), 100, 10) == FrameVerdict.Draw && stream.Gaps == 2, "Two missing numbers are two frames lost");
        Check(stream.Take(Header(StreamA, 43), 100, 20) == FrameVerdict.Refuse && stream.Take(Header(StreamA, 41), 100, 20) == FrameVerdict.Refuse,
            "A number seen before is a frame played back, and is not drawn");
        Check(stream.Gaps == 2, "Nor counted");

        stream.Announce(StreamA, paused: false);
        Check(stream.Take(Header(StreamA, 43), 100, 30) == FrameVerdict.Refuse && stream.Take(Header(StreamA, 44), 100, 30) == FrameVerdict.Draw,
            "The same stream announced again, as with a new size, goes on counting");
        stream.Announce(StreamB, paused: false);
        Check(stream.Take(Header(StreamA, 45), 100, 40) == FrameVerdict.Refuse && stream.Take(Header(StreamB, 0), 100, 40) == FrameVerdict.Draw,
            "A new stream starts from nothing");

        // Pictures and bytes in the last second.
        var rate = new PhoneVideoStream();
        rate.Announce(StreamA, paused: false);
        rate.Take(Header(StreamA, 0), 1000, 0);
        rate.Take(Header(StreamA, 1), 2000, 400);
        rate.Count(500, 800);
        Check(rate.LastSecond(999) == (3, 3500), "Three pictures in the last second, type 1 counted with the rest");
        Check(rate.LastSecond(1000) == (2, 2500) && rate.LastSecond(1799) == (1, 500) && rate.LastSecond(1800) == (0, 0),
            "Each drops out a second after it arrived");
        Console.WriteLine("PASS: a gap in the frame numbers is counted, and a repeated number refused");
    }

    private static void CheckAcknowledgements()
    {
        var acks = new AckPacer();
        Check(acks.Received(StreamA, 0, 1000) == new FrameAck(StreamA, 0) && acks.DueIn(1000) == null, "The first frame is acknowledged as it arrives");
        Check(acks.Received(StreamA, 1, 1010) == null && acks.Received(StreamA, 2, 1020) == null, "Two more inside 30 ms are not, yet");
        Check(acks.DueIn(1020) == 10 && acks.Due(1029) == null, "The higher one is owed, 30 ms after the last that went");
        Check(acks.Due(1030) == new FrameAck(StreamA, 2), "And goes then, so the last frame is never left unsaid");
        Check(acks.Due(1100) == null && acks.DueIn(1100) == null, "Once");
        Check(acks.Received(StreamA, 3, 1059) == null && acks.Due(1060) == new FrameAck(StreamA, 3), "The 30 ms run from the one that went");
        Check(acks.Received(StreamA, 4, 1090) == new FrameAck(StreamA, 4), "Frames far enough apart are each acknowledged at once");
        acks.Received(StreamA, 5, 1091);
        acks.Forget();
        Check(acks.Due(5000) == null && acks.Received(StreamB, 0, 5000) == new FrameAck(StreamB, 0), "Forgotten with the stream");

        Check(new FrameAck(0xfedcba9876543210, 4026531841).Message("7").ToJsonString() ==
            """{"type":"frame-ack","to":"7","stream":"fedcba9876543210","seq":4026531841}""", "frame-ack names the stream and the highest frame");
        Console.WriteLine("PASS: frames are acknowledged at most every 30 ms, and the last one always is");
    }

    /// <summary>A feed with no thread of its own, a clock the test moves, and
    /// lists of what it drew and sent.</summary>
    private sealed class FeedRig
    {
        public long Now;
        public readonly List<PhonePicture> Drawn = [];
        public readonly List<string> Sent = [];
        public readonly PhoneFeed Feed;

        public FeedRig() => Feed = new PhoneFeed(Drawn.Add, msg => Sent.Add(msg.ToJsonString()), () => Now, ownThread: false);

        public void Info(string from, string? stream = null, bool paused = false)
        {
            var msg = new JsonObject { ["type"] = "screen-info", ["from"] = from, ["width"] = 1080, ["height"] = 2340, ["typing"] = true };
            if (stream != null) { msg["stream"] = stream; msg["codec"] = "jpeg"; msg["capture"] = "accessibility"; }
            if (paused) msg["paused"] = true;
            Feed.OnScreenInfo(msg);
        }

        public void Type5(ulong stream, uint seq, params byte[] picture) =>
            Feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(Header(stream, seq), picture)]);

        public void Type1(params byte[] picture) => Feed.OnFrame([Crypto.ChVideo, .. picture]);

        /// <summary>What the feed's thread would have drawn by now, as the picture bytes of each.</summary>
        public List<byte[]> Pump()
        {
            Drawn.Clear();
            while (Feed.Pump()) { }
            return Drawn.Select(p => p.Data.AsSpan(p.Offset, p.Length).ToArray()).ToList();
        }
    }

    private static bool Drew(List<byte[]> drawn, params byte[] picture) => drawn.Count == 1 && drawn[0].SequenceEqual(picture);

    private static void CheckFeedDrawsAnnouncedFrames()
    {
        var rig = new FeedRig();
        // No window is open: whatever arrives is nobody's.
        rig.Info("7", IdA);
        rig.Type5(StreamA, 0, 1);
        rig.Type1(2);
        Check(rig.Pump().Count == 0 && rig.Sent.Count == 0, "With no phone being viewed nothing is drawn or acknowledged");

        rig.Feed.View("7", offered: true);
        rig.Type1(3);
        Check(Drew(rig.Pump(), 3), "Before any announcement a type 1 frame is drawn, as from a phone on an older app");

        // The announcement, and straight behind it the stream's first frame.
        rig.Now = 1000;
        rig.Info("7", IdA);
        rig.Type5(StreamA, 0, 4, 5);
        Check(rig.Sent.SequenceEqual(["""{"type":"frame-ack","to":"7","stream":"00000000000000aa","seq":0}"""]),
            "The frame is acknowledged as it arrives, before anything is decoded");
        Check(Drew(rig.Pump(), 4, 5), "A frame that directly follows its announcement is drawn, without its header");
        Check(rig.Feed.State == (StreamA, false, 0), "The stream is current");

        rig.Now = 1100;
        rig.Type1(6);
        Check(rig.Pump().Count == 0, "Type 1 is refused while an id is current");
        rig.Type5(StreamB, 1, 7);
        Check(rig.Pump().Count == 0 && rig.Sent.Count == 1, "A frame with the wrong id is ignored, and not acknowledged");
        rig.Type5(StreamA, 3, 8);
        Check(Drew(rig.Pump(), 8) && rig.Feed.State.Gaps == 2, "A sequence gap is counted");
        Check(rig.Sent.Count == 2 && rig.Sent[1].Contains("\"seq\":3"), "And the highest number acknowledged");
        rig.Feed.OnFrame([Crypto.ChPhone, 1, 2, 3]);
        rig.Feed.OnFrame([Crypto.ChPhone]);
        rig.Feed.OnFrame([]);
        rig.Feed.OnFrame([Crypto.ChPatch, 1, 2, 3]);
        Check(rig.Pump().Count == 0, "A frame too short for a header, or of another kind, is nothing");

        // Another device's word about a stream is not this phone's.
        rig.Info("8", IdB);
        rig.Type5(StreamB, 0, 9);
        Check(rig.Pump().Count == 0 && rig.Feed.State.Stream == StreamA, "An announcement from a device that is not being viewed is ignored");
        rig.Feed.OnScreenInfo(Json("""{"type":"screen-info","stream":"00000000000000ab"}"""));
        rig.Feed.OnScreenInfo(Json("""{"type":"screen-info","from":7,"stream":"00000000000000ab"}"""));
        Check(rig.Feed.State.Stream == StreamA, "So is one with no sender");

        // Pictures stop for now: an unapproved device joined the session.
        rig.Info("7", paused: true);
        rig.Type1(10);
        rig.Type5(StreamA, 4, 11);
        Check(rig.Pump().Count == 0, "While the phone says it sends nothing, neither kind of frame is drawn");
        rig.Info("7", IdB);
        rig.Type5(StreamA, 5, 12);
        rig.Type5(StreamB, 0, 13);
        Check(Drew(rig.Pump(), 13), "The next stream has a new id, and the old one is dead");

        // An older viewer joined, so the phone sends everyone type 1.
        rig.Info("7");
        rig.Type5(StreamB, 1, 14);
        rig.Type1(15);
        Check(Drew(rig.Pump(), 15), "With no stream announced, type 1 is drawn again");

        rig.Feed.Stop("8");
        rig.Type1(16);
        Check(Drew(rig.Pump(), 16), "Some other window closing changes nothing");
        rig.Info("7", IdA);
        rig.Feed.Stop("7");
        rig.Type5(StreamA, 0, 17);
        rig.Type1(18);
        Check(rig.Pump().Count == 0, "After the window closes nothing is drawn");
        rig.Feed.View("7", offered: true);
        rig.Type1(19);
        Check(Drew(rig.Pump(), 19) && rig.Feed.State.Stream == null, "A window opened again starts from nothing");
        Console.WriteLine("PASS: the feed draws the frames of the stream the viewed phone announced, in the order they arrive");
    }

    private static void CheckFeedBelievesOnlyAnApprovedPhone()
    {
        var rig = new FeedRig();
        // The PC has not approved this phone, so it was offered no codecs.
        rig.Feed.View("7", offered: false);
        rig.Info("7", IdA);
        Check(rig.Feed.State == (null, true, 0), "An announcement from a phone the PC has not approved is ignored");
        rig.Type5(StreamA, 0, 1);
        Check(rig.Pump().Count == 0 && rig.Sent.Count == 0, "Its type 5 frames are not drawn or acknowledged");
        rig.Type1(2);
        Check(Drew(rig.Pump(), 2), "Its type 1 frames are, as they always were");
        rig.Info("7", paused: true);
        rig.Type1(3);
        Check(Drew(rig.Pump(), 3), "Nor can it switch the picture off");

        // Approved while the window is open: the window asks again, offering codecs.
        rig.Feed.View("7", offered: true);
        rig.Info("7", IdA);
        rig.Type5(StreamA, 0, 4);
        Check(Drew(rig.Pump(), 4) && rig.Sent.Count == 1, "Once approved, its stream is taken");

        // And no longer approved.
        rig.Feed.View("7", offered: false);
        Check(rig.Feed.State == (null, true, 0), "What it announced while approved no longer holds");
        rig.Type5(StreamA, 1, 5);
        Check(rig.Pump().Count == 0, "So its stream's frames are refused");

        // The same window asking again changes nothing.
        rig.Feed.View("7", offered: true);
        rig.Info("7", IdA);
        rig.Type5(StreamA, 2, 6);
        rig.Pump();
        rig.Feed.View("7", offered: true);
        Check(rig.Feed.State.Stream == StreamA, "A start-view sent again keeps the stream that is running");
        rig.Feed.View("9", offered: true);
        Check(rig.Feed.State.Stream == null, "A window on another phone starts from nothing");
        Console.WriteLine("PASS: a stream is taken only on the word of a phone this PC has approved");
    }

    private static void CheckFeedKeepsTheNewest()
    {
        var rig = new FeedRig { Now = 1000 };
        rig.Feed.View("7", offered: true);
        rig.Info("7", IdA);
        // Three frames before the feed's thread gets a turn.
        rig.Type5(StreamA, 0, 1);
        rig.Now = 1010;
        rig.Type5(StreamA, 1, 2);
        rig.Now = 1020;
        rig.Type5(StreamA, 2, 3);
        Check(Drew(rig.Pump(), 3), "Only the newest picture is decoded: each JPEG is the whole screen");
        Check(rig.Feed.State.Gaps == 0, "The ones passed over arrived, and are no gap");
        Check(rig.Sent.Count == 1 && rig.Sent[0].Contains("\"seq\":0"), "One acknowledgement has gone");
        rig.Now = 1029;
        Check(!rig.Feed.Pump(), "The next is not due yet");
        rig.Now = 1030;
        Check(rig.Feed.Pump() && rig.Sent.Count == 2 && rig.Sent[1] == """{"type":"frame-ack","to":"7","stream":"00000000000000aa","seq":2}""",
            "30 ms after the first, the highest frame that arrived is acknowledged");
        Check(!rig.Feed.Pump(), "And then there is nothing left to do");

        // A type 1 picture still waiting when a stream is announced is of the time before.
        rig.Info("7");
        rig.Type1(4);
        rig.Info("7", IdB);
        Check(rig.Pump().Count == 0, "A type 1 frame is not drawn after the stream that follows it is announced");

        // A decoder that fails loses one picture, not the feed.
        var failing = new PhoneFeed(_ => throw new InvalidOperationException("not a picture"), _ => { }, () => 0, ownThread: false);
        failing.View("7", offered: false);
        failing.OnFrame([Crypto.ChVideo, 1]);
        Check(failing.Pump(), "A picture that will not decode is passed over");
        Console.WriteLine("PASS: the feed decodes the newest picture only, and acknowledges the last frame even when frames come in a burst");
    }

    private static void CheckFeedOnItsOwnThread()
    {
        // The real thing: a thread of its own, and a real clock.
        var clock = System.Diagnostics.Stopwatch.StartNew();
        var drawn = new List<byte>();
        var sent = new List<string>();
        using var all = new ManualResetEventSlim();
        void Done() { lock (drawn) if (drawn.Contains(3) && sent.Count > 1 && sent[^1].Contains("\"seq\":2")) all.Set(); }
        using var feed = new PhoneFeed(
            p => { lock (drawn) drawn.Add(p.Data[p.Offset]); Done(); },
            msg => { lock (drawn) sent.Add(msg.ToJsonString()); Done(); },
            () => clock.ElapsedMilliseconds);
        feed.View("7", offered: true);
        feed.OnScreenInfo(Json($$"""{"type":"screen-info","from":"7","stream":"{{IdA}}"}"""));
        feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(Header(StreamA, 0), [1])]);
        feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(Header(StreamA, 1), [2])]);
        feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(Header(StreamA, 2), [3])]);
        Check(all.Wait(TimeSpan.FromSeconds(10)), "The feed's thread draws the newest picture and sends the acknowledgement that was owed");
        lock (drawn)
        {
            Check(drawn[^1] == 3, "The last picture drawn is the newest");
            Check(sent[0].Contains("\"seq\":0") && sent[^1].Contains("\"seq\":2"), "The first frame and the last are acknowledged");
        }
        Console.WriteLine("PASS: on its own thread the feed draws without being asked and sends an acknowledgement that came due");
    }

    private static void CheckStartView()
    {
        Check(PhoneFeed.StartView("7", offerCodecs: true).ToJsonString() == """{"type":"start-view","to":"7","codecs":["jpeg"]}""",
            "A phone this PC has approved is offered JPEG in type 5 frames");
        Check(PhoneFeed.StartView("7", offerCodecs: false).ToJsonString() == """{"type":"start-view","to":"7"}""",
            "A phone it has not approved is asked as it always was");
        // That the window's later start-views keep the offer is checked with
        // the window itself, in PrivacyLifecycleTests.
        Console.WriteLine("PASS: a start-view offers JPEG in type 5 frames to an approved phone, and nothing new to any other");
    }

    // ---------------- the Stats readout ----------------

    private static void CheckDelayArithmetic()
    {
        var stats = new PhoneStats();
        Check(stats.Clock(0) == null, "Until the phone has answered a ping, the difference between the clocks is not known");
        Check(stats.PingDueIn(0) == 0, "The first ping is due at once");

        // The phone's clock is 500 000 ms ahead of the PC's, and a ping takes 10 ms each way.
        long first = stats.Ping(1000);
        Check(stats.PingDueIn(1000) == PhoneStats.PingEveryMs && stats.PingDueIn(2999) == 1 && stats.PingDueIn(3000) == 0,
            "The next ping is due two seconds after the last");
        stats.Pong(first, hostTimeMs: 501_010, queueBytes: 0, nowMs: 1020);
        Check(stats.Clock(1020) == (500_000, 10), "The difference is the phone's time less the sending time less half the round trip, give or take that half");

        // A pong that was held up on its way back: a long round trip, the same phone time.
        long late = stats.Ping(3000);
        stats.Pong(late, hostTimeMs: 503_010, queueBytes: 0, nowMs: 3500);
        Check(stats.Clock(3500) == (500_000, 10), "A pong held up on its way back changes nothing");
        // A ping that was held up on its way there: the phone answered 300 ms late.
        long slow = stats.Ping(5000);
        stats.Pong(slow, hostTimeMs: 505_300, queueBytes: 0, nowMs: 5310);
        Check(stats.Clock(5310) == (500_000, 10), "Nor does a ping held up on its way there: the fastest one in the last minute counts");
        // An answer to nothing we asked, and the same answer twice.
        stats.Pong(999, hostTimeMs: 1, queueBytes: 0, nowMs: 5400);
        stats.Pong(slow, hostTimeMs: 1, queueBytes: 0, nowMs: 5400);
        Check(stats.Clock(5400) == (500_000, 10), "A pong that answers no ping of ours is ignored");

        // A picture captured when the phone's clock said 505 850, drawn when the PC's says 6000.
        Check(PhoneStats.Delay(6000, 500_000, 505_850) == 150, "Delay is the PC's clock at drawing, plus the difference, less the capture time");
        // The phone's clock wraps at 32 bits between the capture and the drawing.
        long wrapped = 4_294_967_296 + 100 - 10_000;
        Check(PhoneStats.Delay(10_000, wrapped, 4_294_967_096) == 300, "A clock that wraps round between capture and drawing gives the same answer");
        Check(PhoneStats.Delay(10_000, wrapped + 3 * 4_294_967_296, 4_294_967_096) == 300, "However long the phone has been up");
        Check(PhoneStats.Delay(6000, 500_000, 506_050) == -50, "A capture time ahead of the clock shows as it is, not as 49 days");

        // Over the last second: latest, average and worst.
        var header = new PhoneFrameHeader(StreamA, 0, true, PhoneFrame.Jpeg, false, false, 1080, 2340, 0);
        stats.Arrived(100_000, header with { CaptureMs = 505_500 }, 5600);
        stats.Drawn(header with { CaptureMs = 505_500 }, 5620);      // 120 ms old
        stats.Drawn(header with { CaptureMs = 505_850 }, 6100);      // 250
        stats.Drawn(header with { CaptureMs = 506_250, Repeat = true }, 6400);   // a repeat: left out
        stats.Drawn(header with { CaptureMs = 506_260 }, 6440);      // 180
        var second = stats.Between(5441, 6441, 6440);
        Check(second.DelayLatest == 180 && second.DelayAverage == 183 && second.DelayWorst == 250 && second.DelayMedian == 180,
            "The delays of a second: the latest, their average, the worst, and the middle one; a repeat is left out");
        Check(stats.Between(6441, 7441, 7440).DelayLatest == null, "A second with no picture drawn has no delay");
        Check(stats.Clock(5310 + PhoneStats.KeptMs) == null, "A minute after the last answer, the difference is not known any more");
        Console.WriteLine("PASS: the delay is worked out from the fastest ping of the last minute, and survives the phone's clock wrapping");
    }

    private static void CheckReadoutText()
    {
        // A phone on an older app: type 1 frames, and nothing said but the size.
        var old = new PhoneStats();
        old.Size(1080, 2340);
        old.Arrived(100_000, null, 9_200);
        old.Arrived(100_000, null, 9_600);
        old.Arrived(100_000, null, 10_000);
        Check(old.Text(10_000) == string.Join(Environment.NewLine,
            "Capture not known · JPEG, type 1 · 1080 × 2340",
            "3 pictures/s · 2400 kbit/s",
            "Delay: not known. These pictures don't say when they were taken.",
            "Round trip not known · phone's queue not known",
            "Frames lost: 0"), "An older phone's readout has the rate and the size and says why there is no delay:\n" + old.Text(10_000));
        Check(old.Text(20_000).Contains("0 pictures/s") && old.Text(20_000).Contains("Delay: not known."), "And goes on saying so while its screen is still");
        Check(new PhoneStats().Text(0).Contains("Capture not known · JPEG · size not known") && new PhoneStats().Text(0).Contains("Delay: measuring"),
            "Before anything has arrived, nothing is said about what kind of pictures they are");

        var stats = new PhoneStats();
        stats.Announce("accessibility", "jpeg");
        var header = new PhoneFrameHeader(StreamA, 0, true, PhoneFrame.Jpeg, false, false, 2340, 1080, 0);
        stats.Arrived(51_200, header, 9_500);
        stats.Arrived(51_200, header, 9_900);
        Check(stats.Text(10_000).Contains("Delay: measuring"), "Before the phone has answered a ping the delay is being measured");
        stats.Pong(stats.Ping(9_000), hostTimeMs: 509_010, queueBytes: 3_000, nowMs: 9_020);
        stats.Drawn(header with { CaptureMs = 509_380 }, 9_520);     // 140 ms
        stats.Drawn(header with { CaptureMs = 509_760 }, 9_920);     // 160 ms
        stats.Lost = 2;
        Check(stats.Text(10_000) == string.Join(Environment.NewLine,
            "Accessibility viewing · JPEG · 2340 × 1080",
            "2 pictures/s · 819 kbit/s",
            "Delay 160 ms · average 150 · worst 160 (± 10 ms)",
            "Round trip 20 ms · phone's queue 2.9 KB",
            "Frames lost: 2"), "The readout of a phone that sends type 5:\n" + stats.Text(10_000));
        Check(stats.Text(11_500).Contains("Delay: no new picture in the last second (± 10 ms)") && stats.Text(11_500).Contains("0 pictures/s · 0 kbit/s"),
            "A still screen has no delay to show, and says so");
        stats.Announce("screen-share", "jpeg");
        Check(stats.Text(11_500).StartsWith("Smooth sharing · JPEG"), "Smooth sharing is named as the phone's own screens name it");

        // Shift+F8: the last minute, a line for the whole of it and one for each second.
        var copied = stats.History(10_000).Split(Environment.NewLine);
        Check(copied[0] == "Remote Desktop phone viewer, the last 1 s" && copied[1] == "Smooth sharing · JPEG · 2340 × 1080",
            "The copy says what it is and how the phone was being viewed: " + copied[0]);
        Check(copied[2] == "Pictures/s 2.0 · KB per picture 50 · delay median / worst (ms) 160 / 160 (± 10) · round trip (ms) 20 · phone's queue, worst (KB) 2.9 · frames lost 2",
            "Then the figures the baseline table asks for: " + copied[2]);
        Check(copied[4].StartsWith("s ago\tpictures\tkbit/s") && copied[5] == "1\t2\t819\t150\t160\t20\t2.9",
            "Then each second, with tabs between, so it pastes into a table: " + copied[5]);
        // Half a minute on, with nothing more: thirty-one rows, the old second at the top.
        var longer = stats.History(40_000).Split(Environment.NewLine);
        Check(longer[0] == "Remote Desktop phone viewer, the last 31 s" && longer[5].StartsWith("31\t") && longer[35].StartsWith("1\t0\t0\t-"),
            "The copy covers the time there are figures for, up to a minute: " + longer[0]);
        stats.Reset();
        Check(stats.Text(50_000).StartsWith("Capture not known") && stats.Clock(50_000) == null, "Another phone starts from nothing");
        Console.WriteLine("PASS: the readout says how the phone is viewed, how fast pictures come and how far behind they are");
    }

    private static void CheckFeedKeepsTheFigures()
    {
        var rig = new FeedRig { Now = 10_000 };
        rig.Feed.View("7", offered: true);
        rig.Info("7", IdA);
        rig.Pump();
        Check(rig.Sent.Count == 0, "With the readout closed the phone is not pinged");

        rig.Feed.ShowStats(true);
        rig.Pump();
        Check(rig.Sent.SequenceEqual(["""{"type":"diagnostic-ping","to":"7","nonce":1}"""]), "Opening it pings the phone at once");
        rig.Now = 11_999;
        rig.Pump();
        Check(rig.Sent.Count == 1, "Not again within two seconds");
        // The answer is timed as it arrives.
        rig.Now = 10_020;
        rig.Feed.OnPong(Json("""{"type":"diagnostic-pong","from":"8","nonce":1,"hostTimeMs":1}"""));
        rig.Feed.OnPong(Json("""{"type":"diagnostic-pong","nonce":1,"hostTimeMs":1}"""));
        rig.Feed.OnPong(Json("""{"type":"diagnostic-pong","from":"7","nonce":"one","hostTimeMs":1}"""));
        Check(rig.Feed.StatsText().Contains("Round trip not known"), "Only the viewed phone's answer counts, and only one that makes sense");
        rig.Feed.OnPong(Json("""{"type":"diagnostic-pong","from":"7","nonce":1,"hostTimeMs":510010,"hostQueueBytes":0,"codec":"jpeg","capture":"accessibility","fps":2}"""));
        Check(rig.Feed.StatsText().Contains("Round trip 20 ms · phone's queue 0.0 KB"), "The phone's answer gives the round trip and its queue");
        rig.Now = 12_000;
        rig.Pump();
        Check(rig.Sent.Count == 2 && rig.Sent[1].Contains("\"nonce\":2"), "Two seconds on, the next ping goes");

        // A picture arrives, is decoded, and the window says when it drew it.
        var header = Header(StreamA, 0) with { CaptureMs = 511_900 };
        rig.Feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(header, new byte[2000])]);
        rig.Pump();
        rig.Now = 12_100;
        rig.Feed.Drawn(rig.Drawn[^1].Header);
        var text = rig.Feed.StatsText();
        Check(text.StartsWith("Accessibility viewing · JPEG · 1080 × 2340") && text.Contains("1 picture/s · 16 kbit/s") &&
            text.Contains("Delay 200 ms · average 200 · worst 200 (± 10 ms)"), "The feed's figures are what the readout shows:\n" + text);
        rig.Feed.Drawn(Header(StreamB, 0) with { CaptureMs = 1 });
        rig.Feed.Drawn(null);
        Check(rig.Feed.StatsText().Contains("worst 200"), "A picture of some other stream, or one with no header, adds no delay");
        rig.Feed.OnFrame([Crypto.ChPhone, .. PhoneFrame.Build(Header(StreamA, 3), [1])]);
        Check(rig.Feed.StatsText().EndsWith("Frames lost: 2") && rig.Feed.StatsHistory().Contains("frames lost 2"), "Lost frames are counted in it");

        rig.Feed.ShowStats(false);
        rig.Now = 20_000;
        rig.Pump();
        Check(rig.Sent.Count(m => m.Contains("diagnostic-ping")) == 2, "Closing the readout stops the pings");
        // A window that closes with its readout open takes the readout with it.
        rig.Feed.ShowStats(true);
        rig.Pump();
        rig.Feed.Stop("7");
        rig.Feed.View("7", offered: true);
        rig.Now = 30_000;
        rig.Pump();
        Check(rig.Sent.Count(m => m.Contains("diagnostic-ping")) == 3, "The next window on the phone does not ping it until its own readout is opened");
        rig.Feed.Stop("7");
        rig.Feed.View("9", offered: true);
        Check(rig.Feed.StatsText().StartsWith("Capture not known") && rig.Feed.StatsText().Contains("Delay: measuring"),
            "A window on another phone starts its figures from nothing");
        Console.WriteLine("PASS: the feed pings the phone while the readout is open and keeps the figures it shows");
    }

    private static JsonObject Json(string text) => JsonNode.Parse(text)!.AsObject();

    private static void Near(double actual, double expected, string message)
    {
        if (Math.Abs(actual - expected) > 0.5) throw new Exception($"{message}: expected about {expected:0.##}, got {actual:0.##}");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
