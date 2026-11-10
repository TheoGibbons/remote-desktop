package co.remotedesktop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Says when a newer release is out. The relay names the newest version (in
 * its welcome, and in a `latest` message when one comes out), and this posts a
 * notification once per version that downloads the APK in the browser.
 *
 * The app doesn't install the APK itself: that needs REQUEST_INSTALL_PACKAGES,
 * which the Play Store build may not have. Installs from Play update through
 * Play, and builds that aren't tagged releases are always behind, so neither
 * is told about GitHub releases.
 */
object AppUpdate {
    private const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_TAG = "update"
    private const val PLAY_STORE = "com.android.vending"
    private val VERSION = Regex("""^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$""")

    /** Overrides whether this install hears of releases; tests set it. */
    @Volatile internal var enabledOverride: Boolean? = null

    val current: String get() = BuildConfig.VERSION_NAME

    /** The newest release the relay has named, or null before it has. */
    fun latest(context: Context): String? = Prefs(context).latestRelease.ifEmpty { null }

    /** A newer release is out, and this install should offer it. */
    fun updateAvailable(context: Context): Boolean =
        enabled(context) && latest(context)?.let { isNewer(it, current) } == true

    /** Whether [version] is newer than what the device running [have] has; false when [have] is unknown. */
    fun isNewer(version: String, have: String?): Boolean = have != null && compare(version, have) > 0

    fun downloadIntent(version: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(
        "https://github.com/TheoGibbons/remote-desktop/releases/download/v$version/RemoteDesktop-Android.apk"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The relay named the newest release. */
    fun onLatest(context: Context, version: String?) {
        if (version == null || !VERSION.matches(version)) return
        val prefs = Prefs(context)
        prefs.latestRelease = version
        if (!isNewer(version, current)) {
            // This is it, or newer: an old notification has done its job.
            cancel(context)
            return
        }
        if (!enabled(context) || prefs.updateNotified == version) return
        prefs.updateNotified = version
        notify(context, version)
    }

    // ---------- PCs on an older version ----------

    /** PCs asked to update, by peer id, with when. A PC comes back under a new
     *  id once it has restarted; one that never answers is offered again. */
    private val asked = ConcurrentHashMap<String, Long>()
    private const val ASKED_FOR_MS = 3 * 60_000L

    /** Asks a PC to download the newest release and restart into it. */
    fun askToUpdate(peerId: String) {
        asked[peerId] = SystemClock.elapsedRealtime()
        ConnectionManager.sendJson(JSONObject().put("type", "update-now").put("to", peerId))
    }

    fun isUpdating(peerId: String): Boolean =
        asked[peerId]?.let { SystemClock.elapsedRealtime() - it < ASKED_FOR_MS } == true

    /** What to tell the user about a PC's update-status, or null to say nothing. */
    fun statusText(context: Context, peerName: String, from: String, msg: JSONObject): String? =
        when (msg.optString("state")) {
            "updating" -> context.getString(R.string.update_status_updating, peerName, msg.optString("version"))
            "current" -> {
                asked.remove(from)
                context.getString(R.string.update_status_current, peerName)
            }
            "failed" -> {
                asked.remove(from)
                context.getString(R.string.update_status_failed, peerName, msg.optString("error"))
            }
            else -> null
        }

    private fun enabled(context: Context): Boolean =
        enabledOverride ?: (BuildConfig.UPDATE_CHECKS && installer(context) != PLAY_STORE)

    private fun installer(context: Context): String? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    } catch (e: Exception) {
        null
    }

    /** Orders versions such as 1.0.9 < 1.0.10. A prerelease (1.1.0-beta) comes before its release. */
    fun compare(a: String, b: String): Int {
        fun parse(v: String): Pair<List<Int>, Boolean> {
            val core = v.substringBefore('-')
            return core.split('.').map { it.toIntOrNull() ?: 0 } to (core != v)
        }
        val (pa, preA) = parse(a)
        val (pb, preB) = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return if (preA == preB) 0 else if (preA) -1 else 1
    }

    private fun notify(context: Context, version: String) {
        // Notification permission is optional; Settings still offers the update without it.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val download = PendingIntent.getActivity(context, 0, downloadIntent(version),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle(context.getString(R.string.update_notification_title, version))
                .setContentText(context.getString(R.string.update_notification_text, current))
                .setContentIntent(download)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_TAG, 0, notification)
        } catch (e: Exception) {
            Log.w("AppUpdate", "Could not post the update notification", e)
        }
    }

    private fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TAG, 0)
        } catch (_: Exception) {}
    }
}
