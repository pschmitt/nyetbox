package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.schema.documentTypePresentation
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

data class CachedDocument(
    val id: Int,
    val name: String,
    val filename: String,
    val documentUrl: String?,
    val externalUrl: String?,
    val documentType: String?,
    val comments: String?,
    /** Raw NetBox choice value (e.g. "manual"), unlike [documentType]'s display label. */
    val documentTypeValue: String?,
    /** The object's raw JSON, needed as [PendingEditRepository.submitEdit]'s `baseJson`. */
    val rawJson: String,
)

/** Cache-first access to records from the optional NetBox Documents plugin. */
@Singleton
class DocumentRepository
@Inject
constructor(
    private val directoryRepository: DirectoryRepository,
    private val genericObjectRepository: GenericObjectRepository,
    private val pendingEditRepository: PendingEditRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeFor(endpointPath: String, objectId: Int): Flow<List<CachedDocument>> =
        directoryRepository
            .observeAll()
            .map { models -> models.firstOrNull(::isDocumentsPluginModel)?.endpointPath }
            .distinctUntilChanged()
            .flatMapLatest { documentEndpointPath ->
                documentEndpointPath?.let { genericObjectRepository.observeObjects(it, "") }
                    ?: flowOf(emptyList())
            }
            .map { objects ->
                objects
                    .mapNotNull { entity ->
                        parseDocument(entity)?.takeIf {
                            it.assignedObjectMatches(endpointPath, objectId)
                        }
                    }
                    .sortedWith(
                        compareByDescending<CachedDocumentWithTarget> { it.created }
                            .thenBy { it.document.name }
                    )
                    .map { it.document }
            }

    /** Deletes a cached document immediately when possible, or queues it for offline sync. */
    suspend fun delete(documentId: Int, offline: Boolean): Result<DeleteSubmission> {
        val endpointPath =
            directoryRepository.cachedModels().firstOrNull(::isDocumentsPluginModel)?.endpointPath
                ?: return Result.failure(IllegalStateException("Documents plugin is unavailable"))
        return pendingEditRepository.deleteObject(endpointPath, documentId, offline)
    }

    private fun parseDocument(entity: NetBoxObjectEntity): CachedDocumentWithTarget? {
        val objectJson =
            runCatching {
                    json.decodeFromString(JsonObject.serializer(), entity.json)
                }
                .getOrNull() ?: return null
        val assignedObject = objectJson["assigned_object"] as? JsonObject
        val assignedUrl = assignedObject?.get("url")?.jsonPrimitive?.contentOrNull
        val assignedId =
            assignedObject?.get("id")?.jsonPrimitive?.intOrNull
                ?: objectJson["assigned_object_id"]?.jsonPrimitive?.intOrNull
                ?: return null
        val documentUrl = objectJson["document"]?.jsonPrimitive?.contentOrNull
        val externalUrl = objectJson["external_url"]?.jsonPrimitive?.contentOrNull
        val filename =
            objectJson["filename"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: documentUrl?.substringAfterLast('/')?.substringBefore('?')
                ?: entity.display
        val name =
            objectJson["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: entity.display
        val documentTypeCandidates =
            when (val rawType = objectJson["document_type"]) {
                is JsonObject ->
                    listOfNotNull(
                        rawType["value"]?.jsonPrimitive?.contentOrNull,
                        rawType["label"]?.jsonPrimitive?.contentOrNull,
                        rawType["display"]?.jsonPrimitive?.contentOrNull,
                    )
                else -> listOfNotNull(rawType?.jsonPrimitive?.contentOrNull)
            }
        val documentType = documentTypeCandidates.firstNotNullOfOrNull { candidate ->
            candidate
                .takeIf { it.any(Char::isLetter) }
                ?.let { type -> documentTypePresentation(type)?.label ?: type }
        }
        val documentTypeValue = documentTypeCandidates.firstOrNull()
        return CachedDocumentWithTarget(
            document =
                CachedDocument(
                    id = entity.id,
                    name = name,
                    filename = filename,
                    documentUrl = documentUrl?.takeIf(String::isNotBlank),
                    externalUrl = externalUrl?.takeIf(String::isNotBlank),
                    documentType = documentType,
                    comments = objectJson["comments"]?.jsonPrimitive?.contentOrNull,
                    documentTypeValue = documentTypeValue,
                    rawJson = entity.json,
                ),
            assignedId = assignedId,
            assignedUrl = assignedUrl,
            created = objectJson["created"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }
}

private data class CachedDocumentWithTarget(
    val document: CachedDocument,
    val assignedId: Int,
    val assignedUrl: String?,
    val created: String,
) {
    fun assignedObjectMatches(endpointPath: String, objectId: Int): Boolean {
        if (assignedId != objectId) return false
        val assignedUrl = assignedUrl ?: return true
        val route = endpointPath.removePrefix("api/").trim('/').trimEnd('/')
        val expectedSuffix = "/$route/$objectId"
        return assignedUrl.substringBefore('?').trimEnd('/').endsWith(expectedSuffix)
    }
}
