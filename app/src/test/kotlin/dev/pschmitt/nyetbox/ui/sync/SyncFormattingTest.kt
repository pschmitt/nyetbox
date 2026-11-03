package dev.pschmitt.nyetbox.ui.sync

import dev.pschmitt.nyetbox.sync.SyncOverview
import dev.pschmitt.nyetbox.sync.SyncRunOutcome
import dev.pschmitt.nyetbox.sync.SyncRunRecord
import dev.pschmitt.nyetbox.sync.SyncTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFormattingTest {
    private val minute = 60_000L
    private val now = 1_000_000_000L

    @Test
    fun `durations`() {
        assertEquals("0s", formatSyncDuration(0))
        assertEquals("42s", formatSyncDuration(42_000))
        assertEquals("3m 12s", formatSyncDuration(192_000))
        assertEquals("5m", formatSyncDuration(300_000))
        assertEquals("1h 5m", formatSyncDuration(3_900_000))
    }

    @Test
    fun `relative times in both directions`() {
        assertEquals("just now", formatSyncRelativeTime(now - 10_000, now))
        assertEquals("5m ago", formatSyncRelativeTime(now - 5 * minute, now))
        assertEquals("in 3h", formatSyncRelativeTime(now + 180 * minute, now))
        assertEquals("2d ago", formatSyncRelativeTime(now - 2 * 24 * 60 * minute, now))
    }

    @Test
    fun `summary line names pass type, duration, items and warnings`() {
        val run =
            SyncRunRecord(
                id = "a",
                trigger = SyncTrigger.Manual,
                startedAt = now,
                finishedAt = now + 42_000,
                outcome = SyncRunOutcome.SucceededWithWarnings,
                isFullSync = true,
                itemsRefreshed = 1234,
                warnings = listOf("x", "y"),
            )

        assertEquals("Full sync · 42s · 1,234 items refreshed · 2 warnings", run.summaryLine())
    }

    @Test
    fun `waiting overview explains why and still shows the last run`() {
        val lastRun =
            SyncRunRecord(
                id = "b",
                trigger = SyncTrigger.Periodic,
                startedAt = now - 65 * minute,
                finishedAt = now - 60 * minute,
                outcome = SyncRunOutcome.Failed,
                isFullSync = false,
                message = "HTTP 502",
            )
        val overview =
            SyncOverview(
                lastRun = lastRun,
                waitingReason = "Battery Saver is on",
                waitingSince = now - 10 * minute,
                nextAutomaticSyncAt = now + 120 * minute,
            )

        assertEquals("Waiting to sync", overview.headline())
        val lines = overview.detailLines(lastSuccessfulSyncAt = now - 600 * minute, now, false)
        assertEquals("Battery Saver is on (since 10m ago)", lines[0])
        assertTrue(lines[1].startsWith("Background sync 1h ago · Quick check"))
        assertEquals("HTTP 502", lines[2])
        assertEquals("Last complete sync 10h ago", lines[3])
        assertEquals("Next background sync in 2h", lines[4])
    }

    @Test
    fun `offline mode replaces every other detail`() {
        assertEquals(
            listOf("Offline mode is on, sync is paused"),
            SyncOverview(waitingReason = "x").detailLines(null, now, offlineMode = true),
        )
    }

    @Test
    fun `compact line keeps the card to one quiet line`() {
        val succeeded =
            SyncRunRecord(
                id = "c",
                trigger = SyncTrigger.Periodic,
                startedAt = now - 6 * minute,
                finishedAt = now - 5 * minute,
                outcome = SyncRunOutcome.SucceededWithWarnings,
                warnings = listOf("a", "b"),
            )
        val failed = succeeded.copy(outcome = SyncRunOutcome.Failed, message = "HTTP 502")

        assertEquals(
            "5m ago · 2 warnings",
            SyncOverview(lastRun = succeeded).compactLine(null, now, false),
        )
        assertEquals("HTTP 502", SyncOverview(lastRun = failed).compactLine(null, now, false))
        assertEquals(
            "Battery Saver is on",
            SyncOverview(lastRun = failed, waitingReason = "Battery Saver is on")
                .compactLine(null, now, false),
        )
        assertEquals("Never synced yet", SyncOverview().compactLine(null, now, false))
        assertEquals(null, SyncOverview(isSyncing = true).compactLine(null, now, false))
    }
}
