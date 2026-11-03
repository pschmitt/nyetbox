package dev.pschmitt.nyetbox.ui.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.sync.ScheduledSync
import dev.pschmitt.nyetbox.sync.SyncRunPolicy
import dev.pschmitt.nyetbox.sync.SyncRunRecord
import dev.pschmitt.nyetbox.ui.common.NyetboxCard
import dev.pschmitt.nyetbox.ui.common.SyncStatusCard
import kotlinx.coroutines.delay

/**
 * Sync diagnostics (NBC-455): the live status, the run policy, every scheduled sync request with
 * its WorkManager state, and the persistent run history - plus a shareable plain-text report, so
 * "is sync working?" can be answered from the app itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: SyncDiagnosticsViewModel = hiltViewModel(),
) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val offlineMode by viewModel.offlineMode.collectAsStateWithLifecycle()
    val lastSuccessfulSyncAt by viewModel.lastSuccessfulSyncAt.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMillis = System.currentTimeMillis()
        }
    }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            snackbarMessage = null
        }
    }
    val report = {
        overview.report(
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            lastSuccessfulSyncAt = lastSuccessfulSyncAt,
            nowMillis = System.currentTimeMillis(),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, report())
                            snackbarMessage = "Sync report copied"
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy report")
                    }
                    IconButton(onClick = { shareReport(context, report()) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share report")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SyncStatusCard(
                    overview = overview,
                    lastSuccessfulSyncAt = lastSuccessfulSyncAt,
                    offlineMode = offlineMode,
                    nowMillis = nowMillis,
                    detailed = true,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = viewModel::syncNow,
                        enabled = !overview.isSyncing && !offlineMode,
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (overview.isSyncing) "Syncing…" else "Sync now")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.clearHistory()
                            snackbarMessage = "Sync history cleared"
                        },
                        enabled = overview.history.isNotEmpty(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear history")
                    }
                }
            }
            item { PolicyCard() }
            item { ScheduledSyncsCard(overview.scheduled, nowMillis) }
            item {
                SectionHeader(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    title = "History",
                    subtitle =
                        if (overview.history.isEmpty()) "No sync has been recorded yet"
                        else "Last ${overview.history.size} runs, newest first",
                )
            }
            items(overview.history, key = { it.id }) { run -> SyncRunRow(run, nowMillis) }
        }
    }
}

@Composable
private fun SectionHeader(icon: @Composable () -> Unit, title: String, subtitle: String? = null) {
    ListItem(
        leadingContent = icon,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

@Composable
private fun PolicyCard() {
    NyetboxCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SectionHeader(
                icon = { Icon(Icons.Default.Policy, contentDescription = null) },
                title = "When sync runs",
            )
            SyncRunPolicy.summary.forEach { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScheduledSyncsCard(scheduled: List<ScheduledSync>, nowMillis: Long) {
    NyetboxCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SectionHeader(
                icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                title = "Scheduled syncs",
                subtitle = if (scheduled.isEmpty()) "Nothing is scheduled" else null,
            )
            scheduled.forEachIndexed { index, sync ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(sync.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildList {
                                add(sync.state.displayLabel())
                                sync.nextRunAt?.let {
                                    add("next ${formatSyncRelativeTime(it, nowMillis)}")
                                }
                                if (sync.attempts > 0) add("${sync.attempts} attempt(s)")
                            }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (sync.requirements.isNotEmpty()) {
                        Text(
                            "Needs: ${sync.requirements.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    sync.lastStopReason?.let {
                        Text(
                            "Last stop: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncRunRow(run: SyncRunRecord, nowMillis: Long) {
    var expanded by rememberSaveable(run.id) { mutableStateOf(false) }
    val expandable = run.warnings.isNotEmpty() || (run.message?.length ?: 0) > 80
    NyetboxCard(
        modifier =
            Modifier.fillMaxWidth().animateContentSize().let {
                if (expandable) it.clickable { expanded = !expanded } else it
            }
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                run.outcome.icon(),
                contentDescription = null,
                tint =
                    if (run.outcome.isProblem()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${run.trigger.label}: ${run.outcome.label()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildList {
                            add(formatSyncTimestamp(run.startedAt))
                            add(formatSyncRelativeTime(run.startedAt, nowMillis))
                            if (run.attempt > 1) add("attempt ${run.attempt}")
                        }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                run.summaryLine()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                run.serverName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                run.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                    )
                }
                if (run.warnings.isNotEmpty()) {
                    if (expanded) {
                        run.warnings.forEach { warning ->
                            Text(
                                "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            "Tap to show ${run.warnings.size} warning(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.work.WorkInfo.State.displayLabel(): String =
    when (this) {
        androidx.work.WorkInfo.State.ENQUEUED -> "Waiting to run"
        androidx.work.WorkInfo.State.RUNNING -> "Running"
        androidx.work.WorkInfo.State.SUCCEEDED -> "Finished"
        androidx.work.WorkInfo.State.FAILED -> "Failed"
        androidx.work.WorkInfo.State.BLOCKED -> "Blocked"
        androidx.work.WorkInfo.State.CANCELLED -> "Cancelled"
    }

private fun copyToClipboard(context: Context, text: String) {
    context
        .getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Nyetbox sync report", text))
}

private fun shareReport(context: Context, text: String) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Nyetbox sync diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(Intent.createChooser(send, "Share sync report"))
}
