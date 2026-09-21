package com.bishop.healthconnectgate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.health.connect.client.HealthConnectClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HealthSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: NotificationManager
    override fun onCreate() {
        super.onCreate(); notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) notificationManager.createNotificationChannel(NotificationChannel(CHANNEL, "Health synchronization", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification("Preparing"))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val domain = intent?.getStringExtra("domain") ?: getSharedPreferences("bridge_sync", MODE_PRIVATE).getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN)!!
        scope.launch {
            try {
                val diagnostics = DiagnosticLogger(applicationContext) { domain }
                diagnostics.record("service_start", "HealthSyncService started")
                SyncEngine(applicationContext, HealthConnectClient.getOrCreate(applicationContext), { domain }) { phase ->
                    // "Sending ..." is reported for every batch: it belongs on the screen and in the notification, not in the diagnostics outbox.
                    if (!phase.startsWith("Sending")) diagnostics.record("sync_phase", phase)
                    update(phase)
                }.run()
            } catch (e: Throwable) {
                DiagnosticLogger(applicationContext) { domain }.record("service_sync", "HealthSyncService failure", e)
                SyncStatusStore(applicationContext).failure(e)
                update(ErrorText.describe(e))
            } finally { stopSelfResult(startId) }
        }
        return START_NOT_STICKY
    }
    private fun update(text: String) {
        notificationManager.notify(NOTIFICATION_ID, notification(text))
    }
    private fun notification(text: String): Notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL).setContentTitle(getString(R.string.app_name)).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build() else Notification.Builder(this).setContentTitle(getString(R.string.app_name)).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.coroutineContext.cancel(); super.onDestroy() }
    companion object { private const val CHANNEL = "health_sync"; private const val NOTIFICATION_ID = 4101 }
}
