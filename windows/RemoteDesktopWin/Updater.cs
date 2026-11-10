using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.RegularExpressions;

namespace RemoteDesktopWin;

/// <summary>
/// Keeps this PC on the newest release. The relay names the newest version
/// (welcome <c>latest</c>, or a <c>latest</c> message when one comes out); this
/// downloads that release's exe next to the running one, checks it, and swaps
/// it in when asked to install.
///
/// The relay only names a version, so a hostile relay can't hand this PC a
/// program. The file always comes from this repository's GitHub release for
/// that version. It must match the release's SHA256SUMS.txt, report that
/// version, and carry a valid Authenticode signature from the same publisher
/// as the running exe. Development builds are unsigned, so they never update.
/// </summary>
public sealed class Updater
{
    public enum Phase
    {
        /// <summary>Nothing to do, or not started yet.</summary>
        Idle,
        Downloading,
        /// <summary>Downloaded and checked; installs on <see cref="Install"/>.</summary>
        Ready,
        Failed,
        /// <summary>This build can't update itself (unsigned development build).</summary>
        Unsupported,
    }

    public const string AssetName = "RemoteDesktop-Windows-x64.exe";
    public const string ReleasesPage = "https://github.com/TheoGibbons/remote-desktop/releases/latest";
    private const string DownloadBase = "https://github.com/TheoGibbons/remote-desktop/releases/download/";

    // A failed download is retried this long after, unless someone asks for it.
    private static readonly TimeSpan RetryAfter = TimeSpan.FromHours(1);

    private static readonly Regex VersionPattern = new(@"^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$");

    /// <summary>This app's version, without build metadata such as the commit hash.</summary>
    public static string RunningVersion { get; } = ReadRunningVersion();

    public string CurrentVersion { get; }

    /// <summary>The newest release, as the relay last said, or null before it has.</summary>
    public string? Latest { get; private set; }

    public Phase State { get; private set; }

    /// <summary>Why the last attempt failed, for <see cref="Phase.Failed"/> and <see cref="Phase.Unsupported"/>.</summary>
    public string? Error { get; private set; }

    /// <summary>The version being downloaded, ready, or that failed.</summary>
    public string? Target { get; private set; }

    /// <summary>The failure looks temporary (the network, or GitHub having a
    /// bad moment), so the hourly retry will likely fix it unattended.</summary>
    public bool FailureIsTemporary { get; private set; }

    /// <summary>The newest release is downloaded, checked and waiting to install.</summary>
    public bool ReadyToInstall => State == Phase.Ready && Target == Latest;

    /// <summary>Raised on whichever thread changed something.</summary>
    public event Action? Changed;

    public bool UpdateAvailable => Latest != null && CompareVersions(Latest, CurrentVersion) > 0;

    private readonly string _exePath;
    private readonly string _downloadBase;
    private readonly HttpClient _http;
    private readonly Func<string, string?> _signerOf;
    private readonly object _lock = new();
    private Task<bool>? _preparing;
    private DateTime _failedAt;
    private string? _ownSigner;

    public Updater(string currentVersion, string exePath, string? downloadBase = null,
        HttpClient? http = null, Func<string, string?>? signerOf = null)
    {
        CurrentVersion = currentVersion;
        _exePath = exePath;
        _downloadBase = downloadBase ?? DownloadBase;
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        _signerOf = signerOf ?? SignerOf;
    }

    public string NewPath => _exePath + ".new";
    public string OldPath => _exePath + ".old";

    /// <summary>The relay named the newest release.</summary>
    public void OnLatest(string? version)
    {
        if (version == null || !VersionPattern.IsMatch(version) || version == Latest) return;
        Latest = version;
        Changed?.Invoke();
    }

    /// <summary>
    /// Downloads and checks the newest release, once per version. True when it
    /// is ready to install. A failure is retried an hour later, or right away
    /// with <paramref name="retryFailed"/> (someone asked for the update).
    /// </summary>
    public Task<bool> PrepareAsync(bool retryFailed = false)
    {
        lock (_lock)
        {
            if (!UpdateAvailable) return Task.FromResult(false);
            var version = Latest!;
            if (_preparing is { IsCompleted: false } && Target == version) return _preparing;
            if (Target == version)
            {
                if (State == Phase.Ready) return Task.FromResult(true);
                if (State == Phase.Unsupported) return Task.FromResult(false);
                if (State == Phase.Failed && !retryFailed && DateTime.UtcNow - _failedAt < RetryAfter)
                    return Task.FromResult(false);
            }
            Target = version;
            State = Phase.Downloading;
            Error = null;
            // An older release still downloading writes the same file, so let it finish first.
            var previous = _preparing;
            _preparing = Task.Run(async () =>
            {
                if (previous != null) await previous;
                return await DownloadAsync(version);
            });
        }
        Changed?.Invoke();
        return _preparing;
    }

    private async Task<bool> DownloadAsync(string version)
    {
        try
        {
            // Hashes the whole exe, so only once, and only when there's an update.
            _ownSigner ??= _signerOf(_exePath);
            if (_ownSigner == null)
            {
                Finish(version, Phase.Unsupported, "This is a development build, so it can't update itself.");
                return false;
            }

            var sums = await _http.GetStringAsync($"{_downloadBase}v{version}/SHA256SUMS.txt");
            var expected = HashFromSums(sums, AssetName)
                ?? throw new InvalidDataException($"The release has no checksum for {AssetName}.");

            var actual = await DownloadToAsync($"{_downloadBase}v{version}/{AssetName}", NewPath);
            if (!string.Equals(actual, expected, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("The download doesn't match the release's checksum.");

            var signer = _signerOf(NewPath);
            if (signer == null) throw new InvalidDataException("The download isn't validly signed.");
            if (signer != _ownSigner) throw new InvalidDataException($"The download is signed by someone else ({signer}).");

            var reported = FileVersionInfo.GetVersionInfo(NewPath).ProductVersion?.Split('+')[0];
            if (reported != version)
                throw new InvalidDataException($"The download is version {reported ?? "unknown"}, not {version}.");

            Finish(version, Phase.Ready, null);
            return true;
        }
        catch (Exception ex)
        {
            TryDelete(NewPath);
            Finish(version, Phase.Failed, Describe(ex), IsTemporary(ex));
            return false;
        }
    }

    private void Finish(string version, Phase phase, string? error, bool temporary = false)
    {
        lock (_lock)
        {
            if (Target != version) return; // a newer release took over
            State = phase;
            Error = error;
            FailureIsTemporary = temporary;
            if (phase == Phase.Failed) _failedAt = DateTime.UtcNow;
        }
        Changed?.Invoke();
    }

    private static bool IsTemporary(Exception ex) => ex switch
    {
        HttpRequestException { StatusCode: { } code } => (int)code >= 500 || (int)code == 429,
        HttpRequestException or TaskCanceledException => true,
        _ => false,
    };

    private async Task<string> DownloadToAsync(string url, string path)
    {
        using var response = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
        response.EnsureSuccessStatusCode();
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        await using (var source = await response.Content.ReadAsStreamAsync())
        await using (var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            var buffer = new byte[81920];
            int read;
            while ((read = await source.ReadAsync(buffer)) > 0)
            {
                hash.AppendData(buffer, 0, read);
                await file.WriteAsync(buffer.AsMemory(0, read));
            }
        }
        return Convert.ToHexString(hash.GetHashAndReset());
    }

    private static string Describe(Exception ex) => ex switch
    {
        HttpRequestException { StatusCode: { } code } => $"GitHub answered {(int)code} {code}.",
        HttpRequestException or TaskCanceledException => "Couldn't reach GitHub to download it.",
        UnauthorizedAccessException => $"Can't write next to the app ({ex.Message}).",
        IOException => $"Can't save the download ({ex.Message}).",
        _ => ex.Message,
    };

    /// <summary>
    /// Puts the downloaded exe in place of the running one and starts it with
    /// <paramref name="launch"/>; the caller then exits. If either step fails
    /// the running exe is put back, the update is marked failed, and this throws.
    /// </summary>
    public void Install(Action<string> launch)
    {
        if (State != Phase.Ready) throw new InvalidOperationException("No update is ready.");
        try
        {
            Swap(_exePath, NewPath, OldPath);
            try { launch(_exePath); }
            catch
            {
                Swap(_exePath, OldPath, NewPath); // put the running exe back
                throw;
            }
        }
        catch (Exception ex)
        {
            Finish(Target!, Phase.Failed, Describe(ex));
            throw;
        }
    }

    /// <summary>
    /// Windows lets a running exe be renamed but not replaced, so it moves
    /// aside to <paramref name="aside"/> and the new file takes its name.
    /// </summary>
    internal static void Swap(string exe, string replacement, string aside)
    {
        if (File.Exists(aside)) File.Delete(aside);
        File.Move(exe, aside);
        try { File.Move(replacement, exe); }
        catch
        {
            File.Move(aside, exe);
            throw;
        }
    }

    /// <summary>
    /// After an update the previous exe is left beside this one as .old, still
    /// running for a moment as it exits. Removes it, and any half download.
    /// </summary>
    public static void CleanUp(string exePath)
    {
        TryDelete(exePath + ".new");
        var old = exePath + ".old";
        if (!File.Exists(old)) return;
        Task.Run(async () =>
        {
            for (int i = 0; i < 30 && File.Exists(old); i++)
            {
                if (TryDelete(old)) return;
                await Task.Delay(1000);
            }
        });
    }

    private static bool TryDelete(string path)
    {
        try
        {
            File.Delete(path);
            return true;
        }
        catch { return false; }
    }

    /// <summary>
    /// Orders versions such as 1.0.9 &lt; 1.0.10. A prerelease (1.1.0-beta)
    /// comes before its release.
    /// </summary>
    public static int CompareVersions(string a, string b)
    {
        static (int[] parts, bool pre) Parse(string v)
        {
            var dash = v.IndexOf('-');
            var core = dash < 0 ? v : v[..dash];
            var parts = core.Split('.').Select(p => int.TryParse(p, out var n) ? n : 0).ToArray();
            return (parts, dash >= 0);
        }
        var (pa, preA) = Parse(a);
        var (pb, preB) = Parse(b);
        for (int i = 0; i < Math.Max(pa.Length, pb.Length); i++)
        {
            var x = i < pa.Length ? pa[i] : 0;
            var y = i < pb.Length ? pb[i] : 0;
            if (x != y) return x.CompareTo(y);
        }
        return preA == preB ? 0 : preA ? -1 : 1;
    }

    /// <summary>The hash a sha256sum-style file lists for <paramref name="fileName"/>.</summary>
    public static string? HashFromSums(string sums, string fileName)
    {
        foreach (var line in sums.Split('\n'))
        {
            var parts = line.Trim().Split((char[]?)null, 2, StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 2 && parts[1].TrimStart('*') == fileName && parts[0].Length == 64) return parts[0];
        }
        return null;
    }

    private static string ReadRunningVersion()
    {
        var assembly = typeof(Updater).Assembly;
        var version = assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion
            ?? assembly.GetName().Version?.ToString(3)
            ?? "0.0.0";
        return version.Split('+')[0];
    }

    // ---------- Authenticode ----------

    /// <summary>
    /// The subject of the certificate that signed <paramref name="path"/>, when
    /// Windows accepts its Authenticode signature; null when it is unsigned or
    /// the signature doesn't hold up. Releases are signed with short-lived
    /// certificates that renew every few days, so it is the publisher's name
    /// that stays the same between releases, not the certificate.
    /// </summary>
    public static string? SignerOf(string path)
    {
        if (!File.Exists(path) || WinVerifyTrust(path) != 0) return null;
        try
        {
#pragma warning disable SYSLIB0057 // No X509CertificateLoader equivalent reads an embedded signature.
            using var cert = X509Certificate.CreateFromSignedFile(path);
#pragma warning restore SYSLIB0057
            return cert.Subject;
        }
        catch (CryptographicException)
        {
            return null;
        }
    }

    private static int WinVerifyTrust(string path)
    {
        var fileInfo = new WinTrustFileInfo
        {
            cbStruct = (uint)Marshal.SizeOf<WinTrustFileInfo>(),
            pcwszFilePath = path,
        };
        var filePtr = Marshal.AllocHGlobal(Marshal.SizeOf<WinTrustFileInfo>());
        try
        {
            Marshal.StructureToPtr(fileInfo, filePtr, false);
            var data = new WinTrustData
            {
                cbStruct = (uint)Marshal.SizeOf<WinTrustData>(),
                dwUIChoice = 2,          // WTD_UI_NONE
                fdwRevocationChecks = 0, // WTD_REVOKE_NONE: no network round trip that could fail the update
                dwUnionChoice = 1,       // WTD_CHOICE_FILE
                pFile = filePtr,
                dwStateAction = 0,       // WTD_STATEACTION_IGNORE
            };
            var action = new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE"); // WINTRUST_ACTION_GENERIC_VERIFY_V2
            return WinVerifyTrustNative(IntPtr.Zero, ref action, ref data);
        }
        finally
        {
            Marshal.DestroyStructure<WinTrustFileInfo>(filePtr);
            Marshal.FreeHGlobal(filePtr);
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WinTrustFileInfo
    {
        public uint cbStruct;
        [MarshalAs(UnmanagedType.LPWStr)] public string pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WinTrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }

    [DllImport("wintrust.dll", EntryPoint = "WinVerifyTrust", CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrustNative(IntPtr hwnd, ref Guid action, ref WinTrustData data);
}
