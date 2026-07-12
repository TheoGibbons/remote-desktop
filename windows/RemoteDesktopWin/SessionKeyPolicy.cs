namespace RemoteDesktopWin;

/// <summary>
/// Entropy floor for session keys. The relay only ever sees the derived
/// pairing id, never the raw key, so weak-key rejection can only happen in
/// the clients. Keep these rules in sync with SessionKeyPolicy.kt (Android).
/// </summary>
public static class SessionKeyPolicy
{
    public const int MinLength = 16;

    /// <returns>null if the key is acceptable, otherwise a user-facing reason it is too guessable.</returns>
    public static string? WeaknessOf(string key)
    {
        if (key.Length < MinLength)
            return $"it must be at least {MinLength} characters";
        if (key.Distinct().Count() < 10)
            return "it uses too few distinct characters";
        // Runs of 4+ identical or sequential characters ("aaaa", "1234", "dcba")
        // are the signature of hand-typed keys like "password12345678".
        for (int i = 0; i + 3 < key.Length; i++)
        {
            int d1 = key[i + 1] - key[i];
            int d2 = key[i + 2] - key[i + 1];
            int d3 = key[i + 3] - key[i + 2];
            if (d1 == d2 && d2 == d3 && d1 >= -1 && d1 <= 1)
                return "it contains a repeated or sequential run (like \"aaaa\" or \"1234\")";
        }
        return null;
    }
}
