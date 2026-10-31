package dev.pschmitt.nyetbox.ui.pending

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.PendingEditEntity
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@HiltViewModel
class PendingChangesViewModel
@Inject
constructor(
    private val repository: PendingEditRepository,
    private val json: Json,
) : ViewModel() {
    val changes: StateFlow<List<PendingEditEntity>> =
        repository
            .observeQueuedMutations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val count: StateFlow<Int> =
        changes
            .map { it.size }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                0,
            )

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun display(edit: PendingEditEntity): String {
        val objectJson = decode(edit.localJson)
        return sequenceOf("name", "model", "label", "serial", "asset_tag", "display")
            .mapNotNull { key -> (objectJson[key] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() } ?: "NetBox item #${edit.id}"
    }

    fun kind(edit: PendingEditEntity): String =
        when (edit.state) {
            PendingEditEntity.CREATE_QUEUED -> "Created offline"
            PendingEditEntity.DELETE_QUEUED -> "Deleted offline"
            else -> "Edited offline"
        }

    fun revert(edit: PendingEditEntity) {
        viewModelScope.launch {
            repository.revertPending(edit)
            _message.value = "Local change reverted"
        }
    }

    fun revertAll() {
        viewModelScope.launch {
            val total = changes.value.size
            repository.revertAllPending()
            _message.value = "$total local change${if (total == 1) "" else "s"} reverted"
        }
    }

    fun messageShown() {
        _message.value = null
    }

    private fun decode(raw: String): JsonObject =
        runCatching {
                json.decodeFromString(JsonObject.serializer(), raw)
            }
            .getOrDefault(JsonObject(emptyMap()))
}
