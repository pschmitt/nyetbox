package dev.pschmitt.nyetbox.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A widget instance's full configuration - see [NyetboxGlanceWidget]. */
data class WidgetInstanceConfig(
    val content: WidgetContent = WidgetContent.Stats,
    val actions: List<NavBarItem> = emptyList(),
    val compact: Boolean = false,
    val showActionLabels: Boolean = true,
)

/**
 * In-memory overlay on top of each widget instance's persisted config (itself stored via
 * [PreferencesGlanceStateDefinition]), keyed by the platform app widget id. See
 * [NyetboxGlanceWidget]'s class doc for why this exists: a plain one-shot `getAppWidgetState` read
 * in `provideGlance` is invisible to a composition session that's already running, since
 * `update()`/`updateAll()` don't restart `provideGlance` for a live session. Publishing the
 * freshly-saved config here too, and having the composition `collectAsState()` this instead of a
 * one-shot read, makes a reconfigure take effect immediately either way.
 */
@Singleton
class WidgetConfigStore @Inject constructor() {
    private val _configs = MutableStateFlow<Map<Int, WidgetInstanceConfig>>(emptyMap())
    val configs: StateFlow<Map<Int, WidgetInstanceConfig>> = _configs

    fun publish(appWidgetId: Int, config: WidgetInstanceConfig) {
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
            WidgetInstanceConfig(
                content = WidgetContent.fromStorageOrNull(prefs[KEY_CONTENT]) ?: WidgetContent.Stats,
                actions = decodeWidgetActions(prefs[KEY_ACTIONS]),
                compact = prefs[KEY_COMPACT] ?: false,
                showActionLabels = prefs[KEY_SHOW_ACTION_LABELS] ?: true,
            ),
        )
    }
}
