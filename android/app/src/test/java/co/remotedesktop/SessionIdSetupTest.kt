package co.remotedesktop

import android.content.Context
import android.os.Looper
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowEnvironment

/** UI setup does not exercise the hardware-backed Android identity key. */
@Implements(value = PeerAuth::class, isInAndroidSdk = false)
class ShadowSessionPeerAuth {
    @Implementation
    fun init(context: Context) = Unit

    @Implementation
    fun getOwnFingerprint() = "a3f29c41a3f29c41"
}

@Implements(Environment::class)
class ShadowSessionEnvironment : ShadowEnvironment() {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager() = false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class SessionIdSetupTest {
    private lateinit var prefs: Prefs
    private val validKey = "R8qT2mZ9pK5vN7xB4dF6sW1j"

    @Before
    fun resetSettings() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
        prefs.autoConnect = false
        prefs.serverUrl = "" // Saving must not contact a relay in UI tests.
    }

    @Test
    fun mainCardShowsMissingIdOpensSetupAndRefreshesAfterReturn() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val subtitle = activity.findViewById<TextView>(R.id.sessionIdSubtitle)
            assertEquals(activity.getString(R.string.session_id_required), subtitle.text.toString())
            assertEquals(activity.getColor(R.color.rd_danger), subtitle.currentTextColor)
            activity.findViewById<View>(R.id.sessionIdCard).performClick()
            assertEquals(SessionIdActivity::class.java.name,
                shadowOf(activity).nextStartedActivity.component?.className)
            controller.pause()
            prefs.sessionKey = validKey
            controller.resume()
            assertEquals("R8qT2mZ9pK5vN7xB4dF6sW1...", subtitle.text.toString())
            assertNotEquals(activity.getColor(R.color.rd_danger), subtitle.currentTextColor)
            assertNotNull(activity.findViewById<View>(R.id.scanQrBtn))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun blankAndWeakInputCannotReplaceSavedIdAndValidInputIsSaved() {
        prefs.sessionKey = validKey
        val controller = Robolectric.buildActivity(SessionIdActivity::class.java).setup()
        try {
            val activity = controller.get()
            val box = activity.findViewById<TextInputEditText>(R.id.sessionKeyBox)
            val input = activity.findViewById<TextInputLayout>(R.id.sessionKeyInput)
            val save = activity.findViewById<MaterialButton>(R.id.saveBtn)
            assertEquals(validKey, box.text.toString())
            assertEquals(View.GONE, save.visibility)
            box.setText("   ")
            save.performClick()
            assertEquals(activity.getString(R.string.session_id_required), input.error.toString())
            assertEquals(validKey, prefs.sessionKey)
            box.setText("password12345678")
            save.performClick()
            assertTrue(input.error.toString().contains("too easy to guess"))
            assertEquals(validKey, prefs.sessionKey)
            val newKey = "Q9wE2rT7yU4iO8pA6sD1fG5h"
            box.setText(" $newKey ")
            save.performClick()
            assertEquals(newKey, prefs.sessionKey)
            assertNull(input.error)
            assertEquals(View.GONE, save.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun firstRunShowsErrorAndGenerateRequiresConfirmationThenSavesStrongId() {
        val controller = Robolectric.buildActivity(SessionIdActivity::class.java).setup()
        try {
            val activity = controller.get()
            val input = activity.findViewById<TextInputLayout>(R.id.sessionKeyInput)
            val generate = activity.findViewById<MaterialButton>(R.id.regenerateBtn)
            assertEquals(activity.getString(R.string.session_id_required), input.error.toString())
            assertEquals("Generate new session ID", generate.text.toString())
            generate.performClick()
            assertEquals("", prefs.sessionKey)
            (ShadowDialog.getLatestDialog() as AlertDialog)
                .getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", prefs.sessionKey)
            generate.performClick()
            (ShadowDialog.getLatestDialog() as AlertDialog)
                .getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(SessionKeyPolicy.weaknessOf(prefs.sessionKey))
            assertEquals(prefs.sessionKey, activity.findViewById<TextInputEditText>(R.id.sessionKeyBox).text.toString())
            assertNull(input.error)
            assertNotNull(activity.findViewById<View>(R.id.scanQrBtn))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun unsavedInputSurvivesActivityRecreationWithoutChangingSavedId() {
        prefs.sessionKey = validKey
        val controller = Robolectric.buildActivity(SessionIdActivity::class.java).setup()
        try {
            controller.get().findViewById<TextInputEditText>(R.id.sessionKeyBox).setText("unfinished draft")
            controller.recreate()
            val activity = controller.get()
            assertEquals("unfinished draft", activity.findViewById<TextInputEditText>(R.id.sessionKeyBox).text.toString())
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.saveBtn).visibility)
            assertEquals(validKey, prefs.sessionKey)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun sharedQrParserAcceptsDesktopAndBareKeysAndRejectsWeakJsonWithoutSaving() {
        val server = "wss://relay.example/ws"
        val json = JSONObject().put("v", 1).put("server", server).put("key", validKey).toString()
        assertEquals(SessionPairing.Settings(validKey, server), SessionPairing.parse(json))
        assertEquals(SessionPairing.Settings(validKey), SessionPairing.parse(" $validKey "))
        assertNull(SessionPairing.parse("$validKey\n"))
        prefs.sessionKey = validKey
        val weakJson = JSONObject().put("server", server).put("key", "password12345678").toString()
        assertFalse(SessionPairing.applyScannedSettings(RuntimeEnvironment.getApplication(), weakJson))
        assertEquals(validKey, prefs.sessionKey)
        assertEquals("", prefs.serverUrl)
    }
}
