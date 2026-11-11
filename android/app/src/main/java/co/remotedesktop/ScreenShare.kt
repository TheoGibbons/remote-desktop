package co.remotedesktop

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build

/** Android's own prompt for sharing the screen. */
internal object ScreenShare {
    /**
     * The intent that shows the prompt, asking for the whole screen. Left to
     * itself, Android 14 and later offers "a single app" first, and a share
     * of one app breaks taps: a viewer sends them as fractions of the whole
     * screen. Asked this way, a Samsung on Android 17 comes up on "Share
     * entire screen" with the other choice greyed out.
     */
    fun consentIntent(context: Context): Intent {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
    }
}
