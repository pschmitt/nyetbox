package dev.pschmitt.nyetbox.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncProgressTest {
    @Test
    fun `formats attachment progress for the notification`() {
        val progress =
            SyncProgress(
                message = "Downloading cached images and documents…",
                step = 8,
                totalSteps = 12,
                itemLabel = "images/documents",
                itemCompleted = 7,
                itemTotal = 19,
            )

        assertEquals("7 of 19 images/documents", progress.itemProgressText())
        assertEquals(
            "Quick sync · 8/12 · 7 of 19 images/documents",
            progress.notificationSubText(),
        )
        assertEquals(
            "Downloading cached images and documents…\n7 of 19 images/documents",
            progress.notificationText(),
        )
    }

    @Test
    fun `formats stage progress without an item count`() {
        val progress = SyncProgress("Syncing devices…", step = 3, totalSteps = 10)

        assertEquals("Quick sync · 3/10", progress.notificationSubText())
        assertEquals("Syncing devices…", progress.notificationText())
    }

    @Test
    fun `prefixes full sync passes differently from quick incremental ones`() {
        val quick = SyncProgress("Syncing devices…", step = 3, totalSteps = 10, isFullSync = false)
        val full = SyncProgress("Syncing devices…", step = 3, totalSteps = 10, isFullSync = true)

        assertEquals("Quick sync · 3/10", quick.notificationSubText())
        assertEquals("Full sync · 3/10", full.notificationSubText())
    }
}
