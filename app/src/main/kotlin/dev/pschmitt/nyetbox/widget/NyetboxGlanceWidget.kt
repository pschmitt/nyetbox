package dev.pschmitt.nyetbox.widget

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.pschmitt.nyetbox.MainActivity
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.putGestureExtras
import dev.pschmitt.nyetbox.shortcuts.drawableResForGestureActionGlyph
import dev.pschmitt.nyetbox.ui.common.formatRelativeSyncTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val KEY_CONTENT = stringPreferencesKey("content")
internal val KEY_ACTIONS = stringPreferencesKey("actions")
internal val KEY_COMPACT = booleanPreferencesKey("compact")
internal val KEY_SHOW_ACTION_LABELS = booleanPreferencesKey("show_action_labels")

private const val MAX_FETCHED_ROWS = 8
internal const val MAX_WIDGET_ACTIONS = 3

private val TITLE_BAR_HEIGHT = 48.dp
private val ACTION_ROW_SPACER = 16.dp
private val ACTION_LABEL_HEIGHT = 18.dp
private val LIST_ROW_HEIGHT = 44.dp
private val STATS_NARROW_WIDTH = 260.dp
private val SCAFFOLD_HORIZONTAL_PADDING = 12.dp
private val ACTION_BUTTON_GAP = 12.dp
private val MIN_ACTION_BUTTON_SIZE = 44.dp
private val MAX_ACTION_BUTTON_SIZE = 84.dp
private const val ACTION_BUTTON_ICON_RATIO = 0.45f

/** What the widget's main area shows - independently configurable from its action buttons. */
enum class WidgetContent(val storageKey: String, val label: String) {
    Stats("stats", "Sync status & device count"),
    Bookmarks("bookmarks", "Bookmarked devices"),
    RecentChanges("recent_changes", "Recent changes");

    companion object {
        fun fromStorageOrNull(value: String?): WidgetContent? =
            entries.firstOrNull { it.storageKey == value }
    }
}

internal fun encodeWidgetActions(actions: List<NavBarItem>): String =
    Json.encodeToString(ListSerializer(NavBarItem.serializer()), actions)

internal fun decodeWidgetActions(value: String?): List<NavBarItem> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching {
            Json.decodeFromString(ListSerializer(NavBarItem.serializer()), value)
        }
        .getOrNull() ?: emptyList()
}

/**
 * A single home-screen widget whose *content* (this object), *action buttons* (up to
 * [MAX_WIDGET_ACTIONS], any non-Dashboard navigational [GestureAction]) and *compact mode* (hides
 * the title bar) are all per-instance configurable via [WidgetConfigActivity] - reusing
 * [NavBarItem]/[GestureTarget] and `ActionTargetPickerDialog` exactly as-is, the same model already
 * shipped for the nav bar customizer and launcher shortcuts, rather than a new one. Rendered with
 * Glance's own Material 3 building blocks ([Scaffold]/[TitleBar]) and [GlanceTheme]'s dynamic
 * color, so it picks up the same Material You palette as any other modern widget on the home
 * screen. Action buttons are custom (see [ActionButton]) rather than
 * [androidx.glance.appwidget.components.CircleIconButton], to control the icon-to-container ratio
 * and label as the button scales.
 *
 * Uses [SizeMode.Exact] plus [LocalSize] so the layout adapts to however the widget is currently
 * resized (list row count by height, action button size by width, stats row/column by width),
 * rather than a single fixed layout regardless of the widget's actual size.
 *
 * Config (content/actions/compact) and content data (bookmarks/changes/device count/sync status)
 * are both read *reactively* inside the composition (via [WidgetConfigStore] and the repositories'
 * own `Flow`s/`StateFlow`s) rather than as one-shot suspend reads in [provideGlance] - this matters
 * because `update()`/`updateAll()` on an *already-running* composition session just recomposes the
 * existing tree without re-invoking `provideGlance` (see its own doc comment), so a config change
 * saved while a session is still alive would otherwise never reach it - which is exactly what made
 * reconfiguring an already-placed widget appear to silently do nothing.
 */
class NyetboxGlanceWidget
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val dashboardRepository: DashboardRepository,
    private val workManager: WorkManager,
    private val widgetConfigStore: WidgetConfigStore,
) : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        widgetConfigStore.remove(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }

    @OptIn(FlowPreview::class)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        widgetConfigStore.ensureLoaded(context, id, appWidgetId)

        provideContent {
            GlanceTheme {
                val size = LocalSize.current
                val configs by widgetConfigStore.configs.collectAsState()
                val config = configs[appWidgetId] ?: WidgetInstanceConfig()
                // remember: .map builds a new Flow instance on every call, so it must be memoized
                // rather than created inline in the composition - otherwise collectAsState would
                // restart its collection from a fresh Flow on every recomposition.
                val bookmarksFlow =
                    remember {
                        dashboardRepository
                            .observeBookmarks()
                            .map { it.take(MAX_FETCHED_ROWS) }
                            .distinctUntilChanged()
                            .debounce(500)
                    }
                val bookmarks by bookmarksFlow.collectAsState(initial = emptyList())
                val changesFlow =
                    remember {
                        dashboardRepository
                            .observeChangelog()
                            .map { it.take(MAX_FETCHED_ROWS) }
                            .distinctUntilChanged()
                            .debounce(500)
                    }
                val changes by changesFlow.collectAsState(initial = emptyList())
                val deviceCountFlow = remember { deviceRepository.observeCount().distinctUntilChanged() }
                val deviceCount by deviceCountFlow.collectAsState(initial = 0)
                val syncIssue by settingsRepository.syncIssue.collectAsState()
                val lastSuccessfulSyncAt by settingsRepository.lastSuccessfulSyncAt.collectAsState()
                val offlineMode by settingsRepository.offlineMode.collectAsState()
                val syncStatusText =
                    syncIssue?.message
                        ?: formatRelativeSyncTime(lastSuccessfulSyncAt, System.currentTimeMillis())

                val dashboardIntent =
                    Intent(context, MainActivity::class.java).apply {
                        putGestureExtras(GestureAction.Dashboard, null)
                    }
                val visibleActions = config.actions.take(MAX_WIDGET_ACTIONS)
                val buttonSize = actionButtonSize(size, visibleActions.size)
                val actionRowHeight =
                    if (visibleActions.isEmpty()) {
                        0.dp
                    } else {
                        buttonSize +
                            (if (config.showActionLabels) ACTION_LABEL_HEIGHT else 0.dp) +
                            ACTION_ROW_SPACER
                    }
                val headerReserve =
                    (if (config.compact) 0.dp else TITLE_BAR_HEIGHT) + actionRowHeight

                Scaffold(
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    titleBar =
                        if (config.compact) {
                            null
                        } else {
                            {
                                TitleBar(
                                    startIcon = ImageProvider(R.drawable.ic_object_hub),
                                    iconColor = GlanceTheme.colors.primary,
                                    textColor = GlanceTheme.colors.onSurface,
                                    title =
                                        when (config.content) {
                                            WidgetContent.Stats -> "Nyetbox"
                                            WidgetContent.Bookmarks -> "Bookmarks"
                                            WidgetContent.RecentChanges -> "Recent changes"
                                        },
                                )
                            }
                        },
                ) {
                    Column(
                        modifier =
                            GlanceModifier.fillMaxWidth()
                                // Only fires when a row/button below doesn't have its own more
                                // specific clickable - Glance dispatches to the innermost match,
                                // same as Compose.
                                .clickable(actionStartActivity(dashboardIntent))
                    ) {
                        when (config.content) {
                            WidgetContent.Stats -> StatsContent(size, offlineMode, syncStatusText, deviceCount)
                            WidgetContent.Bookmarks ->
                                BookmarksContent(context, bookmarks, size, headerReserve)
                            WidgetContent.RecentChanges ->
                                RecentChangesContent(context, changes, size, headerReserve)
                        }
                        if (visibleActions.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.height(ACTION_ROW_SPACER))
                            Row(
                                modifier = GlanceModifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                visibleActions.forEachIndexed { index, item ->
                                    if (index > 0) Spacer(modifier = GlanceModifier.width(ACTION_BUTTON_GAP))
                                    ActionButton(context, item, buttonSize, config.showActionLabels)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Persists this widget instance's configuration and refreshes it. */
    suspend fun saveConfig(
        context: Context,
        id: GlanceId,
        content: WidgetContent,
        actions: List<NavBarItem>,
        compact: Boolean,
        showActionLabels: Boolean,
    ) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[KEY_CONTENT] = content.storageKey
            prefs[KEY_ACTIONS] = encodeWidgetActions(actions)
            prefs[KEY_COMPACT] = compact
            prefs[KEY_SHOW_ACTION_LABELS] = showActionLabels
        }
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Published immediately so an *already-running* composition session (e.g. this same widget
        // was just interacted with a few seconds ago, before the user opened "Configure") picks up
        // the change right away - see the class doc for why update() alone doesn't guarantee this.
        widgetConfigStore.publish(
            appWidgetId,
            WidgetInstanceConfig(content, actions, compact, showActionLabels),
        )
        update(context, id)
        // Defensive fallback for the brand-new-widget-placement case only (confirmed on a real
        // device: update() is a silent no-op the very first time a widget is configured, before the
        // launcher finishes placing it) - decoupled from WidgetConfigActivity's own lifecycle, by
        // which point placement has completed and a real update takes effect. See
        // WidgetRefreshWorker. Also confirmed: for a widget this fresh,
        // GlanceAppWidgetManager.getGlanceIds() (which the fallback relies on to find it) may not
        // enumerate it yet even a few seconds in, so this schedules two attempts rather than one, to
        // cover both a quick settle and a slower one.
        workManager.enqueue(
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(3, TimeUnit.SECONDS)
                .build()
        )
        workManager.enqueue(
            OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()
        )
    }
}

/**
 * Fills as much of the row's width as the current widget size allows - up to
 * [MAX_ACTION_BUTTON_SIZE], so a widget with room to spare gets genuinely bigger buttons rather
 * than staying pinned to a small fixed size - clamped down to [MIN_ACTION_BUTTON_SIZE] so a narrow
 * widget doesn't overflow/clip instead.
 */
private fun actionButtonSize(size: DpSize, count: Int): Dp {
    if (count == 0) return MIN_ACTION_BUTTON_SIZE
    val available = size.width - SCAFFOLD_HORIZONTAL_PADDING * 2
    val totalGap = ACTION_BUTTON_GAP * (count - 1)
    val perButton = (available - totalGap) / count
    return perButton.coerceIn(MIN_ACTION_BUTTON_SIZE, MAX_ACTION_BUTTON_SIZE)
}

/** How many of the already-fetched rows actually fit in the widget's current height. */
private fun visibleRowCount(size: DpSize, headerReserve: Dp, fetchedCount: Int): Int {
    if (fetchedCount == 0) return 0
    val available = size.height - headerReserve
    val rows = (available / LIST_ROW_HEIGHT).toInt()
    return rows.coerceIn(1, fetchedCount)
}

@Composable
private fun StatsContent(
    size: DpSize,
    offlineMode: Boolean,
    syncStatusText: String,
    deviceCount: Int,
) {
    val statusBlock: @Composable () -> Unit = {
        Column {
            Text(
                text = if (offlineMode) "Offline" else "Synced",
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    ),
            )
            Text(
                text = syncStatusText,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        }
    }
    val chip: @Composable () -> Unit = {
        Box(
            modifier =
                GlanceModifier.background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(16.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$deviceCount",
                    style =
                        TextStyle(
                            color = GlanceTheme.colors.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        ),
                )
                Text(
                    text = "devices",
                    style =
                        TextStyle(color = GlanceTheme.colors.onPrimaryContainer, fontSize = 12.sp),
                )
            }
        }
    }
    if (size.width < STATS_NARROW_WIDTH) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            statusBlock()
            Spacer(modifier = GlanceModifier.height(12.dp))
            chip()
        }
    } else {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = GlanceModifier.defaultWeight()) { statusBlock() }
            Spacer(modifier = GlanceModifier.width(12.dp))
            chip()
        }
    }
}

@Composable
private fun BookmarksContent(
    context: Context,
    bookmarks: List<BookmarkEntity>,
    size: DpSize,
    headerReserve: Dp,
) {
    if (bookmarks.isEmpty()) {
        EmptyRow("No bookmarks yet")
        return
    }
    val visible = bookmarks.take(visibleRowCount(size, headerReserve, bookmarks.size))
    visible.forEachIndexed { index, bookmark ->
        if (index > 0) Spacer(modifier = GlanceModifier.height(8.dp))
        val target =
            bookmark.targetEndpointPath?.let { path ->
                bookmark.targetId?.let { id ->
                    GestureTarget(endpointPath = path, label = bookmark.display, id = id)
                }
            }
        ObjectRow(
            context = context,
            iconRes = WidgetObjectIcons.forEndpointPath(bookmark.targetEndpointPath),
            primaryText = bookmark.display,
            secondaryText = bookmark.objectType,
            target = target,
        )
    }
}

@Composable
private fun RecentChangesContent(
    context: Context,
    changes: List<ObjectChangeEntity>,
    size: DpSize,
    headerReserve: Dp,
) {
    if (changes.isEmpty()) {
        EmptyRow("No recent changes")
        return
    }
    val visible = changes.take(visibleRowCount(size, headerReserve, changes.size))
    visible.forEachIndexed { index, change ->
        if (index > 0) Spacer(modifier = GlanceModifier.height(8.dp))
        val target =
            change.targetEndpointPath?.let { path ->
                change.targetId?.let { id ->
                    GestureTarget(endpointPath = path, label = change.objectRepr, id = id)
                }
            }
        ObjectRow(
            context = context,
            iconRes = WidgetObjectIcons.forEndpointPath(change.targetEndpointPath),
            primaryText = change.objectRepr,
            secondaryText = change.actionLabel,
            target = target,
        )
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
    )
}

/**
 * One list row matching Material 3's list-item shape: a tonal circular leading icon (the same
 * per-object-type icon [dev.pschmitt.nyetbox.ui.directory.AppIcons] uses in-app, mirrored as a
 * drawable via [WidgetObjectIcons]) plus a two-line title/subtitle - independently clickable only
 * when it resolves to a real navigation target.
 */
@Composable
private fun ObjectRow(
    context: Context,
    @DrawableRes iconRes: Int,
    primaryText: String,
    secondaryText: String,
    target: GestureTarget?,
) {
    val base = GlanceModifier.fillMaxWidth()
    val rowModifier =
        if (target == null) {
            base
        } else {
            base.clickable(
                actionStartActivity(
                    Intent(context, MainActivity::class.java).apply {
                        putGestureExtras(GestureAction.DetailSpecific, target)
                    }
                )
            )
        }
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                GlanceModifier.size(36.dp)
                    .background(GlanceTheme.colors.secondaryContainer)
                    .cornerRadius(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(iconRes),
                contentDescription = null,
                modifier = GlanceModifier.size(20.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
            )
        }
        Spacer(modifier = GlanceModifier.width(12.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = primaryText,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            )
            Text(
                text = secondaryText,
                maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
            )
        }
    }
}

/**
 * A tonal circular button, sized by [buttonSize] (see [actionButtonSize] - deliberately not
 * [androidx.glance.appwidget.components.CircleIconButton], which has its own fixed
 * icon-to-container proportions unsuited to a button that can range from 44dp to 84dp). Uses
 * [GlanceTheme.colors.primaryContainer] rather than the passive list rows' `secondaryContainer` so
 * these actionable buttons visually stand out against the widget's other content, and optionally
 * shows [item]'s label underneath so the icon alone doesn't have to carry the button's meaning.
 */
@Composable
private fun ActionButton(context: Context, item: NavBarItem, buttonSize: Dp, showLabel: Boolean) {
    val intent =
        Intent(context, MainActivity::class.java).apply { putGestureExtras(item.action, item.target) }
    val label = item.target?.label ?: item.action.label
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier.width(buttonSize),
    ) {
        Box(
            modifier =
                GlanceModifier.size(buttonSize)
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(buttonSize / 2)
                    .clickable(actionStartActivity(intent)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(drawableResForGestureActionGlyph(item.action)),
                contentDescription = if (showLabel) null else label,
                modifier = GlanceModifier.size(buttonSize * ACTION_BUTTON_ICON_RATIO),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
            )
        }
        if (showLabel) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = label,
                maxLines = 1,
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            )
        }
    }
}
