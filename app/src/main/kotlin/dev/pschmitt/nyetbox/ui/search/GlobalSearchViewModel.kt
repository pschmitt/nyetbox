package dev.pschmitt.nyetbox.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.DeviceLookup
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.GlobalSearchRepository
import dev.pschmitt.nyetbox.data.repository.RecentVisitRepository
import dev.pschmitt.nyetbox.data.repository.SearchHit
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.queryRemainderAfterTypeSelection
import dev.pschmitt.nyetbox.data.repository.rankSearchHits
import dev.pschmitt.nyetbox.data.repository.recentVisitsToSearchHits
import dev.pschmitt.nyetbox.data.repository.typeFilterSuggestions
import dev.pschmitt.nyetbox.ui.common.CacheFirstRefreshState
import dev.pschmitt.nyetbox.ui.common.runCacheFirstRefresh
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchThumbnail(val url: String)

internal fun shouldRefreshGlobalSearch(queryText: String, offlineMode: Boolean): Boolean =
    queryText.isNotBlank() && !offlineMode

/**
 * Backs [GlobalSearchScreen] (NBC-13) - debounced free-text search, cache-first like every other
 * screen in this app (see [GlobalSearchRepository]'s doc comment). [results] reads straight from
 * Room so it's instant and offline-capable; [refresh] is a best-effort network pass that widens
 * results and feeds the cache, mirroring `GenericListViewModel`'s `objects`/`refresh()` split.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalSearchViewModel
@Inject
constructor(
    private val searchRepository: GlobalSearchRepository,
    private val deviceRepository: DeviceRepository,
    private val genericObjectRepository: GenericObjectRepository,
    directoryRepository: DirectoryRepository,
    private val settingsRepository: SettingsRepository,
    recentVisitRepository: RecentVisitRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _typeFilter = MutableStateFlow<NetBoxModelEntity?>(null)
    val typeFilter: StateFlow<NetBoxModelEntity?> = _typeFilter.asStateFlow()
    val objectTypeAccents = settingsRepository.objectTypeAccents

    private val debouncedQuery: StateFlow<String> =
        _query
            .debounce(300)
            .map { it.trim() }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Cache-first, offline-capable - re-emits automatically once [refresh] (or any other sync)
     * upserts new rows into Room, the same "Flow straight from the DAO" shape
     * `GenericListViewModel` uses, not a one-shot network call.
     */
    private val cachedResults =
        combine(debouncedQuery, _typeFilter) { text, model -> text to model?.endpointPath }
            .flatMapLatest { (text, endpointPath) ->
                if (text.isBlank() && endpointPath == null) {
                    flowOf(emptyList())
                } else {
                    searchRepository.observeCached(text, endpointPath)
                }
            }

    val results: StateFlow<List<SearchHit>> =
        kotlinx.coroutines.flow
            .combine(debouncedQuery, cachedResults) { text, hits -> rankSearchHits(text, hits) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentResults: StateFlow<List<SearchHit>> =
        recentVisitRepository
            .observeRecent()
            .map(::recentVisitsToSearchHits)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devicesById: StateFlow<Map<Int, DeviceLookup>> =
        deviceRepository
            .observeDeviceLookup()
            .map { devices -> devices.associateBy { it.id } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Sourced from the device type's own generically-synced object, not the DeviceTypeEntity
    // cache table - that table is only populated for device types referenced by a synced Device,
    // so a device type with zero devices would never surface a thumbnail here otherwise.
    val deviceTypeFrontImagesById: StateFlow<Map<Int, String>> =
        genericObjectRepository
            .observeThumbnails(GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val knownModels: StateFlow<List<NetBoxModelEntity>> =
        directoryRepository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val typeSuggestions: StateFlow<List<NetBoxModelEntity>> =
        combine(_query, _typeFilter, knownModels) { text, selected, models ->
                if (selected == null) typeFilterSuggestions(text, models) else emptyList()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _refreshState = MutableStateFlow(CacheFirstRefreshState())
    val isRefreshing: StateFlow<Boolean> =
        _refreshState
            .map { it.isRefreshing }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val errorMessage: StateFlow<String?> =
        _refreshState
            .map { it.errorMessage }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Every discovered cached model is available here so result rows and type completions can use
    // the same humanized labels and app icons as the sidebar.
    val modelsByEndpointPath: StateFlow<Map<String, NetBoxModelEntity>> =
        knownModels
            .map { models -> models.associateBy { it.endpointPath } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        // Best-effort network refresh per debounced query - purely additive: [results] above
        // already answers from the cache regardless of this succeeding, so a failure (offline,
        // ...) only gets a quiet message, never a blocking error - same "never gate on network"
        // rule NBC-18 established for the rest of the app.
        viewModelScope.launch {
            combine(debouncedQuery, settingsRepository.offlineMode) { text, offline ->
                    text to offline
                }
                .collectLatest { (text, offline) ->
                    if (!shouldRefreshGlobalSearch(text, offline)) return@collectLatest
                    val endpointPaths =
                        typeFilter.value?.endpointPath?.let(::listOf)
                            ?: (GlobalSearchRepository.BASELINE_ENDPOINT_PATHS +
                                    settingsRepository.pinnedModelPaths.value)
                                .distinct()
                    _refreshState.runCacheFirstRefresh(
                        operation = {
                            searchRepository.refresh(text, endpointPaths)
                            Result.success(Unit)
                        },
                        errorMessage = { "Live search refresh failed - showing cached results" },
                    )
                }
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun selectType(model: NetBoxModelEntity) {
        _typeFilter.value = model
        _query.value = queryRemainderAfterTypeSelection(_query.value)
    }

    fun clearTypeFilter() {
        _typeFilter.value = null
    }

    fun errorShown() {
        _refreshState.update { it.copy(errorMessage = null) }
    }

    fun thumbnailFor(
        hit: SearchHit,
        devicesById: Map<Int, DeviceLookup>,
        deviceTypeFrontImagesById: Map<Int, String>,
    ): SearchThumbnail? =
        when (hit.endpointPath) {
            GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH ->
                deviceTypeFrontImagesById[hit.id]?.let { url -> SearchThumbnail(url) }
            GlobalSearchRepository.DEVICES_ENDPOINT_PATH ->
                devicesById[hit.id]?.deviceTypeId?.let { deviceTypeId ->
                    deviceTypeFrontImagesById[deviceTypeId]?.let { url -> SearchThumbnail(url) }
                }
            else -> null
        }
}
