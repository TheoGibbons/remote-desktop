package co.remotedesktop

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Android starts the Accessibility service by itself after an app update,
 *  with nothing else of the app running. The connection must still go through
 *  the foreground service then, or Android cuts it once the phone dozes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowSessionPeerAuth::class, ShadowSessionEnvironment::class],
    instrumentedPackages = ["co.remotedesktop"])
class AccessibilityConnectTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun connectionField(name: String) = ConnectionManager::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    @Before
    fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        ConnectionManager.stop()
        // Re-initialised against this test's application and its storage.
        connectionField("appContext").set(ConnectionManager, null)
        ConnectionManager.init(context)
        shadowOf(context).clearStartedServices()
    }

    @After
    fun cleanup() {
        ConnectionManager.stop()
        connectionField("appContext").set(ConnectionManager, null)
        ConnectionLog.clear()
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aPairedPhoneConnectsThroughTheForegroundService() {
        Prefs(context).sessionKey = "accessibility-connect-test-key"

        ConnectionService.startFromAccessibility(context)

        assertEquals(ConnectionService::class.java.name, shadowOf(context).nextStartedService?.component?.className)
        // The service connects once Android runs it; nothing connects without it.
        assertEquals("disconnected", ConnectionManager.state)
    }

    @Test
    fun aPhoneWithNoSessionStartsNoService() {
        ConnectionService.startFromAccessibility(context)

        assertNull(shadowOf(context).nextStartedService)
        assertEquals("disconnected: not configured", ConnectionManager.state)
    }

    @Test
    fun withAutoConnectOffItConnectsAsBeforeWithoutTheService() {
        Prefs(context).apply { sessionKey = "accessibility-connect-test-key"; autoConnect = false; serverUrl = "" }

        ConnectionService.startFromAccessibility(context)

        assertNull(shadowOf(context).nextStartedService)
        // Reached ConnectionManager, which had no relay address to go on.
        assertEquals("disconnected: not configured", ConnectionManager.state)
    }
}
