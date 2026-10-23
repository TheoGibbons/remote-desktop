package co.remotedesktop

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo

/**
 * What the paired PC's keyboard does on this phone (`key` and `text`, see
 * PROTOCOL.md). [InputAccessibilityService] plays the result through its own
 * input method (Android 13+), which edits the focused text field the way the
 * on-screen keyboard does, alongside it rather than instead of it.
 */
internal object PhoneKeyboard {

    /** The Android key code for a protocol key name, or null for one the phone has no use for. */
    fun keyCode(code: String): Int? = when (code) {
        "ENTER" -> KeyEvent.KEYCODE_ENTER
        "TAB" -> KeyEvent.KEYCODE_TAB
        "BACKSPACE" -> KeyEvent.KEYCODE_DEL
        "DELETE" -> KeyEvent.KEYCODE_FORWARD_DEL
        "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
        "END" -> KeyEvent.KEYCODE_MOVE_END
        "PGUP" -> KeyEvent.KEYCODE_PAGE_UP
        "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> code.singleOrNull()?.takeIf { it in 'A'..'Z' }?.let { KeyEvent.KEYCODE_A + (it - 'A') }
    }

    /**
     * The key events of one press. Held modifiers get presses of their own
     * around the key, as a hardware keyboard sends them: a text field extends
     * its selection only while a Shift key is down, and ignores the Shift in
     * the arrow's own meta state.
     */
    fun press(keyCode: Int, ctrl: Boolean, shift: Boolean, time: Long): List<KeyEvent> {
        val mods = buildList {
            if (shift) add(KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            if (ctrl) add(KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        }
        val events = ArrayList<KeyEvent>(2 + 2 * mods.size)
        var meta = 0
        for ((mod, bits) in mods) {
            meta = meta or bits
            events += event(time, KeyEvent.ACTION_DOWN, mod, meta)
        }
        events += event(time, KeyEvent.ACTION_DOWN, keyCode, meta)
        events += event(time, KeyEvent.ACTION_UP, keyCode, meta)
        for ((mod, bits) in mods.asReversed()) {
            meta = meta and bits.inv()
            events += event(time, KeyEvent.ACTION_UP, mod, meta)
        }
        return events
    }

    // Flagged as the on-screen keyboard flags its keys, so a press doesn't
    // take the app out of touch mode and start drawing focus highlights.
    private fun event(time: Long, action: Int, keyCode: Int, meta: Int) = KeyEvent(
        time, time, action, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
        KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
    )

    /**
     * The editor action a plain Enter performs in this field, as the on-screen
     * keyboard's Enter does (Search, Send, Next...), or null when Enter is
     * just a key there, e.g. a new line in a multi-line field.
     */
    fun enterAction(info: EditorInfo?): Int? {
        if (info == null || info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        if (info.actionLabel != null) return info.actionId
        return (info.imeOptions and EditorInfo.IME_MASK_ACTION)
            .takeIf { it != EditorInfo.IME_ACTION_NONE && it != EditorInfo.IME_ACTION_UNSPECIFIED }
    }
}
