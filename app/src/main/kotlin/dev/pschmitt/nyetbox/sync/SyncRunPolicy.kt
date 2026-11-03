package dev.pschmitt.nyetbox.sync

/**
 * When a sync runs and when it waits (NBC-454/NBC-455) - the single place that decides it, so the
 * worker, the status card and the diagnostics screen all tell the same story.
 *
 * - Every sync needs a network that matches the user's network settings (Wi-Fi only, roaming). That
 *   is a WorkManager constraint; WorkManager waits for it.
 * - An automatic sync (background, app launch, after an edit) does not start while Battery Saver is
 *   on or the system reports the battery as low. It is retried with a backoff instead. Android uses
 *   the Battery Saver trigger level as its "battery low" threshold when that level is higher than
 *   the default, so on a phone whose Battery Saver starts at 60% this means "above 60% or
 *   charging".
 * - "Sync now", pull-to-refresh and "Retry" always start right away.
 * - Once a sync has started it is never stopped because of the battery. If the app is left while it
 *   runs, it continues as a foreground service with a progress notification.
 */
object SyncRunPolicy {
    const val BATTERY_SAVER_REASON = "Battery Saver is on"
    const val BATTERY_LOW_REASON = "Battery is low"

    /** Why an automatic sync must wait, or null when it may start now. */
    fun deferralReason(powerSaveMode: Boolean, batteryLow: Boolean): String? =
        when {
            powerSaveMode -> BATTERY_SAVER_REASON
            batteryLow -> BATTERY_LOW_REASON
            else -> null
        }

    /** Plain-language summary for the diagnostics screen. */
    val summary: List<String> =
        listOf(
            "Every sync waits for a network that matches your network settings.",
            "Automatic syncs wait while Battery Saver is on or the battery is low, then retry.",
            "\"Sync now\" and pull-to-refresh always start right away.",
            "A sync that has started is never stopped because of the battery.",
            "If you leave the app during a sync, it continues with a progress notification.",
        )
}
