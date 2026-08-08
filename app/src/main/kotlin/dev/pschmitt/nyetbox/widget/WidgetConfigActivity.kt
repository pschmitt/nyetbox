package dev.pschmitt.nyetbox.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.ui.common.formatRelativeSyncTime
import dev.pschmitt.nyetbox.ui.common.iconForGestureAction
import dev.pschmitt.nyetbox.ui.directory.AppIcons
import dev.pschmitt.nyetbox.ui.settings.ActionTargetPickerDialog
import dev.pschmitt.nyetbox.ui.theme.NyetboxTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WidgetConfigViewModel
@Inject
constructor(
    directoryRepository: DirectoryRepository,
    private val genericObjectRepository: GenericObjectRepository,
    dashboardRepository: DashboardRepository,
    deviceRepository: DeviceRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val models: StateFlow<List<NetBoxModelEntity>> =
        directoryRepository
            .observeAll()
            .map { models -> models.distinctBy { it.endpointPath }.sortedBy { it.modelLabel.lowercase() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** See [GenericObjectRepository.observeObjectChoices] - backs [ActionTargetPickerDialog]. */
    fun objectChoices(endpointPath: String, query: String): Flow<List<NetBoxObjectEntity>> =
        genericObjectRepository.observeObjectChoices(endpointPath, query)

    /** Live sample data for [WidgetPreviewCard] - the same sources the widget itself renders. */
    val bookmarks: StateFlow<List<BookmarkEntity>> =
        dashboardRepository
            .observeBookmarks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val changes: StateFlow<List<ObjectChangeEntity>> =
        dashboardRepository
            .observeChangelog()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val deviceCount: StateFlow<Int> =
        deviceRepository.observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val statusText: StateFlow<String> =
        combine(settingsRepository.syncIssue, settingsRepository.lastSuccessfulSyncAt) { issue, lastSync ->
                issue?.message ?: formatRelativeSyncTime(lastSync, System.currentTimeMillis())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val offlineMode: StateFlow<Boolean> = settingsRepository.offlineMode
}

/**
 * Shown by Android before a placed widget instance is confirmed (`APPWIDGET_CONFIGURE`), and again
 * from the placed widget's own long-press "Configure" menu (API 31+, see
 * `android:widgetFeatures="reconfigurable"` in `nyetbox_widget_info.xml`). Lets the user pick both
 * what the widget's main area shows ([WidgetContent]) and up to [MAX_WIDGET_ACTIONS] optional
 * action buttons (any non-Dashboard navigational [GestureAction] + optional target) - see
 * [NyetboxGlanceWidget].
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var widget: NyetboxGlanceWidget

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Documented back-out safety net: if the user backs out before saving, Android must see
        // this widget as never configured.
        setResult(RESULT_CANCELED)

        appWidgetId =
            intent?.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            NyetboxTheme {
                val scope = rememberCoroutineScope()
                var content by remember { mutableStateOf(WidgetContent.Stats) }
                var actions by remember { mutableStateOf<List<NavBarItem>>(emptyList()) }
                var compact by remember { mutableStateOf(false) }
                var showActionLabels by remember { mutableStateOf(true) }
                var loaded by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val glanceId =
                        GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                    val prefs =
                        getAppWidgetState(
                            this@WidgetConfigActivity,
                            PreferencesGlanceStateDefinition,
                            glanceId,
                        )
                    content = WidgetContent.fromStorageOrNull(prefs[KEY_CONTENT]) ?: WidgetContent.Stats
                    actions = decodeWidgetActions(prefs[KEY_ACTIONS])
                    compact = prefs[KEY_COMPACT] ?: false
                    showActionLabels = prefs[KEY_SHOW_ACTION_LABELS] ?: true
                    loaded = true
                }

                if (loaded) {
                    WidgetConfigScreen(
                        content = content,
                        onContentChange = { content = it },
                        actions = actions,
                        onActionsChange = { actions = it },
                        compact = compact,
                        onCompactChange = { compact = it },
                        showActionLabels = showActionLabels,
                        onShowActionLabelsChange = { showActionLabels = it },
                        onSave = {
                            scope.launch {
                                val glanceId =
                                    GlanceAppWidgetManager(this@WidgetConfigActivity)
                                        .getGlanceIdBy(appWidgetId)
                                widget.saveConfig(
                                    this@WidgetConfigActivity,
                                    glanceId,
                                    content,
                                    actions,
                                    compact,
                                    showActionLabels,
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
                                )
                                finish()
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetConfigScreen(
    viewModel: WidgetConfigViewModel = hiltViewModel(),
    content: WidgetContent,
    onContentChange: (WidgetContent) -> Unit,
    actions: List<NavBarItem>,
    onActionsChange: (List<NavBarItem>) -> Unit,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    showActionLabels: Boolean,
    onShowActionLabelsChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val models by viewModel.models.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val changes by viewModel.changes.collectAsState()
    val deviceCount by viewModel.deviceCount.collectAsState()
    val statusText by viewModel.statusText.collectAsState()
    val offlineMode by viewModel.offlineMode.collectAsState()
    var pickerAction by remember { mutableStateOf<GestureAction?>(null) }
    var addMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Widget settings") }) },
        bottomBar = {
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Save")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                WidgetPreviewCard(
                    content = content,
                    actions = actions,
                    compact = compact,
                    showActionLabels = showActionLabels,
                    bookmarks = bookmarks,
                    changes = changes,
                    deviceCount = deviceCount,
                    statusText = statusText,
                    offlineMode = offlineMode,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            item { Text("Content", modifier = Modifier.padding(16.dp, 8.dp)) }
            items(WidgetContent.entries) { option ->
                ListItem(
                    modifier = Modifier.clickable { onContentChange(option) },
                    leadingContent = {
                        RadioButton(selected = content == option, onClick = { onContentChange(option) })
                    },
                    headlineContent = { Text(option.label) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { onCompactChange(!compact) },
                    headlineContent = { Text("Compact mode") },
                    supportingContent = { Text("Hide the title bar to fit more content") },
                    trailingContent = {
                        Switch(checked = compact, onCheckedChange = onCompactChange)
                    },
                )
            }
            item {
                Text(
                    "Action buttons (up to $MAX_WIDGET_ACTIONS, optional)",
                    modifier = Modifier.padding(16.dp, 8.dp),
                )
            }
            if (actions.isNotEmpty()) {
                item {
                    ListItem(
                        modifier = Modifier.clickable { onShowActionLabelsChange(!showActionLabels) },
                        headlineContent = { Text("Show labels") },
                        supportingContent = { Text("Show each action's name under its button") },
                        trailingContent = {
                            Switch(checked = showActionLabels, onCheckedChange = onShowActionLabelsChange)
                        },
                    )
                }
            }
            itemsIndexed(actions) { index, item ->
                ListItem(
                    leadingContent = {
                        Icon(iconForGestureAction(item.action), contentDescription = null)
                    },
                    headlineContent = { Text(item.target?.label ?: item.action.label) },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                onActionsChange(actions.filterIndexed { i, _ -> i != index })
                            }
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    },
                )
            }
            if (actions.size < MAX_WIDGET_ACTIONS) {
                item {
                    Box {
                        ListItem(
                            modifier = Modifier.clickable { addMenuExpanded = true },
                            leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                            headlineContent = { Text("Add action button") },
                        )
                        DropdownMenu(
                            expanded = addMenuExpanded,
                            onDismissRequest = { addMenuExpanded = false },
                        ) {
                            GestureAction.shortcutable.forEach { candidate ->
                                DropdownMenuItem(
                                    text = { Text(candidate.label) },
                                    leadingIcon = {
                                        Icon(iconForGestureAction(candidate), contentDescription = null)
                                    },
                                    onClick = {
                                        addMenuExpanded = false
                                        when (candidate) {
                                            GestureAction.AddSpecific,
                                            GestureAction.ListSpecific,
                                            GestureAction.DetailSpecific -> pickerAction = candidate
                                            else -> onActionsChange(actions + NavBarItem(candidate))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pickerAction?.let { action ->
        ActionTargetPickerDialog(
            action = action,
            models = models,
            objectChoices = viewModel::objectChoices,
            onDismiss = { pickerAction = null },
            onModelSelected = { model ->
                onActionsChange(
                    actions +
                        NavBarItem(
                            action,
                            GestureTarget(endpointPath = model.endpointPath, label = model.modelLabel),
                        )
                )
                pickerAction = null
            },
            onObjectSelected = { obj ->
                onActionsChange(
                    actions +
                        NavBarItem(
                            action,
                            GestureTarget(
                                endpointPath = obj.endpointPath,
                                label = obj.display,
                                id = obj.id,
                            ),
                        )
                )
                pickerAction = null
            },
        )
    }
}

private data class PreviewRow(val endpointPath: String?, val primary: String, val secondary: String)

/**
 * Live mockup of how the widget will look with the current (unsaved) selections. Glance's own
 * rendering can't be reused outside a Glance composition, so this is a parallel plain-Compose
 * approximation, driven by the same underlying data (bookmarks/changes/device count/sync status)
 * the real widget renders - see [NyetboxGlanceWidget].
 */
@Composable
private fun WidgetPreviewCard(
    content: WidgetContent,
    actions: List<NavBarItem>,
    compact: Boolean,
    showActionLabels: Boolean,
    bookmarks: List<BookmarkEntity>,
    changes: List<ObjectChangeEntity>,
    deviceCount: Int,
    statusText: String,
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text =
                            when (content) {
                                WidgetContent.Stats -> "Nyetbox"
                                WidgetContent.Bookmarks -> "Bookmarks"
                                WidgetContent.RecentChanges -> "Recent changes"
                            },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            when (content) {
                WidgetContent.Stats ->
                    PreviewStatsRow(offlineMode = offlineMode, statusText = statusText, deviceCount = deviceCount)
                WidgetContent.Bookmarks ->
                    PreviewObjectRows(
                        rows =
                            bookmarks.take(4).map {
                                PreviewRow(it.targetEndpointPath, it.display, it.objectType)
                            },
                        emptyText = "No bookmarks yet",
                    )
                WidgetContent.RecentChanges ->
                    PreviewObjectRows(
                        rows =
                            changes.take(4).map {
                                PreviewRow(it.targetEndpointPath, it.objectRepr, it.actionLabel)
                            },
                        emptyText = "No recent changes",
                    )
            }
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row {
                    actions.forEachIndexed { index, item ->
                        if (index > 0) Spacer(modifier = Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier =
                                    Modifier.size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    iconForGestureAction(item.action),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            if (showActionLabels) {
                                Text(
                                    text = item.target?.label ?: item.action.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.width(48.dp).padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStatsRow(offlineMode: Boolean, statusText: String, deviceCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = if (offlineMode) "Offline" else "Synced", style = MaterialTheme.typography.bodyLarge)
            Text(text = statusText, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier =
                Modifier.background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "$deviceCount", style = MaterialTheme.typography.titleLarge)
                Text(text = "devices", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PreviewObjectRows(rows: List<PreviewRow>, emptyText: String) {
    if (rows.isEmpty()) {
        Text(text = emptyText, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier.size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = row.endpointPath?.let { AppIcons.forEndpointPath(it) } ?: Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = row.primary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(text = row.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}
