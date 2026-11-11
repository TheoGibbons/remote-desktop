namespace RemoteDesktopWin;

/// <summary>
/// Lets a developer run a second copy of the app beside the installed one.
/// A debug build started with REMOTEDESKTOP_PROFILE=name keeps its settings,
/// identity and trusted devices in a folder of its own and holds its own
/// instance lock, so it joins a session as a separate PC. Such a copy also
/// leaves Windows alone: it never registers to start with Windows, updates
/// itself or restarts as administrator (see MainWindow). Release builds
/// ignore the variable.
/// </summary>
internal static class AppProfile
{
    internal const string Variable = "REMOTEDESKTOP_PROFILE";

    /// <summary>The profile this copy runs as, or null for the ordinary app.</summary>
    internal static string? Name { get; } =
#if DEBUG
        Parse(Environment.GetEnvironmentVariable(Variable));
#else
        null;
#endif

    /// <summary>
    /// The profile a value of the variable names, or null when it is unset.
    /// A name that can't be used throws: carrying on as the ordinary app
    /// would hand a development copy the installed app's identity.
    /// </summary>
    internal static string? Parse(string? value)
    {
        if (string.IsNullOrEmpty(value)) return null;
        // It becomes part of a folder name and of a lock name.
        if (value.Length > 32 || !value.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_'))
            throw new ArgumentException(
                $"{Variable} must be 1 to 32 letters, digits, '-' or '_', not \"{value}\".");
        return value;
    }

    /// <summary>The folder under %AppData% that holds settings.json, identity.bin and trusted-devices.json.</summary>
    internal static string StorageFolder(string? profile) =>
        profile == null ? "RemoteDesktopWin" : "RemoteDesktopWin-" + profile;

    // A stable name, so copies launched from different folders share the lock.
    // Local scopes the lock to the interactive Windows session.
    internal static string InstanceLock(string? profile) =>
        @"Local\RemoteDesktopWin.SingleInstance" + (profile == null ? "" : "." + profile);
}
