package co.remotedesktop

import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible hop between a received-file notification and the app that opens
 * the file or the Downloads folder. Android picks the user's default app or
 * asks which one; if nothing can handle it, this says so instead of the tap
 * doing nothing.
 */
class ReceivedFileActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        val (target, failure) = when {
            intent.action == Intent.ACTION_VIEW && uri != null ->
                // The type comes from MediaStore or FileProvider when this starts.
                Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) to "No app can open this file"
            intent.action == DownloadManager.ACTION_VIEW_DOWNLOADS ->
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS) to "No app can show Downloads"
            else -> null to null
        }
        if (target != null) {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(applicationContext, failure, Toast.LENGTH_LONG).show()
            } catch (e: SecurityException) {
                // Granting the viewer read access can fail once the file is gone.
                Toast.makeText(
                    applicationContext, "Can't open this file. It may have been moved or deleted.", Toast.LENGTH_LONG
                ).show()
            }
        }
        finish()
    }
}
