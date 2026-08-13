package co.joypilot.remotedesktop

import android.content.Context
import android.os.Build

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", BuildConfig.DEFAULT_RELAY_SERVER_URL) ?: BuildConfig.DEFAULT_RELAY_SERVER_URL
        set(v) = sp.edit().putString("serverUrl", v).apply()

    var sessionKey: String
        get() = sp.getString("sessionKey", "") ?: ""
        set(v) = sp.edit().putString("sessionKey", v).apply()

    var deviceName: String
        get() = sp.getString("deviceName", Build.MODEL) ?: Build.MODEL
        set(v) = sp.edit().putString("deviceName", v).apply()

    var autoConnect: Boolean
        get() = sp.getBoolean("autoConnect", true)
        set(v) = sp.edit().putBoolean("autoConnect", v).apply()

    /** Consent: paired devices may view this screen and inject taps/keys. */
    var allowControl: Boolean
        get() = sp.getBoolean("allowControl", true)
        set(v) = sp.edit().putBoolean("allowControl", v).apply()

    /** Consent: paired devices may browse this phone's storage and push files to it. */
    var allowFileAccess: Boolean
        get() = sp.getBoolean("allowFileAccess", true)
        set(v) = sp.edit().putBoolean("allowFileAccess", v).apply()

    /** Stable per-install id so the relay replaces this device's stale
     *  connection on reconnect instead of listing it twice. */
    val deviceUid: String
        get() {
            var uid = sp.getString("deviceUid", null)
            if (uid == null) {
                uid = java.util.UUID.randomUUID().toString().replace("-", "")
                sp.edit().putString("deviceUid", uid).apply()
            }
            return uid
        }
}
