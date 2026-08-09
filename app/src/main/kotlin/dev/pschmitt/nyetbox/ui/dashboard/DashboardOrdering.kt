package dev.pschmitt.nyetbox.ui.dashboard

import dev.pschmitt.nyetbox.data.db.DashboardStatEntity
import dev.pschmitt.nyetbox.data.schema.NetBoxEndpointCatalog
import dev.pschmitt.nyetbox.data.schema.NetBoxEndpointMetadata

enum class DashboardSection(val key: String, val title: String) {
    Stats("stats", "Stats"),
    Search("search", "Search NetBox"),
    News("news", "NetBox news"),
    RecentlyViewed("recently_viewed", "Recently viewed"),
    Bookmarks("bookmarks", "Bookmarks"),
    RecentChanges("recent_changes", "Recent changes"),
}

fun orderedDashboardSections(
    savedOrder: List<String>,
    hidden: Set<String>,
): List<DashboardSection> {
    val customRank = savedOrder.withIndex().associate { it.value to it.index }
    return DashboardSection.entries
        .filterNot { it.key in hidden }
        .sortedWith(
            compareBy<DashboardSection> { customRank[it.key] == null }
                .thenBy { customRank[it.key] ?: Int.MAX_VALUE }
                .thenBy { it.ordinal }
        )
}

fun allDashboardSectionKeys(): List<String> = DashboardSection.entries.map { it.key }

/**
 * NBC-437: which of the dashboard's stat tiles show, and in what order - a user choice
 * (`SettingsRepository.statsOrder`/`hiddenStats`), same shape as [orderedDashboardSections]. Ties
 * (no custom rank yet) fall back to the shared core-model registry's own order rather than cache
 * insertion order, which is otherwise whatever order [DashboardRepository.refreshStats] happened to
 * fetch and upsert them in.
 */
fun orderedStats(
    stats: List<DashboardStatEntity>,
    savedOrder: List<String>,
    hidden: Set<String>,
): List<DashboardStatEntity> {
    val customRank = savedOrder.withIndex().associate { it.value to it.index }
    val naturalRank =
        NetBoxEndpointCatalog.coreModels.withIndex().associate { it.value.endpointPath to it.index }
    return stats
        .filterNot { it.endpointPath in hidden }
        .sortedWith(
            compareBy<DashboardStatEntity> { customRank[it.endpointPath] == null }
                .thenBy { customRank[it.endpointPath] ?: Int.MAX_VALUE }
                .thenBy { naturalRank[it.endpointPath] ?: Int.MAX_VALUE }
        )
}

/**
 * Every stat candidate (not just the currently-visible ones), in the same order [orderedStats]
 * would show them - for the "Customize stats" picker, which needs to list hidden candidates too so
 * they can be turned back on.
 */
fun orderedStatCandidates(savedOrder: List<String>): List<NetBoxEndpointMetadata> {
    val customRank = savedOrder.withIndex().associate { it.value to it.index }
    return NetBoxEndpointCatalog.coreModels.sortedWith(
        compareBy<NetBoxEndpointMetadata> { customRank[it.endpointPath] == null }
            .thenBy { customRank[it.endpointPath] ?: Int.MAX_VALUE }
    )
}
