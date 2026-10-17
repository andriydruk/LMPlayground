package com.druk.lmplayground.inference

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.druk.lmplayground.MainActivity
import com.druk.lmplayground.R

internal object InferenceNotification {
    // v2 because pre-1.5.3 dev builds created an "inference_engine" channel
    // at IMPORTANCE_LOW; once a channel exists, Android caches its settings
    // and won't honor an importance change on the same id. Bumping the id
    // gets MIN importance applied for those users on first launch of 1.5.3.
    const val CHANNEL_ID = "inference_engine_v2"
    private const val LEGACY_CHANNEL_ID = "inference_engine"
    const val NOTIFICATION_ID = 0x1A1A_CAFE.toInt()

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Tidy up the orphaned LOW-importance channel from 1.5.2 dev builds.
        if (nm.getNotificationChannel(LEGACY_CHANNEL_ID) != null) {
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.inference_notification_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = context.getString(R.string.inference_notification_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        title: String? = null,
        text: String? = null,
        actionBody: String? = null,
    ): Notification {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title ?: context.getString(R.string.inference_notification_title))
            .setContentText(text ?: context.getString(R.string.inference_notification_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Defer for up to 10s — short loads never make the notification
            // visible at all, and longer sessions surface it only under the
            // "Silent" group at the bottom of the shade.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)

        // "Copy" / "Share" act on the last response. Embedding the text in
        // the PendingIntents keeps them self-contained (survives the UI
        // process being killed), but the whole notification must be
        // parceled when posted — guard against pathologically long
        // responses blowing the Binder transaction limit.
        if (!actionBody.isNullOrEmpty() && actionBody.length <= MAX_ACTION_BODY_CHARS) {
            builder
                .addAction(
                    R.drawable.ic_content_copy,
                    context.getString(R.string.copy),
                    responseActionIntent(
                        context, ResponseActionActivity.ACTION_COPY, actionBody, requestCode = 1,
                    ),
                )
                .addAction(
                    R.drawable.ic_share,
                    context.getString(R.string.share),
                    responseActionIntent(
                        context, ResponseActionActivity.ACTION_SHARE, actionBody, requestCode = 2,
                    ),
                )
        }
        return builder.build()
    }

    private fun responseActionIntent(
        context: Context,
        action: String,
        body: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ResponseActionActivity::class.java).apply {
            this.action = action
            putExtra(ResponseActionActivity.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ~60 K UTF-16 chars: well under the ~1 MB Binder limit, yet large
    // enough that essentially every real model response keeps its buttons.
    private const val MAX_ACTION_BODY_CHARS = 60_000
}
