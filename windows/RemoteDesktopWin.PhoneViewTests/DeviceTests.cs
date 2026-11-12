using RemoteDesktopWin;

// What a device's row may offer (DeviceActions.cs), and how a window that is
// open on a device follows it through reconnections (DeviceLink.cs). Like the
// files Program.cs tests, there is no WPF in them.
internal static class DeviceTests
{
    private static readonly DateTime T0 = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private static DateTime At(double seconds) => T0.AddSeconds(seconds);

    internal static void Run()
    {
        CheckRowActions();
        CheckLinkWhenThisPcReconnects();
        CheckLinkWhenTheDeviceReconnects();
        CheckLinkNeverTakesAnotherDevice();
        CheckLinkGivesUp();
        CheckLinkWhenTheDeviceDoesNotAnswer();
    }

    private const string Key = "the phone's key", OtherKey = "another key";

    /// <summary>The relay restarts: this PC drops, and every device is back under a new id.</summary>
    private static void CheckLinkWhenThisPcReconnects()
    {
        var link = new DeviceLink("2", Key);
        Check(link is { PeerId: "2", Away: false, Offline: false, GiveUpAt: null } && link.Notice("Phone") == null,
            "a window starts attached to its device, with nothing to say");

        Check(link.ConnectionLost(), "losing the connection is news");
        Check(link is { PeerId: null, Away: true, Offline: true, GiveUpAt: null }, "the id is dead with the connection");
        Check(link.Notice("Phone") == "This PC has lost its connection. Reconnecting…", "the window says this PC is reconnecting");
        Check(!link.ConnectionLost(), "every further failed try is not news");
        // However long this PC is off the relay, the device is not given up: nothing can be seen from there.
        Check(!link.GaveUp(At(100_000)), "the wait does not run while this PC is off the relay");
        Check(!link.Returned("5", Key, At(30)), "nothing is attached while this PC is off the relay");

        Check(link.Connected(At(10)), "being back on the relay is news");
        Check(!link.Connected(At(11)), "once");
        Check(link is { Away: true, Offline: false } && link.GiveUpAt == At(10) + DeviceLink.Wait, "the device is waited for from then");
        Check(link.Notice("Phone") == "Phone has lost its connection. Waiting for it to come back…", "the window says it is waiting for the phone");

        // The phone is back under another id, and has proved the same key.
        Check(link.Returned("5", Key, At(30)), "the same key under a new id is the device come back");
        Check(link is { PeerId: "5", Away: false, Resuming: true, Live: false } && link.GiveUpAt == At(30) + DeviceLink.Wait,
            "and the window is attached to the new id, and waits for the device to answer");
        Check(link.Notice("Phone") == "Phone is back. Asking for its screen again…", "the window says it is asking again");
        Check(!link.Returned("6", Key, At(30)), "an attached window is not moved to another id");
        Check(link.Answered() && link is { Live: true, Resuming: false, GiveUpAt: null } && link.Notice("Phone") == null,
            "once the device answers, the picture is live and there is nothing to say");
        Check(!link.Answered(), "its answering again is not news");
        Check(!link.GaveUp(At(100_000)), "nothing is given up once it is back");
        Console.WriteLine("PASS: a window whose PC reconnects waits, and is attached to the same device under its new id");
    }

    /// <summary>Only the device's connection drops: it leaves, and joins again a moment later.</summary>
    private static void CheckLinkWhenTheDeviceReconnects()
    {
        var link = new DeviceLink("2", Key);
        Check(!link.Left("3", At(0)) && !link.Away, "another device leaving is nothing to this window");
        Check(link.Left("2", At(5)), "its own device leaving is");
        Check(link is { Away: true, Offline: false } && link.GiveUpAt == At(5) + DeviceLink.Wait, "it is waited for from the moment it left");
        Check(link.Notice("Phone")!.StartsWith("Phone has lost its connection"), "the window says the phone is away");
        Check(!link.Left("2", At(6)), "a device that is away cannot leave again, whoever has its old id");
        Check(link.Returned("9", Key, At(30)) && link.PeerId == "9", "it is back under a new id");

        // And this PC drops while it is waiting for the device: the wait starts over when the PC is back.
        link.Left("9", At(40));
        link.ConnectionLost();
        Check(link.GiveUpAt == null && !link.GaveUp(At(1000)), "the wait stops while this PC is off the relay");
        link.Connected(At(500));
        Check(link.GiveUpAt == At(500) + DeviceLink.Wait, "and starts again when it is back");
        Console.WriteLine("PASS: a window whose device reconnects waits for it and carries on under its new id");
    }

    /// <summary>Ids are handed out again, and names are whatever a device says.</summary>
    private static void CheckLinkNeverTakesAnotherDevice()
    {
        var link = new DeviceLink("2", Key);
        link.ConnectionLost();
        link.Connected(At(0));
        // Another device now has the id the phone had.
        Check(!link.Returned("2", OtherKey, At(30)) && link.Away, "a device with another key is not the device, though it has the old id");
        Check(!link.Returned("2", null, At(30)) && link.Away, "nor is one that has proved no key");
        Check(link.Returned("7", Key, At(30)), "the key decides");

        // A window on a device that never proved a key cannot be attached to anything again.
        var unknown = new DeviceLink("2", null);
        unknown.Left("2", At(0));
        Check(!unknown.Returned("2", null, At(30)) && !unknown.Returned("3", Key, At(30)) && unknown.Away, "a window with no key to go by is attached to nothing");
        Check(unknown.GaveUp(At(0) + DeviceLink.Wait), "and closes when the wait is over");
        Console.WriteLine("PASS: a window is only ever attached to a device that proves the key it was opened on");
    }

    /// <summary>
    /// The device is back in the session, but while it was away it stopped
    /// approving this PC, or had control switched off: it ignores what it is
    /// asked and says nothing. The window must not go on as if it were viewed.
    /// </summary>
    private static void CheckLinkWhenTheDeviceDoesNotAnswer()
    {
        var link = new DeviceLink("2", Key);
        Check(link is { Live: true, Resuming: false }, "a window that has just been opened is live");
        link.Left("2", At(0));
        Check(!link.Live, "nothing is live while the device is away");
        link.Returned("8", Key, At(10));
        Check(link is { PeerId: "8", Resuming: true, Live: false }, "back, it has still to answer");
        Check(!link.GaveUp(At(10 + DeviceLink.Wait.TotalSeconds - 1)), "it is given as long to answer as it was to come back");
        Check(link.GaveUp(At(10 + DeviceLink.Wait.TotalSeconds)), "and then the window stops waiting");
        Check(link.GaveUpText("Phone").StartsWith("Phone is back online but did not send its screen, so its window was closed."),
            "and says the device is there and sent nothing: " + link.GaveUpText("Phone"));

        // It says it is asking its user to approve this PC: a person takes a while, so the wait starts again.
        link.StillAsking("7", At(40));
        Check(link.GiveUpAt == At(10) + DeviceLink.Wait, "another device asking its user changes nothing");
        link.StillAsking("8", At(40));
        Check(link.GiveUpAt == At(40) + DeviceLink.Wait, "its own asking gives it the wait again");

        // It leaves again before answering, and the PC drops: nothing is left of the asking.
        Check(link.Left("8", At(50)) && link is { Away: true, Resuming: false }, "it can leave again while it is being asked");
        Check(link.GaveUpText("Phone") == DeviceLink.GoneText("Phone"), "and then it is a device that did not come back");
        link.Returned("9", Key, At(60));
        link.ConnectionLost();
        Check(link is { Away: true, Resuming: false, Offline: true }, "a connection lost while asking leaves it away");

        // A window that waits for good is live as soon as its device is back: there is no picture to mislead.
        var files = new DeviceLink("2", Key, wait: null);
        files.Left("2", At(0));
        files.Returned("4", Key, At(5));
        Check(files is { Live: true, Resuming: false, GiveUpAt: null }, "a file window carries on at once");
        Console.WriteLine("PASS: a device that comes back is not taken for viewed again until it answers, and is given up if it never does");
    }

    private static void CheckLinkGivesUp()
    {
        var link = new DeviceLink("2", Key);
        link.Left("2", At(0));
        Check(!link.GaveUp(At(DeviceLink.Wait.TotalSeconds - 1)), "the device is waited for");
        Check(link.GaveUp(At(DeviceLink.Wait.TotalSeconds)), "for a minute, and then no longer");
        Check(DeviceLink.GoneText("Phone") == "Phone did not come back online, so its window was closed.", "what is said then");

        // A window that waits for good: a list of files shows nothing untrue however long the device is gone.
        var files = new DeviceLink("2", Key, wait: null);
        files.Left("2", At(0));
        Check(files is { Away: true, GiveUpAt: null } && !files.GaveUp(At(1_000_000)), "a window that waits for good never gives up");
        Check(files.Returned("4", Key, At(30)), "and is attached when the device does come back");
        Console.WriteLine("PASS: a viewer stops waiting for a device that stays away a minute, and a file window never does");
    }

    private static void CheckRowActions()
    {
        var approved = DeviceActions.Of(trusted: true, pending: false, verified: true, refused: false);
        Check(approved == DeviceActions.Standing.Approved && DeviceActions.CanOpen(approved), "an approved device can be opened");
        Check(DeviceActions.TipForView(approved) == DeviceActions.ViewTip && DeviceActions.TipForFiles(approved) == DeviceActions.FilesTip,
            "an approved device's buttons say what they do");
        Check(DeviceActions.WhyNot(approved, "viewed") == null, "nothing stands in the way of an approved device");

        // Every other standing: neither button works, and each says why in its own words.
        var pending = DeviceActions.Of(trusted: false, pending: true, verified: true, refused: false);
        var refused = DeviceActions.Of(trusted: false, pending: false, verified: true, refused: true);
        var revoked = DeviceActions.Of(trusted: false, pending: false, verified: true, refused: false);
        var unverified = DeviceActions.Of(trusted: false, pending: false, verified: false, refused: false);
        Check(pending == DeviceActions.Standing.Pending && refused == DeviceActions.Standing.Refused &&
              revoked == DeviceActions.Standing.Revoked && unverified == DeviceActions.Standing.Unverified,
            "a device that is not approved is waiting, refused, revoked or unverified");
        var reasons = new HashSet<string>();
        foreach (var standing in new[] { pending, refused, revoked, unverified })
        {
            Check(!DeviceActions.CanOpen(standing), $"a device that is {standing} cannot be opened");
            string view = DeviceActions.TipForView(standing), files = DeviceActions.TipForFiles(standing);
            Check(view != DeviceActions.ViewTip && view.Contains("can't be viewed"), $"View says why not for a device that is {standing}");
            Check(files != DeviceActions.FilesTip && files.Contains("can't be browsed"), $"Files says why not for a device that is {standing}");
            Check(DeviceActions.WhyNot(standing, "viewed") == view, "what is said on a click is what the tooltip says");
            reasons.Add(view);
        }
        Check(reasons.Count == 4, "each standing has its own reason");
        Check(DeviceActions.TipForView(pending).Contains("Approve"), "a waiting device's buttons say to approve it");
        Check(DeviceActions.TipForView(refused).Contains("refused"), "a refused device's buttons say it was refused");

        // A device that has not proved its key is unverified whatever else is claimed for it.
        Check(DeviceActions.Of(trusted: false, pending: true, verified: false, refused: true) == DeviceActions.Standing.Unverified,
            "nothing is believed of a device that has not proved its key");
        Console.WriteLine("PASS: View and Files are only for a device this PC has approved, and say why otherwise");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new Exception(message);
    }
}
