package dev.pschmitt.nyetbox.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun updateAll() {
        widget.updateAll(context)
        rackViewWidget.updateAll(context)
    }
}
