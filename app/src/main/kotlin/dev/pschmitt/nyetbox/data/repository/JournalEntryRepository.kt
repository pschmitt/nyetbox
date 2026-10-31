package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** NetBox's free-form timestamped notes attached to any object via a GenericForeignKey. */
const val JOURNAL_ENTRY_ENDPOINT_PATH = "api/extras/journal-entries/"

data class JournalMutationResult(val queued: Boolean)

/** Cache-first journal access and durable create/edit mutations for any NetBox object. */
@Singleton
class JournalEntryRepository
@Inject
constructor(
    private val api: GenericNetBoxApi,
    private val genericObjectRepository: GenericObjectRepository,
    private val pendingEditRepository: PendingEditRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val parser = Json { ignoreUnknownKeys = true }
    private var contentTypeChoicesCache: List<String>? = null

    fun observeJournalEntries(endpointPath: String, objectId: Int): Flow<List<JsonObject>> =
        genericObjectRepository
            .observeObjects(JOURNAL_ENTRY_ENDPOINT_PATH, "", "assigned_object_id", objectId)
            .map { entities ->
                entities
                    .mapNotNull { it.toJsonObject() }
                    .filter { it.matchesAssignedObject(endpointPath) }
                    .sortedByDescending {
                        (it["created"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    }
            }

    suspend fun fetchJournalEntries(endpointPath: String, objectId: Int): Result<List<JsonObject>> =
        runCatching {
            if (settingsRepository.offlineMode.value) return@runCatching emptyList()
            val assignedObjectType =
                resolveAssignedObjectType(endpointPath)
                    ?: inferredAssignedObjectType(endpointPath)
                    ?: return@runCatching emptyList()
            api.listObjects(
                    JOURNAL_ENTRY_ENDPOINT_PATH,
                    mapOf(
                        "assigned_object_type" to assignedObjectType,
                        "assigned_object_id" to objectId.toString(),
                        "ordering" to "-created",
                    ),
                )
                .results
                .onEach {
                    genericObjectRepository.cacheLocalObject(JOURNAL_ENTRY_ENDPOINT_PATH, it)
                }
        }

    suspend fun createJournalEntry(
        endpointPath: String,
        objectId: Int,
        kind: String,
        comments: String,
        offline: Boolean,
    ): Result<JournalMutationResult> {
        val assignedObjectType =
            if (offline) inferredAssignedObjectType(endpointPath)
            else resolveAssignedObjectType(endpointPath) ?: inferredAssignedObjectType(endpointPath)
        if (assignedObjectType == null) {
            return Result.failure(
                IllegalArgumentException("Couldn't resolve the item's content type")
            )
        }
        val body =
            JsonObject(
                mapOf(
                    "assigned_object_type" to JsonPrimitive(assignedObjectType),
                    "assigned_object_id" to JsonPrimitive(objectId),
                    "kind" to JsonPrimitive(kind),
                    "comments" to JsonPrimitive(comments),
                )
            )
        return pendingEditRepository.submitCreate(JOURNAL_ENTRY_ENDPOINT_PATH, body, offline).map {
            JournalMutationResult(it is CreateSubmission.Queued)
        }
    }

    suspend fun updateJournalEntry(
        id: Int,
        baseJson: String,
        kind: String,
        comments: String,
    ): Result<JournalMutationResult> {
        val patch =
            JsonObject(
                mapOf(
                    "kind" to JsonPrimitive(kind),
                    "comments" to JsonPrimitive(comments),
                )
            )
        return pendingEditRepository
            .submitEdit(JOURNAL_ENTRY_ENDPOINT_PATH, id, baseJson, patch)
            .map { JournalMutationResult(it is EditSubmission.Queued) }
    }

    private fun NetBoxObjectEntity.toJsonObject(): JsonObject? =
        runCatching {
                parser.decodeFromString(JsonObject.serializer(), json)
            }
            .getOrNull()

    private fun JsonObject.matchesAssignedObject(endpointPath: String): Boolean {
        val assignedObjectType = this["assigned_object_type"] ?: return true
        val expected = inferredAssignedObjectType(endpointPath) ?: return true
        return when (assignedObjectType) {
            is JsonPrimitive -> assignedObjectType.contentOrNull == expected
            is JsonObject -> {
                val app = assignedObjectType["app_label"]?.jsonPrimitive?.contentOrNull
                val model = assignedObjectType["model"]?.jsonPrimitive?.contentOrNull
                (app != null && model != null && "$app.$model" == expected) ||
                    assignedObjectType.values.any { value ->
                        (value as? JsonPrimitive)?.contentOrNull == expected
                    }
            }
            else -> true
        }
    }

    private fun inferredAssignedObjectType(endpointPath: String): String? {
        val segments = endpointPath.trim('/').split('/')
        if (segments.size < 3) return null
        val appKey =
            if (segments[1] == "plugins" && segments.size >= 4) segments[2] else segments[1]
        val normalized = segments.last().replace("-", "").replace("_", "").lowercase()
        val model =
            when {
                normalized.endsWith("ies") -> normalized.dropLast(3) + "y"
                normalized.endsWith("ses") -> normalized.dropLast(2)
                normalized.endsWith("s") -> normalized.dropLast(1)
                else -> normalized
            }
        return "$appKey.$model"
    }

    private suspend fun resolveAssignedObjectType(endpointPath: String): String? {
        val segments = endpointPath.trim('/').split('/')
        if (segments.size < 3) return null
        val appKey =
            if (segments[1] == "plugins" && segments.size >= 4) segments[2] else segments[1]
        val modelKey = segments.last()
        val normalized = modelKey.replace("-", "").replace("_", "").lowercase()
        val candidates = buildSet {
            add(normalized)
            add(normalized.removeSuffix("s"))
            if (normalized.endsWith("es")) add(normalized.dropLast(2))
            if (normalized.endsWith("ies")) add(normalized.dropLast(3) + "y")
        }
        return contentTypeChoices().firstOrNull { choice ->
            val parts = choice.split(".", limit = 2)
            parts.size == 2 && parts[0] == appKey && parts[1] in candidates
        }
    }

    private suspend fun contentTypeChoices(): List<String> =
        contentTypeChoicesCache
            ?: runCatching {
                    val options = api.getJournalEntryOptions()
                    options["actions"]
                        ?.jsonObject
                        ?.get("PUT")
                        ?.jsonObject
                        ?.get("assigned_object_type")
                        ?.jsonObject
                        ?.get("choices")
                        ?.jsonArray
                        ?.mapNotNull {
                            (it as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                        }
                        .orEmpty()
                }
                .getOrDefault(emptyList())
                .also { contentTypeChoicesCache = it }
}
