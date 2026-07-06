package co.joypilot.remotedesktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Rejoins the session after a reboot so the desktop can reach this phone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.autoConnect && prefs.sessionKey.length >= 16) {
            ConnectionService.start(context)
        }
    }
}
