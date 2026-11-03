package dev.pschmitt.nyetbox.sync

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

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
    private val syncHistoryRepository: SyncHistoryRepository,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(context, params) {
    private val trigger: SyncTrigger
        get() =
            inputData.getString(SyncScheduler.KEY_TRIGGER)?.let { stored ->
                SyncTrigger.entries.firstOrNull { it.name == stored }
            } ?: SyncTrigger.Auto

    private val serverName: String?
        get() = settingsRepository.activeServer.value?.displayName

    override suspend fun doWork(): Result {
        if (!settingsRepository.isConfigured) return Result.success()
        // Offline mode is an explicit user request to pause all network activity. WorkManager may
        // still deliver a request that was queued before the toggle, so guard here as well as in
        // the UI entry points and network interceptor.
        if (settingsRepository.offlineMode.value) return Result.success()
        // Battery Saver and low battery are checked only here, when a run is about to start - never
        // as WorkManager constraints. A constraint is re-evaluated while the work runs, so a sync
        // that was already under way got cancelled the moment Battery Saver kicked in or the
        // battery-low broadcast arrived (NBC-454: on a phone whose Battery Saver triggers at 60%,
        // that also made every sync below 60% fail with "Job was cancelled"). Once a sync has
        // started it is allowed to finish. Returning retry leaves a deferred run queued for later.
        // The explicit "Sync now"/"Retry" buttons set KEY_IGNORE_BATTERY_SAVER so a user's
        // deliberate tap always attempts the sync right away instead of silently deferring - the
        // ordinary background/auto-sync path still defers like it always has.
        val ignoreBatterySaver = inputData.getBoolean(SyncScheduler.KEY_IGNORE_BATTERY_SAVER, false)
        if (!ignoreBatterySaver) {
            batteryDeferralReason()?.let { reason ->
                syncHistoryRepository.recordDeferred(
                    trigger,
                    reason,
                    serverName,
                    System.currentTimeMillis(),
                )
                return Result.retry()
            }
        }
        val runId =
            syncHistoryRepository.start(
                trigger,
                attempt = runAttemptCount + 1,
                serverName = serverName,
                now = System.currentTimeMillis(),
            )
        return coroutineScope {
            // Full syncs include every discovered model plus optional durable attachments and can
            // legitimately run for minutes. While the app is visible the in-app sync state is
            // enough and the worker stays an ordinary WorkManager job; as soon as the app is not
            // visible (already at start, or because the user leaves mid-sync) promote it to a
            // foreground service. That keeps Android from stopping it and keeps its network
            // access when Battery Saver blocks background apps, and gives the user the system
            // progress notification.
            val foregroundPromotion =
                if (syncNotifier.isAppInForeground()) {
                    syncNotifier.notifySyncStarted()
                    launch {
                        syncNotifier.appInForegroundFlow.first { inForeground -> !inForeground }
                        promoteToForeground()
                    }
                } else {
                    promoteToForeground()
                    null
                }
            try {
                runSync(runId)
            } catch (cancelled: CancellationException) {
                // Stopped by WorkManager/the OS (constraints, quota, a replacing request). It
                // reschedules the work itself, so this is not a failure worth reporting.
                syncStatusRepository.publishProgress(null)
                syncNotifier.notifySyncStopped()
                syncHistoryRepository.finish(
                    runId,
                    SyncRunOutcome.Stopped,
                    System.currentTimeMillis(),
                    message = syncStopReasonLabel(stopReason),
                )
                throw cancelled
            } finally {
                foregroundPromotion?.cancel()
            }
        }
    }

    private suspend fun runSync(runId: String): Result {
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
                onSuccess = { summary ->
                    syncStatusRepository.publishProgress(null)
                    syncNotifier.notifySyncSucceeded(summary.reconciliation)
                    syncHistoryRepository.finish(
                        runId,
                        outcome =
                            when {
                                summary.skipped -> SyncRunOutcome.UpToDate
                                summary.warnings.isNotEmpty() ->
                                    SyncRunOutcome.SucceededWithWarnings
                                else -> SyncRunOutcome.Succeeded
                            },
                        now = System.currentTimeMillis(),
                        isFullSync = summary.isFullSync.takeUnless { summary.skipped },
                        itemsRefreshed = summary.devices + summary.genericObjects,
                        message = if (summary.skipped) "Cache is fresh, nothing to fetch" else null,
                        warnings = summary.warnings,
                    )
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
                    val willRetry = runAttemptCount < MAX_RETRY_ATTEMPTS
                    syncHistoryRepository.finish(
                        runId,
                        outcome = if (willRetry) SyncRunOutcome.Retrying else SyncRunOutcome.Failed,
                        now = System.currentTimeMillis(),
                        isFullSync = (error as? SyncPassException)?.isFullSync,
                        message = error.message ?: error::class.simpleName ?: "Sync failed",
                        warnings = (error as? SyncPassException)?.warnings.orEmpty(),
                    )
                    widgetUpdater.updateAll()
                    if (willRetry) {
                        syncNotifier.notifySyncRetry(runAttemptCount + 1)
                        Result.retry()
                    } else {
                        syncNotifier.notifySyncFailed(error.message)
                        Result.failure()
                    }
                },
            )
    }

    private suspend fun promoteToForeground() {
        try {
            setForeground(syncNotifier.foregroundInfo())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Android 12+ can refuse a foreground-service start from the background
            // (ForegroundServiceStartNotAllowedException). The sync then simply continues as an
            // ordinary job - degraded, but better than failing the whole run over it.
            Timber.w(error, "Could not promote sync to a foreground service")
        }
    }

    /**
     * Why an automatic run should wait (Battery Saver on, or the system reports the battery as
     * low), or null when it may start. See [SyncRunPolicy].
     */
    private fun batteryDeferralReason(): String? {
        val powerManager =
            applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryLow =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                applicationContext
                    .registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false) == true
        return SyncRunPolicy.deferralReason(
            powerSaveMode = powerManager?.isPowerSaveMode == true,
            batteryLow = batteryLow,
        )
    }

    private companion object {
        const val MAX_RETRY_ATTEMPTS = 3
    }
}
