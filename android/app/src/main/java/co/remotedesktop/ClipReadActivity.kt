package co.remotedesktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager

/**
 * Reads the clipboard for a viewer's Copy ([PhoneClipCopy]). Android lets
 * only the app with the focus read it, so this comes to the front, invisible,
 * reads it once the focus is its own and goes again. It doesn't take touches,
 * and leaves the keyboard of the app underneath where it was, as a dialog
 * with no text field does.
 */
class ClipReadActivity : Activity() {
    companion object {
        private const val EXTRA_TOKEN = "token"
        private const val POLL_MS = 50L
        /** Gone by then whatever happened: a window that never got the focus
         *  must not stay in front of the app underneath. */
        private const val LIFETIME_MS = 3_000L

        fun intent(context: Context, token: Long): Intent =
            Intent(context, ClipReadActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }

    private val main = Handler(Looper.getMainLooper())
    private var token = -1L
    private var deadline = 0L
    private val poll = Runnable { read() }
    private val expire = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        token = intent.getLongExtra(EXTRA_TOKEN, -1)
        if (!PhoneClipCopy.wants(token)) {
            finish()
            return
        }
        main.postDelayed(expire, LIFETIME_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || deadline != 0L || isFinishing) return
        deadline = SystemClock.uptimeMillis() + PhoneClipCopy.LAND_WAIT_MS
        read()
    }

    /** Again every moment until a copy that was pressed has landed, or the wait is over. */
    private fun read() {
        if (isFinishing) return
        if (PhoneClipCopy.read(this, token, last = SystemClock.uptimeMillis() >= deadline)) finish()
        else main.postDelayed(poll, POLL_MS)
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        main.removeCallbacks(expire)
        super.onDestroy()
    }
}
