package dev.pschmitt.nyetbox.ui.generic

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.ImageAttachmentEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.repository.CableTraceRepository
import dev.pschmitt.nyetbox.data.repository.CachedDocument
import dev.pschmitt.nyetbox.data.repository.CustomFieldRepository
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeleteSubmission
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DeviceTypeRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.DocumentRepository
import dev.pschmitt.nyetbox.data.repository.EditSubmission
import dev.pschmitt.nyetbox.data.repository.FileDownloadRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.ImageAttachmentRepository
import dev.pschmitt.nyetbox.data.repository.JournalEntryRepository
import dev.pschmitt.nyetbox.data.repository.MediaUploadRepository
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.repository.RackElevationRepository
import dev.pschmitt.nyetbox.data.repository.RackFace
import dev.pschmitt.nyetbox.data.repository.RecentVisitRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.SvgDiagramRepository
import dev.pschmitt.nyetbox.data.repository.cableTraceStartTarget
import dev.pschmitt.nyetbox.data.repository.cableTraceSvgCacheKey
import dev.pschmitt.nyetbox.data.repository.createChoiceSearchFields
import dev.pschmitt.nyetbox.data.repository.hiddenFieldPreferenceKey
import dev.pschmitt.nyetbox.data.repository.isDocumentsPluginModel
import dev.pschmitt.nyetbox.data.repository.rackElevationSvgCacheKey
import dev.pschmitt.nyetbox.data.schema.AssetTagState
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.data.schema.assetTagState
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.sync.SyncStatusRepository
import dev.pschmitt.nyetbox.sync.TargetedSyncEngine
import dev.pschmitt.nyetbox.ui.common.REFRESH_QUEUED_TOAST
import dev.pschmitt.nyetbox.ui.common.shouldShowRefreshQueuedToast
import dev.pschmitt.nyetbox.ui.common.targetedSyncToast
import dev.pschmitt.nyetbox.ui.navigation.Route
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Mirrors NetBoxNavHost's/GlobalSearchRepository's DEVICES_ENDPOINT_PATH constant - kept local
// rather than shared to avoid a broader refactor while other agents are touching those files.
private const val DEVICES_ENDPOINT_PATH = NetBoxRef.DEVICES_ENDPOINT_PATH

data class RackDevicePreview(
    val deviceTypeId: Int,
    val frontUrl: String?,
    val rearUrl: String?,
)

@HiltViewModel
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenericDetailViewModel
@Inject
constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: GenericObjectRepository,
    private val deviceRepository: DeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val dashboardRepository: DashboardRepository,
    private val fileDownloadRepository: FileDownloadRepository,
    private val imageAttachmentRepository: ImageAttachmentRepository,
    private val documentRepository: DocumentRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val deviceTypeRepository: DeviceTypeRepository,
    private val pendingEditRepository: PendingEditRepository,
    private val recentVisitRepository: RecentVisitRepository,
    private val rackElevationRepository: RackElevationRepository,
    private val cableTraceRepository: CableTraceRepository,
    private val svgDiagramRepository: SvgDiagramRepository,
    private val syncScheduler: SyncScheduler,
    syncStatusRepository: SyncStatusRepository,
    private val targetedSyncEngine: TargetedSyncEngine,
    private val json: Json,
    private val api: GenericNetBoxApi,
    private val directoryRepository: DirectoryRepository,
    private val mediaUploadRepository: MediaUploadRepository,
) : ViewModel() {

    val route: Route.Generic = savedStateHandle.toRoute()

    // NBC-10: "Print label" only makes sense for devices (printlabel's --netbox mode prints a
    // device's QR/asset-tag sticker) - other object types don't have a label to (re)print.
    val isPrintableDevice: Boolean = route.endpointPath == DEVICES_ENDPOINT_PATH
    val isDeviceType: Boolean = route.endpointPath == NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH

    val documentPluginAvailable: StateFlow<Boolean> =
        directoryRepository
            .observeCapability(::isDocumentsPluginModel)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Not every NetBox model accepts image attachments/documents (e.g. users.permission) - default
    // to true (shown) until the one-shot OPTIONS-derived answer lands, so the widgets don't flash
    // in and out for the common case where they are supported.
    val supportsImageAttachments: StateFlow<Boolean> =
        flow {
                emit(mediaUploadRepository.supportsImageAttachments(route.endpointPath))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val supportsDocuments: StateFlow<Boolean> =
        flow {
                emit(mediaUploadRepository.supportsDocuments(route.endpointPath))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isRefreshing = MutableStateFlow(false)

    /**
     * This object's own targeted refresh, not unrelated background sync activity - see [refresh].
     */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * Unlike [isRefreshing], reflects background sync activity generally - backs the "view related
     * items" bottom sheet's spinner, which shows a different endpoint's list populated by the
     * periodic/full sync rather than anything [refresh] itself fetches.
     */
    private val isSyncingGlobally: StateFlow<Boolean> =
        syncStatusRepository.isSyncing.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deleteResult = MutableStateFlow<DeleteSubmission?>(null)
    val deleteResult: StateFlow<DeleteSubmission?> = _deleteResult.asStateFlow()

    private val _documentDeleteResult = MutableStateFlow<DeleteSubmission?>(null)
    val documentDeleteResult: StateFlow<DeleteSubmission?> = _documentDeleteResult.asStateFlow()

    val bookmark: StateFlow<BookmarkEntity?> =
        dashboardRepository
            .observeBookmark(route.endpointPath, route.id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isTogglingBookmark = MutableStateFlow(false)
    val isTogglingBookmark: StateFlow<Boolean> = _isTogglingBookmark.asStateFlow()

    // Keep the edit session in the ViewModel so opening a linked-item create form cannot lose
    // unrelated unsaved fields when navigation temporarily removes this composable.
    private val _editSession = MutableStateFlow(EditSessionState.Idle)
    private val editSession: StateFlow<EditSessionState> = _editSession.asStateFlow()
    val isEditing: StateFlow<Boolean> =
        editSession
            .map { it.isEditing }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isSaving: StateFlow<Boolean> =
        editSession
            .map { it.isSaving }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val editDraftValues: StateFlow<Map<String, String>> =
        editSession
            .map { it.draftValues }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Positive-confirmation Snackbar text - shared by a manual refresh ("Refreshed") and a
    // successful save ("<item> updated!"), both simple "your action worked" acknowledgements.
    private val _refreshedMessage = MutableStateFlow<String?>(null)
    val refreshedMessage: StateFlow<String?> = _refreshedMessage.asStateFlow()

    private val _refreshToastMessage = MutableStateFlow<String?>(null)
    val refreshToastMessage: StateFlow<String?> = _refreshToastMessage.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _fileToOpen = MutableStateFlow<File?>(null)
    val fileToOpen: StateFlow<File?> = _fileToOpen.asStateFlow()

    private val journalEntriesFlow =
        journalEntryRepository.observeJournalEntries(route.endpointPath, route.id).map { entries ->
            entries.mapNotNull { it.toJournalEntryUi() }
        }

    val journalEntries: StateFlow<List<JournalEntryUi>> =
        journalEntriesFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    /**
     * Whether the journal query has produced its first real emission - `journalEntries` starts out
     * as the `emptyList()` placeholder from `stateIn`, so composing the tab bar before this settles
     * can render without a Journal tab that then pops in and shifts every tab after it, landing
     * taps on the wrong tab on slower phones.
     */
    val journalTabReady: StateFlow<Boolean> =
        journalEntriesFlow
            .map { true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * The dashboard sync already owns the cached changelog; do not fan out a network request here.
     */
    val changelog: StateFlow<List<ObjectChangeEntity>> =
        dashboardRepository
            .observeChangelog(route.endpointPath, route.id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<CachedDocument>> =
        documentRepository
            .observeFor(route.endpointPath, route.id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val imageAttachmentObjectType: String? =
        runCatching {
                MediaUploadRepository.contentTypeForEndpoint(route.endpointPath)
            }
            .getOrNull()

    val imageAttachments: StateFlow<List<ImageAttachmentEntity>> =
        imageAttachmentObjectType
            ?.let { objectType -> imageAttachmentRepository.observeFor(objectType, route.id) }
            ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
            ?: flowOf<List<ImageAttachmentEntity>>(emptyList())
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    emptyList(),
                )

    private val _journalMutationState = MutableStateFlow(JournalMutationUiState())
    val journalMutationState: StateFlow<JournalMutationUiState> =
        _journalMutationState.asStateFlow()

    private val objectFlow = repository.observeObject(route.endpointPath, route.id)

    private val objectEntity =
        objectFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the local cache has been checked at least once - `objectEntity`'s `null` default
     * means the same thing whether the first Room query just hasn't landed yet or the object
     * genuinely isn't cached, and the UI needs to tell those apart so a briefly-loading item on a
     * slower device doesn't flash a "not cached" message it's about to contradict.
     */
    val hasCheckedCache: StateFlow<Boolean> =
        objectFlow.map { true }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val decodedObject: StateFlow<JsonObject?> =
        objectEntity
            .map { entity -> entity?.let { decode(it.json) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val title: StateFlow<String?> =
        objectEntity
            .map { it?.display }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Distinguishes "no asset_tag field on this object type" from "field present but
     * blank" - [fields]/`buildFieldRows` drops blank values entirely, so this reads [decodedObject]
     * directly instead (mirrors `GenericListScreen`'s `assetTagStateFromRawJson`).
     */
    val assetTag: StateFlow<AssetTagState> =
        decodedObject
            .map { obj -> obj?.assetTagState() ?: AssetTagState(hasField = false, value = null) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                AssetTagState(hasField = false, value = null),
            )

    val fields: StateFlow<List<FieldRow>> =
        combine(decodedObject, customFieldRepository.observeDefinitions()) { obj, definitions ->
                obj?.let { buildFieldRows(it, definitions, route.endpointPath) } ?: emptyList()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val editableFields: StateFlow<List<EditableField>> =
        combine(decodedObject, customFieldRepository.observeDefinitions()) { obj, definitions ->
                obj?.let { buildEditableFields(it, definitions, route.endpointPath) } ?: emptyList()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _referenceOptions = MutableStateFlow<Map<String, List<EditOption>>>(emptyMap())
    val referenceOptions: StateFlow<Map<String, List<EditOption>>> = _referenceOptions.asStateFlow()

    private val _choiceOptions = MutableStateFlow<Map<String, List<EditOption>>>(emptyMap())
    val choiceOptions: StateFlow<Map<String, List<EditOption>>> = _choiceOptions.asStateFlow()

    private val linkedCreateResultRaw =
        savedStateHandle.getStateFlow<String?>(LINKED_CREATE_RESULT_KEY, null)
    val linkedCreateResult: StateFlow<LinkedCreateResult?> =
        linkedCreateResultRaw
            .map(::decodeLinkedCreateResult)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val hiddenFieldKeys: StateFlow<Set<String>> = settingsRepository.hiddenFieldKeys

    val objectTypeAccent: StateFlow<dev.pschmitt.nyetbox.data.repository.ThemeAccent?> =
        settingsRepository.objectTypeAccents
            .map { it[route.endpointPath.trim('/')] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _relatedTarget = MutableStateFlow<CountTarget?>(null)
    val relatedTarget: StateFlow<CountTarget?> = _relatedTarget.asStateFlow()

    val isRelatedRefreshing: StateFlow<Boolean> = isSyncingGlobally

    val relatedObjects: StateFlow<List<NetBoxObjectEntity>> =
        _relatedTarget
            .flatMapLatest { target ->
                target?.let {
                    repository.observeObjects(it.endpointPath, "", it.relationKey, it.parentId)
                } ?: flowOf(emptyList())
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val relatedPreviewUrls: StateFlow<Map<Int, String>> =
        combine(relatedObjects, deviceTypeRepository.observeAll()) { objects, deviceTypes ->
                val typeImages = deviceTypes.associate {
                    it.id to (it.frontImageUrl ?: it.rearImageUrl)
                }
                objects
                    .mapNotNull { objectEntity ->
                        previewImageUrl(objectEntity, typeImages)?.let { objectEntity.id to it }
                    }
                    .toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val isRack: Boolean = route.endpointPath == "api/dcim/racks/"

    val frontElevation =
        rackElevationRepository
            .observe(route.id, RackFace.FRONT)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList(),
            )

    val rearElevation =
        rackElevationRepository
            .observe(route.id, RackFace.REAR)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList(),
            )

    /** Device-type stock photos keyed by device id, for the rack elevation blocks. */
    val rackDevicePreviews: StateFlow<Map<Int, RackDevicePreview>> =
        combine(
                repository.observeObjects(DEVICES_ENDPOINT_PATH, "", "rack", route.id),
                deviceTypeRepository.observeAll(),
            ) { devices, deviceTypes ->
                val types = deviceTypes.associateBy { it.id }
                devices
                    .mapNotNull { entity ->
                        val objectJson = decode(entity.json) ?: return@mapNotNull null
                        val deviceTypeId =
                            (objectJson["device_type"] as? JsonObject)?.let {
                                (it["id"] as? JsonPrimitive)?.intOrNull
                            } ?: return@mapNotNull null
                        val deviceType = types[deviceTypeId] ?: return@mapNotNull null
                        entity.id to
                            RackDevicePreview(
                                deviceTypeId = deviceTypeId,
                                frontUrl = deviceType.frontImageUrl,
                                rearUrl = deviceType.rearImageUrl,
                            )
                    }
                    .toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val isCable: Boolean = route.endpointPath == NetBoxRef.CABLES_ENDPOINT_PATH

    // The trace endpoint lives on a termination (interface, console port, ...), not the cable
    // itself - resolved from the cable's own a_terminations/b_terminations once it's loaded.
    private val traceStartTarget: StateFlow<Pair<String, Int>?> =
        decodedObject
            .map { it?.let(::cableTraceStartTarget) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val traceSegments: StateFlow<List<CableTraceSegment>> =
        traceStartTarget
            .flatMapLatest { target ->
                target?.let { (endpointPath, id) -> cableTraceRepository.observe(endpointPath, id) }
                    ?: flowOf(emptyList())
            }
            .map { entities ->
                entities.mapNotNull { parseCableTraceSegment(it.segmentIndex, it.json) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun loadCableTrace() {
        val (endpointPath, id) = traceStartTarget.value ?: return
        viewModelScope.launch {
            cableTraceRepository.refresh(endpointPath, id)
            // Silently no-op on failure, same as the journal load below - the trace is a secondary
            // tab, not core object data.
        }
    }

    private val _rackElevationSvg = MutableStateFlow<Map<RackFace, String>>(emptyMap())
    val rackElevationSvg: StateFlow<Map<RackFace, String>> = _rackElevationSvg.asStateFlow()

    private val _traceSvg = MutableStateFlow<String?>(null)
    val traceSvg: StateFlow<String?> = _traceSvg.asStateFlow()

    /**
     * Loads a rack elevation diagram on demand (the "switch to SVG" toggle), cache-first: shows
     * whatever's already persisted (from a prior view, or
     * [dev.pschmitt.nyetbox.sync.OfflineSyncRepository]'s prefetch) immediately, then refreshes it
     * in the background.
     */
    fun loadRackElevationSvg(face: RackFace) {
        val cacheKey = rackElevationSvgCacheKey(route.id, face)
        viewModelScope.launch {
            svgDiagramRepository.cachedContent(cacheKey)?.let { svg ->
                _rackElevationSvg.update { it + (face to svg) }
            }
            svgDiagramRepository
                .refresh(
                    "${NetBoxRef.RACKS_ENDPOINT_PATH}${route.id}/elevation/" +
                        "?face=${face.apiValue}&render=svg",
                    cacheKey,
                )
                .onSuccess { svg -> _rackElevationSvg.update { it + (face to svg) } }
        }
    }

    /** Same idea as [loadRackElevationSvg], for the cable trace tab. */
    fun loadCableTraceSvg() {
        val (endpointPath, id) = traceStartTarget.value ?: return
        val cacheKey = cableTraceSvgCacheKey(endpointPath, id)
        viewModelScope.launch {
            svgDiagramRepository.cachedContent(cacheKey)?.let { _traceSvg.value = it }
            svgDiagramRepository
                .refresh("$endpointPath$id/trace/?render=svg", cacheKey)
                .onSuccess { svg -> _traceSvg.value = svg }
        }
    }

    fun hideField(label: String) {
        settingsRepository.addHiddenField(hiddenFieldPreferenceKey(route.endpointPath, label))
    }

    fun showRelatedItems(target: CountTarget) {
        _relatedTarget.value = target
    }

    fun dismissRelatedItems() {
        _relatedTarget.value = null
    }

    // Web URL mirrors the API path structure with "api/" dropped, e.g. api/dcim/racks/5/ ->
    // <base>/dcim/racks/5/.
    val webUrl: StateFlow<String?> =
        objectEntity
            .combine(settingsRepository.credentials) { entity, credentials ->
                entity to credentials
            }
            .map { (entity, credentials) ->
                if (entity == null || credentials.baseUrl.isBlank()) null
                else
                    "${credentials.baseUrl}/${route.endpointPath.removePrefix("api/")}${entity.id}/"
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Fed into the `printlabel --netbox-url` flag for the "Print label" share action - see
    // PrintLabelIntent.kt.
    val netboxBaseUrl: StateFlow<String?> =
        settingsRepository.credentials
            .map { it.baseUrl.ifBlank { null } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            objectFlow.filterNotNull().take(1).collect { recentVisitRepository.record(it) }
        }
        viewModelScope.launch { loadJournalEntries() }
        viewModelScope.launch { loadImageAttachments() }
        viewModelScope.launch {
            traceStartTarget.filterNotNull().distinctUntilChanged().collect { (endpointPath, id) ->
                cableTraceRepository.refresh(endpointPath, id)
            }
        }
    }

    /**
     * Targeted refresh of this object via [TargetedSyncEngine] - for a container object (rack,
     * site, location) this recursively syncs everything meaningfully linked to it (devices, their
     * device types/interfaces/cables, connected devices, ...); for any other object type it's still
     * more than a bare single-object refresh, since the engine always follows the object's own
     * forward references (e.g. an interface's device and cable). Journal/cable-trace/image
     * attachments aren't part of that generic link graph, so they're refreshed alongside it here.
     */
    fun refresh(showConfirmation: Boolean = false) {
        if (settingsRepository.offlineMode.value) return
        viewModelScope.launch {
            if (shouldShowRefreshQueuedToast(showConfirmation, offlineMode = false)) {
                _refreshToastMessage.value = REFRESH_QUEUED_TOAST
            }
            _isRefreshing.value = true
            val syncResult = targetedSyncEngine.sync(route.endpointPath, route.id)
            if (showConfirmation) {
                _refreshToastMessage.value =
                    targetedSyncToast(
                        primarySucceeded = syncResult.rootSucceeded,
                        failureCount = syncResult.otherFailureCount,
                    )
            }
            if (!syncResult.rootSucceeded) {
                _errorMessage.value = "Couldn't refresh - showing cached data"
            }
            loadJournalEntries()
            loadCableTrace()
            loadImageAttachments()
            _isRefreshing.value = false
        }
    }

    private fun loadJournalEntries() {
        viewModelScope.launch {
            journalEntryRepository.fetchJournalEntries(route.endpointPath, route.id)
            // Silently no-op on failure - the journal is a secondary panel, not core object data,
            // and content-type resolution can legitimately come up empty for unusual object types.
        }
    }

    /**
     * Unlike [documents] (which piggybacks on the Documents plugin's own generically-synced object
     * list, filtered client-side by `assigned_object`), image attachments live in a dedicated table
     * that background sync otherwise only populates for `dcim.device` - so a rack, site, or any
     * other object type would never get its attachments cached without this per-object fetch on
     * open/refresh.
     */
    private fun loadImageAttachments() {
        val objectType = imageAttachmentObjectType ?: return
        viewModelScope.launch {
            imageAttachmentRepository.refresh(objectType, route.id)
            // Silently no-op on failure, same rationale as loadJournalEntries above.
        }
    }

    fun saveJournalEntry(entry: JournalEntryUi?, kind: String, comments: String) {
        if (_journalMutationState.value.isSaving) return
        viewModelScope.launch {
            _journalMutationState.value = JournalMutationUiState(isSaving = true)
            val result =
                if (entry == null) {
                    journalEntryRepository.createJournalEntry(
                        endpointPath = route.endpointPath,
                        objectId = route.id,
                        kind = kind,
                        comments = comments,
                        offline = settingsRepository.offlineMode.value,
                    )
                } else {
                    journalEntryRepository.updateJournalEntry(
                        id = entry.id,
                        baseJson = entry.baseJson,
                        kind = kind,
                        comments = comments,
                    )
                }
            result
                .onSuccess { mutation ->
                    _journalMutationState.value =
                        JournalMutationUiState(
                            message =
                                if (mutation.queued) {
                                    "Journal entry saved locally; will sync when online"
                                } else {
                                    "Journal entry saved"
                                }
                        )
                }
                .onFailure { error ->
                    _journalMutationState.value =
                        JournalMutationUiState(
                            error = error.message ?: "Couldn't save journal entry"
                        )
                }
        }
    }

    fun journalMutationMessageShown() {
        _journalMutationState.update { it.copy(message = null, error = null) }
    }

    fun errorShown() {
        _errorMessage.value = null
    }

    fun refreshedMessageShown() {
        _refreshedMessage.value = null
    }

    fun refreshToastShown() {
        _refreshToastMessage.value = null
    }

    fun delete() {
        if (_isDeleting.value) return
        val customFieldName =
            if (route.endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH) {
                decode(objectEntity.value?.json.orEmpty())?.get("name")?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                }
            } else {
                null
            }
        viewModelScope.launch {
            _isDeleting.value = true
            pendingEditRepository
                .deleteObject(
                    endpointPath = route.endpointPath,
                    id = route.id,
                    offline = settingsRepository.offlineMode.value,
                )
                .onSuccess { result ->
                    if (route.endpointPath == DEVICES_ENDPOINT_PATH) {
                        deviceRepository.removeCachedDevice(route.id)
                    }
                    customFieldName?.let { customFieldRepository.removeDefinition(it) }
                    _deleteResult.value = result
                }
                .onFailure { _errorMessage.value = it.message ?: "Couldn't delete item" }
            _isDeleting.value = false
        }
    }

    fun deleteResultShown() {
        _deleteResult.value = null
    }

    fun toggleBookmark() {
        if (_isTogglingBookmark.value) return
        if (settingsRepository.offlineMode.value) {
            _errorMessage.value = "Can't update bookmarks while offline"
            return
        }
        viewModelScope.launch {
            _isTogglingBookmark.value = true
            val existing = bookmark.value
            val result =
                if (existing != null) dashboardRepository.removeBookmark(existing.id)
                else dashboardRepository.addBookmark(route.endpointPath, route.id)
            result.onFailure { _errorMessage.value = it.message ?: "Couldn't update bookmark" }
            _isTogglingBookmark.value = false
        }
    }

    fun deleteDocument(document: CachedDocument) {
        if (_isDeleting.value) return
        viewModelScope.launch {
            _isDeleting.value = true
            documentRepository
                .delete(document.id, settingsRepository.offlineMode.value)
                .onSuccess { _documentDeleteResult.value = it }
                .onFailure { _errorMessage.value = it.message ?: "Couldn't delete document" }
            _isDeleting.value = false
        }
    }

    fun documentDeleteResultShown() {
        _documentDeleteResult.value = null
    }

    fun startEditing() {
        _errorMessage.value = null
        _editSession.value =
            _editSession.value.beginFullForm(
                objectEntity.value?.json,
                editableFields.value.associate { it.key to it.value },
            )
        _referenceOptions.value = emptyMap()
        _choiceOptions.value = emptyMap()
        viewModelScope.launch { loadEditOptions(editableFields.value) }
    }

    /** Prepare the existing save path for a focused edit dialog without opening the full form. */
    fun startFieldEditing(fieldKey: String) {
        val field = editableFields.value.firstOrNull { it.key == fieldKey } ?: return
        _errorMessage.value = null
        _editSession.value = _editSession.value.beginFocusedField(objectEntity.value?.json)
        _referenceOptions.value = emptyMap()
        _choiceOptions.value = emptyMap()
        viewModelScope.launch { loadEditOptions(listOf(field)) }
    }

    fun cancelFieldEditing() {
        _errorMessage.value = null
        _editSession.value = EditSessionState.Idle
        _referenceOptions.value = emptyMap()
        _choiceOptions.value = emptyMap()
    }

    fun cancelEditing() {
        _errorMessage.value = null
        _editSession.value = EditSessionState.Idle
        _referenceOptions.value = emptyMap()
        _choiceOptions.value = emptyMap()
    }

    /** [edits] maps field key -> (kind, edited text), one entry per changed field. */
    fun save(edits: Map<String, Pair<EditFieldKind, String>>) {
        if (edits.isEmpty()) {
            _editSession.value = EditSessionState.Idle
            return
        }
        viewModelScope.launch {
            _editSession.update { it.saving() }
            val baseJson = _editSession.value.baseJson ?: objectEntity.value?.json
            if (baseJson == null) {
                _errorMessage.value = "Couldn't save: the original object is no longer cached"
            } else {
                pendingEditRepository
                    .submitEdit(route.endpointPath, route.id, baseJson, buildPatchBody(edits))
                    .onSuccess { submission ->
                        if (route.endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH) {
                            repository
                                .observeObject(route.endpointPath, route.id)
                                .first()
                                ?.let { decode(it.json) }
                                ?.let { customFieldRepository.cacheDefinition(it) }
                        }
                        _editSession.value = EditSessionState.Idle
                        when (submission) {
                            EditSubmission.Updated -> {
                                _refreshedMessage.value = "${title.value ?: "Item"} updated!"
                                // Refreshes the wider offline cache (and, if enabled, synced
                                // attachments) so an edit's side effects elsewhere in NetBox
                                // aren't only reflected here.
                                syncScheduler.syncNow()
                            }
                            EditSubmission.Queued ->
                                _refreshedMessage.value = "Saved locally; will sync when online"
                            EditSubmission.ConflictDetected ->
                                _errorMessage.value =
                                    "Conflict detected - review it from the dashboard"
                        }
                    }
                    .onFailure { _errorMessage.value = it.message ?: "Couldn't save changes" }
            }
            _editSession.update { it.copy(isSaving = false) }
        }
    }

    fun initializeEditDraftIfNeeded() {
        if (_editSession.value.draftValues.isEmpty() && editableFields.value.isNotEmpty()) {
            _editSession.update {
                it.copy(
                    draftValues =
                        editableFields.value.associate { field -> field.key to field.value }
                )
            }
        }
    }

    fun setEditDraftValue(key: String, value: String) {
        _editSession.update { it.updateDraft(key, value) }
    }

    fun addReferenceOption(fieldKey: String, option: EditOption) {
        val options = _referenceOptions.value[fieldKey].orEmpty()
        _referenceOptions.value =
            _referenceOptions.value + (fieldKey to (options + option).distinctBy { it.value })
    }

    fun consumeLinkedCreateResult() {
        savedStateHandle[LINKED_CREATE_RESULT_KEY] = null
    }

    private suspend fun loadEditOptions(fields: List<EditableField>) {
        val references = mutableMapOf<String, List<EditOption>>()
        fields
            .filter { it.kind in setOf(EditFieldKind.REFERENCE, EditFieldKind.MULTI_REFERENCE) }
            .forEach { field ->
                val endpoint = field.referenceEndpointPath ?: return@forEach
                val cached = repository.cachedObjects(endpoint)
                val options =
                    buildList {
                            field.currentDisplay?.let { add(EditOption(field.value, it)) }
                            addAll(
                                cached.map { entity ->
                                    val objectJson = decode(entity.json)
                                    EditOption(
                                        value = entity.id.toString(),
                                        label = entity.display,
                                        frontImageUrl =
                                            (objectJson?.get("front_image") as? JsonPrimitive)
                                                ?.contentOrNull,
                                        rearImageUrl =
                                            (objectJson?.get("rear_image") as? JsonPrimitive)
                                                ?.contentOrNull,
                                        searchFields =
                                            objectJson?.createChoiceSearchFields().orEmpty(),
                                    )
                                }
                            )
                        }
                        .distinctBy { it.value }
                references[field.key] = options
            }
        _referenceOptions.value = references

        val choices = mutableMapOf<String, List<EditOption>>()
        if (
            fields.any { it.kind == EditFieldKind.CHOICE } && !settingsRepository.offlineMode.value
        ) {
            choices.putAll(
                runCatching { api.getObjectOptions("${route.endpointPath}${route.id}/") }
                    .getOrNull()
                    ?.let(::parseChoiceOptions)
                    .orEmpty()
            )
        }
        if (!settingsRepository.offlineMode.value) {
            val definitions = customFieldRepository.observeDefinitions().first()
            fields
                .filter {
                    it.customFieldName != null &&
                        it.kind in setOf(EditFieldKind.CHOICE, EditFieldKind.MULTI_CHOICE)
                }
                .forEach { field ->
                    val definition =
                        definitions.firstOrNull { it.name == field.customFieldName }
                            ?: return@forEach
                    val url = definition.choiceSetUrl ?: return@forEach
                    runCatching { api.getObject(url) }
                        .getOrNull()
                        ?.let(::parseCustomChoiceOptions)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { choices[field.key] = it }
                }
        }
        if (route.endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH) {
            val contentTypeOptions =
                repository.cachedContentTypeChoices().map { EditOption(it.value, it.label) }
            if (contentTypeOptions.isNotEmpty()) {
                choices["object_types"] = contentTypeOptions
            }
            customFieldAdminChoiceOptions().forEach { (key, options) ->
                if (choices[key].orEmpty().isEmpty()) choices[key] = options
            }
        }
        _choiceOptions.value = choices
    }

    fun downloadAttachment(url: String, filename: String) {
        if (_isDownloading.value) return
        fileDownloadRepository.persistentFile(url, filename)?.let {
            _fileToOpen.value = it
            return
        }
        viewModelScope.launch {
            _isDownloading.value = true
            fileDownloadRepository
                .downloadToPersistent(url, filename)
                .onSuccess { _fileToOpen.value = it }
                .onFailure { _errorMessage.value = it.message ?: "Couldn't download $filename" }
            _isDownloading.value = false
        }
    }

    fun fileOpened() {
        _fileToOpen.value = null
    }

    fun localAttachmentFile(url: String, filename: String): File? =
        fileDownloadRepository.persistentFile(url, filename)

    private fun previewImageUrl(
        entity: NetBoxObjectEntity,
        deviceTypeImages: Map<Int, String?>,
    ): String? {
        val obj = decode(entity.json) ?: return null
        listOf("front_image", "image", "rear_image").forEach { key ->
            (obj[key] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    return it
                }
        }
        val deviceType = obj["device_type"] as? JsonObject
        val deviceTypeId = (deviceType?.get("id") as? JsonPrimitive)?.intOrNull
        return deviceTypeId?.let { deviceTypeImages[it] }
    }

    private fun decode(rawJson: String): JsonObject? =
        runCatching {
                json.decodeFromString(JsonObject.serializer(), rawJson)
            }
            .getOrNull()

    private fun customFieldAdminChoiceOptions(): Map<String, List<EditOption>> =
        mapOf(
            "type" to
                listOf(
                        "text" to "Text",
                        "longtext" to "Text (long)",
                        "integer" to "Integer",
                        "decimal" to "Decimal",
                        "boolean" to "Boolean (true/false)",
                        "date" to "Date",
                        "datetime" to "Date & time",
                        "url" to "URL",
                        "json" to "JSON",
                        "select" to "Selection",
                        "multiselect" to "Multiple selection",
                        "object" to "Object",
                        "multiobject" to "Multiple objects",
                    )
                    .map { (value, label) -> EditOption(value, label) },
            "filter_logic" to
                listOf(
                        "disabled" to "Disabled",
                        "loose" to "Loose",
                        "exact" to "Exact",
                    )
                    .map { (value, label) -> EditOption(value, label) },
            "ui_visible" to
                listOf(
                        "always" to "Always",
                        "if-set" to "If set",
                        "hidden" to "Hidden",
                    )
                    .map { (value, label) -> EditOption(value, label) },
            "ui_editable" to
                listOf(
                        "yes" to "Yes",
                        "no" to "No",
                        "hidden" to "Hidden",
                    )
                    .map { (value, label) -> EditOption(value, label) },
        )

    private companion object {
        const val CUSTOM_FIELDS_ENDPOINT_PATH = "api/extras/custom-fields/"
    }
}

internal fun parseChoiceOptions(response: JsonObject): Map<String, List<EditOption>> {
    val actions = response["actions"] as? JsonObject
    val editableFields = ((actions?.get("PATCH") ?: actions?.get("PUT")) as? JsonObject).orEmpty()
    return editableFields
        .mapNotNull { (key, definition) ->
            val choices =
                (definition as? JsonObject)?.get("choices") as? JsonArray ?: return@mapNotNull null
            val options = choices.mapNotNull { choice ->
                val obj = choice as? JsonObject ?: return@mapNotNull null
                val value =
                    (obj["value"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val label =
                    (obj["display_name"] as? JsonPrimitive)?.contentOrNull
                        ?: (obj["display"] as? JsonPrimitive)?.contentOrNull
                        ?: value
                EditOption(value, label)
            }
            key to options
        }
        .toMap()
}

internal fun parseCustomChoiceOptions(response: JsonObject): List<EditOption> =
    listOf("base_choices", "extra_choices")
        .flatMap { key ->
            (response[key] as? JsonArray).orEmpty().mapNotNull { choice ->
                val pair = choice as? JsonArray ?: return@mapNotNull null
                val value =
                    (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val label = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: value
                EditOption(value, label)
            }
        }
        .distinctBy { it.value }
