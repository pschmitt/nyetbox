package dev.pschmitt.nyetbox.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class SyncScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun schedulePeriodic() {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(
                    settingsRepository.syncIntervalHours.value.toLong(),
                    TimeUnit.HOURS,
                )
                .setConstraints(syncConstraints())
                .setSyncBackoffCriteria()
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.Periodic.name))
                .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * @param forceFullSync Skips incremental (`last_updated`-filtered) fetching for this run and
     *   fully re-fetches every endpoint instead. The explicit Settings "Sync now" button and the
     *   dashboard pull-to-refresh gesture pass `true`; the many other callers of this function
     *   (post-CRUD refreshes, gesture shortcuts, and other incidental refreshes) stay incremental.
     * @param force Replaces any already-queued manual sync instead of keeping it, and tells
     *   [SyncWorker] to attempt the sync immediately even while Android's Battery Saver is on
     *   (which would otherwise defer it). A prior request can be sitting unstarted for a long
     *   time - blocked on constraints, backing off after a failure, deferred by the OS's
     *   app-standby job throttling, or waiting out Battery Saver - and `KEEP`/the Battery Saver
     *   check would then make a fresh tap on "Sync now" a silent no-op. The explicit "Sync
     *   now"/"Retry" buttons pass `true` so the user's tap always wins; incidental callers stay
     *   `false` so a routine post-CRUD refresh doesn't cancel and restart a user-initiated sync
     *   already in flight, and so the ordinary background/auto-sync path keeps deferring to Battery
     *   Saver like it should. A forced request still never replaces a manual sync that is already
     *   *running*: REPLACE would cancel it mid-pass (NBC-454) only to start the same work over.
     */
    fun syncNow(forceFullSync: Boolean = false, force: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(syncConstraints())
                .setSyncBackoffCriteria()
                .setInputData(
                    workDataOf(
                        KEY_FORCE_FULL_SYNC to forceFullSync,
                        KEY_IGNORE_BATTERY_SAVER to force,
                        KEY_TRIGGER to (if (force) SyncTrigger.Manual else SyncTrigger.Auto).name,
                    )
                )
                .build()
        if (!force) {
            workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return
        }
        scope.launch {
            val alreadyRunning =
                workManager.getWorkInfosForUniqueWorkFlow(ONE_TIME_WORK_NAME).first().any {
                    it.state == WorkInfo.State.RUNNING
                }
            val policy = if (alreadyRunning) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, policy, request)
        }
    }

    /** Queues the first refresh without making app startup wait for network or disk work. */
    fun scheduleStartup() {
        if (
            !shouldScheduleStartup(
                syncOnAppLaunch = settingsRepository.syncOnAppLaunch.value,
                offlineMode = settingsRepository.offlineMode.value,
            )
        ) {
            return
        }
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(syncConstraints())
                .setSyncBackoffCriteria()
                // A short grace period (NBC-370) so a quick reopen of the app shortly after the
                // last sync doesn't visibly retrigger a syncing state on every launch - only a
                // genuinely stale cache survives long enough to still see this fire.
                .setInitialDelay(STARTUP_SYNC_DELAY_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_TRIGGER to SyncTrigger.Startup.name))
                .build()
        // REPLACE, not KEEP: this only runs from Application.onCreate, so the previous startup
        // request can't belong to a sync running in this process - but it can be a stale one from
        // an older install still waiting on constraints this build no longer sets (NBC-454:
        // batteryNotLow), which KEEP would preserve indefinitely.
        workManager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelStartup() {
        workManager.cancelUniqueWork(STARTUP_WORK_NAME)
    }

    /** Stops work that could otherwise continue writing into the profile being left. */
    fun cancelForServerSwitch() {
        workManager.cancelUniqueWork(ONE_TIME_WORK_NAME)
        workManager.cancelUniqueWork(STARTUP_WORK_NAME)
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    // WorkManager's own default backoff for an unconfigured retry is BackoffPolicy.EXPONENTIAL
    // starting at WorkRequest.MIN_BACKOFF_MILLIS (10 seconds) - fine for something like a quick
    // network fetch, but far too aggressive for SyncWorker's up-to-3 retries on a real sync
    // failure (every model, plus attachments): 10s/20s/40s of near-immediate hammering instead of
    // backing off. Every sync WorkRequest sets this explicitly instead of inheriting that default.
    private fun <B : WorkRequest.Builder<B, *>> B.setSyncBackoffCriteria(): B =
        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_RETRY_BACKOFF_MINUTES, TimeUnit.MINUTES)

    private fun syncConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                SyncNetworkPolicy.requiredNetworkType(
                    syncOnlyOnWifi = settingsRepository.syncOnlyOnWifi.value,
                    syncWhileRoaming = settingsRepository.syncWhileRoaming.value,
                )
            )
            // No setRequiresBatteryNotLow(): WorkManager re-evaluates constraints while work runs
            // and cancels it when one stops holding, so a sync in progress was killed the moment
            // the battery-low broadcast arrived (NBC-454). SyncWorker checks Battery Saver and
            // low battery once, when a run starts, instead.
            .setRequiresCharging(settingsRepository.syncOnlyWhenCharging.value)
            .build()

    companion object {
        // Not private: SyncStatusRepository observes WorkManager by these same unique work names
        // to derive the app-wide "is background sync running" signal (NBC-23), so it needs to
        // agree with whatever SyncScheduler actually enqueues under.
        const val PERIODIC_WORK_NAME = "netbox-periodic-sync"
        const val ONE_TIME_WORK_NAME = "netbox-manual-sync"
        const val STARTUP_WORK_NAME = "netbox-startup-sync"

        const val STARTUP_SYNC_DELAY_SECONDS = 10L

        // Not private: SyncWorker reads this same key back out of its inputData.
        const val KEY_FORCE_FULL_SYNC = "force_full_sync"

        // Not private: SyncWorker reads this same key back out of its inputData.
        const val KEY_IGNORE_BATTERY_SAVER = "ignore_battery_saver"

        // Not private: SyncWorker reads this back to label its sync history entry (NBC-455).
        const val KEY_TRIGGER = "trigger"

        private const val SYNC_RETRY_BACKOFF_MINUTES = 1L
    }
}

internal fun shouldScheduleStartup(syncOnAppLaunch: Boolean, offlineMode: Boolean): Boolean =
    syncOnAppLaunch && !offlineMode
