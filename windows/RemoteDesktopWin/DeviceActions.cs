namespace RemoteDesktopWin;

/// <summary>
/// What the row of a device that is online may offer.
///
/// View and Files are for a device this PC has approved, and for no other.
/// What is typed and clicked in a viewer, and every file that is sent, goes
/// to the device the row names; until its key has been checked and approved
/// here, the name is only what that device says it is called. A viewer on
/// such a device would also draw whatever pictures arrived, and the bare
/// pictures an older phone sends name no sender.
///
/// So until then the two buttons are greyed, and say why. The row is where
/// the device is approved, so they stay in sight.
///
/// No WPF here: RemoteDesktopWin.PhoneViewTests compiles this file in.
/// </summary>
internal static class DeviceActions
{
    /// <summary>How a device that is online stands with this PC.</summary>
    public enum Standing
    {
        /// <summary>Its key is one this PC has approved.</summary>
        Approved,
        /// <summary>It has proved its key, and the person here has not answered yet.</summary>
        Pending,
        /// <summary>The person here said no to it, which holds until the app is closed.</summary>
        Refused,
        /// <summary>Its approval was taken away while it was online. It asks again when it next connects.</summary>
        Revoked,
        /// <summary>It has not proved a key: it has only just joined, or its app is too old to.</summary>
        Unverified,
    }

    public const string ViewTip = "View and control this device";
    public const string FilesTip = "Browse this device's files";

    /// <param name="verified">It has proved which key it holds.</param>
    /// <param name="refused">Its key is one that was refused since the app started.</param>
    public static Standing Of(bool trusted, bool pending, bool verified, bool refused) =>
        trusted ? Standing.Approved :
        !verified ? Standing.Unverified :
        pending ? Standing.Pending :
        refused ? Standing.Refused :
        Standing.Revoked;

    /// <summary>Whether View and Files may be used.</summary>
    public static bool CanOpen(Standing standing) => standing == Standing.Approved;

    /// <summary>The tooltip of the View button.</summary>
    public static string TipForView(Standing standing) => WhyNot(standing, "viewed") ?? ViewTip;

    /// <summary>The tooltip of the Files button.</summary>
    public static string TipForFiles(Standing standing) => WhyNot(standing, "browsed") ?? FilesTip;

    /// <summary>Why the device cannot be opened, or null if it can. Also said
    /// when something asks to open it all the same.</summary>
    /// <param name="done">What would be done to it: "viewed", "browsed".</param>
    public static string? WhyNot(Standing standing, string done) => standing switch
    {
        Standing.Approved => null,
        Standing.Pending =>
            $"This device can't be {done} until you approve it. Check that it shows the same code, then choose Approve.",
        Standing.Refused =>
            $"You refused this device, so it can't be {done}. To approve it after all, close Remote Desktop " +
            "from its tray icon and start it again: it asks once more.",
        Standing.Revoked =>
            $"This device is no longer approved, so it can't be {done}. It asks to be approved the next time it connects.",
        _ =>
            $"This device has not shown which device it is yet, so it can't be {done}. " +
            "If this stays, update Remote Desktop on it.",
    };
}
