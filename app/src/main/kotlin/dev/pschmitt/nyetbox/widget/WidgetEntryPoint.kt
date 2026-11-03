package dev.pschmitt.nyetbox.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pschmitt.nyetbox.NyetboxApp

/**
 * Hands the Hilt-provided Glance widgets to their receivers without field injection.
 *
 * `@AndroidEntryPoint` only injects a receiver from inside `onReceive()`, but Glance 1.2's
 * `updateAll()` also creates receiver instances itself and reads [glanceAppWidget] straight away.
 * On such an instance the old `@Inject lateinit var widget` was never set, so every widget refresh
 * crashed with "lateinit property widget has not been initialized" - and because the refresh runs
 * at the end of [dev.pschmitt.nyetbox.sync.SyncWorker], every sync was marked as failed (NBC-454).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun nyetboxGlanceWidget(): NyetboxGlanceWidget

    fun rackViewGlanceWidget(): RackViewGlanceWidget

    companion object {
        fun get(): WidgetEntryPoint =
            EntryPointAccessors.fromApplication(
                NyetboxApp.instance,
                WidgetEntryPoint::class.java,
            )
    }
}
