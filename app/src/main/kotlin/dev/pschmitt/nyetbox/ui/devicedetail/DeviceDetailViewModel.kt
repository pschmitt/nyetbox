package dev.pschmitt.nyetbox.ui.devicedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.db.DeviceTypeEntity
import dev.pschmitt.nyetbox.data.db.ImageAttachmentEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.repository.CachedDocument
import dev.pschmitt.nyetbox.data.repository.CustomFieldRepository
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeleteSubmission
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DeviceTypeRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.DocumentRepository
import dev.pschmitt.nyetbox.data.repository.FileDownloadRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.ImageAttachmentRepository
import dev.pschmitt.nyetbox.data.repository.JournalEntryRepository
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.repository.RecentVisitRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.TopologyRepository
import dev.pschmitt.nyetbox.data.repository.hiddenFieldPreferenceKey
import dev.pschmitt.nyetbox.data.repository.isDocumentsPluginModel
import dev.pschmitt.nyetbox.data.repository.isTopologyPluginModel
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.data.topology.TopologyGraph
import dev.pschmitt.nyetbox.sync.TargetedSyncEngine
import dev.pschmitt.nyetbox.ui.common.REFRESH_QUEUED_TOAST
import dev.pschmitt.nyetbox.ui.common.shouldShowRefreshQueuedToast
import dev.pschmitt.nyetbox.ui.common.targetedSyncToast
import dev.pschmitt.nyetbox.ui.generic.FieldRow
import dev.pschmitt.nyetbox.ui.generic.JournalEntryUi
import dev.pschmitt.nyetbox.ui.generic.JournalMutationUiState
import dev.pschmitt.nyetbox.ui.generic.buildFieldRows
import dev.pschmitt.nyetbox.ui.generic.toJournalEntryUi
import dev.pschmitt.nyetbox.ui.navigation.Route
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

private const val DEVICE_OBJECT_TYPE = "dcim.device"
const val JOURNAL_TAB_ENDPOINT_PATH = "__journal__"
const val INTERFACES_TAB_ENDPOINT_PATH = NetBoxRef.INTERFACES_ENDPOINT_PATH
const val DEVICE_TYPES_ENDPOINT_PATH = NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH
const val CONNECTED_DEVICES_TAB_ENDPOINT_PATH = "__connected_devices__"
private const val IP_ADDRESSES_ENDPOINT_PATH = NetBoxRef.IP_ADDRESSES_ENDPOINT_PATH
private val ipAddressJson = Json { ignoreUnknownKeys = true }

data class DeviceRelatedTab(val label: String, val endpointPath: String)

data class InterfaceIpAddress(val id: Int, val address: String)

internal fun connectedDevicesFor(
    current: DeviceEntity?,
    devices: List<DeviceEntity>,
    graph: TopologyGraph?,
): List<DeviceEntity> {
    if (current == null || graph == null) return emptyList()
    val nodeNames =
        graph.nodes.associate { node ->
            node.id to node.label.lineSequence().firstOrNull()?.trim().orEmpty()
        }
    val currentNodeIds = nodeNames.filterValues { it.equals(current.name, ignoreCase = true) }.keys
    if (currentNodeIds.isEmpty()) return emptyList()
    val neighborNodeIds =
        graph.edges
            .flatMap { edge ->
                when {
                    edge.source in currentNodeIds -> listOf(edge.target)
                    edge.target in currentNodeIds -> listOf(edge.source)
                    else -> emptyList()
                }
            }
            .toSet()
    val neighborNames = neighborNodeIds.mapNotNull(nodeNames::get).map(String::lowercase).toSet()
    return devices
        .asSequence()
        .filter { it.id != current.id && it.name.lowercase() in neighborNames }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .toList()
}

internal data class ParsedInterfaceIpAddress(
    val interfaceId: Int,
    val ipAddress: InterfaceIpAddress,
)

internal fun parseInterfaceIpAddress(
    objectId: Int,
    rawJson: String,
): ParsedInterfaceIpAddress? {
    val objectJson =
        runCatching {
            ipAddressJson.decodeFromString(JsonObject.serializer(), rawJson)
        }
            .getOrNull() ?: return null
    if (objectJson["assigned_object_type"]?.jsonPrimitive?.contentOrNull != "dcim.interface") {
        return null
    }
    val interfaceId = objectJson["assigned_object_id"]?.jsonPrimitive?.intOrNull ?: return null
    val address =
        objectJson["address"]?.jsonPrimitive?.contentOrNull
            ?: objectJson["display"]?.jsonPrimitive?.contentOrNull
    if (address.isNullOrBlank()) return null
    return ParsedInterfaceIpAddress(interfaceId, InterfaceIpAddress(objectId, address))
}

internal fun parseManufacturerId(rawJson: String): Int? {
    val objectJson =
        runCatching {
            ipAddressJson.decodeFromString(JsonObject.serializer(), rawJson)
        }
            .getOrNull() ?: return null
    return (objectJson["manufacturer"] as? JsonObject)?.get("id")?.jsonPrimitive?.intOrNull
}

val DEVICE_RELATED_TABS =
    listOf(
        DeviceRelatedTab("Journal", JOURNAL_TAB_ENDPOINT_PATH),
        DeviceRelatedTab("Connected devices", CONNECTED_DEVICES_TAB_ENDPOINT_PATH),
        DeviceRelatedTab("Interfaces", INTERFACES_TAB_ENDPOINT_PATH),
        DeviceRelatedTab("Front ports", "api/dcim/front-ports/"),
        DeviceRelatedTab("Rear ports", "api/dcim/rear-ports/"),
        DeviceRelatedTab("Power ports", "api/dcim/power-ports/"),
        DeviceRelatedTab("Console ports", "api/dcim/console-ports/"),
        DeviceRelatedTab("Power outlets", "api/dcim/power-outlets/"),
        DeviceRelatedTab("Module bays", "api/dcim/module-bays/"),
    )

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeviceDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: DeviceRepository,
    private val deviceTypeRepository: DeviceTypeRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val imageAttachmentRepository: ImageAttachmentRepository,
    private val documentRepository: DocumentRepository,
    private val journalEntryRepository: JournalEntryRepository,
    private val fileDownloadRepository: FileDownloadRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val dashboardRepository: DashboardRepository,
    private val pendingEditRepository: PendingEditRepository,
    private val recentVisitRepository: RecentVisitRepository,
    private val settingsRepository: SettingsRepository,
    private val directoryRepository: DirectoryRepository,
    private val topologyRepository: TopologyRepository,
    private val targetedSyncEngine: TargetedSyncEngine,
) : ViewModel() {

    private val deviceId: Int = savedStateHandle.toRoute<Route.DeviceDetail>().deviceId

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting.asStateFlow()

    private val _deleteResult = MutableStateFlow<DeleteSubmission?>(null)
    val deleteResult: StateFlow<DeleteSubmission?> = _deleteResult.asStateFlow()

    private val _documentDeleteResult = MutableStateFlow<DeleteSubmission?>(null)
    val documentDeleteResult: StateFlow<DeleteSubmission?> = _documentDeleteResult.asStateFlow()

    val bookmark: StateFlow<BookmarkEntity?> =
        dashboardRepository
            .observeBookmark("api/dcim/devices/", deviceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isTogglingBookmark = MutableStateFlow(false)
    val isTogglingBookmark: StateFlow<Boolean> = _isTogglingBookmark.asStateFlow()

    val hiddenFieldKeys: StateFlow<Set<String>> = settingsRepository.hiddenFieldKeys

    val documentPluginAvailable: StateFlow<Boolean> =
        directoryRepository
            .observeCapability(::isDocumentsPluginModel)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val topologyPluginAvailableFlow =
        directoryRepository.observeCapability(::isTopologyPluginModel)

    val topologyPluginAvailable: StateFlow<Boolean> =
        topologyPluginAvailableFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    val objectTypeAccent: StateFlow<dev.pschmitt.nyetbox.data.repository.ThemeAccent?> =
        settingsRepository.objectTypeAccents
            .map { it["api/dcim/devices"] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun hideField(label: String) {
        settingsRepository.addHiddenField(hiddenFieldPreferenceKey("api/dcim/devices/", label))
    }

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _refreshedMessage = MutableStateFlow<String?>(null)
    val refreshedMessage: StateFlow<String?> = _refreshedMessage.asStateFlow()

    private val _refreshToastMessage = MutableStateFlow<String?>(null)
    val refreshToastMessage: StateFlow<String?> = _refreshToastMessage.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _fileToOpen = MutableStateFlow<File?>(null)
    val fileToOpen: StateFlow<File?> = _fileToOpen.asStateFlow()

    val device: StateFlow<DeviceEntity?> =
        deviceRepository
            .observeDevice(deviceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether the local cache has been checked at least once - see
     * [dev.pschmitt.nyetbox.ui.generic.GenericDetailViewModel.hasCheckedCache] for why `device ==
     * null` alone can't tell "still loading" apart from "genuinely not cached".
     */
    val hasCheckedCache: StateFlow<Boolean> =
        deviceRepository
            .observeDevice(deviceId)
            .map { true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // device.url is the *API* url (e.g. https://host/api/dcim/devices/393/) - the actual web page
    // mirrors that path with the "/api" prefix dropped.
    val webUrl: StateFlow<String?> =
        device
            .map { entity ->
                entity?.url?.toHttpUrlOrNull()?.let { apiUrl -> apiUrlToWebUrl(apiUrl) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Scheme+host(+port) only, e.g. https://netbox.example.com - fed into the `printlabel
    // --netbox-url` flag for NBC-10's "share a print command" action so it works even if the
    // user's shell doesn't already have NETBOX_URL exported.
    val netboxBaseUrl: StateFlow<String?> =
        device
            .map { entity ->
                entity?.url?.toHttpUrlOrNull()?.let { apiUrl -> apiUrlToBaseUrl(apiUrl) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val deviceType: StateFlow<DeviceTypeEntity?> =
        device
            .flatMapLatest { entity ->
                entity?.deviceTypeId?.let { deviceTypeRepository.observe(it) } ?: flowOf(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val deviceTypeImages: StateFlow<Map<Int, DeviceTypeEntity>> =
        deviceTypeRepository
            .observeAll()
            .map { types -> types.associateBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Manufacturer references live in the cached generic device-type object. */
    val manufacturerId: StateFlow<Int?> =
        device
            .flatMapLatest { entity ->
                entity?.deviceTypeId?.let { deviceTypeId ->
                    genericObjectRepository
                        .observeObject(DEVICE_TYPES_ENDPOINT_PATH, deviceTypeId)
                        .map { objectEntity -> objectEntity?.json?.let(::parseManufacturerId) }
                } ?: flowOf(null)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Custom fields are stored with the typed device row so this remains usable offline. */
    val customFieldRows: StateFlow<List<FieldRow>> =
        combine(device, customFieldRepository.observeDefinitions()) { entity, definitions ->
                val customFields =
                    entity?.customFieldsJson?.let { raw ->
                        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
                    } ?: return@combine emptyList()
                buildFieldRows(
                    JsonObject(mapOf("custom_fields" to customFields)),
                    definitions,
                    "api/dcim/devices/",
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val imageAttachments: StateFlow<List<ImageAttachmentEntity>> =
        imageAttachmentRepository
            .observeFor(DEVICE_OBJECT_TYPE, deviceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documents: StateFlow<List<CachedDocument>> =
        documentRepository
            .observeFor("api/dcim/devices/", deviceId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val journalEntriesFlow =
        journalEntryRepository.observeJournalEntries("api/dcim/devices/", deviceId).map { entries ->
            entries.mapNotNull { it.toJournalEntryUi() }
        }

    val journalEntries: StateFlow<List<JournalEntryUi>> =
        journalEntriesFlow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    private val changelogFlow =
        dashboardRepository.observeChangelog(NetBoxRef.DEVICES_ENDPOINT_PATH, deviceId)

    val changelog: StateFlow<List<ObjectChangeEntity>> =
        changelogFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val cachedTopologyGraph = MutableStateFlow<TopologyGraph?>(null)

    private val allDevicesFlow = deviceRepository.observeDevices("")

    /** Resolve graph neighbors against the typed cache so this tab never needs a live lookup. */
    val connectedDevices: StateFlow<List<DeviceEntity>> =
        combine(device, allDevicesFlow, cachedTopologyGraph) { current, devices, graph ->
                connectedDevicesFor(current, devices, graph)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _journalMutationState = MutableStateFlow(JournalMutationUiState())
    val journalMutationState: StateFlow<JournalMutationUiState> =
        _journalMutationState.asStateFlow()

    private val relatedObjectFlows: Map<String, Flow<List<NetBoxObjectEntity>>> =
        DEVICE_RELATED_TABS.filterNot {
                it.endpointPath == JOURNAL_TAB_ENDPOINT_PATH ||
                    it.endpointPath == CONNECTED_DEVICES_TAB_ENDPOINT_PATH
            }
            .associate { tab ->
                tab.endpointPath to
                    genericObjectRepository.observeObjects(tab.endpointPath, "", "device", deviceId)
            }

    val relatedObjects: Map<String, StateFlow<List<NetBoxObjectEntity>>> =
        DEVICE_RELATED_TABS.associate { tab ->
            tab.endpointPath to
                (relatedObjectFlows[tab.endpointPath] ?: flowOf(emptyList())).stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5000),
                    emptyList(),
                )
        }

    private val topologyGraphChecked = MutableStateFlow(false)

    /**
     * Whether every source that decides which related tab shows up has produced its first real
     * emission - the counts backing tab visibility all start out as `emptyList()`/`false`
     * placeholders (see the `stateIn` calls above), so composing the tab bar before this settles
     * shows a partial tab set that then grows and reflows as each cached query lands, which is
     * exactly what makes tabs land under the wrong finger on slower phones.
     */
    val tabsReady: StateFlow<Boolean> =
        combine(
                buildList {
                    add(journalEntriesFlow.map { true })
                    add(changelogFlow.map { true })
                    add(allDevicesFlow.map { true })
                    add(topologyPluginAvailableFlow.map { true })
                    add(topologyGraphChecked)
                    relatedObjectFlows.values.forEach { add(it.map { true }) }
                }
            ) { flags ->
                flags.all { it }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val interfaceIpAddresses: StateFlow<Map<Int, List<InterfaceIpAddress>>> =
        genericObjectRepository
            .observeObjects(IP_ADDRESSES_ENDPOINT_PATH, "")
            .map { objects ->
                buildMap {
                    objects.forEach { objectEntity ->
                        parseInterfaceIpAddress(objectEntity.id, objectEntity.json)?.let {
                            assignment ->
                            getOrPut(assignment.interfaceId) { mutableListOf() }
                                .add(assignment.ipAddress)
                        }
                    }
                }
                    .mapValues { (_, addresses) -> addresses.distinctBy { it.id } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            device.filterNotNull().take(1).collect { recentVisitRepository.record(it) }
        }
        viewModelScope.launch { refreshJournal() }
        viewModelScope.launch {
            topologyRepository.cached().onSuccess { snapshot ->
                cachedTopologyGraph.value = snapshot?.graph
            }
            topologyGraphChecked.value = true
        }
    }

    /**
     * Targeted device sync via [TargetedSyncEngine]: the device row itself, then (only if that
     * succeeds) everything meaningfully linked to it - device type, interfaces/ports/IP addresses,
     * cables on those interfaces, and the device on the other end of each cable - fanned out
     * concurrently rather than a full unscoped sync pass. Image attachments and the journal aren't
     * part of that generic link graph, so they're refreshed alongside it here.
     */
    fun refresh(showConfirmation: Boolean = false) {
        if (settingsRepository.offlineMode.value) return
        viewModelScope.launch {
            if (shouldShowRefreshQueuedToast(showConfirmation, offlineMode = false)) {
                _refreshToastMessage.value = REFRESH_QUEUED_TOAST
            }
            _isRefreshing.value = true
            val syncResult = targetedSyncEngine.sync(NetBoxRef.DEVICES_ENDPOINT_PATH, deviceId)
            if (!syncResult.rootSucceeded) {
                _errorMessage.value = "Couldn't refresh - showing cached data"
            }
            imageAttachmentRepository.refresh(DEVICE_OBJECT_TYPE, deviceId).onFailure {
                Timber.w(it, "Couldn't refresh image attachments for device %d", deviceId)
            }
            if (showConfirmation) {
                _refreshToastMessage.value =
                    targetedSyncToast(
                        primarySucceeded = syncResult.rootSucceeded,
                        failureCount = syncResult.otherFailureCount,
                    )
            }
            refreshJournal()
            _isRefreshing.value = false
        }
    }

    fun refreshJournal() {
        viewModelScope.launch {
            journalEntryRepository.fetchJournalEntries("api/dcim/devices/", deviceId)
        }
    }

    fun saveJournalEntry(entry: JournalEntryUi?, kind: String, comments: String) {
        if (_journalMutationState.value.isSaving) return
        viewModelScope.launch {
            _journalMutationState.value = JournalMutationUiState(isSaving = true)
            val result =
                if (entry == null) {
                    journalEntryRepository.createJournalEntry(
                        endpointPath = "api/dcim/devices/",
                        objectId = deviceId,
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
        viewModelScope.launch {
            _isDeleting.value = true
            pendingEditRepository
                .deleteObject(
                    endpointPath = "api/dcim/devices/",
                    id = deviceId,
                    offline = settingsRepository.offlineMode.value,
                )
                .onSuccess { result ->
                    deviceRepository.removeCachedDevice(deviceId)
                    _deleteResult.value = result
                }
                .onFailure { _errorMessage.value = it.message ?: "Couldn't delete device" }
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
                else dashboardRepository.addBookmark("api/dcim/devices/", deviceId)
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
                .onFailure { Timber.w(it, "Couldn't delete NetBox document %d", document.id) }
            _isDeleting.value = false
        }
    }

    fun documentDeleteResultShown() {
        _documentDeleteResult.value = null
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

    private fun apiUrlToWebUrl(apiUrl: HttpUrl): String =
        apiUrl.newBuilder().encodedPath(apiUrl.encodedPath.removePrefix("/api")).build().toString()

    private fun apiUrlToBaseUrl(apiUrl: HttpUrl): String =
        apiUrl.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
}
