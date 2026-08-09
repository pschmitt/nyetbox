package dev.pschmitt.nyetbox.ui.dashboard

import dev.pschmitt.nyetbox.data.db.DashboardStatEntity
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardOrderingTest {
    @Test
    fun `news is hidden by default while the remaining sections retain their order`() {
        assertEquals(
            listOf("stats", "search", "recently_viewed", "bookmarks", "recent_changes"),
            orderedDashboardSections(emptyList(), setOf("news")).map { it.key },
        )
    }

    @Test
    fun `saved section order wins and hidden sections stay omitted`() {
        assertEquals(
            listOf(DashboardSection.RecentChanges, DashboardSection.Stats),
            orderedDashboardSections(
                savedOrder = listOf("recent_changes", "stats", "news"),
                hidden = setOf("search", "bookmarks", "news", "recently_viewed"),
            ),
        )
    }

    // NBC-437: which stat tiles show, and in what order, is a user choice.
    @Test
    fun `stats with no saved order fall back to the shared core-model registry's order`() {
        val stats =
            listOf(
                stat(NetBoxRef.RACKS_ENDPOINT_PATH),
                stat(NetBoxRef.DEVICES_ENDPOINT_PATH),
                stat(NetBoxRef.SITES_ENDPOINT_PATH),
            )

        assertEquals(
            listOf(
                NetBoxRef.DEVICES_ENDPOINT_PATH,
                NetBoxRef.SITES_ENDPOINT_PATH,
                NetBoxRef.RACKS_ENDPOINT_PATH,
            ),
            orderedStats(stats, savedOrder = emptyList(), hidden = emptySet())
                .map { it.endpointPath },
        )
    }

    @Test
    fun `saved stats order wins and hidden stats stay omitted`() {
        val stats =
            listOf(
                stat(NetBoxRef.DEVICES_ENDPOINT_PATH),
                stat(NetBoxRef.RACKS_ENDPOINT_PATH),
                stat(NetBoxRef.SITES_ENDPOINT_PATH),
            )

        assertEquals(
            listOf(NetBoxRef.RACKS_ENDPOINT_PATH, NetBoxRef.DEVICES_ENDPOINT_PATH),
            orderedStats(
                    stats,
                    savedOrder = listOf(NetBoxRef.RACKS_ENDPOINT_PATH, NetBoxRef.DEVICES_ENDPOINT_PATH),
                    hidden = setOf(NetBoxRef.SITES_ENDPOINT_PATH),
                )
                .map { it.endpointPath },
        )
    }

    @Test
    fun `stat candidates include every core model, even hidden ones, for the customize picker`() {
        val candidates = orderedStatCandidates(savedOrder = emptyList())

        assertEquals(candidates.size, candidates.distinctBy { it.endpointPath }.size)
        assert(candidates.any { it.endpointPath == "api/tenancy/tenants/" })
    }

    @Test
    fun `saved candidate order is respected`() {
        assertEquals(
            listOf(NetBoxRef.RACKS_ENDPOINT_PATH, NetBoxRef.DEVICES_ENDPOINT_PATH),
            orderedStatCandidates(
                    savedOrder = listOf(NetBoxRef.RACKS_ENDPOINT_PATH, NetBoxRef.DEVICES_ENDPOINT_PATH)
                )
                .take(2)
                .map { it.endpointPath },
        )
    }

    private fun stat(endpointPath: String) =
        DashboardStatEntity(endpointPath, label = endpointPath, count = 0, syncedAt = 0L)
}
