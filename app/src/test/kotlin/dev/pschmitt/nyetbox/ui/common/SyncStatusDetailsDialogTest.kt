package dev.pschmitt.nyetbox.ui.common

import dev.pschmitt.nyetbox.data.repository.LastSyncSummary
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

// NBC-435: the after-the-fact answer to "was that sync as cheap as it should have been?".
class SyncStatusDetailsDialogTest {
    @Test
    fun `formats a quick incremental pass under a minute`() {
        val summary =
            LastSyncSummary(
                isFullSync = false,
                durationMillis = TimeUnit.SECONDS.toMillis(58),
                itemsRefreshed = 3,
            )

        assertEquals(
            "Last sync: quick check · 58s · 3 items refreshed",
            formatLastSyncSummary(summary),
        )
    }

    @Test
    fun `formats a full pass over a minute`() {
        val summary =
            LastSyncSummary(
                isFullSync = true,
                durationMillis = TimeUnit.MINUTES.toMillis(4),
                itemsRefreshed = 512,
            )

        assertEquals(
            "Last sync: full sync · 4m · 512 items refreshed",
            formatLastSyncSummary(summary),
        )
    }

    @Test
    fun `uses singular item wording for exactly one refreshed item`() {
        val summary =
            LastSyncSummary(isFullSync = false, durationMillis = 1_000L, itemsRefreshed = 1)

        assertEquals(
            "Last sync: quick check · 1s · 1 item refreshed",
            formatLastSyncSummary(summary),
        )
    }
}
