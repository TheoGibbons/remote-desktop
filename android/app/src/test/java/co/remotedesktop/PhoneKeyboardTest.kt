package co.remotedesktop

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneKeyboardTest {

    @Test
    fun mapsEditingKeysAndShortcutLetters() {
        assertEquals(KeyEvent.KEYCODE_DEL, PhoneKeyboard.keyCode("BACKSPACE"))
        assertEquals(KeyEvent.KEYCODE_FORWARD_DEL, PhoneKeyboard.keyCode("DELETE"))
        assertEquals(KeyEvent.KEYCODE_MOVE_HOME, PhoneKeyboard.keyCode("HOME"))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, PhoneKeyboard.keyCode("LEFT"))
        assertEquals(KeyEvent.KEYCODE_A, PhoneKeyboard.keyCode("A"))
        assertEquals(KeyEvent.KEYCODE_Z, PhoneKeyboard.keyCode("Z"))
        // Characters arrive as text, and Esc is Back rather than a key.
        assertNull(PhoneKeyboard.keyCode("a"))
        assertNull(PhoneKeyboard.keyCode("1"))
        assertNull(PhoneKeyboard.keyCode("ESC"))
        assertNull(PhoneKeyboard.keyCode(""))
    }

    @Test
    fun plainPressIsDownThenUp() {
        val events = PhoneKeyboard.press(KeyEvent.KEYCODE_DEL, ctrl = false, shift = false, time = 100)
        assertEquals(
            listOf("DOWN DEL 0", "UP DEL 0"),
            events.map(::describe),
        )
        assertTrue(events.all { it.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0 })
    }

    @Test
    fun heldModifiersArePressedAroundTheKey() {
        // A text field selects with Shift+arrow only after the Shift key's own
        // press, so it comes first and is let go last.
        val events = PhoneKeyboard.press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true, shift = true, time = 100)
        assertEquals(
            listOf(
                "DOWN SHIFT_LEFT S",
                "DOWN CTRL_LEFT SC",
                "DOWN DPAD_LEFT SC",
                "UP DPAD_LEFT SC",
                "UP CTRL_LEFT S",
                "UP SHIFT_LEFT 0",
            ),
            events.map(::describe),
        )
    }

    @Test
    fun ctrlShortcutCarriesCtrl() {
        val key = PhoneKeyboard.press(KeyEvent.KEYCODE_A, ctrl = true, shift = false, time = 100)
            .single { it.keyCode == KeyEvent.KEYCODE_A && it.action == KeyEvent.ACTION_DOWN }
        assertTrue(key.isCtrlPressed)
        assertFalse(key.isShiftPressed)
    }

    @Test
    fun enterPerformsTheFieldsAction() {
        assertEquals(EditorInfo.IME_ACTION_SEARCH, PhoneKeyboard.enterAction(editor(EditorInfo.IME_ACTION_SEARCH)))
        assertEquals(EditorInfo.IME_ACTION_SEND, PhoneKeyboard.enterAction(editor(EditorInfo.IME_ACTION_SEND)))
        val custom = editor(EditorInfo.IME_ACTION_DONE).apply {
            actionLabel = "Post"
            actionId = 42
        }
        assertEquals(42, PhoneKeyboard.enterAction(custom))
    }

    @Test
    fun enterIsAKeyWhereTheFieldHasNoAction() {
        // TextView sets this on every multi-line field: Enter starts a new line.
        assertNull(PhoneKeyboard.enterAction(editor(EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)))
        assertNull(PhoneKeyboard.enterAction(editor(EditorInfo.IME_ACTION_NONE)))
        assertNull(PhoneKeyboard.enterAction(editor(EditorInfo.IME_ACTION_UNSPECIFIED)))
        assertNull(PhoneKeyboard.enterAction(null))
    }

    private fun editor(imeOptions: Int) = EditorInfo().apply { this.imeOptions = imeOptions }

    private fun describe(e: KeyEvent): String {
        val action = if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
        val meta = buildString {
            if (e.isShiftPressed) append('S')
            if (e.isCtrlPressed) append('C')
        }.ifEmpty { "0" }
        return "$action ${names[e.keyCode] ?: e.keyCode} $meta"
    }

    // Robolectric's KeyEvent.keyCodeToString() gives numbers.
    private val names = mapOf(
        KeyEvent.KEYCODE_DEL to "DEL",
        KeyEvent.KEYCODE_DPAD_LEFT to "DPAD_LEFT",
        KeyEvent.KEYCODE_SHIFT_LEFT to "SHIFT_LEFT",
        KeyEvent.KEYCODE_CTRL_LEFT to "CTRL_LEFT",
    )
}
