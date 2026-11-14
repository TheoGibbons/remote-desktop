namespace RemoteDesktopWin;

/// <summary>
/// The order every list of devices is shown in: PCs above phones, and within
/// each kind, devices that are online above those that are not, then by name.
/// The phone app lists its devices the same way (DeviceOrder.kt).
///
/// No WPF here: RemoteDesktopWin.PhoneViewTests compiles this file in.
/// </summary>
internal static class DeviceOrder
{
    /// <summary>PCs first, then phones, then any kind this app doesn't know.</summary>
    public static int KindRank(string? device) => device switch
    {
        "windows" => 0,
        "android" => 1,
        _ => 2,
    };

    /// <param name="device">The kind a device says it is: "windows" or "android".</param>
    /// <param name="online">Whether it is in the session now.</param>
    /// <param name="key">What tells two devices of one name apart, so that
    /// they keep their places whichever of them joined first.</param>
    public static List<T> Sort<T>(IEnumerable<T> items, Func<T, string?> device, Func<T, bool> online,
        Func<T, string> name, Func<T, string?> key) =>
        items.OrderBy(i => KindRank(device(i)))
            .ThenBy(i => online(i) ? 0 : 1)
            .ThenBy(name, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(i => key(i) ?? "", StringComparer.Ordinal)
            .ToList();
}
