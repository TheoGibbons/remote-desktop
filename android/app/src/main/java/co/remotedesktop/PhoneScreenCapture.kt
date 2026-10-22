package co.remotedesktop

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Routes phone viewing to the optional smooth stream, or persistent Accessibility capture. */
object PhoneScreenCapture {
    private val main = Handler(Looper.getMainLooper())
    private val viewers = ConcurrentHashMap.newKeySet<String>()
    private var appContext: Context? = null
    val stateListeners = CopyOnWriteArrayList<() -> Unit>()

    val isAvailable: Boolean
        get() = ScreenCaptureService.isRunning ||
            (Build.VERSION.SDK_INT >= 30 && InputAccessibilityService.instance?.canCaptureScreen == true)

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PeerAuth.changeListeners.add { refresh() }
    }

    fun onViewRequested(context: Context, peerId: String) {
        init(context)
        if (!Prefs(context).allowControl || !PeerAuth.isTrusted(peerId)) return
        viewers.add(peerId)
        main.post {
            if (!viewers.contains(peerId)) return@post
            val setupHint = if (Build.VERSION.SDK_INT >= 30)
                "Enable Screen view and control (Accessibility) in Permissions & Access"
            else "Enable screen sharing in Permissions & Access to view this phone"
            val message = if (isAvailable) "The desktop started viewing this phone" else setupHint
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            // An additional viewer needs dimensions even if the source is already streaming.
            ScreenCaptureService.resendScreenInfo()
            InputAccessibilityService.instance?.resendScreenInfo()
        }
        refresh()
    }

    /** Whether this peer has asked to view this phone and not stopped since. */
    fun isViewing(peerId: String): Boolean = viewers.contains(peerId)

    /** Clear requests on disconnect/revocation so a later session never resumes an old view. */
    fun onViewStopped(peerId: String? = null) {
        if (peerId == null) viewers.clear() else viewers.remove(peerId)
        refresh()
    }

    /** Rechecked before capture and before sending, including after asynchronous screenshots. */
    fun maySendFrames(context: Context): Boolean =
        ConnectionManager.state == "connected" && Prefs(context).allowControl &&
            PeerAuth.allPeersTrusted && viewers.any { PeerAuth.isTrusted(it) }

    fun refresh() {
        main.post {
            val context = appContext ?: return@post
            viewers.removeAll { !PeerAuth.isTrusted(it) }
            val active = maySendFrames(context)
            val smooth = ScreenCaptureService.isRunning
            // Stop the old source before starting the new one.
            if (smooth) {
                InputAccessibilityService.instance?.setScreenStreaming(false)
                ScreenCaptureService.setStreaming(active)
            } else {
                ScreenCaptureService.setStreaming(false)
                InputAccessibilityService.instance?.setScreenStreaming(active)
            }
            stateListeners.forEach { it() }
        }
    }
}
