package dev.pschmitt.nyetbox.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.DashboardStatEntity
import dev.pschmitt.nyetbox.data.db.NewsItemEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.db.RecentVisitEntity
import dev.pschmitt.nyetbox.data.repository.SyncIssue
import dev.pschmitt.nyetbox.sync.SyncProgress
import dev.pschmitt.nyetbox.sync.notificationSubText
import dev.pschmitt.nyetbox.ui.common.NetBoxBottomBar
import dev.pschmitt.nyetbox.ui.common.NetBoxResponsiveScaffold
import dev.pschmitt.nyetbox.ui.common.NyetboxActionCard
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.NyetboxListItem
import dev.pschmitt.nyetbox.ui.common.ObjectTypeBadge
import dev.pschmitt.nyetbox.ui.common.RemoteThumbnail
import dev.pschmitt.nyetbox.ui.common.SectionReorderState
import dev.pschmitt.nyetbox.ui.common.SuppressiblePullToRefreshBox
import dev.pschmitt.nyetbox.ui.common.SyncIssueCard
import dev.pschmitt.nyetbox.ui.common.SyncIssueDetailsDialog
import dev.pschmitt.nyetbox.ui.common.SyncStatusCard
import dev.pschmitt.nyetbox.ui.common.SyncStatusDetailsDialog
import dev.pschmitt.nyetbox.ui.common.buildSyncIssueReport
import dev.pschmitt.nyetbox.ui.common.detailAccentFor
import dev.pschmitt.nyetbox.ui.common.formatNetBoxDateTime
import dev.pschmitt.nyetbox.ui.common.objectTypeLabel
import dev.pschmitt.nyetbox.ui.common.rememberReorderWiggle
import dev.pschmitt.nyetbox.ui.common.rememberSectionReorderState
import dev.pschmitt.nyetbox.ui.common.sectionDragOffset
import dev.pschmitt.nyetbox.ui.common.sectionReorderGesture
import dev.pschmitt.nyetbox.ui.directory.AppIcons
import dev.pschmitt.nyetbox.ui.navigation.Route
import kotlinx.coroutines.delay

internal fun shouldShowSyncIssue(offlineMode: Boolean): Boolean = !offlineMode

/**
 * The status card and the issue card share one slot - never show a bland "Synced" line right above
 * (or below) the error explaining why it actually isn't.
 */
internal fun shouldShowSyncStatus(offlineMode: Boolean, hasSyncIssue: Boolean): Boolean =
    !offlineMode && !hasSyncIssue

private const val RECENT_VISITS_PREVIEW_LIMIT = 3
private const val RECENT_CHANGES_PREVIEW_LIMIT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenDrawer: () -> Unit,
    onNavigate: (Route) -> Unit,
    onNavigateToReference: (endpointPath: String, id: Int) -> Unit,
    onStatClick: (endpointPath: String, label: String) -> Unit,
    onChangeDiffClick: (changeId: Int) -> Unit,
    onConflictsClick: () -> Unit,
    onPendingChangesClick: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val changelog by viewModel.changelog.collectAsStateWithLifecycle()
    val recentVisits by viewModel.recentVisits.collectAsStateWithLifecycle()
    val news by viewModel.news.collectAsStateWithLifecycle()
    val devicesById by viewModel.devicesById.collectAsStateWithLifecycle()
    val deviceTypeFrontImagesById by
        viewModel.deviceTypeFrontImagesById.collectAsStateWithLifecycle()
    val modelsByEndpointPath by viewModel.modelsByEndpointPath.collectAsStateWithLifecycle()
    val conflictCount by viewModel.conflictCount.collectAsStateWithLifecycle()
    val pendingChangeCount by viewModel.pendingChangeCount.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val lastSuccessfulSyncAt by viewModel.lastSuccessfulSyncAt.collectAsStateWithLifecycle()
    val lastSyncSummary by viewModel.lastSyncSummary.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val showInitialSyncOverlay by viewModel.showInitialSyncOverlay.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val syncIssue by viewModel.syncIssue.collectAsStateWithLifecycle()
    val activeServer by viewModel.activeServer.collectAsStateWithLifecycle()
    val dashboardSavedOrder by viewModel.dashboardSectionOrder.collectAsStateWithLifecycle()
    val hiddenDashboardSections by viewModel.hiddenDashboardSections.collectAsStateWithLifecycle()
    val statsSavedOrder by viewModel.statsOrder.collectAsStateWithLifecycle()
    val hiddenStats by viewModel.hiddenStats.collectAsStateWithLifecycle()
    val objectTypeAccents by viewModel.objectTypeAccents.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val dashboardSections =
        orderedDashboardSections(
            savedOrder = dashboardSavedOrder,
            hidden = hiddenDashboardSections,
        )
    val dashboardOrder = dashboardSections.map { it.key }
    val orderedStatsList = orderedStats(stats, statsSavedOrder, hiddenStats)
    val dashboardListState = rememberLazyListState()
    val dashboardReorderState = rememberSectionReorderState()
    var dashboardReorderMode by remember { mutableStateOf(false) }
    var showDashboardVisibilityDialog by remember { mutableStateOf(false) }
    var showStatsCustomizeDialog by remember { mutableStateOf(false) }
    var recentVisitsExpanded by remember { mutableStateOf(false) }
    var recentChangesExpanded by remember { mutableStateOf(false) }
    var syncIssueDetails by remember { mutableStateOf<SyncIssue?>(null) }
    var showSyncStatusDetails by remember { mutableStateOf(false) }
    val cacheSummary by viewModel.cacheSummary.collectAsStateWithLifecycle()
    var copiedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    LaunchedEffect(copiedMessage) {
        copiedMessage?.let {
            snackbarHostState.showSnackbar(it)
            copiedMessage = null
        }
    }

    // Last-resort escape hatch: if the overlay's own conditions (see
    // DashboardViewModel.showInitialSyncOverlay) somehow never resolve - e.g. a sync stays
    // RUNNING without ever reaching a terminal state - don't trap the user behind it forever.
    // 20s was too eager for a real first sync (richer NetBox instances, slower connections): the
    // Play Store screenshot captures were catching this firing before the sync actually finished,
    // revealing a still-loading dashboard - a real user would hit the same thing. This is a safety
    // net for the genuinely-stuck case, not the expected happy path, so it can afford to be
    // patient.
    var initialSyncTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(showInitialSyncOverlay) {
        if (showInitialSyncOverlay) {
            initialSyncTimedOut = false
            delay(45_000)
            initialSyncTimedOut = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        NetBoxResponsiveScaffold(
            // Blur the real dashboard behind the initial-sync overlay instead of leaving it
            // sharp underneath a dark scrim - each section's own "nothing cached yet" empty
            // state was clearly visible through the scrim, mid-load, reading as broken rather
            // than loading. A blurred backdrop reads as "this is loading" regardless of what
            // state any individual section happens to be in at that moment.
            modifier =
                if (showInitialSyncOverlay && !initialSyncTimedOut) Modifier.blur(16.dp)
                else Modifier,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Dashboard, contentDescription = null)
                            Text("Dashboard", modifier = Modifier.padding(start = 8.dp))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                        }
                    },
                    actions = {
                        if (dashboardReorderMode) {
                            IconButton(onClick = { showDashboardVisibilityDialog = true }) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = "Show or hide dashboard sections",
                                )
                            }
                            IconButton(onClick = { dashboardReorderMode = false }) {
                                Icon(
                                    Icons.Default.Done,
                                    contentDescription = "Finish organizing dashboard",
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = { NetBoxBottomBar(onNavigate = onNavigate) },
        ) { padding ->
            SuppressiblePullToRefreshBox(
                // Sync has a global progress bar and Android notification; avoid the large circular
                // indicator moving over the dashboard while that background work is running.
                isRefreshing = false,
                onRefresh = viewModel::refresh,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                val bookmarkTargets =
                    bookmarks
                        .mapNotNull { bookmark ->
                            val path = bookmark.targetEndpointPath
                            val id = bookmark.targetId
                            if (path != null && id != null) bookmark.id to (path to id) else null
                        }
                        .toMap()
                val changeTargets =
                    changelog
                        .mapNotNull { change ->
                            val path = change.targetEndpointPath
                            val id = change.targetId
                            if (path != null && id != null) change.id to (path to id) else null
                        }
                        .toMap()

                LazyColumn(
                    state = dashboardListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    if (shouldShowSyncIssue(offlineMode)) {
                        syncIssue?.let { issue ->
                            item {
                                SyncIssueCard(
                                    issue,
                                    onRetry = viewModel::retrySync,
                                    isSyncing = isRefreshing,
                                    onShowDetails = { syncIssueDetails = issue },
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                    if (shouldShowSyncStatus(offlineMode, hasSyncIssue = syncIssue != null)) {
                        item {
                            SyncStatusCard(
                                lastSuccessfulSyncAt = lastSuccessfulSyncAt,
                                isSyncing = isRefreshing,
                                syncProgress = syncProgress,
                                onShowDetails = {
                                    showSyncStatusDetails = true
                                    viewModel.loadCacheSummary()
                                },
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    if (offlineMode) {
                        item {
                            NyetboxActionCard(
                                onClick = onPendingChangesClick,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.CloudOff, contentDescription = null)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            "Offline mode",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "Showing cached data; network sync is paused",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            lastSuccessfulSyncAt?.let { timestamp ->
                                                "Last sync: ${formatNetBoxDateTime(java.time.Instant.ofEpochMilli(timestamp).toString())}"
                                            } ?: "Last sync: not completed yet",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            if (pendingChangeCount == 0) {
                                                "No pending local changes"
                                            } else {
                                                "$pendingChangeCount pending change${if (pendingChangeCount == 1) "" else "s"} · Tap to review"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                    if (conflictCount > 0) {
                        item {
                            NyetboxActionCard(
                                onClick = onConflictsClick,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "$conflictCount edit conflict${if (conflictCount == 1) "" else "s"}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            "Review local and server values",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                    items(dashboardSections, key = { "dashboard-section-${it.key}" }) { section ->
                        DashboardSectionContainer(
                            section = section,
                            reorderMode = dashboardReorderMode,
                            order = dashboardOrder,
                            listState = dashboardListState,
                            reorderState = dashboardReorderState,
                            onEnterReorder = { dashboardReorderMode = true },
                            onHide = {
                                viewModel.setDashboardSectionHidden(section.key, true)
                                dashboardReorderMode = true
                            },
                            onOrderChanged = viewModel::setDashboardSectionOrder,
                            onCustomize =
                                if (section == DashboardSection.Stats) {
                                    { showStatsCustomizeDialog = true }
                                } else null,
                        ) {
                            when (section) {
                                DashboardSection.Stats -> {
                                    if (orderedStatsList.isEmpty()) {
                                        EmptyHint(
                                            isRefreshing,
                                            "No stats cached yet - pull to sync",
                                        )
                                    } else {
                                        StatsRow(orderedStatsList, objectTypeAccents, onStatClick)
                                    }
                                }
                                DashboardSection.Search ->
                                    GlobalSearchCard(
                                        onClick = { onNavigate(Route.GlobalSearch) },
                                        reorderMode = dashboardReorderMode,
                                        onLongPress = { dashboardReorderMode = true },
                                        onHide = {
                                            viewModel.setDashboardSectionHidden(section.key, true)
                                            dashboardReorderMode = true
                                        },
                                    )
                                DashboardSection.News -> {
                                    if (news.isEmpty()) {
                                        EmptyHint(isRefreshing, "No news cached yet - pull to sync")
                                    } else {
                                        news.forEach { newsItem ->
                                            NewsRow(newsItem) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Intent.ACTION_VIEW,
                                                            newsItem.link.toUri(),
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                DashboardSection.RecentlyViewed -> {
                                    if (recentVisits.isEmpty()) {
                                        // Recent visits are local history, not sync data. A running
                                        // sync must not turn this honest empty state into a
                                        // permanent
                                        // "Loading…" message on a fresh install.
                                        EmptyHint(false, "No recently viewed items yet")
                                    } else {
                                        recentVisits
                                            .let { visits ->
                                                if (recentVisitsExpanded) visits
                                                else visits.take(RECENT_VISITS_PREVIEW_LIMIT)
                                            }
                                            .forEach { visit ->
                                                val thumbnail =
                                                    viewModel.thumbnailFor(
                                                        visit.endpointPath,
                                                        visit.id,
                                                        devicesById,
                                                        deviceTypeFrontImagesById,
                                                    )
                                                RecentVisitRow(
                                                    visit = visit,
                                                    thumbnail = thumbnail,
                                                    modelLabel =
                                                        modelsByEndpointPath[visit.endpointPath]
                                                            ?.modelLabel,
                                                    typeColor =
                                                        MaterialTheme.colorScheme.detailAccentFor(
                                                            visit.endpointPath,
                                                            objectTypeAccents[
                                                                visit.endpointPath.trim('/')],
                                                        ),
                                                    onClick = {
                                                        onNavigateToReference(
                                                            visit.endpointPath,
                                                            visit.id,
                                                        )
                                                    },
                                                )
                                            }
                                        if (recentVisits.size > RECENT_VISITS_PREVIEW_LIMIT) {
                                            ExpandSectionButton(
                                                expanded = recentVisitsExpanded,
                                                collapsedLabel =
                                                    "Show all ${recentVisits.size} recently viewed",
                                                onClick = {
                                                    recentVisitsExpanded = !recentVisitsExpanded
                                                },
                                            )
                                        }
                                    }
                                }
                                DashboardSection.Bookmarks -> {
                                    if (bookmarks.isEmpty()) {
                                        EmptyHint(isRefreshing, "No bookmarks yet")
                                    } else {
                                        bookmarks.forEach { bookmark ->
                                            val thumbnail =
                                                bookmark.targetEndpointPath?.let { path ->
                                                    bookmark.targetId?.let { id ->
                                                        viewModel.thumbnailFor(
                                                            path,
                                                            id,
                                                            devicesById,
                                                            deviceTypeFrontImagesById,
                                                        )
                                                    }
                                                }
                                            BookmarkRow(
                                                bookmark = bookmark,
                                                thumbnail = thumbnail,
                                                modelLabel =
                                                    bookmark.targetEndpointPath?.let {
                                                        modelsByEndpointPath[it]?.modelLabel
                                                    },
                                                typeColor =
                                                    bookmark.targetEndpointPath?.let { path ->
                                                        MaterialTheme.colorScheme.detailAccentFor(
                                                            path,
                                                            objectTypeAccents[path.trim('/')],
                                                        )
                                                    } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                            ) {
                                                bookmarkTargets[bookmark.id]?.let { (path, id) ->
                                                    onNavigateToReference(path, id)
                                                }
                                            }
                                        }
                                    }
                                }
                                DashboardSection.RecentChanges -> {
                                    if (changelog.isEmpty()) {
                                        EmptyHint(
                                            isRefreshing,
                                            "No changes cached yet - pull to sync",
                                        )
                                    } else {
                                        changelog
                                            .let { changes ->
                                                if (recentChangesExpanded) changes
                                                else changes.take(RECENT_CHANGES_PREVIEW_LIMIT)
                                            }
                                            .forEach { change ->
                                                val thumbnail =
                                                    change.targetEndpointPath?.let { path ->
                                                        change.targetId?.let { id ->
                                                            viewModel.thumbnailFor(
                                                                path,
                                                                id,
                                                                devicesById,
                                                                deviceTypeFrontImagesById,
                                                            )
                                                        }
                                                    }
                                                ChangeRow(
                                                    change = change,
                                                    thumbnail = thumbnail,
                                                    modelLabel =
                                                        change.targetEndpointPath?.let {
                                                            modelsByEndpointPath[it]?.modelLabel
                                                        },
                                                    typeColor =
                                                        change.targetEndpointPath?.let { path ->
                                                            MaterialTheme.colorScheme
                                                                .detailAccentFor(
                                                                    path,
                                                                    objectTypeAccents[
                                                                        path.trim('/')],
                                                                )
                                                        }
                                                            ?: MaterialTheme.colorScheme
                                                                .onSurfaceVariant,
                                                    onClick = {
                                                        changeTargets[change.id]?.let { (path, id)
                                                            ->
                                                            onNavigateToReference(path, id)
                                                        }
                                                    },
                                                    onDiffClick = { onChangeDiffClick(change.id) },
                                                )
                                            }
                                        if (changelog.size > RECENT_CHANGES_PREVIEW_LIMIT) {
                                            ExpandSectionButton(
                                                expanded = recentChangesExpanded,
                                                collapsedLabel =
                                                    "Show all ${changelog.size} changes",
                                                onClick = {
                                                    recentChangesExpanded = !recentChangesExpanded
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showInitialSyncOverlay && !initialSyncTimedOut) {
            InitialSyncOverlay(syncProgress)
        }
    }

    if (showDashboardVisibilityDialog) {
        DashboardVisibilityDialog(
            hidden = hiddenDashboardSections,
            onToggle = { key, hidden -> viewModel.setDashboardSectionHidden(key, hidden) },
            onDismiss = { showDashboardVisibilityDialog = false },
        )
    }

    if (showStatsCustomizeDialog) {
        StatsCustomizeDialog(
            order = statsSavedOrder,
            hidden = hiddenStats,
            onOrderChanged = viewModel::setStatsOrder,
            onToggleHidden = { key, hidden -> viewModel.setStatHidden(key, hidden) },
            onDismiss = { showStatsCustomizeDialog = false },
        )
    }

    syncIssueDetails?.let { issue ->
        SyncIssueDetailsDialog(
            issue = issue,
            onDismiss = { syncIssueDetails = null },
            onCopyLogs = {
                val report = buildSyncIssueReport(issue, activeServer, offlineMode)
                context
                    .getSystemService<ClipboardManager>()
                    ?.setPrimaryClip(ClipData.newPlainText("Sync issue", report))
                syncIssueDetails = null
                copiedMessage = "Sync issue details copied"
            },
        )
    }

    if (showSyncStatusDetails) {
        SyncStatusDetailsDialog(
            isSyncing = isRefreshing,
            syncProgress = syncProgress,
            lastSuccessfulSyncAt = lastSuccessfulSyncAt,
            lastSyncSummary = lastSyncSummary,
            cacheSummary = cacheSummary,
            onSyncNow = viewModel::refresh,
            onDismiss = { showSyncStatusDetails = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardSectionContainer(
    section: DashboardSection,
    reorderMode: Boolean,
    order: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    reorderState: SectionReorderState,
    onEnterReorder: () -> Unit,
    onHide: () -> Unit,
    onOrderChanged: (List<String>) -> Unit,
    onCustomize: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .sectionDragOffset("dashboard-section-${section.key}", reorderState)
                .sectionReorderGesture(
                    key = "dashboard-section-${section.key}",
                    order = order.map { "dashboard-section-$it" },
                    listState = listState,
                    state = reorderState,
                    onDragStart = onEnterReorder,
                    onOrderChanged = { changed ->
                        onOrderChanged(changed.map { it.removePrefix("dashboard-section-") })
                    },
                )
    ) {
        if (section == DashboardSection.Search) {
            content()
        } else {
            // Keep each dashboard section together like the grouped Settings cards: the
            // heading is part of the surface and the individual rows remain independently
            // actionable cards inside it.
            NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                DashboardSectionHeader(
                    section = section,
                    reorderMode = reorderMode,
                    onLongPress = onEnterReorder,
                    onHide = onHide,
                    onCustomize = onCustomize,
                )
                Column(Modifier.padding(horizontal = 8.dp)) { content() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardSectionHeader(
    section: DashboardSection,
    reorderMode: Boolean,
    onLongPress: () -> Unit,
    onHide: () -> Unit,
    onCustomize: (() -> Unit)? = null,
) {
    val wiggle = rememberReorderWiggle(reorderMode)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .graphicsLayer { rotationZ = wiggle }
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            when (section) {
                DashboardSection.Stats -> Icons.Default.BarChart
                DashboardSection.News -> Icons.Default.Newspaper
                DashboardSection.RecentlyViewed -> Icons.Default.History
                DashboardSection.Bookmarks -> Icons.Default.Bookmark
                DashboardSection.RecentChanges -> Icons.Default.Difference
                DashboardSection.Search -> Icons.Default.Search
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            section.title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (reorderMode && onCustomize != null) {
            IconButton(onClick = onCustomize) {
                Icon(Icons.Default.Tune, contentDescription = "Customize ${section.title}")
            }
        }
        if (reorderMode) {
            IconButton(onClick = onHide) {
                Icon(Icons.Default.VisibilityOff, contentDescription = "Hide ${section.title}")
            }
        }
    }
}

@Composable
private fun InitialSyncOverlay(syncProgress: SyncProgress?) {
    // Deliberately a same-window Compose overlay, not a Dialog: a Dialog owns a separate Android
    // Window, which does not play well with activityRule.scenario.recreate() in NetBoxE2eTest -
    // confirmed via CI, where that combination left the activity stuck "PAUSED" instead of
    // reaching DESTROYED and cascaded into unrelated test failures later in the same run.
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                // A Box with only a background modifier is invisible to hit-testing - this no-op
                // click is what actually absorbs touches so nothing behind is reachable.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.testTag("e2e-initial-sync-overlay"),
        ) {
            Column(
                modifier = Modifier.padding(32.dp).width(240.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(20.dp))
                Text(
                    "Setting up your NetBox instance",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    syncProgress?.message
                        ?: "Fetching your inventory for the first time - this only happens once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (syncProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        syncProgress.notificationSubText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * NBC-437: which stat tiles show on the dashboard, and in what order. A vertical checklist (drag
 * handle to reorder, checkbox to show/hide) reusing [SectionReorderState] as-is - it already
 * operates on a plain scroll-axis offset/size, so the same helper built for the dashboard's
 * vertical section list works unchanged here, no horizontal-drag variant needed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatsCustomizeDialog(
    order: List<String>,
    hidden: Set<String>,
    onOrderChanged: (List<String>) -> Unit,
    onToggleHidden: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = orderedStatCandidates(order)
    val keys = candidates.map { it.endpointPath }
    val listState = rememberLazyListState()
    val reorderState = rememberSectionReorderState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
        title = { Text("Customize stats") },
        text = {
            LazyColumn(state = listState, modifier = Modifier.heightIn(max = 400.dp)) {
                items(candidates, key = { it.endpointPath }) { candidate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier.fillMaxWidth()
                                .sectionDragOffset(candidate.endpointPath, reorderState),
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = "Drag to reorder ${candidate.label}",
                            modifier =
                                Modifier.padding(end = 12.dp)
                                    .sectionReorderGesture(
                                        key = candidate.endpointPath,
                                        order = keys,
                                        listState = listState,
                                        state = reorderState,
                                        onOrderChanged = onOrderChanged,
                                    ),
                        )
                        Checkbox(
                            checked = candidate.endpointPath !in hidden,
                            onCheckedChange = { visible ->
                                onToggleHidden(candidate.endpointPath, !visible)
                            },
                        )
                        Text(candidate.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DashboardVisibilityDialog(
    hidden: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
        title = { Text("Dashboard sections") },
        text = {
            Column {
                DashboardSection.entries.forEach { section ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = section.key !in hidden,
                            onCheckedChange = { visible -> onToggle(section.key, !visible) },
                        )
                        Text(section.title)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun ExpandSectionButton(
    expanded: Boolean,
    collapsedLabel: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "Show less" else collapsedLabel)
    }
}

@Composable
private fun NewsRow(item: NewsItemEntity, onClick: () -> Unit) {
    NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
        NyetboxListItem(
            leadingContent = { Icon(Icons.Default.Newspaper, contentDescription = null) },
            headlineContent = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    item.summary?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (item.publishedAt > 0) {
                        Text(
                            formatTimestamp(
                                java.time.Instant.ofEpochMilli(item.publishedAt).toString()
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open news article")
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun StatsRow(
    stats: List<DashboardStatEntity>,
    objectTypeAccents: Map<String, dev.pschmitt.nyetbox.data.repository.ThemeAccent>,
    onStatClick: (String, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scrim = MaterialTheme.colorScheme.scrim
    // Precise "is there more this way" instead of LazyListState.canScrollForward/canScrollBackward
    // directly: those report true the instant the content is even a fraction of a pixel wider than
    // the viewport, which can flag "more to scroll" when every tile is already effectively fully on
    // screen. Checking whether the actual first/last stat's visible bounds cover the viewport edge
    // only shows the hint when a tile is genuinely, visibly cut off.
    val hasMoreForward by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null ||
                last.index != info.totalItemsCount - 1 ||
                last.offset + last.size > info.viewportEndOffset
        }
    }
    val hasMoreBackward by remember {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            first == null ||
                first.index != 0 ||
                first.offset < listState.layoutInfo.viewportStartOffset
        }
    }
    Box {
        LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(stats, key = { it.endpointPath }) { stat ->
                StatTile(
                    stat,
                    typeColor =
                        MaterialTheme.colorScheme.detailAccentFor(
                            stat.endpointPath,
                            objectTypeAccents[stat.endpointPath.trim('/')],
                        ),
                    onClick = { onStatClick(stat.endpointPath, stat.label) },
                )
            }
        }
        // Fixed-width tiles can land on a screen width that fits exactly N of them with nothing
        // peeking past the edge, so the row reads as "that's all of them" even though it scrolls -
        // and a tile's own card background is the same color as the surrounding section card, so a
        // fade-to-background-color edge is invisible wherever nothing happens to be cut off right
        // at the boundary. A scrim (visible against any backdrop, tile or bare card background)
        // plus a chevron reads unambiguously as "more this way" instead. Only shown on whichever
        // side there's actually more content, not permanently - and never when every tile already
        // fully fits (e.g. only 3 stats configured on a wide enough screen).
        if (hasMoreForward) {
            Box(
                Modifier.align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(32.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.28f))
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "More stats - scroll right",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasMoreBackward) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(24.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(scrim.copy(alpha = 0.28f), scrim.copy(alpha = 0f))
                        )
                    )
            )
        }
    }
}

@Composable
private fun StatTile(
    stat: DashboardStatEntity,
    typeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    // Fixed height too, not just width - the label ("Device Types" vs. "Racks") wraps to a
    // different number of lines depending on its length, which otherwise leaves the cards in a
    // row at different heights.
    Card(
        onClick = onClick,
        modifier = Modifier.size(110.dp, 136.dp),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        // Matching the parent Stats card's own elevation (1.dp) still rendered a visible outline
        // around every tile - Material3's Card applies a tonal elevation overlay on top of
        // containerColor, so a tile "at 1.dp" inside a surface already "at 1.dp" ends up more
        // tinted than its parent despite requesting the identical nominal color, showing up as a
        // seam (confirmed visible in the Play Store screenshots). 0.dp renders flush instead.
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                AppIcons.forEndpointPath(stat.endpointPath),
                contentDescription = null,
                tint = typeColor,
            )
            Spacer(Modifier.height(8.dp))
            Text(stat.count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(
                stat.label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun GlobalSearchCard(
    onClick: () -> Unit,
    reorderMode: Boolean,
    onLongPress: () -> Unit,
    onHide: () -> Unit,
) {
    val wiggle = rememberReorderWiggle(reorderMode)
    // Same pill shape + tonal-surface treatment as ModernSearchField
    // (ui/common/ModernSearchField.kt), so this navigation affordance reads as the same "modern
    // search" visual language as every actual search field in the app.
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier =
            Modifier.fillMaxWidth()
                .testTag("e2e-search-card")
                .graphicsLayer { rotationZ = wiggle }
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        ListItem(
            colors =
                ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    headlineColor = MaterialTheme.colorScheme.onSurface,
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            leadingContent = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text("Search NetBox", style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = { Text("Find devices, IPs, sites, racks, and more") },
            trailingContent = {
                if (reorderMode) {
                    IconButton(onClick = onHide) {
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = "Hide Search NetBox",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun BookmarkRow(
    bookmark: BookmarkEntity,
    thumbnail: DashboardThumbnail?,
    modelLabel: String?,
    typeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    val hasTarget = bookmark.targetEndpointPath != null && bookmark.targetId != null
    val icon =
        bookmark.targetEndpointPath?.let { AppIcons.forEndpointPath(it) } ?: Icons.Default.Bookmark
    NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
        NyetboxListItem(
            leadingContent = {
                if (thumbnail == null) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = typeColor)
                    }
                } else {
                    RemoteThumbnail(
                        imageUrl = thumbnail.url,
                        contentDescription = bookmark.display,
                        modifier = Modifier.size(56.dp),
                        fallbackTint = typeColor,
                    )
                }
            },
            headlineContent = {
                Text(bookmark.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    bookmark.targetEndpointPath?.let { endpointPath ->
                        ObjectTypeBadge(
                            label = objectTypeLabel(modelLabel, endpointPath),
                            icon = AppIcons.forEndpointPath(endpointPath),
                            color = typeColor,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(formatTimestamp(bookmark.created))
                }
            },
            modifier = Modifier.clickable(enabled = hasTarget, onClick = onClick),
        )
    }
}

@Composable
private fun RecentVisitRow(
    visit: RecentVisitEntity,
    thumbnail: DashboardThumbnail?,
    modelLabel: String?,
    typeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
        NyetboxListItem(
            leadingContent = {
                if (thumbnail == null) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            AppIcons.forEndpointPath(visit.endpointPath),
                            contentDescription = null,
                            tint = typeColor,
                        )
                    }
                } else {
                    RemoteThumbnail(
                        imageUrl = thumbnail.url,
                        contentDescription = visit.display,
                        modifier = Modifier.size(56.dp),
                        fallbackTint = typeColor,
                    )
                }
            },
            headlineContent = {
                Text(visit.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    visit.secondaryLine?.takeIf(String::isNotBlank)?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ObjectTypeBadge(
                        label = objectTypeLabel(modelLabel, visit.endpointPath),
                        icon = AppIcons.forEndpointPath(visit.endpointPath),
                        color = typeColor,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Viewed ${formatTimestamp(java.time.Instant.ofEpochMilli(visit.visitedAt).toString())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
private fun ChangeRow(
    change: ObjectChangeEntity,
    thumbnail: DashboardThumbnail?,
    modelLabel: String?,
    typeColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onDiffClick: () -> Unit,
) {
    val hasTarget = change.targetEndpointPath != null && change.targetId != null
    val icon =
        when (change.actionValue) {
            "create" -> Icons.Default.AddCircle
            "update" -> Icons.Default.Edit
            "delete" -> Icons.Default.Delete
            else -> Icons.Default.History
        }
    NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
        NyetboxListItem(
            leadingContent = {
                if (thumbnail == null) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = change.actionLabel, tint = typeColor)
                    }
                } else {
                    RemoteThumbnail(
                        imageUrl = thumbnail.url,
                        contentDescription = change.objectRepr,
                        modifier = Modifier.size(56.dp),
                        fallbackTint = typeColor,
                    )
                }
            },
            headlineContent = {
                Text(change.objectRepr, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    change.targetEndpointPath?.let { endpointPath ->
                        ObjectTypeBadge(
                            label = objectTypeLabel(modelLabel, endpointPath),
                            icon = AppIcons.forEndpointPath(endpointPath),
                            color = typeColor,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text("${change.actionLabel} by ${change.userDisplay}")
                    Text(
                        formatTimestamp(change.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            // A separate affordance from the row tap (which navigates to the object's *current*
            // state) - the diff view shows what this specific change actually did, which the user
            // explicitly asked for as its own destination rather than folded into the object page.
            trailingContent = {
                IconButton(onClick = onDiffClick) {
                    Icon(Icons.Default.Difference, contentDescription = "View change diff")
                }
            },
            modifier = Modifier.clickable(enabled = hasTarget, onClick = onClick),
        )
    }
}

@Composable
private fun EmptyHint(isRefreshing: Boolean, idleText: String) {
    Text(
        if (isRefreshing) "Loading…" else idleText,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * "2026-07-25T16:33:05.946712Z" -> "2026-07-25 16:33" - a first-pass, good-enough human format; no
 * timezone conversion, matches how timestamps are shown elsewhere in the app (e.g. Journal
 * entries) - just raw-ish ISO trimmed to the minute.
 */
private fun formatTimestamp(iso: String): String = formatNetBoxDateTime(iso)
