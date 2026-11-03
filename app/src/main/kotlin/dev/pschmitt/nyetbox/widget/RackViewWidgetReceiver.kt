package dev.pschmitt.nyetbox.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** See [WidgetEntryPoint] for why this doesn't use `@AndroidEntryPoint` field injection. */
class RackViewWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget by lazy {
        WidgetEntryPoint.get().rackViewGlanceWidget()
    }
}
