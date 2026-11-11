using System.Text.Json.Nodes;
using RemoteDesktopWin;

// The decisions behind the phone viewer: when its "⋯" menu is open, how the
// window is sized round the phone's picture, how a dragged slider is paced and
// what a phone says about itself. All of it lives in PhoneMenu.cs, which has
// no WPF in it, so none of this needs a window.
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
        CheckFirstFit();
        CheckTurning();
        CheckSmallWorkArea();
        CheckStaysOnScreen();
        CheckThrottle();
        CheckPhoneState();
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

    // ---------------- what the phone says ----------------

    private static void CheckPhoneState()
    {
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
