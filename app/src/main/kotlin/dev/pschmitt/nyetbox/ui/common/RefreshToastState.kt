package dev.pschmitt.nyetbox.ui.common

internal const val REFRESH_QUEUED_TOAST = "Sync queued"

internal fun shouldShowRefreshQueuedToast(
    showConfirmation: Boolean,
    offlineMode: Boolean,
): Boolean = showConfirmation && !offlineMode

/**
 * Terminal toast for a targeted refresh - [primarySucceeded] is the main object's own fetch,
 * [failureCount] the number of linked-object syncs (device type, interfaces, IP addresses, ...)
 * that failed alongside it. A failed primary fetch always wins, since nothing else was meaningfully
 * scoped without it.
 */
internal fun targetedSyncToast(primarySucceeded: Boolean, failureCount: Int): String =
    when {
        !primarySucceeded -> "Sync failed"
        failureCount == 0 -> "Sync complete"
        failureCount == 1 -> "Synced with 1 issue"
        else -> "Synced with $failureCount issues"
    }
