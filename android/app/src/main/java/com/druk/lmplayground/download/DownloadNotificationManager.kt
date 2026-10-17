package com.druk.lmplayground.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.druk.lmplayground.R
import kotlin.math.absoluteValue

class DownloadNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "model_downloads"
        private const val NOTIFICATION_ID_BASE = 100_000
        const val ACTION_CANCEL_DOWNLOAD = "com.druk.lmplayground.CANCEL_DOWNLOAD"
        const val EXTRA_WORK_NAME = "work_name"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.download_notification_channel_description)
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun getNotificationId(modelName: String): Int {
        return NOTIFICATION_ID_BASE + modelName.hashCode().absoluteValue % 50_000
    }

    fun buildProgressNotification(
        modelName: String,
        progress: Float,
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long,
        workName: String
    ): android.app.Notification {
        val notificationId = getNotificationId(modelName)

        val cancelIntent = Intent(ACTION_CANCEL_DOWNLOAD).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_WORK_NAME, workName)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = buildProgressText(bytesDownloaded, totalBytes, speedBytesPerSec, etaSeconds)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(modelName)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                R.drawable.ic_baseline_close,
                context.getString(R.string.cancel),
                cancelPendingIntent
            )

        if (progress >= 0f && totalBytes > 0) {
            builder.setProgress(1000, (progress * 1000).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    fun buildCopyingNotification(modelName: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(modelName)
            .setContentText(context.getString(R.string.moving_to_storage))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun buildCompleteNotification(modelName: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(modelName)
            .setContentText(context.getString(R.string.download_complete))
            .setAutoCancel(true)
            .build()
    }

    fun buildFailureNotification(modelName: String, error: String): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(modelName)
            .setContentText(error)
            .setAutoCancel(true)
            .build()
    }

    fun showNotification(notificationId: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    fun cancelNotification(notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(notificationId)
    }

    private fun buildProgressText(
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBytesPerSec: Long,
        etaSeconds: Long
    ): String {
        val parts = mutableListOf<String>()

        if (totalBytes > 0) {
            val downloaded = Formatter.formatFileSize(context, bytesDownloaded)
            val total = Formatter.formatFileSize(context, totalBytes)
            parts.add("$downloaded / $total")
        } else if (bytesDownloaded > 0) {
            parts.add(Formatter.formatFileSize(context, bytesDownloaded))
        }

        if (speedBytesPerSec > 0) {
            parts.add("${Formatter.formatFileSize(context, speedBytesPerSec)}/s")
        }

        if (etaSeconds > 0) {
            parts.add(formatEta(etaSeconds))
        }

        return parts.joinToString(" \u2022 ")
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s left"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s left"
            else -> {
                val hours = seconds / 3600
                val mins = (seconds % 3600) / 60
                "${hours}h ${mins}m left"
            }
        }
    }

    class CancelDownloadReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CANCEL_DOWNLOAD) return

            val workName = intent.getStringExtra(EXTRA_WORK_NAME) ?: return
            val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

            WorkManager.getInstance(context).cancelUniqueWork(workName)

            if (notificationId != -1) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.cancel(notificationId)
            }
        }
    }
}
