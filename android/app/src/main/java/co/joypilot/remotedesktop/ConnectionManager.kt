package co.joypilot.remotedesktop

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * App-wide WebSocket connection. Configured once with server URL + session key,
 * then reconnects forever with backoff — no re-authentication, ever.
 */
object ConnectionManager {

    data class Peer(val id: String, val device: String, val name: String)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var enabled = false
    private var backoffMs = 2000L
    private lateinit var appContext: Context

    // Derived from the session key; the raw key never leaves the device.
    private var encKey: ByteArray = ByteArray(0)
    private var pairId: String = ""

    var myId: String? = null
        private set
    var state: String = "disconnected"
        private set
    val peers = CopyOnWriteArrayList<Peer>()

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
        }
    }

    fun start(context: Context) {
        init(context)
        val prefs = Prefs(appContext)
        if (prefs.sessionKey.length < 16 || prefs.serverUrl.isBlank()) {
            setState("disconnected: not configured")
            return
        }
        val (k, pid) = Crypto.deriveKeys(prefs.sessionKey)
        encKey = k
        pairId = pid
        enabled = true
        backoffMs = 2000L
        connect()
    }

    fun stop() {
        enabled = false
        ws?.close(1000, "user stop")
        ws = null
        myId = null
        peers.clear()
        setState("disconnected")
    }

    private fun connect() {
        if (!enabled) return
        val prefs = Prefs(appContext)
        setState("connecting")
        val request = Request.Builder().url(prefs.serverUrl).build()
        ws = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(socket: WebSocket) {
        if (!enabled || socket !== ws) return
        setState("disconnected: retrying...")
        main.postDelayed({ connect() }, backoffMs)
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
            val prefs = Prefs(appContext)
            // hello is cleartext and carries only the derived pairing id.
            webSocket.send(
                JSONObject()
                    .put("type", "hello")
                    .put("session", pairId)
                    .put("device", "android")
                    .put("name", prefs.deviceName)
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
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
            val data = bytes.toByteArray()
            if (data.isEmpty()) return
            val frameType = data[0]
            val plaintext = Crypto.decrypt(encKey, data.copyOfRange(1, data.size), frameType) ?: return
            val full = ByteArray(1 + plaintext.size)
            full[0] = frameType
            System.arraycopy(plaintext, 0, full, 1, plaintext.size)
            when (frameType.toInt()) {
                1 -> binaryListeners.forEach { it(full) }   // video frame from a peer
                2 -> fs.handleFileChunk(full)               // file chunk
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scheduleReconnect(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect(webSocket)
        }
    }

    private fun handleJson(msg: JSONObject) {
        when (msg.optString("type")) {
            "welcome" -> {
                myId = msg.optString("id")
                backoffMs = 2000L
                peers.clear()
                msg.optJSONArray("peers")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        peers.add(Peer(p.getString("id"), p.getString("device"), p.getString("name")))
                    }
                }
                setState("connected")
            }
            "peer-joined" -> {
                val p = msg.getJSONObject("peer")
                peers.add(Peer(p.getString("id"), p.getString("device"), p.getString("name")))
                setState("connected") // nudge peer-dependent UI to refresh
            }
            "peer-left" -> {
                val id = msg.optString("id")
                peers.removeAll { it.id == id }
                setState(state)
            }
            "error" -> setState("error: " + msg.optString("message"))

            // Desktop controlling this phone
            "tap", "swipe", "back", "homebtn", "recents" ->
                InputAccessibilityService.instance?.handle(msg)

            "start-view" -> ScreenCaptureService.onViewRequested(appContext)
            "stop-view" -> ScreenCaptureService.onViewStopped()

            // File system requests/responses
            "fs-list", "fs-get", "fs-begin", "fs-end" -> fs.handleJson(msg)
        }
        main.post { jsonListeners.forEach { it(msg) } }
    }
}
