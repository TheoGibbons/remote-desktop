package co.remotedesktop

import android.content.Context
import android.os.Build

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    // Never backed up (see res/xml/backup_rules.xml), unlike the settings.
    private val install = context.getSharedPreferences("install", Context.MODE_PRIVATE)

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

    // Auto (un-)privacy and rememberPrivacy both decide what happens when viewing
    // starts and stops, so switching one on switches the other off.
    var autoPrivacy: Boolean
        get() = sp.getBoolean("autoPrivacy", false)
        set(v) = setAutoOption("autoPrivacy", v)

    var autoUnprivacy: Boolean
        get() = sp.getBoolean("autoUnprivacy", false)
        set(v) = setAutoOption("autoUnprivacy", v)

    private fun setAutoOption(key: String, on: Boolean) {
        val edit = sp.edit().putBoolean(key, on)
        // Switching one off also settles an unset rememberPrivacy as off, or its
        // default would switch it on once no auto option is left.
        if (on || !sp.contains("rememberPrivacy")) edit.putBoolean("rememberPrivacy", false)
        edit.apply()
    }

    /** Each PC keeps the privacy screen as it was last set in the viewer, through
     *  disconnects, and gets it back as soon as viewing starts again. On by
     *  default, unless an auto option was turned on before this setting existed. */
    var rememberPrivacy: Boolean
        get() = sp.getBoolean("rememberPrivacy", !autoPrivacy && !autoUnprivacy)
        set(v) {
            val edit = sp.edit().putBoolean("rememberPrivacy", v)
            if (v) edit.putBoolean("autoPrivacy", false).putBoolean("autoUnprivacy", false)
            edit.apply()
        }

    /** The privacy screen last set for the PC with this identity fingerprint,
     *  or null if it never was. */
    fun lastPrivacy(pcFingerprint: String): Boolean? =
        if (sp.contains("privacy:$pcFingerprint")) sp.getBoolean("privacy:$pcFingerprint", false) else null

    fun setLastPrivacy(pcFingerprint: String, enabled: Boolean) =
        sp.edit().putBoolean("privacy:$pcFingerprint", enabled).apply()

    /** Consent: paired devices may view this screen and inject taps/keys. */
    var allowControl: Boolean
        get() = sp.getBoolean("allowControl", true)
        set(v) {
            sp.edit().putBoolean("allowControl", v).apply()
            if (!v) PhoneScreenCapture.onViewStopped()
        }

    /** Consent: paired devices may browse this phone's storage and push files to it. */
    var allowFileAccess: Boolean
        get() = sp.getBoolean("allowFileAccess", true)
        set(v) = sp.edit().putBoolean("allowFileAccess", v).apply()

    /** Stable per-install id so the relay replaces this device's stale
     *  connection on reconnect instead of listing it twice. A phone restored
     *  from this one's backup must not share it, or the relay would take the
     *  two for one device and each would knock the other off. */
    val deviceUid: String
        get() {
            var uid = install.getString("deviceUid", null)
            if (uid == null) {
                uid = java.util.UUID.randomUUID().toString().replace("-", "")
                install.edit().putString("deviceUid", uid).apply()
                // Older versions kept it in the settings, which are backed up.
                sp.edit().remove("deviceUid").apply()
            }
            return uid
        }
}
