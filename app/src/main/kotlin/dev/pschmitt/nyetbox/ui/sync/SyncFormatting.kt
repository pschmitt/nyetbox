package dev.pschmitt.nyetbox.ui.sync

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import dev.pschmitt.nyetbox.sync.SyncOverview
import dev.pschmitt.nyetbox.sync.SyncRunOutcome
import dev.pschmitt.nyetbox.sync.SyncRunPolicy
import dev.pschmitt.nyetbox.sync.SyncRunRecord
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit

/** "42s", "3m 12s", "1h 5m". */
internal fun formatSyncDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis.coerceAtLeast(0))
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
        else -> "${seconds}s"
    }
}

/** "just now", "5m ago", "3h ago", "2d ago" - or "in 5m" etc. for a future [timestamp]. */
internal fun formatSyncRelativeTime(timestamp: Long, nowMillis: Long): String {
    val delta = timestamp - nowMillis
    val absolute = kotlin.math.abs(delta)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(absolute)
    val hours = TimeUnit.MILLISECONDS.toHours(absolute)
    val days = TimeUnit.MILLISECONDS.toDays(absolute)
    val amount =
        when {
            minutes < 1 -> return if (delta > 0) "in under a minute" else "just now"
            minutes < 60 -> "${minutes}m"
            hours < 24 -> "${hours}h"
            else -> "${days}d"
        }
    return if (delta > 0) "in $amount" else "$amount ago"
}

/** Absolute local date and time, for history entries. */
internal fun formatSyncTimestamp(timestamp: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))

internal fun SyncRunOutcome.label(): String =
    when (this) {
        SyncRunOutcome.Running -> "Running"
        SyncRunOutcome.Succeeded -> "Succeeded"
        SyncRunOutcome.SucceededWithWarnings -> "Succeeded with warnings"
        SyncRunOutcome.UpToDate -> "Already up to date"
        SyncRunOutcome.Retrying -> "Failed, will retry"
        SyncRunOutcome.Failed -> "Failed"
        SyncRunOutcome.Deferred -> "Waiting"
        SyncRunOutcome.Stopped -> "Stopped"
    }

internal fun SyncRunOutcome.icon(): ImageVector =
    when (this) {
        SyncRunOutcome.Running -> Icons.Default.Sync
        SyncRunOutcome.Succeeded -> Icons.Default.CheckCircle
        SyncRunOutcome.SucceededWithWarnings -> Icons.Default.WarningAmber
        SyncRunOutcome.UpToDate -> Icons.Default.CloudDone
        SyncRunOutcome.Retrying -> Icons.Default.Replay
        SyncRunOutcome.Failed -> Icons.Default.ErrorOutline
        SyncRunOutcome.Deferred -> Icons.Default.HourglassTop
        SyncRunOutcome.Stopped -> Icons.Default.PauseCircle
    }

internal fun SyncRunOutcome.isProblem(): Boolean =
    this == SyncRunOutcome.Failed ||
        this == SyncRunOutcome.Retrying ||
        this == SyncRunOutcome.SucceededWithWarnings

/** "Full sync · 42s · 1,234 items refreshed · 3 warnings". */
internal fun SyncRunRecord.summaryLine(): String =
    buildList {
            when {
                outcome == SyncRunOutcome.UpToDate -> add("Nothing to fetch")
                isFullSync == true -> add("Full sync")
                isFullSync == false -> add("Quick check")
            }
            if (outcome != SyncRunOutcome.Deferred)
                durationMillis?.let { add(formatSyncDuration(it)) }
            itemsRefreshed
                ?.takeIf { outcome != SyncRunOutcome.UpToDate }
                ?.let {
                    add(
                        if (it == 1) "1 item refreshed"
                        else "${NumberFormat.getIntegerInstance().format(it)} items refreshed"
                    )
                }
            if (warnings.isNotEmpty()) {
                add(if (warnings.size == 1) "1 warning" else "${warnings.size} warnings")
            }
            if (outcome == SyncRunOutcome.Deferred && repeatCount > 1) add("$repeatCount times")
        }
        .joinToString(" · ")

/** Headline for the status card: what sync is doing or how it last went. */
internal fun SyncOverview.headline(): String =
    when {
        isSyncing -> progress?.message ?: "Syncing…"
        waitingReason != null -> "Waiting to sync"
        lastRun == null -> "Not synced yet"
        else ->
            when (lastRun.outcome) {
                SyncRunOutcome.Failed -> "Last sync failed"
                SyncRunOutcome.Retrying -> "Last sync failed, retrying"
                SyncRunOutcome.SucceededWithWarnings -> "Synced with warnings"
                SyncRunOutcome.Stopped -> "Last sync was interrupted"
                else -> "Synced"
            }
    }

/** Icon matching [headline]. */
internal fun SyncOverview.headlineIcon(): ImageVector =
    when {
        isSyncing -> Icons.Default.Sync
        waitingReason != null -> Icons.Default.HourglassTop
        lastRun == null -> Icons.Default.Sync
        else -> lastRun.outcome.icon()
    }

/**
 * Up to three detail lines for the status card below the headline, most important first: the live
 * step, why sync is waiting, how and when the last run went, and when the next automatic sync is
 * due.
 */
internal fun SyncOverview.detailLines(
    lastSuccessfulSyncAt: Long?,
    nowMillis: Long,
    offlineMode: Boolean,
): List<String> = buildList {
    if (offlineMode) {
        add("Offline mode is on, sync is paused")
        return@buildList
    }
    if (isSyncing) {
        currentRun?.let { run ->
            add(
                "${run.trigger.label} · running for ${formatSyncDuration(nowMillis - run.startedAt)}"
            )
        }
        return@buildList
    }
    waitingReason?.let { reason ->
        val since =
            waitingSince?.let { " (since ${formatSyncRelativeTime(it, nowMillis)})" }.orEmpty()
        add("$reason$since")
    }
    lastRun?.let { run ->
        val whenText = formatSyncRelativeTime(run.finishedAt ?: run.startedAt, nowMillis)
        val detail = run.summaryLine().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        add("${run.trigger.label} $whenText$detail")
        if (run.outcome == SyncRunOutcome.Failed || run.outcome == SyncRunOutcome.Retrying) {
            run.message?.let { add(it) }
        }
    }
    if (lastRun == null || lastRun.outcome != SyncRunOutcome.Succeeded) {
        lastSuccessfulSyncAt?.let {
            add("Last complete sync ${formatSyncRelativeTime(it, nowMillis)}")
        }
    }
    nextAutomaticSyncAt?.let {
        add("Next background sync ${formatSyncRelativeTime(it, nowMillis)}")
    }
}

/**
 * The single line under the card headline on the dashboard and in settings - the one thing worth
 * knowing right now. While syncing the live step text is shown instead (by the card itself), so
 * this returns null then. Everything else lives on the diagnostics screen.
 */
internal fun SyncOverview.compactLine(
    lastSuccessfulSyncAt: Long?,
    nowMillis: Long,
    offlineMode: Boolean,
): String? {
    if (offlineMode) return "Offline mode is on"
    if (isSyncing) return null
    waitingReason?.let {
        return it
    }
    val run = lastRun
    if (
        run != null &&
            (run.outcome == SyncRunOutcome.Failed || run.outcome == SyncRunOutcome.Retrying)
    ) {
        return run.message ?: formatSyncRelativeTime(run.finishedAt ?: run.startedAt, nowMillis)
    }
    val lastSyncedAt = run?.finishedAt ?: lastSuccessfulSyncAt ?: return "Never synced yet"
    val warnings =
        run?.warnings?.size?.takeIf {
            run.outcome == SyncRunOutcome.SucceededWithWarnings && it > 0
        }
    return buildString {
        append(formatSyncRelativeTime(lastSyncedAt, nowMillis).replaceFirstChar { it.uppercase() })
        warnings?.let { append(if (it == 1) " · 1 warning" else " · $it warnings") }
    }
}

/** Plain-text report for sharing/copying from the diagnostics screen. */
internal fun SyncOverview.report(
    appVersion: String,
    lastSuccessfulSyncAt: Long?,
    nowMillis: Long,
): String = buildString {
    appendLine("Nyetbox sync diagnostics")
    appendLine("App version: $appVersion")
    appendLine("Generated: ${formatSyncTimestamp(nowMillis)}")
    appendLine("Status: ${headline()}")
    detailLines(lastSuccessfulSyncAt, nowMillis, offlineMode = false).forEach {
        appendLine("  $it")
    }
    appendLine()
    appendLine("Policy:")
    SyncRunPolicy.summary.forEach { appendLine("  - $it") }
    appendLine()
    appendLine("Scheduled syncs:")
    if (scheduled.isEmpty()) appendLine("  (none)")
    scheduled.forEach { sync ->
        append("  ${sync.label}: ${sync.state}")
        sync.nextRunAt?.let { append(", next ${formatSyncTimestamp(it)}") }
        if (sync.attempts > 0) append(", ${sync.attempts} attempt(s)")
        if (sync.requirements.isNotEmpty()) append(", needs ${sync.requirements.joinToString()}")
        sync.lastStopReason?.let { append(", last stop: $it") }
        appendLine()
    }
    appendLine()
    appendLine("History (newest first):")
    if (history.isEmpty()) appendLine("  (empty)")
    history.forEach { run ->
        append(
            "  ${formatSyncTimestamp(run.startedAt)} ${run.trigger.label}: ${run.outcome.label()}"
        )
        run.summaryLine().takeIf { it.isNotEmpty() }?.let { append(" ($it)") }
        if (run.attempt > 1) append(" [attempt ${run.attempt}]")
        appendLine()
        run.message?.let { appendLine("      $it") }
        run.warnings.forEach { appendLine("      - $it") }
    }
}
