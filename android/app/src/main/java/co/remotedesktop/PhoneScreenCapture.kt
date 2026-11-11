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
        if (!Prefs(context).allowControl || !mayView(context, peerId)) return
        // Only a device that was not already viewing wakes the phone and is
        // announced. A viewer may ask again, and that changes nothing here.
        val started = viewers.add(peerId)
        if (started) ScreenWaker.wake(context)
        main.post {
            if (!viewers.contains(peerId)) return@post
            if (started) {
                val setupHint = if (Build.VERSION.SDK_INT >= 30)
                    "Enable Screen view and control (Accessibility) in Permissions & Access"
                else "Enable screen sharing in Permissions & Access to view this phone"
                val viewer = ConnectionManager.peers.firstOrNull { it.id == peerId }?.name ?: "A paired device"
                val message = if (isAvailable) "$viewer started viewing this phone" else setupHint
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            // An additional viewer needs dimensions even if the source is already streaming.
            ScreenCaptureService.resendScreenInfo()
            InputAccessibilityService.instance?.resendScreenInfo()
        }
        refresh()
    }

    /** Whether this peer has asked to view this phone and not stopped since. */
    fun isViewing(peerId: String): Boolean = viewers.contains(peerId)

    /** An approved PC, or an approved phone while Allow other phones is on. */
    private fun mayView(context: Context, peerId: String): Boolean =
        PeerAuth.isTrusted(peerId) && Prefs(context).allowsDevice(ConnectionManager.deviceOf(peerId))

    /** Who frames are addressed to, so a device watching another host never gets them. */
    fun recipients(): List<String> {
        val context = appContext ?: return emptyList()
        return viewers.filter { mayView(context, it) }
    }

    /** Clear requests on disconnect/revocation so a later session never resumes an old view. */
    fun onViewStopped(peerId: String? = null) {
        if (peerId == null) viewers.clear() else viewers.remove(peerId)
        // If it starts again it is told how things stand again, though nothing has changed.
        LockState.forget(peerId)
        refresh()
    }

    /** Rechecked before capture and before sending, including after asynchronous screenshots. */
    fun maySendFrames(context: Context): Boolean =
        ConnectionManager.state == "connected" && Prefs(context).allowControl &&
            PeerAuth.allPeersTrusted && viewers.any { mayView(context, it) }

    fun refresh() {
        main.post {
            val context = appContext ?: return@post
            viewers.removeAll { !mayView(context, it) }
            // A screen turned for a viewer goes back to the phone's own way with the last of them.
            if (viewers.isEmpty()) InputAccessibilityService.instance?.releaseRotation()
            val active = maySendFrames(context)
            // Each viewer is told whether the screen is on and locked, while there is one.
            LockState.watch(context, active)
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
