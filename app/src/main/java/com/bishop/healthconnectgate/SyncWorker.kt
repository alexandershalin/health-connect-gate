package com.bishop.healthconnectgate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("bridge_sync", Context.MODE_PRIVATE)
        val domain = prefs.getString("gateway_domain", BuildConfig.DEFAULT_GATEWAY_DOMAIN)!!
        if (domain.isBlank()) return@withContext Result.failure() // nothing to sync to until the user enters a domain
        // A failed promotion (missing permission/type, background start limits) must not abort or crash the sync.
        runCatching { setForeground(createForegroundInfo("Preparing")) }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            DiagnosticLogger(applicationContext) { domain }.record("worker_foreground", "Could not run the worker as a foreground service", it)
        }
        return@withContext try {
            SyncEngine(applicationContext, HealthConnectClient.getOrCreate(applicationContext), { domain }) { updateNotification(it) }.run()
            Result.success()
        } catch (error: AuthRequiredException) {
            DiagnosticLogger(applicationContext) { domain }.record("worker_sync", "Sign-in required; not retrying", error)
            SyncStatusStore(applicationContext).failure(error)
            Result.failure()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            DiagnosticLogger(applicationContext) { domain }.record("worker_sync", "SyncWorker failure", error)
            SyncStatusStore(applicationContext).failure(error)
            // A dropped connection is worth another try soon (the run continues where it stopped); anything else waits for the next period.
            if (ErrorClassifier.shouldRetryWork(error, runAttemptCount)) Result.retry() else Result.failure()
        }
    }
    private fun updateNotification(text: String) { applicationContext.getSystemService(NotificationManager::class.java).notify(ID, notification(text)) }
    private fun notification(text: String): Notification { val manager = applicationContext.getSystemService(NotificationManager::class.java); if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Health synchronization", NotificationManager.IMPORTANCE_LOW)); return if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(applicationContext, CHANNEL).setContentTitle(applicationContext.getString(R.string.app_name)).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build() else Notification.Builder(applicationContext).setContentTitle(applicationContext.getString(R.string.app_name)).setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build() }
    private fun createForegroundInfo(text: String) = if (android.os.Build.VERSION.SDK_INT >= 34) ForegroundInfo(ID, notification(text), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else ForegroundInfo(ID, notification(text))
    companion object { const val UNIQUE_NAME = "health-connect-periodic-sync"; private const val CHANNEL = "health_sync"; private const val ID = 4102
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }

        /** Hourly sync, but only with a network connection and a battery that is not low; a transient failure is repeated after 30 s, 1 min, 2 min... */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
