package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Full-screen viewer for controlling the paired Windows desktop. Renders the
 * stitched multi-monitor stream and turns touch + the on-screen keyboard into
 * remote input.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var screen: RemoteScreenView
    private lateinit var keyboard: KeyboardPanel
    private lateinit var imeCatcher: EditText
    private lateinit var root: FrameLayout
    private lateinit var diagnosticsPanel: LinearLayout
    private lateinit var diagnosticsText: TextView
    private var winId: String? = null
    private var imePrev = ""
    private var imeGuard = false
    private var imeVisible = false
    private val streamStats = ViewerStreamStats()
    private val diagnosticsHandler = Handler(Looper.getMainLooper())
    private var nextPingNonce = 0L
    private var pendingPingNonce = -1L
    private var pingSentAtMs = 0L
    private var lastRttMs: Long? = null
    private var pingTimedOut = false
    private var hostQueueBytes: Long? = null
    private var hostTargetFps: Int? = null
    private var hostJpegQuality: Int? = null
    private var hostMaxWidth: Int? = null
    private var diagnosticsUpdater: Runnable? = null

    // Dirty-rect compositing state (see PROTOCOL.md, binary frame type 3).
    // Touched only on the network thread that delivers binary frames.
    private var compose: Bitmap? = null
    private var composeCanvas: android.graphics.Canvas? = null
    private var lastSeq = -1L
    private var haveKeyframe = false
    @Volatile private var lastKeyframeRequestAt = 0L

    private val binaryListener: (ByteArray) -> Unit = { data ->
        if (data.isNotEmpty()) when (data[0].toInt()) {
            1 -> { // legacy full-frame JPEG
                val started = SystemClock.elapsedRealtimeNanos()
                val bmp = BitmapFactory.decodeByteArray(data, 1, data.size - 1)
                if (bmp != null) {
                    screen.setFrame(bmp)
                    streamStats.recordFrame(
                        bytes = data.size,
                        rectCount = 1,
                        changedPixels = bmp.width.toLong() * bmp.height,
                        decodeTimeNanos = SystemClock.elapsedRealtimeNanos() - started,
                        isKeyframe = true,
                        hadSequenceGap = false,
                        width = bmp.width,
                        height = bmp.height,
                    )
                } else streamStats.recordDecodeError()
            }
            3 -> handlePatch(data)
        }
    }

    /**
     * [seq u32][flags u8: bit0 keyframe][surfW u16][surfH u16][rectCount u16]
     * then per rect [x u16][y u16][w u16][h u16][jpegLen u32][JPEG bytes].
     * Tiles are absolute pixel content, so a lost patch only leaves regions
     * stale — keep painting and ask the host for a keyframe to catch up.
     */
    private fun handlePatch(data: ByteArray) {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            val buf = java.nio.ByteBuffer.wrap(data, 1, data.size - 1)
            val seq = buf.int.toLong() and 0xFFFFFFFFL
            val keyframe = (buf.get().toInt() and 1) != 0
            val w = buf.short.toInt() and 0xFFFF
            val h = buf.short.toInt() and 0xFFFF
            val rectCount = buf.short.toInt() and 0xFFFF

            var bmp = compose
            if (bmp == null || bmp.width != w || bmp.height != h) {
                if (!keyframe) { requestKeyframe(); return } // can't composite yet
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                compose = bmp
                composeCanvas = android.graphics.Canvas(bmp)
                haveKeyframe = false
            }
            val hadSequenceGap = !keyframe && haveKeyframe &&
                seq != ((lastSeq + 1L) and 0xFFFFFFFFL)
            if (keyframe) haveKeyframe = true
            else if (!haveKeyframe || hadSequenceGap) requestKeyframe()
            lastSeq = seq

            val canvas = composeCanvas!!
            val dirtyRects = ArrayList<Rect>(rectCount)
            var changedPixels = 0L
            repeat(rectCount) {
                val x = buf.short.toInt() and 0xFFFF
                val y = buf.short.toInt() and 0xFFFF
                val tileW = buf.short.toInt() and 0xFFFF
                val tileH = buf.short.toInt() and 0xFFFF
                val len = buf.int
                if (len < 0 || len > buf.remaining()) throw IllegalArgumentException("Invalid patch length")
                val pos = buf.position()
                buf.position(pos + len)
                val tile = BitmapFactory.decodeByteArray(data, pos, len)
                    ?: throw IllegalArgumentException("Invalid JPEG tile")
                canvas.drawBitmap(tile, x.toFloat(), y.toFloat(), null)
                tile.recycle()
                val right = (x + tileW).coerceAtMost(w)
                val bottom = (y + tileH).coerceAtMost(h)
                if (x < right && y < bottom) {
                    dirtyRects.add(Rect(x, y, right, bottom))
                    changedPixels += (right - x).toLong() * (bottom - y)
                }
            }
            screen.setFrame(bmp, dirtyRects)
            streamStats.recordFrame(
                bytes = data.size,
                rectCount = dirtyRects.size,
                changedPixels = changedPixels,
                decodeTimeNanos = SystemClock.elapsedRealtimeNanos() - started,
                isKeyframe = keyframe,
                hadSequenceGap = hadSequenceGap,
                width = w,
                height = h,
            )
        } catch (e: Exception) {
            streamStats.recordDecodeError()
            requestKeyframe()
        }
    }

    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestAt < 2000) return
        lastKeyframeRequestAt = now
        winId?.let {
            ConnectionManager.sendJson(JSONObject().put("type", "request-keyframe").put("to", it))
        }
    }

    private val jsonListener: (JSONObject) -> Unit = { msg ->
        if (msg.optString("type") == "peer-left" && msg.optString("id") == winId) {
            Toast.makeText(this, "Desktop disconnected", Toast.LENGTH_SHORT).show()
            finish()
        }
        if (msg.optString("type") == "auth-result" && msg.optString("from") == winId) {
            when (msg.optString("status")) {
                // Approved: re-request the stream — the start-view sent while we
                // were still unapproved was dropped by the desktop.
                "trusted" ->
                    winId?.let { ConnectionManager.sendJson(JSONObject().put("type", "start-view").put("to", it)) }
                "denied", "revoked", "disconnected" -> finish()
            }
        }
        if (msg.optString("type") == "diagnostic-pong" && msg.optString("from") == winId &&
            msg.optLong("nonce", -1L) == pendingPingNonce
        ) {
            lastRttMs = SystemClock.elapsedRealtime() - pingSentAtMs
            hostQueueBytes = msg.optLong("hostQueueBytes", -1L).takeIf { it >= 0 }
            hostTargetFps = msg.optInt("targetFps", -1).takeIf { it > 0 }
            hostJpegQuality = msg.optInt("jpegQuality", -1).takeIf { it > 0 }
            hostMaxWidth = msg.optInt("maxWidth", -1).takeIf { it >= 0 }
            pendingPingNonce = -1L
            pingTimedOut = false
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.init(this)
        winId = ConnectionManager.peers.firstOrNull { it.device == "windows" }?.id
        if (winId == null) {
            Toast.makeText(this, "No desktop is online in this session", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        root = FrameLayout(this)
        screen = RemoteScreenView(this)
        val debugPrefs = getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
        screen.setDirtyRectHighlightsEnabled(debugPrefs.getBoolean(PREF_HIGHLIGHT_DIRTY, false))
        root.addView(screen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        wireGestures()

        // Keyboard panel (hidden until toggled).
        keyboard = KeyboardPanel(this).apply {
            visibility = View.GONE
            onKey = { code, action -> send(JSONObject().put("type", "key").put("code", code).put("action", action)) }
            onText = { text -> send(JSONObject().put("type", "text").put("text", text)) }
            onOpenIme = { toggleIme() }
        }
        root.addView(keyboard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        diagnosticsPanel = buildDiagnosticsPanel()
        val panelMargin = (8 * resources.displayMetrics.density).toInt()
        val panelWidth = minOf(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            (460 * resources.displayMetrics.density).toInt(),
        )
        root.addView(diagnosticsPanel, FrameLayout.LayoutParams(
            panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = panelMargin
            topMargin = panelMargin
        })

        root.addView(buildToolbar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        imeCatcher = buildImeCatcher()
        root.addView(imeCatcher, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
        installVisibleAreaTracking()
    }

    private fun buildToolbar(): LinearLayout {
        fun tb(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setPadding(20, 8, 20, 8)
            alpha = 0.85f
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#88000000"))
            addView(tb("⌨") {
                keyboard.visibility = if (keyboard.visibility == View.VISIBLE) {
                    keyboard.releaseAll(); View.GONE
                } else View.VISIBLE
                keyboard.post { updateBottomOcclusion() }
            })
            addView(tb("Stats") { showDiagnostics() })
            addView(tb("?") { showGestureHelp() })
            addView(tb("Ctrl+Alt+Del") { ctrlAltDel() })
            addView(tb("✕") { finish() })
        }
    }

    private fun wireGestures() {
        screen.onMouseMove = { x, y ->
            send(JSONObject().put("type", "mouse").put("action", "move").put("x", x).put("y", y))
        }
        screen.onMouseButton = { button, action, x, y ->
            send(JSONObject().put("type", "mouse").put("action", action).put("button", button).put("x", x).put("y", y))
        }
        screen.onWheel = { dx, dy -> send(JSONObject().put("type", "scroll").put("dx", dx).put("dy", dy)) }
    }

    private fun showGestureHelp() {
        AlertDialog.Builder(this)
            .setTitle("Mouse gestures")
            .setMessage(
                """
                Move pointer — drag with one finger
                Left-click — tap with one finger
                Left-click drag — press and hold, then drag
                Right-click — tap with two fingers
                Pan — drag with two fingers
                Zoom — pinch with two fingers
                Mouse wheel — drag up or down with three fingers
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun ctrlAltDel() {
        // The real Ctrl+Alt+Del is a secure sequence Windows won't let apps
        // inject; the host answers `cad` with SendSAS when policy allows it
        // and opens Task Manager otherwise.
        send(JSONObject().put("type", "cad"))
        Toast.makeText(this, "Sent Ctrl+Alt+Del (opens Task Manager on most PCs)", Toast.LENGTH_SHORT).show()
    }

    /** Hidden field that funnels the phone's native IME into remote text/keys. */
    private fun buildImeCatcher(): EditText {
        return EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            alpha = 0f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (imeGuard) return
                    val cur = s?.toString() ?: ""
                    diffAndSend(imePrev, cur)
                    imePrev = cur
                    if (cur.length > 512) { // keep the buffer from growing unbounded
                        imeGuard = true; text?.clear(); imePrev = ""; imeGuard = false
                    }
                }
            })
        }
    }

    private fun diffAndSend(prev: String, cur: String) {
        var p = 0
        val min = minOf(prev.length, cur.length)
        while (p < min && prev[p] == cur[p]) p++
        repeat(prev.length - p) {
            send(JSONObject().put("type", "key").put("code", "BACKSPACE").put("action", "press"))
        }
        val added = cur.substring(p)
        val buf = StringBuilder()
        for (ch in added) {
            if (ch == '\n') {
                if (buf.isNotEmpty()) { send(JSONObject().put("type", "text").put("text", buf.toString())); buf.clear() }
                send(JSONObject().put("type", "key").put("code", "ENTER").put("action", "press"))
            } else buf.append(ch)
        }
        if (buf.isNotEmpty()) send(JSONObject().put("type", "text").put("text", buf.toString()))
    }

    private fun toggleIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (imeVisible) {
            imm.hideSoftInputFromWindow(imeCatcher.windowToken, 0)
            imeCatcher.clearFocus()
        } else {
            imeCatcher.requestFocus()
            imm.showSoftInput(imeCatcher, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Keep the virtual cursor above whichever keyboard actually covers pixels. */
    private fun installVisibleAreaTracking() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            root.post { updateBottomOcclusion() }
            insets
        }
        root.viewTreeObserver.addOnGlobalLayoutListener {
            ViewCompat.getRootWindowInsets(root)?.let {
                imeVisible = it.isVisible(WindowInsetsCompat.Type.ime())
            }
            updateBottomOcclusion()
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateBottomOcclusion() {
        if (!::screen.isInitialized || screen.height == 0) return
        val screenLocation = IntArray(2)
        screen.getLocationOnScreen(screenLocation)
        val screenBottom = screenLocation[1] + screen.height
        var covered = 0

        if (::keyboard.isInitialized && keyboard.visibility == View.VISIBLE && keyboard.height > 0) {
            val keyboardLocation = IntArray(2)
            keyboard.getLocationOnScreen(keyboardLocation)
            covered = (screenBottom - keyboardLocation[1]).coerceAtLeast(0)
        }

        // This is zero when adjustResize already shortened the viewer, and is
        // the actual overlap when the IME floats over the viewer.
        val visibleWindow = Rect()
        screen.getWindowVisibleDisplayFrame(visibleWindow)
        covered = maxOf(covered, (screenBottom - visibleWindow.bottom).coerceAtLeast(0))
        screen.setBottomOcclusion(covered)
    }

    private fun showDiagnostics() {
        if (diagnosticsPanel.visibility == View.VISIBLE) {
            hideDiagnostics()
            return
        }
        diagnosticsPanel.visibility = View.VISIBLE
        diagnosticsUpdater?.let { diagnosticsHandler.removeCallbacks(it) }

        var previousStream = streamStats.snapshot()
        var previousConnection = ConnectionManager.debugStats()
        val updater = object : Runnable {
            override fun run() {
                sendDiagnosticPingIfDue()
                val stream = streamStats.snapshot()
                val connection = ConnectionManager.debugStats()
                diagnosticsText.text = formatDiagnostics(previousStream, stream, previousConnection, connection)
                previousStream = stream
                previousConnection = connection
                diagnosticsHandler.postDelayed(this, STATS_REFRESH_MS)
            }
        }
        diagnosticsUpdater = updater
        updater.run()
    }

    private fun hideDiagnostics() {
        if (::diagnosticsPanel.isInitialized) diagnosticsPanel.visibility = View.GONE
        diagnosticsUpdater?.let { diagnosticsHandler.removeCallbacks(it) }
        diagnosticsUpdater = null
    }

    private fun buildDiagnosticsPanel(): LinearLayout {
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()
        val prefs = getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
        diagnosticsText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
        }
        val highlight = CheckBox(this).apply {
            setText(R.string.highlight_dirty_rectangles)
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(PREF_HIGHLIGHT_DIRTY, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PREF_HIGHLIGHT_DIRTY, checked).apply()
                screen.setDirtyRectHighlightsEnabled(checked)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = 8 * density
            setBackgroundColor(Color.parseColor("#E619202C"))
            setPadding(pad, pad, pad, (4 * density).toInt())
            addView(diagnosticsText)
            addView(highlight)
        }
    }

    private fun sendDiagnosticPingIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (pendingPingNonce >= 0 && now - pingSentAtMs < PING_TIMEOUT_MS) return
        if (pendingPingNonce >= 0) {
            pendingPingNonce = -1L
            pingTimedOut = true
        }
        if (now - pingSentAtMs < PING_INTERVAL_MS) return
        val nonce = ++nextPingNonce
        pendingPingNonce = nonce
        pingSentAtMs = now
        send(JSONObject().put("type", "diagnostic-ping").put("nonce", nonce))
    }

    private fun formatDiagnostics(
        previousStream: ViewerStreamStats.Snapshot,
        stream: ViewerStreamStats.Snapshot,
        previousConnection: ConnectionManager.DebugStats,
        connection: ConnectionManager.DebugStats,
    ): String {
        val elapsedSeconds = ((stream.sampledAtMs - previousStream.sampledAtMs) / 1000.0)
            .coerceAtLeast(0.001)
        val frames = stream.frames - previousStream.frames
        val fps = frames / elapsedSeconds
        val streamMbps = (stream.streamBytes - previousStream.streamBytes) * 8.0 /
            elapsedSeconds / 1_000_000.0
        val wireMbps = (connection.receivedWireBytes - previousConnection.receivedWireBytes) * 8.0 /
            elapsedSeconds / 1_000_000.0
        val decodeMs = if (frames > 0) {
            (stream.decodeNanos - previousStream.decodeNanos) / frames / 1_000_000.0
        } else 0.0
        val rectsPerFrame = if (frames > 0) {
            (stream.rects - previousStream.rects).toDouble() / frames
        } else 0.0
        val dirtyPercentPerSecond = if (stream.surfacePixels > 0) {
            (stream.dirtyPixels - previousStream.dirtyPixels) * 100.0 /
                stream.surfacePixels / elapsedSeconds
        } else 0.0
        val rtt = when {
            lastRttMs != null -> "${lastRttMs} ms"
            pingTimedOut -> "timed out"
            else -> "measuring…"
        }
        val lastFrame = stream.lastFrameAgoMs?.let { formatAge(it) } ?: "never"
        val lastPacket = connection.lastReceiveAgoMs?.let { formatAge(it) } ?: "never"
        val hostConfig = if (hostTargetFps != null) {
            "${hostTargetFps} FPS • JPEG ${hostJpegQuality ?: "?"} • " +
                (hostMaxWidth?.takeIf { it > 0 }?.let { "max ${it}px" } ?: "native width")
        } else "unavailable"

        return String.format(
            Locale.US,
            "Connection  %s (%s)\n" +
                "Round trip  %s\n" +
                "Receive     %.2f Mbps wire / %.2f Mbps screen\n" +
                "Last packet %s ago • %d messages\n" +
                "Queues      phone %s • desktop %s\n" +
                "Reconnects  %d attempts • %d failures\n\n" +
                "Host config %s\n" +
                "Surface     %d × %d\n" +
                "Changed FPS %.1f\n" +
                "Decode      %.2f ms/frame\n" +
                "Dirty areas %.1f rects/frame • %.1f%% screen/sec\n" +
                "Frames      %d total • %d keyframes\n" +
                "Integrity   %d sequence gaps • %d decode errors\n" +
                "Last frame  %s ago\n\n" +
                "FPS falls to zero when the desktop is idle.",
            connection.state,
            formatAge(connection.connectedForMs),
            rtt,
            wireMbps,
            streamMbps,
            lastPacket,
            connection.receivedMessages,
            formatBytes(connection.outgoingQueueBytes),
            hostQueueBytes?.let { formatBytes(it) } ?: "unavailable",
            connection.connectionAttempts,
            connection.failures,
            hostConfig,
            stream.surfaceWidth,
            stream.surfaceHeight,
            fps,
            decodeMs,
            rectsPerFrame,
            dirtyPercentPerSecond,
            stream.frames,
            stream.keyframes,
            stream.sequenceGaps,
            stream.decodeErrors,
            lastFrame,
        )
    }

    private fun formatAge(ms: Long): String = when {
        ms < 1_000 -> "${ms} ms"
        ms < 60_000 -> String.format(Locale.US, "%.1f s", ms / 1000.0)
        else -> "%d:%02d".format(Locale.US, ms / 60_000, (ms / 1_000) % 60)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
    }

    private fun send(obj: JSONObject) {
        winId?.let { ConnectionManager.sendJson(obj.put("to", it)) }
    }

    override fun onResume() {
        super.onResume()
        ConnectionManager.binaryListeners.add(binaryListener)
        ConnectionManager.jsonListeners.add(jsonListener)
        winId?.let { ConnectionManager.sendJson(JSONObject().put("type", "start-view").put("to", it)) }
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.binaryListeners.remove(binaryListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        if (::keyboard.isInitialized) keyboard.releaseAll()
        hideDiagnostics()
        diagnosticsHandler.removeCallbacksAndMessages(null)
        winId?.let { ConnectionManager.sendJson(JSONObject().put("type", "stop-view").put("to", it)) }
    }

    private companion object {
        const val DEBUG_PREFS = "viewer_debug"
        const val PREF_HIGHLIGHT_DIRTY = "highlight_dirty_rects"
        const val STATS_REFRESH_MS = 1_000L
        const val PING_INTERVAL_MS = 2_000L
        const val PING_TIMEOUT_MS = 5_000L
    }
}
