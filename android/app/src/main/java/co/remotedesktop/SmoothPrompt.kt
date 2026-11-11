package co.remotedesktop

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Does what [SmoothSharing] decides when a viewer asks for smooth sharing to
 * start or stop: shows Android's prompt, takes it away, starts and stops
 * [ScreenCaptureService], and sends `smooth-status`. It holds no rules.
 *
 * The prompt is Android's, and only a person answers it: someone holding the
 * phone, or someone at a viewer who sees it in the picture and clicks its
 * button there. Nothing here presses it.
 *
 * Android lets an app whose Accessibility service it has bound start an
 * activity with no window open, which is how the prompt gets on the screen.
 * Where that does not work (the service is off, or a phone refuses anyway) a
 * notification asks instead, and tapping it opens the prompt.
 *
 * Messages arrive on the network thread; everything here runs on the main one.
 */
internal object SmoothPrompt {
    private const val TAG = "SmoothPrompt"
    private const val CHANNEL_ID = "requests"
    private const val NOTIFICATION_ID = 3
    /** How long the prompt's activity has to appear before the notification asks instead. */
    internal const val SHOW_WAIT_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    internal var rules = SmoothSharing()
    private var appContext: Context? = null

    /** The activity under the prompt, while there is one. */
    private var activity: ScreenShareRequestActivity? = null
    /** The device a `stop-smooth` that is being carried out came from. */
    private var stoppedBy: String? = null
    private var listeningForScreenOff = false

    private val tick = Runnable { run(rules.tick(SystemClock.uptimeMillis())) }
    private val notShown = Runnable { promptDidNotAppear() }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = ended(SmoothSharing.Outcome.SCREEN_OFF)
    }

    /** A prompt asked for by a viewer is up, or the notification standing in for it. */
    val pending: Boolean get() = rules.prompting

    /** `smooth` in `screen-info`. */
    fun offer(context: Context): String = SmoothSharing.offer(ScreenCaptureService.isRunning, canShowPrompt(context),
        PhoneScreenCapture.canViewWithoutSharing)

    private fun canShowPrompt(context: Context) =
        InputAccessibilityService.instance != null || PermissionsActivity.notificationsOn(context)

    /** `request-smooth` or `stop-smooth` from [from], with what is known of that device. */
    fun handle(context: Context, from: String, type: String, asker: SmoothSharing.Asker) {
        val app = context.applicationContext
        main.post {
            appContext = app
            val sharing = ScreenCaptureService.isRunning
            if (type == "stop-smooth") {
                val steps = rules.stop(from, asker, sharing, PhoneScreenCapture.canViewWithoutSharing)
                if (SmoothSharing.StopSharing in steps)
                    stoppedBy = ConnectionManager.peers.firstOrNull { it.id == from }?.name ?: "a viewer"
                run(steps)
                return@post
            }
            val phone = SmoothSharing.Phone(
                sharing = sharing,
                inUse = LockState.read(app).inUse,
                sending = PhoneScreenCapture.maySendFrames(app),
                canShowPrompt = canShowPrompt(app),
                otherCapture = PhoneScreenCapture.canViewWithoutSharing,
            )
            run(rules.request(from, asker, phone, SystemClock.uptimeMillis()))
        }
    }

    /** Who is viewing has changed ([PhoneScreenCapture.refresh]). Main thread. */
    fun viewersNow(viewing: Collection<String>) = run(rules.viewersNow(viewing))

    /** [ScreenCaptureService] is up and has the screen. Main thread. */
    fun sharingStarted() = run(rules.started())

    /** [ScreenCaptureService] has gone; [wasRunning] is whether it ever had the screen. Main thread. */
    fun sharingEnded(context: Context, wasRunning: Boolean) {
        val by = stoppedBy
        stoppedBy = null
        val locked = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        run(rules.ended(wasRunning, by, locked, PhoneScreenCapture.statusRecipients()))
    }

    private fun run(steps: List<SmoothSharing.Step>) {
        for (step in steps) when (step) {
            is SmoothSharing.Tell -> for (to in step.to) tell(to, step.state, step.reason)
            SmoothSharing.ShowPrompt -> showPrompt()
            SmoothSharing.ClosePrompt -> closePrompt()
            SmoothSharing.StopSharing -> appContext?.let { ScreenCaptureService.stop(it) }
        }
        watch()
    }

    private fun tell(to: String, state: String, reason: String?) {
        val msg = JSONObject().put("type", "smooth-status").put("to", to).put("state", state)
        if (reason != null) msg.put("reason", reason)
        ConnectionManager.sendJson(msg)
    }

    /** Keeps the clock and the screen-off listener going exactly while a prompt is up. */
    private fun watch() {
        main.removeCallbacks(tick)
        val context = appContext ?: return
        val due = rules.giveUpAt
        if (due != null) main.postDelayed(tick, (due - SystemClock.uptimeMillis()).coerceAtLeast(0))
        else main.removeCallbacks(notShown)
        val listen = due != null
        if (listen == listeningForScreenOff) return
        listeningForScreenOff = listen
        try {
            if (listen) context.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            else context.unregisterReceiver(screenOff)
        } catch (_: RuntimeException) {
            // Not registered after all. The prompt still goes when its time is up.
        }
    }

    private fun showPrompt() {
        val context = appContext ?: return
        // From the service: Android lets a process it has bound an Accessibility service in start
        // an activity with no window open. Without the service there is only the notification.
        val service = InputAccessibilityService.instance
        if (service == null) {
            notifyInstead(context)
            return
        }
        try {
            service.startActivity(ScreenShareRequestActivity.intent(service))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start the prompt", e)
        }
        // A start Android refuses fails without a word. So look whether it came.
        main.removeCallbacks(notShown)
        main.postDelayed(notShown, SHOW_WAIT_MS)
    }

    private fun promptDidNotAppear() {
        val context = appContext ?: return
        if (!rules.prompting || activity != null) return
        notifyInstead(context)
    }

    /** "Tap to see the prompt": a notification's tap may always open an activity. */
    private fun notifyInstead(context: Context) {
        if (!PermissionsActivity.notificationsOn(context)) {
            ended(SmoothSharing.Outcome.NOT_SHOWN)
            return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Requests from your devices", NotificationManager.IMPORTANCE_HIGH))
            val asker = rules.waitingFor.firstOrNull()
                ?.let { id -> ConnectionManager.peers.firstOrNull { it.id == id }?.name } ?: "A paired device"
            val open = PendingIntent.getActivity(context, 0, ScreenShareRequestActivity.intent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle("$asker is asking for smooth video")
                .setContentText("Tap to see Android's prompt, and choose there.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            for (to in rules.waitingFor) tell(to, SmoothSharing.PROMPTING, SmoothSharing.TAP_NOTIFICATION)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not post the notification", e)
            ended(SmoothSharing.Outcome.NOT_SHOWN)
        }
    }

    private fun closePrompt() {
        main.removeCallbacks(notShown)
        activity?.dismiss()
        activity = null
        appContext?.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun ended(outcome: SmoothSharing.Outcome) = run(rules.answered(outcome, SystemClock.uptimeMillis()))

    // ---------------- called by the activity under the prompt ----------------

    /** The activity is on the screen. False when nobody is waiting for a prompt any more. */
    fun shown(shown: ScreenShareRequestActivity): Boolean {
        appContext = shown.applicationContext
        if (!rules.prompting) return false
        // One prompt at a time: a second activity has nothing to ask.
        val current = activity
        if (current != null && current !== shown && !current.isDestroyed) return false
        activity = shown
        main.removeCallbacks(notShown)
        // It was the notification's tap that opened it.
        shown.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        return true
    }

    /** Android's prompt was answered. Starts sharing if it was approved, and it was still wanted. */
    fun answered(from: ScreenShareRequestActivity, resultCode: Int, data: Intent?) {
        if (activity !== from) return
        activity = null
        if (!rules.prompting) return
        val granted = resultCode == Activity.RESULT_OK && data != null
        ended(if (granted) SmoothSharing.Outcome.GRANTED else SmoothSharing.Outcome.DECLINED)
        if (!granted) return
        try {
            ScreenCaptureService.start(from, resultCode, data!!)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start sharing", e)
            sharingEnded(from, wasRunning = false)
        }
    }

    /** The activity went without an answer: Android closed it, or its task was swiped away. */
    fun gone(from: ScreenShareRequestActivity) {
        if (activity !== from) return
        activity = null
        if (rules.prompting) ended(SmoothSharing.Outcome.DECLINED)
    }
}

/**
 * Stands under Android's screen-sharing prompt when a viewer has asked for
 * smooth sharing: shows the prompt, and hands its answer to [SmoothPrompt].
 * It draws nothing itself.
 *
 * Not `noHistory`: Android delivers no result to such an activity, and the
 * result is the consent. A turned phone must not make it ask twice, so it
 * handles the turn itself, and if Android makes it anew all the same it only
 * waits for the answer to the prompt that is already up.
 */
class ScreenShareRequestActivity : Activity() {
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(ASKED) == true
        if (!SmoothPrompt.shown(this)) {
            // A notification tapped after the request was given up, or the app was restarted under the prompt.
            if (asked) finishActivity(REQUEST)
            finish()
            return
        }
        if (asked) return
        asked = true
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ScreenShare.consentIntent(this), REQUEST)
        } catch (e: RuntimeException) {
            Log.w("SmoothPrompt", "Android has no prompt to show", e)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED, asked)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST) return
        SmoothPrompt.answered(this, resultCode, data)
        finish()
    }

    /** Takes Android's prompt off the screen, and this with it. Nobody is told by this. */
    fun dismiss() {
        if (asked) finishActivity(REQUEST)
        finish()
    }

    override fun onDestroy() {
        // Made anew for a turn, it is still the same request, and the new one takes over.
        if (isFinishing) SmoothPrompt.gone(this)
        super.onDestroy()
    }

    companion object {
        private const val REQUEST = 1
        private const val ASKED = "asked"

        fun intent(context: Context): Intent =
            Intent(context, ScreenShareRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
