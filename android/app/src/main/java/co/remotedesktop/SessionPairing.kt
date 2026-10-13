package co.remotedesktop

import android.content.Context
import android.widget.Toast
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/** Shared by the main-screen and Session ID QR scanner shortcuts. */
object SessionPairing {
    data class Settings(val key: String, val server: String? = null)

    fun scanOptions() = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Scan the QR code shown by “QR code” in the desktop app")
        setBeepEnabled(false)
        setOrientationLocked(true)
    }

    fun parse(contents: String): Settings? {
        val json = try { JSONObject(contents) } catch (_: Exception) { null }
        if (json != null) {
            val key = json.optString("key").trim()
            if (SessionKeyPolicy.weaknessOf(key) != null) return null
            return Settings(key, json.optString("server").takeIf { it.isNotBlank() })
        }
        // Older pairing codes may contain just the session key.
        val key = contents.trim()
        return if (!contents.contains('\n') && SessionKeyPolicy.weaknessOf(key) == null)
            Settings(key) else null
    }

    fun applyScannedSettings(context: Context, contents: String): Boolean {
        val settings = parse(contents)
        if (settings == null) {
            Toast.makeText(context, "That QR code is not a Remote Desktop pairing code", Toast.LENGTH_LONG).show()
            return false
        }
        val prefs = Prefs(context)
        settings.server?.let { prefs.serverUrl = it }
        prefs.sessionKey = settings.key
        Ui.reconnect(context)
        Toast.makeText(context, "Session settings scanned", Toast.LENGTH_SHORT).show()
        return true
    }
}
