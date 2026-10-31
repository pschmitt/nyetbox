package dev.pschmitt.nyetbox.ui.generic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.ui.common.ModernSearchField
import dev.pschmitt.nyetbox.ui.common.NetBoxBottomBar
import dev.pschmitt.nyetbox.ui.common.NetBoxResponsiveScaffold
import dev.pschmitt.nyetbox.ui.common.NetBoxSectionHeader
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.NyetboxListItem
import dev.pschmitt.nyetbox.ui.directory.AppIcons
import dev.pschmitt.nyetbox.ui.directory.DirectoryViewModel
import dev.pschmitt.nyetbox.ui.navigation.Route

/** Lets the user choose any discovered NetBox model before opening the generic create form. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemScreen(
    onBack: () -> Unit,
    onModelClick: (NetBoxModelEntity) -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: DirectoryViewModel = hiltViewModel(),
) {
    val modelsByApp by viewModel.modelsByApp.collectAsStateWithLifecycle()
    val pinnedPaths by viewModel.settingsRepository.pinnedModelPaths.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val models =
        buildList {
                add(
                    NetBoxModelEntity(
                        appKey = "dcim",
                        appLabel = "DCIM",
                        modelKey = "devices",
                        modelLabel = "Devices",
                        endpointPath = NetBoxRef.DEVICES_ENDPOINT_PATH,
                    )
                )
                add(
                    NetBoxModelEntity(
                        appKey = "dcim",
                        appLabel = "DCIM",
                        modelKey = "device-types",
                        modelLabel = "Device types",
                        endpointPath = NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH,
                    )
                )
                addAll(
                    modelsByApp
                        .toList()
                        .sortedBy { (appKey, models) -> models.firstOrNull()?.appLabel ?: appKey }
                        .flatMap { (_, appModels) ->
                            appModels.sortedBy { it.modelLabel.lowercase() }
                        }
                )
            }
            .distinctBy { it.endpointPath }
    val normalizedQuery = query.trim().lowercase()
    val filteredModels = models.filter { model ->
        normalizedQuery.isBlank() ||
            model.modelLabel.lowercase().contains(normalizedQuery) ||
            model.modelKey.lowercase().contains(normalizedQuery) ||
            model.appLabel.lowercase().contains(normalizedQuery)
    }
    val defaultPinnedEndpoints =
        listOf(NetBoxRef.DEVICES_ENDPOINT_PATH, NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH)
    val pinnedEndpoints =
        buildList {
                addAll(defaultPinnedEndpoints.filter { it in pinnedPaths })
                addAll(
                    filteredModels
                        .filter {
                            it.endpointPath in pinnedPaths &&
                                it.endpointPath !in defaultPinnedEndpoints
                        }
                        .sortedWith(
                            compareBy<NetBoxModelEntity> { it.appLabel.lowercase() }
                                .thenBy { it.modelLabel.lowercase() }
                        )
                        .map { it.endpointPath }
                )
            }
            .distinct()
            .take(MAX_PINNED_ITEM_TYPES)
    val pinnedModels = pinnedEndpoints.mapNotNull { endpoint ->
        filteredModels.firstOrNull { it.endpointPath == endpoint }
    }
    val otherModels =
        filteredModels
            .filterNot { it.endpointPath in pinnedEndpoints }
            .sortedWith(
                compareBy<NetBoxModelEntity> { it.appLabel.lowercase() }
                    .thenBy { it.modelLabel.lowercase() }
            )

    NetBoxResponsiveScaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add item") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = { NetBoxBottomBar(onNavigate = onNavigate) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ModernSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search item types",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            if (models.isEmpty()) {
                Text(
                    "No NetBox object types are cached yet. Connect and sync first.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
            } else if (filteredModels.isEmpty()) {
                Text(
                    "No matching item types.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
            } else {
                if (pinnedModels.isNotEmpty()) {
                    Column {
                        NetBoxSectionHeader(
                            icon = AppIcons.forAppKey("core"),
                            title = "Pinned",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        pinnedModels.forEach { model ->
                            AddModelRow(
                                model = model,
                                onModelClick = onModelClick,
                                onTogglePin = { viewModel.togglePinned(model.endpointPath) },
                                isPinned = true,
                            )
                        }
                    }
                }
                LazyColumn(modifier = Modifier.fillMaxSize().weight(1f)) {
                    if (pinnedModels.isEmpty()) {
                        item {
                            NetBoxSectionHeader(
                                icon = AppIcons.forAppKey("core"),
                                title = "Item types",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    if (otherModels.isNotEmpty()) {
                        item {
                            NetBoxSectionHeader(
                                icon = AppIcons.forAppKey("core"),
                                title = "All item types",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(otherModels, key = { it.endpointPath }) { model ->
                            AddModelRow(
                                model = model,
                                onModelClick = onModelClick,
                                onTogglePin = { viewModel.togglePinned(model.endpointPath) },
                                isPinned = model.endpointPath in pinnedPaths,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddModelRow(
    model: NetBoxModelEntity,
    onModelClick: (NetBoxModelEntity) -> Unit,
    onTogglePin: () -> Unit,
    isPinned: Boolean,
) {
    NyetboxCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        NyetboxListItem(
            leadingContent = {
                Icon(AppIcons.forEndpointPath(model.endpointPath), contentDescription = null)
            },
            headlineContent = { Text(model.modelLabel) },
            supportingContent = { Text(model.appLabel) },
            trailingContent = {
                IconButton(onClick = onTogglePin) {
                    Box(
                        modifier =
                            Modifier.size(32.dp)
                                .background(
                                    color =
                                        if (isPinned) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "Unpin" else "Pin to top",
                            modifier = Modifier.size(18.dp),
                            tint =
                                if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            modifier = Modifier.clickable(onClick = { onModelClick(model) }),
        )
    }
}

private const val MAX_PINNED_ITEM_TYPES = 5
