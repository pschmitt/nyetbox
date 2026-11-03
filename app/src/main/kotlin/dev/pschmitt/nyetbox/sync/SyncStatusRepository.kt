package dev.pschmitt.nyetbox.sync

import androidx.work.WorkInfo
import androidx.work.WorkManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * App-wide "is background sync currently running" signal (NBC-23) - backs a persistent indicator
 * that's correct no matter which screen (if any) is on-screen, unlike the existing per-screen
 * `PullToRefreshBox` spinners, which only reflect a manual pull while that specific screen is
 * visible.
 *
 * Reads straight off [WorkManager]'s own locally-persisted [WorkInfo] state for the two unique work
 * names [SyncScheduler] enqueues under - that state lives in WorkManager's own Room database
 * on-device, so this renders correctly offline too. Only the sync work *itself* needs connectivity
 * (see its `NetworkType.CONNECTED` constraint); observing whether it's running does not.
 */
@Singleton
class SyncStatusRepository
@Inject
constructor(
    private val workManager: WorkManager,
    syncHistoryRepository: SyncHistoryRepository,
) {

    val isSyncing: Flow<Boolean> =
        combine(
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.ONE_TIME_WORK_NAME),
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.STARTUP_WORK_NAME),
        ) { periodic, oneTime, startup ->
            (periodic + oneTime + startup).any { it.state == WorkInfo.State.RUNNING }
        }

    /** The latest manual sync state, including terminal success/failure for refresh feedback. */
    val manualSyncState: Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.ONE_TIME_WORK_NAME).map { infos ->
            infos.firstOrNull()?.state
        }

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)

    /**
     * The most recent [SyncProgress] reported by whichever sync is currently running (NBC-370) -
     * mirrors exactly what [SyncNotifier]'s system notification shows, so [SyncWorker] publishes
     * here the same moment it hands a [SyncProgress] to `SyncNotifier.notifySyncProgress`. Reset to
     * `null` once a sync attempt finishes (success, failure, or a retry backoff) so a stale step
     * doesn't linger into the next run.
     */
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    fun publishProgress(progress: SyncProgress?) {
        _syncProgress.value = progress
    }

    /** Full picture for the sync status card and the diagnostics screen (NBC-455). */
    val overview: Flow<SyncOverview> =
        combine(
            isSyncing,
            syncProgress,
            syncHistoryRepository.runs,
            workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.PERIODIC_WORK_NAME),
            combine(
                workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.ONE_TIME_WORK_NAME),
                workManager.getWorkInfosForUniqueWorkFlow(SyncScheduler.STARTUP_WORK_NAME),
                ::Pair,
            ),
        ) { syncing, progress, history, periodic, (manual, startup) ->
            buildSyncOverview(syncing, progress, history, periodic, manual, startup)
        }
}
