package dev.pschmitt.nyetbox.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.MainActivity
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.putGestureExtras
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Publishes [SettingsRepository.shortcutItems] as launcher long-press shortcuts, using
 * [ShortcutManagerCompat.setDynamicShortcuts] so the whole list is replaced atomically every time
 * it changes - reordering, adding, or removing a shortcut in Settings takes effect without the
 * user needing to relaunch the app.
 */
@Singleton
class ShortcutSyncer
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun sync() =
        withContext(Dispatchers.Default) {
            val shortcuts =
                settingsRepository.shortcutItems.value.mapIndexed { index, item ->
                    shortcutFor(index, item)
                }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }

    private fun shortcutFor(index: Int, item: NavBarItem): ShortcutInfoCompat {
        val label = item.target?.label ?: item.action.label
        val intent =
            Intent(context, MainActivity::class.java).apply {
                putGestureExtras(item.action, item.target)
            }
        return ShortcutInfoCompat.Builder(context, "shortcut_$index")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, drawableResForGestureAction(item.action)))
            .setIntent(intent)
            .setRank(index)
            .build()
    }
}
