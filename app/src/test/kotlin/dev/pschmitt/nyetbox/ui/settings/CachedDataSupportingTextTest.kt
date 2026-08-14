package dev.pschmitt.nyetbox.ui.settings

import dev.pschmitt.nyetbox.sync.SyncProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedDataSupportingTextTest {
    private fun text(
        isSyncing: Boolean = false,
        syncProgress: SyncProgress? = null,
        cachedDeviceCount: Int = 10,
        cachedObjectCount: Int = 20,
        cachedImageCount: Int = 5,
        persistentCacheFiles: Int = 3,
        persistentCacheBytes: Long = 1024L,
    ) =
        cachedDataSupportingText(
            isSyncing,
            syncProgress,
            cachedDeviceCount,
            cachedObjectCount,
            cachedImageCount,
            persistentCacheFiles,
            persistentCacheBytes,
        )

    @Test
    fun showsOnlyCacheTotalsWhenNotSyncing() {
        val result = text(isSyncing = false)
        assertFalse(result.contains("Downloading"))
        assertTrue(result.contains("10 devices"))
    }

    @Test
    fun showsOnlyCacheTotalsWhileSyncingABeforeAttachmentPhaseStarts() {
        val progress = SyncProgress("Syncing devices…", step = 3, totalSteps = 10)
        val result = text(isSyncing = true, syncProgress = progress)
        assertFalse(result.contains("Downloading"))
    }

    @Test
    fun showsLiveAttachmentProgressDuringTheAttachmentPhase() {
        val progress =
            SyncProgress(
                "Downloading cached images and documents…",
                step = 8,
                totalSteps = 10,
                itemLabel = "images/documents",
                itemCompleted = 4,
                itemTotal = 12,
                bytesDownloaded = 2_097_152L,
            )
        val result = text(isSyncing = true, syncProgress = progress)
        assertTrue(result.startsWith("Downloading images and documents… 4 of 12 · 2.0 MiB downloaded"))
        assertTrue(result.contains("10 devices"))
    }

    @Test
    fun fallsBackToCacheTotalsOnceTheAttachmentPhaseEnds() {
        val progress =
            SyncProgress(
                "Reconciling changes…",
                step = 10,
                totalSteps = 10,
                itemLabel = "images/documents",
                itemCompleted = 12,
                itemTotal = 12,
            )
        // isSyncing false simulates the sync having finished (or failed) right after that phase.
        val result = text(isSyncing = false, syncProgress = progress)
        assertFalse(result.contains("Downloading"))
    }
}
