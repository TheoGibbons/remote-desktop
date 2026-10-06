package co.remotedesktop

import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35, 36])
class WindowInsetsTest {
    private fun root() = FrameLayout(RuntimeEnvironment.getApplication()).apply {
        setPadding(2, 3, 4, 5)
    }

    private fun insets(top: Int = 24, bottom: Int = 48, cutoutLeft: Int = 0, ime: Int = 0) =
        WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutoutLeft, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
            .setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
            .build()

    @Test
    fun repeatedInsetsDoNotAccumulateAndRotationReplacesTheSafeArea() {
        val root = root()
        Ui.applySystemBarInsets(root)
        repeat(2) { ViewCompat.dispatchApplyWindowInsets(root, insets()) }
        assertEquals(27, root.paddingTop)
        assertEquals(53, root.paddingBottom)

        val remaining = ViewCompat.dispatchApplyWindowInsets(root, insets(top = 0, bottom = 16, cutoutLeft = 30))
        assertEquals(32, root.paddingLeft)
        assertEquals(3, root.paddingTop)
        assertEquals(4, root.paddingRight)
        assertEquals(21, root.paddingBottom)
        assertEquals(Insets.NONE, remaining.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()))
    }

    @Test
    fun formsStayAboveTheKeyboardAndRestoreTheirPaddingWhenItCloses() {
        val root = root()
        Ui.applySystemBarInsets(root)
        ViewCompat.dispatchApplyWindowInsets(root, insets(ime = 300))
        assertEquals(305, root.paddingBottom)
        ViewCompat.dispatchApplyWindowInsets(root, insets())
        assertEquals(53, root.paddingBottom)
    }

    @Test
    fun viewerKeepsItsViewportAndReceivesKeyboardOcclusionInformation() {
        val root = root()
        var keyboardVisible = false
        Ui.applySystemBarInsets(root, includeIme = false) {
            keyboardVisible = it.isVisible(WindowInsetsCompat.Type.ime())
        }
        val remaining = ViewCompat.dispatchApplyWindowInsets(root, insets(ime = 300))
        assertEquals(53, root.paddingBottom)
        assertTrue(keyboardVisible)
        assertEquals(300, remaining.getInsets(WindowInsetsCompat.Type.ime()).bottom)
    }

    @Test
    @Config(sdk = [34])
    fun olderAndroidKeepsItsExistingDecorManagedPadding() {
        val root = root()
        Ui.applySystemBarInsets(root)
        ViewCompat.dispatchApplyWindowInsets(root, insets(ime = 300))
        assertEquals(2, root.paddingLeft)
        assertEquals(3, root.paddingTop)
        assertEquals(4, root.paddingRight)
        assertEquals(5, root.paddingBottom)
    }
}
