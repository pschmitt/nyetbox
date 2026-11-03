package dev.pschmitt.nyetbox.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncRunPolicyTest {
    @Test
    fun `automatic sync may start with normal battery`() {
        assertNull(SyncRunPolicy.deferralReason(powerSaveMode = false, batteryLow = false))
    }

    @Test
    fun `battery saver wins over low battery as the reported reason`() {
        assertEquals(
            SyncRunPolicy.BATTERY_SAVER_REASON,
            SyncRunPolicy.deferralReason(powerSaveMode = true, batteryLow = true),
        )
    }

    @Test
    fun `low battery alone defers`() {
        assertEquals(
            SyncRunPolicy.BATTERY_LOW_REASON,
            SyncRunPolicy.deferralReason(powerSaveMode = false, batteryLow = true),
        )
    }
}
