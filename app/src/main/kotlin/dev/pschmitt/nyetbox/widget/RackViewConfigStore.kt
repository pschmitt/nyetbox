package dev.pschmitt.nyetbox.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.pschmitt.nyetbox.data.repository.RackFace
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A rack-view widget instance's full configuration - see [RackViewGlanceWidget]. */
data class RackViewInstanceConfig(
    val rackId: Int? = null,
    val rackLabel: String = "",
    val face: RackFace = RackFace.FRONT,
    val compact: Boolean = false,
)

/**
 * In-memory overlay on top of each rack-view widget instance's persisted config, keyed by the
 * platform app widget id - the same pattern as [WidgetConfigStore], for the same reason: a
 * one-shot `getAppWidgetState` read in `provideGlance` is invisible to a composition session
 * that's already running, since `update()`/`updateAll()` don't restart `provideGlance` for a live
 * session. See [WidgetConfigStore]'s doc for the full explanation.
 */
@Singleton
class RackViewConfigStore @Inject constructor() {
    private val _configs = MutableStateFlow<Map<Int, RackViewInstanceConfig>>(emptyMap())
    val configs: StateFlow<Map<Int, RackViewInstanceConfig>> = _configs

    fun publish(appWidgetId: Int, config: RackViewInstanceConfig) {
        _configs.update { it + (appWidgetId to config) }
    }

    /** Drops a deleted widget instance's cached config - platform app widget ids can be reused. */
    fun remove(appWidgetId: Int) {
        _configs.update { it - appWidgetId }
    }

    /** Seeds the in-memory cache from persisted state, if this widget id isn't cached yet. */
    suspend fun ensureLoaded(context: Context, id: GlanceId, appWidgetId: Int) {
        if (_configs.value.containsKey(appWidgetId)) return
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        publish(
            appWidgetId,
            RackViewInstanceConfig(
                rackId = prefs[KEY_RACK_ID],
                rackLabel = prefs[KEY_RACK_LABEL] ?: "",
                face = if (prefs[KEY_RACK_FACE] == RackFace.REAR.apiValue) RackFace.REAR else RackFace.FRONT,
                compact = prefs[KEY_RACK_COMPACT] ?: false,
            ),
        )
    }
}
