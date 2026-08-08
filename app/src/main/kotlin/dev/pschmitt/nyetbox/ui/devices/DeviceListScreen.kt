package dev.pschmitt.nyetbox.ui.devices

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.repository.parseGlobalSearchQuery
import dev.pschmitt.nyetbox.ui.common.AssetTagBadge
import dev.pschmitt.nyetbox.ui.common.MissingAssetTagBadge
import dev.pschmitt.nyetbox.ui.common.ModernSearchField
import dev.pschmitt.nyetbox.ui.common.NetBoxBottomBar
import dev.pschmitt.nyetbox.ui.common.NetBoxResponsiveScaffold
import dev.pschmitt.nyetbox.ui.common.RemoteThumbnail
import dev.pschmitt.nyetbox.ui.common.SearchHighlightedText
import dev.pschmitt.nyetbox.ui.common.StatusChip
import dev.pschmitt.nyetbox.ui.common.SuppressiblePullToRefreshBox
import dev.pschmitt.nyetbox.ui.common.detailAccentFor
import dev.pschmitt.nyetbox.ui.directory.AppIcons
import dev.pschmitt.nyetbox.ui.navigation.Route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    onDeviceClick: (Int) -> Unit,
    onCreateClick: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNavigate: (Route) -> Unit,
    viewModel: DeviceListViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val deviceTypeImages by viewModel.deviceTypeImages.collectAsStateWithLifecycle()
    val objectTypeAccent by viewModel.objectTypeAccent.collectAsStateWithLifecycle()
    val rowColor = MaterialTheme.colorScheme.detailAccentFor("api/dcim/devices/", objectTypeAccent)
    val query by viewModel.query.collectAsStateWithLifecycle()
    // Free text + filter *values* only, keys stripped - so typing `status:active` highlights
    // "active" in the row instead of the literal "status:active" (mirrors GlobalSearchScreen).
    val highlightQuery = remember(query) { parseGlobalSearchQuery(query).networkQuery }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    NetBoxResponsiveScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            AppIcons.forEndpointPath("api/dcim/devices/"),
                            contentDescription = null,
                            tint = rowColor,
                        )
                        Text("Devices", modifier = Modifier.padding(start = 8.dp))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation")
                    }
                },
            )
        },
        bottomBar = { NetBoxBottomBar(onNavigate = onNavigate) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SuppressiblePullToRefreshBox(
                // Keep the gesture active, but don't duplicate the global sync progress indicator.
                isRefreshing = false,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    ModernSearchField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = "Search devices",
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (devices.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (isRefreshing) "Loading devices…"
                                else "No devices cached yet - pull to sync",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).testTag("e2e-device-list"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(
                                items = devices,
                                key = { it.id },
                                contentType = { "device-row" },
                            ) { device ->
                                DeviceRow(
                                    device = device,
                                    frontImageUrl =
                                        deviceTypeImages[device.deviceTypeId]?.frontImageUrl,
                                    fallbackTint = rowColor,
                                    highlightQuery = highlightQuery,
                                    onClick = { onDeviceClick(device.id) },
                                )
                            }
                        }
                    }
                }
            }
            FloatingActionButton(
                onClick = onCreateClick,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create device")
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceEntity,
    frontImageUrl: String?,
    fallbackTint: androidx.compose.ui.graphics.Color,
    highlightQuery: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag("e2e-device-list-row"),
        shape = RoundedCornerShape(20.dp),
        colors =
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteThumbnail(
                imageUrl = frontImageUrl,
                contentDescription = device.deviceTypeModel,
                modifier = Modifier.size(64.dp),
                fallbackTint = fallbackTint,
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchHighlightedText(
                        value = device.name,
                        query = highlightQuery,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp),
                    )
                }
                val subtitle =
                    listOfNotNull(device.siteName, device.deviceTypeModel).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    SearchHighlightedText(
                        value = subtitle,
                        query = highlightQuery,
                        style =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    device.assetTag?.takeIf(String::isNotBlank)?.let {
                        AssetTagBadge(it, highlightQuery = highlightQuery)
                    } ?: MissingAssetTagBadge()
                    StatusChip(
                        label = device.statusLabel,
                        value = device.statusValue,
                        highlightQuery = highlightQuery,
                    )
                }
            }
        }
    }
}
