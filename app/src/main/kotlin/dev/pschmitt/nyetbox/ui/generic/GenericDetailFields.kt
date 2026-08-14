package dev.pschmitt.nyetbox.ui.generic

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.pschmitt.nyetbox.data.repository.hiddenFieldPreferenceKey
import dev.pschmitt.nyetbox.data.schema.Humanize
import dev.pschmitt.nyetbox.ui.common.CollapsibleCommentCard
import dev.pschmitt.nyetbox.ui.common.DetailTrailingActions
import dev.pschmitt.nyetbox.ui.common.ImageViewerItem
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.NyetboxDetailsCard
import dev.pschmitt.nyetbox.ui.common.NyetboxLinkedItemsCard
import dev.pschmitt.nyetbox.ui.common.NyetboxSectionCard
import dev.pschmitt.nyetbox.ui.common.RemoteThumbnail
import dev.pschmitt.nyetbox.ui.common.formatNetBoxDateTime
import dev.pschmitt.nyetbox.ui.directory.AppIcons

internal const val KEY_DETAILS_CARD_TITLE = "Details"
internal const val KEY_LINKED_ITEMS_CARD_TITLE = "Linked items"

internal fun visibleFieldRows(
    rows: List<FieldRow>,
    endpointPath: String,
    hiddenFieldKeys: Set<String>,
    showHiddenFields: Boolean,
): List<FieldRow> {
    if (showHiddenFields) return rows
    val filtered = rows.filterNot { row ->
        row !is FieldRow.Section &&
            row !is FieldRow.CustomGroup &&
            hiddenFieldPreferenceKey(endpointPath, row.label) in hiddenFieldKeys
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

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.detailCard(
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    item {
        NyetboxCard(
            modifier =
                Modifier.padding(vertical = 4.dp)
                    .then(
                        if (onClick != null || onLongPress != null) {
                            Modifier.combinedClickable(
                                onClick = { onClick?.invoke() },
                                onLongClick = { onLongPress?.invoke() },
                            )
                        } else Modifier
                    )
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                content()
            }
        }
    }
}

internal fun endpointModelLabel(endpointPath: String): String =
    endpointPath
        .trimEnd('/')
        .substringAfterLast('/')
        .takeIf { it.isNotBlank() }
        ?.let(Humanize::label) ?: "Details"

internal fun LazyListScope.fieldRow(
    row: FieldRow,
    onNavigateToReference: (String, Int) -> Unit,
    onRelatedItems: (CountTarget) -> Unit,
    onOpenUrl: (String) -> Unit,
    netboxBaseUrl: String?,
    onDownloadAttachment: (url: String, filename: String) -> Unit,
    onImageClick: (ImageViewerItem) -> Unit,
    isDownloading: Boolean,
    onCopyValue: (label: String, value: String) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
    onMatterPairingCode: (String) -> Unit,
) {
    when (row) {
        is FieldRow.Section ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        row.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        is FieldRow.CustomGroup ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Category,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        row.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        is FieldRow.Metadata ->
            item {
                NyetboxCard(modifier = Modifier.padding(vertical = 4.dp)) {
                    MetadataContent(row, Modifier.padding(12.dp))
                }
            }
        is FieldRow.PlainText ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                PlainTextContent(row, onCopyValue, onFieldLongPress, onMatterPairingCode)
            }
        is FieldRow.BooleanValue -> item { BooleanValueSurfaceContent(row, onFieldLongPress) }
        is FieldRow.Count ->
            detailCard(
                onClick = { onRelatedItems(row.target) },
                onLongPress = { onFieldLongPress(row.label) },
            ) {
                LinkedItemContent(row)
            }
        is FieldRow.Markdown ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                MarkdownContent(row, onFieldLongPress)
            }
        is FieldRow.Reference ->
            detailCard(
                onClick = { onNavigateToReference(row.target.endpointPath, row.target.id) },
                onLongPress = { onFieldLongPress(row.label) },
            ) {
                ReferenceContent(row, onNavigateToReference, onCopyValue, onFieldLongPress)
            }
        is FieldRow.Image ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                ImageFieldContent(row, onImageClick, onFieldLongPress)
            }
        is FieldRow.ReferenceList ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                ReferenceListContent(row, onNavigateToReference, onFieldLongPress)
            }
        is FieldRow.TagList ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                TagListContent(row, onNavigateToReference, onFieldLongPress)
            }
        is FieldRow.ChipList ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                ChipListContent(row, onFieldLongPress)
            }
        is FieldRow.ExternalLink ->
            detailCard(
                onClick = { onOpenUrl(row.url) },
                onLongPress = { onFieldLongPress(row.label) },
            ) {
                ExternalLinkContent(row, netboxBaseUrl, onFieldLongPress)
            }
        is FieldRow.FileAttachment ->
            detailCard(onLongPress = { onFieldLongPress(row.label) }) {
                FileAttachmentContent(row, onDownloadAttachment, isDownloading, onFieldLongPress)
            }
    }
}

@Composable
private fun PlainTextContent(
    row: FieldRow.PlainText,
    onCopyValue: (label: String, value: String) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
    onMatterPairingCode: (String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                row.value,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (row.copyable) {
                DetailTrailingActions(
                    copyLabel = row.label,
                    onCopy = { onCopyValue(row.label, row.value) },
                )
            }
            if (row.matterPairingCode) {
                IconButton(
                    onClick = { onMatterPairingCode(row.value) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = "Show Matter pairing QR code",
                    )
                }
            }
        }
    }
}

@Composable
private fun MetadataContent(row: FieldRow.Metadata, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.AccessTime,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                row.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatNetBoxDateTime(row.value),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BooleanValueSurfaceContent(
    row: FieldRow.BooleanValue,
    onFieldLongPress: (label: String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color =
            if (row.value) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier =
            Modifier.fillMaxWidth()
                .padding(vertical = 4.dp)
                .combinedClickable(onClick = {}, onLongClick = { onFieldLongPress(row.label) }),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (row.value) Icons.Default.CheckCircle else Icons.Default.Close,
                contentDescription = if (row.value) "Enabled" else "Disabled",
                tint =
                    if (row.value) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column {
                FieldLabel(row.label) { onFieldLongPress(row.label) }
                Text(
                    if (row.value) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyLarge,
                    color =
                        if (row.value) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun MarkdownContent(row: FieldRow.Markdown, onFieldLongPress: (label: String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        CollapsibleCommentCard(content = row.content, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ReferenceContent(
    row: FieldRow.Reference,
    onNavigateToReference: (String, Int) -> Unit,
    onCopyValue: (label: String, value: String) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                AppIcons.forEndpointPath(row.target.endpointPath),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                row.target.display,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            DetailTrailingActions(
                copyLabel = row.label.takeIf { row.copyable },
                onCopy = { onCopyValue(row.label, row.target.display) }.takeIf { row.copyable },
                openLabel = row.label,
                onOpen = { onNavigateToReference(row.target.endpointPath, row.target.id) },
            )
        }
    }
}

@Composable
private fun ImageFieldContent(
    row: FieldRow.Image,
    onImageClick: (ImageViewerItem) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        RemoteThumbnail(
            imageUrl = row.url,
            contentDescription = row.label,
            modifier =
                Modifier.fillMaxWidth().height(160.dp).padding(top = 4.dp).clickable {
                    onImageClick(ImageViewerItem(url = row.url, title = row.label))
                },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun ReferenceListContent(
    row: FieldRow.ReferenceList,
    onNavigateToReference: (String, Int) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        row.targets.forEach { target ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.clickable { onNavigateToReference(target.endpointPath, target.id) }
                        .padding(vertical = 2.dp),
            ) {
                Icon(
                    AppIcons.forEndpointPath(target.endpointPath),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    target.display,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TagListContent(
    row: FieldRow.TagList,
    onNavigateToReference: (String, Int) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            FieldLabel(row.label) { onFieldLongPress(row.label) }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            row.targets.forEach { target ->
                AssistChip(
                    onClick = { onNavigateToReference(target.endpointPath, target.id) },
                    label = { Text(target.display) },
                    leadingIcon = {
                        Icon(
                            Icons.AutoMirrored.Filled.Label,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ChipListContent(row: FieldRow.ChipList, onFieldLongPress: (label: String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        Text(row.values.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ExternalLinkContent(
    row: FieldRow.ExternalLink,
    netboxBaseUrl: String?,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                shortenDisplayedUrl(row.url, netboxBaseUrl),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun LinkedItemContent(row: FieldRow.Count) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Icon(
            AppIcons.forEndpointPath(row.target.endpointPath),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            row.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Badge { Text(row.value) }
    }
}

@Composable
private fun FileAttachmentContent(
    row: FieldRow.FileAttachment,
    onDownloadAttachment: (url: String, filename: String) -> Unit,
    isDownloading: Boolean,
    onFieldLongPress: (label: String) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        FieldLabel(row.label) { onFieldLongPress(row.label) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth().clickable(enabled = !isDownloading) {
                    onDownloadAttachment(row.url, row.filename)
                },
        ) {
            Icon(Icons.Default.Description, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                row.filename,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Download, contentDescription = "Download and open")
            }
        }
    }
}

/**
 * A cluster of consecutive [FieldRow]s from the Overview list - see [clusterFieldRows]. Every row
 * that isn't part of a cluster stays a [FieldRowCluster.Standalone], rendered exactly like today
 * via [fieldRow]. [FieldRowCluster.Grouped] covers two distinct cases, both stacked inside one
 * shared card: a real NetBox custom-field group (`label` is the group name, shown as a card title
 * via [NyetboxSectionCard]), and a run of key native/top-level fields (`label` is
 * [KEY_DETAILS_CARD_TITLE]).
 */
internal sealed interface FieldRowCluster {
    data class Standalone(val row: FieldRow) : FieldRowCluster

    data class Grouped(val label: String?, val rows: List<FieldRow>) : FieldRowCluster
}

/**
 * Native/top-level field types that belong in the titled key-details card. [FieldRow.BooleanValue]
 * is included so a boolean sitting in the middle of an otherwise-plain field list (e.g. a rack's
 * `desc_units`, between `weight_unit` and `outer_width`) doesn't fragment one "Details" card into
 * several - [ClusteredFieldRow] already renders it with its own tinted surface inline, the same way
 * it does for a boolean inside a real custom-field group.
 */
private fun FieldRow.isKeyDetailsField(): Boolean =
    this is FieldRow.PlainText ||
        this is FieldRow.Reference ||
        this is FieldRow.Markdown ||
        this is FieldRow.Metadata ||
        this is FieldRow.BooleanValue

/**
 * Clusters a flat [FieldRow] list (as produced by `buildFieldRows`) two ways, matching the
 * marker-walking idiom [visibleFieldRows] already uses:
 * - Before the `"Custom fields"` [FieldRow.Section] marker (native/top-level fields), consecutive
 *   [FieldRow.PlainText], [FieldRow.Reference], [FieldRow.Markdown], [FieldRow.Metadata], and
 *   [FieldRow.BooleanValue] rows - the generic shape of site, role, serial, device type, comments,
 *   audit fields, and boolean toggles like a rack's `desc_units` - cluster into one titled
 *   [KEY_DETAILS_CARD_TITLE] card, in their original order. This is type-driven, not name-driven,
 *   so it stays generic across every NetBox object type.
 * - Consecutive [FieldRow.Count] rows cluster into one titled [KEY_LINKED_ITEMS_CARD_TITLE] card.
 *   These are reverse-related collections such as a site's circuits, devices, racks, and virtual
 *   machines, and remain individually clickable to open the cached filtered list.
 * - Other native row types (`Image`, `ReferenceList`, `TagList`, `ChipList`, `ExternalLink`,
 *   `FileAttachment`) keep their own distinct cards and break both runs - if a native run resumes
 *   afterward, only the first such run is titled [KEY_DETAILS_CARD_TITLE]; later ones render as a
 *   plain, untitled card so a screen never shows two cards both saying "Details".
 * - From the `"Custom fields"` [FieldRow.Section] marker onward, custom fields sharing a real,
 *   admin-defined NetBox custom-field group cluster into one shared, titled card. The synthetic
 *   [UNGROUPED_CUSTOM_FIELD_GROUP_LABEL] bucket - and the `"Custom fields"` [FieldRow.Section]
 *   itself - is left exactly as-is.
 */
internal fun clusterFieldRows(rows: List<FieldRow>): List<FieldRowCluster> = buildList {
    var sawCustomFieldsSection = false
    var pendingNativeRun: MutableList<FieldRow>? = null
    var pendingLinkedItemsRun: MutableList<FieldRow>? = null
    var pendingGroupLabel: String? = null
    var pendingGroupRows: MutableList<FieldRow>? = null
    // A breaking row type (e.g. a boolean like a rack's "Desc. Units") can split one native run
    // into several - only the first gets the "Details" title, so the screen never shows two cards
    // both titled "Details"; any later run renders as a plain, untitled card instead.
    var usedDetailsTitle = false

    fun flushNativeRun() {
        val run = pendingNativeRun
        pendingNativeRun = null
        if (run == null) return
        if (usedDetailsTitle) {
            add(FieldRowCluster.Grouped(label = null, rows = run))
        } else {
            add(FieldRowCluster.Grouped(label = KEY_DETAILS_CARD_TITLE, rows = run))
            usedDetailsTitle = true
        }
    }

    fun flushLinkedItemsRun() {
        val run = pendingLinkedItemsRun
        pendingLinkedItemsRun = null
        if (run == null) return
        add(FieldRowCluster.Grouped(label = KEY_LINKED_ITEMS_CARD_TITLE, rows = run))
    }

    fun flushPendingGroup() {
        val label = pendingGroupLabel
        val members = pendingGroupRows
        if (label != null && !members.isNullOrEmpty()) {
            add(FieldRowCluster.Grouped(label, members))
        } else {
            members?.forEach { add(FieldRowCluster.Standalone(it)) }
        }
        pendingGroupLabel = null
        pendingGroupRows = null
    }

    rows.forEach { row ->
        if (!sawCustomFieldsSection) {
            when {
                row is FieldRow.Section -> {
                    flushNativeRun()
                    flushLinkedItemsRun()
                    sawCustomFieldsSection = true
                    add(FieldRowCluster.Standalone(row))
                }
                row is FieldRow.Count -> {
                    flushNativeRun()
                    (pendingLinkedItemsRun
                            ?: mutableListOf<FieldRow>().also { pendingLinkedItemsRun = it })
                        .add(row)
                }
                row.isKeyDetailsField() -> {
                    flushLinkedItemsRun()
                    (pendingNativeRun ?: mutableListOf<FieldRow>().also { pendingNativeRun = it })
                        .add(row)
                }
                else -> {
                    flushNativeRun()
                    flushLinkedItemsRun()
                    add(FieldRowCluster.Standalone(row))
                }
            }
            return@forEach
        }
        when (row) {
            is FieldRow.Section -> {
                flushPendingGroup()
                add(FieldRowCluster.Standalone(row))
            }
            is FieldRow.CustomGroup -> {
                flushPendingGroup()
                if (row.label == UNGROUPED_CUSTOM_FIELD_GROUP_LABEL) {
                    // Keep today's look: a plain heading, its fields render standalone below.
                    add(FieldRowCluster.Standalone(row))
                } else {
                    pendingGroupLabel = row.label
                    pendingGroupRows = mutableListOf()
                }
            }
            else -> {
                val pending = pendingGroupRows
                if (pending != null) pending.add(row) else add(FieldRowCluster.Standalone(row))
            }
        }
    }
    flushNativeRun()
    flushLinkedItemsRun()
    flushPendingGroup()
}

/**
 * Renders a flat [FieldRow] list, clustering both real named custom-field groups and key native
 * fields into titled shared cards (via [clusterFieldRows]) while every other row renders exactly
 * like [fieldRow] renders it standalone today.
 */
internal fun LazyListScope.fieldRows(
    rows: List<FieldRow>,
    onNavigateToReference: (String, Int) -> Unit,
    onRelatedItems: (CountTarget) -> Unit,
    onOpenUrl: (String) -> Unit,
    netboxBaseUrl: String?,
    onDownloadAttachment: (url: String, filename: String) -> Unit,
    onImageClick: (ImageViewerItem) -> Unit,
    isDownloading: Boolean,
    onCopyValue: (label: String, value: String) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
    onMatterPairingCode: (String) -> Unit,
) {
    clusterFieldRows(rows).forEach { cluster ->
        when (cluster) {
            is FieldRowCluster.Standalone ->
                fieldRow(
                    cluster.row,
                    onNavigateToReference,
                    onRelatedItems,
                    onOpenUrl,
                    netboxBaseUrl,
                    onDownloadAttachment,
                    onImageClick,
                    isDownloading,
                    onCopyValue,
                    onFieldLongPress,
                    onMatterPairingCode,
                )
            is FieldRowCluster.Grouped -> {
                val clusteredContent: @Composable ColumnScope.() -> Unit = {
                    Column(Modifier.padding(horizontal = 12.dp)) {
                        cluster.rows.forEach { row ->
                            ClusteredFieldRow(
                                row = row,
                                onNavigateToReference = onNavigateToReference,
                                onRelatedItems = onRelatedItems,
                                onOpenUrl = onOpenUrl,
                                netboxBaseUrl = netboxBaseUrl,
                                onDownloadAttachment = onDownloadAttachment,
                                onImageClick = onImageClick,
                                isDownloading = isDownloading,
                                onCopyValue = onCopyValue,
                                onFieldLongPress = onFieldLongPress,
                                onMatterPairingCode = onMatterPairingCode,
                            )
                        }
                    }
                }
                val label = cluster.label
                item {
                    if (label != null) {
                        if (label == KEY_DETAILS_CARD_TITLE) {
                            NyetboxDetailsCard(
                                modifier = Modifier.padding(vertical = 4.dp),
                                content = clusteredContent,
                            )
                        } else if (label == KEY_LINKED_ITEMS_CARD_TITLE) {
                            NyetboxLinkedItemsCard(
                                modifier = Modifier.padding(vertical = 4.dp),
                                content = clusteredContent,
                            )
                        } else {
                            NyetboxSectionCard(
                                title = label,
                                icon = Icons.Outlined.Category,
                                modifier = Modifier.padding(vertical = 4.dp),
                                content = clusteredContent,
                            )
                        }
                    } else {
                        NyetboxCard(
                            modifier = Modifier.padding(vertical = 4.dp),
                            content = clusteredContent,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.clusterInteraction(
    onClick: (() -> Unit)?,
    onLongPress: () -> Unit,
): Modifier = combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongPress)

/**
 * Renders a single row's content stacked inside a shared cluster card ([FieldRowCluster.Grouped]),
 * with no card of its own - only [FieldRow.Reference] and [FieldRow.ExternalLink] carry a
 * card-level click action standalone (see [fieldRow]'s `detailCard` calls), so those need an
 * explicit click handler re-attached here; every other type either has no click action or already
 * embeds its own (e.g. [FieldRow.FileAttachment]'s download row).
 */
@Composable
private fun ClusteredFieldRow(
    row: FieldRow,
    onNavigateToReference: (String, Int) -> Unit,
    onRelatedItems: (CountTarget) -> Unit,
    onOpenUrl: (String) -> Unit,
    netboxBaseUrl: String?,
    onDownloadAttachment: (url: String, filename: String) -> Unit,
    onImageClick: (ImageViewerItem) -> Unit,
    isDownloading: Boolean,
    onCopyValue: (label: String, value: String) -> Unit,
    onFieldLongPress: (label: String) -> Unit,
    onMatterPairingCode: (String) -> Unit,
) {
    if (row is FieldRow.BooleanValue) {
        // Already owns its own tinted Surface plus click handling - wrapping it in another
        // clickable Column would just be redundant, not additive.
        BooleanValueSurfaceContent(row, onFieldLongPress)
        return
    }
    val onClick: (() -> Unit)? =
        when (row) {
            is FieldRow.Reference -> {
                { onNavigateToReference(row.target.endpointPath, row.target.id) }
            }
            is FieldRow.ExternalLink -> {
                { onOpenUrl(row.url) }
            }
            is FieldRow.Count -> {
                { onRelatedItems(row.target) }
            }
            else -> null
        }
    Column(
        Modifier.fillMaxWidth()
            .clusterInteraction(onClick) { onFieldLongPress(row.label) }
            .padding(vertical = 4.dp)
    ) {
        when (row) {
            is FieldRow.PlainText ->
                PlainTextContent(row, onCopyValue, onFieldLongPress, onMatterPairingCode)
            is FieldRow.Markdown -> MarkdownContent(row, onFieldLongPress)
            is FieldRow.Metadata -> MetadataContent(row)
            is FieldRow.Count -> LinkedItemContent(row)
            is FieldRow.Reference ->
                ReferenceContent(row, onNavigateToReference, onCopyValue, onFieldLongPress)
            is FieldRow.ChipList -> ChipListContent(row, onFieldLongPress)
            is FieldRow.ExternalLink -> ExternalLinkContent(row, netboxBaseUrl, onFieldLongPress)
            is FieldRow.FileAttachment ->
                FileAttachmentContent(row, onDownloadAttachment, isDownloading, onFieldLongPress)
            is FieldRow.Image -> ImageFieldContent(row, onImageClick, onFieldLongPress)
            is FieldRow.ReferenceList ->
                ReferenceListContent(row, onNavigateToReference, onFieldLongPress)
            is FieldRow.TagList -> TagListContent(row, onNavigateToReference, onFieldLongPress)
            is FieldRow.BooleanValue,
            is FieldRow.Section,
            is FieldRow.CustomGroup -> Unit // Never produced as a custom-field group member.
        }
    }
}
