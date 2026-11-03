package dev.pschmitt.nyetbox.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What asked for a sync run (NBC-455) - shown in the sync history and diagnostics. */
@Serializable
enum class SyncTrigger(val label: String) {
    /** The recurring background sync. */
    Periodic("Background sync"),

    /** The one-off refresh queued when the app process starts. */
    Startup("App launch"),

    /** An explicit "Sync now"/pull-to-refresh/"Retry" from the user. */
    Manual("Sync now"),

    /** An incidental refresh, e.g. after an edit or a gesture shortcut. */
    Auto("Automatic refresh"),
}

/** How a sync run ended, or that it is still running (NBC-455). */
@Serializable
enum class SyncRunOutcome {
    Running,
    Succeeded,
    SucceededWithWarnings,
    /** Skipped because the previous pass was recent and nothing is queued (NBC-427). */
    UpToDate,
    /** Failed; WorkManager will try again after a backoff. */
    Retrying,
    Failed,
    /** Not started because of a start condition (Battery Saver, low battery). */
    Deferred,
    /** Stopped by WorkManager or the OS before it finished; rescheduled automatically. */
    Stopped,
}

/** One entry of the sync history (NBC-455). */
@Serializable
data class SyncRunRecord(
    val id: String,
    val trigger: SyncTrigger,
    val serverName: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val outcome: SyncRunOutcome,
    val attempt: Int = 1,
    val isFullSync: Boolean? = null,
    val itemsRefreshed: Int? = null,
    val message: String? = null,
    val warnings: List<String> = emptyList(),
    /** How many consecutive deferrals this (coalesced) [SyncRunOutcome.Deferred] entry covers. */
    val repeatCount: Int = 1,
) {
    val durationMillis: Long?
        get() = finishedAt?.let { (it - startedAt).coerceAtLeast(0) }
}

/**
 * Persistent, capped log of sync runs (NBC-455), so "is sync working at all?" has a real answer in
 * release builds too, where nothing is logged to logcat. One entry per run, kept in its own small
 * SharedPreferences file rather than the per-server Room cache: it must survive cache resets and
 * server switches (each entry names its server), and it is tiny.
 *
 * Writes go through a lock and are applied asynchronously; reads come from [runs].
 */
@Singleton
class SyncHistoryRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val runListSerializer = ListSerializer(SyncRunRecord.serializer())

    private val _runs = MutableStateFlow(load())

    /** Newest first. */
    val runs: StateFlow<List<SyncRunRecord>> = _runs.asStateFlow()

    /** Records the start of a run and returns its id for [finish]. */
    fun start(trigger: SyncTrigger, attempt: Int, serverName: String?, now: Long): String {
        val id = UUID.randomUUID().toString()
        update { runs ->
            listOf(
                SyncRunRecord(
                    id = id,
                    trigger = trigger,
                    serverName = serverName,
                    startedAt = now,
                    outcome = SyncRunOutcome.Running,
                    attempt = attempt,
                )
            ) + runs
        }
        return id
    }

    fun finish(
        id: String,
        outcome: SyncRunOutcome,
        now: Long,
        isFullSync: Boolean? = null,
        itemsRefreshed: Int? = null,
        message: String? = null,
        warnings: List<String> = emptyList(),
    ) {
        update { runs ->
            runs.map { run ->
                if (run.id != id) run
                else
                    run.copy(
                        finishedAt = now,
                        outcome = outcome,
                        isFullSync = isFullSync ?: run.isFullSync,
                        itemsRefreshed = itemsRefreshed ?: run.itemsRefreshed,
                        message = message,
                        warnings = warnings.take(MAX_WARNINGS_PER_RUN),
                    )
            }
        }
    }

    /**
     * Records that a run did not start. Consecutive deferrals for the same trigger and reason are
     * coalesced into one entry (with a [SyncRunRecord.repeatCount]), so WorkManager's retries while
     * Battery Saver stays on don't flood the history.
     */
    fun recordDeferred(trigger: SyncTrigger, reason: String, serverName: String?, now: Long) {
        update { runs ->
            val latest = runs.firstOrNull()
            if (
                latest != null &&
                    latest.outcome == SyncRunOutcome.Deferred &&
                    latest.trigger == trigger &&
                    latest.message == reason
            ) {
                listOf(latest.copy(finishedAt = now, repeatCount = latest.repeatCount + 1)) +
                    runs.drop(1)
            } else {
                listOf(
                    SyncRunRecord(
                        id = UUID.randomUUID().toString(),
                        trigger = trigger,
                        serverName = serverName,
                        startedAt = now,
                        finishedAt = now,
                        outcome = SyncRunOutcome.Deferred,
                        message = reason,
                    )
                ) + runs
            }
        }
    }

    /**
     * Marks entries still flagged [SyncRunOutcome.Running] as stopped - used on process start, when
     * no sync can be running yet, so a run killed together with its process doesn't show as running
     * forever.
     */
    fun markInterruptedRuns(now: Long) {
        update { runs ->
            runs.map { run ->
                if (run.outcome != SyncRunOutcome.Running) run
                else
                    run.copy(
                        finishedAt = now,
                        outcome = SyncRunOutcome.Stopped,
                        message = "Interrupted (the app was closed or restarted)",
                    )
            }
        }
    }

    fun clear() {
        update { emptyList() }
    }

    private fun update(transform: (List<SyncRunRecord>) -> List<SyncRunRecord>) {
        synchronized(lock) {
            val updated = transform(_runs.value).take(MAX_RUNS)
            _runs.value = updated
            prefs
                .edit()
                .putString(KEY_RUNS, json.encodeToString(runListSerializer, updated))
                .apply()
        }
    }

    private fun load(): List<SyncRunRecord> =
        prefs.getString(KEY_RUNS, null)?.let { stored ->
            runCatching { json.decodeFromString(runListSerializer, stored) }.getOrNull()
        } ?: emptyList()

    private companion object {
        const val PREFS_NAME = "sync_history"
        const val KEY_RUNS = "runs"
        const val MAX_RUNS = 50
        const val MAX_WARNINGS_PER_RUN = 25
    }
}
