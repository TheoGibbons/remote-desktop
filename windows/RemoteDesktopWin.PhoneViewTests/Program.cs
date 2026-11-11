using System.Text.Json.Nodes;
using RemoteDesktopWin;

// The decisions behind the phone viewer: when its "⋯" menu is open, how the
// window is sized round the phone's picture, how a dragged slider is paced and
// what a phone says about itself (PhoneMenu.cs), and what is said over the
// picture of a phone that is asleep or locked (LockOverlay.cs). Neither file
// has any WPF in it, so none of this needs a window.
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
        CheckRotation();
        CheckLockState();
        CheckCaptions();
        CheckBlackPicture();
        CheckBlackWatch();
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
            {"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1,0.5,0.7,0.3]},"message":"Draw your pattern"}
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
