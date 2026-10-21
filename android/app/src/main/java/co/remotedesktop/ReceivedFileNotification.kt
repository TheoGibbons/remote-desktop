package co.remotedesktop

import android.Manifest
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File

internal object ReceivedFileNotification {
    private const val CHANNEL_ID = "received_files"

    // Both go through ReceivedFileActivity so a missing handler shows a message;
    // a notification launching another app directly fails silently.
    internal fun openIntent(context: Context, uri: Uri): Intent =
        Intent(context, ReceivedFileActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    internal fun showDownloadsIntent(context: Context): Intent =
        Intent(context, ReceivedFileActivity::class.java)
            .setAction(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun show(context: Context, savedPath: String, uri: Uri, transferId: Int) {
        // Notification permission is optional; denial must never fail a saved transfer.
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Received files", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(context, transferId, openIntent(context, uri), flags)
            val showDownloads = PendingIntent.getActivity(context, 0, showDownloadsIntent(context), flags)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle("File received")
                .setContentText("${File(savedPath).name} saved to Downloads")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Show in Downloads", showDownloads).build())
                .setAutoCancel(true)
                .build()
            manager.notify("received:$savedPath", 0, notification)
        } catch (e: Exception) {
            Log.w("ReceivedFileNotification", "Could not post received-file notification", e)
        }
    }
}
