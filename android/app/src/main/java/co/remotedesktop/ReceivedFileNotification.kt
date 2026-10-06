package co.remotedesktop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

internal object ReceivedFileNotification {
    private const val CHANNEL_ID = "received_files"

    @Suppress("DEPRECATION")
    internal fun locateIntent(savedPath: String): Intent {
        // Use the final path, including MediaStore's rename for duplicate names.
        // A file initial URI opens the system picker in its containing directory.
        val relativePath = File(savedPath)
            .relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
        val documentUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:$relativePath"
        )
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, documentUri)
        }
    }

    fun show(context: Context, savedPath: String, transferId: Int) {
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
            // Go directly to the picker, including while the app is in the background.
            val locate = PendingIntent.getActivity(
                context, transferId, locateIntent(savedPath),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_remote)
                .setContentTitle("File received")
                .setContentText("${File(savedPath).name} saved to Downloads")
                .setContentIntent(locate)
                .addAction(Notification.Action.Builder(null, "Locate file", locate).build())
                .setAutoCancel(true)
                .build()
            manager.notify("received:$savedPath", 0, notification)
        } catch (e: Exception) {
            Log.w("ReceivedFileNotification", "Could not post received-file notification", e)
        }
    }
}
