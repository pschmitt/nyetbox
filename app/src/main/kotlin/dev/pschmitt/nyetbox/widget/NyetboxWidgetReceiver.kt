package dev.pschmitt.nyetbox.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NyetboxWidgetReceiver : GlanceAppWidgetReceiver() {

    @Inject lateinit var widget: NyetboxGlanceWidget

    override val glanceAppWidget: GlanceAppWidget
        get() = widget
}
