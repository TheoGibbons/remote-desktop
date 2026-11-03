package co.remotedesktop

import android.content.Context
import com.google.android.material.materialswitch.MaterialSwitch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Same sandbox as ConnectionHistoryTest, which also opens SettingsActivity.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class ViewerSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun clearSettings() =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()

    @Before
    fun setup() {
        clearSettings()
        Prefs(context).autoConnect = false
        ConnectionManager.stop()
        connectionField("appContext").set(ConnectionManager, null)
        ConnectionManager.init(context)
    }

    @After
    fun cleanup() {
        ConnectionManager.stop()
        connectionField("appContext").set(ConnectionManager, null)
        clearSettings()
    }

    private fun showLastSeenSwitch(action: (MaterialSwitch) -> Unit) {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            action(controller.get().findViewById(R.id.showLastSeenAreasSwitch))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun showLastSeenAreasIsOnByDefaultAndTheSwitchSavesIt() {
        assertTrue(Prefs(context).showLastSeenAreas)
        showLastSeenSwitch { switch ->
            assertTrue(switch.isChecked)
            switch.performClick()
            assertFalse(Prefs(context).showLastSeenAreas)
        }
        // Reopening shows what was saved, and switching back on saves that too.
        showLastSeenSwitch { switch ->
            assertFalse(switch.isChecked)
            switch.performClick()
            assertTrue(Prefs(context).showLastSeenAreas)
        }
    }
}
