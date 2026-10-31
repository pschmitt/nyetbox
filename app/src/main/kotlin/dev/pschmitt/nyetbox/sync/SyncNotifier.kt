package dev.pschmitt.nyetbox.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.MainActivity
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.repository.ChangeNotificationEvent
import dev.pschmitt.nyetbox.data.repository.ReconciliationSummary
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the progress and failure notifications for the background WorkManager sync. */
// Every notification path is guarded by notificationsAllowed(); lint does not infer that helper
// through WorkManager callbacks, so keep this boundary suppression next to the guard contract.
@SuppressLint("MissingPermission")
@Singleton
class SyncNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    @Volatile private var appInForeground = false
    @Volatile private var syncActive = false
    @Volatile private var lastProgress: SyncProgress? = null

    /**
     * Creates the notification channel once, ahead of time - required on API 26+, cheap/no-op if it
     * already exists. Call from [dev.pschmitt.nyetbox.NyetboxApp.onCreate].
     */
    fun createChannel() {
        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                    "Background sync",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "Shows background NetBox sync progress and failures"
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                }
        context.getSystemService(NotificationManager::class.java).apply {
            // The old channel was created at IMPORTANCE_DEFAULT in earlier builds. Remove it so
            // an upgrade cannot leave the noisy channel behind in the user's notification list.
            deleteNotificationChannel(LEGACY_CHANNEL_ID)
            createNotificationChannel(channel)
        }
    }

    /** Called by the single app activity when its UI becomes visible. */
    fun onAppForeground() {
        appInForeground = true
        if (syncActive) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Whether the app UI is currently visible to the user. */
    fun isAppInForeground(): Boolean = appInForeground

    /** Called when the app leaves the foreground so active sync progress becomes visible again. */
    fun onAppBackground() {
        appInForeground = false
        if (syncActive) lastProgress?.let(::postProgress)
    }

    /** Builds the foreground notification required for long-running WorkManager syncs. */
    fun foregroundInfo(message: String = "Syncing NetBox data…"): ForegroundInfo {
        syncActive = true
        lastProgress = SyncProgress(message, step = 0, totalSteps = 1)
        val notification = progressNotification(lastProgress!!)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Best-effort: silently does nothing if POST_NOTIFICATIONS (API 33+) hasn't been granted - this
     * is a nice-to-have surface, not something worth crashing the worker over.
     */
    fun notifySyncFailed(message: String?) {
        syncActive = false
        if (appInForeground) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sync_problem)
                .setContentTitle("Background sync failed")
                .setContentText(message ?: "Couldn't sync with NetBox - showing cached data")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(message ?: "Couldn't sync with NetBox - showing cached data")
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /** Shows an ongoing system-level progress notification while any sync attempt is running. */
    fun notifySyncStarted() {
        notifySyncProgress(SyncProgress("Refreshing cached data…", step = 0, totalSteps = 1))
    }

    fun notifySyncProgress(progress: SyncProgress) {
        syncActive = true
        lastProgress = progress
        if (!appInForeground) postProgress(progress)
        else NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Keeps the ongoing notification visible while WorkManager waits before retrying. */
    fun notifySyncRetry(attempt: Int) {
        notifySyncProgress(
            SyncProgress("Retrying sync (attempt $attempt)…", step = 0, totalSteps = 1)
        )
    }

    /** Removes progress after a successful sync and records any uploaded local changes. */
    fun notifySyncSucceeded(reconciliation: ReconciliationSummary = ReconciliationSummary()) {
        syncActive = false
        lastProgress = null
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        if (reconciliation.total > 0) postReconciliation(reconciliation)
    }

    private fun postProgress(progress: SyncProgress) {
        if (!notificationsAllowed()) return
        NotificationManagerCompat.from(context)
            .notify(
                NOTIFICATION_ID,
                progressNotification(progress),
            )
    }

    private fun postReconciliation(summary: ReconciliationSummary) {
        if (!notificationsAllowed()) return
        val details = summary.details()
        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_RECONCILIATION_SUMMARY, details)
            }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                RECONCILIATION_NOTIFICATION_ID,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sync_problem)
                .setContentTitle("NetBox changes uploaded")
                .setContentText(summary.shortText())
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        NotificationManagerCompat.from(context).notify(RECONCILIATION_NOTIFICATION_ID, notification)
    }

    /**
     * Posts optional change alerts only while the app is backgrounded, using the silent channel.
     */
    fun notifyNetBoxChanges(events: List<ChangeNotificationEvent>) {
        if (events.isEmpty() || appInForeground || !notificationsAllowed()) return
        val details =
            events.take(MAX_CHANGE_DETAILS).joinToString("\n") {
                "\${it.actionLabel}: \${it.objectRepr}"
            } +
                if (events.size > MAX_CHANGE_DETAILS) {
                    "\n…and \${events.size - MAX_CHANGE_DETAILS} more"
                } else {
                    ""
                }
        val countLabel =
            if (events.size == 1) "1 matching change" else "\${events.size} matching changes"
        val openAppIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                CHANGE_NOTIFICATION_ID,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sync_problem)
                .setContentTitle("NetBox changes detected")
                .setContentText(countLabel)
                .setStyle(NotificationCompat.BigTextStyle().bigText(details))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        NotificationManagerCompat.from(context).notify(CHANGE_NOTIFICATION_ID, notification)
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun progressNotification(progress: SyncProgress) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle("Syncing data")
            .setContentText(progress.notificationText())
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(progress.notificationText())
                    .setSummaryText(progress.notificationSubText())
            )
            .apply {
                setProgress(
                    progress.totalSteps.coerceAtLeast(1),
                    progress.step.coerceIn(0, progress.totalSteps.coerceAtLeast(1)),
                    false,
                )
                setSubText(progress.notificationSubText())
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        const val CHANNEL_ID = "background_sync_silent"
        const val EXTRA_RECONCILIATION_SUMMARY = "extra_reconciliation_summary"
        private const val LEGACY_CHANNEL_ID = "background_sync"
        private const val NOTIFICATION_ID = 1001
        private const val RECONCILIATION_NOTIFICATION_ID = 1002
        private const val CHANGE_NOTIFICATION_ID = 1003
        private const val MAX_CHANGE_DETAILS = 8
    }
}

private fun ReconciliationSummary.shortText(): String =
    buildList {
            if (created.isNotEmpty()) add("${created.size} item(s) created")
            if (edited.isNotEmpty()) add("${edited.size} item(s) updated")
            if (deleted.isNotEmpty()) add("${deleted.size} item(s) deleted")
        }
        .joinToString(", ")

private fun ReconciliationSummary.details(): String =
    buildList {
            created.forEach { add("Created: ${it.display} (#${it.id})") }
            edited.forEach { add("Updated: ${it.display} (#${it.id})") }
            deleted.forEach { add("Deleted: ${it.display} (#${it.id})") }
        }
        .joinToString("\n")
