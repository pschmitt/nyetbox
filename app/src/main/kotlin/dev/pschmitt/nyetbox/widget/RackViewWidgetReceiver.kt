package dev.pschmitt.nyetbox.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class RackViewWidgetReceiver : GlanceAppWidgetReceiver() {

    @Inject lateinit var widget: RackViewGlanceWidget

    override val glanceAppWidget: GlanceAppWidget
        get() = widget
}
