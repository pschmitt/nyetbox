package dev.pschmitt.nyetbox.ui.directory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.widget.WidgetUpdater
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DirectoryViewModel
@Inject
constructor(
    private val directoryRepository: DirectoryRepository,
    val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val widgetUpdater: WidgetUpdater,
) : ViewModel() {

    val modelsByApp: StateFlow<Map<String, List<NetBoxModelEntity>>> =
        directoryRepository
            .observeAll()
            .map { models -> models.groupBy { it.appKey } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val sidebarAppOrder: StateFlow<List<String>> = settingsRepository.sidebarAppOrder
    val sidebarModelOrders: StateFlow<Map<String, List<String>>> =
        settingsRepository.sidebarModelOrders
    val hiddenSidebarApps: StateFlow<Set<String>> = settingsRepository.hiddenSidebarApps

    val pinnedModels: StateFlow<List<NetBoxModelEntity>> =
        settingsRepository.pinnedModelPaths
            .flatMapLatest { paths -> directoryRepository.observePinned(paths) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun refresh() {
        if (settingsRepository.offlineMode.value) return
        viewModelScope.launch { directoryRepository.refresh() }
    }

    fun togglePinned(endpointPath: String) {
        settingsRepository.togglePinned(endpointPath)
    }

    fun setSidebarAppOrder(order: List<String>) {
        settingsRepository.setSidebarAppOrder(order)
    }

    fun setSidebarModelOrder(appKey: String, order: List<String>) {
        settingsRepository.setSidebarModelOrder(appKey, order)
    }

    fun setSidebarAppHidden(appKey: String, hidden: Boolean) {
        settingsRepository.setSidebarAppHidden(appKey, hidden)
    }

    fun setOfflineMode(enabled: Boolean) {
        settingsRepository.setOfflineMode(enabled)
        if (!enabled) syncScheduler.syncNow()
        viewModelScope.launch { widgetUpdater.updateAll() }
    }
}
