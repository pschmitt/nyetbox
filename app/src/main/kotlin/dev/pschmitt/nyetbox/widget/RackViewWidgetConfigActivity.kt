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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.RackElevationEntity
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.RackElevationRepository
import dev.pschmitt.nyetbox.data.repository.RackFace
import dev.pschmitt.nyetbox.ui.theme.NyetboxTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RackViewWidgetConfigViewModel
@Inject
constructor(
    genericObjectRepository: GenericObjectRepository,
    private val rackElevationRepository: RackElevationRepository,
) : ViewModel() {

    val racks: StateFlow<List<NetBoxObjectEntity>> =
        genericObjectRepository
            .observeObjects(RACKS_ENDPOINT_PATH, "")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val previewRequest = MutableStateFlow<Pair<Int, RackFace>?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val previewSlots: StateFlow<List<RackElevationEntity>> =
        previewRequest
            .flatMapLatest { request ->
                request?.let { (rackId, face) -> rackElevationRepository.observe(rackId, face) }
                    ?: emptyFlow()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun requestPreview(rackId: Int, face: RackFace) {
        previewRequest.value = rackId to face
    }
}

/**
 * Shown before a placed rack-view widget instance is confirmed (`APPWIDGET_CONFIGURE`), and again
 * from the placed widget's own long-press "Configure" menu. Lets the user pick the specific rack
 * ([RACKS_ENDPOINT_PATH] object), which face to show, and compact mode - see
 * [RackViewGlanceWidget].
 */
@AndroidEntryPoint
class RackViewWidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var widget: RackViewGlanceWidget

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
                var rackId by remember { mutableStateOf<Int?>(null) }
                var rackLabel by remember { mutableStateOf("") }
                var face by remember { mutableStateOf(RackFace.FRONT) }
                var compact by remember { mutableStateOf(false) }
                var loaded by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val glanceId =
                        GlanceAppWidgetManager(this@RackViewWidgetConfigActivity).getGlanceIdBy(appWidgetId)
                    val prefs =
                        getAppWidgetState(
                            this@RackViewWidgetConfigActivity,
                            PreferencesGlanceStateDefinition,
                            glanceId,
                        )
                    rackId = prefs[KEY_RACK_ID]
                    rackLabel = prefs[KEY_RACK_LABEL] ?: ""
                    face = if (prefs[KEY_RACK_FACE] == RackFace.REAR.apiValue) RackFace.REAR else RackFace.FRONT
                    compact = prefs[KEY_RACK_COMPACT] ?: false
                    loaded = true
                }

                if (loaded) {
                    RackViewConfigScreen(
                        rackId = rackId,
                        rackLabel = rackLabel,
                        onRackSelected = { id, label -> rackId = id; rackLabel = label },
                        face = face,
                        onFaceChange = { face = it },
                        compact = compact,
                        onCompactChange = { compact = it },
                        onSave = {
                            val id = rackId ?: return@RackViewConfigScreen
                            scope.launch {
                                val glanceId =
                                    GlanceAppWidgetManager(this@RackViewWidgetConfigActivity)
                                        .getGlanceIdBy(appWidgetId)
                                widget.saveConfig(
                                    this@RackViewWidgetConfigActivity,
                                    glanceId,
                                    id,
                                    rackLabel,
                                    face,
                                    compact,
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
private fun RackViewConfigScreen(
    viewModel: RackViewWidgetConfigViewModel = hiltViewModel(),
    rackId: Int?,
    rackLabel: String,
    onRackSelected: (Int, String) -> Unit,
    face: RackFace,
    onFaceChange: (RackFace) -> Unit,
    compact: Boolean,
    onCompactChange: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val racks by viewModel.racks.collectAsState()
    val previewSlots by viewModel.previewSlots.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(rackId, face) { rackId?.let { viewModel.requestPreview(it, face) } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rack view widget") }) },
        bottomBar = {
            Button(
                onClick = onSave,
                enabled = rackId != null,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Save")
            }
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item {
                RackViewPreviewCard(
                    rackLabel = rackLabel,
                    compact = compact,
                    slots = previewSlots,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            item { Text("Rack", modifier = Modifier.padding(16.dp, 8.dp)) }
            item {
                ListItem(
                    modifier = Modifier.clickable { pickerOpen = true },
                    leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                    headlineContent = { Text(rackLabel.ifBlank { "Choose a rack" }) },
                )
            }
            item { Text("Face", modifier = Modifier.padding(16.dp, 8.dp)) }
            items(RackFace.entries) { option ->
                ListItem(
                    modifier = Modifier.clickable { onFaceChange(option) },
                    leadingContent = {
                        RadioButton(selected = face == option, onClick = { onFaceChange(option) })
                    },
                    headlineContent = { Text(option.label) },
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable { onCompactChange(!compact) },
                    headlineContent = { Text("Compact mode") },
                    supportingContent = { Text("Hide the title bar to fit more content") },
                    trailingContent = { Switch(checked = compact, onCheckedChange = onCompactChange) },
                )
            }
        }
    }

    if (pickerOpen) {
        RackPickerDialog(
            racks = racks,
            onDismiss = { pickerOpen = false },
            onRackSelected = { rack ->
                onRackSelected(rack.id, rack.display)
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun RackPickerDialog(
    racks: List<NetBoxObjectEntity>,
    onDismiss: () -> Unit,
    onRackSelected: (NetBoxObjectEntity) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        racks.filter { rack ->
            query.isBlank() ||
                rack.display.contains(query, ignoreCase = true) ||
                rack.secondaryLine.orEmpty().contains(query, ignoreCase = true)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a rack") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search racks") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        "No matching cached racks",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(filtered) { rack ->
                        ListItem(
                            modifier = Modifier.clickable { onRackSelected(rack) },
                            leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                            headlineContent = { Text(rack.display) },
                            supportingContent = { rack.secondaryLine?.let { Text(it) } },
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
    )
}

private data class PreviewBlock(val deviceId: Int?, val slots: List<RackElevationEntity>)

private fun mergePreviewSlots(slots: List<RackElevationEntity>): List<PreviewBlock> {
    val blocks = mutableListOf<PreviewBlock>()
    slots.forEach { slot ->
        val current = blocks.lastOrNull()
        if (current != null && current.deviceId == slot.deviceId) {
            blocks[blocks.lastIndex] = current.copy(slots = current.slots + slot)
        } else {
            blocks += PreviewBlock(slot.deviceId, listOf(slot))
        }
    }
    return blocks
}

/**
 * Live mockup of how the rack-view widget will look with the current (unsaved) selections - plain
 * Compose, driven by the real, already-synced [RackElevationEntity] data for the selected rack -
 * see [RackViewGlanceWidget].
 */
@Composable
private fun RackViewPreviewCard(
    rackLabel: String,
    compact: Boolean,
    slots: List<RackElevationEntity>,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(rackLabel.ifBlank { "Rack view" }, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (slots.isEmpty()) {
                Text("No elevation data yet", style = MaterialTheme.typography.bodyMedium)
                return@Card
            }
            mergePreviewSlots(slots).take(8).forEach { block ->
                val firstSlot = block.slots.first()
                val lastSlot = block.slots.last()
                val deviceId = block.deviceId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(
                        if (firstSlot.slotName == lastSlot.slotName) firstSlot.slotName
                        else "${firstSlot.slotName}–${lastSlot.slotName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(56.dp),
                        maxLines = 1,
                    )
                    Box(
                        modifier =
                            Modifier.weight(1f)
                                .height(20.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (deviceId != null) previewDeviceColor(deviceId)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (deviceId != null) {
                            Text(
                                firstSlot.deviceDisplay ?: "Device #$deviceId",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF263238),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PREVIEW_DEVICE_PALETTE =
    listOf(
        Color(0xFFDDEBFF),
        Color(0xFFE3F4E7),
        Color(0xFFFFE5D0),
        Color(0xFFEDE0FF),
        Color(0xFFFFF0B3),
        Color(0xFFD9F4F0),
        Color(0xFFFFDDE4),
        Color(0xFFE4E8F0),
    )

private fun previewDeviceColor(deviceId: Int) =
    PREVIEW_DEVICE_PALETTE[Math.floorMod(deviceId, PREVIEW_DEVICE_PALETTE.size)]
