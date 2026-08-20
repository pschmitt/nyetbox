package dev.pschmitt.nyetbox.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncScheduler
@Inject
constructor(
    private val workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
) {

    fun schedulePeriodic() {
        val request =
            PeriodicWorkRequestBuilder<SyncWorker>(
                    settingsRepository.syncIntervalHours.value.toLong(),
                    TimeUnit.HOURS,
                )
                .setConstraints(syncConstraints())
                .setSyncBackoffCriteria()
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
     */
    fun syncNow(forceFullSync: Boolean = false) {
        val request =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(syncConstraints())
                .setSyncBackoffCriteria()
                .setInputData(workDataOf(KEY_FORCE_FULL_SYNC to forceFullSync))
                .build()
        workManager.enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.KEEP, request)
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
                .build()
        workManager.enqueueUniqueWork(STARTUP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
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
            // Battery Saver is stricter than Android's low-battery threshold and is checked by
            // SyncWorker as well, since WorkManager has no native power-save-mode constraint.
            .setRequiresBatteryNotLow(true)
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

        private const val SYNC_RETRY_BACKOFF_MINUTES = 1L
    }
}

internal fun shouldScheduleStartup(syncOnAppLaunch: Boolean, offlineMode: Boolean): Boolean =
    syncOnAppLaunch && !offlineMode
