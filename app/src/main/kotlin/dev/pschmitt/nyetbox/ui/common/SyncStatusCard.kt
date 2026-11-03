package dev.pschmitt.nyetbox.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pschmitt.nyetbox.sync.SyncOverview
import dev.pschmitt.nyetbox.sync.SyncProgress
import dev.pschmitt.nyetbox.sync.notificationSubText
import dev.pschmitt.nyetbox.ui.sync.compactLine
import dev.pschmitt.nyetbox.ui.sync.detailLines
import dev.pschmitt.nyetbox.ui.sync.headline
import dev.pschmitt.nyetbox.ui.sync.headlineIcon
import dev.pschmitt.nyetbox.ui.sync.isProblem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * The dashboard/settings sync status card (NBC-455): what sync is doing right now, why it is
 * waiting, how and when the last run ended (and with which error), and when the next background
 * sync is due - driven by [SyncOverview], so it reflects the persistent sync history and
 * WorkManager's real state rather than just "Synced"/a spinner.
 *
 * While syncing it shows the live step (the same text as the system notification, NBC-370) and a
 * progress bar.
 */
@Composable
fun SyncStatusCard(
    overview: SyncOverview,
    lastSuccessfulSyncAt: Long?,
    modifier: Modifier = Modifier,
    offlineMode: Boolean = false,
    nowMillis: Long = rememberSyncClock(),
    onShowDetails: (() -> Unit)? = null,
    detailed: Boolean = false,
) {
    val problem = !overview.isSyncing && overview.lastRun?.outcome?.isProblem() == true
    NyetboxCard(
        modifier =
            modifier.fillMaxWidth().let {
                if (onShowDetails != null) it.clickable(onClick = onShowDetails) else it
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (overview.isSyncing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    overview.headlineIcon(),
                    contentDescription = null,
                    tint =
                        if (problem) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(overview.headline(), style = MaterialTheme.typography.titleMedium)
                val progress = overview.progress
                if (overview.isSyncing && progress != null) {
                    Text(
                        progress.notificationSubText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = {
                            progress.step.toFloat() / progress.totalSteps.coerceAtLeast(1)
                        },
                        // Capped so it reads as a compact gauge rather than a full-width rule.
                        modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(),
                    )
                }
                // The dashboard/settings card stays to one quiet line; the diagnostics screen
                // asks for the full breakdown.
                val lines =
                    if (detailed) {
                        overview.detailLines(lastSuccessfulSyncAt, nowMillis, offlineMode)
                    } else {
                        listOfNotNull(
                            overview.compactLine(lastSuccessfulSyncAt, nowMillis, offlineMode)
                        )
                    }
                if (detailed && overview.isSyncing && lines.isNotEmpty())
                    Spacer(Modifier.height(6.dp))
                lines.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (detailed) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A clock that ticks every few seconds, so relative times ("5m ago") stay current. */
@Composable
internal fun rememberSyncClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The card's headline: the current sync step's message while syncing, else "Synced". */
internal fun syncStatusHeadline(isSyncing: Boolean, syncProgress: SyncProgress?): String =
    when {
        !isSyncing -> "Synced"
        syncProgress != null -> syncProgress.message
        else -> "Syncing…"
    }

/**
 * The card's secondary line: the same "Step X of Y · N of M items" detail shown in the system
 * notification (see [notificationSubText]) while syncing, else the usual last-synced timestamp.
 */
internal fun syncStatusSubText(
    isSyncing: Boolean,
    syncProgress: SyncProgress?,
    lastSuccessfulSyncAt: Long?,
    nowMillis: Long,
): String =
    if (isSyncing && syncProgress != null) {
        syncProgress.notificationSubText()
    } else {
        formatRelativeSyncTime(lastSuccessfulSyncAt, nowMillis)
    }

internal fun formatRelativeSyncTime(lastSuccessfulSyncAt: Long?, nowMillis: Long): String {
    if (lastSuccessfulSyncAt == null) return "Never synced yet"
    val deltaMillis = (nowMillis - lastSuccessfulSyncAt).coerceAtLeast(0)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMillis)
    val hours = TimeUnit.MILLISECONDS.toHours(deltaMillis)
    val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
    return when {
        minutes < 1 -> "Last synced just now"
        minutes < 60 -> "Last synced ${minutes}m ago"
        hours < 24 -> "Last synced ${hours}h ago"
        days < 7 -> "Last synced ${days}d ago"
        else ->
            "Last synced on " +
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(lastSuccessfulSyncAt))
    }
}
