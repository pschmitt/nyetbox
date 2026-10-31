package dev.pschmitt.nyetbox.ui.common

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.repository.CachedDocument
import dev.pschmitt.nyetbox.data.repository.DeviceTypePhotoFace
import dev.pschmitt.nyetbox.data.repository.DirectoryRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.MediaUploadRepository
import dev.pschmitt.nyetbox.data.repository.PendingEditRepository
import dev.pschmitt.nyetbox.data.schema.documentTypePresentation
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class MediaDocumentTypeOption(val value: String, val label: String)

enum class MediaUploadKind(val label: String) {
    ImageAttachment("Image attachment"),
    DeviceTypeFront("Device-type front photo"),
    DeviceTypeRear("Device-type rear photo"),
    Document("NetBox document"),
}

data class MediaUploadUiState(
    val isUploading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaUploadViewModel
@Inject
constructor(
    private val repository: MediaUploadRepository,
    private val pendingEditRepository: PendingEditRepository,
    directoryRepository: DirectoryRepository,
    genericObjectRepository: GenericObjectRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MediaUploadUiState())
    val state: StateFlow<MediaUploadUiState> = _state.asStateFlow()

    private val documentModels =
        directoryRepository
            .observeAll()
            .map { models -> models.filter(::isDocumentModel) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val documentEndpointPath: StateFlow<String?> =
        documentModels
            .map { models -> models.firstOrNull { !it.modelKey.contains("type") }?.endpointPath }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val documentTypeOptions: StateFlow<List<MediaDocumentTypeOption>> =
        documentModels
            .flatMapLatest { models ->
                val typeModel = models.firstOrNull { it.modelKey.contains("type") }
                val documentModel = models.firstOrNull { !it.modelKey.contains("type") }
                when {
                    typeModel != null ->
                        genericObjectRepository.observeObjects(typeModel.endpointPath, "").map {
                            objects ->
                            objects.map { MediaDocumentTypeOption(it.id.toString(), it.display) }
                        }
                    documentModel != null ->
                        genericObjectRepository
                            .observeObjects(documentModel.endpointPath, "")
                            .map(::documentTypeOptionsFromDocuments)
                    else -> flowOf(emptyList())
                }
            }
            .map { options -> (options + DEFAULT_DOCUMENT_TYPES).distinctBy { it.value } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun upload(
        kind: MediaUploadKind,
        endpointPath: String,
        objectId: Int,
        uri: Uri,
        filename: String,
        documentEndpointPath: String?,
        documentTypeValue: String?,
        imageAttachmentId: Int? = null,
        onUploaded: () -> Unit,
    ) {
        if (_state.value.isUploading) return
        viewModelScope.launch {
            _state.value = MediaUploadUiState(isUploading = true)
            val result =
                when (kind) {
                    MediaUploadKind.ImageAttachment ->
                        imageAttachmentId?.let {
                            repository.replaceImageAttachment(
                                endpointPath,
                                objectId,
                                it,
                                uri,
                                filename,
                            )
                        }
                            ?: repository.uploadImageAttachment(
                                endpointPath,
                                objectId,
                                uri,
                                filename,
                            )
                    MediaUploadKind.DeviceTypeFront,
                    MediaUploadKind.DeviceTypeRear -> {
                        if (endpointPath != "api/dcim/device-types/") {
                            Result.failure(
                                IllegalArgumentException("Device-type photos require a device type")
                            )
                        } else {
                            repository.uploadDeviceTypePhoto(
                                objectId,
                                if (kind == MediaUploadKind.DeviceTypeFront)
                                    DeviceTypePhotoFace.Front
                                else DeviceTypePhotoFace.Rear,
                                uri,
                                filename,
                            )
                        }
                    }
                    MediaUploadKind.Document -> {
                        when {
                            documentEndpointPath == null ->
                                Result.failure(
                                    IllegalStateException(
                                        "NetBox Documents is not available in the cache"
                                    )
                                )
                            documentTypeValue == null ->
                                Result.failure(IllegalArgumentException("Choose a document type"))
                            else ->
                                repository.uploadDocument(
                                    documentEndpointPath,
                                    endpointPath,
                                    objectId,
                                    uri,
                                    filename,
                                    documentTypeValue,
                                )
                        }
                    }
                }
            result
                .onSuccess {
                    _state.value = MediaUploadUiState(message = "Uploaded")
                    onUploaded()
                }
                .onFailure { error ->
                    _state.value =
                        MediaUploadUiState(error = error.message ?: "Couldn't upload the file")
                }
        }
    }

    /** PATCHes a document's metadata (type/comments) - queues offline like any other edit. */
    fun editDocument(
        document: CachedDocument,
        documentTypeValue: String?,
        comments: String?,
        onSaved: () -> Unit,
    ) {
        val endpointPath = documentEndpointPath.value
        if (endpointPath == null) {
            _state.value =
                MediaUploadUiState(error = "NetBox Documents is not available in the cache")
            return
        }
        viewModelScope.launch {
            _state.value = MediaUploadUiState(isUploading = true)
            val patch = buildJsonObject {
                documentTypeValue?.let { put("document_type", it) }
                put("comments", comments.orEmpty())
            }
            pendingEditRepository
                .submitEdit(endpointPath, document.id, document.rawJson, patch)
                .onSuccess {
                    _state.value = MediaUploadUiState(message = "Saved")
                    onSaved()
                }
                .onFailure { error ->
                    _state.value =
                        MediaUploadUiState(error = error.message ?: "Couldn't save changes")
                }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    private fun isDocumentModel(model: NetBoxModelEntity): Boolean =
        model.appKey.contains("document", ignoreCase = true) &&
            model.modelKey.contains("document", ignoreCase = true)
}

private val mediaJson = Json { ignoreUnknownKeys = true }

private val DEFAULT_DOCUMENT_TYPES =
    listOf(
        MediaDocumentTypeOption("manual", "Manual"),
        MediaDocumentTypeOption("purchaseorder", "Purchase order"),
        MediaDocumentTypeOption("other", "Other"),
    )

private fun documentTypeOptionsFromDocuments(
    objects: List<dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity>
): List<MediaDocumentTypeOption> =
    objects
        .mapNotNull { entity ->
            val raw =
                runCatching {
                        mediaJson
                            .decodeFromString(JsonObject.serializer(), entity.json)["document_type"]
                    }
                    .getOrNull()
            val value =
                when (raw) {
                    is JsonPrimitive -> raw.contentOrNull
                    is JsonObject -> raw["value"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val labelCandidates =
                when (raw) {
                    is JsonObject ->
                        listOfNotNull(
                            raw["value"]?.jsonPrimitive?.contentOrNull,
                            raw["label"]?.jsonPrimitive?.contentOrNull,
                            raw["display"]?.jsonPrimitive?.contentOrNull,
                        )
                    else -> emptyList()
                } + value
            val label =
                labelCandidates.firstNotNullOfOrNull { candidate ->
                    candidate.takeIf { it.any(Char::isLetter) }?.let(::documentTypeLabel)
                } ?: documentTypeLabel(value)
            MediaDocumentTypeOption(value, label)
        }
        .distinctBy { it.value }

private fun documentTypeLabel(value: String): String =
    documentTypePresentation(value)?.label
        ?: value.replace('_', ' ').replaceFirstChar { it.uppercase() }
