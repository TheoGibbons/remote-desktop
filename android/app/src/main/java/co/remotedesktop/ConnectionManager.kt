package co.remotedesktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * App-wide WebSocket connection. Configured once with server URL + session key,
 * then reconnects forever with backoff — no re-authentication, ever.
 */
object ConnectionManager {

    data class Peer(val id: String, val device: String, val name: String)
    data class DebugStats(
        val state: String,
        val connectedForMs: Long,
        val receivedWireBytes: Long,
        val receivedMessages: Long,
        val lastReceiveAgoMs: Long?,
        val outgoingQueueBytes: Long,
        val connectionAttempts: Long,
        val failures: Long,
    )

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private var ws: WebSocket? = null
    private var enabled = false
    // A retry is waiting out its backoff, with no attempt in flight.
    @Volatile private var retryScheduled = false
    private var backoffMs = 2000L
    private lateinit var appContext: Context
    private val receivedWireBytes = AtomicLong()
    private val receivedMessages = AtomicLong()
    private val connectionAttempts = AtomicLong()
    private val connectionFailures = AtomicLong()
    @Volatile private var connectedAtMs = 0L
    @Volatile private var lastReceiveAtMs = 0L

    // Derived from the session key; the raw key never leaves the device.
    private var encKey: ByteArray = ByteArray(0)
    private var pairId: String = ""

    var myId: String? = null
        private set
    @Volatile var state: String = "disconnected"
        private set
    /** Wall-clock end of the relay's ban on this network, or 0. While it lasts
     *  the state stays "blocked" across retries instead of "connecting". */
    @Volatile var blockedUntilMs = 0L
        private set
    val peers = CopyOnWriteArrayList<Peer>()

    /** Intent extra: the peer id of the desktop a viewer or file browser opens. */
    const val EXTRA_PEER_ID = "co.remotedesktop.PEER_ID"

    /** The desktop with this peer id, or the first one online when none is named. */
    fun desktop(peerId: String?): Peer? =
        peers.firstOrNull { it.device == "windows" && (peerId == null || it.id == peerId) }

    // UI listeners, invoked on the main thread.
    val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()
    val jsonListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    // Frame listener invoked on the network thread (decode off the UI thread).
    val binaryListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    lateinit var fs: FsHandler
        private set

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            fs = FsHandler(appContext)
            PeerAuth.init(appContext)
            PhoneScreenCapture.init(appContext)
        }
    }

    fun start(context: Context) {
        init(context)
        // Idempotent: the activity, ConnectionService and the accessibility
        // service may all call this — keep the existing socket if we have one.
        if (enabled && ws != null) return
        val prefs = Prefs(appContext)
        if (prefs.sessionKey.length < 16 || prefs.serverUrl.isBlank()) {
            setState("disconnected: not configured")
            return
        }
        val (k, pid) = Crypto.deriveKeys(prefs.sessionKey)
        encKey = k
        pairId = pid
        PeerAuth.onSessionStart(pid) // wipes the trust store if the key changed
        enabled = true
        backoffMs = 2000L
        connect()
    }

    fun stop() {
        PhoneScreenCapture.onViewStopped()
        enabled = false
        retryScheduled = false
        main.removeCallbacks(reconnect)
        ws?.close(1000, "user stop")
        ws = null
        myId = null
        peers.clear()
        PeerAuth.resetConnection()
        if (::fs.isInitialized) fs.abort(reason = "The connection was closed")
        blockedUntilMs = 0L
        setState("disconnected")
    }

    fun debugStats(): DebugStats {
        val now = SystemClock.elapsedRealtime()
        val connectedAt = connectedAtMs
        val lastReceive = lastReceiveAtMs
        return DebugStats(
            state = state,
            connectedForMs = if (state == "connected" && connectedAt > 0) now - connectedAt else 0,
            receivedWireBytes = receivedWireBytes.get(),
            receivedMessages = receivedMessages.get(),
            lastReceiveAgoMs = if (lastReceive > 0) now - lastReceive else null,
            outgoingQueueBytes = ws?.queueSize() ?: 0,
            connectionAttempts = connectionAttempts.get(),
            failures = connectionFailures.get(),
        )
    }

    /** Retries now instead of waiting out the backoff (up to 30 s) — for
     *  something the user is waiting on. An attempt already under way is left
     *  to finish, and a ban from the relay to run out. */
    fun reconnectNow() {
        if (!enabled || !retryScheduled || blockedUntilMs > System.currentTimeMillis()) return
        connect()
    }

    private fun connect() {
        // A connection started directly (retry button, settings change) replaces
        // a scheduled retry, which would otherwise open a second socket later.
        main.removeCallbacks(reconnect)
        retryScheduled = false
        if (!enabled) return
        connectionAttempts.incrementAndGet()
        val prefs = Prefs(appContext)
        var url = prefs.serverUrl.trim()

        // The relay server expects the /ws path. Append it if missing.
        val hostStart = url.indexOf("://").let { if (it == -1) 0 else it + 3 }
        val pathStart = url.indexOf("/", hostStart)
        if (pathStart == -1 || pathStart == url.length - 1) {
            url = url.removeSuffix("/") + "/ws"
        }

        Log.d("ConnectionManager", "Connecting to $url")
        // The relay refuses a banned network on every retry; keep saying so
        // rather than flashing "connecting".
        if (blockedUntilMs <= System.currentTimeMillis()) setState("connecting")
        try {
            val request = Request.Builder().url(url).build()
            ws = client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.e("ConnectionManager", "Failed to create WebSocket request for URL: $url", e)
            setState("error: invalid URL")
        }
    }

    private fun scheduleReconnect(socket: WebSocket) {
        if (!enabled || socket !== ws) return
        PhoneScreenCapture.onViewStopped()
        peers.clear()
        PeerAuth.resetConnection()
        // Transfers on the old socket can never finish; say so instead of waiting forever.
        if (::fs.isInitialized) fs.abort(reason = "The connection was lost")
        // A refusal from the relay says more than "retrying"; keep showing it.
        setState(if (state == "blocked" || state.startsWith("error")) state else "disconnected: retrying...")
        main.postDelayed(reconnect, backoffMs)
        retryScheduled = true
        backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
    }

    private fun setState(s: String) {
        state = s
        main.post { stateListeners.forEach { it(s) } }
    }

    /** Send a control message, encrypted end-to-end (the relay sees only the
     *  routing envelope). `to` is lifted into the cleartext envelope. */
    fun sendJson(obj: JSONObject) {
        val socket = ws ?: return
        val blob = Crypto.encrypt(encKey, obj.toString().toByteArray(), Crypto.CH_JSON)
        val env = JSONObject().put("type", "enc").put("d", Base64.encodeToString(blob, Base64.NO_WRAP))
        obj.optString("to").takeIf { it.isNotEmpty() }?.let { env.put("to", it) }
        socket.send(env.toString())
    }

    /** Send an encrypted binary frame (frameType 1 = video, 2 = file chunk). */
    fun sendBinary(frameType: Byte, payload: ByteArray): Boolean {
        val socket = ws ?: return false
        // Don't queue unboundedly if the link is slower than the frame rate.
        if (socket.queueSize() > 6_000_000) return false
        val blob = Crypto.encrypt(encKey, payload, frameType)
        val wire = ByteArray(1 + blob.size)
        wire[0] = frameType
        System.arraycopy(blob, 0, wire, 1, blob.size)
        return socket.send(ByteString.of(*wire))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("ConnectionManager", "WebSocket opened")
            val prefs = Prefs(appContext)
            // hello is cleartext and carries only the derived pairing id.
            webSocket.send(
                JSONObject()
                    .put("type", "hello")
                    .put("session", pairId)
                    .put("device", "android")
                    .put("name", prefs.deviceName)
                    .put("uid", prefs.deviceUid)
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            receivedWireBytes.addAndGet(text.toByteArray(Charsets.UTF_8).size.toLong())
            receivedMessages.incrementAndGet()
            lastReceiveAtMs = SystemClock.elapsedRealtime()
            val msg = try { JSONObject(text) } catch (e: Exception) { return }
            if (msg.optString("type") == "enc") {
                val blob = try { Base64.decode(msg.optString("d"), Base64.NO_WRAP) } catch (e: Exception) { return }
                val pt = Crypto.decrypt(encKey, blob, Crypto.CH_JSON) ?: return
                val inner = try { JSONObject(String(pt)) } catch (e: Exception) { return }
                inner.put("from", msg.optString("from"))
                handleJson(inner)
            } else {
                handleJson(msg) // cleartext server messages (welcome / peer-* / error)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            receivedWireBytes.addAndGet(bytes.size.toLong())
            receivedMessages.incrementAndGet()
            lastReceiveAtMs = SystemClock.elapsedRealtime()
            val data = bytes.toByteArray()
            if (data.isEmpty()) return
            val frameType = data[0]
            val plaintext = Crypto.decrypt(encKey, data.copyOfRange(1, data.size), frameType) ?: return
            val full = ByteArray(1 + plaintext.size)
            full[0] = frameType
            System.arraycopy(plaintext, 0, full, 1, plaintext.size)
            when (frameType.toInt()) {
                1 -> binaryListeners.forEach { it(full) }   // full video frame from a peer
                2 -> fs.handleFileChunk(full)               // file chunk
                3 -> binaryListeners.forEach { it(full) }   // dirty-rect screen patch
                4 -> binaryListeners.forEach { it(full) }   // H.264 access unit
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connectionFailures.incrementAndGet()
            Log.e("ConnectionManager", "WebSocket failure: ${t.message}", t)
            response?.let {
                Log.e("ConnectionManager", "Response code: ${it.code}, message: ${it.message}")
            }
            scheduleReconnect(webSocket)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Answer the relay's close so onClosed, and the reconnect, follow at
            // once. Unanswered, the socket sat until a ping went unanswered
            // (~40 s), so every refusal looked like a stalled connection.
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("ConnectionManager", "WebSocket closed: $code / $reason")
            scheduleReconnect(webSocket)
        }
    }

    private fun handleJson(msg: JSONObject) {
        val from = msg.optString("from")
        when (msg.optString("type")) {
            "welcome" -> {
                PhoneScreenCapture.onViewStopped()
                myId = msg.optString("id")
                connectedAtMs = SystemClock.elapsedRealtime()
                backoffMs = 2000L
                peers.clear()
                PeerAuth.resetConnection() // new connection, stale peer ids
                msg.optJSONArray("peers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        peers.add(Peer(p.getString("id"), p.getString("device"), p.getString("name")))
                    }
                }
                peers.forEach { PeerAuth.onPeerJoined(it.id, it.name, it.device) }
                blockedUntilMs = 0L
                setState("connected")
            }
            "peer-joined" -> {
                val p = msg.getJSONObject("peer")
                val peer = Peer(p.getString("id"), p.getString("device"), p.getString("name"))
                peers.add(peer)
                PeerAuth.onPeerJoined(peer.id, peer.name, peer.device)
                main.post {
                    android.widget.Toast.makeText(
                        appContext, "${peer.name} (${peer.device}) is online",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                setState("connected") // nudge peer-dependent UI to refresh
            }
            "peer-left" -> {
                val id = msg.optString("id")
                PhoneScreenCapture.onViewStopped(id)
                val name = peerName(id)
                peers.removeAll { it.id == id }
                PeerAuth.onPeerLeft(id)
                if (::fs.isInitialized) fs.abort(id, "$name disconnected")
                setState(state)
            }
            "error" -> {
                // The relay is refusing this connection and closes it next. A ban
                // says how long it lasts (retryAfter, in seconds).
                val retryAfterS = msg.optLong("retryAfter")
                if (retryAfterS > 0) {
                    blockedUntilMs = System.currentTimeMillis() + retryAfterS * 1000
                    setState("blocked")
                } else {
                    setState("error: " + msg.optString("message"))
                }
            }

            // Device authentication handshake (see PROTOCOL.md).
            "auth-challenge", "auth-response" -> PeerAuth.handleJson(msg)
            "auth-result" -> onAuthResult(from, msg.optString("status"))

            // Desktop controlling this phone — only with the user's consent
            // (the toggles in MainActivity) AND from a device this phone has
            // approved. The session key alone must not grant control.
            "tap", "swipe", "touch", "pinch", "back", "homebtn", "recents", "text", "key" ->
                if (Prefs(appContext).allowControl && PeerAuth.isTrusted(from))
                    InputAccessibilityService.instance?.handle(msg)

            "start-view" ->
                if (Prefs(appContext).allowControl && PeerAuth.isTrusted(from))
                    PhoneScreenCapture.onViewRequested(appContext, from)
            "stop-view" ->
                if (PeerAuth.isTrusted(from)) PhoneScreenCapture.onViewStopped(from)

            // File system requests/responses. Chunks are broadcast like video,
            // so serving also requires that no unapproved peer is present.
            "fs-list", "fs-get", "fs-begin", "fs-end", "fs-cancel" ->
                fs.handleJson(msg, peerAccessAllowed = Prefs(appContext).allowFileAccess &&
                    PeerAuth.isTrusted(from) && PeerAuth.allPeersTrusted)
        }
        main.post { jsonListeners.forEach { it(msg) } }
    }

    /** A peer told us where we stand with it (this phone is the viewer here).
     *  ViewerActivity reacts via jsonListeners (re-requests or closes). */
    private fun onAuthResult(from: String, status: String) {
        PeerAuth.onResultFrom(from, status)
        // A full hang-up: also stop streaming this phone's own screen to them.
        if (status == "revoked" || status == "disconnected") PhoneScreenCapture.onViewStopped(from)
        val text = when (status) {
            "pending" -> "${peerName(from)} is asking its user to approve this phone — " +
                "code ${PeerAuth.shortCode(PeerAuth.ownFingerprint)}"
            "denied" -> "${peerName(from)} denied this phone access"
            "revoked" -> "${peerName(from)} revoked this phone's access"
            "disconnected" -> "${peerName(from)} ended this phone's session"
            else -> return
        }
        main.post { android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_LONG).show() }
    }

    private fun peerName(id: String): String =
        peers.firstOrNull { it.id == id }?.name ?: "A paired device"
}
