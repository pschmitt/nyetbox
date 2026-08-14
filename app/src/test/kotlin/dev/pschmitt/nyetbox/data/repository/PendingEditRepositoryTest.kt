package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.PagedResponseDto
import dev.pschmitt.nyetbox.data.db.NetBoxObjectDao
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.ObjectThumbnail
import dev.pschmitt.nyetbox.data.db.PendingEditDao
import dev.pschmitt.nyetbox.data.db.PendingEditEntity
import dev.pschmitt.nyetbox.sync.SyncIssueReporter
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingEditRepositoryTest {

    private val json = Json

    // NBC-427: the sync freshness short-circuit must never skip while an edit is still queued.
    @Test
    fun `hasQueuedMutations reflects whether any local edit is still queued`() = runTest {
        val pending = FakePendingEditDao()
        val repository = repository(FakeApi(server("server", "v1")), pending, FakeNetBoxObjectDao())

        assertEquals(false, repository.hasQueuedMutations())

        pending.upsert(
            PendingEditEntity(
                endpointPath = "api/dcim/devices/",
                id = 1,
                baseJson = "{}",
                localJson = "{}",
                patchJson = "{}",
                state = PendingEditEntity.QUEUED,
                serverJson = null,
                createdAt = 0L,
            )
        )

        assertEquals(true, repository.hasQueuedMutations())
    }

    @Test
    fun `server version divergence creates a conflict instead of patching`() = runTest {
        val api = FakeApi(server("server", "v2"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)

        val result =
            repository.submitEdit(
                "api/dcim/devices/",
                1,
                server("old", "v1").toString(),
                patch("local"),
            )

        assertEquals(EditSubmission.ConflictDetected, result.getOrThrow())
        assertNull(api.lastPatch)
        val conflict = pending.get("api/dcim/devices/", 1)
        assertNotNull(conflict)
        assertEquals(PendingEditEntity.CONFLICT, conflict!!.state)
        assertEquals(
            "server",
            json
                .decodeFromString(JsonObject.serializer(), conflict.serverJson!!)["name"]
                ?.toString()
                ?.trim('"'),
        )
        assertEquals(
            "local",
            json
                .decodeFromString(JsonObject.serializer(), objectDao.last!!.json)["name"]
                ?.toString()
                ?.trim('"'),
        )
    }

    @Test
    fun `network failure queues the patch and caches the local object`() = runTest {
        val api = FakeApi(server("old", "v1"), failGets = true)
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)

        val result =
            repository.submitEdit(
                "api/dcim/devices/",
                1,
                server("old", "v1").toString(),
                patch("local"),
            )

        assertEquals(EditSubmission.Queued, result.getOrThrow())
        val queued = pending.get("api/dcim/devices/", 1)
        assertNotNull(queued)
        assertEquals(PendingEditEntity.QUEUED, queued!!.state)
        assertEquals(
            "local",
            json
                .decodeFromString(JsonObject.serializer(), queued.localJson)["name"]
                ?.toString()
                ?.trim('"'),
        )
        assertEquals(
            "local",
            json
                .decodeFromString(JsonObject.serializer(), objectDao.last!!.json)["name"]
                ?.toString()
                ?.trim('"'),
        )
    }

    @Test
    fun `matching server version patches and clears the pending edit`() = runTest {
        val api = FakeApi(server("old", "v1"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)

        val result =
            repository.submitEdit(
                "api/dcim/devices/",
                1,
                server("old", "v1").toString(),
                patch("local"),
            )

        assertEquals(EditSubmission.Updated, result.getOrThrow())
        assertEquals(patch("local"), api.lastPatch)
        assertNull(pending.get("api/dcim/devices/", 1))
        assertEquals(
            "local",
            json
                .decodeFromString(JsonObject.serializer(), objectDao.last!!.json)["name"]
                ?.toString()
                ?.trim('"'),
        )
        assertTrue(api.server["last_updated"] == JsonPrimitive("v1"))
    }

    @Test
    fun `offline deletion hides the cached object and reconciles later`() = runTest {
        val api = FakeApi(server("to-delete", "v1"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)
        val endpoint = "api/dcim/racks/"
        objectDao.upsert(
            NetBoxObjectEntity(
                endpointPath = endpoint,
                id = 1,
                display = "to-delete",
                secondaryLine = null,
                json = server("to-delete", "v1").toString(),
                syncedAt = 1L,
            )
        )

        val queued = repository.deleteObject(endpoint, 1, offline = true).getOrThrow()

        assertEquals(DeleteSubmission.Queued, queued)
        assertEquals(PendingEditEntity.DELETE_QUEUED, pending.get(endpoint, 1)!!.state)
        assertNull(objectDao.last)

        val sync = repository.syncPending()

        assertEquals("${endpoint}1/", api.lastDelete)
        assertEquals(1, sync.reconciliation.deleted.size)
        assertNull(pending.get(endpoint, 1))
    }

    @Test
    fun `disposable offline create and later edit reconcile without touching existing item`() =
        runTest {
            val api = FakeApi(server("untouched-existing-fixture", "v1"))
            val pending = FakePendingEditDao()
            val objectDao = FakeNetBoxObjectDao()
            val repository = repository(api, pending, objectDao)
            val body =
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive("NBC-145-disposable-offline-create"),
                        "asset_tag" to JsonPrimitive("#NBC-145-TEST"),
                    )
                )

            val queued =
                repository.submitCreate("api/dcim/devices/", body, offline = true).getOrThrow()
                    as CreateSubmission.Queued
            val localId = (queued.objectJson["id"] as JsonPrimitive).content.toInt()
            assertTrue(localId < 0)
            assertEquals(
                PendingEditEntity.CREATE_QUEUED,
                pending.get("api/dcim/devices/", localId)!!.state,
            )

            repository.submitEdit(
                endpointPath = "api/dcim/devices/",
                id = localId,
                baseJson = queued.objectJson.toString(),
                patch = patch("NBC-145-disposable-offline-edited"),
            )
            val sync = repository.syncPending()

            assertEquals(1, sync.reconciliation.created.size)
            assertEquals(
                "NBC-145-disposable-offline-edited",
                api.lastCreate!!["name"]?.toString()?.trim('"'),
            )
            assertNull(pending.get("api/dcim/devices/", localId))
            assertEquals("untouched-existing-fixture", api.server["name"]?.toString()?.trim('"'))
            assertEquals(101, objectDao.last!!.id)
        }

    @Test
    fun `reverting a queued disposable create removes only its local cache entry`() = runTest {
        val api = FakeApi(server("untouched-existing-fixture", "v1"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)

        repository.submitCreate(
            "api/dcim/devices/",
            JsonObject(mapOf("name" to JsonPrimitive("NBC-145-disposable-to-revert"))),
            offline = true,
        )
        val queued = pending.getQueuedCreates().single()
        repository.revertPending(queued)

        assertNull(pending.get("api/dcim/devices/", queued.id))
        assertNull(objectDao.last)
        assertEquals("untouched-existing-fixture", api.server["name"]?.toString()?.trim('"'))
    }

    @Test
    fun `resolution patches only fields selected from the local edit`() = runTest {
        val api = FakeApi(server("server", "v2"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)
        val conflict =
            PendingEditEntity(
                endpointPath = "api/dcim/devices/",
                id = 1,
                baseJson = server("old", "v1").toString(),
                localJson = server("local", "v1").toString(),
                patchJson = patch("local").toString(),
                state = PendingEditEntity.CONFLICT,
                serverJson = server("server", "v2").toString(),
                createdAt = 1L,
            )
        pending.upsert(conflict)

        val result = repository.resolveConflict(conflict, setOf("name"))

        assertTrue(result.isSuccess)
        assertEquals(patch("local"), api.lastPatch)
        assertNull(pending.get("api/dcim/devices/", 1))
    }

    @Test
    fun `resolution keeps the conflict when the server changes again`() = runTest {
        val api = FakeApi(server("server-new", "v3"))
        val pending = FakePendingEditDao()
        val objectDao = FakeNetBoxObjectDao()
        val repository = repository(api, pending, objectDao)
        val conflict =
            PendingEditEntity(
                endpointPath = "api/dcim/devices/",
                id = 1,
                baseJson = server("old", "v1").toString(),
                localJson = server("local", "v1").toString(),
                patchJson = patch("local").toString(),
                state = PendingEditEntity.CONFLICT,
                serverJson = server("server", "v2").toString(),
                createdAt = 1L,
            )
        pending.upsert(conflict)

        val result = repository.resolveConflict(conflict, setOf("name"))

        assertTrue(result.exceptionOrNull() is StaleConflictException)
        assertNull(api.lastPatch)
        assertEquals(
            "v3",
            json
                .decodeFromString(
                    JsonObject.serializer(),
                    pending.get("api/dcim/devices/", 1)!!.serverJson!!,
                )["last_updated"]
                ?.toString()
                ?.trim('"'),
        )
    }

    private fun repository(
        api: FakeApi,
        pending: FakePendingEditDao,
        objectDao: FakeNetBoxObjectDao,
    ) =
        PendingEditRepository(
            api,
            pending,
            GenericObjectRepository(api, objectDao, json, SyncIssueReporter()),
            json,
        )

    private fun server(name: String, version: String): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(1),
                "display" to JsonPrimitive(name),
                "name" to JsonPrimitive(name),
                "last_updated" to JsonPrimitive(version),
            )
        )

    private fun patch(name: String): JsonObject = JsonObject(mapOf("name" to JsonPrimitive(name)))
}

internal class FakeApi(
    var server: JsonObject,
    private val failGets: Boolean = false,
    private val failures: Map<FakeApiOperation, Throwable> = emptyMap(),
) : GenericNetBoxApi {
    var lastPatch: JsonObject? = null
    var lastCreate: JsonObject? = null
    var lastDelete: String? = null
    private var nextCreatedId = 101

    override suspend fun getAuthenticationCheck(): JsonObject = error("unused")

    override suspend fun getApiRoot(): Map<String, String> = error("unused")

    override suspend fun getUrlMap(url: String): Map<String, String> = error("unused")

    override suspend fun listObjects(
        url: String,
        query: Map<String, String>,
    ): PagedResponseDto<JsonObject> = error("unused")

    override suspend fun getObject(url: String): JsonObject {
        failures[FakeApiOperation.Get]?.let { throw it }
        if (failGets) throw IOException("offline")
        return server
    }

    override suspend fun getObjectOptions(url: String): JsonObject = error("unused")

    override suspend fun getJsonArray(url: String): kotlinx.serialization.json.JsonArray =
        error("unused")

    override suspend fun getSvg(url: String): okhttp3.ResponseBody = error("unused")

    override suspend fun patchObject(url: String, body: JsonObject): JsonObject {
        failures[FakeApiOperation.Patch]?.let { throw it }
        lastPatch = body
        server =
            JsonObject(
                buildMap {
                    putAll(server)
                    putAll(body)
                }
            )
        return server
    }

    override suspend fun createObject(url: String, body: JsonObject): JsonObject {
        failures[FakeApiOperation.Create]?.let { throw it }
        lastCreate = body
        return JsonObject(
            buildMap {
                putAll(body)
                put("id", JsonPrimitive(nextCreatedId++))
                put("display", body["name"] ?: JsonPrimitive("Created item"))
                put("last_updated", JsonPrimitive("created"))
            }
        )
    }

    override suspend fun deleteObject(url: String) {
        failures[FakeApiOperation.Delete]?.let { throw it }
        lastDelete = url
    }

    override suspend fun getJournalEntryOptions(): JsonObject = error("unused")
}

internal class FakePendingEditDao : PendingEditDao {
    private val edits = mutableMapOf<Pair<String, Int>, PendingEditEntity>()

    override fun observeConflicts(): Flow<List<PendingEditEntity>> =
        flowOf(edits.values.filter { it.state == PendingEditEntity.CONFLICT })

    override fun observeConflictCount(): Flow<Int> =
        flowOf(edits.values.count { it.state == PendingEditEntity.CONFLICT })

    override fun observeQueuedMutations(): Flow<List<PendingEditEntity>> =
        flowOf(
            edits.values.filter {
                it.state in
                    setOf(
                        PendingEditEntity.QUEUED,
                        PendingEditEntity.CREATE_QUEUED,
                        PendingEditEntity.DELETE_QUEUED,
                    )
            }
        )

    override fun observeQueuedMutationCount(): Flow<Int> =
        flowOf(
            edits.values.count {
                it.state in
                    setOf(
                        PendingEditEntity.QUEUED,
                        PendingEditEntity.CREATE_QUEUED,
                        PendingEditEntity.DELETE_QUEUED,
                    )
            }
        )

    override suspend fun getQueuedMutations(): List<PendingEditEntity> =
        edits.values.filter {
            it.state in
                setOf(
                    PendingEditEntity.QUEUED,
                    PendingEditEntity.CREATE_QUEUED,
                    PendingEditEntity.DELETE_QUEUED,
                )
        }

    override suspend fun getQueuedEdits(): List<PendingEditEntity> =
        edits.values.filter { it.state == PendingEditEntity.QUEUED }

    override suspend fun getQueuedCreates(): List<PendingEditEntity> =
        edits.values.filter { it.state == PendingEditEntity.CREATE_QUEUED }

    override suspend fun getQueuedDeletes(): List<PendingEditEntity> =
        edits.values.filter { it.state == PendingEditEntity.DELETE_QUEUED }

    override suspend fun get(endpointPath: String, id: Int): PendingEditEntity? =
        edits[endpointPath to id]

    override suspend fun upsert(edit: PendingEditEntity) {
        edits[edit.endpointPath to edit.id] = edit
    }

    override suspend fun delete(endpointPath: String, id: Int) {
        edits.remove(endpointPath to id)
    }
}

internal class FakeNetBoxObjectDao : NetBoxObjectDao {
    var last: NetBoxObjectEntity? = null

    override fun observeAll(endpointPath: String): Flow<List<NetBoxObjectEntity>> =
        flowOf(emptyList())

    override fun search(endpointPath: String, query: String): Flow<List<NetBoxObjectEntity>> =
        flowOf(emptyList())

    override fun searchAllInEndpoint(
        endpointPath: String,
        query: String,
        limit: Int,
    ): Flow<List<NetBoxObjectEntity>> = flowOf(emptyList())

    override fun observeByRelatedObjectId(
        endpointPath: String,
        relatedObjectId: Int,
    ): Flow<List<NetBoxObjectEntity>> = flowOf(emptyList())

    override suspend fun getByRelatedObjectId(
        endpointPath: String,
        relatedObjectId: Int,
    ): List<NetBoxObjectEntity> = emptyList()

    override fun observeById(endpointPath: String, id: Int): Flow<NetBoxObjectEntity?> =
        flowOf(last)

    override fun observeAllObjects(): Flow<List<NetBoxObjectEntity>> = flowOf(listOfNotNull(last))

    override fun observeThumbnails(endpointPath: String): Flow<List<ObjectThumbnail>> =
        flowOf(emptyList())

    override suspend fun getById(endpointPath: String, id: Int): NetBoxObjectEntity? =
        last?.takeIf {
            it.endpointPath == endpointPath && it.id == id
        }

    override fun searchAll(query: String, limit: Int): Flow<List<NetBoxObjectEntity>> =
        flowOf(emptyList())

    override suspend fun upsertAll(objects: List<NetBoxObjectEntity>) {
        last = objects.lastOrNull() ?: last
    }

    override suspend fun upsert(obj: NetBoxObjectEntity) {
        last = obj
    }

    override suspend fun delete(endpointPath: String, id: Int) {
        if (last?.endpointPath == endpointPath && last?.id == id) last = null
    }

    override suspend fun count(endpointPath: String): Int =
        if (last?.endpointPath == endpointPath) 1 else 0

    override suspend fun countAll(): Int = if (last == null) 0 else 1

    override suspend fun getAll(endpointPath: String): List<NetBoxObjectEntity> =
        listOfNotNull(last).filter { it.endpointPath == endpointPath }

    override suspend fun getAll(): List<NetBoxObjectEntity> = listOfNotNull(last)

    override suspend fun maxLastUpdated(endpointPath: String): String? = null

    override suspend fun pruneStale(endpointPath: String, cutoff: Long) = Unit
}
