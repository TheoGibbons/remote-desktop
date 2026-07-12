using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteDesktopWin;

/// <summary>
/// This install's persistent EC P-256 identity. The private key lives in
/// identity.bin next to settings.json, PKCS#8 wrapped with DPAPI (current
/// user). The SHA-256 of the DER SubjectPublicKeyInfo is the fingerprint;
/// its first 8 hex digits are the human-checkable "device code".
/// See PROTOCOL.md, "Device authentication".
/// </summary>
public sealed class DeviceIdentity
{
    private static readonly byte[] Context = Encoding.UTF8.GetBytes("remote-desktop/auth-v1");

    private readonly ECDsa _key;
    public byte[] PublicKey { get; }   // DER SubjectPublicKeyInfo
    public string Fingerprint { get; }

    private DeviceIdentity(ECDsa key)
    {
        _key = key;
        PublicKey = key.ExportSubjectPublicKeyInfo();
        Fingerprint = FingerprintOf(PublicKey);
    }

    public static DeviceIdentity LoadOrCreate(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var pkcs8 = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
                var key = ECDsa.Create();
                key.ImportPkcs8PrivateKey(pkcs8, out _);
                return new DeviceIdentity(key);
            }
        }
        catch
        {
            // Unreadable (restored from another machine/user) — a fresh identity
            // just means this install must be re-approved once.
        }

        var fresh = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllBytes(path,
            ProtectedData.Protect(fresh.ExportPkcs8PrivateKey(), null, DataProtectionScope.CurrentUser));
        return new DeviceIdentity(fresh);
    }

    private static byte[] SignedPayload(byte[] nonce, byte[] pub, string pairId)
    {
        var pid = Encoding.UTF8.GetBytes(pairId);
        var payload = new byte[Context.Length + nonce.Length + pub.Length + pid.Length];
        int p = 0;
        Buffer.BlockCopy(Context, 0, payload, p, Context.Length); p += Context.Length;
        Buffer.BlockCopy(nonce, 0, payload, p, nonce.Length); p += nonce.Length;
        Buffer.BlockCopy(pub, 0, payload, p, pub.Length); p += pub.Length;
        Buffer.BlockCopy(pid, 0, payload, p, pid.Length);
        return payload;
    }

    /// <summary>ECDSA-SHA256, DER-encoded (matches Java's SHA256withECDSA).</summary>
    public byte[] Sign(byte[] nonce, string pairId) =>
        _key.SignData(SignedPayload(nonce, PublicKey, pairId),
            HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    public static bool Verify(byte[] pub, byte[] nonce, string pairId, byte[] sig)
    {
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(pub, out _);
            return key.VerifyData(SignedPayload(nonce, pub, pairId), sig,
                HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch
        {
            return false;
        }
    }

    public static string FingerprintOf(byte[] spki) =>
        Convert.ToHexString(SHA256.HashData(spki)).ToLowerInvariant();

    /// <summary>"a3f29c41…" → "A3F2-9C41", the code users compare across devices.</summary>
    public static string ShortCode(string fingerprint) =>
        fingerprint.Length < 8 ? fingerprint.ToUpperInvariant()
        : $"{fingerprint[..4].ToUpperInvariant()}-{fingerprint[4..8].ToUpperInvariant()}";
}

/// <summary>
/// Per-peer authentication over the relay (see PROTOCOL.md, "Device
/// authentication"): challenges every peer that joins, verifies signatures,
/// and tracks which live peers are trusted. Trust is persisted per fingerprint
/// in trusted-devices.json, scoped to the pairing id — an approved device never
/// re-prompts unless revoked or the session key changes.
///
/// All methods must be called on the UI thread (MainWindow marshals every
/// WsClient event there); only <see cref="AllPeersTrusted"/> is safe to read
/// from any thread.
/// </summary>
public class PeerAuth
{
    public record TrustedDevice(string Fingerprint, string Name, string Device,
        DateTimeOffset FirstApproved, DateTimeOffset LastSeen);

    public record PendingRequest(string PeerId, string Fingerprint, string Name, string Device);

    private class StoreFile
    {
        public string PairId { get; set; } = "";
        public List<TrustedDevice> Devices { get; set; } = new();
    }

    private readonly DeviceIdentity _identity;
    private readonly string _storePath;
    private WsClient? _ws;
    private string _pairId = "";

    private readonly Dictionary<string, TrustedDevice> _trusted = new();   // fingerprint -> entry
    private readonly HashSet<string> _deniedFingerprints = new();          // this process run only
    private readonly List<PendingRequest> _pending = new();

    // Per-connection state (peer ids are new on every reconnect).
    private readonly Dictionary<string, (string Name, string Device)> _peerInfo = new();
    private readonly Dictionary<string, byte[]> _outstandingNonce = new();
    private readonly Dictionary<string, string> _peerFingerprint = new();  // verified peers
    private readonly HashSet<string> _trustedPeers = new();
    private volatile bool _allTrusted = true;

    /// <summary>A new fingerprint wants in — show the approve/deny prompt.</summary>
    public event Action<PendingRequest>? ApprovalNeeded;
    /// <summary>Any trust/pending/peer change — refresh the devices list.</summary>
    public event Action? Changed;

    public string OwnFingerprint => _identity.Fingerprint;
    public IReadOnlyCollection<TrustedDevice> TrustedDevices => _trusted.Values;
    public IReadOnlyList<PendingRequest> Pending => _pending;

    /// <summary>True while every peer in the session has been verified and
    /// approved. Gates broadcast sends (video, file chunks) — every key holder
    /// can decrypt those, approved or not. Thread-safe.</summary>
    public bool AllPeersTrusted => _allTrusted;

    public PeerAuth(string storageDir)
    {
        _identity = DeviceIdentity.LoadOrCreate(Path.Combine(storageDir, "identity.bin"));
        _storePath = Path.Combine(storageDir, "trusted-devices.json");
    }

    public void Attach(WsClient ws) => _ws = ws;

    /// <summary>Load the trust store; wipe it if the session key (pairing id) changed.</summary>
    public void OnSessionStart(string pairId)
    {
        _pairId = pairId;
        _trusted.Clear();
        try
        {
            if (File.Exists(_storePath))
            {
                var store = JsonSerializer.Deserialize<StoreFile>(File.ReadAllText(_storePath));
                if (store != null && store.PairId == pairId)
                    foreach (var d in store.Devices) _trusted[d.Fingerprint] = d;
            }
        }
        catch { }
        Save(); // rewrites under the current pairId, wiping stale entries
        ResetConnection();
    }

    /// <summary>Clear per-connection state (socket dropped; peer ids are stale).</summary>
    public void ResetConnection()
    {
        _peerInfo.Clear();
        _outstandingNonce.Clear();
        _peerFingerprint.Clear();
        _trustedPeers.Clear();
        _pending.Clear();
        RecomputeAllTrusted();
        Changed?.Invoke();
    }

    public void OnPeerJoined(string id, string name, string device)
    {
        _peerInfo[id] = (name, device);
        var nonce = RandomNumberGenerator.GetBytes(32);
        _outstandingNonce[id] = nonce;
        _ws?.SendJson(new JsonObject
        {
            ["type"] = "auth-challenge",
            ["to"] = id,
            ["nonce"] = Convert.ToBase64String(nonce),
        });
        RecomputeAllTrusted();
        Changed?.Invoke();
    }

    public void OnPeerLeft(string id)
    {
        _peerInfo.Remove(id);
        _outstandingNonce.Remove(id);
        _peerFingerprint.Remove(id);
        _trustedPeers.Remove(id);
        _pending.RemoveAll(p => p.PeerId == id);
        RecomputeAllTrusted();
        Changed?.Invoke();
    }

    public bool IsTrusted(string? peerId) => peerId != null && _trustedPeers.Contains(peerId);

    public string? FingerprintOf(string peerId) => _peerFingerprint.GetValueOrDefault(peerId);

    /// <returns>true if the message was an auth message and has been handled.</returns>
    public bool HandleJson(JsonNode msg)
    {
        var type = msg["type"]?.GetValue<string>();
        var from = msg["from"]?.GetValue<string>();
        if (from == null) return false;
        switch (type)
        {
            case "auth-challenge": OnChallenge(from, msg); return true;
            case "auth-response": OnResponse(from, msg); return true;
            default: return false;
        }
    }

    private void OnChallenge(string from, JsonNode msg)
    {
        byte[] nonce;
        try { nonce = Convert.FromBase64String(msg["nonce"]?.GetValue<string>() ?? ""); }
        catch { return; }
        if (nonce.Length is < 16 or > 64) return;

        _ws?.SendJson(new JsonObject
        {
            ["type"] = "auth-response",
            ["to"] = from,
            ["nonce"] = Convert.ToBase64String(nonce),
            ["pub"] = Convert.ToBase64String(_identity.PublicKey),
            ["sig"] = Convert.ToBase64String(_identity.Sign(nonce, _pairId)),
        });
    }

    private void OnResponse(string from, JsonNode msg)
    {
        if (!_outstandingNonce.Remove(from, out var expected)) return; // unsolicited
        byte[] nonce, pub, sig;
        try
        {
            nonce = Convert.FromBase64String(msg["nonce"]?.GetValue<string>() ?? "");
            pub = Convert.FromBase64String(msg["pub"]?.GetValue<string>() ?? "");
            sig = Convert.FromBase64String(msg["sig"]?.GetValue<string>() ?? "");
        }
        catch { return; }
        if (!CryptographicOperations.FixedTimeEquals(nonce, expected)) return;
        if (!DeviceIdentity.Verify(pub, nonce, _pairId, sig)) return;

        var fp = DeviceIdentity.FingerprintOf(pub);
        _peerFingerprint[from] = fp;
        var (name, device) = _peerInfo.GetValueOrDefault(from, ("unknown", "unknown"));

        if (_trusted.TryGetValue(fp, out var known))
        {
            _trusted[fp] = known with { Name = name, LastSeen = DateTimeOffset.UtcNow };
            Save();
            Trust(from);
        }
        else if (_deniedFingerprints.Contains(fp))
        {
            SendResult(from, false, "denied");
        }
        else if (_pending.Any(p => p.Fingerprint == fp))
        {
            // Same device reconnected while its prompt is still up: retarget the
            // request at the new peer id instead of stacking a second prompt.
            var i = _pending.FindIndex(p => p.Fingerprint == fp);
            _pending[i] = _pending[i] with { PeerId = from };
            SendResult(from, false, "pending");
        }
        else
        {
            var req = new PendingRequest(from, fp, name, device);
            _pending.Add(req);
            SendResult(from, false, "pending");
            Changed?.Invoke();
            ApprovalNeeded?.Invoke(req);
        }
    }

    public void Approve(string fingerprint)
    {
        var req = _pending.FirstOrDefault(p => p.Fingerprint == fingerprint);
        _pending.RemoveAll(p => p.Fingerprint == fingerprint);
        if (req == null) return;
        var now = DateTimeOffset.UtcNow;
        _trusted[fingerprint] = new TrustedDevice(fingerprint, req.Name, req.Device, now, now);
        Save();
        foreach (var (peerId, fp) in _peerFingerprint)
            if (fp == fingerprint) Trust(peerId);
        Changed?.Invoke();
    }

    public void Deny(string fingerprint)
    {
        _deniedFingerprints.Add(fingerprint);
        _pending.RemoveAll(p => p.Fingerprint == fingerprint);
        foreach (var (peerId, fp) in _peerFingerprint)
            if (fp == fingerprint) SendResult(peerId, false, "denied");
        Changed?.Invoke();
    }

    /// <summary>Delete the fingerprint from the trust store: the device is a
    /// stranger again and re-prompts on its next connect. Kicks live sessions.</summary>
    public void Revoke(string fingerprint)
    {
        _trusted.Remove(fingerprint);
        Save();
        foreach (var (peerId, fp) in _peerFingerprint)
        {
            if (fp != fingerprint) continue;
            _trustedPeers.Remove(peerId);
            SendResult(peerId, false, "revoked");
        }
        RecomputeAllTrusted();
        Changed?.Invoke();
    }

    /// <summary>Courtesy hang-up: the peer's viewers close, trust is kept, and
    /// it may reconnect silently. Enforcement stays local (we stop streaming).</summary>
    public void DisconnectPeer(string peerId) => SendResult(peerId, false, "disconnected");

    private void Trust(string peerId)
    {
        _trustedPeers.Add(peerId);
        SendResult(peerId, true, "trusted");
        RecomputeAllTrusted();
        Changed?.Invoke();
    }

    private void SendResult(string to, bool ok, string status) =>
        _ws?.SendJson(new JsonObject
        {
            ["type"] = "auth-result",
            ["to"] = to,
            ["ok"] = ok,
            ["status"] = status,
        });

    private void RecomputeAllTrusted() =>
        _allTrusted = _peerInfo.Keys.All(_trustedPeers.Contains);

    private void Save()
    {
        try
        {
            File.WriteAllText(_storePath, JsonSerializer.Serialize(
                new StoreFile { PairId = _pairId, Devices = _trusted.Values.ToList() },
                new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { }
    }
}
