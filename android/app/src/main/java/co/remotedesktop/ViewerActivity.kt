package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var diagnosticsScroll: ScrollView
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
    private var composeRegion: Rect? = null
    private var lastSeq = -1L
    private var haveKeyframe = false
    @Volatile private var lastKeyframeRequestAt = 0L
    // Last viewport reported to the host, so an unchanged one is not re-sent —
    // every region change costs the host a keyframe.
    private var lastSentRegion: String? = null

    // H.264 path. The decoder renders onto videoView's surface; nothing here
    // ever holds a decoded frame.
    private lateinit var videoView: TextureView
    @Volatile private var videoSurface: Surface? = null
    private var decoder: H264Decoder? = null
    private var videoPtsUs = 0L
    private var hostCodec: String? = null

    private val binaryListener: (ByteArray) -> Unit = { data ->
        if (data.isNotEmpty()) when (data[0].toInt()) {
            1 -> { // legacy full-frame JPEG
                val started = SystemClock.elapsedRealtimeNanos()
                val bmp = BitmapFactory.decodeByteArray(data, 1, data.size - 1)
                if (bmp != null) {
                    screen.setFrame(bmp, Rect(0, 0, bmp.width, bmp.height))
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
            3 -> { releaseVideo(); handlePatch(data) }
            4 -> handleH264(data)
        }
    }

    /**
     * [seq u32][flags u8: bit0 keyframe][surfW u16][surfH u16]
     * [regionX u16][regionY u16][regionW u16][regionH u16][Annex B access unit]
     */
    private fun handleH264(data: ByteArray) {
        val started = SystemClock.elapsedRealtimeNanos()
        try {
            val buf = java.nio.ByteBuffer.wrap(data, 1, data.size - 1)
            val seq = buf.int.toLong() and 0xFFFFFFFFL
            val keyframe = (buf.get().toInt() and 1) != 0
            val w = buf.short.toInt() and 0xFFFF
            val h = buf.short.toInt() and 0xFFFF
            val rx = buf.short.toInt() and 0xFFFF
            val ry = buf.short.toInt() and 0xFFFF
            val rw = buf.short.toInt() and 0xFFFF
            val rh = buf.short.toInt() and 0xFFFF
            val region = Rect(rx, ry, rx + rw, ry + rh)
            if (w <= 0 || h <= 0 || buf.remaining() <= 0) return

            val surface = videoSurface ?: return
            var dec = decoder
            if (dec == null || dec.width != w || dec.height != h) {
                // A zoom changed the frame size; encoders cannot change
                // resolution mid-stream and neither can decoders.
                dec?.release()
                if (!keyframe) { requestKeyframe(); decoder = null; return }
                dec = H264Decoder(surface, w, h)
                decoder = dec
            }

            val hadSequenceGap = haveKeyframe && !keyframe &&
                seq != ((lastSeq + 1L) and 0xFFFFFFFFL)
            if (keyframe) haveKeyframe = true
            else if (!haveKeyframe || hadSequenceGap) requestKeyframe()
            lastSeq = seq
            if (!haveKeyframe) return // nothing to decode from yet

            val au = ByteArray(buf.remaining())
            buf.get(au)
            videoPtsUs += 1_000_000L / 30
            val rendered = dec.decode(au, videoPtsUs, keyframe)
            if (rendered) runOnUiThread { screen.setVideoFrame(w, h, region) }

            streamStats.recordFrame(
                bytes = data.size,
                rectCount = 1,
                changedPixels = w.toLong() * h,
                decodeTimeNanos = SystemClock.elapsedRealtimeNanos() - started,
                isKeyframe = keyframe,
                hadSequenceGap = hadSequenceGap,
                width = w,
                height = h,
            )
        } catch (e: Exception) {
            streamStats.recordDecodeError()
            decoder?.release()
            decoder = null
            haveKeyframe = false
            requestKeyframe()
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
            val flags = buf.get().toInt()
            val keyframe = (flags and 1) != 0
            val w = buf.short.toInt() and 0xFFFF
            val h = buf.short.toInt() and 0xFFFF
            val rectCount = buf.short.toInt() and 0xFFFF

            // Bit 1: this frame covers only part of the desktop, and the header
            // says which part. Only sent to viewers that asked for a region.
            val region = if ((flags and 2) != 0) {
                val rx = buf.short.toInt() and 0xFFFF
                val ry = buf.short.toInt() and 0xFFFF
                val rw = buf.short.toInt() and 0xFFFF
                val rh = buf.short.toInt() and 0xFFFF
                Rect(rx, ry, rx + rw, ry + rh)
            } else {
                Rect(0, 0, w, h) // whole desktop, image pixels are desktop pixels
            }

            // Bit 2: the host moved the crop and expects the overlapping
            // pixels to be carried across, so only the newly exposed edge is
            // in this patch. Without it a pan costs a full repaint.
            val scrolled = (flags and 4) != 0

            var bmp = compose
            var retired: Bitmap? = null
            val prevRegion = composeRegion
            if (region != prevRegion) {
                if (scrolled && haveKeyframe && prevRegion != null && bmp != null &&
                    bmp.width == w && bmp.height == h && canCarry(prevRegion, region, w, h)
                ) {
                    val kx = region.width() / w
                    val ky = region.height() / h
                    val overlap = Rect(prevRegion)
                    overlap.intersect(region)
                    val moved = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val movedCanvas = android.graphics.Canvas(moved)
                    movedCanvas.drawBitmap(
                        bmp,
                        Rect(
                            (overlap.left - prevRegion.left) / kx, (overlap.top - prevRegion.top) / ky,
                            (overlap.right - prevRegion.left) / kx, (overlap.bottom - prevRegion.top) / ky,
                        ),
                        Rect(
                            (overlap.left - region.left) / kx, (overlap.top - region.top) / ky,
                            (overlap.right - region.left) / kx, (overlap.bottom - region.top) / ky,
                        ),
                        null,
                    )
                    retired = bmp
                    bmp = moved
                    compose = moved
                    composeCanvas = movedCanvas
                    // The canvas still describes the desktop correctly, so this
                    // is not a gap and needs no keyframe.
                } else if (!keyframe) {
                    // A new region we cannot carry into: the pixels we hold now
                    // mean somewhere else.
                    requestKeyframe()
                    return
                } else {
                    haveKeyframe = false
                }
                composeRegion = region
            }
            if (bmp == null || bmp.width != w || bmp.height != h) {
                if (!keyframe) { requestKeyframe(); return } // can't composite yet
                // The host adapts its output resolution while streaming, so this
                // now happens mid-session rather than once. A stitched desktop
                // canvas is tens of MB, so hand the old one back explicitly
                // instead of leaving several for the collector to notice.
                retired = bmp
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
            // The view works in desktop coordinates, so map tile rects out of
            // image space through the region this frame covers.
            val toDeskX = region.width().toDouble() / w
            val toDeskY = region.height().toDouble() / h
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
                    dirtyRects.add(Rect(
                        region.left + (x * toDeskX).toInt(),
                        region.top + (y * toDeskY).toInt(),
                        region.left + Math.ceil(right * toDeskX).toInt(),
                        region.top + Math.ceil(bottom * toDeskY).toInt(),
                    ))
                    changedPixels += (right - x).toLong() * (bottom - y)
                }
            }
            screen.setFrame(bmp, region, dirtyRects)
            // setFrame has already swapped the view onto the new bitmap, and
            // recycling on the view's own thread cannot race a draw in progress.
            retired?.let { old -> screen.post { old.recycle() } }
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

    /**
     * Whether the pixels held for [prev] can be shifted to describe [now].
     * Requires the same crop size, an exact whole number of desktop pixels per
     * image pixel, a move that lands on whole image pixels, and something left
     * to carry. The host snaps its crop to that grid precisely so this holds
     * for a pan; anything else falls back to a keyframe.
     */
    private fun canCarry(prev: Rect, now: Rect, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return false
        if (prev.width() != now.width() || prev.height() != now.height()) return false
        if (now.width() % w != 0 || now.height() % h != 0) return false
        val kx = now.width() / w
        val ky = now.height() / h
        if ((now.left - prev.left) % kx != 0 || (now.top - prev.top) % ky != 0) return false
        return Rect.intersects(prev, now)
    }

    private fun releaseVideo() {
        val dec = decoder ?: return
        decoder = null
        dec.release()
        runOnUiThread { screen.clearVideo() }
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
        if (msg.optString("type") == "screen-info" && msg.optString("from") == winId) {
            // desktopWidth/Height are the coordinate space region patches and
            // input are expressed in. Older hosts send only the streamed size,
            // which for them is the whole desktop anyway.
            val dw = msg.optInt("desktopWidth", msg.optInt("width", 0))
            val dh = msg.optInt("desktopHeight", msg.optInt("height", 0))
            if (dw > 0 && dh > 0) screen.setDesktopSize(dw, dh)
        }
        if (msg.optString("type") == "diagnostic-pong" && msg.optString("from") == winId &&
            msg.optLong("nonce", -1L) == pendingPingNonce
        ) {
            lastRttMs = SystemClock.elapsedRealtime() - pingSentAtMs
            hostQueueBytes = msg.optLong("hostQueueBytes", -1L).takeIf { it >= 0 }
            hostTargetFps = msg.optInt("targetFps", -1).takeIf { it > 0 }
            hostJpegQuality = msg.optInt("jpegQuality", -1).takeIf { it > 0 }
            hostMaxWidth = msg.optInt("maxWidth", -1).takeIf { it >= 0 }
            hostCodec = msg.optString("codec").takeIf { it.isNotEmpty() }
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
        root.setBackgroundColor(Color.BLACK)

        // The decoder renders here. It sits under the overlay and is
        // positioned entirely by RemoteScreenView's transform, so pan and zoom
        // apply to video and to the pointer through the same matrix.
        videoView = TextureView(this)
        videoView.isOpaque = false
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                videoSurface = Surface(st)
                // The host only sends H.264 to viewers that said they could
                // decode it, and until now this one could not.
                screen.resendViewport()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releaseVideo()
                videoSurface?.release()
                videoSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        root.addView(videoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        screen = RemoteScreenView(this)
        screen.attachVideo(videoView)
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
            panelWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START
        ).apply {
            leftMargin = panelMargin
            rightMargin = panelMargin
            topMargin = panelMargin
            bottomMargin = panelMargin
        })

        val toolbar = buildToolbar()
        root.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        toolbar.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val screenLocation = IntArray(2)
            val toolbarLocation = IntArray(2)
            screen.getLocationOnScreen(screenLocation)
            view.getLocationOnScreen(toolbarLocation)
            screen.setTopOcclusion(
                (toolbarLocation[1] + view.height - screenLocation[1]).coerceAtLeast(0)
            )
        }

        imeCatcher = buildImeCatcher()
        root.addView(imeCatcher, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
        installVisibleAreaTracking()
    }

    private fun buildToolbar(): LinearLayout {
        val density = resources.displayMetrics.density
        val horizontalPadding = (4 * density).toInt()
        val verticalPadding = (2 * density).toInt()
        fun tb(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            isSingleLine = true
            minimumWidth = 0
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            alpha = 0.85f
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 6f
            setBackgroundColor(Color.parseColor("#88000000"))
            fun addToolbarButton(label: String, weight: Float = 1f, onClick: () -> Unit) {
                addView(tb(label, onClick), LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    weight,
                ))
            }
            addToolbarButton("⌨") {
                keyboard.visibility = if (keyboard.visibility == View.VISIBLE) {
                    keyboard.releaseAll(); View.GONE
                } else View.VISIBLE
                keyboard.post { updateBottomOcclusion() }
            }
            addToolbarButton("Stats") { showDiagnostics() }
            addToolbarButton("?") { showGestureHelp() }
            addToolbarButton("Ctrl+Alt+Del", weight = 2f) { ctrlAltDel() }
            addToolbarButton("✕") { finish() }
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
        screen.onViewportChanged = { x, y, w, h, outW, outH ->
            // Round before comparing so sub-pixel drift from a settling gesture
            // does not cost the host a keyframe for a region it is already on.
            val key = "%.3f,%.3f,%.3f,%.3f,%d,%d".format(Locale.US, x, y, w, h, outW, outH)
            if (key != lastSentRegion) {
                lastSentRegion = key
                send(JSONObject().put("type", "view-region")
                    .put("x", x).put("y", y).put("w", w).put("h", h)
                    .put("outW", outW).put("outH", outH)
                    // Only once there is somewhere to decode onto, or the host
                    // would stream frames we cannot show.
                    .put("h264", videoSurface != null))
            }
        }
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
        diagnosticsPanel.bringToFront()
        diagnosticsUpdater?.let { diagnosticsHandler.removeCallbacks(it) }

        var previousStream = streamStats.snapshot()
        var previousConnection = ConnectionManager.debugStats()
        val updater = object : Runnable {
            override fun run() {
                sendDiagnosticPingIfDue()
                val stream = streamStats.snapshot()
                val connection = ConnectionManager.debugStats()
                val scrollY = diagnosticsScroll.scrollY
                diagnosticsText.text = formatDiagnostics(previousStream, stream, previousConnection, connection)
                diagnosticsScroll.post {
                    val maxScrollY = (diagnosticsText.height - diagnosticsScroll.height).coerceAtLeast(0)
                    diagnosticsScroll.scrollTo(0, scrollY.coerceAtMost(maxScrollY))
                }
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
        diagnosticsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(diagnosticsText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ViewerActivity).apply {
                text = getString(R.string.stats)
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@ViewerActivity).apply {
                setText(R.string.close)
                isAllCaps = false
                setOnClickListener { hideDiagnostics() }
            })
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
            addView(header)
            addView(diagnosticsScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
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
        val size = hostMaxWidth?.takeIf { it > 0 }?.let { "max ${it}px" } ?: "native width"
        val hostConfig = when {
            hostTargetFps == null -> "unavailable"
            // JPEG quality means nothing on the codec path, so do not print it.
            hostCodec == "h264" -> "${hostTargetFps} FPS • H.264 • $size"
            else -> "${hostTargetFps} FPS • JPEG ${hostJpegQuality ?: "?"} • $size"
        }

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
        releaseVideo()
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
