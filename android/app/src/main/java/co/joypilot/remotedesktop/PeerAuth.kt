package co.joypilot.remotedesktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-device authentication (see PROTOCOL.md, "Device authentication").
 *
 * This install's identity is a non-exportable EC P-256 key in the
 * AndroidKeyStore; the SHA-256 of its DER SubjectPublicKeyInfo is the
 * fingerprint, whose first 8 hex digits are shown as the "device code".
 * Every peer that joins the session is challenged; peers are only honored
 * (input, viewing, files) once their fingerprint is in the local trust
 * store — approved once by the user, silently re-verified on every
 * reconnect, until revoked or the session key changes.
 *
 * Called from the OkHttp network thread and the UI thread; state is guarded
 * by [lock]. Listeners run on the main thread.
 */
object PeerAuth {

    data class TrustedDevice(
        val fingerprint: String,
        val name: String,
        val device: String,
        val firstApproved: Long,
        val lastSeen: Long,
    )

    data class PendingRequest(
        val peerId: String,
        val fingerprint: String,
        val name: String,
        val device: String,
    )

    private const val TAG = "PeerAuth"
    private const val KEY_ALIAS = "device-identity"
    private const val AUTH_CONTEXT = "remote-desktop/auth-v1"
    private const val NOTIF_CHANNEL = "pairing"

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pairId = ""

    private val trusted = LinkedHashMap<String, TrustedDevice>() // fingerprint -> entry
    private val deniedFingerprints = HashSet<String>()           // this process run only
    private val pending = mutableListOf<PendingRequest>()

    // Per-connection state (peer ids are new on every reconnect).
    private val peerInfo = HashMap<String, Pair<String, String>>() // id -> (name, device)
    private val outstandingNonce = HashMap<String, ByteArray>()
    private val peerFingerprint = HashMap<String, String>()       // verified peers
    private val trustedPeers = HashSet<String>()

    /** True while every peer in the session is verified and approved. Gates
     *  broadcast sends (video frames) — any key holder can decrypt those. */
    @Volatile
    var allPeersTrusted = true
        private set

    /** Any trust/pending change — refresh device lists. Main thread. */
    val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /** A new fingerprint wants in — show the approve/deny UI. Main thread. */
    val approvalListeners = CopyOnWriteArrayList<(PendingRequest) -> Unit>()

    // ---------- identity ----------

    private fun keyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) return
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        kpg.generateKeyPair()
    }

    private fun privateKey(): PrivateKey = keyStore().getKey(KEY_ALIAS, null) as PrivateKey

    /** DER SubjectPublicKeyInfo — the same encoding .NET exports/imports. */
    private fun publicKeyBytes(): ByteArray = keyStore().getCertificate(KEY_ALIAS).publicKey.encoded

    private fun ByteArray.sha256Hex(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    val ownFingerprint: String
        get() {
            ensureKey()
            return publicKeyBytes().sha256Hex()
        }

    /** "a3f29c41…" → "A3F2-9C41", the code users compare across devices. */
    fun shortCode(fingerprint: String): String =
        if (fingerprint.length < 8) fingerprint.uppercase()
        else "${fingerprint.substring(0, 4).uppercase()}-${fingerprint.substring(4, 8).uppercase()}"

    private fun signedPayload(nonce: ByteArray, pub: ByteArray): ByteArray =
        AUTH_CONTEXT.toByteArray() + nonce + pub + pairId.toByteArray()

    /** ECDSA-SHA256, DER-encoded (Java default; matches .NET Rfc3279DerSequence). */
    private fun sign(nonce: ByteArray, pub: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey())
            update(signedPayload(nonce, pub))
            sign()
        }

    private fun verify(pub: ByteArray, nonce: ByteArray, sig: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedPayload(nonce, pub))
            verify(sig)
        }
    } catch (e: Exception) {
        false
    }

    // ---------- lifecycle ----------

    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
        ensureKey()
    }

    /** Load the trust store; wipe it if the session key (pairing id) changed. */
    fun onSessionStart(newPairId: String) {
        synchronized(lock) {
            pairId = newPairId
            trusted.clear()
            val sp = appContext.getSharedPreferences("trust", Context.MODE_PRIVATE)
            if (sp.getString("pairId", "") == newPairId) {
                try {
                    val arr = JSONArray(sp.getString("devices", "[]") ?: "[]")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val d = TrustedDevice(
                            o.getString("fingerprint"), o.getString("name"),
                            o.getString("device"), o.getLong("firstApproved"), o.getLong("lastSeen"),
                        )
                        trusted[d.fingerprint] = d
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "trust store unreadable, starting fresh", e)
                }
            }
            saveLocked()
            resetConnectionLocked()
        }
        notifyChanged()
    }

    /** Clear per-connection state (socket dropped; peer ids are stale). */
    fun resetConnection() {
        synchronized(lock) { resetConnectionLocked() }
        notifyChanged()
    }

    private fun resetConnectionLocked() {
        peerInfo.clear()
        outstandingNonce.clear()
        peerFingerprint.clear()
        trustedPeers.clear()
        pending.clear()
        recomputeAllTrustedLocked()
    }

    fun onPeerJoined(id: String, name: String, device: String) {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        synchronized(lock) {
            peerInfo[id] = Pair(name, device)
            outstandingNonce[id] = nonce
            recomputeAllTrustedLocked()
        }
        ConnectionManager.sendJson(
            JSONObject()
                .put("type", "auth-challenge")
                .put("to", id)
                .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
        )
        notifyChanged()
    }

    fun onPeerLeft(id: String) {
        val dropped: List<PendingRequest>
        synchronized(lock) {
            peerInfo.remove(id)
            outstandingNonce.remove(id)
            peerFingerprint.remove(id)
            trustedPeers.remove(id)
            dropped = pending.filter { it.peerId == id }
            pending.removeAll { it.peerId == id }
            recomputeAllTrustedLocked()
        }
        dropped.forEach { cancelApprovalNotification(it.fingerprint) }
        notifyChanged()
    }

    fun isTrusted(peerId: String?): Boolean =
        peerId != null && synchronized(lock) { trustedPeers.contains(peerId) }

    fun fingerprintOfPeer(peerId: String): String? =
        synchronized(lock) { peerFingerprint[peerId] }

    fun trustedDevices(): List<TrustedDevice> = synchronized(lock) { trusted.values.toList() }

    fun pendingRequests(): List<PendingRequest> = synchronized(lock) { pending.toList() }

    // ---------- handshake ----------

    /** @return true if the message was an auth message and has been handled. */
    fun handleJson(msg: JSONObject): Boolean {
        val from = msg.optString("from")
        if (from.isEmpty()) return false
        when (msg.optString("type")) {
            "auth-challenge" -> onChallenge(from, msg)
            "auth-response" -> onResponse(from, msg)
            else -> return false
        }
        return true
    }

    private fun onChallenge(from: String, msg: JSONObject) {
        val nonce = try { Base64.decode(msg.optString("nonce"), Base64.NO_WRAP) } catch (e: Exception) { return }
        if (nonce.size < 16 || nonce.size > 64) return
        val pub = publicKeyBytes()
        val sig = try { sign(nonce, pub) } catch (e: Exception) {
            Log.e(TAG, "signing failed", e)
            return
        }
        ConnectionManager.sendJson(
            JSONObject()
                .put("type", "auth-response")
                .put("to", from)
                .put("nonce", Base64.encodeToString(nonce, Base64.NO_WRAP))
                .put("pub", Base64.encodeToString(pub, Base64.NO_WRAP))
                .put("sig", Base64.encodeToString(sig, Base64.NO_WRAP))
        )
    }

    private fun onResponse(from: String, msg: JSONObject) {
        val nonce: ByteArray
        val pub: ByteArray
        val sig: ByteArray
        try {
            nonce = Base64.decode(msg.optString("nonce"), Base64.NO_WRAP)
            pub = Base64.decode(msg.optString("pub"), Base64.NO_WRAP)
            sig = Base64.decode(msg.optString("sig"), Base64.NO_WRAP)
        } catch (e: Exception) {
            return
        }

        var request: PendingRequest? = null
        synchronized(lock) {
            val expected = outstandingNonce.remove(from) ?: return // unsolicited
            if (!MessageDigest.isEqual(nonce, expected)) return
            if (!verify(pub, nonce, sig)) return

            val fp = pub.sha256Hex()
            peerFingerprint[from] = fp
            val (name, device) = peerInfo[from] ?: Pair("unknown", "unknown")

            val known = trusted[fp]
            when {
                known != null -> {
                    trusted[fp] = known.copy(name = name, lastSeen = System.currentTimeMillis())
                    saveLocked()
                    trustedPeers.add(from)
                    recomputeAllTrustedLocked()
                    sendResult(from, true, "trusted")
                }
                deniedFingerprints.contains(fp) -> sendResult(from, false, "denied")
                pending.any { it.fingerprint == fp } -> {
                    // Same device reconnected while its prompt is still up:
                    // retarget the request instead of stacking a second prompt.
                    val i = pending.indexOfFirst { it.fingerprint == fp }
                    pending[i] = pending[i].copy(peerId = from)
                    sendResult(from, false, "pending")
                }
                else -> {
                    request = PendingRequest(from, fp, name, device)
                    pending.add(request!!)
                    sendResult(from, false, "pending")
                }
            }
        }
        notifyChanged()
        request?.let { req ->
            showApprovalNotification(req)
            main.post { approvalListeners.forEach { it(req) } }
        }
    }

    // ---------- user decisions ----------

    fun approve(fingerprint: String) {
        synchronized(lock) {
            val req = pending.firstOrNull { it.fingerprint == fingerprint } ?: return
            pending.removeAll { it.fingerprint == fingerprint }
            val now = System.currentTimeMillis()
            trusted[fingerprint] = TrustedDevice(fingerprint, req.name, req.device, now, now)
            saveLocked()
            for ((peerId, fp) in peerFingerprint) {
                if (fp == fingerprint) {
                    trustedPeers.add(peerId)
                    sendResult(peerId, true, "trusted")
                }
            }
            recomputeAllTrustedLocked()
        }
        cancelApprovalNotification(fingerprint)
        notifyChanged()
    }

    fun deny(fingerprint: String) {
        synchronized(lock) {
            deniedFingerprints.add(fingerprint)
            pending.removeAll { it.fingerprint == fingerprint }
            for ((peerId, fp) in peerFingerprint)
                if (fp == fingerprint) sendResult(peerId, false, "denied")
        }
        cancelApprovalNotification(fingerprint)
        notifyChanged()
    }

    /** Delete the fingerprint from the trust store: the device is a stranger
     *  again and re-prompts on its next connect. Kicks live sessions. */
    fun revoke(fingerprint: String) {
        synchronized(lock) {
            trusted.remove(fingerprint)
            saveLocked()
            for ((peerId, fp) in peerFingerprint) {
                if (fp != fingerprint) continue
                trustedPeers.remove(peerId)
                sendResult(peerId, false, "revoked")
            }
            recomputeAllTrustedLocked()
        }
        notifyChanged()
    }

    /** Courtesy hang-up: the peer's viewers close, trust is kept, and it may
     *  reconnect silently. Enforcement stays local (we stop streaming). */
    fun disconnectPeer(peerId: String) = sendResult(peerId, false, "disconnected")

    // ---------- internals ----------

    private fun sendResult(to: String, ok: Boolean, status: String) {
        ConnectionManager.sendJson(
            JSONObject().put("type", "auth-result").put("to", to).put("ok", ok).put("status", status)
        )
    }

    private fun recomputeAllTrustedLocked() {
        allPeersTrusted = peerInfo.keys.all { trustedPeers.contains(it) }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        for (d in trusted.values) {
            arr.put(
                JSONObject()
                    .put("fingerprint", d.fingerprint)
                    .put("name", d.name)
                    .put("device", d.device)
                    .put("firstApproved", d.firstApproved)
                    .put("lastSeen", d.lastSeen)
            )
        }
        appContext.getSharedPreferences("trust", Context.MODE_PRIVATE)
            .edit().putString("pairId", pairId).putString("devices", arr.toString()).apply()
    }

    private fun notifyChanged() {
        main.post { changeListeners.forEach { it() } }
    }

    /** Surface the request even when the app is backgrounded — approval happens
     *  in MainActivity's devices section. */
    private fun showApprovalNotification(req: PendingRequest) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Pairing requests", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                appContext, 0, Intent(appContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val notif = android.app.Notification.Builder(appContext, NOTIF_CHANNEL)
                .setContentTitle("New device wants to connect")
                .setContentText("${req.name} (${shortCode(req.fingerprint)}) — open to approve or deny")
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(notifId(req.fingerprint), notif)
        } catch (e: Exception) {
            Log.w(TAG, "could not post pairing notification", e)
        }
    }

    private fun cancelApprovalNotification(fingerprint: String) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId(fingerprint))
        } catch (_: Exception) {
        }
    }

    private fun notifId(fingerprint: String) = 0x50414952 xor fingerprint.hashCode() // "PAIR"
}
