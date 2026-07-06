package co.joypilot.remotedesktop

import android.content.Context
import android.os.Build

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "ws://") ?: "ws://"
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
}
