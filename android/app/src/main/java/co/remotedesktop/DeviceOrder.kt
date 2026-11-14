package co.remotedesktop

/**
 * The order every list of devices is shown in: PCs above phones, and within
 * each kind, devices that are online above those that are not, then by name.
 * The PC app lists its devices the same way (DeviceOrder.cs).
 */
object DeviceOrder {
    /** PCs first, then phones, then any kind this app doesn't know. */
    fun kindRank(device: String?): Int = when (device) {
        "windows" -> 0
        "android" -> 1
        else -> 2
    }

    /**
     * @param device the kind a device says it is: "windows" or "android".
     * @param online whether it is in the session now.
     * @param key what tells two devices of one name apart, so that they keep
     *   their places whichever of them joined first.
     */
    fun <T> sort(
        items: Iterable<T>,
        device: (T) -> String?,
        online: (T) -> Boolean,
        name: (T) -> String,
        key: (T) -> String?,
    ): List<T> = items.sortedWith(
        compareBy<T> { kindRank(device(it)) }
            .thenBy { if (online(it)) 0 else 1 }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { name(it) }
            .thenBy { key(it) ?: "" }
    )

    /** Devices online in the session, which all are. */
    fun peers(peers: Iterable<ConnectionManager.Peer>): List<ConnectionManager.Peer> =
        sort(peers, { it.device }, { true }, { it.name }, { PeerAuth.fingerprintOfPeer(it.id) ?: it.id })
}
