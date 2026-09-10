package co.remotedesktop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the relay connection alive so the paired
 * desktop can reach this phone (control, file browsing) without the app
 * being open on screen. Survives the launcher activity being swiped away
 * and restarts after reboots via [BootReceiver].
 *
 * Screen *sharing* still requires the one-tap MediaProjection consent
 * (an Android platform rule); this service only maintains the session.
 */
class ConnectionService : Service() {

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIF_ID = 2

        fun start(context: Context) {
            val i = Intent(context, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotif()
        ConnectionManager.start(applicationContext)
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Connection", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Desktop")
            .setContentText("Connected to the session — paired devices can reach this phone")
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
