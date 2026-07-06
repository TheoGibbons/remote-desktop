package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Full-screen viewer for controlling the paired Windows desktop. Renders the
 * stitched multi-monitor stream and turns touch + the on-screen keyboard into
 * remote input.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var screen: RemoteScreenView
    private lateinit var keyboard: KeyboardPanel
    private lateinit var imeCatcher: EditText
    private var winId: String? = null
    private var imePrev = ""
    private var imeGuard = false

    private val binaryListener: (ByteArray) -> Unit = { data ->
        if (data.isNotEmpty() && data[0].toInt() == 1) {
            val bmp = BitmapFactory.decodeByteArray(data, 1, data.size - 1)
            if (bmp != null) screen.setFrame(bmp)
        }
    }

    private val jsonListener: (JSONObject) -> Unit = { msg ->
        if (msg.optString("type") == "peer-left" && msg.optString("id") == winId) {
            Toast.makeText(this, "Desktop disconnected", Toast.LENGTH_SHORT).show()
            finish()
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

        val root = FrameLayout(this)
        screen = RemoteScreenView(this)
        root.addView(screen, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        wireGestures()

        // Keyboard panel (hidden until toggled).
        keyboard = KeyboardPanel(this).apply {
            visibility = View.GONE
            onKey = { code, action -> send(JSONObject().put("type", "key").put("code", code).put("action", action)) }
            onText = { text -> send(JSONObject().put("type", "text").put("text", text)) }
            onOpenIme = { showIme() }
        }
        root.addView(keyboard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        root.addView(buildToolbar(), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        imeCatcher = buildImeCatcher()
        root.addView(imeCatcher, FrameLayout.LayoutParams(1, 1))

        setContentView(root)
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
            })
            addView(tb("Drag") {
                screen.dragMode = !screen.dragMode
                Toast.makeText(this@ViewerActivity, if (screen.dragMode) "Drag mode ON" else "Drag mode OFF", Toast.LENGTH_SHORT).show()
            })
            addView(tb("Ctrl+Alt+Del") { ctrlAltDel() })
            addView(tb("✕") { finish() })
        }
    }

    private fun wireGestures() {
        screen.onLeftClick = { x, y ->
            send(JSONObject().put("type", "mouse").put("action", "down").put("button", "left").put("x", x).put("y", y))
            send(JSONObject().put("type", "mouse").put("action", "up").put("button", "left").put("x", x).put("y", y))
        }
        screen.onRightClick = { x, y ->
            send(JSONObject().put("type", "mouse").put("action", "down").put("button", "right").put("x", x).put("y", y))
            send(JSONObject().put("type", "mouse").put("action", "up").put("button", "right").put("x", x).put("y", y))
        }
        screen.onWheel = { dx, dy -> send(JSONObject().put("type", "scroll").put("dx", dx).put("dy", dy)) }
        screen.onLeftDown = { x, y -> send(JSONObject().put("type", "mouse").put("action", "down").put("button", "left").put("x", x).put("y", y)) }
        screen.onMove = { x, y -> send(JSONObject().put("type", "mouse").put("action", "move").put("x", x).put("y", y)) }
        screen.onLeftUp = { x, y -> send(JSONObject().put("type", "mouse").put("action", "up").put("button", "left").put("x", x).put("y", y)) }
    }

    private fun ctrlAltDel() {
        send(JSONObject().put("type", "key").put("code", "CTRL").put("action", "down"))
        send(JSONObject().put("type", "key").put("code", "ALT").put("action", "down"))
        send(JSONObject().put("type", "key").put("code", "DELETE").put("action", "press"))
        send(JSONObject().put("type", "key").put("code", "ALT").put("action", "up"))
        send(JSONObject().put("type", "key").put("code", "CTRL").put("action", "up"))
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

    private fun showIme() {
        imeCatcher.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(imeCatcher, InputMethodManager.SHOW_IMPLICIT)
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
        keyboard.releaseAll()
        winId?.let { ConnectionManager.sendJson(JSONObject().put("type", "stop-view").put("to", it)) }
    }
}
