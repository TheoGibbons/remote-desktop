package co.remotedesktop

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.json.JSONObject

/**
 * Shows another phone in the session and controls it by touch, with buttons
 * for the Back, Home, Recents and Notifications this phone's own edge
 * gestures can't send. The other phone streams whole JPEG frames (type 1).
 * Kept apart from [ViewerActivity], whose patches, video, privacy and
 * clipboard only a PC uses.
 */
class PhoneViewerActivity : AppCompatActivity() {

    private var peerId: String? = null
    private var peerName = ""
    private lateinit var screen: PhoneScreenView
    private lateinit var notice: TextView
    private lateinit var reconnectNotice: View
    private lateinit var reconnectReason: TextView
    private lateinit var ime: EditText
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var viewing = false
    /** Nothing arrived within [NO_FRAME_HINT_MS] of asking. */
    private var nothingYet = false
    /** Whether the other phone can type, from its screen-info; null until it says. */
    private var typing: Boolean? = null
    private var imePrev = ""
    private var imeGuard = false

    private val noFrameHint = Runnable {
        nothingYet = true
        updateNotice()
    }

    // Network thread: decode here, draw on the main thread.
    private val binaryListener: (ByteArray) -> Unit = { data ->
        // Only type 1; an older relay may also pass on a PC's patches or video.
        if (viewing && data.size > 1 && data[0] == Crypto.CH_VIDEO) {
            BitmapFactory.decodeByteArray(data, 1, data.size - 1)?.let { bitmap ->
                runOnUiThread { if (viewing) showFrame(bitmap) }
            }
        }
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
            menu.add(R.string.viewer_keyboard).apply {
                setIcon(R.drawable.ic_keyboard)
                iconTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.rd_on_surface))
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener { toggleKeyboard(); true }
            }
        }
        screen = findViewById<PhoneScreenView>(R.id.phoneScreen).apply { onInput = ::sendInput }
        notice = findViewById(R.id.phoneViewerNotice)
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
                    TypingDiff.messages(imePrev, cur).forEach(::send)
                    imePrev = cur
                    if (cur.length > 512) { // keep the buffer from growing without end
                        imeGuard = true; text?.clear(); imePrev = ""; imeGuard = false
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (peerId == null) return
        viewing = true
        ConnectionManager.binaryListeners.add(binaryListener)
        ConnectionManager.jsonListeners.add(jsonListener)
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
        ConnectionManager.binaryListeners.remove(binaryListener)
        ConnectionManager.jsonListeners.remove(jsonListener)
        ConnectionManager.stateListeners.remove(stateListener)
        send(JSONObject().put("type", "stop-view"))
    }

    private fun startView() {
        send(JSONObject().put("type", "start-view"))
        // The other phone ignores start-view without saying why, e.g. with
        // control off or a device awaiting approval there. Say what to check.
        nothingYet = false
        handler.removeCallbacks(noFrameHint)
        handler.postDelayed(noFrameHint, NO_FRAME_HINT_MS)
        updateNotice()
    }

    private fun showFrame(bitmap: android.graphics.Bitmap) {
        screen.setFrame(bitmap)
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
