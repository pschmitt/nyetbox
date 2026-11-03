package dev.pschmitt.nyetbox.sync

import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo

/**
 * Everything the UI needs to tell the user what sync is doing (NBC-455): the run in progress, how
 * the last run ended, why sync is waiting, when the next automatic sync is due, and the raw state
 * of every scheduled sync request for the diagnostics screen.
 */
data class SyncOverview(
    val isSyncing: Boolean = false,
    val progress: SyncProgress? = null,
    /** The history entry of the run in progress, if any. */
    val currentRun: SyncRunRecord? = null,
    /** The newest run that actually ran to an end (not running, not deferred). */
    val lastRun: SyncRunRecord? = null,
    /** Set while the newest history entry is a deferral, i.e. sync is currently waiting. */
    val waitingReason: String? = null,
    val waitingSince: Long? = null,
    val nextAutomaticSyncAt: Long? = null,
    val scheduled: List<ScheduledSync> = emptyList(),
    val history: List<SyncRunRecord> = emptyList(),
)

/** One unique WorkManager sync request and its current state, for the diagnostics screen. */
data class ScheduledSync(
    val label: String,
    val state: WorkInfo.State,
    val nextRunAt: Long?,
    val attempts: Int,
    val requirements: List<String>,
    val lastStopReason: String?,
)

/** Pure assembly of [SyncOverview], kept separate from Flow plumbing so it is unit-testable. */
internal fun buildSyncOverview(
    isSyncing: Boolean,
    progress: SyncProgress?,
    history: List<SyncRunRecord>,
    periodic: List<WorkInfo>,
    manual: List<WorkInfo>,
    startup: List<WorkInfo>,
): SyncOverview {
    val currentRun =
        history.firstOrNull { it.outcome == SyncRunOutcome.Running }.takeIf { isSyncing }
    val lastRun = history.firstOrNull {
        it.outcome != SyncRunOutcome.Running && it.outcome != SyncRunOutcome.Deferred
    }
    val newest = history.firstOrNull()
    val waiting = newest?.takeIf { it.outcome == SyncRunOutcome.Deferred && !isSyncing }
    val nextAutomatic =
        periodic
            .firstOrNull { it.state == WorkInfo.State.ENQUEUED }
            ?.nextScheduleTimeMillis
            ?.takeIf { it != Long.MAX_VALUE }
    val scheduled = buildList {
        periodic.lastOrNull()?.let { add(it.toScheduledSync(SyncTrigger.Periodic.label)) }
        startup.lastOrNull()?.let { add(it.toScheduledSync(SyncTrigger.Startup.label)) }
        manual.lastOrNull()?.let { add(it.toScheduledSync("Sync now / refresh")) }
    }
    return SyncOverview(
        isSyncing = isSyncing,
        progress = progress,
        currentRun = currentRun,
        lastRun = lastRun,
        waitingReason = waiting?.message,
        waitingSince = waiting?.startedAt,
        nextAutomaticSyncAt = nextAutomatic,
        scheduled = scheduled,
        history = history,
    )
}

private fun WorkInfo.toScheduledSync(label: String): ScheduledSync =
    ScheduledSync(
        label = label,
        state = state,
        nextRunAt =
            nextScheduleTimeMillis.takeIf {
                state == WorkInfo.State.ENQUEUED && it != Long.MAX_VALUE
            },
        attempts = runAttemptCount,
        requirements = constraints.requirementLabels(),
        lastStopReason =
            stopReason
                .takeIf { it != WorkInfo.STOP_REASON_NOT_STOPPED }
                ?.let(::syncStopReasonLabel),
    )

internal fun Constraints.requirementLabels(): List<String> = buildList {
    when (requiredNetworkType) {
        NetworkType.CONNECTED -> add("Any network")
        NetworkType.UNMETERED -> add("Unmetered network (Wi-Fi)")
        NetworkType.NOT_ROAMING -> add("Network, not roaming")
        NetworkType.METERED -> add("Metered network")
        NetworkType.TEMPORARILY_UNMETERED -> add("Temporarily unmetered network")
        NetworkType.NOT_REQUIRED -> Unit
    }
    if (requiresCharging()) add("Charging")
    if (requiresBatteryNotLow()) add("Battery not low")
    if (requiresDeviceIdle()) add("Device idle")
    if (requiresStorageNotLow()) add("Storage not low")
}

/** Plain-language label for a WorkManager stop reason. */
internal fun syncStopReasonLabel(stopReason: Int): String =
    when (stopReason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "Replaced or cancelled by the app"
        WorkInfo.STOP_REASON_PREEMPT -> "Preempted by the system"
        WorkInfo.STOP_REASON_TIMEOUT -> "Ran out of time allowed by the system"
        WorkInfo.STOP_REASON_DEVICE_STATE -> "Stopped by the system (device state)"
        WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW -> "Battery became low"
        WorkInfo.STOP_REASON_CONSTRAINT_CHARGING -> "Device stopped charging"
        WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> "Network became unavailable"
        WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE -> "Device left idle mode"
        WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> "Storage became low"
        WorkInfo.STOP_REASON_QUOTA -> "Background run quota exhausted"
        WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> "Background activity restricted"
        WorkInfo.STOP_REASON_APP_STANDBY -> "App standby restrictions"
        WorkInfo.STOP_REASON_USER -> "Stopped by the user"
        WorkInfo.STOP_REASON_SYSTEM_PROCESSING -> "Stopped by the system"
        WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED -> "Rescheduled by the system"
        else -> "Stopped before it finished"
    }
