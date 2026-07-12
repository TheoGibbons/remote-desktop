package co.joypilot.remotedesktop

/**
 * Entropy floor for session keys. The relay only ever sees the derived
 * pairing id, never the raw key, so weak-key rejection can only happen in
 * the clients. Keep these rules in sync with SessionKeyPolicy.cs (Windows).
 */
object SessionKeyPolicy {

    const val MIN_LENGTH = 16

    /** Returns null if the key is acceptable, otherwise a user-facing reason it is too guessable. */
    fun weaknessOf(key: String): String? {
        if (key.length < MIN_LENGTH) return "it must be at least $MIN_LENGTH characters"
        if (key.toSet().size < 10) return "it uses too few distinct characters"
        // Runs of 4+ identical or sequential characters ("aaaa", "1234", "dcba")
        // are the signature of hand-typed keys like "password12345678".
        for (i in 0..key.length - 4) {
            val d1 = key[i + 1] - key[i]
            val d2 = key[i + 2] - key[i + 1]
            val d3 = key[i + 3] - key[i + 2]
            if (d1 == d2 && d2 == d3 && d1 in -1..1)
                return "it contains a repeated or sequential run (like \"aaaa\" or \"1234\")"
        }
        return null
    }
}
