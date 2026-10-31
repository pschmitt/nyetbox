package dev.pschmitt.nyetbox.ui.topology

import android.graphics.Paint
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.topology.TopologyGraph
import dev.pschmitt.nyetbox.data.topology.TopologyNode
import dev.pschmitt.nyetbox.data.topology.TopologyPosition
import dev.pschmitt.nyetbox.ui.common.RemoteThumbnail
import dev.pschmitt.nyetbox.ui.common.SearchQueryVisualTransformation
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopologyScreen(
    onBack: () -> Unit,
    focusedDeviceId: Int? = null,
    onOpenDevice: (Int) -> Unit = {},
    viewModel: TopologyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    LaunchedEffect(focusedDeviceId) { viewModel.focusDevice(focusedDeviceId) }
    LaunchedEffect(focusedDeviceId, state.focusedNodeId) {
        if (focusedDeviceId != null) selectedNodeId = state.focusedNodeId
    }
    val selectedInfo = selectedNodeId?.let(state.nodeInfo::get)
    val selectedGraph = selectedNodeId?.let { id ->
        state.graph?.nodes?.firstOrNull { it.id == id }
    }
    val connectedInfos =
        selectedNodeId
            ?.let { selected ->
                state.graph?.edges.orEmpty().mapNotNull { edge ->
                    when (selected) {
                        edge.source -> state.nodeInfo[edge.target]
                        edge.target -> state.nodeInfo[edge.source]
                        else -> null
                    }
                }
            }
            .orEmpty()
            .distinctBy { it.nodeId }
    LaunchedEffect(searchOpen) { if (searchOpen) viewModel.searchDevices("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Topology") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = true }) {
                        Icon(Icons.Default.Search, contentDescription = "Search topology devices")
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.isRefreshing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh topology")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.graph?.let { graph ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Hub,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${graph.nodes.size} nodes · ${graph.edges.size} connections",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.cachedAt?.let { cachedAt ->
                        Text(
                            " · ${DateUtils.getRelativeTimeSpanString(cachedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    TopologyGraphCanvas(
                        graph = graph,
                        nodeInfo = state.nodeInfo,
                        showDeviceTypeImages = state.showDeviceTypeImages,
                        focusedNodeId = state.focusedNodeId,
                        onNodeClick = { selectedNodeId = it },
                        onNodeDragEnd = viewModel::moveNode,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                }
                Text(
                    "Pinch, Ctrl+scroll, or use the controls to zoom. Long-press a node to move it; tap one for details. This graph is cached for offline use.",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
                ?: run {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(PaddingValues(24.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.isRefreshing || state.isLoading) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Text("Loading topology…", modifier = Modifier.padding(top = 12.dp))
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Hub,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    state.errorMessage ?: "No topology export is cached yet",
                                    modifier = Modifier.padding(top = 12.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = viewModel::refresh) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Retry topology refresh",
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    if (selectedInfo != null) {
        ModalBottomSheet(onDismissRequest = { selectedNodeId = null }) {
            TopologyNodeSheet(
                info = selectedInfo,
                graphNode = selectedGraph,
                connectedDevices = connectedInfos,
                showDeviceTypeImages = state.showDeviceTypeImages,
                onOpenDevice = { deviceId ->
                    selectedNodeId = null
                    onOpenDevice(deviceId)
                },
                onSelectConnected = { selectedNodeId = it.nodeId },
            )
        }
    }
    if (searchOpen) {
        TopologyDeviceSearchSheet(
            devices = state.deviceSearchResults,
            onDismiss = { searchOpen = false },
            onQueryChange = viewModel::searchDevices,
            onSelect = { info ->
                searchOpen = false
                viewModel.focusDevice(info.deviceId)
                selectedNodeId = info.nodeId
            },
        )
    }
}

@Composable
private fun TopologyGraphCanvas(
    graph: TopologyGraph,
    nodeInfo: Map<String, TopologyNodeInfo>,
    showDeviceTypeImages: Boolean,
    focusedNodeId: String?,
    onNodeClick: (String) -> Unit,
    onNodeDragEnd: (String, TopologyPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewportSize by remember(graph) { mutableStateOf(IntSize.Zero) }
    var zoom by remember(graph) { mutableFloatStateOf(1f) }
    var pan by remember(graph) { mutableStateOf(Offset.Zero) }
    val dragOffsets = remember(graph) { mutableStateMapOf<String, Offset>() }
    val latestOnNodeDragEnd by rememberUpdatedState(onNodeDragEnd)
    val latestOnNodeClick by rememberUpdatedState(onNodeClick)
    var ctrlPressed by remember { mutableStateOf(false) }
    var initialized by remember(graph) { mutableStateOf(false) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(MIN_TOPOLOGY_ZOOM, MAX_TOPOLOGY_ZOOM)
        pan += panChange
    }
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val labelPaint =
        remember(colorScheme.onSurface, density.density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorScheme.onSurface.toArgb()
                textAlign = Paint.Align.CENTER
            }
        }
    val renderData =
        remember(graph, colorScheme.outline) { buildTopologyRenderData(graph, colorScheme.outline) }
    val graphBounds = renderData.bounds
    val focusedPoint = focusedNodeId?.let { id ->
        graph.nodes
            .firstOrNull { it.id == id }
            ?.let { Offset(it.x + it.width / 2f, it.y + it.height / 2f) }
    }

    fun updateZoom(requestedZoom: Float) {
        val nextZoom = requestedZoom.coerceIn(MIN_TOPOLOGY_ZOOM, MAX_TOPOLOGY_ZOOM)
        pan =
            topologyButtonZoomPan(
                bounds = graphBounds,
                viewportSize = viewportSize,
                currentZoom = zoom,
                nextZoom = nextZoom,
                currentPan = pan,
                focusedPoint = focusedPoint,
            )
        zoom = nextZoom
    }

    fun zoomAt(position: Offset) {
        val nextZoom =
            (zoom * TOPOLOGY_DOUBLE_TAP_ZOOM_FACTOR).coerceIn(MIN_TOPOLOGY_ZOOM, MAX_TOPOLOGY_ZOOM)
        if (nextZoom == zoom || viewportSize == IntSize.Zero) return
        pan =
            topologyDoubleTapZoomPan(
                viewportSize = viewportSize,
                currentZoom = zoom,
                nextZoom = nextZoom,
                currentPan = pan,
                tapPosition = position,
            )
        zoom = nextZoom
    }

    LaunchedEffect(graph, viewportSize, focusedNodeId) {
        if (!initialized && viewportSize != IntSize.Zero) {
            zoom = initialTopologyZoom(graph.nodes.size, viewportSize.width.toFloat())
            pan =
                focusedPoint?.let { topologyFocusPan(graphBounds, viewportSize, zoom, it) }
                    ?: Offset.Zero
            initialized = true
        } else if (focusedPoint != null && viewportSize != IntSize.Zero) {
            pan = topologyFocusPan(graphBounds, viewportSize, zoom, focusedPoint)
        }
    }

    val totalScale = topologyTotalScale(renderData.bounds, viewportSize, zoom)
    val imageLayouts =
        if (showDeviceTypeImages && totalScale >= TOPOLOGY_IMAGE_SCALE) {
            topologyNodeLayouts(renderData, viewportSize, zoom, pan, dragOffsets).filter {
                nodeInfo[it.node.id]?.localImageFile != null
            }
        } else {
            emptyList()
        }

    Box(
        modifier =
            modifier
                .onSizeChanged { viewportSize = it }
                .background(colorScheme.surfaceContainerLow)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.CtrlLeft || event.key == Key.CtrlRight) {
                        ctrlPressed = event.type == KeyEventType.KeyDown
                        true
                    } else {
                        false
                    }
                }
                .pointerInput(graph, zoom, pan, focusedNodeId, ctrlPressed) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            val scrollY = change.scrollDelta.y
                            if (ctrlPressed && scrollY != 0f) {
                                change.consume()
                                updateZoom(topologyZoomForScroll(zoom, scrollY, ctrlPressed))
                            }
                        }
                    }
                }
                .semantics {
                    contentDescription =
                        "Topology graph with ${graph.nodes.size} nodes and ${graph.edges.size} connections"
                }
    ) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .pointerInput(renderData, viewportSize, zoom, pan) {
                        detectTapGestures(
                            onDoubleTap = ::zoomAt,
                            onTap = { position ->
                                topologyNodeAt(
                                        position,
                                        renderData,
                                        viewportSize,
                                        zoom,
                                        pan,
                                        dragOffsets,
                                    )
                                    ?.let { latestOnNodeClick(it.node.id) }
                            },
                        )
                    }
                    .pointerInput(renderData, viewportSize, zoom, pan) {
                        var activeNodeId: String? = null
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                activeNodeId =
                                    topologyNodeAt(
                                            position,
                                            renderData,
                                            viewportSize,
                                            zoom,
                                            pan,
                                            dragOffsets,
                                        )
                                        ?.node
                                        ?.id
                            },
                            onDrag = { change, dragAmount ->
                                val nodeId = activeNodeId ?: return@detectDragGesturesAfterLongPress
                                change.consume()
                                val delta = dragAmount / totalScale.coerceAtLeast(0.001f)
                                val current = dragOffsets[nodeId] ?: Offset.Zero
                                dragOffsets[nodeId] = current + delta
                            },
                            onDragEnd = {
                                val nodeId = activeNodeId
                                activeNodeId = null
                                if (nodeId != null) {
                                    val delta = dragOffsets.remove(nodeId) ?: Offset.Zero
                                    if (delta != Offset.Zero) {
                                        latestOnNodeDragEnd(
                                            nodeId,
                                            TopologyPosition(delta.x, delta.y),
                                        )
                                    }
                                }
                            },
                            onDragCancel = {
                                activeNodeId?.let(dragOffsets::remove)
                                activeNodeId = null
                            },
                        )
                    }
                    .transformable(transformState)
        ) {
            val availableWidth = (size.width - 32f).coerceAtLeast(1f)
            val availableHeight = (size.height - 32f).coerceAtLeast(1f)
            val fitScale = topologyFitScale(graphBounds, availableWidth, availableHeight)
            val totalScale = fitScale * zoom
            val graphCenter = graphBounds.center
            val screenCenter = Offset(size.width / 2f, size.height / 2f) + pan

            fun map(x: Float, y: Float): Offset =
                screenCenter + (Offset(x, y) - graphCenter) * totalScale

            renderData.edges.forEach { edge ->
                val source = renderData.nodes[edge.sourceIndex]
                val target = renderData.nodes[edge.targetIndex]
                val sourceOffset = dragOffsets[source.node.id] ?: Offset.Zero
                val targetOffset = dragOffsets[target.node.id] ?: Offset.Zero
                drawLine(
                    color = edge.color,
                    start =
                        map(
                            source.node.x + sourceOffset.x + source.node.width / 2f,
                            source.node.y + sourceOffset.y + source.node.height / 2f,
                        ),
                    end =
                        map(
                            target.node.x + targetOffset.x + target.node.width / 2f,
                            target.node.y + targetOffset.y + target.node.height / 2f,
                        ),
                    strokeWidth = max(1.5f, 2.5f * totalScale),
                )
            }

            val fill = colorScheme.surfaceContainerHighest
            val border = colorScheme.primary
            val focusedBorder = colorScheme.tertiary
            renderData.nodes.forEach { renderNode ->
                val node = renderNode.node
                val offset = dragOffsets[node.id] ?: Offset.Zero
                val topLeft = map(node.x + offset.x, node.y + offset.y)
                val nodeSize = Size(node.width * totalScale, node.height * totalScale)
                drawRoundRect(
                    color = fill,
                    topLeft = topLeft,
                    size = nodeSize,
                    cornerRadius = CornerRadius(8f * totalScale.coerceAtLeast(0.5f)),
                )
                drawRoundRect(
                    color = if (node.id == focusedNodeId) focusedBorder else border,
                    topLeft = topLeft,
                    size = nodeSize,
                    cornerRadius = CornerRadius(8f * totalScale.coerceAtLeast(0.5f)),
                    style = Stroke(width = max(1f, 1.5f * totalScale)),
                )
                drawTopologyNodeIcon(
                    kind = renderNode.iconKind,
                    center = topLeft + Offset(nodeSize.width / 2f, nodeSize.height / 2f),
                    radius = max(3f, min(nodeSize.width, nodeSize.height) * 0.26f),
                    color = border,
                )
            }

            if (totalScale >= TOPOLOGY_LABEL_SCALE) {
                drawIntoCanvas { canvas ->
                    labelPaint.textSize = (10f * density.density * totalScale).coerceIn(8f, 24f)
                    renderData.nodes.forEach { renderNode ->
                        val node = renderNode.node
                        val offset = dragOffsets[node.id] ?: Offset.Zero
                        val topLeft = map(node.x + offset.x, node.y + offset.y)
                        val nodeSize = Size(node.width * totalScale, node.height * totalScale)
                        val lineCount =
                            if (totalScale < TOPOLOGY_DETAIL_SCALE) 1
                            else renderNode.labelLines.size
                        repeat(lineCount.coerceAtMost(renderNode.labelLines.size)) { index ->
                            canvas.nativeCanvas.drawText(
                                renderNode.labelLines[index],
                                topLeft.x + nodeSize.width / 2f,
                                topLeft.y + nodeSize.height + labelPaint.textSize * (index + 1.2f),
                                labelPaint,
                            )
                        }
                    }
                }
            }
        }

        imageLayouts.forEach { layout ->
            val info = nodeInfo[layout.node.id]
            Box(
                modifier =
                    Modifier.offset {
                            IntOffset(
                                layout.topLeft.x.roundToInt(),
                                layout.topLeft.y.roundToInt(),
                            )
                        }
                        .size(
                            with(density) { layout.size.width.toDp() },
                            with(density) { layout.size.height.toDp() },
                        )
                        .semantics {
                            contentDescription =
                                "Topology node ${info?.displayName ?: layout.node.label}"
                        }
            ) {
                if (showDeviceTypeImages && info?.localImageFile != null) {
                    RemoteThumbnail(
                        imageUrl = info.frontImageUrl,
                        localFile = info.localImageFile,
                        contentDescription = info.displayName,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        Row(
            modifier =
                Modifier.align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(
                        colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
                        RoundedCornerShape(14.dp),
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { updateZoom(zoom / ZOOM_STEP) },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
            }
            Text(
                "${(zoom * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
            IconButton(
                onClick = {
                    val resetZoom =
                        initialTopologyZoom(graph.nodes.size, viewportSize.width.toFloat())
                    zoom = resetZoom
                    pan =
                        focusedPoint?.let {
                            topologyFocusPan(graphBounds, viewportSize, resetZoom, it)
                        } ?: Offset.Zero
                },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset topology view")
            }
            IconButton(
                onClick = { updateZoom(zoom * ZOOM_STEP) },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
            }
        }
    }
}

@Composable
private fun TopologyNodeSheet(
    info: TopologyNodeInfo,
    graphNode: TopologyNode?,
    connectedDevices: List<TopologyNodeInfo>,
    showDeviceTypeImages: Boolean,
    onOpenDevice: (Int) -> Unit,
    onSelectConnected: (TopologyNodeInfo) -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showDeviceTypeImages && !info.frontImageUrl.isNullOrBlank()) {
                RemoteThumbnail(
                    imageUrl = info.frontImageUrl,
                    localFile = info.localImageFile,
                    contentDescription = info.displayName,
                    modifier = Modifier.size(72.dp),
                )
            } else {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(info.displayName, style = MaterialTheme.typography.titleLarge)
                info.deviceTypeModel?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                info.statusLabel?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        info.deviceId?.let { deviceId ->
            Button(onClick = { onOpenDevice(deviceId) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Text("Open device", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (connectedDevices.isNotEmpty()) {
            Text("Connected devices", style = MaterialTheme.typography.titleMedium)
            connectedDevices.forEach { connected ->
                ListItem(
                    leadingContent = {
                        if (showDeviceTypeImages && !connected.frontImageUrl.isNullOrBlank()) {
                            RemoteThumbnail(
                                imageUrl = connected.frontImageUrl,
                                localFile = connected.localImageFile,
                                contentDescription = connected.displayName,
                                modifier = Modifier.size(56.dp),
                            )
                        } else {
                            Icon(Icons.Default.Hub, contentDescription = null)
                        }
                    },
                    headlineContent = { Text(connected.displayName) },
                    supportingContent = { connected.deviceTypeModel?.let { Text(it) } },
                    modifier = Modifier.clickable { onSelectConnected(connected) },
                )
            }
        } else {
            Text(
                "No connected devices in the cached topology",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        graphNode?.let { node ->
            Text(
                "Node ${node.id}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopologyDeviceSearchSheet(
    devices: List<TopologyNodeInfo>,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelect: (TopologyNodeInfo) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Find a device", style = MaterialTheme.typography.titleLarge)
            Text(
                "Search the cached topology devices. Try name:, manufacturer:, ip:, or mac:.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation =
                    SearchQueryVisualTransformation(MaterialTheme.colorScheme.primary),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search devices") },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (devices.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No device nodes are cached"
                            else "No matching device nodes",
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(devices, key = { it.nodeId }) { info ->
                        ListItem(
                            leadingContent = {
                                if (info.localImageFile != null) {
                                    RemoteThumbnail(
                                        imageUrl = info.frontImageUrl,
                                        localFile = info.localImageFile,
                                        contentDescription = info.displayName,
                                        modifier = Modifier.size(48.dp),
                                    )
                                } else {
                                    Icon(Icons.Default.Hub, contentDescription = null)
                                }
                            },
                            headlineContent = { Text(info.displayName) },
                            supportingContent = {
                                Column {
                                    info.deviceTypeModel?.let { Text(it) }
                                    info.matchHint?.let {
                                        Text(
                                            "Matched $it",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onSelect(info) },
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

internal data class TopologyRenderNode(
    val node: TopologyNode,
    val iconKind: TopologyNodeIconKind,
    val labelLines: List<String>,
)

internal data class TopologyRenderEdge(
    val sourceIndex: Int,
    val targetIndex: Int,
    val color: Color,
)

internal data class TopologyRenderData(
    val nodes: List<TopologyRenderNode>,
    val edges: List<TopologyRenderEdge>,
    val bounds: Rect,
)

internal fun buildTopologyRenderData(
    graph: TopologyGraph,
    fallbackEdgeColor: Color,
): TopologyRenderData {
    val nodes =
        graph.nodes.map { node ->
            TopologyRenderNode(
                node = node,
                iconKind = topologyNodeIconKind(node.label),
                labelLines = topologyLabelParts(node.label),
            )
        }
    val indexById = nodes.mapIndexed { index, node -> node.node.id to index }.toMap()
    val edges =
        graph.edges.mapNotNull { edge ->
            val sourceIndex = indexById[edge.source] ?: return@mapNotNull null
            val targetIndex = indexById[edge.target] ?: return@mapNotNull null
            TopologyRenderEdge(
                sourceIndex = sourceIndex,
                targetIndex = targetIndex,
                color = parseColor(edge.color, fallbackEdgeColor),
            )
        }
    return TopologyRenderData(nodes = nodes, edges = edges, bounds = graph.bounds())
}

private data class TopologyNodeLayout(
    val node: TopologyNode,
    val topLeft: Offset,
    val size: Size,
    val totalScale: Float,
)

private fun topologyNodeLayouts(
    renderData: TopologyRenderData,
    viewportSize: IntSize,
    zoom: Float,
    pan: Offset,
    dragOffsets: Map<String, Offset>,
): List<TopologyNodeLayout> {
    if (viewportSize == IntSize.Zero) return emptyList()
    val bounds = renderData.bounds
    val fitScale =
        topologyFitScale(
            bounds,
            (viewportSize.width - 32).coerceAtLeast(1).toFloat(),
            (viewportSize.height - 32).coerceAtLeast(1).toFloat(),
        )
    val totalScale = fitScale * zoom
    val screenCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f) + pan
    return renderData.nodes.map { renderNode ->
        val node = renderNode.node
        val offset = dragOffsets[node.id] ?: Offset.Zero
        val topLeft =
            screenCenter +
                (Offset(node.x + offset.x, node.y + offset.y) - bounds.center) * totalScale
        TopologyNodeLayout(
            node = node,
            topLeft = topLeft,
            size = Size(node.width * totalScale, node.height * totalScale),
            totalScale = totalScale,
        )
    }
}

private fun topologyTotalScale(bounds: Rect, viewportSize: IntSize, zoom: Float): Float {
    if (viewportSize == IntSize.Zero) return 0f
    return topologyFitScale(
        bounds,
        (viewportSize.width - 32).coerceAtLeast(1).toFloat(),
        (viewportSize.height - 32).coerceAtLeast(1).toFloat(),
    ) * zoom
}

private fun topologyNodeAt(
    position: Offset,
    renderData: TopologyRenderData,
    viewportSize: IntSize,
    zoom: Float,
    pan: Offset,
    dragOffsets: Map<String, Offset>,
): TopologyRenderNode? {
    val totalScale = topologyTotalScale(renderData.bounds, viewportSize, zoom)
    if (totalScale <= 0f) return null
    val screenCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f) + pan
    val boundsCenter = renderData.bounds.center
    return renderData.nodes.asReversed().firstOrNull { renderNode ->
        val node = renderNode.node
        val offset = dragOffsets[node.id] ?: Offset.Zero
        val topLeft =
            screenCenter +
                (Offset(node.x + offset.x, node.y + offset.y) - boundsCenter) * totalScale
        position.x >= topLeft.x &&
            position.x <= topLeft.x + node.width * totalScale &&
            position.y >= topLeft.y &&
            position.y <= topLeft.y + node.height * totalScale
    }
}

internal fun topologyFocusPan(
    bounds: Rect,
    viewportSize: IntSize,
    zoom: Float,
    focusedPoint: Offset,
): Offset {
    val fitScale =
        topologyFitScale(
            bounds,
            (viewportSize.width - 32).coerceAtLeast(1).toFloat(),
            (viewportSize.height - 32).coerceAtLeast(1).toFloat(),
        )
    return Offset(viewportSize.width / 2f, viewportSize.height / 2f) -
        (focusedPoint - bounds.center) * (fitScale * zoom) -
        Offset(viewportSize.width / 2f, viewportSize.height / 2f)
}

internal fun topologyButtonZoomPan(
    bounds: Rect,
    viewportSize: IntSize,
    currentZoom: Float,
    nextZoom: Float,
    currentPan: Offset,
    focusedPoint: Offset?,
): Offset {
    val fitScale =
        topologyFitScale(
            bounds,
            (viewportSize.width - 32).coerceAtLeast(1).toFloat(),
            (viewportSize.height - 32).coerceAtLeast(1).toFloat(),
        )
    val currentScale = (fitScale * currentZoom).coerceAtLeast(0.001f)
    val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    val visiblePoint = bounds.center - currentPan / currentScale
    val target =
        focusedPoint
            ?: Offset(
                visiblePoint.x.coerceIn(bounds.left, bounds.right),
                visiblePoint.y.coerceIn(bounds.top, bounds.bottom),
            )
    return -(target - bounds.center) * (fitScale * nextZoom)
}

/** Keeps the point under a double-tap stationary while increasing the topology zoom. */
internal fun topologyDoubleTapZoomPan(
    viewportSize: IntSize,
    currentZoom: Float,
    nextZoom: Float,
    currentPan: Offset,
    tapPosition: Offset,
): Offset {
    val viewportCenter = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
    val zoomRatio = nextZoom / currentZoom.coerceAtLeast(0.001f)
    return (tapPosition - viewportCenter) - (tapPosition - viewportCenter - currentPan) * zoomRatio
}

internal fun topologyZoomForScroll(
    currentZoom: Float,
    scrollY: Float,
    ctrlPressed: Boolean,
): Float =
    if (!ctrlPressed || scrollY == 0f) {
        currentZoom
    } else {
        (currentZoom * if (scrollY < 0f) ZOOM_STEP else 1f / ZOOM_STEP).coerceIn(
            MIN_TOPOLOGY_ZOOM,
            MAX_TOPOLOGY_ZOOM,
        )
    }

private const val MIN_TOPOLOGY_ZOOM = 0.35f
private const val MAX_TOPOLOGY_ZOOM = 8f
private const val ZOOM_STEP = 1.4f
private const val TOPOLOGY_DOUBLE_TAP_ZOOM_FACTOR = 2f
private const val TOPOLOGY_LABEL_SCALE = 0.55f
private const val TOPOLOGY_DETAIL_SCALE = 1.4f
private const val TOPOLOGY_IMAGE_SCALE = 1.4f

internal enum class TopologyNodeIconKind {
    Generic,
    Compute,
    Network,
    Power,
    Wireless,
}

internal fun topologyNodeIconKind(label: String): TopologyNodeIconKind {
    val normalized = label.lowercase()
    return when {
        listOf("power", "pdu", "ups", "breaker", "outlet", "feed").any(normalized::contains) ->
            TopologyNodeIconKind.Power
        listOf("switch", "router", "firewall", "gateway", "access point", " wi-fi", "wifi")
            .any(normalized::contains) -> TopologyNodeIconKind.Network
        listOf("server", "nuc", "nas", "kvm", "proxmox", "compute").any(normalized::contains) ->
            TopologyNodeIconKind.Compute
        listOf(
                "sensor",
                "thermostat",
                "motion",
                "button",
                "plug",
                "light",
                "cube",
                "door",
                "wireless",
            )
            .any(normalized::contains) -> TopologyNodeIconKind.Wireless
        else -> TopologyNodeIconKind.Generic
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTopologyNodeIcon(
    kind: TopologyNodeIconKind,
    center: Offset,
    radius: Float,
    color: Color,
) {
    val strokeWidth = max(1f, radius * 0.22f)
    when (kind) {
        TopologyNodeIconKind.Generic -> drawCircle(color = color, radius = radius, center = center)
        TopologyNodeIconKind.Compute -> {
            drawRoundRect(
                color = color,
                topLeft = center - Offset(radius * 0.85f, radius * 0.7f),
                size = Size(radius * 1.7f, radius * 1.4f),
                cornerRadius = CornerRadius(radius * 0.18f),
                style = Stroke(width = strokeWidth),
            )
            repeat(2) { index ->
                val y = center.y - radius * 0.25f + index * radius * 0.5f
                drawLine(
                    color = color,
                    start = Offset(center.x - radius * 0.55f, y),
                    end = Offset(center.x + radius * 0.55f, y),
                    strokeWidth = strokeWidth,
                )
            }
        }
        TopologyNodeIconKind.Network -> {
            drawCircle(color = color, radius = radius * 0.35f, center = center)
            val top = center + Offset(0f, -radius)
            val right = center + Offset(radius, 0f)
            val bottom = center + Offset(0f, radius)
            val left = center + Offset(-radius, 0f)
            drawLine(color = color, start = center, end = top, strokeWidth = strokeWidth)
            drawCircle(color = color, radius = radius * 0.24f, center = top)
            drawLine(color = color, start = center, end = right, strokeWidth = strokeWidth)
            drawCircle(color = color, radius = radius * 0.24f, center = right)
            drawLine(color = color, start = center, end = bottom, strokeWidth = strokeWidth)
            drawCircle(color = color, radius = radius * 0.24f, center = bottom)
            drawLine(color = color, start = center, end = left, strokeWidth = strokeWidth)
            drawCircle(color = color, radius = radius * 0.24f, center = left)
        }
        TopologyNodeIconKind.Power -> {
            drawLine(
                color = color,
                start = center + Offset(0f, -radius),
                end = center + Offset(0f, radius * 0.25f),
                strokeWidth = strokeWidth,
            )
            drawArc(
                color = color,
                startAngle = 35f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = center - Offset(radius * 0.8f, radius * 0.8f),
                size = Size(radius * 1.6f, radius * 1.6f),
                style = Stroke(width = strokeWidth),
            )
        }
        TopologyNodeIconKind.Wireless -> {
            repeat(3) { index ->
                val scale =
                    when (index) {
                        0 -> 0.35f
                        1 -> 0.65f
                        else -> 0.95f
                    }
                drawArc(
                    color = color,
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = center - Offset(radius * scale, radius * scale),
                    size = Size(radius * scale * 2f, radius * scale * 2f),
                    style = Stroke(width = strokeWidth),
                )
            }
            drawCircle(
                color = color,
                radius = strokeWidth * 0.8f,
                center = center + Offset(0f, radius * 0.55f),
            )
        }
    }
}

internal fun initialTopologyZoom(nodeCount: Int, viewportWidth: Float): Float =
    when {
        viewportWidth < 720f -> if (nodeCount > 40) 0.85f else 1f
        viewportWidth < 1200f -> if (nodeCount > 40) 0.95f else 1f
        else -> 1f
    }

internal fun topologyFitScale(bounds: Rect, availableWidth: Float, availableHeight: Float): Float =
    min(
            availableWidth / bounds.width.coerceAtLeast(1f),
            availableHeight / bounds.height.coerceAtLeast(1f),
        )
        // A fit scale already makes the whole graph visible. Capping it prevents a one-node or
        // malformed export from becoming a giant square before the user has interacted with it.
        .coerceIn(0.15f, 2f)

internal fun topologyLabelLines(label: String, totalScale: Float): List<String> {
    if (totalScale < TOPOLOGY_LABEL_SCALE) return emptyList()

    val lines = topologyLabelParts(label)
    return lines.take(if (totalScale < TOPOLOGY_DETAIL_SCALE) 1 else 3)
}

private fun topologyLabelParts(label: String): List<String> =
    label.lines().map { it.take(32) }.filter(String::isNotBlank).take(3)

private fun TopologyGraph.bounds(): Rect {
    if (nodes.isEmpty()) return Rect(0f, 0f, 1f, 1f)
    val minX = nodes.minOf(TopologyNode::x)
    val minY = nodes.minOf(TopologyNode::y)
    val maxX = nodes.maxOf { it.x + it.width }
    val maxY = nodes.maxOf { it.y + it.height }
    return Rect(minX - 40f, minY - 40f, maxX + 40f, maxY + 80f)
}

private fun TopologyGraph.contentCenter(): Offset {
    if (nodes.isEmpty()) return Offset.Zero
    return Offset(
        nodes.map { it.x + it.width / 2f }.average().toFloat(),
        nodes.map { it.y + it.height / 2f }.average().toFloat(),
    )
}

private fun parseColor(value: String, fallback: Color): Color =
    runCatching {
            Color(value.toColorInt())
        }
        .getOrDefault(fallback)
