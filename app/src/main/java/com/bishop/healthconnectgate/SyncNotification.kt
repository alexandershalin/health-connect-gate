package com.bishop.healthconnectgate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** The ongoing "Health synchronization" notification, shared by the periodic worker and the manual sync service. */
internal object SyncNotification {
    private const val CHANNEL = "health_sync"

    fun build(context: Context, text: String): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Health synchronization", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(context)
        }
        return builder.setContentTitle(context.getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    }
}
