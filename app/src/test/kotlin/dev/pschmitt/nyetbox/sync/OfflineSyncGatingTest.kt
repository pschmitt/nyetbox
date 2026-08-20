package dev.pschmitt.nyetbox.sync

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSyncGatingTest {

    private val fiveMinutes = TimeUnit.MINUTES.toMillis(5)

    // NBC-427
    @Test
    fun `skips a fresh pass with no queued mutations`() {
        assertTrue(
            shouldSkipSyncPass(
                forceFullSync = false,
                now = 1_000_000L,
                lastSuccessfulSyncAt = 1_000_000L - (fiveMinutes - 1),
                hasQueuedMutations = false,
            )
        )
    }

    // NBC-451: the explicit dashboard pull uses the same full-sync request as Settings' Sync now.
    @Test
    fun `never skips an explicitly requested full sync regardless of freshness`() {
        assertFalse(
            shouldSkipSyncPass(
                forceFullSync = true,
                now = 1_000_000L,
                lastSuccessfulSyncAt = 1_000_000L,
                hasQueuedMutations = false,
            )
        )
    }

    @Test
    fun `never skips while a local edit is still queued`() {
        assertFalse(
            shouldSkipSyncPass(
                forceFullSync = false,
                now = 1_000_000L,
                lastSuccessfulSyncAt = 1_000_000L,
                hasQueuedMutations = true,
            )
        )
    }

    @Test
    fun `does not skip once the freshness window has elapsed`() {
        assertFalse(
            shouldSkipSyncPass(
                forceFullSync = false,
                now = 1_000_000L,
                lastSuccessfulSyncAt = 1_000_000L - fiveMinutes,
                hasQueuedMutations = false,
            )
        )
    }

    @Test
    fun `does not skip when there is no prior successful sync`() {
        assertFalse(
            shouldSkipSyncPass(
                forceFullSync = false,
                now = 1_000_000L,
                lastSuccessfulSyncAt = null,
                hasQueuedMutations = false,
            )
        )
    }

    // NBC-429
    @Test
    fun `excludes the object-changes endpoint from generic model sync`() {
        assertTrue(isSyncExcluded("api/core/object-changes/"))
        assertFalse(isSyncExcluded("api/dcim/devices/"))
    }

    // NBC-430
    @Test
    fun `rediscovers the directory on a full pass or an empty cache`() {
        assertTrue(shouldRediscoverDirectory(isFullSyncPass = true, cachedModelCount = 50))
        assertTrue(shouldRediscoverDirectory(isFullSyncPass = false, cachedModelCount = 0))
    }

    @Test
    fun `reuses the cached directory on an incremental pass with a populated cache`() {
        assertFalse(shouldRediscoverDirectory(isFullSyncPass = false, cachedModelCount = 50))
    }

    // NBC-431
    @Test
    fun `refreshes topology only on a full pass or a device or cable delta`() {
        assertTrue(
            shouldRefreshTopology(
                isFullSyncPass = true,
                changedDeviceCount = 0,
                changedCableCount = 0,
            )
        )
        assertTrue(
            shouldRefreshTopology(
                isFullSyncPass = false,
                changedDeviceCount = 1,
                changedCableCount = 0,
            )
        )
        assertTrue(
            shouldRefreshTopology(
                isFullSyncPass = false,
                changedDeviceCount = 0,
                changedCableCount = 1,
            )
        )
        assertFalse(
            shouldRefreshTopology(
                isFullSyncPass = false,
                changedDeviceCount = 0,
                changedCableCount = 0,
            )
        )
    }

    // NBC-432/433 (rack)
    @Test
    fun `refreshes rack data on a full pass, a changed rack row, or a changed device in the rack`() {
        assertTrue(
            shouldRefreshRackData(
                isFullSyncPass = true,
                rackSyncedAt = 0L,
                passStartedAt = 1_000L,
                changedDeviceCountInRack = 0,
            )
        )
        assertTrue(
            shouldRefreshRackData(
                isFullSyncPass = false,
                rackSyncedAt = 1_000L,
                passStartedAt = 1_000L,
                changedDeviceCountInRack = 0,
            )
        )
        assertTrue(
            shouldRefreshRackData(
                isFullSyncPass = false,
                rackSyncedAt = 0L,
                passStartedAt = 1_000L,
                changedDeviceCountInRack = 1,
            )
        )
    }

    @Test
    fun `skips rack data when nothing about the rack or its devices changed this pass`() {
        assertFalse(
            shouldRefreshRackData(
                isFullSyncPass = false,
                rackSyncedAt = 0L,
                passStartedAt = 1_000L,
                changedDeviceCountInRack = 0,
            )
        )
    }

    // NBC-433 (cable)
    @Test
    fun `refreshes a cable trace svg on a full pass or a changed cable row`() {
        assertTrue(
            shouldRefreshCableTraceSvg(
                isFullSyncPass = true,
                cableSyncedAt = 0L,
                passStartedAt = 1_000L,
            )
        )
        assertTrue(
            shouldRefreshCableTraceSvg(
                isFullSyncPass = false,
                cableSyncedAt = 1_000L,
                passStartedAt = 1_000L,
            )
        )
    }

    @Test
    fun `skips an unchanged cable's trace svg on an incremental pass`() {
        assertFalse(
            shouldRefreshCableTraceSvg(
                isFullSyncPass = false,
                cableSyncedAt = 0L,
                passStartedAt = 1_000L,
            )
        )
    }
}
