package dev.pschmitt.nyetbox.ui.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.ScannerLens
import dev.pschmitt.nyetbox.data.repository.ScannerRearLens
import dev.pschmitt.nyetbox.data.repository.ScannerResolution
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.scanner.NetBoxTarget
import dev.pschmitt.nyetbox.scanner.NetBoxUrlParser
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ScanResultState {
    data object Scanning : ScanResultState

    data object Resolving : ScanResultState

    data class Found(val target: NetBoxTarget) : ScanResultState

    data class NotRecognized(val raw: String) : ScanResultState

    data class NotFound(val assetTag: String) : ScanResultState
}

@HiltViewModel
class ScannerViewModel
@Inject
constructor(
    private val deviceRepository: DeviceRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanResultState>(ScanResultState.Scanning)
    val state: StateFlow<ScanResultState> = _state.asStateFlow()

    val scannerLens: StateFlow<ScannerLens> = settingsRepository.scannerLens
    val scannerRearLens: StateFlow<ScannerRearLens> = settingsRepository.scannerRearLens
    val scannerResolution: StateFlow<ScannerResolution> = settingsRepository.scannerResolution

    private var handled = false

    fun onCodeScanned(raw: String) {
        if (handled) return
        val target = NetBoxUrlParser.parse(raw)
        if (target == null) {
            val assetTag = NetBoxUrlParser.parseAssetTag(raw)
            if (assetTag == null) {
                _state.value = ScanResultState.NotRecognized(raw)
                return
            }
            handled = true
            _state.value = ScanResultState.Resolving
            viewModelScope.launch {
                val device = deviceRepository.findByAssetTag(assetTag)
                _state.value =
                    device?.let { ScanResultState.Found(NetBoxTarget.Device(it.id)) }
                        ?: ScanResultState.NotFound(assetTag)
            }
            return
        }
        handled = true
        _state.value = ScanResultState.Resolving
        viewModelScope.launch {
            // Best-effort refresh so a freshly scanned object is up to date - the detail screen
            // still works from the Room cache either way if this fails offline.
            val resolvedTarget =
                when (target) {
                    is NetBoxTarget.Device -> {
                        deviceRepository.refreshDevice(target.id)
                        target
                    }
                    is NetBoxTarget.DeviceAssetTag ->
                        deviceRepository.findByAssetTag(target.assetTag)?.let {
                            NetBoxTarget.Device(it.id)
                        }
                    is NetBoxTarget.Object -> {
                        genericObjectRepository.refreshObject(target.endpointPath, target.id)
                        target
                    }
                    is NetBoxTarget.Setup -> target
                }
            if (resolvedTarget == null && target is NetBoxTarget.DeviceAssetTag) {
                _state.value = ScanResultState.NotFound(target.assetTag)
            } else {
                _state.value = ScanResultState.Found(resolvedTarget ?: target)
            }
        }
    }

    fun reset() {
        handled = false
        _state.value = ScanResultState.Scanning
    }

    fun setScannerLens(lens: ScannerLens) {
        settingsRepository.setScannerLens(lens)
    }
}
