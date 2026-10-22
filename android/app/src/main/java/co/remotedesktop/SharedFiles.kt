package co.remotedesktop

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Prefer EXTRA_STREAM: ClipData may contain a preview rather than another attachment. */
object SharedFiles {
    fun fromIntent(intent: Intent): List<Uri> {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return emptyList()
        return try {
            val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            val uris = streams.ifEmpty {
                intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
                    ?: listOfNotNull(intent.data)
            }.distinct()
            // An exported activity must never open file:// paths supplied by another app.
            if (uris.any { it.scheme != "content" }) emptyList() else uris
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /** Text shared on its own, such as a link, goes to the PC's clipboard rather
     *  than its Downloads. A share that attaches a file stays a file share, and
     *  clipData is ignored: for a link it is often just a preview image. */
    fun textFromIntent(intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return null
        return try {
            if (IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) != null) null
            else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        } catch (_: RuntimeException) {
            null
        }
    }
}
