package co.joypilot.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

/**
 * A full "hardware" keyboard for controlling Windows, including Ctrl / Alt /
 * Shift / Win and the function/navigation keys.
 *
 * Emission model:
 *  - Ctrl / Alt / Win are hold-toggles: tapping sends the key down and it stays
 *    held (highlighted) so you can build combos like Ctrl+Alt+Del; tap again to
 *    release. They are auto-released when the panel is hidden.
 *  - Shift flips the on-screen letter/symbol layer and, for non-text keys, is
 *    wrapped around the emitted key so Shift+Arrow / Ctrl+Shift+Esc work.
 *  - Printable keys with no Ctrl/Alt/Win held are sent as literal [onText] so
 *    symbols and letters type correctly regardless of host layout; with a
 *    modifier held they are sent as [onKey] base codes so shortcuts work.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class KeyboardPanel(context: Context) : LinearLayout(context) {

    var onKey: ((code: String, action: String) -> Unit)? = null
    var onText: ((String) -> Unit)? = null
    var onOpenIme: (() -> Unit)? = null

    private val chordMods = linkedMapOf("CTRL" to false, "ALT" to false, "WIN" to false)
    private var shift = false
    private var symbols = false
    private var fnLayer = false

    private val lettersRows = listOf(
        "q w e r t y u i o p",
        "a s d f g h j k l",
        "z x c v b n m"
    )
    private val symbolRows = listOf(
        "1 2 3 4 5 6 7 8 9 0",
        "@ # $ % & * - + ( )",
        "! \" ' : ; / ? , ."
    )

    private val modButtons = HashMap<String, Button>()
    private lateinit var shiftButton: Button
    private lateinit var dynamic: LinearLayout
    private val density = context.resources.displayMetrics.density

    // Key size adapts to the screen so all rows fit in landscape too.
    private var keyHeightPx = 0
    private var keyTextSp = 13f

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#20232A"))
        val pad = (4 * density).toInt()
        setPadding(pad, pad, pad, pad)
        buildAll()
    }

    private fun buildAll() {
        computeKeySize()
        removeAllViews()
        modButtons.clear()
        addView(buildModifierRow())
        dynamic = LinearLayout(context).apply { orientation = VERTICAL }
        addView(dynamic)
        rebuildDynamic()
        refreshModHighlights()
    }

    /** Budget ~60% of the screen height over the 8 possible rows, so the
     *  panel never overflows (landscape) but keys stay finger-sized (portrait). */
    private fun computeKeySize() {
        val dm = context.resources.displayMetrics
        val rows = 8
        val perRow = (dm.heightPixels * 0.60f / rows).toInt() - dp(2) // minus margins
        keyHeightPx = perRow.coerceIn(dp(30), dp(46))
        keyTextSp = if (keyHeightPx < dp(38)) 11f else 13f
    }

    /** The viewer activity handles rotation itself (configChanges), so rebuild
     *  the rows for the new screen size here. */
    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        post { buildAll() }
    }

    /** Release any held modifiers (call when hiding the panel). */
    fun releaseAll() {
        for ((code, on) in chordMods) if (on) onKey?.invoke(code, "up")
        chordMods.keys.forEach { chordMods[it] = false }
        shift = false
        refreshModHighlights()
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun key(label: String, weight: Float = 1f, onTap: (Button) -> Unit): Button {
        return Button(context).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, keyTextSp)
            setPadding(dp(2), 0, dp(2), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            layoutParams = LayoutParams(0, keyHeightPx, weight).apply {
                setMargins(dp(1), dp(1), dp(1), dp(1))
            }
            setOnClickListener { onTap(this) }
        }
    }

    private fun row(vararg buttons: Button): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            buttons.forEach { addView(it) }
        }
    }

    private fun buildModifierRow(): LinearLayout {
        fun modKey(label: String, code: String): Button {
            val b = key(label, 1.2f) { toggleMod(code) }
            modButtons[code] = b
            return b
        }
        shiftButton = key("⇧ Shift", 1.4f) { toggleShift() }
        return row(
            modKey("Ctrl", "CTRL"),
            modKey("Win", "WIN"),
            modKey("Alt", "ALT"),
            shiftButton,
            key("Fn", 1f) { fnLayer = !fnLayer; rebuildDynamic() },
            key(if (symbols) "ABC" else "?123", 1.2f) { symbols = !symbols; rebuildDynamic() },
            key("⌨", 1f) { onOpenIme?.invoke() }
        )
    }

    private fun toggleMod(code: String) {
        val now = !(chordMods[code] ?: false)
        chordMods[code] = now
        onKey?.invoke(code, if (now) "down" else "up")
        refreshModHighlights()
    }

    private fun toggleShift() {
        shift = !shift
        refreshModHighlights()
        rebuildDynamic()
    }

    private fun refreshModHighlights() {
        for ((code, b) in modButtons) highlight(b, chordMods[code] == true)
        highlight(shiftButton, shift)
    }

    private fun highlight(b: Button, on: Boolean) {
        b.setBackgroundColor(if (on) Color.parseColor("#3B82F6") else Color.parseColor("#3A3F4B"))
        b.setTextColor(Color.WHITE)
    }

    private fun anyChordMod() = chordMods.values.any { it }

    // ---- key emission ----

    private fun emitPrintable(glyph: String, baseCode: String) {
        if (anyChordMod()) emitKeyPress(baseCode) else onText?.invoke(glyph)
    }

    private fun emitKeyPress(code: String) {
        val wrapShift = shift
        if (wrapShift) onKey?.invoke("SHIFT", "down")
        onKey?.invoke(code, "press")
        if (wrapShift) onKey?.invoke("SHIFT", "up")
    }

    // ---- dynamic layers ----

    private fun rebuildDynamic() {
        dynamic.removeAllViews()

        // Top row: F-keys when Fn is on, else the number row.
        if (fnLayer) {
            val f1 = ArrayList<Button>()
            for (i in 1..6) f1.add(key("F$i") { emitKeyPress("F$i") })
            val f2 = ArrayList<Button>()
            for (i in 7..12) f2.add(key("F$i") { emitKeyPress("F$i") })
            dynamic.addView(row(*f1.toTypedArray()))
            dynamic.addView(row(*f2.toTypedArray()))
        } else {
            val digits = (if (symbols) symbolRows[0] else "1 2 3 4 5 6 7 8 9 0").split(" ")
            dynamic.addView(row(*digits.map { g ->
                key(g) { emitPrintable(g, g) }
            }.toTypedArray()))
        }

        val rows = if (symbols) symbolRows.drop(1) else lettersRows.take(2)
        for (r in rows) {
            dynamic.addView(row(*r.split(" ").map { g ->
                val glyph = if (!symbols && shift) g.uppercase() else g
                key(glyph) { emitPrintable(glyph, g.uppercase()) }
            }.toTypedArray()))
        }

        // Last letter row gets Shift + Backspace bookends (letters layer only).
        if (!symbols) {
            val letters = lettersRows[2].split(" ").map { g ->
                val glyph = if (shift) g.uppercase() else g
                key(glyph) { emitPrintable(glyph, g.uppercase()) }
            }
            val shiftKey = key(if (shift) "⇧" else "⇧", 1.5f) { toggleShift() }
            highlight(shiftKey, shift)
            val back = key("⌫", 1.5f) { emitKeyPress("BACKSPACE") }
            dynamic.addView(row(shiftKey, *letters.toTypedArray(), back))
        } else {
            val third = symbolRows[2].split(" ").map { g -> key(g) { emitPrintable(g, g) } }
            val back = key("⌫", 1.5f) { emitKeyPress("BACKSPACE") }
            dynamic.addView(row(*third.toTypedArray(), back))
        }

        // Bottom control row: navigation + space + enter.
        dynamic.addView(row(
            key("Esc", 1.1f) { emitKeyPress("ESC") },
            key("Tab", 1.1f) { emitKeyPress("TAB") },
            key("◀", 1f) { emitKeyPress("LEFT") },
            key("Space", 3f) { emitPrintable(" ", "SPACE") },
            key("▶", 1f) { emitKeyPress("RIGHT") },
            key("Enter", 1.6f) { emitKeyPress("ENTER") }
        ))
        dynamic.addView(row(
            key("Del", 1.1f) { emitKeyPress("DELETE") },
            key("Home", 1.2f) { emitKeyPress("HOME") },
            key("End", 1.1f) { emitKeyPress("END") },
            key("PgUp", 1.2f) { emitKeyPress("PGUP") },
            key("PgDn", 1.2f) { emitKeyPress("PGDN") },
            key("▲", 1f) { emitKeyPress("UP") },
            key("▼", 1f) { emitKeyPress("DOWN") }
        ))
    }
}
