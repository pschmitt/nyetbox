package dev.pschmitt.nyetbox.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.sync.SyncHistoryRepository
import dev.pschmitt.nyetbox.sync.SyncOverview
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.sync.SyncStatusRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Backs [SyncDiagnosticsScreen] (NBC-455). */
@HiltViewModel
class SyncDiagnosticsViewModel
@Inject
constructor(
    syncStatusRepository: SyncStatusRepository,
    private val syncHistoryRepository: SyncHistoryRepository,
    private val syncScheduler: SyncScheduler,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val overview: StateFlow<SyncOverview> =
        syncStatusRepository.overview.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            SyncOverview(),
        )

    val offlineMode: StateFlow<Boolean> = settingsRepository.offlineMode

    val lastSuccessfulSyncAt: StateFlow<Long?> = settingsRepository.lastSuccessfulSyncAt

    fun syncNow() {
        if (!settingsRepository.offlineMode.value) {
            syncScheduler.syncNow(forceFullSync = true, force = true)
        }
    }

    fun clearHistory() {
        syncHistoryRepository.clear()
    }
}
