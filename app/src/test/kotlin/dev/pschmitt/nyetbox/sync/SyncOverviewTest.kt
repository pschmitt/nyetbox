package dev.pschmitt.nyetbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncOverviewTest {
    private fun run(outcome: SyncRunOutcome, startedAt: Long, message: String? = null) =
        SyncRunRecord(
            id = "$outcome-$startedAt",
            trigger = SyncTrigger.Periodic,
            startedAt = startedAt,
            finishedAt = if (outcome == SyncRunOutcome.Running) null else startedAt + 1_000,
            outcome = outcome,
            message = message,
        )

    private fun overview(isSyncing: Boolean, history: List<SyncRunRecord>) =
        buildSyncOverview(isSyncing, null, history, emptyList(), emptyList(), emptyList())

    @Test
    fun `newest deferral means sync is waiting, last run skips deferrals`() {
        val succeeded = run(SyncRunOutcome.Succeeded, 100)
        val deferred = run(SyncRunOutcome.Deferred, 200, SyncRunPolicy.BATTERY_SAVER_REASON)

        val result = overview(isSyncing = false, history = listOf(deferred, succeeded))

        assertEquals(SyncRunPolicy.BATTERY_SAVER_REASON, result.waitingReason)
        assertEquals(200L, result.waitingSince)
        assertEquals(succeeded, result.lastRun)
    }

    @Test
    fun `an older deferral followed by a run is no longer waiting`() {
        val deferred = run(SyncRunOutcome.Deferred, 100, SyncRunPolicy.BATTERY_LOW_REASON)
        val failed = run(SyncRunOutcome.Failed, 200, "HTTP 500")

        val result = overview(isSyncing = false, history = listOf(failed, deferred))

        assertNull(result.waitingReason)
        assertEquals(failed, result.lastRun)
    }

    @Test
    fun `running entry is only the current run while WorkManager reports syncing`() {
        val running = run(SyncRunOutcome.Running, 300)

        assertEquals(running, overview(isSyncing = true, history = listOf(running)).currentRun)
        assertNull(overview(isSyncing = false, history = listOf(running)).currentRun)
    }
}
