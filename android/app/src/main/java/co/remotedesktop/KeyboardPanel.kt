package co.remotedesktop

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import kotlin.math.roundToInt

/** Gboard-style typing rows with compact desktop modifiers and navigation above them.
 * Ctrl / Alt / Win stay held until toggled off or the panel is hidden. Shift
 * beside Ctrl stays enabled for shortcuts such as Shift+Arrow. The typing Shift
 * capitalizes the next letter; double-tapping it locks uppercase typing, while
 * holding it works as a physical Shift modifier for every desktop key.
 * Printable keys use literal text unless a desktop modifier is held.
 * Every key except the toggles repeats while held, like a physical keyboard.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class KeyboardPanel(context: Context) : LinearLayout(context) {
    var onKey: ((code: String, action: String) -> Unit)? = null
    var onText: ((String) -> Unit)? = null
    var onOpenIme: (() -> Unit)? = null

    private val chordMods = linkedMapOf("CTRL" to false, "ALT" to false, "WIN" to false)
    private var stickyShift = false
    private enum class TypingShift { OFF, ONCE, CAPS_LOCK }
    private var typingShift = TypingShift.OFF
    private var lastTypingShiftTapAt = -1L
    private var heldTypingShift = false
    private var typingShiftUsed = false
    private var typingShiftButton: Button? = null
    private val typingLetters = mutableListOf<Button>()
    private val uppercaseLetters get() = stickyShift || heldTypingShift || typingShift != TypingShift.OFF
    private var symbols = false
    private var extraSymbols = false
    private var fnLayer = false
    private val density get() = resources.displayMetrics.density
    private var keyHeightPx = 0
    private var controlHeightPx = 0
    private var rowGapPx = 0
    private var keyTextSp = 24f
    private val modButtons = HashMap<String, Button>()
    private val keyTouches = HashMap<Int, KeyTouch>()

    private data class KeyTouch(val key: Button, val downTime: Long, val offsetX: Float, val offsetY: Float)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#383838"))
        buildAll()
    }

    // Keep the visual gaps, but give every point in the panel to the nearest
    // key rectangle. Each finger keeps its original key until lifted/cancelled.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { cancelKeyTouches(); beginKeyTouch(event, 0) }
            MotionEvent.ACTION_POINTER_DOWN -> beginKeyTouch(event, event.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    keyTouches[event.getPointerId(i)]?.let { sendKeyTouch(it, event, i, MotionEvent.ACTION_MOVE) }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = event.actionIndex
                keyTouches.remove(event.getPointerId(i))?.let { sendKeyTouch(it, event, i, MotionEvent.ACTION_UP) }
                if (event.actionMasked == MotionEvent.ACTION_UP) cancelKeyTouches()
            }
            MotionEvent.ACTION_CANCEL -> cancelKeyTouches()
        }
        return true // The keyboard never passes touches through to the desktop.
    }

    private fun beginKeyTouch(event: MotionEvent, index: Int) {
        val x = event.getX(index)
        val y = event.getY(index)
        var nearest: Button? = null
        var nearestDistance = Float.POSITIVE_INFINITY
        var left = 0f
        var top = 0f
        for (r in 0 until childCount) {
            val row = getChildAt(r) as? LinearLayout ?: continue
            if (row.visibility != View.VISIBLE) continue
            for (k in 0 until row.childCount) {
                val key = row.getChildAt(k) as? Button ?: continue
                if (!key.isEnabled || key.visibility != View.VISIBLE || key.width == 0 || key.height == 0) continue
                val keyLeft = (row.left + key.left).toFloat()
                val keyTop = (row.top + key.top).toFloat()
                val dx = x - x.coerceIn(keyLeft, keyLeft + key.width)
                val dy = y - y.coerceIn(keyTop, keyTop + key.height)
                val distance = dx * dx + dy * dy
                if (distance < nearestDistance) {
                    nearest = key; nearestDistance = distance; left = keyLeft; top = keyTop
                }
            }
        }
        val key = nearest ?: return
        // A second finger on the same key must not reset its current press.
        if (keyTouches.values.any { it.key === key }) return
        val touch = KeyTouch(key, event.eventTime,
            if (nearestDistance == 0f) -left else key.width / 2f - x,
            if (nearestDistance == 0f) -top else key.height / 2f - y)
        keyTouches[event.getPointerId(index)] = touch
        sendKeyTouch(touch, event, index, MotionEvent.ACTION_DOWN)
    }

    private fun sendKeyTouch(touch: KeyTouch, event: MotionEvent, index: Int, action: Int) {
        val local = MotionEvent.obtain(touch.downTime, event.eventTime, action,
            event.getX(index) + touch.offsetX, event.getY(index) + touch.offsetY, event.metaState)
        try {
            local.source = event.source
            if (touch.key === typingShiftButton) handleTypingShiftTouch(touch.key, local)
            else touch.key.dispatchTouchEvent(local)
        } finally {
            local.recycle()
        }
    }

    private fun cancelKeyTouches() {
        val touches = keyTouches.values.toList()
        keyTouches.clear()
        for (touch in touches) {
            val cancel = MotionEvent.obtain(touch.downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            try {
                if (touch.key === typingShiftButton) handleTypingShiftTouch(touch.key, cancel)
                else touch.key.dispatchTouchEvent(cancel)
            } finally { cancel.recycle() }
        }
    }

    private fun handleTypingShiftTouch(button: Button, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                heldTypingShift = true
                typingShiftUsed = false
                button.isPressed = true
                button.drawableHotspotChanged(event.x, event.y)
                onKey?.invoke("SHIFT", "down")
                refreshTypingShift()
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (event.x < -slop || event.x >= button.width + slop ||
                    event.y < -slop || event.y >= button.height + slop) finishTypingShiftTouch(button, false)
            }
            MotionEvent.ACTION_UP -> finishTypingShiftTouch(button, button.isPressed)
            MotionEvent.ACTION_CANCEL -> finishTypingShiftTouch(button, false)
        }
    }

    private fun finishTypingShiftTouch(button: Button, tap: Boolean) {
        button.isPressed = false
        if (!heldTypingShift) return
        heldTypingShift = false
        onKey?.invoke("SHIFT", "up")
        if (tap && !typingShiftUsed) button.performClick()
        else {
            if (typingShiftUsed && typingShift == TypingShift.ONCE) typingShift = TypingShift.OFF
            lastTypingShiftTapAt = -1L
            refreshTypingShift()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE && typingShiftButton != null) cancelKeyTouches()
    }

    override fun onDetachedFromWindow() {
        cancelKeyTouches()
        super.onDetachedFromWindow()
    }

    private fun dp(v: Float) = (v * density).roundToInt()

    private fun buildAll() {
        cancelKeyTouches()
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // The old modifier buttons were 30..46 dp high with 2 dp of margins.
        // Reduce that whole row to 75%, independently of the typing key height.
        val oldHeight = (dm.heightPixels * .60f / 8).toInt() - dp(2f)
        controlHeightPx = ((oldHeight.coerceIn(dp(30f), dp(46f)) + dp(2f)) * .75f).roundToInt()
        rowGapPx = dp(if (landscape) 6f else 10f)
        val typingRows = if (fnLayer) 6 else 4
        val available = dm.heightPixels * .65f - controlHeightPx * 2 - dp(12f)
        keyHeightPx = minOf(dp(if (landscape) 34f else 44f),
            (available / typingRows).toInt() - rowGapPx).coerceAtLeast(dp(24f))
        keyTextSp = if (keyHeightPx < dp(34f)) 20f else if (landscape) 22f else 24f
        setPadding(dp(2f), dp(4f), dp(2f), dp(4f))
        removeAllViews()
        modButtons.clear()
        typingShiftButton = null
        typingLetters.clear()
        addView(buildModifierRow())
        addView(buildNavigationRow())
        if (fnLayer) {
            for (start in listOf(1, 7)) addView(row(*(start until start + 6).map { i ->
                key("F$i", textSp = 16f, repeatOnHold = true) { emitKeyPress("F$i") }
            }.toTypedArray()))
        }
        if (symbols) buildSymbols() else buildLetters()
        addView(buildBottomRow())
        refreshModHighlights()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        post { buildAll() }
    }

    fun releaseAll() {
        cancelKeyTouches()
        for ((code, held) in chordMods) if (held) onKey?.invoke(code, "up")
        chordMods.keys.forEach { chordMods[it] = false }
        stickyShift = false
        typingShift = TypingShift.OFF
        lastTypingShiftTapAt = -1L
        buildAll()
    }

    private fun key(
        label: String,
        weight: Float = 1f,
        special: Boolean = false,
        pill: Boolean = false,
        compact: Boolean = false,
        textSp: Float = keyTextSp,
        icon: String? = null,
        resetsShiftTap: Boolean = true,
        repeatOnHold: Boolean = false,
        pressOnTouchDown: Boolean = false,
        onTap: () -> Unit,
    ): Button = KeyboardButton(context, icon, repeatOnHold) { pressOnTouchDown || heldTypingShift }.apply {
        text = if (icon == null) label else ""
        contentDescription = label
        isAllCaps = false
        isSingleLine = true
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        minWidth = 0; minimumWidth = 0
        minHeight = 0; minimumHeight = 0
        backgroundTintList = null
        stateListAnimator = null
        val radius = if (pill) 100f else 7f
        val fill = if (special) SPECIAL_COLOR else KEY_COLOR
        background = keyBackground(fill, radius)
        // Store the normal background so active modifiers keep their rounded shape.
        tag = Pair(fill, radius)
        layoutParams = LayoutParams(0, if (compact) controlHeightPx - dp(2f) else keyHeightPx, weight).apply {
            setMargins(dp(2f), dp(1f), dp(2f), if (compact) dp(1f) else rowGapPx - dp(1f))
        }
        setOnClickListener {
            if (heldTypingShift) typingShiftUsed = true
            if (resetsShiftTap) lastTypingShiftTapAt = -1L
            onTap()
        }
    }

    private fun keyBackground(color: Int, radius: Float): Drawable {
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#557F879A")), shape, null)
    }

    private fun row(vararg keys: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        isBaselineAligned = false
        keys.forEach { addView(it) }
    }

    private fun spacer(weight: Float) = View(context).apply {
        layoutParams = LayoutParams(0, 1, weight)
    }

    private fun buildModifierRow(): LinearLayout {
        fun mod(label: String, code: String) = key(label, compact = true, special = true, textSp = 12f) {
            val held = chordMods[code] != true
            chordMods[code] = held
            onKey?.invoke(code, if (held) "down" else "up")
            refreshModHighlights()
        }.also { modButtons[code] = it }
        return row(
            mod("Ctrl", "CTRL"),
            key("Shift", compact = true, special = true, textSp = 12f) {
                stickyShift = !stickyShift
                buildAll()
            }.also {
                it.contentDescription = "Sticky Shift"
                highlight(it, stickyShift)
            },
            mod("Win", "WIN"), mod("Alt", "ALT"),
            key("Fn", compact = true, special = true, textSp = 12f) { fnLayer = !fnLayer; buildAll() }
                .also { highlight(it, fnLayer) },
            control("Esc", "ESC"), control("Tab", "TAB"),
            key("Phone keyboard", compact = true, special = true, icon = "keyboard") { onOpenIme?.invoke() },
        )
    }

    private fun control(label: String, code: String) =
        key(label, compact = true, special = true, textSp = 11f,
            repeatOnHold = true, pressOnTouchDown = true) { emitKeyPress(code) }

    private fun buildNavigationRow() = row(
        control("Del", "DELETE"), control("Home", "HOME"), control("End", "END"),
        control("PgUp", "PGUP"), control("PgDn", "PGDN"),
        control("◀", "LEFT").apply { contentDescription = "Left arrow" },
        control("▲", "UP").apply { contentDescription = "Up arrow" },
        control("▼", "DOWN").apply { contentDescription = "Down arrow" },
        control("▶", "RIGHT").apply { contentDescription = "Right arrow" },
    )

    private fun printable(glyph: String) = key(glyph, repeatOnHold = true) { emitPrintable(glyph, glyph.uppercase()) }

    private fun buildLetters() {
        fun letter(char: Char) = printable(if (uppercaseLetters) char.uppercase() else char.toString())
            .also { typingLetters.add(it) }
        addView(row(*"qwertyuiop".map { letter(it) }.toTypedArray()))
        addView(row(spacer(.5f), *"asdfghjkl".map { letter(it) }.toTypedArray(), spacer(.5f)))
        val shiftKey = key("Shift", 1.5f, special = true,
            icon = if (typingShift == TypingShift.CAPS_LOCK) "caps-lock" else "shift",
            resetsShiftTap = false) { toggleTypingShift() }
        typingShiftButton = shiftKey
        addView(row(shiftKey, *"zxcvbnm".map { letter(it) }.toTypedArray(), backspace()))
        refreshTypingShift()
    }

    private fun refreshTypingShift() {
        typingShiftButton?.let { button ->
            highlight(button, heldTypingShift || typingShift != TypingShift.OFF)
            ViewCompat.setStateDescription(button, if (heldTypingShift) "Held" else when (typingShift) {
                TypingShift.OFF -> "Off"
                TypingShift.ONCE -> "Next letter"
                TypingShift.CAPS_LOCK -> "Caps Lock"
            })
        }
        for (button in typingLetters) {
            val glyph = button.text.toString()
            button.text = if (uppercaseLetters) glyph.uppercase() else glyph.lowercase()
            button.contentDescription = button.text
        }
    }

    private fun buildSymbols() {
        val first = if (extraSymbols) listOf("~", "`", "|", "•", "√", "π", "÷", "×", "<", ">")
            else "1234567890".map { it.toString() }
        val second = if (extraSymbols) listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\")
            else listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
        val third = if (extraSymbols) listOf("_", "©", "®", "™", "[", "]", "!")
            else listOf("!", "/", ";", ":", "'", "\"", "?")
        addView(row(*first.map { printable(it) }.toTypedArray()))
        addView(row(*second.map { printable(it) }.toTypedArray()))
        addView(row(
            key(if (extraSymbols) "?123" else "=\\<", 1.5f, special = true, textSp = 16f) {
                extraSymbols = !extraSymbols; buildAll()
            },
            *third.map { printable(it) }.toTypedArray(), backspace(),
        ))
    }

    private fun backspace() = key("Backspace", 1.5f, special = true, icon = "backspace",
        repeatOnHold = true, pressOnTouchDown = true) { emitKeyPress("BACKSPACE") }

    private fun buildBottomRow() = row(
        key(if (symbols) "ABC" else "?123", 1.5f, special = true, pill = true, textSp = 16f) {
            symbols = !symbols; extraSymbols = false; buildAll()
        },
        key(",", special = true, repeatOnHold = true) { emitPrintable(",", ",") },
        key("Emoji and phone keyboard", icon = "emoji") { onOpenIme?.invoke() },
        key("English", 4f, textSp = 14f, repeatOnHold = true) { emitPrintable(" ", "SPACE") }
            .apply { contentDescription = "Space" },
        key(".", special = true, repeatOnHold = true) { emitPrintable(".", ".") },
        key("Enter", 1.5f, special = true, pill = true, icon = "enter", repeatOnHold = true) { emitKeyPress("ENTER") },
    )

    private fun refreshModHighlights() {
        for ((code, button) in modButtons) highlight(button, chordMods[code] == true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun highlight(button: Button, on: Boolean) {
        val (color, radius) = button.tag as Pair<Int, Float>
        button.background = keyBackground(if (on) Color.parseColor("#8096CD") else color, radius)
        button.isSelected = on
    }

    private fun emitPrintable(glyph: String, baseCode: String) {
        val letter = glyph.length == 1 && glyph[0].isLetter()
        if (heldTypingShift || chordMods.values.any { it }) {
            emitKeyPress(baseCode, stickyShift || heldTypingShift || (letter && typingShift == TypingShift.ONCE))
        } else onText?.invoke(if (letter) {
            if (uppercaseLetters) glyph.uppercase() else glyph.lowercase()
        } else glyph)
        if (letter && typingShift == TypingShift.ONCE) {
            typingShift = TypingShift.OFF
            // Update in place: rebuilding would detach a held letter and stop its repeat.
            refreshTypingShift()
        }
    }

    private fun toggleTypingShift() {
        val now = SystemClock.uptimeMillis()
        typingShift = when {
            typingShift == TypingShift.ONCE && lastTypingShiftTapAt >= 0 &&
                now - lastTypingShiftTapAt <= ViewConfiguration.getDoubleTapTimeout() -> TypingShift.CAPS_LOCK
            typingShift == TypingShift.OFF -> TypingShift.ONCE
            else -> TypingShift.OFF
        }
        lastTypingShiftTapAt = if (typingShift == TypingShift.ONCE) now else -1L
        buildAll()
    }

    private fun emitKeyPress(code: String, shifted: Boolean = stickyShift || heldTypingShift) {
        val temporaryShift = shifted && !heldTypingShift
        if (temporaryShift) onKey?.invoke("SHIFT", "down")
        onKey?.invoke(code, "press")
        if (temporaryShift) onKey?.invoke("SHIFT", "up")
    }

    private companion object {
        val KEY_COLOR = Color.parseColor("#242424")
        val SPECIAL_COLOR = Color.parseColor("#41485C")
    }
}

@SuppressLint("ViewConstructor") // Constructed in code, never inflated from XML.
private class KeyboardButton(
    context: Context,
    icon: String?,
    private val repeatOnHold: Boolean,
    private val pressOnTouchDown: () -> Boolean,
) : AppCompatButton(context) {
    private val keyIcon = icon?.let { KeyboardIcon(it, resources.displayMetrics.density) }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var immediateTouch = false
    private var customTouch = false
    private var repeated = false
    private val repeatPress = object : Runnable {
        override fun run() {
            if (!isPressed || !isEnabled || !isShown) {
                stopRepeating()
                return
            }
            repeated = true
            performClick()
            if (isPressed) postDelayed(this, 50L)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            immediateTouch = pressOnTouchDown()
            customTouch = immediateTouch || repeatOnHold
        }
        if (!customTouch) return super.onTouchEvent(event)
        if (!isEnabled) {
            stopRepeating()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopRepeating()
                repeated = false
                isPressed = true
                drawableHotspotChanged(event.x, event.y)
                if (immediateTouch) performClick()
                if (repeatOnHold && isPressed) postDelayed(repeatPress, 400L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.x < -touchSlop || event.x >= width + touchSlop ||
                    event.y < -touchSlop || event.y >= height + touchSlop) stopRepeating()
            }
            MotionEvent.ACTION_UP -> {
                // Quick taps on typing keys fire on release, so sliding off a mistyped key cancels it.
                val tap = isPressed && !immediateTouch && !repeated
                stopRepeating()
                if (tap) performClick()
            }
            MotionEvent.ACTION_CANCEL -> stopRepeating()
        }
        return true
    }

    private fun stopRepeating() {
        removeCallbacks(repeatPress)
        isPressed = false
    }

    override fun onDetachedFromWindow() {
        stopRepeating()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        keyIcon?.let {
            val saved = canvas.save()
            canvas.translate(scrollX.toFloat(), scrollY.toFloat())
            it.setBounds(0, 0, width, height)
            it.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }
}

/** Outline icons keep the Gboard proportions without depending on font glyphs. */
private class KeyboardIcon(private val icon: String, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E4E8F5")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    override fun draw(canvas: Canvas) {
        val size = minOf(24f * density, bounds.height() * .70f)
        canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        val path = Path()
        when (icon) {
            "shift", "caps-lock" -> {
                path.moveTo(12f, 2f); path.lineTo(22f, 12f); path.lineTo(16f, 12f)
                path.lineTo(16f, 22f); path.lineTo(8f, 22f); path.lineTo(8f, 12f)
                path.lineTo(2f, 12f); path.close()
                if (icon == "caps-lock") {
                    path.moveTo(8f, 24f); path.lineTo(16f, 24f)
                }
            }
            "backspace" -> {
                path.moveTo(8f, 4f); path.lineTo(22f, 4f); path.lineTo(22f, 20f)
                path.lineTo(8f, 20f); path.lineTo(1f, 12f); path.close()
                path.moveTo(11f, 8f); path.lineTo(18f, 16f)
                path.moveTo(18f, 8f); path.lineTo(11f, 16f)
            }
            "enter" -> {
                path.moveTo(21f, 5f); path.lineTo(21f, 13f); path.lineTo(3f, 13f)
                path.moveTo(9f, 7f); path.lineTo(3f, 13f); path.lineTo(9f, 19f)
            }
            "keyboard" -> {
                canvas.drawRoundRect(1f, 4f, 23f, 20f, 2f, 2f, paint)
                for (y in listOf(8f, 12f)) for (x in listOf(5f, 9f, 13f, 17f))
                    canvas.drawPoint(x, y, paint)
                path.moveTo(6f, 16f); path.lineTo(18f, 16f)
            }
            "emoji" -> {
                canvas.drawCircle(12f, 12f, 10f, paint)
                canvas.drawCircle(8f, 9f, .8f, paint)
                canvas.drawCircle(16f, 9f, .8f, paint)
                canvas.drawArc(6f, 6f, 18f, 18f, 20f, 140f, false, paint)
            }
        }
        canvas.drawPath(path, paint)
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Android")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
