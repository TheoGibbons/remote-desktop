package co.remotedesktop

import android.graphics.Bitmap
import android.graphics.Color
import co.remotedesktop.LockScreenReader.Area
import co.remotedesktop.LockScreenReader.Entry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a phone viewing another phone says over a picture that is dark
 *  because that phone is asleep or on its unlock screen. The Windows app's
 *  half is CheckCaptions and CheckBlackPicture in RemoteDesktopWin.PhoneViewTests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockCaptionTest {
    private fun state(screenOn: Boolean, locked: Boolean, wakeFailed: String? = null) =
        LockCaption.State(screenOn, locked, wakeFailed)

    /** Which string the caption is made from, or null for no caption. */
    private fun text(state: LockCaption.State?, pictureBlack: Boolean): Int? {
        var used: Int? = null
        LockCaption.caption(state, pictureBlack) { used = it; "" }
        return used
    }

    private fun parse(json: String) = LockCaption.parse(JSONObject(json))

    @Test
    fun eachStateHasItsWords() {
        // Awake and unlocked: nothing, however dark the app on screen is.
        assertNull(text(state(true, false), pictureBlack = false))
        assertNull(text(state(true, false), pictureBlack = true))

        // Asleep, whatever the picture and whether or not it is locked.
        for (locked in listOf(false, true)) for (black in listOf(false, true)) {
            assertEquals(R.string.phone_viewer_asleep, text(state(false, locked), black))
            assertEquals(R.string.phone_viewer_wake_failed_notifications,
                text(state(false, locked, "notifications"), black))
            assertEquals(R.string.phone_viewer_wake_failed, text(state(false, locked, "unknown"), black))
            // A reason a later app might give is still a wake that failed.
            assertEquals(R.string.phone_viewer_wake_failed, text(state(false, locked, "something-new"), black))
        }

        // Locked with the lock screen in the picture: it says "Swipe to unlock" itself.
        assertNull(text(state(true, true), pictureBlack = false))
        // Locked and black: the unlock screen.
        assertEquals(R.string.phone_viewer_unlock_hidden, text(state(true, true), pictureBlack = true))

        // A phone that has never said: only the picture to go by.
        assertNull(text(null, pictureBlack = false))
        assertEquals(R.string.phone_viewer_black, text(null, pictureBlack = true))
    }

    @Test
    fun aBlackPictureMattersOnlyOnALockedPhoneOrOneThatNeverSays() {
        assertTrue(LockCaption.blackMatters(null))
        assertTrue(LockCaption.blackMatters(state(true, true)))
        assertFalse(LockCaption.blackMatters(state(true, false)))
        assertFalse(LockCaption.blackMatters(state(false, true)))
        assertFalse(LockCaption.blackMatters(state(false, false)))
    }

    @Test
    fun lockStateIsReadAndAnythingOddInItIsRefused() {
        // As the phone builds them: the same texts as in LockStateTest (lockStateIsBuiltAsTheViewersReadIt).
        assertEquals(state(true, false), parse("""{"type":"lock-state","to":"pc","screenOn":true,"locked":false}"""))
        assertEquals(state(false, true, "notifications"),
            parse("""{"type":"lock-state","to":"pc","screenOn":false,"locked":true,"wakeFailed":"notifications"}"""))
        // Fields a later phone may add are ignored.
        assertEquals(state(true, true),
            parse("""{"type":"lock-state","screenOn":true,"locked":true,"biometrics":{"face":true},"attempts":3}"""))
        // A wake can't have failed on a phone whose screen is on.
        assertEquals(state(true, false), parse("""{"screenOn":true,"locked":false,"wakeFailed":"notifications"}"""))
        assertEquals(state(false, false), parse("""{"screenOn":false,"locked":false,"wakeFailed":7}"""))

        assertNull(parse("""{"type":"lock-state"}"""))
        assertNull(parse("""{"screenOn":true}"""))
        assertNull(parse("""{"screenOn":"yes","locked":false}"""))
        assertNull(parse("""{"screenOn":1,"locked":0}"""))
    }

    // ---------- the unlock screen, drawn ----------

    // The same texts as in LockStateTest (theUnlockScreenIsDescribedAsTheViewersReadIt) and
    // RemoteDesktopWin.PhoneViewTests (CheckLockEntry). Change them together.
    private val sharedPattern = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1454,0.5141,0.7083,0.3269]},"message":"For your security, draw your pattern.","to":"pc"}"""
    private val sharedPin = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[{"label":"1","rect":[0.1389,0.375,0.2037,0.0917]},{"label":"0","rect":[0.3981,0.7,0.2037,0.0917]},{"label":"delete","rect":[0.1389,0.7,0.2037,0.0917]},{"label":"ok","rect":[0.6574,0.7,0.2037,0.0917]}]},"message":"Enter PIN","to":"pc"}"""
    private val sharedPassword = """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"password"},"to":"pc"}"""

    /** A whole pad: twelve keys in four rows. */
    private fun pinPad(vararg without: String): String {
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("delete", "0", "ok"))
        val keys = rows.flatMapIndexed { r, row -> row.mapIndexed { c, label ->
            if (label in without) null
            else """{"label":"$label","rect":[${0.14 + c * 0.26},${0.375 + r * 0.108},0.2,0.09]}"""
        } }.filterNotNull()
        return """{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pin","keys":[${keys.joinToString(",")}]},"message":"Enter PIN"}"""
    }

    @Test
    fun whereThePatternPinOrPasswordIsEnteredIsReadAsThePhoneBuildsIt() {
        val pattern = parse(sharedPattern)!!
        assertEquals(Entry.Pattern(Area(0.1454, 0.5141, 0.7083, 0.3269)), pattern.entry)
        assertEquals("For your security, draw your pattern.", pattern.message)

        // Four keys are not a pad: all ten digits have to be there.
        parse(sharedPin)!!.let {
            assertNull(it.entry)
            assertEquals("Enter PIN", it.message)
        }
        val pin = parse(pinPad())!!
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "delete", "0", "ok"), (pin.entry as Entry.Pin).keys.map { it.label })
        assertEquals(parse(pinPad()), pin)
        assertEquals(11, (parse(pinPad("ok"))!!.entry as Entry.Pin).keys.size)
        assertNull(parse(pinPad("7"))!!.entry)

        parse(sharedPassword)!!.let {
            assertSame(Entry.Password, it.entry)
            assertNull(it.message)
        }

        // An unlock screen is only ever on a phone that is locked with its screen on.
        assertEquals(state(true, false), parse(sharedPattern.replace("\"locked\":true", "\"locked\":false")))
        assertEquals(state(false, true), parse(sharedPattern.replace("\"screenOn\":true", "\"screenOn\":false")))

        // Anything odd: no entry, and the viewer says what it would without one.
        for (odd in listOf("null", "7", "\"pattern\"", "{}", """{"kind":"swipe"}""", """{"kind":7}""",
            """{"kind":"pattern"}""", """{"kind":"pattern","area":[0.1,0.5,0.7]}""", """{"kind":"pattern","area":[0.1,0.5,0.7,"0.3"]}""",
            """{"kind":"pattern","area":[0.1,0.5,0,0.3]}""", """{"kind":"pattern","area":[-0.1,0.5,0.7,0.3]}""",
            """{"kind":"pattern","area":[0.5,0.5,0.7,0.3]}""", """{"kind":"pattern","area":[0.1,0.9,0.7,0.3]}""",
            """{"kind":"pin"}""", """{"kind":"pin","keys":[]}""", """{"kind":"pin","keys":"1234567890"}""")) {
            assertEquals(odd, state(true, true), parse("""{"screenOn":true,"locked":true,"entry":$odd}"""))
        }
        // Keys this viewer has no use for are left out; the pad still stands.
        val extra = pinPad().replace("""{"label":"1",""",
            """{"label":"emergency","rect":[0,0,0.1,0.1]},{"label":7,"rect":[0,0,0.1,0.1]},{"label":"1","rect":[2,2,2,2]},{"label":"1",""")
        assertEquals(pin, parse(extra))

        assertEquals(state(true, true), parse("""{"screenOn":true,"locked":true,"message":"   "}"""))
        assertEquals(state(true, true), parse("""{"screenOn":true,"locked":true,"message":7}"""))
        assertEquals(LockCaption.MESSAGE_MAX, parse("""{"screenOn":true,"locked":true,"message":"${"x".repeat(5000)}"}""")!!.message!!.length)
    }

    @Test
    fun aDrawnUnlockScreenReplacesTheCaptionAndAPasswordIsAskedForInWords() {
        val pattern = parse(sharedPattern)!!
        val pin = parse(pinPad())!!
        val password = parse(sharedPassword)!!
        for (black in listOf(false, true)) {
            assertNull(text(pattern, black))
            assertNull(text(pin, black))
            assertEquals(R.string.phone_viewer_password, text(password, black))
        }
        // With the phone's own message first: it is how a wrong try is answered.
        assertEquals("Wrong password\nTYPE", LockCaption.caption(password.copy(message = "Wrong password"), true) { "TYPE" })
        assertEquals("TYPE", LockCaption.caption(password, true) { "TYPE" })

        fun heading(state: LockCaption.State?) = LockCaption.heading(state) { "#$it" }
        assertEquals("For your security, draw your pattern.", heading(pattern))
        assertEquals("#${R.string.phone_viewer_draw_pattern}", heading(pattern.copy(message = null)))
        assertEquals("Enter PIN", heading(pin))
        assertEquals("#${R.string.phone_viewer_enter_pin}", heading(pin.copy(message = null)))
        assertNull(heading(password))
        assertNull(heading(state(true, true)))
        assertNull(heading(null))
    }

    @Test
    fun theNineDotsSitAtTheCentresOfAnEvenSplitOfTheGrid() {
        // The S26: a 765 pixel square at (157, 1203) on a 1080 x 2340 screen. The same
        // numbers as CheckPatternDots in RemoteDesktopWin.PhoneViewTests.
        val area = Area(157 / 1080.0, 1203 / 2340.0, 765 / 1080.0, 765 / 2340.0)
        val dots = LockCaption.dots(area)
        assertEquals(9, dots.size)
        assertEquals(157 + 127.5, dots[0].first * 1080, 0.01)
        assertEquals(1203 + 127.5, dots[0].second * 2340, 0.01)
        assertEquals(157 + 382.5, dots[4].first * 1080, 0.01)
        assertEquals(1203 + 382.5, dots[4].second * 2340, 0.01)
        assertEquals(922 - 127.5, dots[8].first * 1080, 0.01)
        assertEquals(1968 - 127.5, dots[8].second * 2340, 0.01)

        fun at(px: Double, py: Double) = LockCaption.dotAt(px / 1080, py / 2340, area)
        for (i in 0 until 9) assertEquals(i, LockCaption.dotAt(dots[i].first, dots[i].second, area))
        // Within 0.3 of a cell (76 pixels here), as on the phone, across and down.
        assertEquals(0, at(157 + 127.5 + 70, 1203 + 127.5))
        assertEquals(-1, at(157 + 127.5 + 80, 1203 + 127.5))
        assertEquals(0, at(157 + 127.5, 1203 + 127.5 + 70))
        assertEquals(-1, at(157 + 127.5, 1203 + 127.5 + 80))
        assertEquals(-1, at(157 + 255.0, 1203 + 255.0))
        assertEquals(-1, at(10.0, 10.0))
    }

    private fun picture(width: Int, height: Int, background: Int, draw: (Bitmap) -> Unit = {}): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(background)
            draw(it)
        }

    private fun Bitmap.fill(left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val row = IntArray(right - left) { color }
        for (y in top until bottom) setPixels(row, 0, right - left, left, y, right - left, 1)
    }

    @Test
    fun aPictureIsBlackWhenNearlyAllOfItIs() {
        assertTrue(LockCaption.isNearlyBlack(picture(1080, 2340, Color.BLACK)))
        // What a JPEG leaves of black.
        assertTrue(LockCaption.isNearlyBlack(picture(1080, 2340, Color.rgb(3, 2, 4))))
        // The unlock screen's Back arrow, and the words a phone sends while its screen is off.
        assertTrue(LockCaption.isNearlyBlack(picture(1080, 2340, Color.BLACK) { it.fill(900, 2200, 960, 2260, Color.WHITE) }))
        assertTrue(LockCaption.isNearlyBlack(picture(1080, 2340, Color.BLACK) { it.fill(300, 1150, 780, 1190, Color.WHITE) }))
        // Sideways, and small.
        assertTrue(LockCaption.isNearlyBlack(picture(2340, 1080, Color.BLACK)))
        assertTrue(LockCaption.isNearlyBlack(picture(40, 90, Color.BLACK)))

        // A lock screen on a black wallpaper: a clock and a few lines.
        assertFalse(LockCaption.isNearlyBlack(picture(1080, 2340, Color.BLACK) {
            it.fill(300, 400, 780, 600, Color.WHITE)
        }))
        // A dark theme is dark grey, not black.
        assertFalse(LockCaption.isNearlyBlack(picture(1080, 2340, Color.rgb(0x17, 0x17, 0x17))))
        // One colour is enough to be lit.
        assertFalse(LockCaption.isNearlyBlack(picture(1080, 2340, Color.rgb(0, 0, 90))))
        assertFalse(LockCaption.isNearlyBlack(picture(1080, 2340, Color.WHITE)))
    }

    @Test
    fun picturesAreLookedAtTwiceASecondAtMostAndOnlyWhileItMatters() {
        var looks = 0
        var black = true
        val watch = LockCaption.BlackWatch { looks++; black }
        val frame = picture(4, 4, Color.BLACK)

        // An unlocked phone: never looked at.
        assertFalse(watch.look(frame, state(true, false), 0))
        assertEquals(0, looks)

        // Locked: the first picture is looked at, the next ones not until half a second has gone.
        assertTrue(watch.look(frame, state(true, true), 1_000))
        black = false
        assertTrue(watch.look(frame, state(true, true), 1_200))
        assertTrue(watch.look(frame, state(true, true), 1_499))
        assertEquals(1, looks)
        assertFalse(watch.look(frame, state(true, true), 1_500))
        assertEquals(2, looks)

        // The phone's state changed: the answer is forgotten and the next picture looked at.
        black = true
        watch.forget()
        assertTrue(watch.look(frame, null, 1_600))
        assertEquals(3, looks)
    }
}
