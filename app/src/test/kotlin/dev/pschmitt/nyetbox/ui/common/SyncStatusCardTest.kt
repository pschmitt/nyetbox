package dev.pschmitt.nyetbox.ui.common

import dev.pschmitt.nyetbox.sync.SyncProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusCardTest {
    @Test
    fun `headline shows synced when not syncing regardless of any leftover progress`() {
        val progress = SyncProgress("Syncing devices…", step = 3, totalSteps = 10)

        assertEquals("Synced", syncStatusHeadline(isSyncing = false, syncProgress = progress))
        assertEquals("Synced", syncStatusHeadline(isSyncing = false, syncProgress = null))
    }

    @Test
    fun `headline falls back to generic text before the first progress update arrives`() {
        assertEquals("Syncing…", syncStatusHeadline(isSyncing = true, syncProgress = null))
    }

    @Test
    fun `headline shows the current step message while syncing`() {
        val progress = SyncProgress("Syncing devices…", step = 3, totalSteps = 10)

        assertEquals(
            "Syncing devices…",
            syncStatusHeadline(isSyncing = true, syncProgress = progress),
        )
    }

    @Test
    fun `subtext mirrors the notification's step and item progress while syncing`() {
        val progress =
            SyncProgress(
                message = "Downloading cached images and documents…",
                step = 8,
                totalSteps = 12,
                itemLabel = "images/documents",
                itemCompleted = 7,
                itemTotal = 19,
            )

        assertEquals(
            "Quick sync · 8/12 · 7 of 19 images/documents",
            syncStatusSubText(
                isSyncing = true,
                syncProgress = progress,
                lastSuccessfulSyncAt = null,
                nowMillis = 1_000L,
            ),
        )
    }

    @Test
    fun `subtext falls back to the last-synced timestamp before the first progress update arrives`() {
        val now = 1_000_000L

        assertEquals(
            "Last synced just now",
            syncStatusSubText(
                isSyncing = true,
                syncProgress = null,
                lastSuccessfulSyncAt = now,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun `subtext shows the last-synced timestamp once syncing has finished`() {
        val now = 1_000_000L
        val progress = SyncProgress("Syncing devices…", step = 3, totalSteps = 10)

        assertEquals(
            "Last synced just now",
            syncStatusSubText(
                isSyncing = false,
                syncProgress = progress,
                lastSuccessfulSyncAt = now,
                nowMillis = now,
            ),
        )
    }
}
