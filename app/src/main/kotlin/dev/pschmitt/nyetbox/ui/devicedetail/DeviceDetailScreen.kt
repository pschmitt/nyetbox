package dev.pschmitt.nyetbox.ui.devicedetail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.db.DeviceTypeEntity
import dev.pschmitt.nyetbox.data.db.ImageAttachmentEntity
import dev.pschmitt.nyetbox.data.repository.CachedDocument
import dev.pschmitt.nyetbox.data.repository.DeleteSubmission
import dev.pschmitt.nyetbox.data.repository.hiddenFieldPreferenceKey
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.ui.common.AssetTagBadge
import dev.pschmitt.nyetbox.ui.common.CollapsibleCommentCard
import dev.pschmitt.nyetbox.ui.common.CommentCard
import dev.pschmitt.nyetbox.ui.common.DetailTrailingActions
import dev.pschmitt.nyetbox.ui.common.FieldActionDialog
import dev.pschmitt.nyetbox.ui.common.ImageViewerDialog
import dev.pschmitt.nyetbox.ui.common.ImageViewerItem
import dev.pschmitt.nyetbox.ui.common.ImageViewerMetadataLink
import dev.pschmitt.nyetbox.ui.common.ItemDetailScaffold
import dev.pschmitt.nyetbox.ui.common.ItemDetailTab
import dev.pschmitt.nyetbox.ui.common.ItemDetailTabLayout
import dev.pschmitt.nyetbox.ui.common.ItemDetailTopBar
import dev.pschmitt.nyetbox.ui.common.JournalEntryEditorDialog
import dev.pschmitt.nyetbox.ui.common.MatterPairingCodeDialog
import dev.pschmitt.nyetbox.ui.common.MediaCarousel
import dev.pschmitt.nyetbox.ui.common.MediaUploadDialog
import dev.pschmitt.nyetbox.ui.common.MediaUploadKind
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.NyetboxDetailsCard
import dev.pschmitt.nyetbox.ui.common.NyetboxListItem
import dev.pschmitt.nyetbox.ui.common.PrintLabelDialog
import dev.pschmitt.nyetbox.ui.common.PrintLabelRequest
import dev.pschmitt.nyetbox.ui.common.RemoteThumbnail
import dev.pschmitt.nyetbox.ui.common.StatusChip
import dev.pschmitt.nyetbox.ui.common.SuppressiblePullToRefreshBox
import dev.pschmitt.nyetbox.ui.common.SyncPulseIcon
import dev.pschmitt.nyetbox.ui.common.detailAccentFor
import dev.pschmitt.nyetbox.ui.common.displayName
import dev.pschmitt.nyetbox.ui.common.fileViewIntent
import dev.pschmitt.nyetbox.ui.common.formatNetBoxDateTime
import dev.pschmitt.nyetbox.ui.common.journalKindPresentation
import dev.pschmitt.nyetbox.ui.common.shareIntent
import dev.pschmitt.nyetbox.ui.common.toDocumentViewerItem
import dev.pschmitt.nyetbox.ui.common.toImageViewerItem
import dev.pschmitt.nyetbox.ui.directory.AppIcons
import dev.pschmitt.nyetbox.ui.generic.FieldRow
import dev.pschmitt.nyetbox.ui.generic.GenericDetailChangelogRow
import dev.pschmitt.nyetbox.ui.generic.JournalEntryUi
import dev.pschmitt.nyetbox.ui.generic.actionValue
import dev.pschmitt.nyetbox.ui.generic.fieldRows
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private val interfaceJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceDetailScreen(
    deviceId: Int,
    onBack: () -> Unit,
    onEditClick: () -> Unit,
    onEditFieldClick: (fieldKey: String) -> Unit,
    onDeviceTypeClick: (id: Int, breadcrumb: String) -> Unit,
    onReferenceClick: (endpointPath: String, id: Int, breadcrumb: String) -> Unit,
    onRackPositionClick: (rackId: Int, deviceId: Int, breadcrumb: String) -> Unit,
    onAddComponent: () -> Unit,
    onOpenTopology: () -> Unit,
    onChangeDiffClick: (changeId: Int) -> Unit,
    onDeleted: () -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val device by viewModel.device.collectAsStateWithLifecycle()
    val hasCheckedCache by viewModel.hasCheckedCache.collectAsStateWithLifecycle()
    val tabsReady by viewModel.tabsReady.collectAsStateWithLifecycle()
    val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
    val netboxBaseUrl by viewModel.netboxBaseUrl.collectAsStateWithLifecycle()
    val deviceType by viewModel.deviceType.collectAsStateWithLifecycle()
    val deviceTypeImages by viewModel.deviceTypeImages.collectAsStateWithLifecycle()
    val manufacturerId by viewModel.manufacturerId.collectAsStateWithLifecycle()
    val imageAttachments by viewModel.imageAttachments.collectAsStateWithLifecycle()
    val interfaceIpAddresses by viewModel.interfaceIpAddresses.collectAsStateWithLifecycle()
    val journalEntries by viewModel.journalEntries.collectAsStateWithLifecycle()
    val changelog by viewModel.changelog.collectAsStateWithLifecycle()
    val connectedDevices by viewModel.connectedDevices.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val documentDeleteResult by viewModel.documentDeleteResult.collectAsStateWithLifecycle()
    val bookmark by viewModel.bookmark.collectAsStateWithLifecycle()
    val isTogglingBookmark by viewModel.isTogglingBookmark.collectAsStateWithLifecycle()
    val documentPluginAvailable by viewModel.documentPluginAvailable.collectAsStateWithLifecycle()
    val topologyPluginAvailable by viewModel.topologyPluginAvailable.collectAsStateWithLifecycle()
    val journalMutationState by viewModel.journalMutationState.collectAsStateWithLifecycle()
    val customFieldRows by viewModel.customFieldRows.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val fileToOpen by viewModel.fileToOpen.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }
    val relatedCounts = DEVICE_RELATED_TABS.map { tab ->
        when (tab.endpointPath) {
            JOURNAL_TAB_ENDPOINT_PATH -> journalEntries.size
            CONNECTED_DEVICES_TAB_ENDPOINT_PATH ->
                if (topologyPluginAvailable) connectedDevices.size else 0
            else ->
                viewModel.relatedObjects[tab.endpointPath]
                    ?.collectAsStateWithLifecycle()
                    ?.value
                    ?.size ?: 0
        }
    }
    val visibleRelatedTabs = DEVICE_RELATED_TABS.filterIndexed { index, _ ->
        relatedCounts[index] > 0
    }
    val changelogTabIndex = visibleRelatedTabs.size + 1
    val tabCount = changelogTabIndex + 1
    val visibleSelectedTab = selectedTab.coerceIn(0, tabCount - 1)
    LaunchedEffect(visibleRelatedTabs) { selectedTab = visibleSelectedTab }
    val selectedRelatedObjects =
        if (visibleSelectedTab in 1..visibleRelatedTabs.size) {
            val endpointPath = visibleRelatedTabs[visibleSelectedTab - 1].endpointPath
            viewModel.relatedObjects[endpointPath]?.collectAsStateWithLifecycle()?.value.orEmpty()
        } else {
            emptyList()
        }
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val deleteResult by viewModel.deleteResult.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val refreshedMessage by viewModel.refreshedMessage.collectAsStateWithLifecycle()
    val refreshToastMessage by viewModel.refreshToastMessage.collectAsStateWithLifecycle()
    val hiddenFieldKeys by viewModel.hiddenFieldKeys.collectAsStateWithLifecycle()
    val objectTypeAccent by viewModel.objectTypeAccent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Full-screen image viewer state (NBC-20) - which item list + which index within it is open,
    // shared by both the device-type front/rear photos and the image-attachment row below.
    var imageViewer by remember { mutableStateOf<Pair<List<ImageViewerItem>, Int>?>(null) }
    var matterPairingCode by remember { mutableStateOf<String?>(null) }
    var copiedMessage by remember { mutableStateOf<String?>(null) }
    var printRequest by remember { mutableStateOf<PrintLabelRequest?>(null) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showMediaUpload by remember { mutableStateOf(false) }
    var mediaUploadInitialKind by remember { mutableStateOf<MediaUploadKind?>(null) }
    var mediaUploadInitialUri by remember { mutableStateOf<Uri?>(null) }
    var imageAttachmentAction by remember { mutableStateOf<ImageAttachmentEntity?>(null) }
    var imageAttachmentToEdit by remember { mutableStateOf<ImageAttachmentEntity?>(null) }
    var showJournalEditor by remember { mutableStateOf(false) }
    var journalEditorEntry by remember { mutableStateOf<JournalEntryUi?>(null) }
    var showHiddenFields by remember { mutableStateOf(false) }
    var fieldActionLabel by remember { mutableStateOf<String?>(null) }
    val hiddenFieldsForDevice = hiddenFieldKeys.filter { it.startsWith("device/") }
    val isFieldVisible: (String) -> Boolean = { label ->
        showHiddenFields || hiddenFieldPreferenceKey("api/dcim/devices/", label) !in hiddenFieldKeys
    }
    val visibleCustomFieldRows =
        visibleDeviceCustomFieldRows(customFieldRows, hiddenFieldKeys, showHiddenFields)
    val detailAccent =
        MaterialTheme.colorScheme.detailAccentFor("api/dcim/devices/", objectTypeAccent)
    val deviceTypeViewerItems =
        deviceTypePhotoItems(deviceType, device?.manufacturerName, manufacturerId)
    val imageAttachmentViewerItems = imageAttachments.map {
        it.toImageViewerItem(sourceLabel = "Image attachment")
    }
    val documentViewerItems: Map<CachedDocument, ImageViewerItem> =
        documents
            .mapNotNull { document ->
                val localFile =
                    document.documentUrl?.let {
                        viewModel.localAttachmentFile(it, document.filename)
                    }
                document.toDocumentViewerItem(localFile)?.let { document to it }
            }
            .toMap()
    val allImageViewerItems =
        deviceTypeViewerItems + imageAttachmentViewerItems + documentViewerItems.values

    fun openImageViewer(item: ImageViewerItem) {
        val existingIndex = allImageViewerItems.indexOfFirst { it.url == item.url }
        if (existingIndex >= 0) {
            imageViewer = allImageViewerItems to existingIndex
        } else {
            val items = allImageViewerItems + item
            imageViewer = items to items.lastIndex
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    LaunchedEffect(documentDeleteResult) {
        val result = documentDeleteResult ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            if (result == DeleteSubmission.Queued) "Document deletion queued"
            else "Document deleted"
        )
        viewModel.documentDeleteResultShown()
    }

    LaunchedEffect(refreshedMessage) {
        refreshedMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.refreshedMessageShown()
        }
    }

    LaunchedEffect(refreshToastMessage) {
        refreshToastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.refreshToastShown()
        }
    }

    LaunchedEffect(journalMutationState.message) {
        journalMutationState.message?.let {
            showJournalEditor = false
            snackbarHostState.showSnackbar(it)
            viewModel.journalMutationMessageShown()
        }
    }

    LaunchedEffect(deleteResult) {
        if (deleteResult != null) {
            showDeleteConfirmation = false
            viewModel.deleteResultShown()
            onDeleted()
        }
    }

    LaunchedEffect(copiedMessage) {
        copiedMessage?.let {
            snackbarHostState.showSnackbar(it)
            copiedMessage = null
        }
    }

    LaunchedEffect(fileToOpen) {
        val file = fileToOpen ?: return@LaunchedEffect
        runCatching { context.startActivity(fileViewIntent(context, file)) }
            .onFailure { snackbarHostState.showSnackbar("No app found to open ${file.name}") }
        viewModel.fileOpened()
    }

    val onCopyValue: (String, String) -> Unit = { label, value ->
        context
            .getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        copiedMessage = "Copied $label"
    }

    ItemDetailScaffold(
        snackbarHostState = snackbarHostState,
        topBar = {
            ItemDetailTopBar(
                detailAccent = detailAccent,
                onBack = onBack,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncPulseIcon(
                            AppIcons.forEndpointPath(NetBoxRef.DEVICES_ENDPOINT_PATH),
                            tint = detailAccent,
                            syncing = isRefreshing,
                        )
                        Text(
                            device?.name ?: "Device",
                            maxLines = 1,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (bookmark != null) "Remove bookmark" else "Add bookmark"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (bookmark != null) Icons.Default.Bookmark
                                        else Icons.Default.BookmarkBorder,
                                        contentDescription = null,
                                    )
                                },
                                enabled = device != null && !isTogglingBookmark,
                                onClick = {
                                    viewModel.toggleBookmark()
                                    actionMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Print label") },
                                leadingIcon = {
                                    Icon(Icons.Default.Print, contentDescription = null)
                                },
                                enabled = device != null && webUrl != null,
                                onClick = {
                                    val current = device
                                    val url = webUrl
                                    if (current != null && url != null) {
                                        printRequest =
                                            PrintLabelRequest(
                                                objectUrl = url,
                                                labelText =
                                                    current.assetTag?.takeIf { it.isNotBlank() }
                                                        ?: current.name,
                                                longLabelText =
                                                    listOfNotNull(
                                                            current.name,
                                                            current.assetTag,
                                                            current.serial,
                                                        )
                                                        .filter(String::isNotBlank)
                                                        .joinToString("\n"),
                                            )
                                    }
                                    actionMenuExpanded = false
                                },
                            )
                            webUrl?.let { url ->
                                DropdownMenuItem(
                                    text = { Text("Share") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Share, contentDescription = null)
                                    },
                                    onClick = {
                                        context.startActivity(shareIntent(context, url))
                                        actionMenuExpanded = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Open in browser") },
                                    leadingIcon = {
                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                    },
                                    onClick = {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url.toUri())
                                        )
                                        actionMenuExpanded = false
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Sync") },
                                leadingIcon = {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                },
                                enabled = !isRefreshing,
                                onClick = {
                                    viewModel.refresh(showConfirmation = true)
                                    actionMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                enabled = device != null,
                                onClick = {
                                    onEditClick()
                                    actionMenuExpanded = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Add component") },
                                leadingIcon = {
                                    Icon(Icons.Default.Cable, contentDescription = null)
                                },
                                enabled = device != null && !isRefreshing,
                                onClick = {
                                    onAddComponent()
                                    actionMenuExpanded = false
                                },
                            )
                            if (topologyPluginAvailable) {
                                DropdownMenuItem(
                                    text = { Text("Open topology") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Hub, contentDescription = null)
                                    },
                                    enabled = device != null,
                                    onClick = {
                                        onOpenTopology()
                                        actionMenuExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Add journal entry") },
                                leadingIcon = {
                                    Icon(Icons.Default.History, contentDescription = null)
                                },
                                enabled = !isRefreshing,
                                onClick = {
                                    journalEditorEntry = null
                                    showJournalEditor = true
                                    actionMenuExpanded = false
                                },
                            )
                            HorizontalDivider()
                            if (hiddenFieldsForDevice.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("Show hidden fields") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Visibility, contentDescription = null)
                                    },
                                    onClick = {
                                        showHiddenFields = true
                                        actionMenuExpanded = false
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = device != null && !isDeleting,
                                onClick = {
                                    showDeleteConfirmation = true
                                    actionMenuExpanded = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = device
        SuppressiblePullToRefreshBox(
            // Sync has a global progress bar and Android notification; avoid the large circular
            // indicator over the device while that background work is running.
            isRefreshing = false,
            onRefresh = { viewModel.refresh(showConfirmation = true) },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (current == null || !tabsReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (!hasCheckedCache || (current != null && !tabsReady)) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            if (isRefreshing) "Loading…"
                            else "Not cached yet - connect and refresh",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                val tabs = buildList {
                    add(ItemDetailTab("Overview", Icons.Default.Info))
                    visibleRelatedTabs.forEach { tab ->
                        add(
                            ItemDetailTab(
                                label = tab.label,
                                icon = tabIcon(tab),
                                count = relatedCounts[DEVICE_RELATED_TABS.indexOf(tab)],
                            )
                        )
                    }
                    add(ItemDetailTab("Changelog", Icons.Default.Difference, changelog.size))
                }
                ItemDetailTabLayout(
                    tabs = tabs,
                    selectedTab = visibleSelectedTab,
                    onTabSelected = { selectedTab = it },
                    tabCount = tabCount,
                ) {
                    item {
                        val deviceTypePreview = deviceTypeViewerItems.firstOrNull { item ->
                            item.metadata.any { (_, value) -> value == "Front" }
                        }
                        NyetboxCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (deviceTypePreview != null) {
                                        RemoteThumbnail(
                                            imageUrl = deviceTypePreview.url,
                                            contentDescription = deviceTypePreview.title,
                                            modifier =
                                                Modifier.size(64.dp).clickable {
                                                    imageViewer =
                                                        allImageViewerItems to
                                                            allImageViewerItems.indexOf(
                                                                deviceTypePreview
                                                            )
                                                },
                                            contentScale = ContentScale.Fit,
                                        )
                                    } else {
                                        Surface(
                                            color = detailAccent.copy(alpha = 0.18f),
                                            shape =
                                                androidx.compose.foundation.shape
                                                    .RoundedCornerShape(14.dp),
                                            modifier = Modifier.size(64.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    AppIcons.forEndpointPath(
                                                        NetBoxRef.DEVICES_ENDPOINT_PATH
                                                    ),
                                                    contentDescription = null,
                                                    tint = detailAccent,
                                                    modifier = Modifier.size(34.dp),
                                                )
                                            }
                                        }
                                    }
                                    Column(
                                        Modifier.padding(start = 10.dp)
                                            .padding(end = 8.dp)
                                            .weight(1f)
                                    ) {
                                        current.deviceTypeModel?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (isFieldVisible("Status")) {
                                            Spacer(Modifier.height(2.dp))
                                            Box(
                                                modifier =
                                                    Modifier.combinedClickable(
                                                        onClick = {},
                                                        onLongClick = {
                                                            fieldActionLabel = "Status"
                                                        },
                                                    )
                                            ) {
                                                StatusChip(
                                                    label = current.statusLabel,
                                                    value = current.statusValue,
                                                )
                                            }
                                        }
                                    }
                                    current.assetTag?.takeIf(String::isNotBlank)?.let { assetTag ->
                                        AssetTagBadge(
                                            assetTag = assetTag,
                                            modifier =
                                                Modifier.padding(start = 4.dp)
                                                    .combinedClickable(
                                                        onClick = {},
                                                        onLongClick = {
                                                            onCopyValue("Asset tag", assetTag)
                                                        },
                                                    ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (visibleSelectedTab == 0) {
                        item {
                            MediaCarousel(
                                attachments = imageAttachments,
                                documents = documents,
                                onImageClick = { index ->
                                    imageViewer =
                                        allImageViewerItems to (deviceTypeViewerItems.size + index)
                                },
                                onDocumentClick = { document ->
                                    documentViewerItems[document]?.let { item ->
                                        imageViewer =
                                            allImageViewerItems to allImageViewerItems.indexOf(item)
                                    }
                                        ?: document.documentUrl?.let { url ->
                                            viewModel.downloadAttachment(url, document.filename)
                                        }
                                        ?: document.externalUrl?.let { url ->
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, url.toUri())
                                            )
                                        }
                                },
                                onAddMedia = { uri, defaultKind ->
                                    mediaUploadInitialKind = defaultKind
                                    mediaUploadInitialUri = uri
                                    showMediaUpload = true
                                },
                                supportsImageAttachments = true,
                                supportsDocuments = documentPluginAvailable,
                                onAttachmentLongPress = { imageAttachmentAction = it },
                                localFileFor = { document ->
                                    document.documentUrl?.let {
                                        viewModel.localAttachmentFile(it, document.filename)
                                    }
                                },
                                onDeleteDocument =
                                    if (documentPluginAvailable) viewModel::deleteDocument
                                    else null,
                            )
                        }
                        val hasVisibleDetails =
                            listOf(
                                    "site" to current.siteName,
                                    "rack" to current.rackName,
                                    "position" to current.position?.toString(),
                                    "role" to current.roleName,
                                    "manufacturer" to current.manufacturerName,
                                    "serial" to current.serial,
                                    "asset_tag" to current.assetTag,
                                    "primary_ip" to current.primaryIp,
                                    "comments" to current.comments,
                                )
                                .any { (key, value) ->
                                    isFieldVisible(key) && !value.isNullOrBlank()
                                } ||
                                (isFieldVisible("device_type") &&
                                    isFieldVisible("model") &&
                                    !current.deviceTypeModel.isNullOrBlank())
                        if (hasVisibleDetails)
                            item {
                                NyetboxDetailsCard(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(horizontal = 12.dp)) {
                                        current.siteName
                                            ?.takeIf { isFieldVisible("site") && it.isNotBlank() }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Site",
                                                    value = value,
                                                    leadingIcon =
                                                        AppIcons.forEndpointPath("api/dcim/sites/"),
                                                    onClick =
                                                        current.siteId?.let { id ->
                                                            {
                                                                onReferenceClick(
                                                                    "api/dcim/sites/",
                                                                    id,
                                                                    current.name,
                                                                )
                                                            }
                                                        },
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.rackName
                                            ?.takeIf { isFieldVisible("rack") && it.isNotBlank() }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Rack",
                                                    value = value,
                                                    leadingIcon =
                                                        AppIcons.forEndpointPath("api/dcim/racks/"),
                                                    onClick =
                                                        current.rackId?.let { id ->
                                                            {
                                                                onReferenceClick(
                                                                    "api/dcim/racks/",
                                                                    id,
                                                                    current.name,
                                                                )
                                                            }
                                                        },
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.position
                                            ?.toString()
                                            ?.takeIf {
                                                isFieldVisible("position") && it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Position",
                                                    value = value,
                                                    onClick =
                                                        current.rackId?.let { rackId ->
                                                            {
                                                                onRackPositionClick(
                                                                    rackId,
                                                                    current.id,
                                                                    current.name,
                                                                )
                                                            }
                                                        },
                                                    openIcon = Icons.Default.Visibility,
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.roleName
                                            ?.takeIf { isFieldVisible("role") && it.isNotBlank() }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Role",
                                                    value = value,
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.manufacturerName
                                            ?.takeIf {
                                                isFieldVisible("manufacturer") && it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Manufacturer",
                                                    value = value,
                                                    leadingIcon =
                                                        AppIcons.forEndpointPath(
                                                            "api/dcim/manufacturers/"
                                                        ),
                                                    onClick =
                                                        manufacturerId?.let { id ->
                                                            {
                                                                onReferenceClick(
                                                                    "api/dcim/manufacturers/",
                                                                    id,
                                                                    current.name,
                                                                )
                                                            }
                                                        },
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.deviceTypeModel
                                            ?.takeIf {
                                                isFieldVisible("device_type") &&
                                                    isFieldVisible("model") &&
                                                    it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Device type",
                                                    value = value,
                                                    leadingIcon =
                                                        AppIcons.forEndpointPath(
                                                            "api/dcim/device-types/"
                                                        ),
                                                    onClick =
                                                        deviceType?.id?.let { id ->
                                                            { onDeviceTypeClick(id, current.name) }
                                                        },
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.serial
                                            ?.takeIf { isFieldVisible("serial") && it.isNotBlank() }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Serial",
                                                    value = value,
                                                    copyable = true,
                                                    onCopyValue = onCopyValue,
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.assetTag
                                            ?.takeIf {
                                                isFieldVisible("asset_tag") && it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Asset tag",
                                                    value = value,
                                                    copyable = true,
                                                    onCopyValue = onCopyValue,
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.primaryIp
                                            ?.takeIf {
                                                isFieldVisible("primary_ip") && it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailFieldContent(
                                                    label = "Primary IP",
                                                    value = value,
                                                    leadingIcon =
                                                        AppIcons.forEndpointPath(
                                                            "api/ipam/ip-addresses/"
                                                        ),
                                                    copyable = true,
                                                    onCopyValue = onCopyValue,
                                                    onClick =
                                                        current.primaryIpId?.let { id ->
                                                            {
                                                                onReferenceClick(
                                                                    "api/ipam/ip-addresses/",
                                                                    id,
                                                                    current.name,
                                                                )
                                                            }
                                                        },
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                        current.comments
                                            ?.takeIf {
                                                isFieldVisible("comments") && it.isNotBlank()
                                            }
                                            ?.let { value ->
                                                DetailMarkdownContent(
                                                    label = "Comments",
                                                    value = value,
                                                    onFieldLongPress = { fieldActionLabel = it },
                                                    modifier = Modifier.padding(vertical = 8.dp),
                                                )
                                            }
                                    }
                                }
                            }
                        fieldRows(
                            rows = visibleCustomFieldRows,
                            onNavigateToReference = { endpointPath, id ->
                                onReferenceClick(endpointPath, id, current.name)
                            },
                            onRelatedItems = {},
                            onOpenUrl = { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            },
                            netboxBaseUrl = netboxBaseUrl,
                            onDownloadAttachment = viewModel::downloadAttachment,
                            onImageClick = ::openImageViewer,
                            isDownloading = isDownloading,
                            onCopyValue = onCopyValue,
                            onFieldLongPress = { fieldActionLabel = it },
                            onMatterPairingCode = { matterPairingCode = it },
                        )
                        item {
                            Spacer(Modifier.height(24.dp))
                            Text(
                                "Last synced ${DateFormat.getDateTimeInstance().format(Date(current.syncedAt))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (visibleSelectedTab == changelogTabIndex) {
                        if (changelog.isEmpty()) {
                            item {
                                Text(
                                    "No changelog entries found for this device.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontStyle = FontStyle.Italic,
                                    modifier = Modifier.padding(vertical = 16.dp),
                                )
                            }
                        } else {
                            changelog.forEach { change ->
                                item(key = "changelog-${change.id}") {
                                    GenericDetailChangelogRow(
                                        change = change,
                                        onClick = { onChangeDiffClick(change.id) },
                                    )
                                }
                            }
                        }
                    } else {
                        val tab = visibleRelatedTabs[visibleSelectedTab - 1]
                        if (tab.endpointPath == JOURNAL_TAB_ENDPOINT_PATH) {
                            deviceJournalEntriesItems(
                                entries = journalEntries,
                                onEdit = {
                                    journalEditorEntry = it
                                    showJournalEditor = true
                                },
                            )
                        } else if (tab.endpointPath == CONNECTED_DEVICES_TAB_ENDPOINT_PATH) {
                            deviceConnectedDevicesItems(
                                devices = connectedDevices,
                                deviceTypeImages = deviceTypeImages,
                                onDeviceClick = { deviceId ->
                                    onReferenceClick(
                                        NetBoxRef.DEVICES_ENDPOINT_PATH,
                                        deviceId,
                                        current.name,
                                    )
                                },
                            )
                        } else {
                            deviceRelatedObjectsItems(
                                tab = tab,
                                objects = selectedRelatedObjects,
                                interfaceIpAddresses = interfaceIpAddresses,
                                onObjectClick = { objectId ->
                                    onReferenceClick(tab.endpointPath, objectId, current.name)
                                },
                                onIpClick = { ipAddress ->
                                    onReferenceClick(
                                        "api/ipam/ip-addresses/",
                                        ipAddress.id,
                                        current.name,
                                    )
                                },
                                onCopyValue = onCopyValue,
                            )
                        }
                    }
                }
            }
        }
    }

    imageViewer?.let { (items, index) ->
        ImageViewerDialog(
            items = items,
            initialIndex = index,
            onDismiss = { imageViewer = null },
            onMetadataLinkClick = { link ->
                imageViewer = null
                if (link.endpointPath == "api/dcim/device-types/") {
                    onDeviceTypeClick(link.id, link.breadcrumb)
                } else {
                    onReferenceClick(link.endpointPath, link.id, link.breadcrumb)
                }
            },
            onOpenExternally = { item ->
                documentViewerItems.entries
                    .firstOrNull { it.value == item }
                    ?.key
                    ?.let { document ->
                        document.documentUrl?.let { url ->
                            viewModel.downloadAttachment(url, document.filename)
                        }
                            ?: document.externalUrl?.let { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            }
                    }
            },
        )
    }
    matterPairingCode?.let { code ->
        MatterPairingCodeDialog(code = code, onDismiss = { matterPairingCode = null })
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteConfirmation = false },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Delete ${device?.name ?: "device"}?") },
            text = {
                Text(
                    "This removes the device from NetBox. The cached copy will be removed now; " +
                        "if you are offline, the deletion will be uploaded when sync resumes."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = viewModel::delete,
                    enabled = !isDeleting,
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Delete")
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showDeleteConfirmation = false },
                    enabled = !isDeleting,
                ) {
                    Text("Cancel")
                }
            },
        )
    }
    printRequest?.let { request ->
        PrintLabelDialog(request = request, onDismiss = { printRequest = null })
    }
    if (showMediaUpload) {
        MediaUploadDialog(
            endpointPath = "api/dcim/devices/",
            objectId = deviceId,
            onDismiss = {
                showMediaUpload = false
                imageAttachmentToEdit = null
                mediaUploadInitialKind = null
                mediaUploadInitialUri = null
            },
            onUploaded = {
                showMediaUpload = false
                imageAttachmentToEdit = null
                mediaUploadInitialKind = null
                mediaUploadInitialUri = null
                viewModel.refresh(showConfirmation = false)
            },
            initialKind = mediaUploadInitialKind,
            initialUri = mediaUploadInitialUri,
            imageAttachmentId = imageAttachmentToEdit?.id,
            supportsImageAttachments = true,
            supportsDocuments = documentPluginAvailable,
        )
    }
    if (showJournalEditor) {
        JournalEntryEditorDialog(
            entry = journalEditorEntry,
            state = journalMutationState,
            onDismiss = { if (!journalMutationState.isSaving) showJournalEditor = false },
            onSave = { kind, comments ->
                viewModel.saveJournalEntry(journalEditorEntry, kind, comments)
            },
        )
    }
    fieldActionLabel?.let { label ->
        FieldActionDialog(
            fieldLabel = label,
            fieldValue = deviceFieldActionValue(device, customFieldRows, label),
            canEdit = true,
            onCopy = {
                deviceFieldActionValue(device, customFieldRows, label)?.let {
                    onCopyValue(label, it)
                }
                fieldActionLabel = null
            },
            onChangelog = {
                fieldActionLabel = null
                selectedTab = changelogTabIndex
            },
            onEdit = {
                fieldActionLabel = null
                onEditFieldClick(deviceEditFieldKey(label))
            },
            onHide = {
                viewModel.hideField(label)
                fieldActionLabel = null
            },
            onDismiss = { fieldActionLabel = null },
        )
    }
    imageAttachmentAction?.let { attachment ->
        FieldActionDialog(
            fieldLabel = attachment.displayName(),
            fieldValue = attachment.imageUrl,
            canEdit = true,
            editLabel = "Edit image",
            showHide = false,
            onCopy = {
                attachment.imageUrl?.let { onCopyValue(attachment.displayName(), it) }
                imageAttachmentAction = null
            },
            onEdit = {
                imageAttachmentAction = null
                imageAttachmentToEdit = attachment
                mediaUploadInitialKind = MediaUploadKind.ImageAttachment
                showMediaUpload = true
            },
            onHide = { imageAttachmentAction = null },
            onDismiss = { imageAttachmentAction = null },
        )
    }
}

private fun deviceFieldActionValue(
    device: dev.pschmitt.nyetbox.data.db.DeviceEntity?,
    customFieldRows: List<FieldRow>,
    label: String,
): String? {
    val current = device
    if (current == null) return customFieldRows.firstOrNull { it.label == label }?.actionValue()
    return when (label) {
        "Status" -> current.statusLabel ?: current.statusValue
        "Site" -> current.siteName
        "Rack" -> current.rackName
        "Position" -> current.position?.toString()
        "Role" -> current.roleName
        "Manufacturer" -> current.manufacturerName
        "Device type",
        "Model" -> current.deviceTypeModel
        "Serial" -> current.serial
        "Asset tag" -> current.assetTag
        "Primary IP" -> current.primaryIp
        "Comments" -> current.comments
        else -> customFieldRows.firstOrNull { it.label == label }?.actionValue()
    }
}

private fun visibleDeviceCustomFieldRows(
    rows: List<FieldRow>,
    hiddenFieldKeys: Set<String>,
    showHiddenFields: Boolean,
): List<FieldRow> {
    if (showHiddenFields) return rows
    val filtered = rows.filterNot { row ->
        row !is FieldRow.Section &&
            row !is FieldRow.CustomGroup &&
            hiddenFieldPreferenceKey("api/dcim/devices/", row.label) in hiddenFieldKeys
    }
    return buildList {
        val pendingHeaders = mutableListOf<FieldRow>()
        filtered.forEach { row ->
            if (row is FieldRow.Section || row is FieldRow.CustomGroup) {
                pendingHeaders += row
            } else {
                addAll(pendingHeaders)
                pendingHeaders.clear()
                add(row)
            }
        }
    }
}

private fun deviceEditFieldKey(label: String): String =
    when (label) {
        "Device type",
        "Model" -> "device_type"
        "Asset tag" -> "asset_tag"
        "Primary IP" -> "primary_ip"
        else -> label.replace(' ', '_').lowercase()
    }

@Composable
private fun tabIcon(tab: DeviceRelatedTab) =
    if (tab.endpointPath == JOURNAL_TAB_ENDPOINT_PATH) Icons.Default.History
    else if (tab.endpointPath == CONNECTED_DEVICES_TAB_ENDPOINT_PATH) Icons.Default.Hub
    else AppIcons.forEndpointPath(tab.endpointPath)

/**
 * Renders the "Connected devices" tab as virtualized [LazyListScope] items rather than an eagerly
 * composed `forEach` - a device can have dozens of neighbors in a dense topology, and composing
 * every row synchronously on every tab switch (instead of only the ones scrolled into view) is what
 * made switching to this tab feel sluggish.
 */
private fun LazyListScope.deviceConnectedDevicesItems(
    devices: List<DeviceEntity>,
    deviceTypeImages: Map<Int, DeviceTypeEntity>,
    onDeviceClick: (Int) -> Unit,
) {
    if (devices.isEmpty()) {
        item {
            Text(
                "No connected devices in the cached topology.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        return
    }
    items(devices, key = { "connected-${it.id}" }) { connected ->
        val frontImage = connected.deviceTypeId?.let(deviceTypeImages::get)?.frontImageUrl
        NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
            NyetboxListItem(
                leadingContent = {
                    if (frontImage.isNullOrBlank()) {
                        Icon(
                            AppIcons.forEndpointPath(NetBoxRef.DEVICES_ENDPOINT_PATH),
                            contentDescription = null,
                        )
                    } else {
                        RemoteThumbnail(
                            imageUrl = frontImage,
                            contentDescription = connected.name,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                },
                headlineContent = { Text(connected.name) },
                supportingContent = {
                    connected.deviceTypeModel?.let { model ->
                        Text(
                            listOfNotNull(model, connected.statusLabel).joinToString(" · "),
                            maxLines = 1,
                        )
                    }
                },
                modifier = Modifier.clickable { onDeviceClick(connected.id) },
            )
        }
    }
}

/**
 * Renders the Interfaces/Front ports/Rear ports/Power ports/... tabs as virtualized [LazyListScope]
 * items rather than an eagerly composed `forEach`. A single device (a large switch, say) can have
 * 48+ interfaces; composing all of them synchronously every time this tab is selected - rather than
 * only the rows currently scrolled into view - is what made switching to these tabs feel sluggish.
 */
private fun LazyListScope.deviceRelatedObjectsItems(
    tab: DeviceRelatedTab,
    objects: List<dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity>,
    interfaceIpAddresses: Map<Int, List<InterfaceIpAddress>> = emptyMap(),
    onObjectClick: (Int) -> Unit,
    onIpClick: (InterfaceIpAddress) -> Unit,
    onCopyValue: (String, String) -> Unit,
) {
    if (objects.isEmpty()) {
        item {
            Text(
                "No cached ${tab.label.lowercase()} for this device. Refresh while online to load them.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        return
    }
    items(objects, key = { "related-${tab.endpointPath}-${it.id}" }) { objectEntity ->
        val ipAddresses =
            if (tab.endpointPath == INTERFACES_TAB_ENDPOINT_PATH) {
                interfaceIpAddresses[objectEntity.id].orEmpty()
            } else {
                emptyList()
            }
        val macAddresses =
            if (tab.endpointPath == INTERFACES_TAB_ENDPOINT_PATH) {
                objectEntity.interfaceMacAddresses()
            } else {
                emptyList()
            }
        NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
            NyetboxListItem(
                leadingContent = {
                    Icon(AppIcons.forEndpointPath(tab.endpointPath), contentDescription = null)
                },
                headlineContent = { Text(objectEntity.display) },
                supportingContent = {
                    if (ipAddresses.isNotEmpty() || macAddresses.isNotEmpty()) {
                        Column {
                            ipAddresses.forEach { ipAddress ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "IP: ",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        ipAddress.address,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier =
                                            Modifier.weight(1f).clickable { onIpClick(ipAddress) },
                                    )
                                    DetailTrailingActions(
                                        copyLabel = "IP address",
                                        onCopy = { onCopyValue("IP address", ipAddress.address) },
                                        openLabel = "IP address",
                                        onOpen = { onIpClick(ipAddress) },
                                    )
                                }
                            }
                            macAddresses.forEach { macAddress ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "MAC: ",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(macAddress, modifier = Modifier.weight(1f))
                                    DetailTrailingActions(
                                        copyLabel = "MAC address",
                                        onCopy = { onCopyValue("MAC address", macAddress) },
                                    )
                                }
                            }
                        }
                    } else {
                        (if (tab.endpointPath == INTERFACES_TAB_ENDPOINT_PATH) {
                                objectEntity.interfaceSubtitle(emptyList())
                            } else {
                                objectEntity.secondaryLine
                            })
                            ?.let { Text(it) }
                    }
                },
                modifier = Modifier.clickable { onObjectClick(objectEntity.id) },
            )
        }
    }
}

/**
 * Renders the "Journal" tab as virtualized [LazyListScope] items rather than an eagerly composed
 * `forEach`, matching [deviceConnectedDevicesItems]/[deviceRelatedObjectsItems] above - items with
 * many journal entries would otherwise pay the same synchronous-compose-everything cost on every
 * tab switch.
 */
private fun LazyListScope.deviceJournalEntriesItems(
    entries: List<JournalEntryUi>,
    onEdit: (JournalEntryUi) -> Unit,
) {
    if (entries.isEmpty()) {
        item {
            Text(
                "No journal entries found for this device.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        }
        return
    }
    items(entries, key = { "device-journal-${it.id}" }) { entry ->
        val kindPresentation = journalKindPresentation(entry.kind)
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    kindPresentation.option.icon,
                    contentDescription = entry.kindLabel,
                    tint = kindPresentation.foreground,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${entry.kindLabel} · ${formatNetBoxDateTime(entry.created)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onEdit(entry) }) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit journal entry")
                }
            }
            Spacer(Modifier.height(4.dp))
            CommentCard(content = entry.comments, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Device-type stock photos for the overview preview and its front/rear viewer pager. */
private fun deviceTypePhotoItems(
    deviceType: DeviceTypeEntity?,
    manufacturerName: String?,
    manufacturerId: Int?,
): List<ImageViewerItem> {
    val front = deviceType?.frontImageUrl
    val rear = deviceType?.rearImageUrl
    val id = deviceType?.id ?: return emptyList()
    val model = deviceType.model?.takeIf { it.isNotBlank() } ?: "device type"
    return listOfNotNull(
        front
            .takeUnless { it.isNullOrBlank() }
            ?.let {
                ImageViewerItem(
                    url = it,
                    title = "Front of $model",
                    metadata = deviceTypeImageMetadata(deviceType, "Front", manufacturerName),
                    sourceLabel = "Device type image",
                    metadataLinks =
                        deviceTypeImageMetadataLinks(model, manufacturerName, id, manufacturerId),
                )
            },
        rear
            .takeUnless { it.isNullOrBlank() }
            ?.let {
                ImageViewerItem(
                    url = it,
                    title = "Rear of $model",
                    metadata = deviceTypeImageMetadata(deviceType, "Rear", manufacturerName),
                    sourceLabel = "Device type image",
                    metadataLinks =
                        deviceTypeImageMetadataLinks(model, manufacturerName, id, manufacturerId),
                )
            },
    )
}

private fun deviceTypeImageMetadata(
    deviceType: DeviceTypeEntity,
    view: String,
    manufacturerName: String?,
): List<Pair<String, String>> = buildList {
    manufacturerName?.takeIf { it.isNotBlank() }?.let { add("Manufacturer" to it) }
    deviceType.model?.takeIf { it.isNotBlank() }?.let { add("Device type" to it) }
    add("View" to view)
}

private fun deviceTypeImageMetadataLinks(
    model: String,
    manufacturerName: String?,
    deviceTypeId: Int,
    manufacturerId: Int?,
): List<ImageViewerMetadataLink> = buildList {
    manufacturerName
        ?.takeIf { it.isNotBlank() }
        ?.let { name ->
            manufacturerId?.let { id ->
                add(
                    ImageViewerMetadataLink(
                        label = "Manufacturer",
                        endpointPath = "api/dcim/manufacturers/",
                        id = id,
                        breadcrumb = name,
                    )
                )
            }
        }
    add(
        ImageViewerMetadataLink(
            label = "Device type",
            endpointPath = "api/dcim/device-types/",
            id = deviceTypeId,
            breadcrumb = model,
        )
    )
}

/** Pull the useful network identity into interface list subtitles when NetBox includes it. */
private fun dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity.interfaceMacAddresses(): List<String> {
    val objectJson =
        runCatching { interfaceJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return emptyList()
    return buildList {
            listOf("mac_address", "primary_mac_address").forEach { key ->
                objectJson[key]?.displayValue()?.let(::add)
            }
            (objectJson["mac_addresses"] as? JsonArray).orEmpty().forEach { element ->
                element.displayValue()?.let(::add)
            }
        }
        .distinct()
}

private fun dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity.interfaceSubtitle(
    cachedIpAddresses: List<String>
): String? {
    val objectJson =
        runCatching { interfaceJson.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return secondaryLine
    val addresses =
        buildList {
                val ipList = objectJson["ip_addresses"] as? JsonArray
                ipList.orEmpty().forEach { element -> element.displayValue()?.let(::add) }
                listOf("primary_ip4", "primary_ip6", "ip_address").forEach { key ->
                    objectJson[key]?.displayValue()?.let(::add)
                }
                addAll(cachedIpAddresses)
            }
            .distinct()
    val macAddresses = interfaceMacAddresses()
    val networkParts = buildList {
        if (addresses.isNotEmpty()) add("IP: ${addresses.joinToString(", ")}")
        if (macAddresses.isNotEmpty()) add("MAC: ${macAddresses.joinToString(", ")}")
    }
    return networkParts.joinToString(" · ").takeIf { it.isNotBlank() } ?: secondaryLine
}

private fun JsonElement.displayValue(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject ->
            listOf("address", "display", "cidr", "value")
                .asSequence()
                .mapNotNull { key -> this[key]?.displayValue() }
                .firstOrNull()
        else -> null
    }

private fun LazyListScope.detailField(
    label: String,
    value: String?,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    copyable: Boolean = false,
    onCopyValue: (label: String, value: String) -> Unit = { _, _ -> },
    onClick: (() -> Unit)? = null,
    openIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Filled.OpenInNew,
    onFieldLongPress: (label: String) -> Unit = {},
) {
    if (value.isNullOrBlank()) return
    item {
        NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
            DetailFieldContent(
                label = label,
                value = value,
                leadingIcon = leadingIcon,
                copyable = copyable,
                onCopyValue = onCopyValue,
                onClick = onClick,
                openIcon = openIcon,
                onFieldLongPress = onFieldLongPress,
            )
        }
    }
}

@Composable
private fun DetailFieldContent(
    label: String,
    value: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    copyable: Boolean = false,
    onCopyValue: (label: String, value: String) -> Unit = { _, _ -> },
    onClick: (() -> Unit)? = null,
    openIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.AutoMirrored.Filled.OpenInNew,
    onFieldLongPress: (label: String) -> Unit = {},
    modifier: Modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
) {
    Column(
        modifier =
            modifier.combinedClickable(
                onClick = { onClick?.invoke() },
                onLongClick = { onFieldLongPress(label) },
            )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            leadingIcon?.let { icon ->
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            DetailTrailingActions(
                copyLabel = label.takeIf { copyable },
                onCopy = { onCopyValue(label, value) }.takeIf { copyable },
                openLabel = label.takeIf { onClick != null },
                onOpen = onClick,
                openIcon = openIcon,
            )
        }
    }
}

/** NetBox's "comments" field supports Markdown - rendered, not shown as literal text. */
private fun LazyListScope.detailMarkdownField(
    label: String,
    value: String?,
    onFieldLongPress: (label: String) -> Unit = {},
) {
    if (value.isNullOrBlank()) return
    item {
        NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
            DetailMarkdownContent(label, value, onFieldLongPress)
        }
    }
}

@Composable
private fun DetailMarkdownContent(
    label: String,
    value: String,
    onFieldLongPress: (label: String) -> Unit = {},
    modifier: Modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
) {
    Column(
        modifier =
            modifier.combinedClickable(
                onClick = {},
                onLongClick = { onFieldLongPress(label) },
            )
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CollapsibleCommentCard(content = value, modifier = Modifier.fillMaxWidth())
    }
}
