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
