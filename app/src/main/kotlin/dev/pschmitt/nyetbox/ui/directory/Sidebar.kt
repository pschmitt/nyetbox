package dev.pschmitt.nyetbox.ui.directory

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.repository.TopologyRepository
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.ui.common.ModernSearchField
import dev.pschmitt.nyetbox.ui.common.rememberReorderWiggle
import dev.pschmitt.nyetbox.ui.common.rememberSectionReorderState
import dev.pschmitt.nyetbox.ui.common.sectionDragOffset
import dev.pschmitt.nyetbox.ui.common.sectionReorderGesture
import dev.pschmitt.nyetbox.ui.common.visualColorForEndpointPath

private const val DEVICES_PATH = NetBoxRef.DEVICES_ENDPOINT_PATH

internal fun sidebarVisibleAppKeysForSearch(
    modelsByApp: Map<String, List<NetBoxModelEntity>>,
    query: String,
    hiddenApps: Set<String>,
): Set<String> {
    val modelMatches =
        if (query.isBlank()) {
            modelsByApp.keys
        } else {
            modelsByApp
                .filterValues { models ->
                    models.any { it.modelLabel.contains(query, ignoreCase = true) }
                }
                .keys
        }
    val specialMatches =
        if (
            query.isNotBlank() &&
                TopologyRepository.PLUGIN_APP_KEY !in hiddenApps &&
                "Topology".contains(query, ignoreCase = true)
        ) {
            setOf(TopologyRepository.PLUGIN_APP_KEY)
        } else {
            emptySet()
        }
    return (modelMatches + specialMatches) - hiddenApps
}

private fun <T> moveItem(items: List<T>, item: T, offset: Int): List<T>? {
    val from = items.indexOf(item)
    val to = from + offset
    if (from < 0 || to !in items.indices) return null
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
fun Sidebar(
    onDeviceListClick: () -> Unit,
    onModelClick: (NetBoxModelEntity) -> Unit,
    onTopologyClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    viewModel: DirectoryViewModel = hiltViewModel(),
) {
    val modelsByApp by viewModel.modelsByApp.collectAsStateWithLifecycle()
    val pinnedModels by viewModel.pinnedModels.collectAsStateWithLifecycle()
    val pinnedPaths by viewModel.settingsRepository.pinnedModelPaths.collectAsStateWithLifecycle()
    val sidebarAppOrder by viewModel.sidebarAppOrder.collectAsStateWithLifecycle()
    val sidebarModelOrders by viewModel.sidebarModelOrders.collectAsStateWithLifecycle()
    val hiddenSidebarApps by viewModel.hiddenSidebarApps.collectAsStateWithLifecycle()
    val offlineMode by viewModel.settingsRepository.offlineMode.collectAsStateWithLifecycle()
    val credentials by viewModel.settingsRepository.credentials.collectAsStateWithLifecycle()
    val objectTypeAccents by
        viewModel.settingsRepository.objectTypeAccents.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    // Collapsed by default, like the NetBox web UI's sidebar - matches app keys ("dcim",
    // "plugins/netbox-documents", ...), not the humanized labels.
    var expandedApps by remember { mutableStateOf(emptySet<String>()) }
    var reorderMode by remember { mutableStateOf(false) }
    var showSidebarVisibilityDialog by remember { mutableStateOf(false) }
    val sidebarListState = rememberLazyListState()
    val sidebarReorderState = rememberSectionReorderState()
    val context = LocalContext.current
    // ic_launcher is an adaptive icon; render the resolved drawable so the same app artwork is
    // usable inside Compose on every Android version.
    val appIconBitmap = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap()
    }

    val filteredModelsByApp =
        if (searchQuery.isBlank()) modelsByApp
        else
            modelsByApp
                .mapValues { (_, models) ->
                    models.filter { it.modelLabel.contains(searchQuery, ignoreCase = true) }
                }
                .filterValues { it.isNotEmpty() }
    val visibleFilteredModelsByApp = filteredModelsByApp.filterKeys { it !in hiddenSidebarApps }
    val visibleSidebarAppKeysForSearch =
        sidebarVisibleAppKeysForSearch(modelsByApp, searchQuery, hiddenSidebarApps)
    val visibleSidebarAppKeys = orderSidebarAppKeys(visibleSidebarAppKeysForSearch, sidebarAppOrder)
    val allSidebarAppKeys = orderSidebarAppKeys(modelsByApp.keys, sidebarAppOrder)

    ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
        Column(Modifier.fillMaxHeight()) {
            LazyColumn(
                state = sidebarListState,
                modifier = Modifier.weight(1f).testTag("e2e-sidebar-list"),
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        if (appIconBitmap != null) {
                            Image(
                                bitmap = appIconBitmap,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Icon(
                                AppIcons.forEndpointPath(DEVICES_PATH),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Nyetbox",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (reorderMode) {
                            IconButton(onClick = { showSidebarVisibilityDialog = true }) {
                                Icon(
                                    Icons.Default.Visibility,
                                    contentDescription = "Show or hide sidebar groups",
                                )
                            }
                            IconButton(onClick = { reorderMode = false }) {
                                Icon(
                                    Icons.Default.Done,
                                    contentDescription = "Finish organizing sidebar",
                                )
                            }
                        }
                    }
                }
                item {
                    ModernSearchField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = "Search sections",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (searchQuery.isBlank()) {
                    item {
                        NavigationDrawerItem(
                            label = { Text("Devices") },
                            icon = {
                                Icon(
                                    AppIcons.forEndpointPath(DEVICES_PATH),
                                    contentDescription = null,
                                    tint =
                                        visualColorForEndpointPath(
                                            DEVICES_PATH,
                                            objectTypeAccents[DEVICES_PATH.trim('/')],
                                            MaterialTheme.colorScheme,
                                        ),
                                )
                            },
                            selected = false,
                            onClick = onDeviceListClick,
                            modifier =
                                Modifier.padding(horizontal = 12.dp)
                                    .testTag("e2e-device-list-entry"),
                        )
                    }
                    items(
                        pinnedModels.filter { it.endpointPath != DEVICES_PATH },
                        key = { "pinned-${it.endpointPath}" },
                    ) { model ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NavigationDrawerItem(
                                label = { Text(model.modelLabel) },
                                icon = {
                                    Icon(
                                        AppIcons.forEndpointPath(model.endpointPath),
                                        contentDescription = null,
                                        tint =
                                            visualColorForEndpointPath(
                                                model.endpointPath,
                                                objectTypeAccents[model.endpointPath.trim('/')],
                                                MaterialTheme.colorScheme,
                                            ),
                                    )
                                },
                                selected = false,
                                onClick = { onModelClick(model) },
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            if (reorderMode) {
                                IconButton(
                                    onClick = { viewModel.togglePinned(model.endpointPath) }
                                ) {
                                    Box(
                                        modifier =
                                            Modifier.size(32.dp)
                                                .background(
                                                    color =
                                                        MaterialTheme.colorScheme.primaryContainer,
                                                    shape = CircleShape,
                                                ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.PushPin,
                                            contentDescription = "Unpin",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                visibleSidebarAppKeys.forEach { appKey ->
                    val models =
                        orderSidebarModels(
                            appKey,
                            visibleFilteredModelsByApp[appKey].orEmpty(),
                            sidebarModelOrders[appKey].orEmpty(),
                        )
                    val appLabel =
                        models.firstOrNull()?.appLabel
                            ?: modelsByApp[appKey]?.firstOrNull()?.appLabel
                            ?: appKey
                    val appColor =
                        models.firstOrNull()?.let { model ->
                            visualColorForEndpointPath(
                                model.endpointPath,
                                objectTypeAccents[model.endpointPath.trim('/')],
                            )
                        }
                            ?: modelsByApp[appKey]?.firstOrNull()?.let { model ->
                                visualColorForEndpointPath(
                                    model.endpointPath,
                                    objectTypeAccents[model.endpointPath.trim('/')],
                                )
                            }
                            ?: androidx.compose.ui.graphics.Color.Gray
                    // Searching implicitly expands every matching section - no point collapsing
                    // search results the user is actively looking for.
                    val isExpanded = searchQuery.isNotBlank() || appKey in expandedApps
                    item(key = "sidebar-app-$appKey") {
                        val wiggle = rememberReorderWiggle(reorderMode)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .sectionDragOffset("sidebar-app-$appKey", sidebarReorderState)
                                    .sectionReorderGesture(
                                        key = "sidebar-app-$appKey",
                                        order = visibleSidebarAppKeys.map { "sidebar-app-$it" },
                                        listState = sidebarListState,
                                        state = sidebarReorderState,
                                        onDragStart = { reorderMode = true },
                                        onOrderChanged = { changed ->
                                            viewModel.setSidebarAppOrder(
                                                changed.map { it.removePrefix("sidebar-app-") }
                                            )
                                        },
                                    )
                                    .graphicsLayer { rotationZ = wiggle }
                                    .clickable(
                                        onClick = {
                                            expandedApps =
                                                if (appKey in expandedApps) expandedApps - appKey
                                                else expandedApps + appKey
                                        }
                                    )
                                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        ) {
                            Icon(
                                AppIcons.forAppKey(appKey),
                                contentDescription = null,
                                tint = appColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                appLabel,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            if (reorderMode) {
                                IconButton(
                                    onClick = { viewModel.setSidebarAppHidden(appKey, true) }
                                ) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = "Hide $appLabel",
                                    )
                                }
                            }
                            Icon(
                                if (isExpanded) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (isExpanded) {
                        if (
                            appKey == TopologyRepository.PLUGIN_APP_KEY &&
                                (searchQuery.isBlank() ||
                                    "Topology".contains(searchQuery, ignoreCase = true))
                        ) {
                            item {
                                NavigationDrawerItem(
                                    label = { Text("Topology") },
                                    icon = { Icon(Icons.Default.Hub, contentDescription = null) },
                                    selected = false,
                                    onClick = onTopologyClick,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                        items(models, key = { it.endpointPath }) { model ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            ) {
                                NavigationDrawerItem(
                                    label = { Text(model.modelLabel) },
                                    icon = {
                                        Icon(
                                            AppIcons.forEndpointPath(model.endpointPath),
                                            contentDescription = null,
                                            tint =
                                                visualColorForEndpointPath(
                                                    model.endpointPath,
                                                    objectTypeAccents[model.endpointPath.trim('/')],
                                                    MaterialTheme.colorScheme,
                                                ),
                                        )
                                    },
                                    selected = false,
                                    onClick = { onModelClick(model) },
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                )
                                if (reorderMode) {
                                    val orderedModels = models.map { it.modelKey }
                                    IconButton(
                                        onClick = {
                                            moveItem(orderedModels, model.modelKey, -1)?.let {
                                                viewModel.setSidebarModelOrder(appKey, it)
                                            }
                                        },
                                        enabled = orderedModels.firstOrNull() != model.modelKey,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "Move ${model.modelLabel} up",
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            moveItem(orderedModels, model.modelKey, 1)?.let {
                                                viewModel.setSidebarModelOrder(appKey, it)
                                            }
                                        },
                                        enabled = orderedModels.lastOrNull() != model.modelKey,
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = "Move ${model.modelLabel} down",
                                        )
                                    }
                                }
                                val isPinned = model.endpointPath in pinnedPaths
                                IconButton(
                                    onClick = { viewModel.togglePinned(model.endpointPath) }
                                ) {
                                    Box(
                                        modifier =
                                            Modifier.size(32.dp)
                                                .background(
                                                    color =
                                                        if (isPinned)
                                                            MaterialTheme.colorScheme
                                                                .primaryContainer
                                                        else
                                                            MaterialTheme.colorScheme
                                                                .surfaceContainerHighest,
                                                    shape = CircleShape,
                                                ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            if (isPinned) Icons.Filled.PushPin
                                            else Icons.Outlined.PushPin,
                                            contentDescription =
                                                if (isPinned) "Unpin" else "Pin to top",
                                            modifier = Modifier.size(18.dp),
                                            tint =
                                                if (isPinned)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider()
            // Static (non-scrolling), like SidebarFooter below it - always visible above the
            // footer rather than the last, easy-to-miss item in the scrolling model list.
            ListItem(
                leadingContent = { Icon(Icons.Default.CloudOff, contentDescription = null) },
                headlineContent = { Text("Offline mode") },
                supportingContent = {
                    Text(if (offlineMode) "Cached data only" else "Allow network sync")
                },
                trailingContent = {
                    Switch(
                        checked = offlineMode,
                        onCheckedChange = viewModel::setOfflineMode,
                        modifier = Modifier.testTag("e2e-offline-toggle"),
                    )
                },
            )
            HorizontalDivider()
            SidebarFooter(
                appVersion = BuildConfig.VERSION_NAME,
                netboxUrl = credentials.baseUrl,
                onSettingsClick = onSettingsClick,
                onAboutClick = onAboutClick,
            )
        }
    }

    if (showSidebarVisibilityDialog) {
        SidebarVisibilityDialog(
            appKeys = allSidebarAppKeys,
            labels = modelsByApp.mapValues { (_, models) -> models.firstOrNull()?.appLabel },
            hidden = hiddenSidebarApps,
            onToggle = viewModel::setSidebarAppHidden,
            onDismiss = { showSidebarVisibilityDialog = false },
        )
    }
}

@Composable
private fun SidebarVisibilityDialog(
    appKeys: List<String>,
    labels: Map<String, String?>,
    hidden: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Visibility, contentDescription = null) },
        title = { Text("Sidebar groups") },
        text = {
            Column {
                appKeys.forEach { appKey ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(
                            checked = appKey !in hidden,
                            onCheckedChange = { visible -> onToggle(appKey, !visible) },
                        )
                        Text(labels[appKey] ?: appKey)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Static (non-scrolling) footer pinned to the bottom of the drawer. */
@Composable
private fun SidebarFooter(
    appVersion: String,
    netboxUrl: String,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f).clickable(onClick = onAboutClick)) {
            Text(
                "version $appVersion",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (netboxUrl.isNotBlank()) {
                Text(
                    displayNetBoxHostname(netboxUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            modifier = Modifier.testTag("e2e-settings-action"),
            onClick = onSettingsClick,
        ) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}
