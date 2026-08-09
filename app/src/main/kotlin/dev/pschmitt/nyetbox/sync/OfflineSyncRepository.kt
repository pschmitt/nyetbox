package dev.pschmitt.nyetbox.sync

import dev.pschmitt.nyetbox.data.db.CacheDatabaseManager
import dev.pschmitt.nyetbox.data.repository.CustomFieldRepository
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DeviceTypeRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.FileDownloadRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.ImageAttachmentRepository
import dev.pschmitt.nyetbox.data.repository.LastSyncSummary
import dev.pschmitt.nyetbox.data.repository.OfflineAttachment
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.repository.RackElevationRepository
import dev.pschmitt.nyetbox.data.repository.RackFace
import dev.pschmitt.nyetbox.data.repository.ReconciliationSummary
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.SvgDiagramRepository
import dev.pschmitt.nyetbox.data.repository.TopologyRepository
import dev.pschmitt.nyetbox.data.repository.cableTraceStartTarget
import dev.pschmitt.nyetbox.data.repository.cableTraceSvgCacheKey
import dev.pschmitt.nyetbox.data.repository.rackElevationSvgCacheKey
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException
import timber.log.Timber

data class OfflineSyncSummary(
    val devices: Int,
    val genericObjects: Int,
    val durableAttachments: Int,
    val reconciliation: ReconciliationSummary = ReconciliationSummary(),
)

data class SyncProgress(
    val message: String,
    val step: Int,
    val totalSteps: Int,
    val itemLabel: String? = null,
    val itemCompleted: Int? = null,
    val itemTotal: Int? = null,
    /**
     * Whether this step belongs to a full (24h-interval/forced) pass rather than a routine
     * incremental one (NBC-435) - surfaced so the UI/notification can tell the user "this is a
     * quick check" from "this is the full reconciliation pass" instead of showing identical text
     * either way.
     */
    val isFullSync: Boolean = false,
)

internal fun SyncProgress.itemProgressText(): String? =
    if (itemLabel != null && itemCompleted != null && itemTotal != null && itemTotal >= 0) {
        "$itemCompleted of $itemTotal $itemLabel"
    } else {
        null
    }

internal fun SyncProgress.notificationSubText(): String = buildList {
    add(if (isFullSync) "Full sync" else "Quick sync")
    add("${step.coerceIn(0, totalSteps.coerceAtLeast(1))}/${totalSteps.coerceAtLeast(1)}")
    itemProgressText()?.let(::add)
}
    .joinToString(" · ")

internal fun SyncProgress.notificationText(): String = buildString {
    append(message)
    itemProgressText()?.let {
        append('\n')
        append(it)
    }
}

/**
 * How long a cache is allowed to rely on incremental (`last_updated`-filtered) syncs before the
 * next sync forces a full, unfiltered pass. Incremental syncs can't see objects deleted on the
 * server since they only ask for what changed - a periodic full pass is what actually reconciles
 * those deletions (see the pruning step in [OfflineSyncRepository.syncAllLocked]).
 */
private val FULL_SYNC_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(24)

/**
 * How recently a sync pass must have completed successfully for a *new* pass to short-circuit as a
 * no-op (NBC-427) - guards against the missed-periodic-work-plus-startup-work race (WorkManager
 * starting an overdue `PeriodicWorkRequest` the moment the process comes up, then NBC-370's delayed
 * startup work firing 10s later) and against a plain rapid reopen, both of which otherwise run the
 * entire ~600-request pass again for data that cannot have changed in the interim.
 */
private val SYNC_FRESHNESS_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(5)

/**
 * Generic-model endpoints excluded from the per-model sync loop even though
 * [dev.pschmitt.nyetbox.data.repository.DirectoryRepository] discovers them as ordinary paginated
 * collections (NBC-429). `api/core/object-changes/` has no `last_updated` field (it has `time`), so
 * [dev.pschmitt.nyetbox.data.repository.GenericObjectRepository.lastUpdatedWatermark] is
 * permanently null for it and every pass falls back to a full, unfiltered refetch of the entire
 * changelog - the fattest rows NetBox serves, and already redundant with
 * [DashboardRepository.refreshChangelog]'s own 25-most-recent fetch. `api/extras/tagged-objects/`
 * has the same no-`last_updated` shape and the same full-refetch cost, but is deliberately left
 * alone here: its rows back tag screens and were not confirmed safe to drop from this sync pass.
 */
private val SYNC_EXCLUDED_ENDPOINTS = setOf("api/core/object-changes/")

/** NBC-427: whether a new sync pass can be skipped entirely as a no-op. */
internal fun shouldSkipSyncPass(
    forceFullSync: Boolean,
    now: Long,
    lastSuccessfulSyncAt: Long?,
    hasQueuedMutations: Boolean,
): Boolean =
    !forceFullSync &&
        now - (lastSuccessfulSyncAt ?: 0L) < SYNC_FRESHNESS_WINDOW_MILLIS &&
        !hasQueuedMutations

/**
 * NBC-429: whether [endpointPath] can never be synced incrementally, see [SYNC_EXCLUDED_ENDPOINTS].
 */
internal fun isSyncExcluded(endpointPath: String): Boolean = endpointPath in SYNC_EXCLUDED_ENDPOINTS

/** NBC-430: whether the model directory needs a real re-discovery this pass. */
internal fun shouldRediscoverDirectory(isFullSyncPass: Boolean, cachedModelCount: Int): Boolean =
    isFullSyncPass || cachedModelCount == 0

/** NBC-431: whether the topology export needs re-downloading this pass. */
internal fun shouldRefreshTopology(
    isFullSyncPass: Boolean,
    changedDeviceCount: Int,
    changedCableCount: Int,
): Boolean = isFullSyncPass || changedDeviceCount > 0 || changedCableCount > 0

/**
 * NBC-432/433: whether a rack's elevation and rack-face SVG diagrams need refreshing this pass -
 * shared by both, since an SVG rendering of the elevation can't change unless the elevation itself
 * could have.
 */
internal fun shouldRefreshRackData(
    isFullSyncPass: Boolean,
    rackSyncedAt: Long,
    passStartedAt: Long,
    changedDeviceCountInRack: Int,
): Boolean = isFullSyncPass || rackSyncedAt >= passStartedAt || changedDeviceCountInRack > 0

/** NBC-433: whether a cable's trace SVG needs refreshing this pass. */
internal fun shouldRefreshCableTraceSvg(
    isFullSyncPass: Boolean,
    cableSyncedAt: Long,
    passStartedAt: Long,
): Boolean = isFullSyncPass || cableSyncedAt >= passStartedAt

/** Coordinates the complete cache-first sync used by manual and background refreshes. */
@Singleton
class OfflineSyncRepository
@Inject
constructor(
    private val deviceRepository: DeviceRepository,
    private val dashboardRepository: DashboardRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val directoryRepository: DirectoryRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val deviceTypeRepository: DeviceTypeRepository,
    private val imageAttachmentRepository: ImageAttachmentRepository,
    private val rackElevationRepository: RackElevationRepository,
    private val svgDiagramRepository: SvgDiagramRepository,
    private val fileDownloadRepository: FileDownloadRepository,
    private val settingsRepository: SettingsRepository,
    private val pendingEditRepository: PendingEditRepository,
    private val topologyRepository: TopologyRepository,
    private val syncIssueReporter: SyncIssueReporter,
    private val cacheDatabaseManager: CacheDatabaseManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun syncAll(
        forceFullSync: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): Result<OfflineSyncSummary> = cacheDatabaseManager.withActiveServer {
        syncAllLocked(forceFullSync, onProgress)
    }

    private suspend fun syncAllLocked(
        forceFullSync: Boolean = false,
        onProgress: (SyncProgress) -> Unit = {},
    ): Result<OfflineSyncSummary> {
        // Everything upserted from this point on is stamped with a syncedAt >= passStartedAt, so
        // any row still older than it once a full fetch of its endpoint succeeds is something the
        // server no longer has - see the pruning calls below.
        val passStartedAt = System.currentTimeMillis()
        // NBC-427: skip the entire pass when the last one succeeded recently enough that nothing
        // could plausibly have changed server-side, and there's no locally-queued edit that only a
        // real pass would upload. Returning here - before isFullSyncPass/runCatching/onProgress are
        // even touched - means a skipped pass emits no SyncProgress steps and, critically, does NOT
        // call recordSuccessfulSync(): only a pass that actually ran can move the freshness window
        // forward, so frequent reopens can't compound into "never syncs again." A failed/warned
        // pass also leaves lastSuccessfulSyncAt unset (see the bottom of this function), so the
        // next pass always still runs - the short-circuit is only ever safe to take right after a
        // fully clean pass.
        if (
            shouldSkipSyncPass(
                forceFullSync = forceFullSync,
                now = passStartedAt,
                lastSuccessfulSyncAt = settingsRepository.lastSuccessfulSyncAt.value,
                hasQueuedMutations = pendingEditRepository.hasQueuedMutations(),
            )
        ) {
            return Result.success(
                OfflineSyncSummary(devices = 0, genericObjects = 0, durableAttachments = 0)
            )
        }
        val isFullSyncPass =
            forceFullSync ||
                (passStartedAt - settingsRepository.lastFullSyncAt > FULL_SYNC_INTERVAL_MILLIS)
        val concurrency = settingsRepository.syncConcurrency.value

        val failureCount = AtomicInteger(0)
        val retryableFailure = AtomicReference<Throwable?>(null)
        var step = 0
        var totalSteps = 8 + if (settingsRepository.syncAttachmentsToDisk.value) 2 else 0

        fun reportProgress(message: String): SyncProgress {
            step++
            return SyncProgress(message, step, totalSteps, isFullSync = isFullSyncPass)
                .also(onProgress)
        }

        fun recordFailure(scope: String, error: Throwable) {
            failureCount.incrementAndGet()
            syncIssueReporter.report(
                "$scope: ${error.message ?: error::class.simpleName ?: "failed"}"
            )
            if (error.isRetryableSyncFailure()) retryableFailure.compareAndSet(null, error)
        }

        val result = runCatching {
            // Resolve queued edits before the normal cache refresh can replace their local view.
            reportProgress("Uploading queued edits…")
            val pendingResult = pendingEditRepository.syncPending()
            pendingResult.retryableFailure?.let { recordFailure("Queued mutation sync", it) }
            reportProgress("Syncing dashboard data…")
            dashboardRepository.refresh().onFailure { recordFailure("Dashboard sync", it) }
            reportProgress("Syncing custom-field definitions…")
            customFieldRepository.refresh().onFailure { recordFailure("Custom-field sync", it) }

            reportProgress("Syncing devices…")
            val (deviceResult, deviceFetchWasFull) = syncDevicesIncrementally(isFullSyncPass)
            val devices = deviceResult.getOrElse {
                recordFailure("Device sync", it)
                0
            }
            if (deviceResult.isSuccess && deviceFetchWasFull) {
                deviceRepository.pruneStale(passStartedAt)
            }

            reportProgress("Syncing device types…")
            deviceRepository
                .cachedDevices()
                .asSequence()
                .mapNotNull { it.deviceTypeId }
                .distinct()
                .toList()
                .syncConcurrently(concurrency) { deviceTypeId ->
                    // NBC-428: device-type photos rarely change, so an incremental pass only
                    // fetches ids not already cached (ensureCached); the 24h/forced full pass still
                    // re-fetches every one, catching a changed photo within a day like everything
                    // else in the incremental contract.
                    val result =
                        if (isFullSyncPass) deviceTypeRepository.refresh(deviceTypeId).map {}
                        else deviceTypeRepository.ensureCached(deviceTypeId)
                    result.onFailure { error ->
                        recordFailure("Device type $deviceTypeId sync", error)
                    }
                }

            // NBC-430: the installed-model directory changes only when a NetBox plugin is
            // installed/removed or NetBox itself is upgraded - re-discovering it every pass costs
            // ~100+ probe requests to re-learn something that almost never changes, so incremental
            // passes reuse the existing cache and only full passes (or an empty cache) re-discover.
            if (shouldRediscoverDirectory(isFullSyncPass, directoryRepository.cachedModelCount())) {
                reportProgress("Discovering NetBox models…")
                directoryRepository.refresh().onFailure { recordFailure("Directory sync", it) }
            } else {
                reportProgress("Using cached model directory…")
            }

            val models = directoryRepository.cachedModels()
            val topologyAvailable = models.any { it.appKey == TopologyRepository.PLUGIN_APP_KEY }
            totalSteps =
                8 +
                    (if (settingsRepository.syncAttachmentsToDisk.value) 2 else 0) +
                    (if (topologyAvailable) 1 else 0)

            // NBC-429: api/core/object-changes/ (and similarly-shaped endpoints) can never be
            // synced incrementally by this loop - see SYNC_EXCLUDED_ENDPOINTS - and the dashboard
            // already keeps its own small changelog cache. Prune whatever this endpoint left behind
            // from before the exclusion existed.
            val syncableModels = models.filterNot { isSyncExcluded(it.endpointPath) }
            SYNC_EXCLUDED_ENDPOINTS.forEach { excludedPath ->
                if (models.any { it.endpointPath == excludedPath }) {
                    genericObjectRepository.pruneStale(excludedPath, Long.MAX_VALUE)
                }
            }

            val modelsProgress = reportProgress("Syncing ${syncableModels.size} NetBox models…")
            val modelsCompleted = AtomicInteger(0)
            val genericObjectsTotal = AtomicInteger(0)
            // Changed-this-pass count for cables specifically, so the topology step below
            // (NBC-431) can skip re-downloading the topology export when nothing that could affect
            // it changed.
            val cablesChangedCount = AtomicInteger(0)
            // Endpoints whose full, unfiltered fetch just succeeded this pass - the only ones
            // safe to prune (an endpoint that only got an incremental fetch, or whose fetch
            // failed partway through, must keep whatever it already had cached).
            val fullySyncedEndpoints = ConcurrentHashMap.newKeySet<String>()
            syncableModels.syncConcurrently(concurrency) { model ->
                val (syncResult, wasFullFetch) =
                    syncModelIncrementally(model.endpointPath, isFullSyncPass)
                syncResult.fold(
                    onSuccess = { count ->
                        genericObjectsTotal.addAndGet(count)
                        if (model.endpointPath == NetBoxRef.CABLES_ENDPOINT_PATH) {
                            cablesChangedCount.addAndGet(count)
                        }
                        if (wasFullFetch) fullySyncedEndpoints += model.endpointPath
                    },
                    onFailure = { recordFailure("${model.endpointPath} sync", it) },
                )
                onProgress(
                    modelsProgress.copy(
                        itemLabel = "models",
                        itemCompleted = modelsCompleted.incrementAndGet(),
                        itemTotal = syncableModels.size,
                    )
                )
            }
            fullySyncedEndpoints.forEach { endpointPath ->
                genericObjectRepository.pruneStale(endpointPath, passStartedAt)
            }
            val genericObjects = genericObjectsTotal.get()

            reportProgress("Syncing rack elevations…")
            val racks = genericObjectRepository.cachedObjects(NetBoxRef.RACKS_ENDPOINT_PATH)
            // NBC-432: an elevation only changes when the rack itself changes or a device in it is
            // (re)placed/removed - both bump last_updated, which the incremental fetches above
            // already picked up (syncedAt >= passStartedAt). Computed once and shared with the
            // rack-face SVG loop below (NBC-433), which needs the exact same decision.
            val rackDataChanged = racks.associate { rack ->
                rack.id to
                    shouldRefreshRackData(
                        isFullSyncPass = isFullSyncPass,
                        rackSyncedAt = rack.syncedAt,
                        passStartedAt = passStartedAt,
                        changedDeviceCountInRack =
                            deviceRepository.countChangedInRack(rack.id, passStartedAt),
                    )
            }
            racks.syncConcurrently(concurrency) { rack ->
                val needsRefresh =
                    rackDataChanged.getValue(rack.id) || !rackElevationRepository.hasCached(rack.id)
                if (!needsRefresh) return@syncConcurrently
                rackElevationRepository.refresh(rack.id, RackFace.FRONT).onFailure {
                    recordFailure("Rack ${rack.id} front elevation", it)
                }
                rackElevationRepository.refresh(rack.id, RackFace.REAR).onFailure {
                    recordFailure("Rack ${rack.id} rear elevation", it)
                }
            }

            // Same opt-in as the attachment download pass below - these SVG diagrams are the same
            // kind of "extra, non-essential bytes" the user is choosing to pre-download for offline
            // use, not core object data.
            if (settingsRepository.syncAttachmentsToDisk.value) {
                reportProgress("Syncing rack and cable diagrams…")
                racks.syncConcurrently(concurrency) { rack ->
                    for (face in listOf(RackFace.FRONT, RackFace.REAR)) {
                        val cacheKey = rackElevationSvgCacheKey(rack.id, face)
                        // NBC-433: reuses the exact rack-changed decision NBC-432 computed above,
                        // plus always fetching a diagram this rack has no persisted copy of yet
                        // (a freshly-enabled "sync attachments to disk", or a cleared cache).
                        val needsRefresh =
                            rackDataChanged.getValue(rack.id) ||
                                !svgDiagramRepository.isCached(cacheKey)
                        if (!needsRefresh) continue
                        svgDiagramRepository
                            .refresh(
                                "api/dcim/racks/${rack.id}/elevation/?face=${face.apiValue}&render=svg",
                                cacheKey,
                            )
                            .onFailure { recordFailure("Rack ${rack.id} ${face.apiValue} SVG", it) }
                    }
                }
                val cables = genericObjectRepository.cachedObjects(NetBoxRef.CABLES_ENDPOINT_PATH)
                cables.syncConcurrently(concurrency) { cable ->
                    val cableJson = runCatching {
                        json.decodeFromString(JsonObject.serializer(), cable.json)
                    }
                        .getOrNull()
                    val target = cableJson?.let(::cableTraceStartTarget)
                    if (target != null) {
                        val (endpointPath, id) = target
                        val cacheKey = cableTraceSvgCacheKey(endpointPath, id)
                        // NBC-433: an unchanged cable (and its terminations, which bump the
                        // cable's own row in NetBox) cannot have a changed trace.
                        val needsRefresh =
                            shouldRefreshCableTraceSvg(
                                isFullSyncPass,
                                cable.syncedAt,
                                passStartedAt,
                            ) || !svgDiagramRepository.isCached(cacheKey)
                        if (!needsRefresh) return@syncConcurrently
                        svgDiagramRepository
                            .refresh("$endpointPath$id/trace/?render=svg", cacheKey)
                            .onFailure { recordFailure("Cable ${cable.id} trace SVG", it) }
                    }
                }
            }

            if (topologyAvailable) {
                // NBC-431: the topology export only reflects device/cable data, so an incremental
                // pass that changed neither can safely skip re-downloading and re-rendering the
                // (multi-MB) export. A full pass always refreshes it regardless, to also catch
                // deletions the incremental device/cable fetches above can't see.
                val topologyInputsChanged =
                    shouldRefreshTopology(isFullSyncPass, devices, cablesChangedCount.get())
                if (topologyInputsChanged) {
                    reportProgress("Syncing topology map…")
                    topologyRepository.refresh().onFailure {
                        syncIssueReporter.report("Topology sync: ${it.message ?: "failed"}")
                    }
                } else {
                    reportProgress("Topology unchanged, skipping…")
                }
            }

            val durableAttachments =
                if (settingsRepository.syncAttachmentsToDisk.value) {
                    val attachmentProgress =
                        reportProgress("Downloading cached images and documents…")
                    runCatching {
                        syncAttachments(concurrency, isFullSyncPass) { completed, total ->
                            onProgress(
                                attachmentProgress.copy(
                                    itemLabel = "images/documents",
                                    itemCompleted = completed,
                                    itemTotal = total,
                                )
                            )
                        }
                    }
                        .getOrElse {
                            recordFailure("Attachment sync", it)
                            0
                        }
                } else 0
            retryableFailure.get()?.let { throw it }
            // Only a fully clean full-sync pass is trustworthy evidence that every endpoint's
            // cache now matches the server - a partial failure leaves some endpoints unreconciled,
            // so the next periodic run (a few hours away) should try a full pass again rather than
            // waiting out the entire interval.
            if (isFullSyncPass && failureCount.get() == 0) {
                settingsRepository.lastFullSyncAt = passStartedAt
            }
            OfflineSyncSummary(
                devices = devices,
                genericObjects = genericObjects,
                durableAttachments = durableAttachments,
                reconciliation = pendingResult.reconciliation,
            )
        }
        val warnings = syncIssueReporter.drain()
        when {
            result.isFailure ->
                settingsRepository.recordSyncIssue(
                    buildString {
                        append(result.exceptionOrNull()?.message ?: "Sync failed")
                        if (warnings.isNotEmpty()) {
                            append("\n")
                            append(warnings.joinToString("\n"))
                        }
                    }
                )
            warnings.isNotEmpty() -> settingsRepository.recordSyncIssue(warnings.joinToString("\n"))
            else -> {
                settingsRepository.clearSyncIssue()
                settingsRepository.recordSuccessfulSync()
                settingsRepository.recordSyncSummary(
                    LastSyncSummary(
                        isFullSync = isFullSyncPass,
                        durationMillis = System.currentTimeMillis() - passStartedAt,
                        itemsRefreshed =
                            result.getOrNull()?.let { it.devices + it.genericObjects } ?: 0,
                    )
                )
            }
        }
        return result
    }

    private fun Throwable.isRetryableSyncFailure(): Boolean =
        generateSequence(this) { it.cause }
            .any { cause -> cause is IOException || cause is HttpException && cause.code() >= 500 }

    /**
     * Fetches only devices changed since the last watermark, unless [isFullSyncPass] or there's no
     * watermark yet (never synced before). Falls back to one full, unfiltered fetch if the filtered
     * attempt fails - a small number of NetBox deployments/proxies may not support
     * `last_updated__gte`. Returns whether the fetch that actually succeeded was a full one, i.e.
     * whether it's safe to prune devices this cache still has that the server no longer does.
     */
    private suspend fun syncDevicesIncrementally(
        isFullSyncPass: Boolean
    ): Pair<Result<Int>, Boolean> {
        val watermark = if (isFullSyncPass) null else deviceRepository.lastUpdatedWatermark()
        var result = deviceRepository.syncAll(lastUpdatedGte = watermark)
        var wasFullFetch = watermark == null
        if (result.isFailure && watermark != null) {
            result = deviceRepository.syncAll()
            wasFullFetch = true
        }
        return result to wasFullFetch
    }

    /** Same incremental/fallback/prune-eligibility contract as [syncDevicesIncrementally]. */
    private suspend fun syncModelIncrementally(
        endpointPath: String,
        isFullSyncPass: Boolean,
    ): Pair<Result<Int>, Boolean> {
        val watermark =
            if (isFullSyncPass) null else genericObjectRepository.lastUpdatedWatermark(endpointPath)
        val filters = watermark?.let { mapOf("last_updated__gte" to it) } ?: emptyMap()
        var result = genericObjectRepository.syncAll(endpointPath, filters = filters)
        var wasFullFetch = watermark == null
        if (result.isFailure && watermark != null) {
            result = genericObjectRepository.syncAll(endpointPath)
            wasFullFetch = true
        }
        return result to wasFullFetch
    }

    /**
     * Runs [action] over every item with at most [concurrency] running at once. The endpoints this
     * is used for (NetBox models, device types, rack elevations, attachment downloads) are
     * otherwise unrelated to each other, so overlapping their network round-trips is a meaningful
     * speedup - bounded so a small self-hosted NetBox instance isn't hit with more concurrent
     * requests than it can handle. Concurrent Room writes from within [action] are safe (Room's
     * WAL-backed connection serializes them), so no extra locking is needed there.
     */
    private suspend fun <T> Iterable<T>.syncConcurrently(
        concurrency: Int,
        action: suspend (T) -> Unit,
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        map { item -> async { semaphore.withPermit { action(item) } } }.awaitAll()
    }

    private suspend fun syncAttachments(
        concurrency: Int,
        isFullSyncPass: Boolean,
        onProgress: (completed: Int, total: Int) -> Unit,
    ): Int {
        imageAttachmentRepository.refreshAll("dcim.device").onFailure { error ->
            syncIssueReporter.report(
                "Image attachments for devices failed: ${error.message ?: "failed"}"
            )
        }

        val attachments = buildList {
            addAll(genericObjectRepository.cachedMediaAttachments())
            deviceTypeRepository.cachedAll().forEach { deviceType ->
                deviceType.frontImageUrl?.let {
                    add(OfflineAttachment(it, "device-type-${deviceType.id}-front"))
                }
                deviceType.rearImageUrl?.let {
                    add(OfflineAttachment(it, "device-type-${deviceType.id}-rear"))
                }
            }
            imageAttachmentRepository.cachedAll().forEach { attachment ->
                attachment.imageUrl?.let {
                    add(
                        OfflineAttachment(
                            it,
                            attachment.name?.takeIf(String::isNotBlank)
                                ?: attachment.display?.takeIf(String::isNotBlank)
                                ?: "image-attachment-${attachment.id}",
                        )
                    )
                }
            }
        }
            .distinctBy(OfflineAttachment::url)

        val downloaded = AtomicInteger(0)
        onProgress(0, attachments.size)
        attachments.syncConcurrently(concurrency) { attachment ->
            // NBC-434: incremental passes stay as cheap as before (an existing file is trusted,
            // zero requests); only the 24h/forced full pass pays one conditional request per
            // attachment to catch NetBox-side replacements under the same URL, most of which come
            // back a near-zero-byte 304.
            fileDownloadRepository
                .downloadToPersistent(
                    attachment.url,
                    attachment.filename,
                    revalidate = isFullSyncPass,
                )
                .onSuccess { onProgress(downloaded.incrementAndGet(), attachments.size) }
                .onFailure { error ->
                    Timber.w(error, "Couldn't persist offline attachment %s", attachment.url)
                    syncIssueReporter.report(
                        "Attachment ${attachment.filename} failed: ${error.message ?: "download failed"}"
                    )
                }
        }
        Timber.i("Synced %d durable attachments", downloaded.get())
        return downloaded.get()
    }
}
