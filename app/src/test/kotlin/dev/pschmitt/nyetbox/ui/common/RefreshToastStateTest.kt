package dev.pschmitt.nyetbox.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshToastStateTest {
    @Test
    fun `failed primary fetch reports failure regardless of linked-object failures`() {
        assertEquals("Sync failed", targetedSyncToast(primarySucceeded = false, failureCount = 0))
        assertEquals("Sync failed", targetedSyncToast(primarySucceeded = false, failureCount = 3))
    }

    @Test
    fun `successful primary fetch with no linked failures reports completion`() {
        assertEquals("Sync complete", targetedSyncToast(primarySucceeded = true, failureCount = 0))
    }

    @Test
    fun `successful primary fetch with linked failures reports the issue count`() {
        assertEquals(
            "Synced with 1 issue",
            targetedSyncToast(primarySucceeded = true, failureCount = 1),
        )
        assertEquals(
            "Synced with 3 issues",
            targetedSyncToast(primarySucceeded = true, failureCount = 3),
        )
    }

    @Test
    fun offlineRefreshNeverReportsQueued() {
        assertEquals(
            false,
            shouldShowRefreshQueuedToast(showConfirmation = true, offlineMode = true),
        )
        assertEquals(
            true,
            shouldShowRefreshQueuedToast(showConfirmation = true, offlineMode = false),
        )
    }
}
