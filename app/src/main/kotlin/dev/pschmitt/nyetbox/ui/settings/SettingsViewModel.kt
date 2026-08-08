package dev.pschmitt.nyetbox.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.CacheDatabaseManager
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.repository.ChangeNotificationFilter
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.FileDownloadRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureShortcut
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import dev.pschmitt.nyetbox.data.repository.NetBoxCredentials
import dev.pschmitt.nyetbox.data.repository.NetBoxUserIdentity
import dev.pschmitt.nyetbox.data.repository.PrintSettings
import dev.pschmitt.nyetbox.data.repository.ScannerLens
import dev.pschmitt.nyetbox.data.repository.ScannerRearLens
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.schema.jsonInt
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.sync.SyncStatusRepository
import dev.pschmitt.nyetbox.widget.WidgetUpdater
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState

    data object Testing : ConnectionTestState

    data class Success(val message: String) : ConnectionTestState

    data class Failure(val message: String) : ConnectionTestState
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    val settingsRepository: SettingsRepository,
    private val deviceRepository: DeviceRepository,
    private val api: GenericNetBoxApi,
    private val syncScheduler: SyncScheduler,
    syncStatusRepository: SyncStatusRepository,
    private val directoryRepository: DirectoryRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val cacheDatabaseManager: CacheDatabaseManager,
    private val fileDownloadRepository: FileDownloadRepository,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    private val _isLoadingCurrentUser = MutableStateFlow(false)
    val isLoadingCurrentUser: StateFlow<Boolean> = _isLoadingCurrentUser.asStateFlow()

    val currentUser: StateFlow<NetBoxUserIdentity?> = settingsRepository.currentUser

    private val _connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val connectionTest: StateFlow<ConnectionTestState> = _connectionTest.asStateFlow()

    val isSyncing: StateFlow<Boolean> =
        syncStatusRepository.isSyncing.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    val gestureTargets: StateFlow<Map<GestureShortcut, GestureTarget>> =
        settingsRepository.gestureTargets

    val navBarItems: StateFlow<List<NavBarItem>> = settingsRepository.navBarItems

    val shortcutItems: StateFlow<List<NavBarItem>> = settingsRepository.shortcutItems

    val gestureModels: StateFlow<List<NetBoxModelEntity>> =
        directoryRepository
            .observeAll()
            .map { models ->
                models.distinctBy { it.endpointPath }.sortedBy { it.modelLabel.lowercase() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gestureObjects: StateFlow<List<NetBoxObjectEntity>> =
        genericObjectRepository
            .observeAllObjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdatingBaseUrl = MutableStateFlow(false)
    val isUpdatingBaseUrl: StateFlow<Boolean> = _isUpdatingBaseUrl.asStateFlow()

    private val _cachedDeviceCount = MutableStateFlow(0)
    val cachedDeviceCount: StateFlow<Int> = _cachedDeviceCount.asStateFlow()

    private val _cachedObjectCount = MutableStateFlow(0)
    val cachedObjectCount: StateFlow<Int> = _cachedObjectCount.asStateFlow()

    private val _cachedImageCount = MutableStateFlow(0)
    val cachedImageCount: StateFlow<Int> = _cachedImageCount.asStateFlow()

    private val _persistentCacheBytes = MutableStateFlow(0L)
    val persistentCacheBytes: StateFlow<Long> = _persistentCacheBytes.asStateFlow()

    private val _persistentCacheFiles = MutableStateFlow(0)
    val persistentCacheFiles: StateFlow<Int> = _persistentCacheFiles.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refreshCacheCounts()
        viewModelScope.launch {
            settingsRepository.credentials
                .map { it.isValid }
                .distinctUntilChanged()
                .collect { configured ->
                    if (configured) refreshCurrentUser() else settingsRepository.clearCurrentUser()
                }
        }
        viewModelScope.launch {
            isSyncing.drop(1).distinctUntilChanged().collect { syncing ->
                if (!syncing) refreshCacheCounts()
            }
        }
    }

    /**
     * The one deliberate exception to incremental sync: a user explicitly tapping "Sync now" is
     * asking for a genuinely current, fully-reconciled cache (including catching server-side
     * deletions an incremental sync can't see), not the fast incidental refresh every other caller
     * of syncNow() wants.
     */
    fun syncNow() {
        if (settingsRepository.offlineMode.value) return
        syncScheduler.syncNow(forceFullSync = true)
    }

    fun switchServer(id: String) {
        val profile = settingsRepository.serverProfiles.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            syncScheduler.cancelForServerSwitch()
            cacheDatabaseManager.switchTo(profile)
            settingsRepository.switchServer(id)
            refreshCacheCounts()
            if (!settingsRepository.offlineMode.value) {
                syncScheduler.schedulePeriodic()
                syncScheduler.syncNow()
            }
        }
    }

    fun addServer(baseUrl: String, token: String, displayName: String?) {
        if (baseUrl.isBlank() || token.isBlank()) return
        val previousId = settingsRepository.activeServerId.value
        val profile =
            runCatching { settingsRepository.addServer(baseUrl, token, displayName) }.getOrNull()
                ?: return
        viewModelScope.launch {
            cacheDatabaseManager.switchTo(profile)
            settingsRepository.switchServer(profile.id)
            if (settingsRepository.offlineMode.value) {
                refreshCacheCounts()
                return@launch
            }
            lookupCurrentUser(profile.credentials)
                .onSuccess {
                    settingsRepository.setCurrentUser(it)
                    syncScheduler.syncNow()
                }
                .onFailure {
                    cacheDatabaseManager.delete(profile)
                    fileDownloadRepository.deletePersistentCache(profile)
                    settingsRepository.removeServer(profile.id)
                    previousId?.let { settingsRepository.switchServer(it) }
                    previousId?.let { id ->
                        settingsRepository.serverProfiles.value
                            .firstOrNull { it.id == id }
                            ?.let { cacheDatabaseManager.switchTo(it) }
                    }
                    _errorMessage.value = it.connectionMessage()
                }
            refreshCacheCounts()
        }
    }

    fun updateServer(id: String, baseUrl: String, token: String, displayName: String?) {
        val previous = settingsRepository.serverProfiles.value.firstOrNull { it.id == id } ?: return
        val profile = settingsRepository.updateServer(id, baseUrl, token, displayName) ?: return
        if (settingsRepository.activeServerId.value != id) return
        viewModelScope.launch {
            cacheDatabaseManager.switchTo(profile)
            if (settingsRepository.offlineMode.value) return@launch
            lookupCurrentUser(profile.credentials)
                .onSuccess {
                    settingsRepository.setCurrentUser(it)
                    syncScheduler.syncNow()
                }
                .onFailure {
                    settingsRepository.updateServer(
                        previous.id,
                        previous.baseUrl,
                        previous.token,
                        previous.displayName,
                    )
                    _errorMessage.value = it.connectionMessage()
                }
            refreshCacheCounts()
        }
    }

    fun removeServer(id: String, onNoServers: () -> Unit = {}) {
        val profile = settingsRepository.serverProfiles.value.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            if (settingsRepository.activeServerId.value == id) syncScheduler.cancelForServerSwitch()
            cacheDatabaseManager.delete(profile)
            fileDownloadRepository.deletePersistentCache(profile)
            val removed = settingsRepository.removeServer(id) ?: return@launch
            val next = settingsRepository.activeServer.value
            if (next != null) {
                cacheDatabaseManager.switchTo(next)
                refreshCacheCounts()
            } else {
                onNoServers()
            }
        }
    }

    fun testConnection() {
        if (_connectionTest.value == ConnectionTestState.Testing) return
        val credentials = settingsRepository.credentials.value
        if (!credentials.isValid) {
            _connectionTest.value =
                ConnectionTestState.Failure("Configure a NetBox connection first")
            return
        }
        if (settingsRepository.offlineMode.value) {
            _connectionTest.value =
                ConnectionTestState.Failure(
                    "Offline mode is enabled. Turn it off to test the connection."
                )
            return
        }
        viewModelScope.launch {
            _connectionTest.value = ConnectionTestState.Testing
            lookupCurrentUser(credentials)
                .onSuccess {
                    settingsRepository.setCurrentUser(it)
                    _connectionTest.value =
                        ConnectionTestState.Success("Connected as ${it.summary}")
                }
                .onFailure {
                    _connectionTest.value = ConnectionTestState.Failure(it.connectionMessage())
                }
        }
    }

    private fun refreshCurrentUser() {
        if (settingsRepository.offlineMode.value) return
        viewModelScope.launch {
            val credentials = settingsRepository.credentials.value
            if (!credentials.isValid) return@launch
            _isLoadingCurrentUser.value = true
            lookupCurrentUser(credentials).onSuccess { settingsRepository.setCurrentUser(it) }
            _isLoadingCurrentUser.value = false
        }
    }

    private suspend fun lookupCurrentUser(
        credentials: NetBoxCredentials
    ): Result<NetBoxUserIdentity> = runCatching {
        parseCurrentUser(api.getAuthenticationCheck())
    }
        .recoverCatching {
            // NetBox 4.5 introduced authentication-check. Keep older instances usable by
            // falling
            // back to the v2 token-owner lookup when the endpoint is not available.
            val tokenKey = tokenKey(credentials.token) ?: error("Token owner lookup is unavailable")
            val tokenPage =
                api.listObjects(
                    url = "api/users/tokens/",
                    query = mapOf("key" to tokenKey, "limit" to "1"),
                )
            val user =
                tokenPage.results.firstOrNull()?.get("user") as? JsonObject
                    ?: error("The NetBox API did not return the token owner")
            parseCurrentUser(user)
        }

    private fun parseCurrentUser(user: JsonObject): NetBoxUserIdentity {
        val username =
            user.stringValue("username") ?: error("The authenticated user has no username")
        return NetBoxUserIdentity(
            username = username,
            fullName =
                listOfNotNull(user.stringValue("first_name"), user.stringValue("last_name"))
                    .joinToString(" ")
                    .takeIf { it.isNotBlank() },
            email = user.stringValue("email"),
            id = user.jsonInt("id"),
        )
    }

    private fun tokenKey(token: String): String? =
        Regex("^nb[a-z]+_([^.]*)\\..+$", RegexOption.IGNORE_CASE)
            .matchEntire(token)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

    private fun Throwable.connectionMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: "Couldn't reach that NetBox instance"

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun errorShown() {
        _errorMessage.value = null
    }

    private fun refreshCacheCounts() {
        viewModelScope.launch {
            _cachedDeviceCount.value = deviceRepository.cachedDeviceCount()
            val database = cacheDatabaseManager.activeDatabase.value
            _cachedObjectCount.value = database.netBoxObjectDao().countAll()
            _cachedImageCount.value =
                database.deviceTypeDao().countWithImages() + database.imageAttachmentDao().count()
            fileDownloadRepository.persistentStats().let { stats ->
                _persistentCacheBytes.value = stats.bytes
                _persistentCacheFiles.value = stats.fileCount
            }
        }
    }

    fun setSyncAttachmentsToDisk(enabled: Boolean) {
        settingsRepository.setSyncAttachmentsToDisk(enabled)
        if (enabled) syncNow()
    }

    fun setSyncOnlyOnWifi(enabled: Boolean) {
        settingsRepository.setSyncOnlyOnWifi(enabled)
        syncScheduler.schedulePeriodic()
    }

    fun setSyncWhileRoaming(enabled: Boolean) {
        settingsRepository.setSyncWhileRoaming(enabled)
        syncScheduler.schedulePeriodic()
    }

    fun setSyncOnAppLaunch(enabled: Boolean) {
        settingsRepository.setSyncOnAppLaunch(enabled)
        if (!enabled) syncScheduler.cancelStartup()
    }

    fun setChangeNotificationsEnabled(enabled: Boolean) {
        settingsRepository.setChangeNotificationsEnabled(enabled)
    }

    fun setChangeNotificationFilter(filter: ChangeNotificationFilter, enabled: Boolean) {
        settingsRepository.setChangeNotificationFilter(filter, enabled)
    }

    fun setGestureAction(action: GestureAction) {
        settingsRepository.setGestureAction(action)
    }

    fun setGestureAction(shortcut: GestureShortcut, action: GestureAction) {
        settingsRepository.setGestureAction(shortcut, action)
        if (action != GestureAction.AddSpecific && action != GestureAction.ListSpecific) {
            settingsRepository.clearGestureTarget(shortcut)
        }
    }

    fun setGestureTarget(shortcut: GestureShortcut, model: NetBoxModelEntity) {
        settingsRepository.setGestureTarget(
            shortcut,
            GestureTarget(endpointPath = model.endpointPath, label = model.modelLabel),
        )
    }

    fun setGestureDetailTarget(shortcut: GestureShortcut, obj: NetBoxObjectEntity) {
        settingsRepository.setGestureTarget(
            shortcut,
            GestureTarget(endpointPath = obj.endpointPath, id = obj.id, label = obj.display),
        )
    }

    fun addNavBarItem(action: GestureAction) {
        settingsRepository.setNavBarItems(navBarItems.value + NavBarItem(action))
    }

    fun addNavBarItem(action: GestureAction, model: NetBoxModelEntity) {
        val target = GestureTarget(endpointPath = model.endpointPath, label = model.modelLabel)
        settingsRepository.setNavBarItems(navBarItems.value + NavBarItem(action, target))
    }

    fun addNavBarItem(action: GestureAction, obj: NetBoxObjectEntity) {
        val target =
            GestureTarget(endpointPath = obj.endpointPath, id = obj.id, label = obj.display)
        settingsRepository.setNavBarItems(navBarItems.value + NavBarItem(action, target))
    }

    fun removeNavBarItem(index: Int) {
        settingsRepository.setNavBarItems(navBarItems.value.filterIndexed { i, _ -> i != index })
    }

    fun moveNavBarItem(from: Int, to: Int) {
        val items = navBarItems.value.toMutableList()
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        settingsRepository.setNavBarItems(items)
    }

    fun resetNavBarItems() {
        settingsRepository.resetNavBarItems()
    }

    fun addShortcutItem(action: GestureAction) {
        settingsRepository.setShortcutItems(shortcutItems.value + NavBarItem(action))
    }

    fun addShortcutItem(action: GestureAction, model: NetBoxModelEntity) {
        val target = GestureTarget(endpointPath = model.endpointPath, label = model.modelLabel)
        settingsRepository.setShortcutItems(shortcutItems.value + NavBarItem(action, target))
    }

    fun addShortcutItem(action: GestureAction, obj: NetBoxObjectEntity) {
        val target =
            GestureTarget(endpointPath = obj.endpointPath, id = obj.id, label = obj.display)
        settingsRepository.setShortcutItems(shortcutItems.value + NavBarItem(action, target))
    }

    fun removeShortcutItem(index: Int) {
        settingsRepository.setShortcutItems(
            shortcutItems.value.filterIndexed { i, _ -> i != index }
        )
    }

    fun moveShortcutItem(from: Int, to: Int) {
        val items = shortcutItems.value.toMutableList()
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        settingsRepository.setShortcutItems(items)
    }

    fun resetShortcutItems() {
        settingsRepository.resetShortcutItems()
    }

    fun setScannerLens(lens: ScannerLens) {
        settingsRepository.setScannerLens(lens)
    }

    fun setScannerRearLens(lens: ScannerRearLens) {
        settingsRepository.setScannerRearLens(lens)
    }

    fun updatePrintSettings(transform: (PrintSettings) -> PrintSettings) {
        settingsRepository.updatePrintSettings(transform(settingsRepository.printSettings.value))
    }

    fun setOfflineMode(enabled: Boolean) {
        settingsRepository.setOfflineMode(enabled)
        if (!enabled) {
            refreshCurrentUser()
            syncScheduler.syncNow()
        }
        viewModelScope.launch { widgetUpdater.updateAll() }
    }

    fun addHiddenField(key: String) {
        settingsRepository.addHiddenField(key)
    }

    fun removeHiddenField(key: String) {
        settingsRepository.removeHiddenField(key)
    }

    /**
     * Switches the configured NetBox server. Saves eagerly (the dynamic base-URL interceptor reads
     * from [SettingsRepository] reactively, so there's no other way to actually test the new URL)
     * then validates reachability, reverting back to the previous URL/token on failure rather than
     * leaving the app pointed at an unreachable instance - mirrors
     * `OnboardingViewModel.connect()`'s save-then-validate shape. Editing a profile preserves its
     * isolated cache; only explicit profile removal is destructive.
     */
    fun updateBaseUrl(newBaseUrl: String) {
        val previous = settingsRepository.credentials.value
        val trimmed = newBaseUrl.trim().trimEnd('/')
        if (trimmed.isBlank() || trimmed == previous.baseUrl) return
        viewModelScope.launch {
            _isUpdatingBaseUrl.value = true
            settingsRepository.save(trimmed, previous.token)
            directoryRepository
                .refresh()
                .onSuccess { refreshCacheCounts() }
                .onFailure {
                    settingsRepository.save(previous.baseUrl, previous.token)
                    _errorMessage.value =
                        it.message ?: "Couldn't reach that NetBox instance - reverted"
                }
            _isUpdatingBaseUrl.value = false
        }
    }

    fun logOut() {
        settingsRepository.clear()
    }
}
