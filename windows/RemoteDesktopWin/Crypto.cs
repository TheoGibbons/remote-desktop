using System.Security.Cryptography;
using System.Text;

namespace RemoteDesktopWin;

/// <summary>
/// End-to-end encryption shared with the Android app. Keys are derived from the
/// session key with HKDF-SHA256; frames are AES-256-GCM with the channel byte as
/// associated data. The relay only ever sees ciphertext + routing metadata.
///
/// Wire format: nonce(12) || ciphertext || tag(16).
/// Channels: 0 = JSON control, 1 = video, 2 = file chunk.
///
/// The constants and vectors here are verified against the same values in
/// server/crypto.mjs and the Kotlin Crypto object (see <see cref="SelfTest"/>).
/// </summary>
public static class Crypto
{
    private static readonly byte[] Salt = Encoding.UTF8.GetBytes("remote-desktop/v1");
    private static readonly byte[] InfoEnc = Encoding.UTF8.GetBytes("e2ee-key");
    private static readonly byte[] InfoPair = Encoding.UTF8.GetBytes("pair-id");

    public const byte ChJson = 0;
    public const byte ChVideo = 1;
    public const byte ChFile = 2;

    /// <returns>(32-byte AES key, lowercase-hex pairing id sent to the server)</returns>
    public static (byte[] encKey, string pairId) DeriveKeys(string sessionKey)
    {
        var ikm = Encoding.UTF8.GetBytes(sessionKey);
        var encKey = HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, 32, Salt, InfoEnc);
        var pairId = HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm, 32, Salt, InfoPair);
        return (encKey, Convert.ToHexString(pairId).ToLowerInvariant());
    }

    public static byte[] Encrypt(byte[] key, byte[] plaintext, byte channel) =>
        EncryptWithNonce(key, plaintext, channel, RandomNumberGenerator.GetBytes(12));

    private static byte[] EncryptWithNonce(byte[] key, byte[] plaintext, byte channel, byte[] nonce)
    {
        var ct = new byte[plaintext.Length];
        var tag = new byte[16];
        using var gcm = new AesGcm(key, 16);
        ReadOnlySpan<byte> aad = stackalloc byte[] { channel };
        gcm.Encrypt(nonce, plaintext, ct, tag, aad);
        var outb = new byte[12 + ct.Length + 16];
        Buffer.BlockCopy(nonce, 0, outb, 0, 12);
        Buffer.BlockCopy(ct, 0, outb, 12, ct.Length);
        Buffer.BlockCopy(tag, 0, outb, 12 + ct.Length, 16);
        return outb;
    }

    /// <returns>plaintext, or null if the frame is malformed or fails authentication</returns>
    public static byte[]? Decrypt(byte[] key, byte[] blob, byte channel)
    {
        if (blob.Length < 12 + 16) return null;
        try
        {
            var nonce = blob.AsSpan(0, 12);
            var tag = blob.AsSpan(blob.Length - 16, 16);
            var ct = blob.AsSpan(12, blob.Length - 28);
            var pt = new byte[ct.Length];
            using var gcm = new AesGcm(key, 16);
            ReadOnlySpan<byte> aad = stackalloc byte[] { channel };
            gcm.Decrypt(nonce, ct, tag, pt, aad);
            return pt;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Verifies this implementation against the shared cross-language vectors.
    /// Call once at startup (Debug) to catch any crypto drift.
    /// </summary>
    public static bool SelfTest()
    {
        const string key = "correct horse battery staple";
        const string expectEncKey = "198b7e020fa0c8fdfeb3514e6b402eb3ea5af30addadc19153965eeec0e8dabf";
        const string expectPairId = "9a81be09e574b5a3078107db507c21722e8ea1f9f0a7bf762d6894a5dd371626";
        const string expectBlob =
            "000102030405060708090a0b11316fc47766f9c1dec528cf275cf3fa7d82e9c7f09830195d76f8782e7999f9150b6d97be135331";
        const string plaintext = "{\"type\":\"mouse\",\"x\":0.5}";

        var (enc, pid) = DeriveKeys(key);
        if (Convert.ToHexString(enc).ToLowerInvariant() != expectEncKey) return false;
        if (pid != expectPairId) return false;

        var nonce = Convert.FromHexString("000102030405060708090a0b");
        var blob = EncryptWithNonce(enc, Encoding.UTF8.GetBytes(plaintext), ChJson, nonce);
        if (Convert.ToHexString(blob).ToLowerInvariant() != expectBlob) return false;

        var dec = Decrypt(enc, blob, ChJson);
        if (dec == null || Encoding.UTF8.GetString(dec) != plaintext) return false;
        if (Decrypt(enc, blob, ChVideo) != null) return false; // AAD binding
        return true;
    }
}
