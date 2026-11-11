package co.remotedesktop

import co.remotedesktop.LockScreenReader.Area
import co.remotedesktop.LockScreenReader.Entry
import co.remotedesktop.LockScreenReader.Found
import co.remotedesktop.LockScreenReader.Node
import org.junit.Assert.*
import org.junit.Test

/** Finding the pattern grid, the PIN keys or the password field in a plain
 *  list of what is on the unlock screen. No phone and no Android here. */
class LockScreenReaderTest {
    private val ui = "com.android.systemui:id/"

    private fun node(id: String? = null, bounds: String = "[0,0][0,0]", text: String? = null, description: String? = null,
                     clickable: Boolean = false, editable: Boolean = false, className: String = "android.view.View"): Node {
        val (l, t, r, b) = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").matchEntire(bounds)!!.destructured
        return Node(id?.let { ui + it }, className, text, description, l.toInt(), t.toInt(), r.toInt(), b.toInt(), clickable, editable)
    }

    /**
     * The S26's pattern screen (SM-S942W, Android 17, 1080 x 2340), as an
     * accessibility service saw it on 2026-11-10: the message line, one
     * clickable square for the whole grid, Emergency call and the lock icon.
     * The grid's bounds and its id, the message's words and its id are as
     * recorded in PHONE-UNLOCK-PLAN.md. The other views' ids and bounds were
     * not written down and are filled in here.
     */
    private val s26Pattern = listOf(
        node("keyguard_host_view", "[0,0][1080,2340]", className = "android.widget.FrameLayout"),
        node("lock_icon", "[504,560][576,632]", className = "android.widget.ImageView"),
        node("sec_bouncer_message_area", "[90,700][990,780]", text = "For your security, draw your pattern.",
            className = "android.widget.TextView"),
        node("lockPatternView", "[157,1203][922,1968]", clickable = true),
        node("emergency_call_button", "[390,2080][690,2176]", text = "Emergency call", clickable = true,
            className = "android.widget.Button"),
    )

    @Test
    fun theS26sPatternGridIsFoundAsOneSquare() {
        val found = LockScreenReader.find(s26Pattern, 1080, 2340)
        // 157/1080, 1203/2340, 765/1080, 765/2340.
        assertEquals(Entry.Pattern(Area(0.1454, 0.5141, 0.7083, 0.3269)), found.entry)
        assertEquals("For your security, draw your pattern.", found.message)
    }

    @Test
    fun theMessageFollowsWhatTheLockScreenSays() {
        fun message(text: String?) = LockScreenReader.find(s26Pattern.map {
            if (it.idName == "sec_bouncer_message_area") it.copy(text = text) else it
        }, 1080, 2340).message
        assertEquals("Incorrect pattern drawn", message("Incorrect pattern drawn"))
        assertEquals("Try again in 30 seconds", message("  Try again in 30 seconds "))
        assertNull(message(null))
        assertNull(message("   "))
        // Android's own lock screen has two lines; both are passed on, once each.
        val two = listOf(
            node("bouncer_primary_message_area", "[90,500][990,560]", text = "Wrong pattern"),
            node("bouncer_secondary_message_area", "[90,560][990,620]", text = "Try again in 28 seconds"),
            node("keyguard_message_area", "[90,620][990,680]", text = "Wrong pattern"),
            node("lockPatternView", "[157,1203][922,1968]", clickable = true),
        )
        assertEquals("Wrong pattern Try again in 28 seconds", LockScreenReader.find(two, 1080, 2340).message)
        // Never more than a line or two, whatever a phone puts there.
        assertEquals(200, message("x".repeat(5000))!!.length)
    }

    /** Android's own PIN pad on a 1080 x 2400 screen: twelve keys in four rows,
     *  each a group named `key<digit>` holding the digit and its letters. */
    private val aospPin: List<Node> = buildList {
        add(node("keyguard_pin_view", "[0,0][1080,2400]", className = "android.widget.LinearLayout"))
        add(node("keyguard_message_area", "[90,520][990,600]", text = "Enter PIN", className = "android.widget.TextView"))
        add(node("pinEntry", "[240,640][840,760]", description = "PIN area"))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("delete", "0", "enter"))
        for ((r, row) in rows.withIndex()) for ((c, name) in row.withIndex()) {
            val left = 150 + c * 280
            val top = 900 + r * 260
            val bounds = "[$left,$top][${left + 220},${top + 220}]"
            when (name) {
                "delete" -> add(node("delete_button", bounds, description = "Delete", clickable = true,
                    className = "android.widget.ImageButton"))
                "enter" -> add(node("key_enter", bounds, description = "Enter", clickable = true,
                    className = "android.widget.ImageButton"))
                else -> {
                    add(node("key$name", bounds, description = name, clickable = true, className = "android.view.ViewGroup"))
                    add(node("digit_text", "[${left + 80},${top + 60}][${left + 140},${top + 130}]", text = name,
                        className = "android.widget.TextView"))
                    add(node("klondike_text", "[${left + 70},${top + 130}][${left + 150},${top + 170}]", text = "ABC",
                        className = "android.widget.TextView"))
                }
            }
        }
        add(node("emergency_call_button", "[390,2180][690,2276]", text = "Emergency call", clickable = true))
    }

    @Test
    fun androidsOwnPinPadIsFoundKeyByKey() {
        val found = LockScreenReader.find(aospPin, 1080, 2400)
        val pin = found.entry as Entry.Pin
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "delete", "ok"), pin.keys.map { it.label })
        // The whole key, not the digit drawn on it: 150/1080, 900/2400, 220/1080, 220/2400.
        assertEquals(Area(0.1389, 0.375, 0.2037, 0.0917), pin.keys.first { it.label == "1" }.rect)
        // 0 is in the middle of the bottom row, delete to its left, ok to its right.
        assertEquals(Area(0.3981, 0.7, 0.2037, 0.0917), pin.keys.first { it.label == "0" }.rect)
        assertEquals(0.1389, pin.keys.first { it.label == "delete" }.rect.x, 0.0)
        assertEquals(0.6574, pin.keys.first { it.label == "ok" }.rect.x, 0.0)
        assertEquals("Enter PIN", found.message)
    }

    @Test
    fun aPinPadWithOtherNamesIsFoundByWhatItsKeysSay() {
        // No ids at all: ten things to press that say a digit, and two that say what they do.
        val unnamed = buildList {
            for (d in 0..9) add(Node(text = d.toString(), left = 100 * (d % 3) + 100, top = 300 * (d / 3) + 600,
                right = 100 * (d % 3) + 180, bottom = 300 * (d / 3) + 680, clickable = true))
            add(Node(description = "Backspace", left = 500, top = 1500, right = 580, bottom = 1580, clickable = true))
            add(Node(description = "OK", left = 600, top = 1500, right = 680, bottom = 1580, clickable = true))
        }
        val pin = LockScreenReader.find(unnamed, 1080, 2340).entry as Entry.Pin
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "delete", "ok"), pin.keys.map { it.label })

        // Digits that are only drawn, on keys that say nothing: still where to tap.
        val drawn = (0..9).map { d -> Node(text = d.toString(), left = 100, top = 100 * d + 600, right = 180, bottom = 100 * d + 680) }
        val bare = LockScreenReader.find(drawn, 1080, 2340).entry as Entry.Pin
        assertEquals(10, bare.keys.size)
        assertNull("A pad with no delete key has none", bare.keys.firstOrNull { it.label == "delete" })

        // A pad auto-confirms when it has no Enter: that key is simply left out.
        val noEnter = aospPin.filter { it.idName != "key_enter" }
        assertEquals(11, (LockScreenReader.find(noEnter, 1080, 2400).entry as Entry.Pin).keys.size)
    }

    @Test
    fun aFewDigitsOnAScreenAreNotAPinPad() {
        // A lock screen's clock and a notification count.
        val clock = listOf(node("clock_hour", "[400,400][500,560]", text = "1"), node("clock_minute", "[520,400][620,560]", text = "2"),
            node("badge", "[900,100][940,140]", text = "3"))
        assertEquals(Found.NOTHING, LockScreenReader.find(clock, 1080, 2340))
        // Nine of the ten keys.
        assertNull(LockScreenReader.find(aospPin.filter { it.idName != "key7" && it.text != "7" }, 1080, 2400).entry)
    }

    @Test
    fun aPasswordFieldIsFound() {
        val password = listOf(
            node("keyguard_password_view", "[0,0][1080,2340]"),
            node("keyguard_message_area", "[90,700][990,780]", text = "Enter password"),
            node("passwordEntry", "[120,900][960,1020]", editable = true, className = "android.widget.EditText"),
        )
        val found = LockScreenReader.find(password, 1080, 2340)
        assertSame(Entry.Password, found.entry)
        assertEquals("Enter password", found.message)
    }

    @Test
    fun theLockScreenItselfHasNothingToEnter() {
        // Clock, date, a notification and the shortcuts: what is up before the swipe.
        val lockScreen = listOf(
            node("notification_panel", "[0,0][1080,2340]"),
            node("clock_view", "[300,300][780,620]", text = "12:30"),
            node("date_view", "[300,640][780,700]", text = "Wed, 11 November"),
            node("notification_row", "[40,900][1040,1100]", text = "Dinner at 7?", clickable = true),
            node("start_button", "[60,2100][180,2220]", description = "Phone", clickable = true),
            node("keyguard_indication_text", "[200,2200][880,2260]", text = "Swipe to unlock"),
        )
        assertEquals(Found.NOTHING, LockScreenReader.find(lockScreen, 1080, 2340))
        assertEquals(Found.NOTHING, LockScreenReader.find(emptyList(), 1080, 2340))
    }

    @Test
    fun aGridThatIsNotOnTheScreenIsNotReported() {
        fun grid(bounds: String) = LockScreenReader.find(listOf(node("lockPatternView", bounds, clickable = true)), 1080, 2340).entry
        assertNotNull(grid("[157,1203][922,1968]"))
        assertNull("no size", grid("[157,1203][157,1203]"))
        assertNull("off the screen", grid("[157,1203][1300,1968]"))
        assertNull("a screen with no size", LockScreenReader.find(s26Pattern, 0, 0).entry)
        // Another app's view that happens to share the name is still only looked at
        // if its window is the system's: that is decided before the list is made.
        assertEquals("lockPatternView", Node(id = "com.example:id/lockPatternView").idName)
    }

    @Test
    fun theGridWinsOverAnythingElseOnTheSameScreen() {
        // A phone could show both a grid and a field (it doesn't today); the order is fixed.
        val both = s26Pattern + node("passwordEntry", "[120,900][960,1020]", editable = true)
        assertTrue(LockScreenReader.find(both, 1080, 2340).entry is Entry.Pattern)
        val pinAndField = aospPin + node("passwordEntry", "[120,700][960,820]", editable = true)
        assertTrue(LockScreenReader.find(pinAndField, 1080, 2400).entry is Entry.Pin)
    }
}
