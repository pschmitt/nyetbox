package dev.pschmitt.nyetbox.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Single seam every sync/offline-mode-change call site refreshes the widget through, instead of
 * each one independently knowing how to talk to Glance - a no-op if no widget instance is placed.
 */
@Singleton
class WidgetUpdater
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val widget: NyetboxGlanceWidget,
    private val rackViewWidget: RackViewGlanceWidget,
) {

    /**
     * Best-effort: a widget that can't be refreshed must never turn the sync (or whatever else
     * called this) into a failure - it did once, NBC-454.
     */
    suspend fun updateAll() {
        try {
            widget.updateAll(context)
            rackViewWidget.updateAll(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Widget refresh failed")
        }
    }
}
