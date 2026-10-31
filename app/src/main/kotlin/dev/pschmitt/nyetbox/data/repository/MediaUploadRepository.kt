package dev.pschmitt.nyetbox.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.api.MediaNetBoxApi
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class MediaUploadReceipt(val id: Int?)

/** Explicit, online-only multipart uploads. All reads after a successful upload refresh Room. */
@Singleton
class MediaUploadRepository
@Inject
constructor(
    private val api: MediaNetBoxApi,
    private val genericApi: GenericNetBoxApi,
    private val imageAttachmentRepository: ImageAttachmentRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val directoryRepository: DirectoryRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private var imageAttachmentContentTypesCache: List<String>? = null
    private var documentContentTypesCache: Pair<String, List<String>>? = null

    /**
     * Whether NetBox's `api/extras/image-attachments/` endpoint accepts this object type - not
     * every model (e.g. `users.permission`) supports image attachments. Determined the same way
     * [JournalEntryRepository] resolves `assigned_object_type`: DRF's `OPTIONS` response enumerates
     * a `ChoiceField`'s valid values, here for `object_type`. Fails open (returns true) when the
     * choices can't be determined - e.g. offline - so the upload option isn't hidden by a transient
     * failure.
     */
    suspend fun supportsImageAttachments(endpointPath: String): Boolean {
        val objectType =
            runCatching { contentTypeForEndpoint(endpointPath) }.getOrNull() ?: return true
        val choices =
            imageAttachmentContentTypesCache
                ?: objectTypeChoices(IMAGE_ATTACHMENTS_ENDPOINT_PATH).also {
                    imageAttachmentContentTypesCache = it
                }
        return choices.isEmpty() || objectType in choices
    }

    /** Same idea as [supportsImageAttachments], but for the (optional) NetBox Documents plugin. */
    suspend fun supportsDocuments(endpointPath: String): Boolean {
        val objectType =
            runCatching { contentTypeForEndpoint(endpointPath) }.getOrNull() ?: return true
        val docEndpointPath = documentEndpointPath() ?: return true
        val cached = documentContentTypesCache
        val choices =
            if (cached != null && cached.first == docEndpointPath) cached.second
            else
                objectTypeChoices(docEndpointPath).also {
                    documentContentTypesCache = docEndpointPath to it
                }
        return choices.isEmpty() || objectType in choices
    }

    private suspend fun documentEndpointPath(): String? =
        directoryRepository
            .cachedModels()
            .filter { model ->
                model.appKey.contains("document", ignoreCase = true) &&
                    model.modelKey.contains("document", ignoreCase = true)
            }
            .firstOrNull { model -> !model.modelKey.contains("type", ignoreCase = true) }
            ?.endpointPath

    private suspend fun objectTypeChoices(endpointPath: String): List<String> =
        runCatching {
                val options = genericApi.getObjectOptions(endpointPath)
                val actions = options["actions"]?.jsonObject
                (actions?.get("POST") ?: actions?.get("PUT"))
                    ?.jsonObject
                    ?.get("object_type")
                    ?.jsonObject
                    ?.get("choices")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty()
            }
            .getOrDefault(emptyList())

    suspend fun uploadImageAttachment(
        endpointPath: String,
        objectId: Int,
        uri: Uri,
        filename: String,
    ): Result<MediaUploadReceipt> =
        upload(
            operation = {
                val objectType = contentTypeForEndpoint(endpointPath)
                api.uploadFile(
                    "api/extras/image-attachments/",
                    filePart("image", uri, filename),
                    fields(
                        "object_type" to objectType,
                        "object_id" to objectId.toString(),
                        "name" to
                            filenameWithMimeExtension(
                                filename,
                                context.contentResolver.getType(uri),
                            ),
                    ),
                )
            },
            afterUpload = {
                imageAttachmentRepository.refresh(contentTypeForEndpoint(endpointPath), objectId)
            },
        )

    suspend fun replaceImageAttachment(
        endpointPath: String,
        objectId: Int,
        attachmentId: Int,
        uri: Uri,
        filename: String,
    ): Result<MediaUploadReceipt> =
        upload(
            operation = {
                api.patchFile(
                    "${IMAGE_ATTACHMENTS_ENDPOINT_PATH}$attachmentId/",
                    filePart("image", uri, filename),
                )
            },
            afterUpload = {
                imageAttachmentRepository.refresh(contentTypeForEndpoint(endpointPath), objectId)
            },
        )

    suspend fun uploadDeviceTypePhoto(
        deviceTypeId: Int,
        face: DeviceTypePhotoFace,
        uri: Uri,
        filename: String,
    ): Result<MediaUploadReceipt> =
        upload(
            operation = {
                api.patchFile(
                    "${NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH}$deviceTypeId/",
                    filePart(face.fieldName, uri, filename),
                )
            },
            afterUpload = {},
        )

    suspend fun uploadDocument(
        documentEndpointPath: String,
        objectEndpointPath: String,
        objectId: Int,
        uri: Uri,
        filename: String,
        documentTypeValue: String,
    ): Result<MediaUploadReceipt> =
        upload(
            operation = {
                api.uploadFile(
                    documentEndpointPath,
                    filePart("file", uri, filename),
                    fields(
                        "name" to
                            filenameWithMimeExtension(
                                filename,
                                context.contentResolver.getType(uri),
                            ),
                        "document_type" to documentTypeValue,
                        "object_type" to contentTypeForEndpoint(objectEndpointPath),
                        "object_id" to objectId.toString(),
                    ),
                )
            },
            afterUpload = { genericObjectRepository.syncAll(documentEndpointPath) },
        )

    private suspend fun upload(
        operation: suspend () -> kotlinx.serialization.json.JsonObject,
        afterUpload: suspend () -> Unit,
    ): Result<MediaUploadReceipt> =
        withContext(Dispatchers.IO) {
            if (settingsRepository.offlineMode.value) {
                return@withContext Result.failure(
                    IllegalStateException("Uploads are unavailable in offline mode")
                )
            }
            runCatching {
                val response = operation()
                afterUpload()
                MediaUploadReceipt(response["id"]?.toString()?.toIntOrNull())
            }
        }

    private fun fields(vararg values: Pair<String, String>): Map<String, RequestBody> =
        values.associate { (key, value) ->
            key to value.toRequestBody("text/plain".toMediaTypeOrNull())
        }

    private fun filePart(field: String, uri: Uri, filename: String): MultipartBody.Part {
        val resolver = context.contentResolver
        val mediaTypeName = resolver.getType(uri)
        val mediaType = mediaTypeName?.toMediaTypeOrNull()
        val uploadFilename = filenameWithMimeExtension(filename, mediaTypeName)
        val body =
            object : RequestBody() {
                override fun contentType() = mediaType

                override fun contentLength(): Long =
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                        ?: super.contentLength()

                override fun writeTo(sink: okio.BufferedSink) {
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Couldn't open selected file" }
                        input.copyTo(sink.outputStream())
                    }
                }
            }
        return MultipartBody.Part.createFormData(field, uploadFilename.safeFilename(), body)
    }

    private fun String.safeFilename(): String =
        substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload" }

    companion object {
        private const val IMAGE_ATTACHMENTS_ENDPOINT_PATH = "api/extras/image-attachments/"

        fun contentTypeForEndpoint(endpointPath: String): String {
            val segments = endpointPath.trim('/').split('/')
            require(segments.size >= 3) { "Unsupported NetBox endpoint: $endpointPath" }
            val app =
                if (segments[1] == "plugins" && segments.size >= 4) segments[2] else segments[1]
            val model = segments.last().removeSuffix("s").replace("-", "").replace("_", "")
            return "$app.$model"
        }

        /** Preserve a provider's filename, adding a MIME-derived extension when it is absent. */
        fun filenameWithMimeExtension(filename: String, mimeType: String?): String {
            val clean =
                filename.substringAfterLast('/').substringAfterLast('\\').ifBlank { "upload" }
            if (clean.substringAfterLast('.', "").isNotBlank()) return clean
            val extension =
                MIME_EXTENSIONS[mimeType?.substringBefore(';')?.lowercase()] ?: return clean
            return "$clean.$extension"
        }

        private val MIME_EXTENSIONS =
            mapOf(
                "application/pdf" to "pdf",
                "application/msword" to "doc",
                "application/rtf" to "rtf",
                "application/zip" to "zip",
                "application/x-7z-compressed" to "7z",
                "application/vnd.ms-excel" to "xls",
                "application/vnd.ms-powerpoint" to "ppt",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation" to
                    "pptx",
                "image/avif" to "avif",
                "image/bmp" to "bmp",
                "image/gif" to "gif",
                "image/heic" to "heic",
                "image/heif" to "heif",
                "image/jpeg" to "jpg",
                "image/jpg" to "jpg",
                "image/png" to "png",
                "image/webp" to "webp",
                "text/csv" to "csv",
                "text/plain" to "txt",
            )
    }
}

enum class DeviceTypePhotoFace(val fieldName: String, val label: String) {
    Front("front_image", "Front photo"),
    Rear("rear_image", "Rear photo"),
}
