package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.PendingEditDao
import dev.pschmitt.nyetbox.data.db.PendingEditEntity
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.HttpException
import timber.log.Timber

sealed interface EditSubmission {
    data object Updated : EditSubmission

    data object Queued : EditSubmission

    data object ConflictDetected : EditSubmission
}

sealed interface DeleteSubmission {
    data object Deleted : DeleteSubmission

    data object Queued : DeleteSubmission
}

sealed interface CreateSubmission {
    val objectJson: JsonObject

    data class Created(override val objectJson: JsonObject) : CreateSubmission

    data class Queued(override val objectJson: JsonObject) : CreateSubmission
}

data class ReconciledItem(
    val endpointPath: String,
    val id: Int,
    val display: String,
)

data class ReconciliationSummary(
    val created: List<ReconciledItem> = emptyList(),
    val edited: List<ReconciledItem> = emptyList(),
    val deleted: List<ReconciledItem> = emptyList(),
) {
    val total: Int
        get() = created.size + edited.size + deleted.size
}

data class PendingSyncResult(
    val reconciliation: ReconciliationSummary = ReconciliationSummary(),
    val retryableFailure: Throwable? = null,
)

class StaleConflictException : Exception("The server changed again; review the conflict once more")

/** Durable edit outbox and three-way conflict store for generic NetBox objects (NBC-32). */
@Singleton
class PendingEditRepository
@Inject
constructor(
    private val api: GenericNetBoxApi,
    private val pendingEditDao: PendingEditDao,
    private val genericObjectRepository: GenericObjectRepository,
    private val json: Json,
) {
    fun observeConflicts(): Flow<List<PendingEditEntity>> = pendingEditDao.observeConflicts()

    fun observeConflictCount(): Flow<Int> = pendingEditDao.observeConflictCount()

    fun observeQueuedMutations(): Flow<List<PendingEditEntity>> =
        pendingEditDao.observeQueuedMutations()

    fun observeQueuedMutationCount(): Flow<Int> = pendingEditDao.observeQueuedMutationCount()

    /**
     * Whether any local create/edit/delete is still waiting to be uploaded - the sync freshness
     * short-circuit (NBC-427) must never skip a pass while this is true, since that's the only path
     * that uploads a queued mutation.
     */
    suspend fun hasQueuedMutations(): Boolean = pendingEditDao.getQueuedMutations().isNotEmpty()

    /**
     * Drops one local mutation and restores the last server-backed snapshot when it was an edit.
     */
    suspend fun revertPending(edit: PendingEditEntity) {
        if (edit.state == PendingEditEntity.CREATE_QUEUED) {
            genericObjectRepository.removeCachedObject(edit.endpointPath, edit.id)
        } else if (
            edit.state == PendingEditEntity.QUEUED || edit.state == PendingEditEntity.DELETE_QUEUED
        ) {
            genericObjectRepository.cacheLocalObject(edit.endpointPath, decode(edit.baseJson))
        }
        pendingEditDao.delete(edit.endpointPath, edit.id)
    }

    suspend fun revertAllPending() {
        pendingEditDao.getQueuedMutations().forEach { revertPending(it) }
    }

    /** Creates immediately when possible, or stores a local object and POST body in the outbox. */
    suspend fun submitCreate(
        endpointPath: String,
        body: JsonObject,
        offline: Boolean,
    ): Result<CreateSubmission> {
        if (!offline) {
            try {
                return Result.success(
                    CreateSubmission.Created(
                        api.createObject(endpointPath, body).also {
                            genericObjectRepository.cacheLocalObject(endpointPath, it)
                        }
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // A transient connectivity loss is exactly the same durable outbox case as
                // explicitly enabled offline mode. Keep the form result usable immediately.
            } catch (error: HttpException) {
                if (error.code() < 500) return Result.failure(error)
                // Treat a temporary server-side failure like a connectivity loss. The next
                // scheduled run will retry the durable POST.
            } catch (error: Exception) {
                return Result.failure(error)
            }
        }
        return Result.success(queueCreate(endpointPath, body))
    }

    /** Checks the server version before PATCHing, or persists the edit when the network is down. */
    suspend fun submitEdit(
        endpointPath: String,
        id: Int,
        baseJson: String,
        patch: JsonObject,
    ): Result<EditSubmission> {
        val existing = pendingEditDao.get(endpointPath, id)
        if (existing?.state == PendingEditEntity.CREATE_QUEUED) {
            val local = withDisplay(merge(decode(existing.localJson), patch))
            val body = merge(decode(existing.patchJson), patch)
            pendingEditDao.upsert(
                existing.copy(localJson = encode(local), patchJson = encode(body))
            )
            genericObjectRepository.cacheLocalObject(endpointPath, local)
            return Result.success(EditSubmission.Queued)
        }
        val effectiveBase = existing?.baseJson ?: baseJson
        val effectiveLocal = merge(decode(existing?.localJson ?: baseJson), patch)
        val effectivePatch = merge(decode(existing?.patchJson ?: "{}"), patch)
        val edit =
            PendingEditEntity(
                endpointPath = endpointPath,
                id = id,
                baseJson = effectiveBase,
                localJson = encode(effectiveLocal),
                patchJson = encode(effectivePatch),
                state = PendingEditEntity.QUEUED,
                serverJson = null,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )

        return try {
            val server = api.getObject("$endpointPath$id/")
            if (hasChanged(decode(effectiveBase), server)) {
                pendingEditDao.upsert(
                    edit.copy(state = PendingEditEntity.CONFLICT, serverJson = encode(server))
                )
                genericObjectRepository.cacheLocalObject(endpointPath, effectiveLocal)
                Result.success(EditSubmission.ConflictDetected)
            } else {
                val updated = api.patchObject("$endpointPath$id/", effectivePatch)
                genericObjectRepository.cacheLocalObject(endpointPath, updated)
                pendingEditDao.delete(endpointPath, id)
                Result.success(EditSubmission.Updated)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            pendingEditDao.upsert(edit)
            genericObjectRepository.cacheLocalObject(endpointPath, effectiveLocal)
            Result.success(EditSubmission.Queued)
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Deletes an object immediately when possible, or hides it and queues the DELETE offline. */
    suspend fun deleteObject(
        endpointPath: String,
        id: Int,
        offline: Boolean,
    ): Result<DeleteSubmission> {
        val existing = pendingEditDao.get(endpointPath, id)
        if (existing?.state == PendingEditEntity.CREATE_QUEUED) {
            genericObjectRepository.removeCachedObject(endpointPath, id)
            pendingEditDao.delete(endpointPath, id)
            return Result.success(DeleteSubmission.Deleted)
        }

        val cached = genericObjectRepository.cachedObjects(endpointPath).firstOrNull { it.id == id }
        val baseJson = existing?.baseJson ?: cached?.json ?: "{}"
        if (offline) {
            queueDelete(endpointPath, id, baseJson, existing)
            return Result.success(DeleteSubmission.Queued)
        }

        return try {
            api.deleteObject("$endpointPath$id/")
            genericObjectRepository.removeCachedObject(endpointPath, id)
            pendingEditDao.delete(endpointPath, id)
            Result.success(DeleteSubmission.Deleted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            queueDelete(endpointPath, id, baseJson, existing)
            Result.success(DeleteSubmission.Queued)
        } catch (error: HttpException) {
            if (error.code() == 404) {
                genericObjectRepository.removeCachedObject(endpointPath, id)
                pendingEditDao.delete(endpointPath, id)
                Result.success(DeleteSubmission.Deleted)
            } else {
                Result.failure(error)
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Retries queued creates and edits before the normal cache sync can overwrite local views. */
    suspend fun syncPending(): PendingSyncResult {
        val created = mutableListOf<ReconciledItem>()
        val deleted = mutableListOf<ReconciledItem>()
        val edited = mutableListOf<ReconciledItem>()
        fun result(failure: Throwable? = null) =
            PendingSyncResult(
                reconciliation =
                    ReconciliationSummary(
                        created = created.toList(),
                        edited = edited.toList(),
                        deleted = deleted.toList(),
                    ),
                retryableFailure = failure,
            )

        for (edit in pendingEditDao.getQueuedCreates()) {
            try {
                val server = api.createObject(edit.endpointPath, decode(edit.patchJson))
                genericObjectRepository.cacheLocalObject(edit.endpointPath, server)
                // Replace the negative local-only cache row with the server-assigned ID. Without
                // this removal, a successful reconciliation would leave a duplicate local item.
                genericObjectRepository.removeCachedObject(edit.endpointPath, edit.id)
                pendingEditDao.delete(edit.endpointPath, edit.id)
                created += reconciledItem(edit.endpointPath, server, edit.localJson)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return result(IOException("Offline create upload failed"))
            } catch (error: HttpException) {
                if (error.code() >= 500) {
                    return result(error)
                }
                Timber.w(
                    error,
                    "Offline create sync rejected by server for %s/%d",
                    edit.endpointPath,
                    edit.id,
                )
            } catch (error: Exception) {
                Timber.w(
                    error,
                    "Offline create sync failed for %s/%d",
                    edit.endpointPath,
                    edit.id,
                )
            }
        }

        for (edit in pendingEditDao.getQueuedDeletes()) {
            try {
                api.deleteObject("${edit.endpointPath}${edit.id}/")
                genericObjectRepository.removeCachedObject(edit.endpointPath, edit.id)
                pendingEditDao.delete(edit.endpointPath, edit.id)
                deleted +=
                    reconciledItem(
                        edit.endpointPath,
                        JsonObject(mapOf("id" to JsonPrimitive(edit.id))),
                        edit.localJson,
                    )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                return result(IOException("Queued delete upload failed"))
            } catch (error: HttpException) {
                if (error.code() == 404) {
                    genericObjectRepository.removeCachedObject(edit.endpointPath, edit.id)
                    pendingEditDao.delete(edit.endpointPath, edit.id)
                    deleted +=
                        reconciledItem(
                            edit.endpointPath,
                            JsonObject(mapOf("id" to JsonPrimitive(edit.id))),
                            edit.localJson,
                        )
                } else if (error.code() >= 500) {
                    return result(error)
                } else {
                    Timber.w(
                        error,
                        "Pending delete sync rejected by server for %s/%d",
                        edit.endpointPath,
                        edit.id,
                    )
                }
            } catch (error: Exception) {
                Timber.w(error, "Pending delete sync failed for %s/%d", edit.endpointPath, edit.id)
            }
        }

        for (edit in pendingEditDao.getQueuedEdits()) {
            try {
                val server = api.getObject("${edit.endpointPath}${edit.id}/")
                if (hasChanged(decode(edit.baseJson), server)) {
                    pendingEditDao.upsert(
                        edit.copy(state = PendingEditEntity.CONFLICT, serverJson = encode(server))
                    )
                    continue
                }
                val updated =
                    api.patchObject("${edit.endpointPath}${edit.id}/", decode(edit.patchJson))
                genericObjectRepository.cacheLocalObject(edit.endpointPath, updated)
                pendingEditDao.delete(edit.endpointPath, edit.id)
                edited += reconciledItem(edit.endpointPath, updated, edit.localJson)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                // Leave the edit queued for the next scheduled/manual sync.
                return result(IOException("Queued edit upload failed"))
            } catch (error: HttpException) {
                if (error.code() >= 500) {
                    return result(error)
                }
                Timber.w(
                    error,
                    "Pending edit sync rejected by server for %s/%d",
                    edit.endpointPath,
                    edit.id,
                )
            } catch (error: Exception) {
                Timber.w(error, "Pending edit sync failed for %s/%d", edit.endpointPath, edit.id)
            }
        }
        return result()
    }

    private suspend fun queueDelete(
        endpointPath: String,
        id: Int,
        baseJson: String,
        existing: PendingEditEntity?,
    ) {
        pendingEditDao.upsert(
            PendingEditEntity(
                endpointPath = endpointPath,
                id = id,
                baseJson = baseJson,
                localJson = baseJson,
                patchJson = "{}",
                state = PendingEditEntity.DELETE_QUEUED,
                serverJson = null,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
        )
        genericObjectRepository.removeCachedObject(endpointPath, id)
    }

    /**
     * Applies the selected local fields, after confirming the conflict's server snapshot is
     * current.
     */
    suspend fun resolveConflict(edit: PendingEditEntity, keepLocalKeys: Set<String>): Result<Unit> {
        val savedServer =
            edit.serverJson
                ?: return Result.failure(IllegalStateException("Conflict has no server snapshot"))
        return try {
            val currentServer = api.getObject("${edit.endpointPath}${edit.id}/")
            if (hasChanged(decode(savedServer), currentServer)) {
                pendingEditDao.upsert(edit.copy(serverJson = encode(currentServer)))
                return Result.failure(StaleConflictException())
            }
            val local = decode(edit.localJson)
            val patch =
                JsonObject(
                    keepLocalKeys.mapNotNull { key -> local[key]?.let { key to it } }.toMap()
                )
            if (patch.isNotEmpty()) {
                val updated = api.patchObject("${edit.endpointPath}${edit.id}/", patch)
                genericObjectRepository.cacheLocalObject(edit.endpointPath, updated)
            } else {
                genericObjectRepository.cacheLocalObject(edit.endpointPath, currentServer)
            }
            pendingEditDao.delete(edit.endpointPath, edit.id)
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun decode(raw: String): JsonObject =
        json.decodeFromString(JsonObject.serializer(), raw)

    private fun encode(value: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), value)

    private fun merge(base: JsonObject, patch: JsonObject): JsonObject =
        JsonObject(
            buildMap {
                putAll(base)
                putAll(patch)
            }
        )

    private fun hasChanged(base: JsonObject, server: JsonObject): Boolean {
        val baseVersion = version(base)
        val serverVersion = version(server)
        return if (baseVersion != null && serverVersion != null) {
            baseVersion != serverVersion
        } else {
            base != server
        }
    }

    private fun version(value: JsonObject): String? =
        (value["last_updated"] as? JsonPrimitive)?.contentOrNull

    private suspend fun queueCreate(
        endpointPath: String,
        body: JsonObject,
    ): CreateSubmission.Queued {
        var localId = nextLocalId()
        while (pendingEditDao.get(endpointPath, localId) != null) localId = nextLocalId()
        val local =
            JsonObject(
                buildMap {
                    putAll(body)
                    put("id", JsonPrimitive(localId))
                    put("display", JsonPrimitive(displayFor(body)))
                }
            )
        pendingEditDao.upsert(
            PendingEditEntity(
                endpointPath = endpointPath,
                id = localId,
                baseJson = "{}",
                localJson = encode(local),
                patchJson = encode(body),
                state = PendingEditEntity.CREATE_QUEUED,
                serverJson = null,
                createdAt = System.currentTimeMillis(),
            )
        )
        genericObjectRepository.cacheLocalObject(endpointPath, local)
        return CreateSubmission.Queued(local)
    }

    private fun nextLocalId(): Int {
        val positive = (System.nanoTime() and 0x7fffffffL).toInt().coerceAtLeast(1)
        return -positive
    }

    private fun withDisplay(value: JsonObject): JsonObject =
        JsonObject(
            buildMap {
                putAll(value)
                put("display", JsonPrimitive(displayFor(value)))
            }
        )

    private fun displayFor(value: JsonObject): String =
        sequenceOf("name", "model", "label", "serial", "asset_tag", "display")
            .mapNotNull { key -> (value[key] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() } ?: "Pending NetBox item"

    private fun reconciledItem(
        endpointPath: String,
        server: JsonObject,
        fallbackLocalJson: String,
    ): ReconciledItem {
        val fallback = runCatching {
            decode(fallbackLocalJson)
        }
            .getOrDefault(JsonObject(emptyMap()))
        return ReconciledItem(
            endpointPath = endpointPath,
            id = (server["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            display =
                displayFor(server).takeUnless { it == "Pending NetBox item" }
                    ?: displayFor(fallback),
        )
    }
}
