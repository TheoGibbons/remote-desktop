using System.Diagnostics;
using RemoteDesktopWin;

internal static class Program
{
    private static int Main(string[] args)
    {
        if (args.Length != 0)
        {
            Console.WriteLine("starting");
            using var guard = SingleInstanceGuard.TryAcquire(
                args[0] == "wait" ? TimeSpan.FromSeconds(5) : TimeSpan.Zero, args[1]);
            Console.WriteLine(guard == null ? "blocked" : "acquired");
            if (guard == null) return 1;
            if (args[0] == "hold") Console.ReadLine();
            return 0;
        }

        CheckProfiles();

        string name = @"Local\RemoteDesktopWin.SingleInstanceTests." + Guid.NewGuid().ToString("N");
        using (var first = SingleInstanceGuard.TryAcquire(TimeSpan.Zero, name)
            ?? throw new Exception("First instance did not acquire the lock"))
        {
            ExpectProbe(name, acquired: false);
            ExpectProbe(name, acquired: false);
            Console.WriteLine("PASS: repeated second launches are blocked while the first instance keeps running");
        }
        ExpectProbe(name, acquired: true);
        Console.WriteLine("PASS: a new launch succeeds after normal shutdown");

        SingleInstanceGuard? original = SingleInstanceGuard.TryAcquire(TimeSpan.Zero, name)
            ?? throw new Exception("Original instance did not acquire the lock");
        try
        {
            using var replacement = Start("wait", name);
            try
            {
                ExpectLine(replacement, "starting");
                if (replacement.WaitForExit(200)) throw new Exception("Replacement did not wait for the original");
                original.Dispose();
                original = null;
                ExpectLine(replacement, "acquired");
                ExpectExit(replacement, 0);
                Console.WriteLine("PASS: administrator restart waits for the original instance to release its lock");
            }
            finally { Stop(replacement); }
        }
        finally { original?.Dispose(); }

        using var crashed = Start("hold", name);
        try
        {
            ExpectLine(crashed, "starting");
            ExpectLine(crashed, "acquired");
            // Keep the kernel object alive so the next owner sees an abandoned
            // mutex rather than simply creating a fresh one after the crash.
            using var retained = new Mutex(initiallyOwned: false, name);
            crashed.Kill();
            ExpectExit(crashed, expectedCode: null);
            ExpectProbe(name, acquired: true);
            Console.WriteLine("PASS: a new launch recovers the lock after an abrupt process exit");
        }
        finally { Stop(crashed); }
        return 0;
    }

    /// <summary>A development copy (REMOTEDESKTOP_PROFILE, debug builds) must not share
    /// the installed app's data folder or lock, and the installed app's must not move.</summary>
    private static void CheckProfiles()
    {
        Expect(AppProfile.StorageFolder(null) == "RemoteDesktopWin", "The installed app's data folder changed");
        Expect(AppProfile.InstanceLock(null) == @"Local\RemoteDesktopWin.SingleInstance", "The installed app's lock changed");
        Expect(AppProfile.Parse(null) == null && AppProfile.Parse("") == null, "An unset profile must be the ordinary app");
        Console.WriteLine("PASS: without a profile the app keeps its usual data folder and lock");

        Expect(AppProfile.Parse("rig") == "rig", "A plain name must be accepted");
        Expect(AppProfile.StorageFolder("rig") == "RemoteDesktopWin-rig", "A profile must have its own data folder");
        Expect(AppProfile.InstanceLock("rig") != AppProfile.InstanceLock(null), "A profile must have its own lock");
        foreach (var bad in new[] { @"..\RemoteDesktopWin", "a/b", "a b", ".", new string('x', 33) })
        {
            try { AppProfile.Parse(bad); }
            catch (ArgumentException) { continue; }
            throw new Exception($"Profile name '{bad}' was accepted");
        }
        Console.WriteLine("PASS: a profile gets its own data folder and lock, and a bad name is refused");

        string profile = "test-" + Guid.NewGuid().ToString("N")[..16];
        using (SingleInstanceGuard.TryAcquire(TimeSpan.Zero, AppProfile.InstanceLock(profile))
            ?? throw new Exception("The profile's first copy did not acquire its lock"))
        {
            ExpectProbe(AppProfile.InstanceLock(profile), acquired: false);
            ExpectProbe(AppProfile.InstanceLock(profile + "-b"), acquired: true);
        }
        Console.WriteLine("PASS: a profile allows one copy of itself, beside copies under other profiles");
    }

    private static void Expect(bool condition, string failure)
    {
        if (!condition) throw new Exception(failure);
    }

    private static Process Start(string mode, string name)
    {
        var start = new ProcessStartInfo(Environment.ProcessPath!)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            WindowStyle = ProcessWindowStyle.Hidden,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
        };
        start.ArgumentList.Add(mode);
        start.ArgumentList.Add(name);
        return Process.Start(start) ?? throw new Exception("Could not start test process");
    }

    private static void ExpectProbe(string name, bool acquired)
    {
        using var process = Start("probe", name);
        try
        {
            ExpectLine(process, "starting");
            ExpectLine(process, acquired ? "acquired" : "blocked");
            ExpectExit(process, acquired ? 0 : 1);
        }
        finally { Stop(process); }
    }

    private static void ExpectLine(Process process, string expected)
    {
        string? line = process.StandardOutput.ReadLineAsync()
            .WaitAsync(TimeSpan.FromSeconds(10)).GetAwaiter().GetResult();
        if (line != expected) throw new Exception($"Expected '{expected}', got '{line}'");
    }

    private static void ExpectExit(Process process, int? expectedCode)
    {
        if (!process.WaitForExit(10_000)) throw new Exception("Test process did not exit");
        if (expectedCode.HasValue && process.ExitCode != expectedCode)
            throw new Exception($"Unexpected exit code: {process.ExitCode}");
    }

    private static void Stop(Process process)
    {
        if (!process.HasExited)
        {
            process.Kill();
            process.WaitForExit(10_000);
        }
    }
}
