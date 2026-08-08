package dev.pschmitt.nyetbox.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.TextStyle
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dev.pschmitt.nyetbox.MainActivity
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.db.RackElevationEntity
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.RackElevationRepository
import dev.pschmitt.nyetbox.data.repository.RackFace
import dev.pschmitt.nyetbox.putGestureExtras
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow

internal val KEY_RACK_ID = intPreferencesKey("rack_id")
internal val KEY_RACK_LABEL = stringPreferencesKey("rack_label")
internal val KEY_RACK_FACE = stringPreferencesKey("rack_face")
internal val KEY_RACK_COMPACT = booleanPreferencesKey("rack_compact")

internal const val RACKS_ENDPOINT_PATH = "api/dcim/racks/"
internal const val DEVICES_ENDPOINT_PATH = "api/dcim/devices/"

private val SLOT_LABEL_WIDTH = 52.dp
private val HEIGHT_PER_SLOT = 10.dp
private val MIN_BLOCK_HEIGHT = 32.dp

/** Fixed device-color palette, matching [dev.pschmitt.nyetbox.ui.generic.GenericDetailRack]'s. */
private val RACK_DEVICE_PALETTE =
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
private val RACK_DEVICE_TEXT_COLOR = ColorProvider(Color(0xFF263238))

private fun rackDeviceColor(deviceId: Int): ColorProvider =
    ColorProvider(RACK_DEVICE_PALETTE[Math.floorMod(deviceId, RACK_DEVICE_PALETTE.size)])

/**
 * A single home-screen widget showing one specific rack's unit-by-unit device layout (picked at
 * config time via [RackViewWidgetConfigActivity]), as a scrollable list - unlike
 * [NyetboxGlanceWidget]'s fixed-row content, this uses Glance's [LazyColumn] since a rack can be
 * far taller than any reasonable widget size. Reuses [RackElevationRepository]'s already-parsed,
 * non-SVG per-unit data (synced alongside every full sync, independent of the in-app SVG diagram
 * view), merging contiguous same-device slots into blocks the same way
 * [dev.pschmitt.nyetbox.ui.generic.GenericDetailRack] does for its own (Compose, non-Glance)
 * rendering of the same data.
 *
 * Config and elevation data are both read *reactively* inside the composition (via
 * [RackViewConfigStore] and [RackElevationRepository]'s own `Flow`) rather than one-shot suspend
 * reads in [provideGlance] - see [NyetboxGlanceWidget]'s class doc for why that matters for
 * reconfiguring an already-placed widget.
 */
class RackViewGlanceWidget
@Inject
constructor(
    private val rackElevationRepository: RackElevationRepository,
    private val rackViewConfigStore: RackViewConfigStore,
    private val workManager: WorkManager,
) : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        rackViewConfigStore.remove(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        rackViewConfigStore.ensureLoaded(context, id, appWidgetId)

        provideContent {
            GlanceTheme {
                val configs by rackViewConfigStore.configs.collectAsState()
                val config = configs[appWidgetId] ?: RackViewInstanceConfig()
                val rackId = config.rackId

                val slotsFlow =
                    remember(rackId, config.face) {
                        rackId
                            ?.let { rackElevationRepository.observe(it, config.face) }
                            ?.distinctUntilChanged() ?: emptyFlow()
                    }
                val slots by slotsFlow.collectAsState(initial = emptyList())
                val blocks = remember(slots) { mergeRackViewSlots(slots) }

                Scaffold(
                    backgroundColor = GlanceTheme.colors.widgetBackground,
                    titleBar =
                        if (config.compact) {
                            null
                        } else {
                            {
                                TitleBar(
                                    startIcon = ImageProvider(R.drawable.ic_object_storage),
                                    iconColor = GlanceTheme.colors.primary,
                                    textColor = GlanceTheme.colors.onSurface,
                                    title = config.rackLabel.ifBlank { "Rack view" },
                                )
                            }
                        },
                ) {
                    when {
                        rackId == null -> EmptyState("No rack selected")
                        blocks.isEmpty() -> EmptyState("No elevation data yet")
                        else ->
                            LazyColumn(modifier = GlanceModifier.fillMaxWidth()) {
                                items(blocks) { block -> RackViewRow(context, block) }
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
        rackId: Int,
        rackLabel: String,
        face: RackFace,
        compact: Boolean,
    ) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[KEY_RACK_ID] = rackId
            prefs[KEY_RACK_LABEL] = rackLabel
            prefs[KEY_RACK_FACE] = face.apiValue
            prefs[KEY_RACK_COMPACT] = compact
        }
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // Published immediately so an already-running composition session picks up the change
        // right away - see NyetboxGlanceWidget's class doc and RackViewConfigStore's doc.
        rackViewConfigStore.publish(appWidgetId, RackViewInstanceConfig(rackId, rackLabel, face, compact))
        update(context, id)
        // Defensive fallback for the brand-new-widget-placement case - see
        // NyetboxGlanceWidget.saveConfig's identical comment and WidgetRefreshWorker.
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

private data class RackViewBlock(val deviceId: Int?, val slots: List<RackElevationEntity>)

/** Merges contiguous same-device slots into one visual block - same logic as GenericDetailRack's. */
private fun mergeRackViewSlots(slots: List<RackElevationEntity>): List<RackViewBlock> {
    val blocks = mutableListOf<RackViewBlock>()
    slots.forEach { slot ->
        val current = blocks.lastOrNull()
        if (current != null && current.deviceId == slot.deviceId) {
            blocks[blocks.lastIndex] = current.copy(slots = current.slots + slot)
        } else {
            blocks += RackViewBlock(slot.deviceId, listOf(slot))
        }
    }
    return blocks
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp))
    }
}

@Composable
private fun RackViewRow(context: Context, block: RackViewBlock) {
    val firstSlot = block.slots.first()
    val lastSlot = block.slots.last()
    val deviceId = block.deviceId
    val label =
        if (firstSlot.slotName == lastSlot.slotName) {
            firstSlot.slotName
        } else {
            "${firstSlot.slotName}–${lastSlot.slotName}"
        }
    val blockHeight = maxOf(HEIGHT_PER_SLOT * block.slots.size, MIN_BLOCK_HEIGHT)

    Row(
        modifier = GlanceModifier.fillMaxWidth().height(blockHeight).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            modifier = GlanceModifier.width(SLOT_LABEL_WIDTH),
        )
        var chipModifier: GlanceModifier =
            GlanceModifier.defaultWeight()
                .height(blockHeight)
                .background(
                    if (deviceId != null) rackDeviceColor(deviceId) else GlanceTheme.colors.surfaceVariant
                )
                .cornerRadius(6.dp)
                .padding(horizontal = 8.dp)
        if (deviceId != null) {
            chipModifier =
                chipModifier.clickable(
                    actionStartActivity(
                        Intent(context, MainActivity::class.java).apply {
                            putGestureExtras(
                                GestureAction.DetailSpecific,
                                GestureTarget(
                                    endpointPath = DEVICES_ENDPOINT_PATH,
                                    label = firstSlot.deviceDisplay ?: "Device #$deviceId",
                                    id = deviceId,
                                ),
                            )
                        }
                    )
                )
        }
        Box(modifier = chipModifier, contentAlignment = Alignment.CenterStart) {
            if (deviceId != null) {
                Text(
                    text = firstSlot.deviceDisplay ?: "Device #$deviceId",
                    maxLines = 1,
                    style = TextStyle(color = RACK_DEVICE_TEXT_COLOR, fontSize = 12.sp),
                )
            }
        }
    }
}
