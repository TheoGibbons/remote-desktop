package co.remotedesktop

import android.graphics.Bitmap
import android.graphics.Color
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

    @Test
    fun eachStateHasItsWords() {
        // Awake and unlocked: nothing, however dark the app on screen is.
        assertNull(LockCaption.text(state(true, false), pictureBlack = false))
        assertNull(LockCaption.text(state(true, false), pictureBlack = true))

        // Asleep, whatever the picture and whether or not it is locked.
        for (locked in listOf(false, true)) for (black in listOf(false, true)) {
            assertEquals(R.string.phone_viewer_asleep, LockCaption.text(state(false, locked), black))
            assertEquals(R.string.phone_viewer_wake_failed_notifications,
                LockCaption.text(state(false, locked, "notifications"), black))
            assertEquals(R.string.phone_viewer_wake_failed, LockCaption.text(state(false, locked, "unknown"), black))
            // A reason a later app might give is still a wake that failed.
            assertEquals(R.string.phone_viewer_wake_failed, LockCaption.text(state(false, locked, "something-new"), black))
        }

        // Locked with the lock screen in the picture: it says "Swipe to unlock" itself.
        assertNull(LockCaption.text(state(true, true), pictureBlack = false))
        // Locked and black: the unlock screen.
        assertEquals(R.string.phone_viewer_unlock_hidden, LockCaption.text(state(true, true), pictureBlack = true))

        // A phone that has never said: only the picture to go by.
        assertNull(LockCaption.text(null, pictureBlack = false))
        assertEquals(R.string.phone_viewer_black, LockCaption.text(null, pictureBlack = true))
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
        fun parse(json: String) = LockCaption.parse(JSONObject(json))
        // As the phone builds them: the same texts as in LockStateTest (lockStateIsBuiltAsTheViewersReadIt).
        assertEquals(state(true, false), parse("""{"type":"lock-state","to":"pc","screenOn":true,"locked":false}"""))
        assertEquals(state(false, true, "notifications"),
            parse("""{"type":"lock-state","to":"pc","screenOn":false,"locked":true,"wakeFailed":"notifications"}"""))
        // Fields a later phone may add are ignored.
        assertEquals(state(true, true),
            parse("""{"type":"lock-state","screenOn":true,"locked":true,"entry":{"kind":"pattern","area":[0.1,0.5,0.7,0.3]},"message":"Draw your pattern"}"""))
        // A wake can't have failed on a phone whose screen is on.
        assertEquals(state(true, false), parse("""{"screenOn":true,"locked":false,"wakeFailed":"notifications"}"""))
        assertEquals(state(false, false), parse("""{"screenOn":false,"locked":false,"wakeFailed":7}"""))

        assertNull(parse("""{"type":"lock-state"}"""))
        assertNull(parse("""{"screenOn":true}"""))
        assertNull(parse("""{"screenOn":"yes","locked":false}"""))
        assertNull(parse("""{"screenOn":1,"locked":0}"""))
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
