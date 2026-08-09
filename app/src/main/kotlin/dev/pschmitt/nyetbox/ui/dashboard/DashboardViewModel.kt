package dev.pschmitt.nyetbox.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.BookmarkEntity
import dev.pschmitt.nyetbox.data.db.CacheDatabaseManager
import dev.pschmitt.nyetbox.data.db.DashboardStatEntity
import dev.pschmitt.nyetbox.data.db.DeviceLookup
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NewsItemEntity
import dev.pschmitt.nyetbox.data.db.ObjectChangeEntity
import dev.pschmitt.nyetbox.data.db.RecentVisitEntity
import dev.pschmitt.nyetbox.data.repository.DashboardRepository
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.FileDownloadRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.GlobalSearchRepository
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.repository.RecentVisitRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.sync.SyncProgress
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.sync.SyncStatusRepository
import dev.pschmitt.nyetbox.sync.shouldScheduleStartup
import dev.pschmitt.nyetbox.ui.common.CacheSummary
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DashboardThumbnail(val url: String)

@HiltViewModel
class DashboardViewModel
@Inject
constructor(
    private val repository: DashboardRepository,
    private val deviceRepository: DeviceRepository,
    private val genericObjectRepository: GenericObjectRepository,
    directoryRepository: DirectoryRepository,
    pendingEditRepository: PendingEditRepository,
    recentVisitRepository: RecentVisitRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    syncStatusRepository: SyncStatusRepository,
    private val cacheDatabaseManager: CacheDatabaseManager,
    private val fileDownloadRepository: FileDownloadRepository,
) : ViewModel() {

    private val _cacheSummary = MutableStateFlow<CacheSummary?>(null)
    val cacheSummary: StateFlow<CacheSummary?> = _cacheSummary.asStateFlow()

    fun loadCacheSummary() {
        viewModelScope.launch {
            val database = cacheDatabaseManager.activeDatabase.value
            val stats = fileDownloadRepository.persistentStats()
            _cacheSummary.value =
                CacheSummary(
                    deviceCount = deviceRepository.cachedDeviceCount(),
                    objectCount = database.netBoxObjectDao().countAll(),
                    imageCount =
                        database.deviceTypeDao().countWithImages() +
                            database.imageAttachmentDao().count(),
                    fileCount = stats.fileCount,
                    bytes = stats.bytes,
                )
        }
    }

    val offlineMode: StateFlow<Boolean> = settingsRepository.offlineMode
    val syncIssue = settingsRepository.syncIssue
    val activeServer = settingsRepository.activeServer
    val lastSuccessfulSyncAt = settingsRepository.lastSuccessfulSyncAt
    val lastSyncSummary = settingsRepository.lastSyncSummary
    val dashboardSectionOrder = settingsRepository.dashboardSectionOrder
    val hiddenDashboardSections = settingsRepository.hiddenDashboardSections
    val statsOrder = settingsRepository.statsOrder
    val hiddenStats = settingsRepository.hiddenStats
    val objectTypeAccents = settingsRepository.objectTypeAccents

    val modelsByEndpointPath: StateFlow<Map<String, NetBoxModelEntity>> =
        directoryRepository
            .observeAll()
            .map { models -> models.associateBy { it.endpointPath } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val isRefreshing: StateFlow<Boolean> =
        syncStatusRepository.isSyncing.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    // Richer step/message/item-count detail for the currently running sync (NBC-370), rendered by
    // SyncStatusCard instead of a generic spinner. SyncStatusRepository already holds this as its
    // own StateFlow, so re-exposing it here just gives DashboardScreen the same collection shape
    // as every other piece of dashboard state.
    val syncProgress: StateFlow<SyncProgress?> = syncStatusRepository.syncProgress

    // Blocks the dashboard behind a full-screen "setting up" overlay for a profile that has never
    // completed a sync, instead of letting every section flash its "nothing cached yet" empty
    // state during the gap between onboarding finishing and the startup sync worker actually
    // reaching WorkManager's RUNNING state (which isRefreshing alone can't cover, since it's only
    // true once the worker has already started). `shouldScheduleStartup` mirrors exactly what
    // SyncScheduler.scheduleStartup() itself checks, so this stays false for anyone who has sync-
    // on-launch disabled or is in offline mode - nothing will ever run for them, so nothing should
    // ever block them. `syncIssue != null` releases the overlay the moment a sync attempt fails,
    // so a broken first connection surfaces the normal SyncIssueCard/retry flow instead of hanging
    // forever; DashboardScreen adds its own short hard timeout as a last-resort safety net on top.
    val showInitialSyncOverlay: StateFlow<Boolean> =
        combine(
                lastSuccessfulSyncAt,
                isRefreshing,
                syncIssue,
                settingsRepository.syncOnAppLaunch,
                settingsRepository.offlineMode,
            ) { lastSync, refreshing, issue, syncOnLaunch, offline ->
                lastSync == null &&
                    issue == null &&
                    (refreshing || shouldScheduleStartup(syncOnLaunch, offline))
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                // Computed synchronously from each upstream StateFlow's current .value, not a
                // hardcoded false - Dashboard's very first composition reads this initial value
                // (combine's own first emission is async, one dispatcher hop later), so a
                // hardcoded false here would flash the overlay-less dashboard for a frame or two
                // on every fresh connect, reopening the exact race this whole thing exists to
                // close.
                initialValue =
                    lastSuccessfulSyncAt.value == null &&
                        syncIssue.value == null &&
                        (isRefreshing.value ||
                            shouldScheduleStartup(
                                settingsRepository.syncOnAppLaunch.value,
                                settingsRepository.offlineMode.value,
                            )),
            )

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val stats: StateFlow<List<DashboardStatEntity>> =
        repository
            .observeStats()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookmarks: StateFlow<List<BookmarkEntity>> =
        repository
            .observeBookmarks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val changelog: StateFlow<List<ObjectChangeEntity>> =
        repository
            .observeChangelog()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val news: StateFlow<List<NewsItemEntity>> =
        repository
            .observeNews()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentVisits: StateFlow<List<RecentVisitEntity>> =
        recentVisitRepository
            .observeRecent(limit = 50)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val devicesById: StateFlow<Map<Int, DeviceLookup>> =
        deviceRepository
            .observeDeviceLookup()
            .map { devices -> devices.associateBy { it.id } }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // Sourced from the device type's own generically-synced object, not the DeviceTypeEntity
    // cache table - that table is only populated for device types referenced by a synced Device,
    // so a device type with zero devices (e.g. one just added to NetBox) would never get a
    // thumbnail here otherwise.
    val deviceTypeFrontImagesById: StateFlow<Map<Int, String>> =
        genericObjectRepository
            .observeThumbnails(GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH)
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val conflictCount: StateFlow<Int> =
        pendingEditRepository
            .observeConflictCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val pendingChangeCount: StateFlow<Int> =
        pendingEditRepository
            .observeQueuedMutationCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun refresh() {
        if (!offlineMode.value) syncScheduler.syncNow()
    }

    fun errorShown() {
        _errorMessage.value = null
    }

    fun retrySync() {
        if (!offlineMode.value) syncScheduler.syncNow()
    }

    fun setDashboardSectionOrder(order: List<String>) {
        settingsRepository.setDashboardSectionOrder(order)
    }

    fun setDashboardSectionHidden(sectionKey: String, hidden: Boolean) {
        settingsRepository.setDashboardSectionHidden(sectionKey, hidden)
    }

    fun setStatsOrder(order: List<String>) {
        settingsRepository.setStatsOrder(order)
    }

    fun setStatHidden(endpointPath: String, hidden: Boolean) {
        settingsRepository.setStatHidden(endpointPath, hidden)
    }

    fun thumbnailFor(
        endpointPath: String,
        id: Int,
        devicesById: Map<Int, DeviceLookup>,
        deviceTypeFrontImagesById: Map<Int, String>,
    ): DashboardThumbnail? =
        when (endpointPath) {
            GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH ->
                deviceTypeFrontImagesById[id]?.let { url -> DashboardThumbnail(url) }
            GlobalSearchRepository.DEVICES_ENDPOINT_PATH ->
                devicesById[id]?.deviceTypeId?.let { deviceTypeId ->
                    deviceTypeFrontImagesById[deviceTypeId]?.let { url -> DashboardThumbnail(url) }
                }
            else -> null
        }
}
