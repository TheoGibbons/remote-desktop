package co.remotedesktop

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows another phone in the session and controls it by touch, with buttons
 * for the Back, Home, Recents and Notifications this phone's own edge
 * gestures can't send. The other phone streams whole JPEGs: as type 5 frames
 * of a stream whose id it tells this one, when it is on a current app and
 * this phone has approved it, and as bare type 1 frames otherwise (see
 * [PhoneVideoStream]). Kept apart from [ViewerActivity], whose patches,
 * video, privacy and clipboard only a PC uses.
 */
class PhoneViewerActivity : AppCompatActivity() {

    private var peerId: String? = null
    private var peerName = ""
    private lateinit var screen: PhoneScreenView
    private lateinit var notice: TextView
    private lateinit var caption: TextView
    private lateinit var reconnectNotice: View
    private lateinit var reconnectReason: TextView
    private lateinit var ime: EditText
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var viewing = false
    /** Nothing arrived within [NO_FRAME_HINT_MS] of asking. */
    private var nothingYet = false
    /** Whether the other phone can type, from its screen-info; null until it says. */
    private var typing: Boolean? = null
    /** What the other phone last said in lock-state: null until it does, and
     *  for good with an app too old to say. */
    @Volatile private var lockState: LockCaption.State? = null
    private var pictureBlack = false
    /** Goes up each time the other phone's screen or lock changes (main thread). */
    @Volatile private var lockChanges = 0
    private val blackWatch = LockCaption.BlackWatch()
    private var imePrev = ""
    private var imeGuard = false

    /** Which of the frames that arrive are the other phone's. Network thread. */
    private val stream = PhoneVideoStream()
    private val acks = AckPacer()
    /** Whether the last start-view offered `codecs`, which it does only to a phone this one has approved. */
    @Volatile private var offered = false
    private val ackDue = Runnable { acknowledge(acks.due(SystemClock.uptimeMillis())) }

    private val noFrameHint = Runnable {
        nothingYet = true
        updateNotice()
    }

    // Network thread: decode here, draw on the main thread.
    private val binaryListener: (ByteArray) -> Unit = { data ->
        // Only a phone's pictures; an older relay may also pass on a PC's patches or video.
        if (viewing && data.size > 1) when (data[0]) {
            // Not while the other phone has announced a stream: a type 1 frame
            // names no sender, and anyone in the session can make one.
            Crypto.CH_VIDEO -> if (stream.drawsType1) draw(data, 1)
            Crypto.CH_PHONE -> PhoneFrame.parse(data, 1)?.let { header ->
                val verdict = stream.take(header)
                if (verdict != PhoneVideoStream.Verdict.REFUSE) {
                    // Said as the frame arrives, before the time it takes to decode it.
                    val now = SystemClock.uptimeMillis()
                    val ack = acks.received(header.stream, header.seq, now)
                    if (ack != null) acknowledge(ack)
                    else acks.dueIn(now)?.let { handler.removeCallbacks(ackDue); handler.postDelayed(ackDue, it) }
                    if (verdict == PhoneVideoStream.Verdict.DRAW) draw(data, 1 + PhoneFrame.HEADER_SIZE)
                }
            }
        }
    }

    /** Decodes the JPEG that starts at [offset] and hands it to the main thread. */
    private fun draw(data: ByteArray, offset: Int) {
        BitmapFactory.decodeByteArray(data, offset, data.size - offset)?.let { bitmap ->
            // The other phone may say its lock has changed while this picture
            // is on its way to the screen. What was seen in the picture was
            // seen before that, and is not carried over.
            val asOf = lockChanges
            val black = blackWatch.look(bitmap, lockState, SystemClock.uptimeMillis())
            runOnUiThread { if (viewing) showFrame(bitmap, black.takeIf { asOf == lockChanges }) }
        }
    }

    private fun acknowledge(ack: AckPacer.Ack?) {
        if (ack != null && viewing) send(ack.message())
    }

    // Network thread, in order with the frames: the stream's id has to be
    // known before the first frame that carries it.
    private val streamListener: (JSONObject) -> Unit = { msg ->
        // Believed only from the phone being viewed, and only if this phone
        // has approved it, which is when it was offered codecs.
        if (offered && msg.optString("type") == "screen-info" && msg.optString("from") == peerId)
            stream.announce(msg.optString("stream"), msg.optBoolean("paused", false))
    }

    /** This phone has approved the other one, or no longer does: ask again, offering what it now may. */
    private val trustListener: () -> Unit = {
        if (viewing && PeerAuth.isTrusted(peerId) != offered) startView()
    }

    private val jsonListener: (JSONObject) -> Unit = { msg ->
        val fromPeer = msg.optString("from") == peerId
        when (msg.optString("type")) {
            "peer-left" -> if (msg.optString("id") == peerId) gone()
            "auth-result" -> if (fromPeer) {
                updateNotice()
                when (msg.optString("status")) {
                    // The start-view sent before approval was dropped.
                    "trusted" -> startView()
                    "denied", "revoked", "disconnected" -> finish()
                }
            }
            // Phones broadcast it, so only this one's counts.
            "screen-info" -> if (fromPeer) typing = msg.optBoolean("typing", false)
            "lock-state" -> if (fromPeer) LockCaption.parse(msg)?.let { state ->
                val before = lockState
                if (state != before) {
                    lockState = state
                    // The picture on show was taken before this. A new message
                    // line alone, which a lock screen that is counting down
                    // sends every second, changes nothing about the picture.
                    if (before == null || before.screenOn != state.screenOn || before.locked != state.locked) {
                        lockChanges++
                        blackWatch.forget()
                        pictureBlack = false
                    }
                    updateCaption()
                }
            }
        }
    }

    private val stateListener: (String) -> Unit = { updateReconnectNotice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ConnectionManager.init(this)
        val peer = ConnectionManager.peer(intent.getStringExtra(ConnectionManager.EXTRA_PEER_ID))
            ?.takeIf { it.device == "android" }
        if (peer == null) {
            Toast.makeText(this, R.string.phone_viewer_not_online, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        peerId = peer.id
        peerName = peer.name

        setContentView(R.layout.activity_phone_viewer)
        Ui.applySystemBarInsets(findViewById(R.id.phoneViewerRoot), includeIme = true)
        // Watching without touching must not let this phone sleep, which would end the stream.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<MaterialToolbar>(R.id.phoneViewerToolbar).apply {
            setNavigationOnClickListener { finish() }
            addAction(R.string.viewer_keyboard, R.drawable.ic_keyboard) { toggleKeyboard() }
            addAction(R.string.rotate_screen, R.drawable.ic_screen_rotation_small) { Ui.rotateScreen(this@PhoneViewerActivity) }
        }
        screen = findViewById<PhoneScreenView>(R.id.phoneScreen).apply { onInput = ::sendInput }
        notice = findViewById(R.id.phoneViewerNotice)
        caption = findViewById(R.id.phoneViewerCaption)
        reconnectNotice = findViewById(R.id.phoneViewerReconnect)
        reconnectReason = findViewById(R.id.phoneViewerReconnectReason)
        mapOf(R.id.phoneNavBack to "back", R.id.phoneNavHome to "homebtn",
            R.id.phoneNavRecents to "recents", R.id.phoneNavNotifications to "notifications"
        ).forEach { (id, type) ->
            findViewById<View>(id).apply {
                tooltipText = contentDescription
                setOnClickListener { send(JSONObject().put("type", type)) }
            }
        }
        ime = findViewById<EditText>(R.id.phoneViewerIme).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Don't let this phone's keyboard learn what is typed into the other one, passwords included.
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (imeGuard) return
                    val cur = s?.toString() ?: ""
                    // While the other phone's PIN pad is up, what is typed
                    // goes to its keys as taps, and none of it as text.
                    val pin = lockState?.takeIf { it.screenOn && it.locked }?.entry as? LockScreenReader.Entry.Pin
                    if (pin != null) LockCaption.pinMessages(pin, TypingDiff.edits(imePrev, cur)).forEach(::send)
                    else TypingDiff.messages(imePrev, cur).forEach(::send)
                    imePrev = cur
                    if (cur.length > 512) { // keep the buffer from growing without end
                        imeGuard = true; text?.clear(); imePrev = ""; imeGuard = false
                    }
                }
            })
        }
    }

    private fun MaterialToolbar.addAction(title: Int, icon: Int, onClick: () -> Unit) {
        menu.add(title).apply {
            setIcon(icon)
            iconTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rd_on_surface))
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener { onClick(); true }
        }
    }

    override fun onResume() {
        super.onResume()
        if (peerId == null) return
        viewing = true
        ConnectionManager.binaryListeners.add(binaryListener)
        ConnectionManager.networkJsonListeners.add(streamListener)
        ConnectionManager.jsonListeners.add(jsonListener)
        PeerAuth.changeListeners.add(trustListener)
        ConnectionManager.stateListeners.add(stateListener)
        updateReconnectNotice()
        startView()
    }

    override fun onPause() {
        super.onPause()
        if (peerId == null) return
        viewing = false
        // Never leave a finger down on the other phone.
        screen.liftFinger()
        handler.removeCallbacks(noFrameHint)
        handler.removeCallbacks(ackDue)
        ConnectionManager.binaryListeners.remove(binaryListener)
        ConnectionManager.networkJsonListeners.remove(streamListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        PeerAuth.changeListeners.remove(trustListener)
        ConnectionManager.stateListeners.remove(stateListener)
        send(JSONObject().put("type", "stop-view"))
    }

    private fun startView() {
        // Type 5 frames are offered only to a phone this one has approved:
        // the stream's id is believed on that phone's word. Until the next
        // phone can decode video, whole JPEGs are all this viewer draws.
        val offer = PeerAuth.isTrusted(peerId)
        if (offer != offered) {
            // What was announced under the other arrangement no longer holds.
            stream.forget()
            acks.forget()
        }
        offered = offer
        val msg = JSONObject().put("type", "start-view")
        if (offer) msg.put("codecs", JSONArray().put(PhoneScreenCapture.JPEG))
        send(msg)
        // The other phone ignores start-view without saying why, e.g. with
        // control off or a device awaiting approval there. Say what to check.
        nothingYet = false
        handler.removeCallbacks(noFrameHint)
        handler.postDelayed(noFrameHint, NO_FRAME_HINT_MS)
        updateNotice()
    }

    /** [black] is whether the picture is nearly all black; null when that
     *  was worked out before the other phone's lock last changed. */
    private fun showFrame(bitmap: android.graphics.Bitmap, black: Boolean?) {
        screen.setFrame(bitmap)
        if (black != null && black != pictureBlack) {
            pictureBlack = black
            updateCaption()
        }
        handler.removeCallbacks(noFrameHint)
        if (nothingYet) {
            nothingYet = false
            updateNotice()
        }
    }

    private fun updateNotice() {
        val text = when {
            PeerAuth.resultFrom(peerId) == "pending" -> getString(R.string.viewer_awaiting_approval, peerName,
                PeerAuth.shortCode(PeerAuth.ownFingerprint))
            nothingYet -> getString(R.string.phone_viewer_nothing_yet, peerName)
            else -> null
        }
        notice.text = text
        notice.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Says over the picture that the other phone is asleep, that it didn't
     *  wake, or that it is on the unlock screen Android leaves out of the
     *  picture; and has that screen drawn when the other phone says what is on it. */
    private fun updateCaption() {
        val string = { id: Int -> getString(id, peerName) }
        val text = LockCaption.caption(lockState, pictureBlack, string)
        caption.text = text
        caption.visibility = if (text == null) View.GONE else View.VISIBLE
        // The unlock screen, which is not in the picture, drawn where the other phone says it is.
        screen.showUnlock(lockState?.entry, LockCaption.heading(lockState, string))
    }

    /** Shown while this phone is off the relay. The other phone approves the
     *  new connection, and its auth-result restarts the stream. */
    private fun updateReconnectNotice() {
        if (isFinishing) return
        val connected = ConnectionManager.state == "connected"
        // Back on the relay without the other phone: it dropped too and has a new id.
        if (connected && ConnectionManager.peers.none { it.id == peerId }) {
            gone()
            return
        }
        reconnectNotice.visibility = if (connected) View.GONE else View.VISIBLE
        val reason = if (connected) null else ConnectionLog.currentProblem()?.reason
        reconnectReason.text = reason
        reconnectReason.visibility = if (reason == null) View.GONE else View.VISIBLE
    }

    private fun gone() {
        if (isFinishing) return
        Toast.makeText(this, getString(R.string.phone_viewer_disconnected, peerName), Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun toggleKeyboard() {
        if (typing == false) {
            Toast.makeText(this, getString(R.string.phone_viewer_no_typing, peerName), Toast.LENGTH_LONG).show()
            return
        }
        val imm = getSystemService(InputMethodManager::class.java)
        if (ViewCompat.getRootWindowInsets(ime)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            imm.hideSoftInputFromWindow(ime.windowToken, 0)
            ime.clearFocus()
        } else {
            ime.requestFocus()
            imm.showSoftInput(ime, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendInput(out: PhoneTouchInput.Out) = send(when (out) {
        is PhoneTouchInput.Tap -> JSONObject().put("type", "tap").put("x", out.x).put("y", out.y)
        is PhoneTouchInput.Touch -> JSONObject().put("type", "touch").put("action", out.action)
            .put("x", out.x).put("y", out.y)
        is PhoneTouchInput.Pinch -> JSONObject().put("type", "pinch").put("x", out.x).put("y", out.y)
            .put("dir", if (out.zoomIn) "in" else "out").put("scale", out.scale)
    })

    private fun send(msg: JSONObject) {
        peerId?.let { ConnectionManager.sendJson(msg.put("to", it)) }
    }

    private companion object {
        const val NO_FRAME_HINT_MS = 5_000L
    }
}
