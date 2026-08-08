package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.PagedResponseDto
import dev.pschmitt.nyetbox.data.db.NetBoxObjectDao
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.ObjectThumbnail
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.sync.SyncIssueReporter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GenericObjectRepositoryTest {
    @Test
    fun `sorts numeric interface suffixes numerically instead of lexicographically`() {
        val names = listOf("Gi1/0/11", "Gi1/0/2", "Gi1/0/1", "Gi1/0/10")

        val sorted = names.sortedWith(::naturalCompare)

        assertEquals(listOf("Gi1/0/1", "Gi1/0/2", "Gi1/0/10", "Gi1/0/11"), sorted)
    }

    @Test
    fun `falls back to case-insensitive comparison for non-numeric chunks`() {
        val names = listOf("mgmt0", "Gi1/0/1", "vlan10", "Vlan2")

        val sorted = names.sortedWith(::naturalCompare)

        assertEquals(listOf("Gi1/0/1", "mgmt0", "Vlan2", "vlan10"), sorted)
    }

    @Test
    fun `shorter prefix sorts before longer name with same prefix`() {
        val names = listOf("Gi1/0/1", "Gi1/0")

        val sorted = names.sortedWith(::naturalCompare)

        assertEquals(listOf("Gi1/0", "Gi1/0/1"), sorted)
    }

    // NBC-372: GenericListScreen's search bar now understands the same `key:value` structured
    // filter syntax as NBC-13's Global Search.

    private val endpointPath = "api/dcim/sites/"

    @Test
    fun `structured filter matches a decoded JSON field, not just display`() = runTest {
        val dao =
            InMemoryNetBoxObjectDao(
                listOf(
                    siteObject(id = 1, name = "Site Alpha", description = "Primary datacenter"),
                    siteObject(id = 2, name = "Site Beta", description = "Secondary datacenter"),
                )
            )
        val repository = repository(dao)

        val result = repository.observeObjects(endpointPath, "description:primary").first()

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun `a lone type filter is a no-op since the list is already scoped to one endpoint`() =
        runTest {
            val dao =
                InMemoryNetBoxObjectDao(
                    listOf(
                        siteObject(id = 1, name = "Site Alpha", description = "Primary"),
                        siteObject(id = 2, name = "Site Beta", description = "Secondary"),
                    )
                )
            val repository = repository(dao)

            val withTypeFilter = repository.observeObjects(endpointPath, "type:sites").first()
            val withoutQuery = repository.observeObjects(endpointPath, "").first()

            assertEquals(withoutQuery.map { it.id }, withTypeFilter.map { it.id })
            assertEquals(listOf(1, 2), withTypeFilter.map { it.id })
        }

    @Test
    fun `structured filter composes with the pre-existing route-level relation filter`() = runTest {
        val dao =
            InMemoryNetBoxObjectDao(
                listOf(
                    siteObject(id = 1, name = "Rack A1", description = "Primary", tenantId = 9),
                    siteObject(
                        id = 2,
                        name = "Rack A2",
                        description = "Primary",
                        tenantId = 10,
                    ),
                )
            )
        val repository = repository(dao)

        val result =
            repository
                .observeObjects(
                    endpointPath,
                    "description:primary",
                    filterKey = "tenant",
                    filterValue = 9,
                )
                .first()

        assertEquals(listOf(1), result.map { it.id })
    }

    // NBC-421: ActionTargetPickerDialog's per-endpoint, bounded, query-driven object choices -
    // replaces loading the entire netbox_objects table into the picker's composition.

    @Test
    fun `object choices are scoped to one endpoint and match the query`() = runTest {
        val dao =
            InMemoryNetBoxObjectDao(
                listOf(
                    siteObject(id = 1, name = "Site Alpha", description = "Primary"),
                    siteObject(id = 2, name = "Site Beta", description = "Secondary"),
                )
            )
        val repository = repository(dao)

        val result = repository.observeObjectChoices(endpointPath, "alpha").first()

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun `object choices are bounded by limit`() = runTest {
        val dao =
            InMemoryNetBoxObjectDao(
                (1..5).map { id -> siteObject(id = id, name = "Site $id", description = "") }
            )
        val repository = repository(dao)

        val result = repository.observeObjectChoices(endpointPath, "", limit = 2).first()

        assertEquals(2, result.size)
    }

    // NBC-422: front_image is precomputed at write time for device-types rows only, so
    // dashboard/search thumbnail lookups never need to decode a row's JSON at read time.

    @Test
    fun `caching a device type precomputes its front image url`() = runTest {
        val dao = InMemoryNetBoxObjectDao()
        val repository = repository(dao)

        repository.cacheLocalObject(
            NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH,
            deviceTypeObject(id = 1, frontImage = "https://netbox.example/media/devicetype-images/x.jpg"),
        )

        assertEquals(
            "https://netbox.example/media/devicetype-images/x.jpg",
            dao.stored(NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH, 1)?.frontImageUrl,
        )
    }

    @Test
    fun `front image url is not precomputed for other endpoints`() = runTest {
        val dao = InMemoryNetBoxObjectDao()
        val repository = repository(dao)

        repository.cacheLocalObject(
            endpointPath,
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(1),
                    "name" to JsonPrimitive("Site Alpha"),
                    "description" to JsonPrimitive("Primary"),
                )
            ),
        )

        assertEquals(null, dao.stored(endpointPath, 1)?.frontImageUrl)
    }

    private fun deviceTypeObject(id: Int, frontImage: String): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "model" to JsonPrimitive("Model $id"),
                "front_image" to JsonPrimitive(frontImage),
            )
        )

    // NBC-390: relation lookups used on every device-detail screen open (a device's interfaces,
    // ports, journal entries; a rack's devices) get an indexed shortcut computed once at
    // sync/write time instead of decoding every cached row of the endpoint on every read.

    @Test
    fun `caching a device-scoped object precomputes its device relation id`() = runTest {
        val dao = InMemoryNetBoxObjectDao()
        val repository = repository(dao)

        repository.cacheLocalObject(
            NetBoxRef.INTERFACES_ENDPOINT_PATH,
            interfaceObject(id = 1, name = "Gi1/0/1", deviceId = 42),
        )

        assertEquals(
            42,
            dao.stored(NetBoxRef.INTERFACES_ENDPOINT_PATH, 1)?.relatedObjectId,
        )
    }

    @Test
    fun `observeObjects filtered by device only returns that device's interfaces`() = runTest {
        val dao = InMemoryNetBoxObjectDao()
        val repository = repository(dao)
        val interfacesEndpoint = NetBoxRef.INTERFACES_ENDPOINT_PATH
        repository.cacheLocalObject(
            interfacesEndpoint,
            interfaceObject(id = 1, name = "Gi1/0/1", deviceId = 42),
        )
        repository.cacheLocalObject(
            interfacesEndpoint,
            interfaceObject(id = 2, name = "Gi1/0/2", deviceId = 99),
        )

        val result =
            repository
                .observeObjects(interfacesEndpoint, "", filterKey = "device", filterValue = 42)
                .first()

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun `rows cached before the relation column existed still match via the null fallback`() =
        runTest {
            val interfacesEndpoint = NetBoxRef.INTERFACES_ENDPOINT_PATH
            val legacyRow =
                NetBoxObjectEntity(
                    endpointPath = interfacesEndpoint,
                    id = 1,
                    display = "Gi1/0/1",
                    secondaryLine = null,
                    json =
                        Json.encodeToString(
                            JsonObject.serializer(),
                            interfaceObject(id = 1, name = "Gi1/0/1", deviceId = 42),
                        ),
                    syncedAt = 0,
                    relatedObjectId = null,
                )
            val dao = InMemoryNetBoxObjectDao(listOf(legacyRow))
            val repository = repository(dao)

            val result =
                repository
                    .observeObjects(interfacesEndpoint, "", filterKey = "device", filterValue = 42)
                    .first()

            assertEquals(listOf(1), result.map { it.id })
        }

    private fun interfaceObject(id: Int, name: String, deviceId: Int): JsonObject =
        JsonObject(
            mapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(name),
                "device" to
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(deviceId),
                            "name" to JsonPrimitive("device-$deviceId"),
                        )
                    ),
            )
        )

    private fun repository(dao: NetBoxObjectDao) =
        GenericObjectRepository(FakeGenericNetBoxApi(), dao, Json, SyncIssueReporter())

    private fun siteObject(
        id: Int,
        name: String,
        description: String,
        tenantId: Int? = null,
    ): NetBoxObjectEntity {
        val fields =
            buildMap<String, JsonElement> {
                put("id", JsonPrimitive(id))
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                if (tenantId != null) {
                    put(
                        "tenant",
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(tenantId),
                                "name" to JsonPrimitive("Tenant $tenantId"),
                            )
                        ),
                    )
                }
            }
        return NetBoxObjectEntity(
            endpointPath = endpointPath,
            id = id,
            display = name,
            secondaryLine = null,
            json = Json.encodeToString(JsonObject.serializer(), JsonObject(fields)),
            syncedAt = 0,
        )
    }
}

/**
 * In-memory approximation of `NetBoxObjectDao.observeAll`/`search` (same `display`-only `LIKE`
 * behavior), so [GenericObjectRepository.observeObjects]'s free-text + structured-filter split can
 * be exercised without a real Room database.
 */
private class InMemoryNetBoxObjectDao(initial: List<NetBoxObjectEntity> = emptyList()) :
    NetBoxObjectDao {
    private val objects = initial.associateBy { it.endpointPath to it.id }.toMutableMap()

    fun stored(endpointPath: String, id: Int): NetBoxObjectEntity? = objects[endpointPath to id]

    override fun observeAll(endpointPath: String): Flow<List<NetBoxObjectEntity>> =
        flowOf(objects.values.filter { it.endpointPath == endpointPath })

    override fun search(endpointPath: String, query: String): Flow<List<NetBoxObjectEntity>> =
        flowOf(
            objects.values.filter {
                it.endpointPath == endpointPath && it.display.contains(query, ignoreCase = true)
            }
        )

    override fun searchAllInEndpoint(
        endpointPath: String,
        query: String,
        limit: Int,
    ): Flow<List<NetBoxObjectEntity>> =
        flowOf(
            objects.values
                .filter {
                    it.endpointPath == endpointPath &&
                        (it.display.contains(query, ignoreCase = true) ||
                            it.secondaryLine.orEmpty().contains(query, ignoreCase = true) ||
                            it.json.contains(query, ignoreCase = true))
                }
                .sortedBy { it.display.lowercase() }
                .take(limit)
        )

    override fun observeByRelatedObjectId(
        endpointPath: String,
        relatedObjectId: Int,
    ): Flow<List<NetBoxObjectEntity>> =
        flowOf(
            objects.values.filter {
                it.endpointPath == endpointPath &&
                    (it.relatedObjectId == relatedObjectId || it.relatedObjectId == null)
            }
        )

    override fun observeById(endpointPath: String, id: Int): Flow<NetBoxObjectEntity?> =
        flowOf(objects[endpointPath to id])

    override fun observeAllObjects(): Flow<List<NetBoxObjectEntity>> =
        flowOf(objects.values.toList())

    override fun observeThumbnails(endpointPath: String): Flow<List<ObjectThumbnail>> =
        flowOf(
            objects.values.mapNotNull {
                if (it.endpointPath != endpointPath || it.frontImageUrl == null) null
                else ObjectThumbnail(it.id, it.frontImageUrl)
            }
        )

    override suspend fun getById(endpointPath: String, id: Int): NetBoxObjectEntity? =
        objects[endpointPath to id]

    override suspend fun getAll(endpointPath: String): List<NetBoxObjectEntity> =
        objects.values.filter { it.endpointPath == endpointPath }

    override fun searchAll(query: String, limit: Int): Flow<List<NetBoxObjectEntity>> =
        flowOf(emptyList())

    override suspend fun upsertAll(objects: List<NetBoxObjectEntity>) {
        objects.forEach { upsert(it) }
    }

    override suspend fun upsert(obj: NetBoxObjectEntity) {
        objects[obj.endpointPath to obj.id] = obj
    }

    override suspend fun delete(endpointPath: String, id: Int) = error("unused")

    override suspend fun count(endpointPath: String): Int =
        objects.values.count { it.endpointPath == endpointPath }

    override suspend fun countAll(): Int = objects.size

    override suspend fun getAll(): List<NetBoxObjectEntity> = objects.values.toList()

    override suspend fun maxLastUpdated(endpointPath: String): String? = error("unused")

    override suspend fun pruneStale(endpointPath: String, cutoff: Long) = error("unused")
}

private class FakeGenericNetBoxApi : GenericNetBoxApi {
    override suspend fun getAuthenticationCheck(): JsonObject = error("unused")

    override suspend fun getApiRoot(): Map<String, String> = error("unused")

    override suspend fun getUrlMap(url: String): Map<String, String> = error("unused")

    override suspend fun listObjects(
        url: String,
        query: Map<String, String>,
    ): PagedResponseDto<JsonObject> = error("unused")

    override suspend fun getObject(url: String): JsonObject = error("unused")

    override suspend fun getObjectOptions(url: String): JsonObject = error("unused")

    override suspend fun getJsonArray(url: String): kotlinx.serialization.json.JsonArray =
        error("unused")

    override suspend fun getSvg(url: String): okhttp3.ResponseBody = error("unused")

    override suspend fun patchObject(url: String, body: JsonObject): JsonObject = error("unused")

    override suspend fun createObject(url: String, body: JsonObject): JsonObject = error("unused")

    override suspend fun deleteObject(url: String) = error("unused")

    override suspend fun getJournalEntryOptions(): JsonObject = error("unused")
}
