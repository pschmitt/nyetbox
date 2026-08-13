package dev.pschmitt.nyetbox.ui.generic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Difference
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.db.ImageAttachmentEntity
import dev.pschmitt.nyetbox.data.repository.CachedDocument
import dev.pschmitt.nyetbox.data.repository.DeleteSubmission
import dev.pschmitt.nyetbox.data.repository.RackFace
import dev.pschmitt.nyetbox.data.repository.hiddenFieldObjectKey
import dev.pschmitt.nyetbox.ui.common.CommentCard
import dev.pschmitt.nyetbox.ui.common.FieldActionDialog
import dev.pschmitt.nyetbox.ui.common.ImageViewerDialog
import dev.pschmitt.nyetbox.ui.common.ImageViewerItem
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
import dev.pschmitt.nyetbox.ui.common.PrintLabelDialog
import dev.pschmitt.nyetbox.ui.common.PrintLabelRequest
import dev.pschmitt.nyetbox.ui.common.SuppressiblePullToRefreshBox
import dev.pschmitt.nyetbox.ui.common.SvgDiagramView
import dev.pschmitt.nyetbox.ui.common.SyncPulseIcon
import dev.pschmitt.nyetbox.ui.common.detailAccentFor
import dev.pschmitt.nyetbox.ui.common.displayName
import dev.pschmitt.nyetbox.ui.common.fileViewIntent
import dev.pschmitt.nyetbox.ui.common.formatNetBoxDateTime
import dev.pschmitt.nyetbox.ui.common.journalKindPresentation
import dev.pschmitt.nyetbox.ui.common.objectTypeLabel
import dev.pschmitt.nyetbox.ui.common.shareIntent
import dev.pschmitt.nyetbox.ui.common.toDocumentViewerItem
import dev.pschmitt.nyetbox.ui.common.toImageViewerItem
import dev.pschmitt.nyetbox.ui.directory.AppIcons

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GenericDetailScreen(
    highlightDeviceId: Int? = null,
    onBack: () -> Unit,
    onNavigateToReference: (endpointPath: String, id: Int, breadcrumb: String?) -> Unit,
    onCreateLinkedItem:
        (
            fieldKey: String,
            endpointPath: String,
            label: String,
            reopenFocusedEditor: Boolean,
        ) -> Unit,
    onAddComponent: () -> Unit,
    onChangeDiffClick: (changeId: Int) -> Unit,
    viewModel: GenericDetailViewModel = hiltViewModel(),
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val assetTag by viewModel.assetTag.collectAsStateWithLifecycle()
    val hasCheckedCache by viewModel.hasCheckedCache.collectAsStateWithLifecycle()
    val journalTabReady by viewModel.journalTabReady.collectAsStateWithLifecycle()
    val fields by viewModel.fields.collectAsStateWithLifecycle()
    val editableFields by viewModel.editableFields.collectAsStateWithLifecycle()
    val referenceOptions by viewModel.referenceOptions.collectAsStateWithLifecycle()
    val choiceOptions by viewModel.choiceOptions.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()
    val deleteResult by viewModel.deleteResult.collectAsStateWithLifecycle()
    val bookmark by viewModel.bookmark.collectAsStateWithLifecycle()
    val isTogglingBookmark by viewModel.isTogglingBookmark.collectAsStateWithLifecycle()
    val isEditing by viewModel.isEditing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val refreshedMessage by viewModel.refreshedMessage.collectAsStateWithLifecycle()
    val refreshToastMessage by viewModel.refreshToastMessage.collectAsStateWithLifecycle()
    val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
    val netboxBaseUrl by viewModel.netboxBaseUrl.collectAsStateWithLifecycle()
    val isDownloading by viewModel.isDownloading.collectAsStateWithLifecycle()
    val fileToOpen by viewModel.fileToOpen.collectAsStateWithLifecycle()
    val journalEntries by viewModel.journalEntries.collectAsStateWithLifecycle()
    val changelog by viewModel.changelog.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val documentDeleteResult by viewModel.documentDeleteResult.collectAsStateWithLifecycle()
    val documentPluginAvailable by viewModel.documentPluginAvailable.collectAsStateWithLifecycle()
    val supportsImageAttachments by viewModel.supportsImageAttachments.collectAsStateWithLifecycle()
    val supportsDocuments by viewModel.supportsDocuments.collectAsStateWithLifecycle()
    val imageAttachments by viewModel.imageAttachments.collectAsStateWithLifecycle()
    val journalMutationState by viewModel.journalMutationState.collectAsStateWithLifecycle()
    val hiddenFieldKeys by viewModel.hiddenFieldKeys.collectAsStateWithLifecycle()
    val objectTypeAccent by viewModel.objectTypeAccent.collectAsStateWithLifecycle()
    val frontElevation by viewModel.frontElevation.collectAsStateWithLifecycle()
    val rearElevation by viewModel.rearElevation.collectAsStateWithLifecycle()
    val rackDevicePreviews by viewModel.rackDevicePreviews.collectAsStateWithLifecycle()
    val traceSegments by viewModel.traceSegments.collectAsStateWithLifecycle()
    val rackElevationSvg by viewModel.rackElevationSvg.collectAsStateWithLifecycle()
    val traceSvg by viewModel.traceSvg.collectAsStateWithLifecycle()
    val relatedTarget by viewModel.relatedTarget.collectAsStateWithLifecycle()
    val relatedObjects by viewModel.relatedObjects.collectAsStateWithLifecycle()
    val relatedPreviewUrls by viewModel.relatedPreviewUrls.collectAsStateWithLifecycle()
    val isRelatedRefreshing by viewModel.isRelatedRefreshing.collectAsStateWithLifecycle()
    val editDraftValues by viewModel.editDraftValues.collectAsStateWithLifecycle()
    val linkedCreateResult by viewModel.linkedCreateResult.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var copiedMessage by remember { mutableStateOf<String?>(null) }
    var printRequest by remember { mutableStateOf<PrintLabelRequest?>(null) }
    var showMediaUpload by remember { mutableStateOf(false) }
    var mediaUploadInitialKind by remember { mutableStateOf<MediaUploadKind?>(null) }
    var mediaUploadInitialUri by remember { mutableStateOf<Uri?>(null) }
    var imageAttachmentAction by remember { mutableStateOf<ImageAttachmentEntity?>(null) }
    var imageAttachmentToEdit by remember { mutableStateOf<ImageAttachmentEntity?>(null) }
    var showJournalEditor by remember { mutableStateOf(false) }
    var journalEditorEntry by remember { mutableStateOf<JournalEntryUi?>(null) }
    var imageViewer by remember { mutableStateOf<Pair<List<ImageViewerItem>, Int>?>(null) }
    var matterPairingCode by remember { mutableStateOf<String?>(null) }
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showHiddenFields by remember { mutableStateOf(false) }
    var showRackElevationSvg by remember { mutableStateOf(false) }
    var showTraceSvg by remember { mutableStateOf(false) }
    var fieldActionLabel by remember { mutableStateOf<String?>(null) }
    var pendingEdits by remember { mutableStateOf<Map<String, Pair<EditFieldKind, String>>?>(null) }
    var pendingEditFieldKey by remember { mutableStateOf<String?>(null) }
    var focusedEditFieldKey by remember { mutableStateOf<String?>(null) }
    var focusedEditValue by remember { mutableStateOf("") }
    var routeFocusHandled by remember { mutableStateOf(false) }
    var automaticEditStarted by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var openChangelogRequested by remember { mutableStateOf(false) }
    val hiddenObjectKey = hiddenFieldObjectKey(viewModel.route.endpointPath)
    val isRouteFocusedEditor = viewModel.route.focusFieldKey != null
    val hiddenFieldsForObject = hiddenFieldKeys.filter { it.startsWith("$hiddenObjectKey/") }
    val visibleFields =
        visibleFieldRows(
            fields,
            viewModel.route.endpointPath,
            hiddenFieldKeys,
            showHiddenFields,
        )
    val statusField =
        visibleFields.firstOrNull { it.label.equals("Status", ignoreCase = true) }
            as? FieldRow.PlainText
    val visibleOverviewFields = visibleFields.filterNot { it == statusField }
    val deviceTypePhotoRows =
        if (viewModel.isDeviceType) visibleOverviewFields.filterIsInstance<FieldRow.Image>()
        else emptyList()
    val detailOverviewFields = visibleOverviewFields.filterNot {
        viewModel.isDeviceType && it is FieldRow.Image
    }
    val imageAttachmentViewerItems = imageAttachments.map { it.toImageViewerItem() }
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
    val deviceTypePhotoViewerItems = deviceTypePhotoItems(deviceTypePhotoRows, title)
    val mediaViewerItems = imageAttachmentViewerItems + documentViewerItems.values
    val allMediaViewerItems = deviceTypePhotoViewerItems + mediaViewerItems
    val deviceTypePreviewIndex =
        deviceTypePhotoRows
            .indexOfFirst { it.label.contains("front", ignoreCase = true) }
            .takeIf { it >= 0 }
            ?.coerceAtMost(deviceTypePhotoViewerItems.lastIndex)
            ?: deviceTypePhotoViewerItems.indices.firstOrNull()
            ?: -1
    val deviceTypePreview = deviceTypePhotoViewerItems.getOrNull(deviceTypePreviewIndex)
    val deviceTypePreviewLabel = deviceTypePhotoRows.getOrNull(deviceTypePreviewIndex)?.label
    val openDeviceTypePreview = {
        if (deviceTypePreviewIndex >= 0) {
            imageViewer = allMediaViewerItems to deviceTypePreviewIndex
        }
    }
    val onDeviceTypePreviewLongPress = {
        if (deviceTypePreviewLabel != null) {
            fieldActionLabel = deviceTypePreviewLabel
        } else if (viewModel.isDeviceType) {
            // The identity card's placeholder is also the entry point for the first photo. The
            // upload dialog defaults to the front face and still lets the user switch to rear.
            mediaUploadInitialKind = MediaUploadKind.DeviceTypeFront
            showMediaUpload = true
        }
    }
    val modelLabel = endpointModelLabel(viewModel.route.endpointPath)
    val detailAccent =
        MaterialTheme.colorScheme.detailAccentFor(viewModel.route.endpointPath, objectTypeAccent)
    val focusedEditField = focusedEditFieldKey?.let { key ->
        editableFields.firstOrNull { it.key == key }
    }
    LaunchedEffect(isEditing, editableFields) {
        if (isEditing) viewModel.initializeEditDraftIfNeeded()
    }
    LaunchedEffect(linkedCreateResult, editableFields) {
        val result = linkedCreateResult ?: return@LaunchedEffect
        val field = editableFields.firstOrNull { it.key == result.fieldKey }
        if (field != null && field.referenceEndpointPath == result.endpointPath) {
            val nextValue =
                if (field.kind == EditFieldKind.MULTI_REFERENCE) {
                    selectedValuesToJson(
                        (selectedValuesFromJson(editDraftValues[field.key] ?: field.value) +
                                result.id.toString())
                            .distinct()
                    )
                } else {
                    result.id.toString()
                }
            viewModel.addReferenceOption(
                field.key,
                EditOption(result.id.toString(), result.display),
            )
            if (result.reopenFocusedEditor) {
                focusedEditFieldKey = field.key
                focusedEditValue = nextValue
                viewModel.startFieldEditing(field.key)
            } else {
                viewModel.setEditDraftValue(field.key, nextValue)
            }
        }
        viewModel.consumeLinkedCreateResult()
    }
    LaunchedEffect(viewModel.route.focusFieldKey, editableFields) {
        val fieldKey = viewModel.route.focusFieldKey ?: return@LaunchedEffect
        if (
            shouldLaunchRouteFocusedEditor(
                routeFocusHandled = routeFocusHandled,
                focusFieldKey = fieldKey,
                focusedEditFieldKey = focusedEditFieldKey,
                hasPendingEdits = pendingEdits != null,
            )
        ) {
            editableFields
                .firstOrNull { it.key == fieldKey }
                ?.let { field ->
                    routeFocusHandled = true
                    focusedEditFieldKey = field.key
                    focusedEditValue = field.value
                    viewModel.startFieldEditing(field.key)
                }
        }
    }
    LaunchedEffect(viewModel.route.startInEdit, title, editableFields) {
        if (
            viewModel.route.startInEdit &&
                !automaticEditStarted &&
                title != null &&
                editableFields.isNotEmpty()
        ) {
            automaticEditStarted = true
            viewModel.startEditing()
        }
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        // While editing, the bold inline banner in EditForm below is the primary signal - a
        // Snackbar on top of that (and, on smaller screens, behind the open keyboard) is both
        // redundant and easy to miss, and dismissing it here would also clear the banner before
        // the user has a chance to read it. Outside of editing (e.g. a refresh failure), the
        // Snackbar is still the only signal.
        if (!isEditing) {
            snackbarHostState.showSnackbar(message)
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
            onBack()
        }
    }

    LaunchedEffect(copiedMessage) {
        copiedMessage?.let {
            snackbarHostState.showSnackbar(it)
            copiedMessage = null
        }
    }

    val onCopyValue: (String, String) -> Unit = { label, value ->
        context
            .getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText(label, value))
        copiedMessage = "Copied $label"
    }

    LaunchedEffect(fileToOpen) {
        val file = fileToOpen ?: return@LaunchedEffect
        runCatching { context.startActivity(fileViewIntent(context, file)) }
            .onFailure { snackbarHostState.showSnackbar("No app found to open ${file.name}") }
        viewModel.fileOpened()
    }

    ItemDetailScaffold(
        snackbarHostState = snackbarHostState,
        topBar = {
            ItemDetailTopBar(
                detailAccent = detailAccent,
                onBack = onBack,
                isEditing = isEditing,
                onCancelEditing = viewModel::cancelEditing,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncPulseIcon(
                            AppIcons.forEndpointPath(viewModel.route.endpointPath),
                            tint = detailAccent,
                            syncing = isRefreshing,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(
                                title ?: objectTypeLabel(modelLabel, viewModel.route.endpointPath),
                                maxLines = 1,
                            )
                            if (viewModel.route.breadcrumb != null) {
                                Text(
                                    "from ${viewModel.route.breadcrumb}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (isEditing) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    val kindByKey = editableFields.associateBy { it.key }
                                    // Only the fields actually changed from their original value -
                                    // editDraftValues holds the *entire* form's current state (it's
                                    // seeded from every editable field when entering edit mode),
                                    // so PATCHing all of it back unconditionally resends untouched
                                    // fields too. Beyond the unnecessary noise in NetBox's own
                                    // change log, this can outright break the save: a field NetBox
                                    // computes itself (e.g. an absolute media URL) may reject being
                                    // resent as-is even when nothing about it changed.
                                    val edits =
                                        editDraftValues
                                            .mapNotNull { (key, value) ->
                                                kindByKey[key]?.let { field ->
                                                    if (value != field.value)
                                                        key to (field.kind to value)
                                                    else null
                                                }
                                            }
                                            .toMap()
                                    if (edits.isEmpty()) {
                                        viewModel.save(emptyMap())
                                    } else {
                                        pendingEdits = edits
                                        pendingEditFieldKey = null
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save")
                            }
                        }
                    } else {
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
                                            if (bookmark != null) "Remove bookmark"
                                            else "Add bookmark"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (bookmark != null) Icons.Default.Bookmark
                                            else Icons.Default.BookmarkBorder,
                                            contentDescription = null,
                                        )
                                    },
                                    enabled = title != null && !isTogglingBookmark,
                                    onClick = {
                                        viewModel.toggleBookmark()
                                        actionMenuExpanded = false
                                    },
                                )
                                if (viewModel.isPrintableDevice) {
                                    DropdownMenuItem(
                                        text = { Text("Print label") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Print, contentDescription = null)
                                        },
                                        enabled = webUrl != null,
                                        onClick = {
                                            webUrl?.let { url ->
                                                printRequest =
                                                    PrintLabelRequest(
                                                        objectUrl = url,
                                                        labelText = title.orEmpty(),
                                                        longLabelText =
                                                            buildList {
                                                                    title
                                                                        ?.takeIf { it.isNotBlank() }
                                                                        ?.let(::add)
                                                                    fields
                                                                        .filterIsInstance<
                                                                            FieldRow.PlainText
                                                                        >()
                                                                        .firstOrNull {
                                                                            it.label.equals(
                                                                                "Asset tag",
                                                                                ignoreCase = true,
                                                                            )
                                                                        }
                                                                        ?.value
                                                                        ?.takeIf { it.isNotBlank() }
                                                                        ?.let(::add)
                                                                    fields
                                                                        .filterIsInstance<
                                                                            FieldRow.PlainText
                                                                        >()
                                                                        .firstOrNull {
                                                                            it.label.equals(
                                                                                "Serial",
                                                                                ignoreCase = true,
                                                                            )
                                                                        }
                                                                        ?.value
                                                                        ?.takeIf { it.isNotBlank() }
                                                                        ?.let(::add)
                                                                }
                                                                .joinToString("\n")
                                                                .takeIf { it.isNotBlank() },
                                                    )
                                            }
                                            actionMenuExpanded = false
                                        },
                                    )
                                }
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
                                            Icon(
                                                Icons.Default.OpenInBrowser,
                                                contentDescription = null,
                                            )
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
                                if (editableFields.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Edit") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        },
                                        onClick = {
                                            viewModel.startEditing()
                                            actionMenuExpanded = false
                                        },
                                    )
                                }
                                if (viewModel.isPrintableDevice) {
                                    DropdownMenuItem(
                                        text = { Text("Add component") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Cable, contentDescription = null)
                                        },
                                        enabled = !isRefreshing,
                                        onClick = {
                                            onAddComponent()
                                            actionMenuExpanded = false
                                        },
                                    )
                                }
                                if (
                                    supportsImageAttachments ||
                                        (documentPluginAvailable && supportsDocuments)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Upload media") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.UploadFile,
                                                contentDescription = null,
                                            )
                                        },
                                        enabled = !isRefreshing,
                                        onClick = {
                                            showMediaUpload = true
                                            mediaUploadInitialKind =
                                                if (supportsImageAttachments) null
                                                else MediaUploadKind.Document
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
                                if (hiddenFieldsForObject.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("Show hidden fields") },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Visibility,
                                                contentDescription = null,
                                            )
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
                                    enabled = title != null && !isDeleting,
                                    onClick = {
                                        showDeleteConfirmation = true
                                        actionMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (isEditing) {
            EditForm(
                fields = editableFields,
                values = editDraftValues,
                referenceOptions = referenceOptions,
                choiceOptions = choiceOptions,
                onValueChange = viewModel::setEditDraftValue,
                errorMessage = errorMessage,
                onCreateLinkedItem = { field ->
                    field.referenceEndpointPath?.let { endpoint ->
                        onCreateLinkedItem(field.key, endpoint, field.label, false)
                    }
                },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        } else {
            SuppressiblePullToRefreshBox(
                // Sync has a global progress bar and Android notification; avoid the large
                // circular indicator over the item while that background work is running.
                isRefreshing = false,
                onRefresh = { viewModel.refresh(showConfirmation = true) },
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                when {
                    title == null || !journalTabReady ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (!hasCheckedCache || (title != null && !journalTabReady)) {
                                CircularProgressIndicator()
                            } else {
                                Text(
                                    if (isRefreshing) "Loading…"
                                    else "Not cached yet - connect and refresh",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    else -> {
                        val hasJournal = journalEntries.isNotEmpty()
                        val tabCount =
                            1 +
                                (if (viewModel.isRack) 1 else 0) +
                                (if (viewModel.isCable) 1 else 0) +
                                (if (hasJournal) 1 else 0) +
                                1
                        val visibleSelectedTab = selectedTab.coerceIn(0, tabCount - 1)
                        LaunchedEffect(
                            viewModel.isRack,
                            viewModel.isCable,
                            hasJournal,
                            changelog.size,
                        ) {
                            selectedTab = visibleSelectedTab
                        }
                        LaunchedEffect(highlightDeviceId, viewModel.isRack) {
                            if (highlightDeviceId != null && viewModel.isRack) {
                                selectedTab = 1
                                // Already the default on a fresh navigation - explicit safeguard
                                // so the highlight (only drawn in the non-SVG view) is visible.
                                showRackElevationSvg = false
                            }
                        }
                        val tabs = buildList {
                            add(ItemDetailTab("Overview", Icons.Default.Info))
                            if (viewModel.isRack) {
                                add(ItemDetailTab("Elevation", Icons.Default.Storage))
                            }
                            if (viewModel.isCable) {
                                add(
                                    ItemDetailTab(
                                        "Trace",
                                        Icons.Default.Route,
                                        traceSegments.size,
                                    )
                                )
                            }
                            if (hasJournal) {
                                add(
                                    ItemDetailTab(
                                        "Journal",
                                        Icons.Default.History,
                                        journalEntries.size,
                                    )
                                )
                            }
                            add(
                                ItemDetailTab(
                                    "Changelog",
                                    Icons.Default.Difference,
                                    changelog.size,
                                )
                            )
                        }
                        val changelogTabIndex = tabs.lastIndex
                        LaunchedEffect(openChangelogRequested, changelogTabIndex) {
                            if (openChangelogRequested) {
                                selectedTab = changelogTabIndex
                                openChangelogRequested = false
                            }
                        }
                        val journalTabIndex = tabs.indexOfFirst { it.label == "Journal" }
                        val traceTabIndex = tabs.indexOfFirst { it.label == "Trace" }
                        ItemDetailTabLayout(
                            tabs = tabs,
                            selectedTab = visibleSelectedTab,
                            onTabSelected = { selectedTab = it },
                            tabCount = tabCount,
                        ) {
                            item {
                                GenericDetailIdentityCard(
                                    id = viewModel.route.id,
                                    endpointPath = viewModel.route.endpointPath,
                                    title = title,
                                    preview = deviceTypePreview,
                                    onPreviewClick = openDeviceTypePreview,
                                    onPreviewLongPress = onDeviceTypePreviewLongPress,
                                    statusField = statusField,
                                    detailAccent = detailAccent,
                                    onStatusLongPress = { fieldActionLabel = statusField?.label },
                                    assetTag = assetTag,
                                    onAssetTagLongPress = { onCopyValue("Asset tag", it) },
                                )
                            }
                            if (visibleSelectedTab == 0) {
                                item { Spacer(Modifier.height(8.dp)) }
                                val showDocuments = documentPluginAvailable && supportsDocuments
                                if (supportsImageAttachments || showDocuments)
                                    item {
                                        MediaCarousel(
                                            attachments =
                                                if (supportsImageAttachments) imageAttachments
                                                else emptyList(),
                                            documents =
                                                if (showDocuments) documents else emptyList(),
                                            onImageClick = { index ->
                                                imageViewer = mediaViewerItems to index
                                            },
                                            onDocumentClick = { document ->
                                                documentViewerItems[document]?.let { item ->
                                                    imageViewer =
                                                        mediaViewerItems to
                                                            mediaViewerItems.indexOf(item)
                                                }
                                                    ?: document.documentUrl?.let { url ->
                                                        viewModel.downloadAttachment(
                                                            url,
                                                            document.filename,
                                                        )
                                                    }
                                                    ?: document.externalUrl?.let { url ->
                                                        context.startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                url.toUri(),
                                                            )
                                                        )
                                                    }
                                            },
                                            onAddMedia = { uri, defaultKind ->
                                                mediaUploadInitialKind = defaultKind
                                                mediaUploadInitialUri = uri
                                                showMediaUpload = true
                                            },
                                            supportsImageAttachments = supportsImageAttachments,
                                            supportsDocuments = showDocuments,
                                            onAttachmentLongPress = { imageAttachmentAction = it },
                                            localFileFor = { document ->
                                                document.documentUrl?.let {
                                                    viewModel.localAttachmentFile(
                                                        it,
                                                        document.filename,
                                                    )
                                                }
                                            },
                                            onDeleteDocument =
                                                if (showDocuments) viewModel::deleteDocument
                                                else null,
                                        )
                                    }
                                fieldRows(
                                    detailOverviewFields,
                                    onNavigateToReference = { endpointPath, id ->
                                        onNavigateToReference(
                                            endpointPath,
                                            id,
                                            title ?: modelLabel,
                                        )
                                    },
                                    viewModel::showRelatedItems,
                                    onOpenUrl = { url ->
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, url.toUri())
                                        )
                                    },
                                    netboxBaseUrl = netboxBaseUrl,
                                    onDownloadAttachment = viewModel::downloadAttachment,
                                    onImageClick = { imageViewer = listOf(it) to 0 },
                                    isDownloading = isDownloading,
                                    onCopyValue = onCopyValue,
                                    onFieldLongPress = { fieldActionLabel = it },
                                    onMatterPairingCode = { matterPairingCode = it },
                                )
                            } else if (viewModel.isRack && visibleSelectedTab == 1) {
                                item { Spacer(Modifier.height(8.dp)) }
                                item {
                                    DiagramViewToggle(
                                        showSvg = showRackElevationSvg,
                                        onToggle = { toSvg ->
                                            showRackElevationSvg = toSvg
                                            if (toSvg) {
                                                viewModel.loadRackElevationSvg(RackFace.FRONT)
                                                viewModel.loadRackElevationSvg(RackFace.REAR)
                                            }
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                                if (showRackElevationSvg) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(
                                                "Front",
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                            )
                                            SvgDiagramView(
                                                svg = rackElevationSvg[RackFace.FRONT],
                                                baseUrl = netboxBaseUrl.orEmpty(),
                                                onNavigate = { endpointPath, id ->
                                                    onNavigateToReference(
                                                        endpointPath,
                                                        id,
                                                        title ?: modelLabel,
                                                    )
                                                },
                                            )
                                            Text(
                                                "Rear",
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                            )
                                            SvgDiagramView(
                                                svg = rackElevationSvg[RackFace.REAR],
                                                baseUrl = netboxBaseUrl.orEmpty(),
                                                onNavigate = { endpointPath, id ->
                                                    onNavigateToReference(
                                                        endpointPath,
                                                        id,
                                                        title ?: modelLabel,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    item {
                                        RackElevationOverview(
                                            front = frontElevation,
                                            rear = rearElevation,
                                            previews = rackDevicePreviews,
                                            highlightDeviceId = highlightDeviceId,
                                            onDeviceClick = { id ->
                                                onNavigateToReference(
                                                    "api/dcim/devices/",
                                                    id,
                                                    title ?: modelLabel,
                                                )
                                            },
                                        )
                                    }
                                }
                            } else if (visibleSelectedTab == traceTabIndex) {
                                item { Spacer(Modifier.height(8.dp)) }
                                item {
                                    DiagramViewToggle(
                                        showSvg = showTraceSvg,
                                        onToggle = { toSvg ->
                                            showTraceSvg = toSvg
                                            if (toSvg) viewModel.loadCableTraceSvg()
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                                if (showTraceSvg) {
                                    item {
                                        SvgDiagramView(
                                            svg = traceSvg,
                                            baseUrl = netboxBaseUrl.orEmpty(),
                                            onNavigate = { endpointPath, id ->
                                                onNavigateToReference(
                                                    endpointPath,
                                                    id,
                                                    title ?: modelLabel,
                                                )
                                            },
                                        )
                                    }
                                } else if (traceSegments.isEmpty()) {
                                    item {
                                        Text(
                                            "No cable path to trace.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                            modifier = Modifier.padding(vertical = 16.dp),
                                        )
                                    }
                                } else {
                                    items(traceSegments, key = { it.index }) { segment ->
                                        CableTraceSegmentCard(
                                            segment = segment,
                                            onNavigate = { endpointPath, id ->
                                                onNavigateToReference(
                                                    endpointPath,
                                                    id,
                                                    title ?: modelLabel,
                                                )
                                            },
                                        )
                                    }
                                }
                            } else if (visibleSelectedTab == journalTabIndex) {
                                if (journalEntries.isEmpty()) {
                                    item {
                                        Text(
                                            "No journal entries found for this item.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                            modifier = Modifier.padding(vertical = 16.dp),
                                        )
                                    }
                                } else {
                                    items(journalEntries, key = { it.id }) { entry ->
                                        JournalEntryItem(
                                            entry,
                                            onEdit = {
                                                journalEditorEntry = entry
                                                showJournalEditor = true
                                            },
                                        )
                                    }
                                }
                            } else {
                                if (changelog.isEmpty()) {
                                    item {
                                        Text(
                                            "No changelog entries found for this item.",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontStyle = FontStyle.Italic,
                                            modifier = Modifier.padding(vertical = 16.dp),
                                        )
                                    }
                                } else {
                                    items(changelog, key = { it.id }) { change ->
                                        GenericDetailChangelogRow(
                                            change = change,
                                            onClick = { onChangeDiffClick(change.id) },
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
            title = { Text("Delete ${title ?: "item"}?") },
            text = {
                Text(
                    "This removes the item from NetBox. The cached copy will be removed now; " +
                        "if you are offline, the deletion will be uploaded when sync resumes."
                )
            },
            confirmButton = {
                TextButton(
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
                TextButton(
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
            endpointPath = viewModel.route.endpointPath,
            objectId = viewModel.route.id,
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
            supportsImageAttachments = supportsImageAttachments,
            supportsDocuments = documentPluginAvailable && supportsDocuments,
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
    imageViewer?.let { (items, index) ->
        ImageViewerDialog(
            items = items,
            initialIndex = index,
            onDismiss = { imageViewer = null },
            onEdit = { item ->
                val label = item.metadata.firstOrNull { it.first == "View" }?.second
                val kind = label?.let(::deviceTypePhotoUploadKind)
                if (kind != null) {
                    imageViewer = null
                    mediaUploadInitialKind = kind
                    showMediaUpload = true
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
    fieldActionLabel?.let { label ->
        FieldActionDialog(
            fieldLabel = label,
            fieldValue = fields.firstOrNull { it.label == label }?.actionValue(),
            canEdit =
                deviceTypePhotoUploadKind(label) != null ||
                    editableFields.any { it.label == label },
            onCopy = {
                fields
                    .firstOrNull { it.label == label }
                    ?.actionValue()
                    ?.let { onCopyValue(label, it) }
                fieldActionLabel = null
            },
            onChangelog = {
                fieldActionLabel = null
                openChangelogRequested = true
            },
            onEdit = {
                val photoKind = deviceTypePhotoUploadKind(label)
                val field = editableFields.firstOrNull { it.label == label }
                fieldActionLabel = null
                if (photoKind != null) {
                    mediaUploadInitialKind = photoKind
                    showMediaUpload = true
                } else if (field != null) {
                    focusedEditFieldKey = field.key
                    focusedEditValue = field.value
                    viewModel.startFieldEditing(field.key)
                }
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
    focusedEditField?.let { field ->
        FocusedEditFieldDialog(
            field = field,
            value = focusedEditValue,
            referenceOptions = referenceOptions,
            choiceOptions = choiceOptions,
            onCreateLinkedItem = { linkedField ->
                linkedField.referenceEndpointPath?.let { endpoint ->
                    onCreateLinkedItem(
                        linkedField.key,
                        endpoint,
                        linkedField.label,
                        true,
                    )
                }
            },
            onValueChange = { focusedEditValue = it },
            onDismiss = {
                routeFocusHandled = true
                viewModel.cancelFieldEditing()
                focusedEditFieldKey = null
                if (isRouteFocusedEditor) onBack()
            },
            onReview = { editedValue ->
                // The route is retained for back-stack/breadcrumb state, but it must not relaunch
                // the focused editor after this review has been confirmed.
                routeFocusHandled = true
                if (editedValue == field.value) {
                    viewModel.cancelFieldEditing()
                    focusedEditFieldKey = null
                    if (isRouteFocusedEditor) onBack()
                } else {
                    pendingEdits = mapOf(field.key to (field.kind to editedValue))
                    pendingEditFieldKey = field.key
                    focusedEditFieldKey = null
                }
            },
        )
    }
    pendingEdits?.let { edits ->
        EditDiffDialog(
            fields = editableFields,
            edits = edits,
            referenceOptions = referenceOptions,
            choiceOptions = choiceOptions,
            onDismiss = {
                pendingEdits = null
                val wasFocusedEdit = pendingEditFieldKey != null
                if (wasFocusedEdit) viewModel.cancelFieldEditing()
                pendingEditFieldKey = null
                if (wasFocusedEdit && isRouteFocusedEditor) onBack()
            },
            onConfirm = {
                viewModel.save(edits)
                // Confirmation ends the focused-edit session. The route keeps its focus key for
                // breadcrumb/back-stack state, so clear the local guard explicitly as well; a
                // cache update from save must not reopen the editor through the route effect.
                routeFocusHandled = true
                focusedEditFieldKey = null
                pendingEdits = null
                pendingEditFieldKey = null
            },
        )
    }
    relatedTarget?.let { target ->
        RelatedItemsBottomSheet(
            target = target,
            objects = relatedObjects,
            previewUrls = relatedPreviewUrls,
            isRefreshing = isRelatedRefreshing,
            onObjectClick = { id ->
                viewModel.dismissRelatedItems()
                onNavigateToReference(target.endpointPath, id, title ?: modelLabel)
            },
            onDismiss = viewModel::dismissRelatedItems,
        )
    }
}

/** Switches an Elevation/Trace tab between its JSON-derived list and NetBox's own SVG rendering. */
@Composable
private fun DiagramViewToggle(
    showSvg: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !showSvg,
            onClick = { onToggle(false) },
            label = { Text("List") },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        FilterChip(
            selected = showSvg,
            onClick = { onToggle(true) },
            label = { Text("Diagram") },
            leadingIcon = {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

@Composable
private fun CableTraceSegmentCard(segment: CableTraceSegment, onNavigate: (String, Int) -> Unit) {
    NyetboxCard {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            segment.nearEnds.forEach { node -> CableTraceNodeRow(node, onNavigate) }
            CableTraceCableRow(segment.cable)
            if (segment.farEnds.isEmpty()) {
                Text(
                    "Not connected further",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            } else {
                segment.farEnds.forEach { node -> CableTraceNodeRow(node, onNavigate) }
            }
        }
    }
}

@Composable
private fun CableTraceNodeRow(node: CableTraceNode, onNavigate: (String, Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Icon(
            AppIcons.forEndpointPath(node.target.endpointPath),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            node.deviceTarget?.let { device ->
                Text(
                    device.display,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onNavigate(device.endpointPath, device.id) },
                )
            }
            Text(
                node.portLabel,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier.clickable { onNavigate(node.target.endpointPath, node.target.id) },
            )
        }
    }
}

@Composable
private fun CableTraceCableRow(cable: CableTraceCableInfo?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 30.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(
            Icons.Default.Cable,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        if (cable == null) {
            Text(
                "No cable",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
        } else {
            cable.color
                ?.let { hex -> runCatching { Color("#$hex".toColorInt()) } }
                ?.getOrNull()
                ?.let { swatch ->
                    Box(Modifier.size(10.dp).background(swatch, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
            Text(
                buildString {
                    append(cable.label)
                    cable.statusLabel?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun JournalEntryItem(entry: JournalEntryUi, onEdit: () -> Unit) {
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
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit journal entry")
            }
        }
        Spacer(Modifier.height(4.dp))
        CommentCard(content = entry.comments, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FieldLabel(text: String, onLongPress: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            onLongPress?.let { Modifier.combinedClickable(onClick = {}, onLongClick = it) }
                ?: Modifier,
    )
}
