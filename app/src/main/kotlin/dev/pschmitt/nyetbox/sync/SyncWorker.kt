package dev.pschmitt.nyetbox.sync

import android.content.Context
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.widget.WidgetUpdater

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val settingsRepository: SettingsRepository,
    private val syncNotifier: SyncNotifier,
    private val syncStatusRepository: SyncStatusRepository,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!settingsRepository.isConfigured) return Result.success()
        // Offline mode is an explicit user request to pause all network activity. WorkManager may
        // still deliver a request that was queued before the toggle, so guard here as well as in
        // the UI entry points and network interceptor.
        if (settingsRepository.offlineMode.value) return Result.success()
        // WorkManager can express low battery but not Android's explicit Battery Saver mode. Do
        // this check immediately before starting the network/foreground work so a manual or
        // already-enqueued request cannot begin while the user has asked the system to conserve
        // power. Returning retry leaves it queued for a later attempt after Battery Saver ends.
        val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isPowerSaveMode == true) return Result.retry()
        // Full syncs include every discovered model plus optional durable attachments and can
        // legitimately run for minutes. Promote the WorkManager job before touching the network
        // so Android keeps it alive and the user gets the real system progress notification.
        // A foreground service notification is mandatory for a background-started long-running
        // worker, but it is needlessly intrusive when the user is already looking at the app. In
        // that case the in-app sync state remains visible and the worker stays an ordinary
        // WorkManager job; if the app is backgrounded before the next scheduled run, the normal
        // foreground path is used again.
        if (!syncNotifier.isAppInForeground()) {
            setForeground(syncNotifier.foregroundInfo())
        } else {
            syncNotifier.notifySyncStarted()
        }
        return offlineSyncRepository
            .syncAll(
                forceFullSync = inputData.getBoolean(SyncScheduler.KEY_FORCE_FULL_SYNC, false),
                onProgress = { progress ->
                    // Published to SyncStatusRepository the same moment it goes to the system
                    // notification (NBC-370), so SyncStatusCard renders the same step/message/item
                    // progress instead of a generic spinner.
                    syncNotifier.notifySyncProgress(progress)
                    syncStatusRepository.publishProgress(progress)
                },
            )
            .fold(
                onSuccess = {
                    syncStatusRepository.publishProgress(null)
                    syncNotifier.notifySyncSucceeded(it.reconciliation)
                    widgetUpdater.updateAll()
                    Result.success()
                },
                onFailure = { error ->
                    // WorkManager's own exponential backoff handles the retry delay - this just
                    // caps how many times a single scheduled run retries before giving up and
                    // surfacing a Notification (NBC-23), rather than retrying silently forever.
                    // A PeriodicWorkRequest's attempt count resets on its next period regardless,
                    // so this only bounds retries *within* one run, not across runs.
                    syncStatusRepository.publishProgress(null)
                    widgetUpdater.updateAll()
                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        syncNotifier.notifySyncRetry(runAttemptCount + 1)
                        Result.retry()
                    } else {
                        syncNotifier.notifySyncFailed(error.message)
                        Result.failure()
                    }
                },
            )
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
