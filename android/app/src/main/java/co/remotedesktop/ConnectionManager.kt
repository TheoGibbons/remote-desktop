package co.remotedesktop

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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

    /** A device online in the session. [version] is null for apps from before devices announced it. */
    data class Peer(val id: String, val device: String, val name: String, val version: String? = null) {
        companion object {
            fun from(p: JSONObject) = Peer(p.getString("id"), p.getString("device"), p.getString("name"),
                p.optString("version").ifEmpty { null })
        }
    }
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

    /** OkHttp pings the relay this often and gives up when the previous ping
     *  is still unanswered, so a silently dead link costs 20-40 s. */
    const val PING_INTERVAL_S = 20L

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
        // Small packets go at once instead of waiting for the one before to be acknowledged.
        .socketFactory(NoDelaySocketFactory())
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

    // For the connection history (ConnectionLog).
    /** The socket the relay admitted (welcome), so its end counts as a drop, not a failed try. */
    @Volatile private var admittedSocket: WebSocket? = null
    /** When the phone went offline (elapsed realtime), until it is admitted again. */
    @Volatile private var offlineSinceMs = 0L
    /** The relay's reason for refusing the socket it is about to close. */
    @Volatile private var refusal: String? = null
    private var loggedProcessStart = false
    private var networkWatch: ConnectivityManager? = null
    private var networkName: String? = null

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
    /** The relay delivers an addressed binary frame to just the peers it names.
     *  Learned from this connection's welcome; older relays broadcast. */
    @Volatile private var relayAddressesBinary = false
    val peers = CopyOnWriteArrayList<Peer>()

    /** Intent extra: the peer id of the device a viewer or file browser opens. */
    const val EXTRA_PEER_ID = "co.remotedesktop.PEER_ID"

    /** The desktop with this peer id, or the first one online when none is named. */
    fun desktop(peerId: String?): Peer? =
        peers.firstOrNull { it.device == "windows" && (peerId == null || it.id == peerId) }

    /** The device with this peer id, PC or phone, or the first PC online when none is named. */
    fun peer(peerId: String?): Peer? =
        if (peerId == null) desktop(null) else peers.firstOrNull { it.id == peerId }

    /** What a peer announced itself as ("windows", "android"), or null if it isn't online. */
    fun deviceOf(peerId: String?): String? = peers.firstOrNull { it.id == peerId }?.device

    /** Above this many queued bytes a picture is skipped rather than sent, so
     *  a slow uplink shows a recent picture instead of seconds-old ones. It is
     *  less than one picture: the one before must have left the app's queue.
     *  (It was 300 KB, which let two or three wait here on top of the megabyte
     *  or more that waits in the system's own buffer, where the app can't see.) */
    const val VIDEO_QUEUE_LIMIT = 64_000L

    /** Whether a video frame sent now would go out rather than be skipped. */
    fun hasRoomForVideo(): Boolean = (ws?.queueSize() ?: 0L) <= VIDEO_QUEUE_LIMIT

    // UI listeners, invoked on the main thread.
    val stateListeners = CopyOnWriteArrayList<(String) -> Unit>()
    val jsonListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    // Invoked on the network thread, before jsonListeners and in order with
    // binary frames: for a message that says how to read the frames after it.
    val networkJsonListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    // Frame listener invoked on the network thread (decode off the UI thread).
    val binaryListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()

    lateinit var fs: FsHandler
        private set

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
            ConnectionLog.init(appContext) { main.post(it) }
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
        // A gap before this line in the history means Android stopped the app.
        if (!loggedProcessStart) {
            loggedProcessStart = true
            record(ConnectionLog.Kind.STARTED)
        }
        watchNetwork()
        CaptureOffers.start(appContext)
        connect()
    }

    fun stop() {
        if (enabled) record(ConnectionLog.Kind.STOPPED)
        unwatchNetwork()
        offlineSinceMs = 0L
        admittedSocket = null
        // Stopped by this phone's owner: whoever views next is announced, also one that says it is only back.
        PhoneScreenCapture.onViewStopped(forGood = true)
        CaptureOffers.stop()
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
        refusal = null
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

    private fun scheduleReconnect(socket: WebSocket, reason: String, detail: String?) {
        if (!enabled || socket !== ws) return
        val now = SystemClock.elapsedRealtime()
        if (admittedSocket === socket) {
            admittedSocket = null
            record(ConnectionLog.Kind.LOST, reason, detail, durationMs = now - connectedAtMs)
            offlineSinceMs = now
        } else {
            record(ConnectionLog.Kind.FAILED, reason, detail)
            if (offlineSinceMs == 0L) offlineSinceMs = now
        }
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

    private fun record(kind: ConnectionLog.Kind, reason: String? = null, detail: String? = null, durationMs: Long = 0) =
        ConnectionLog.add(ConnectionLog.Entry(kind, System.currentTimeMillis(), reason, detail,
            currentNetwork(), durationMs))

    private fun currentNetwork(): String? {
        if (!::appContext.isInitialized) return null
        return try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }?.let(ConnectionLog::describe) ?: "No network"
        } catch (e: Exception) {
            null
        }
    }

    // Callbacks arrive on the main thread, like the scheduled retry.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = try { networkWatch?.getNetworkCapabilities(network) } catch (e: Exception) { null }
            noteNetwork(caps?.let(ConnectionLog::describe))
            retrySoon()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Also called as the signal changes; only a different network counts,
            // e.g. a VPN moving from Wi-Fi to mobile data.
            if (noteNetwork(ConnectionLog.describe(caps))) retrySoon()
        }

        override fun onLost(network: Network) {
            noteNetwork("No network")
        }
    }

    private fun watchNetwork() {
        if (networkWatch != null) return
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(networkCallback, main)
            networkWatch = cm
        } catch (e: Exception) {
            Log.w("ConnectionManager", "Can't watch the network", e)
        }
    }

    private fun unwatchNetwork() {
        try { networkWatch?.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        networkWatch = null
        networkName = null
    }

    /** Records a change of network in the history. True if the phone moved
     *  to a different one; the first report is just where it already is. */
    private fun noteNetwork(name: String?): Boolean {
        if (name == null || name == networkName) return false
        val previous = networkName
        networkName = name
        if (previous == null) return false
        ConnectionLog.add(ConnectionLog.Entry(ConnectionLog.Kind.NETWORK, System.currentTimeMillis(),
            reason = name, detail = "was $previous"))
        return true
    }

    /** A network came up: retry now instead of waiting out a backoff of up to 30 s. */
    private fun retrySoon() {
        if (!enabled) return
        backoffMs = 2000L
        reconnectNow()
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

    /** Send an encrypted binary frame (frameType 1 or 5 = a picture of this
     *  phone's screen, 2 = file chunk). With [to], a relay that supports it
     *  delivers the frame only to those peers; otherwise, and on older
     *  relays, it is broadcast. */
    fun sendBinary(frameType: Byte, payload: ByteArray, to: Collection<String>? = null,
                   always: Boolean = false): Boolean {
        val socket = ws ?: return false
        // Don't queue unboundedly if the link is slower than the frame rate.
        // Video is skipped much sooner: a newer picture replaces it anyway.
        // Not so an H.264 picture ([always]): the ones after it are built on it,
        // so it was held back before it was encoded and is never skipped after.
        val video = frameType == Crypto.CH_VIDEO || frameType == Crypto.CH_PHONE
        if (!always && socket.queueSize() > if (video) VIDEO_QUEUE_LIMIT else 6_000_000L) return false
        val blob = Crypto.encrypt(encKey, payload, frameType)
        val route = if (!to.isNullOrEmpty() && relayAddressesBinary) addressHeader(to) else null
        val wire = ByteArray(1 + (route?.size ?: 0) + blob.size)
        // The relay strips the header and clears the flag, so the receiver
        // decrypts against the plain frame type it always has.
        wire[0] = if (route == null) frameType else (frameType.toInt() or ADDRESSED).toByte()
        route?.copyInto(wire, 1)
        System.arraycopy(blob, 0, wire, 1 + (route?.size ?: 0), blob.size)
        return socket.send(ByteString.of(*wire))
    }

    private const val ADDRESSED = 0x80

    /** [count u8] then count x [idLen u8][id], as PROTOCOL.md lays out. */
    private fun addressHeader(to: Collection<String>): ByteArray {
        val ids = to.take(255).map { it.toByteArray(Charsets.UTF_8) }.filter { it.size in 1..255 }
        val header = ByteArray(1 + ids.sumOf { 1 + it.size })
        header[0] = ids.size.toByte()
        var off = 1
        for (id in ids) {
            header[off++] = id.size.toByte()
            id.copyInto(header, off)
            off += id.size
        }
        return header
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("ConnectionManager", "WebSocket opened")
            relayAddressesBinary = false // until this relay's welcome says otherwise
            val prefs = Prefs(appContext)
            // hello is cleartext and carries only the derived pairing id.
            webSocket.send(
                JSONObject()
                    .put("type", "hello")
                    .put("session", pairId)
                    .put("device", "android")
                    .put("name", prefs.deviceName)
                    .put("uid", prefs.deviceUid)
                    // Shown to the other devices, so they can tell when this one is behind.
                    .put("version", BuildConfig.VERSION_NAME)
                    .toString()
            )
        }

        // One message that can't be read, or that something downstream
        // chokes on, is one message lost. An exception let out of onMessage
        // makes OkHttp fail the socket: any device in the session could then
        // knock this phone off the relay at will.
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                receive(webSocket, text)
            } catch (e: Exception) {
                Log.w("ConnectionManager", "Skipped a message: $e")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                receive(bytes)
            } catch (e: Exception) {
                Log.w("ConnectionManager", "Skipped a frame: $e")
            }
        }

        private fun receive(webSocket: WebSocket, text: String) {
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
                // Not encrypted, so the relay's own (welcome / peer-* / error /
                // latest). The relay puts a sender on everything it passes on
                // from a device, and the apps speak to each other encrypted
                // only. So one with a sender is a device posing as the relay,
                // or trying to be heard without the session key.
                if (msg.has("from")) return
                if (msg.optString("type") == "welcome") admittedSocket = webSocket
                handleJson(msg)
            }
        }

        private fun receive(bytes: ByteString) {
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
                5 -> binaryListeners.forEach { it(full) }   // a phone's picture behind its header
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connectionFailures.incrementAndGet()
            Log.e("ConnectionManager", "WebSocket failure: ${t.message}", t)
            response?.let {
                Log.e("ConnectionManager", "Response code: ${it.code}, message: ${it.message}")
            }
            scheduleReconnect(webSocket, ConnectionLog.reasonFor(t, response?.code), ConnectionLog.detailFor(t))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Answer the relay's close so onClosed, and the reconnect, follow at
            // once. Unanswered, the socket sat until a ping went unanswered
            // (~40 s), so every refusal looked like a stalled connection.
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("ConnectionManager", "WebSocket closed: $code / $reason")
            scheduleReconnect(webSocket,
                refusal?.let { "Refused by the relay: $it" } ?: "The relay closed the connection",
                "Close code $code" + if (reason.isNotEmpty()) " ($reason)" else "")
        }
    }

    /** What only the relay says: who is in the session, and its own refusals and news. */
    private val RELAY_ONLY = setOf("welcome", "peer-joined", "peer-left", "error", "latest")

    private fun handleJson(msg: JSONObject) {
        val from = msg.optString("from")
        // From a device, these are a device posing as the relay, whether or
        // not it encrypted them. Nobody acts on them, the screens included.
        if (from.isNotEmpty() && msg.optString("type") in RELAY_ONLY) return
        when (msg.optString("type")) {
            "welcome" -> {
                PhoneScreenCapture.onViewStopped()
                myId = msg.optString("id")
                relayAddressesBinary = msg.optBoolean("binaryTo", false)
                connectedAtMs = SystemClock.elapsedRealtime()
                record(ConnectionLog.Kind.CONNECTED,
                    durationMs = if (offlineSinceMs > 0) connectedAtMs - offlineSinceMs else 0)
                offlineSinceMs = 0L
                backoffMs = 2000L
                peers.clear()
                PeerAuth.resetConnection() // new connection, stale peer ids
                msg.optJSONArray("peers")?.let { arr ->
                    for (i in 0 until arr.length()) peers.add(Peer.from(arr.getJSONObject(i)))
                }
                peers.forEach { PeerAuth.onPeerJoined(it.id, it.name, it.device) }
                AppUpdate.onLatest(appContext, msg.optString("latest").ifEmpty { null })
                blockedUntilMs = 0L
                setState("connected")
            }
            // A new release is out. Only the relay's own word counts: a peer's message has a sender.
            "latest" -> if (from.isEmpty()) {
                AppUpdate.onLatest(appContext, msg.optString("version"))
                setState(state) // PCs that are now behind get their Update button
            }
            "peer-joined" -> {
                val peer = Peer.from(msg.getJSONObject("peer"))
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
                refusal = msg.optString("message").ifEmpty { msg.optString("code") }
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

            // A PC or phone controlling this phone — only with the user's consent
            // (Permissions & Access) AND from a device this phone has approved.
            // The session key alone must not grant control.
            "tap", "swipe", "touch", "pinch", "back", "homebtn", "recents", "notifications", "text", "key",
            "quick-settings", "screenshot", "lock" ->
                if (mayControl(from)) {
                    // A viewer's input keeps the screen on in smooth sharing, and its Lock lets go.
                    if (PhoneScreenCapture.isViewing(from)) ScreenCaptureService.viewerActed(msg)
                    InputAccessibilityService.instance?.handle(msg)
                }

            // The rest of a viewer's menu. Not input: these never wake the phone.
            "volume", "get-phone-state" ->
                if (mayControl(from)) PhoneControls.handle(appContext, from, msg)
            // Turning the screen is for whoever is looking at it, and is undone when they stop.
            "rotate" ->
                if (mayControl(from) && PhoneScreenCapture.isViewing(from)) PhoneControls.handle(appContext, from, msg)

            "start-view" ->
                if (mayControl(from))
                    PhoneScreenCapture.onViewRequested(appContext, from, codecs(msg), msg.optBoolean("resume", false))
            "stop-view" ->
                if (PeerAuth.isTrusted(from)) PhoneScreenCapture.onViewStopped(from, byViewer = true)

            // Starting to capture the screen because a message asked: SmoothSharing
            // decides who may, from each of these facts about the sender.
            "request-smooth", "stop-smooth" -> {
                val prefs = Prefs(appContext)
                SmoothPrompt.handle(appContext, from, msg.optString("type"), SmoothSharing.Asker(
                    approved = PeerAuth.isTrusted(from),
                    controlOn = prefs.allowControl,
                    kindAllowed = prefs.allowsDevice(deviceOf(from)),
                    viewing = PhoneScreenCapture.isViewing(from),
                ))
            }

            // About the pictures this phone is sending, so only from a device it is sending them to.
            "frame-ack" ->
                if (mayControl(from) && PhoneScreenCapture.isViewing(from)) PhoneScreenCapture.onFrameAck(from, msg)
            "request-keyframe" ->
                if (mayControl(from) && PhoneScreenCapture.isViewing(from)) PhoneScreenCapture.onKeyframeRequest(from, msg)
            "diagnostic-ping" ->
                if (mayControl(from) && PhoneScreenCapture.isViewing(from))
                    sendJson(PhoneScreenCapture.pong(msg.optLong("nonce", -1), ws?.queueSize() ?: 0).put("to", from))

            // File system requests/responses. Older relays broadcast chunks like
            // video, so serving also requires that no unapproved peer is present.
            "fs-list", "fs-get", "fs-begin", "fs-end", "fs-cancel" -> {
                val prefs = Prefs(appContext)
                val shutOut = !prefs.allowsDevice(deviceOf(from))
                fs.handleJson(msg, peerAccessAllowed = prefs.allowFileAccess && !shutOut &&
                    PeerAuth.isTrusted(from) && PeerAuth.allPeersTrusted,
                    deniedReason = if (shutOut) phonesOffReason(prefs) else null)
            }
            "get-file-access" -> if (PeerAuth.isTrusted(from)) sendFileAccess(from)

            // A PC this phone asked to update says how it's going.
            "update-status" -> AppUpdate.statusText(appContext, peerName(from), from, msg)?.let { text ->
                main.post { android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_LONG).show() }
                setState(state) // the Update button comes back if it failed
            }
        }
        networkJsonListeners.forEach { it(msg) }
        main.post { jsonListeners.forEach { it(msg) } }
    }

    /** What a viewer's `start-view` says it can draw; nothing, from an app before type 5 frames. */
    private fun codecs(msg: JSONObject): Set<String> {
        val list = msg.optJSONArray("codecs") ?: return emptySet()
        return (0 until list.length()).mapNotNull { list.opt(it) as? String }.toSet()
    }

    private fun mayControl(from: String): Boolean {
        val prefs = Prefs(appContext)
        return prefs.allowControl && PeerAuth.isTrusted(from) && prefs.allowsDevice(deviceOf(from))
    }

    /** Why another phone can't browse this one. */
    private fun phonesOffReason(prefs: Prefs) = "Allow other phones is off on ${prefs.deviceName}"

    /** Answers get-file-access as a PC does, so the asker knows whether it may
     *  upload here. `phones` tells another phone whether it may open this one. */
    private fun sendFileAccess(to: String) {
        val prefs = Prefs(appContext)
        sendJson(JSONObject().put("type", "file-access").put("to", to)
            .put("allowed", prefs.allowFileAccess && prefs.allowsDevice(deviceOf(to)) && PeerAuth.allPeersTrusted)
            .put("phones", prefs.allowPhones))
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
