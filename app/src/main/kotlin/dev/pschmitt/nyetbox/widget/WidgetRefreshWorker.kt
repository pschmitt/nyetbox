package dev.pschmitt.nyetbox.widget

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * A short-delayed fallback refresh, scheduled by [NyetboxGlanceWidget.saveConfig] alongside its own
 * immediate `update()` call. Confirmed via a real device: a widget being configured for the very
 * first time (before the launcher has finished placing it - i.e. still inside
 * [WidgetConfigActivity], before it returns `RESULT_OK`) silently ignores an `update()` call made
 * at that point, so the widget keeps showing its bind-time default content until the *next*
 * unrelated refresh (e.g. a sync). This worker runs a few seconds later, fully decoupled from that
 * Activity's lifecycle, by which point placement has completed and a real update takes effect.
 * Reconfiguring an *already-placed* widget doesn't need this (its own `update()` call already works
 * immediately), but scheduling it unconditionally is cheap and harmless either way.
 */
@HiltWorker
class WidgetRefreshWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val widgetUpdater: WidgetUpdater,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        widgetUpdater.updateAll()
        return Result.success()
    }
}
