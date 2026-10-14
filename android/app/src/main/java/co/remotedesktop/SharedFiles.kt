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
}
