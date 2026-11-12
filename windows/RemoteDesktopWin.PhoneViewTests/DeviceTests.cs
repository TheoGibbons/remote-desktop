using RemoteDesktopWin;

// What a device's row may offer (DeviceActions.cs). Like the files Program.cs
// tests, there is no WPF in it.
internal static class DeviceTests
{
    internal static void Run()
    {
        CheckRowActions();
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
