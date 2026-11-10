using System.Net;
using System.Security.Cryptography;
using RemoteDesktopWin;

// Checks the self-update: which versions count as newer, what a download must
// pass before it may replace the app, and that a failed install leaves the
// running app in place. GitHub is faked; signatures are checked for real on
// dotnet.exe, which Microsoft signs.
internal static class Program
{
    private const string Base = "https://github.test/download/";
    private const string Signer = "CN=Remote Desktop Test Publisher";
    private static int _failures;
    private static FakeGitHub _github = new();

    private static async Task<int> Main()
    {
        Versions();
        Checksums();
        Signatures();
        await Downloads();
        Swaps();
        Console.WriteLine(_failures == 0 ? "All update checks passed." : $"{_failures} update check(s) failed.");
        return _failures == 0 ? 0 : 1;
    }

    private static void Check(string name, bool ok)
    {
        Console.WriteLine((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) _failures++;
    }

    private static void Versions()
    {
        Check("1.0.10 is newer than 1.0.9", Updater.CompareVersions("1.0.10", "1.0.9") > 0);
        Check("equal versions compare equal", Updater.CompareVersions("1.0.52", "1.0.52") == 0);
        Check("a major version outranks the rest", Updater.CompareVersions("2.0.0", "1.99.99") > 0);
        Check("a prerelease comes before its release", Updater.CompareVersions("1.1.0-beta", "1.1.0") < 0);
        Check("a prerelease is newer than the release before it", Updater.CompareVersions("1.1.0-beta", "1.0.9") > 0);
        Check("the running version drops build metadata", !Updater.RunningVersion.Contains('+') && Updater.RunningVersion == "9.8.7");

        var updater = new Updater("1.0.52", Path.Combine(Path.GetTempPath(), "never-used.exe"));
        updater.OnLatest("../../evil");
        Check("a malformed version from the relay is ignored", updater.Latest == null);
        updater.OnLatest("1.0.52");
        Check("the same version is not an update", updater.Latest == "1.0.52" && !updater.UpdateAvailable);
        updater.OnLatest("1.0.53");
        Check("a newer version is an update", updater.UpdateAvailable);
    }

    private static void Checksums()
    {
        var sums = "aa11" + new string('0', 60) + "  RemoteDesktop-Windows-x64.exe\r\n" +
                   "bb22" + new string('0', 60) + "  RemoteDesktop-Android.apk\r\n";
        Check("the exe's checksum is read from SHA256SUMS.txt",
            Updater.HashFromSums(sums, Updater.AssetName) == "aa11" + new string('0', 60));
        Check("a file the sums don't list has no checksum", Updater.HashFromSums(sums, "other.exe") == null);
    }

    private static void Signatures()
    {
        var dotnet = Environment.GetEnvironmentVariable("PATH")!.Split(Path.PathSeparator)
            .Select(dir => Path.Combine(dir.Trim('"'), "dotnet.exe"))
            .FirstOrDefault(File.Exists);
        if (dotnet == null)
        {
            Check("dotnet.exe is on PATH, to check a real signature", false);
            return;
        }
        var signer = Updater.SignerOf(dotnet);
        Check("a validly signed exe names its publisher", signer?.Contains("Microsoft Corporation") == true);
        Check("an unsigned exe has no signer", Updater.SignerOf(Environment.ProcessPath!) == null);

        var tampered = Path.Combine(Path.GetTempPath(), $"tampered-{Guid.NewGuid():N}.exe");
        try
        {
            var bytes = File.ReadAllBytes(dotnet);
            bytes[bytes.Length / 2] ^= 0xFF;
            File.WriteAllBytes(tampered, bytes);
            Check("a signed exe changed after signing has no signer", Updater.SignerOf(tampered) == null);
        }
        finally { File.Delete(tampered); }
    }

    /// <summary>One scenario: an installed app at 1.0.0, GitHub serving
    /// <paramref name="release"/> as 9.8.7, and the signers the check reports.</summary>
    private static async Task<(Updater updater, string dir, bool ready)> Download(
        byte[] release, string? sums = null, HttpStatusCode status = HttpStatusCode.OK,
        string? ownSigner = Signer, string? newSigner = Signer, string latest = "9.8.7")
    {
        var dir = Directory.CreateTempSubdirectory("rd-update-").FullName;
        var exe = Path.Combine(dir, "RemoteDesktop-Windows-x64.exe");
        File.WriteAllText(exe, "the running app");
        var github = _github = new FakeGitHub
        {
            [$"{Base}v{latest}/SHA256SUMS.txt"] = (status, System.Text.Encoding.ASCII.GetBytes(
                sums ?? $"{Convert.ToHexString(SHA256.HashData(release)).ToLowerInvariant()}  {Updater.AssetName}\n")),
            [$"{Base}v{latest}/{Updater.AssetName}"] = (status, release),
        };
        var updater = new Updater("1.0.0", exe, Base, new HttpClient(github),
            path => path == exe ? ownSigner : newSigner);
        updater.OnLatest(latest);
        var ready = await updater.PrepareAsync();
        return (updater, dir, ready);
    }

    private static async Task Downloads()
    {
        // This test's exe reports version 9.8.7, like the release it stands in for.
        var release = File.ReadAllBytes(Environment.ProcessPath!);

        var (u, dir, ready) = await Download(release);
        Check("a matching, signed release of the announced version is ready", ready && u.ReadyToInstall);
        Check("it waits beside the app as .new", File.ReadAllBytes(u.NewPath).SequenceEqual(release));
        string? launched = null;
        u.Install(path => launched = path);
        var exe = Path.Combine(dir, "RemoteDesktop-Windows-x64.exe");
        Check("installing puts it in the app's place and starts it",
            launched == exe && File.ReadAllBytes(exe).SequenceEqual(release) && !File.Exists(u.NewPath));
        Check("the old app is set aside as .old", File.ReadAllText(u.OldPath) == "the running app");
        Updater.CleanUp(exe);
        for (int i = 0; i < 20 && File.Exists(u.OldPath); i++) await Task.Delay(50);
        Check("the next start removes the old app", !File.Exists(u.OldPath));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release);
        var install = Path.Combine(dir, "RemoteDesktop-Windows-x64.exe");
        try { u.Install(_ => throw new InvalidOperationException("could not start")); } catch { }
        Check("if the new app won't start, the running one is put back",
            File.ReadAllText(install) == "the running app" && File.ReadAllBytes(u.NewPath).SequenceEqual(release) &&
            u.State == Updater.Phase.Failed);
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, sums: new string('0', 64) + "  " + Updater.AssetName);
        Check("a download that doesn't match the checksum is refused",
            !ready && u.State == Updater.Phase.Failed && u.Error!.Contains("checksum") && !File.Exists(u.NewPath));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, newSigner: "CN=Someone Else");
        Check("a download signed by another publisher is refused",
            !ready && u.Error!.Contains("someone else") && !u.FailureIsTemporary && !File.Exists(u.NewPath));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, newSigner: null);
        Check("an unsigned download is refused", !ready && u.Error!.Contains("isn't validly signed"));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, latest: "9.9.9");
        Check("a download reporting another version is refused", !ready && u.Error!.Contains("not 9.9.9"));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, ownSigner: null);
        Check("an unsigned development build doesn't update itself",
            !ready && u.State == Updater.Phase.Unsupported && !File.Exists(u.NewPath));
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, status: HttpStatusCode.ServiceUnavailable);
        Check("GitHub being down is a temporary failure", !ready && u.FailureIsTemporary);
        var asked = _github.Requests;
        Check("a failed download isn't retried straight away", !await u.PrepareAsync() && _github.Requests == asked);
        await u.PrepareAsync(retryFailed: true);
        Check("unless someone asks for the update", _github.Requests > asked);
        Directory.Delete(dir, true);

        (u, dir, ready) = await Download(release, status: HttpStatusCode.NotFound);
        Check("a release without the exe is not a temporary failure", !ready && !u.FailureIsTemporary);
        Directory.Delete(dir, true);
    }

    private static void Swaps()
    {
        var dir = Directory.CreateTempSubdirectory("rd-swap-").FullName;
        var exe = Path.Combine(dir, "app.exe");
        File.WriteAllText(exe, "running");
        try
        {
            Updater.Swap(exe, Path.Combine(dir, "missing.new"), exe + ".old");
            Check("a swap with nothing to swap in throws", false);
        }
        catch (FileNotFoundException)
        {
            Check("a failed swap leaves the running app in place",
                File.ReadAllText(exe) == "running" && !File.Exists(exe + ".old"));
        }
        Directory.Delete(dir, true);
    }

    private sealed class FakeGitHub : HttpMessageHandler
    {
        private readonly Dictionary<string, (HttpStatusCode, byte[])> _files = new();

        public int Requests { get; private set; }

        public (HttpStatusCode, byte[]) this[string url] { set => _files[url] = value; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests++;
            var (status, body) = _files.TryGetValue(request.RequestUri!.ToString(), out var file)
                ? file
                : (HttpStatusCode.NotFound, Array.Empty<byte>());
            return Task.FromResult(new HttpResponseMessage(status) { Content = new ByteArrayContent(body) });
        }
    }
}
