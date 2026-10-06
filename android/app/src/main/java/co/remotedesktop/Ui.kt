package co.remotedesktop

import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.SecureRandom
import java.util.Base64

/** Small dialogs and helpers shared by the settings-style screens. */
object Ui {

    fun applySystemBarInsets(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        applySystemBarInsets(content.getChildAt(0))
    }

    /** Keep controls clear of system bars/cutouts with Android 15+ edge-to-edge.
     * Preserve the original padding across repeated dispatches and leave IME
     * information available to the viewer's keyboard/occlusion tracking. */
    fun applySystemBarInsets(
        root: View,
        includeIme: Boolean = true,
        onInsets: (WindowInsetsCompat) -> Unit = {},
    ) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        val safeAreaTypes = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            if (Build.VERSION.SDK_INT >= 35) {
                val safeArea = insets.getInsets(safeAreaTypes)
                val imeBottom = if (includeIme) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
                view.setPadding(
                    left + safeArea.left, top + safeArea.top, right + safeArea.right,
                    bottom + maxOf(safeArea.bottom, imeBottom),
                )
            }
            onInsets(insets)
            if (Build.VERSION.SDK_INT >= 35) {
                // These insets have been handled here; children must not add them again.
                WindowInsetsCompat.Builder(insets)
                    .setInsets(safeAreaTypes, Insets.NONE)
                    .setDisplayCutout(null)
                    .build()
            } else insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** A random session key that passes [SessionKeyPolicy]. */
    fun generateSessionKey(): String {
        var key: String
        do {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } while (SessionKeyPolicy.weaknessOf(key) != null) // ~1 in 3000 random keys contains a run
        return key
    }

    /** Approve/deny prompt for a device whose fingerprint isn't trusted yet. */
    fun showApprovalDialog(activity: Activity, req: PeerAuth.PendingRequest) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("New device wants to connect")
            .setMessage(
                "\"${req.name}\" (${req.device}) wants to pair with this phone.\n\n" +
                    "Device code: ${PeerAuth.shortCode(req.fingerprint)}\n\n" +
                    "Only allow if that device shows the same code. Once approved it can " +
                    "view, control and browse this phone whenever those permissions are enabled."
            )
            .setPositiveButton("Allow") { _, _ -> PeerAuth.approve(req.fingerprint) }
            .setNegativeButton("Deny") { _, _ -> PeerAuth.deny(req.fingerprint) }
            .setCancelable(true) // dismissed = still pending; the Devices screen keeps the buttons
            .show()
    }

    /** Single-field text prompt used for device name and server URL. */
    fun showTextDialog(
        context: Context,
        title: String,
        initial: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onSave: (String) -> Unit,
    ) {
        val box = EditText(context).apply {
            setText(initial)
            this.inputType = inputType
            setSelection(text.length)
        }
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(box)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("Save") { _, _ -> onSave(box.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Restart the connection with the current prefs and keep the background service up. */
    fun reconnect(context: Context) {
        ConnectionManager.stop()
        ConnectionManager.start(context)
        // Foreground service keeps the session alive so the desktop can reach
        // this phone without the app being open (ConnectionManager.start is
        // idempotent, so the service won't open a second socket).
        ConnectionService.start(context)
    }
}
