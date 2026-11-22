package co.remotedesktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

/** Shared by the main-screen and Session ID QR scanner shortcuts, and by
 *  Session ID → Show QR code, which lets another phone scan this one. */
object SessionPairing {
    data class Settings(val key: String, val server: String? = null)

    fun scanOptions() = ScanOptions().apply {
        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        setPrompt("Scan the QR code from the desktop app, or from Session ID → Show QR code on another phone")
        setBeepEnabled(false)
        setOrientationLocked(true)
    }

    /** The same pairing code the desktop app's QR window shows. */
    fun payload(settings: Settings): String = JSONObject().apply {
        put("v", 1)
        settings.server?.takeIf { it.isNotBlank() }?.let { put("server", it) }
        put("key", settings.key)
    }.toString()

    fun qrMatrix(contents: String): BitMatrix =
        QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 4,
        ))

    /** Black on white with the quiet zone included, so it scans in dark mode too.
     *  [size] is rounded down to whole pixels per module to keep edges sharp. */
    fun qrBitmap(contents: String, size: Int): Bitmap {
        val matrix = qrMatrix(contents)
        val scale = (size / matrix.width).coerceAtLeast(1)
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { i ->
            if (matrix[i % side / scale, i / side / scale]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
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
