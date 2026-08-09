package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.NetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.DeviceDto
import dev.pschmitt.nyetbox.data.api.dto.DeviceTypeDto
import dev.pschmitt.nyetbox.data.api.dto.ImageAttachmentDto
import dev.pschmitt.nyetbox.data.api.dto.PagedResponseDto
import dev.pschmitt.nyetbox.data.db.DeviceTypeDao
import dev.pschmitt.nyetbox.data.db.DeviceTypeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// NBC-428: ensureCached() is what the sync pass now calls on incremental passes instead of
// unconditionally refresh()-ing every cached device type.
class DeviceTypeRepositoryTest {
    @Test
    fun `ensureCached does not call the API for an already-cached id`() = runTest {
        val dao =
            FakeDeviceTypeDao(
                mutableMapOf(
                    7 to
                        DeviceTypeEntity(
                            id = 7,
                            model = "Existing",
                            frontImageUrl = null,
                            rearImageUrl = null,
                            syncedAt = 0L,
                        )
                )
            )
        val api = FakeDeviceTypeApi()
        val repository = DeviceTypeRepository(api, dao)

        val result = repository.ensureCached(7)

        assertTrue(result.isSuccess)
        assertEquals(0, api.fetchCount)
    }

    @Test
    fun `ensureCached fetches and caches an id that isn't cached yet`() = runTest {
        val dao = FakeDeviceTypeDao(mutableMapOf())
        val api = FakeDeviceTypeApi()
        val repository = DeviceTypeRepository(api, dao)

        val result = repository.ensureCached(9)

        assertTrue(result.isSuccess)
        assertEquals(1, api.fetchCount)
        assertEquals("Fetched-9", dao.getById(9)?.model)
    }

    @Test
    fun `ensureCached surfaces the underlying fetch failure for an uncached id`() = runTest {
        val dao = FakeDeviceTypeDao(mutableMapOf())
        val api = FakeDeviceTypeApi(shouldFail = true)
        val repository = DeviceTypeRepository(api, dao)

        val result = repository.ensureCached(9)

        assertTrue(result.isFailure)
    }
}

private class FakeDeviceTypeDao(private val byId: MutableMap<Int, DeviceTypeEntity>) :
    DeviceTypeDao {
    override fun observeAll(): Flow<List<DeviceTypeEntity>> = flowOf(byId.values.toList())

    override fun observeById(id: Int): Flow<DeviceTypeEntity?> = flowOf(byId[id])

    override suspend fun getById(id: Int): DeviceTypeEntity? = byId[id]

    override suspend fun getAll(): List<DeviceTypeEntity> = byId.values.toList()

    override suspend fun countWithImages(): Int =
        byId.values.count { it.frontImageUrl != null || it.rearImageUrl != null }

    override suspend fun upsert(deviceType: DeviceTypeEntity) {
        byId[deviceType.id] = deviceType
    }
}

private class FakeDeviceTypeApi(private val shouldFail: Boolean = false) : NetBoxApi {
    var fetchCount = 0
        private set

    override suspend fun listDevices(
        limit: Int,
        offset: Int,
        search: String?,
        lastUpdatedGte: String?,
    ): PagedResponseDto<DeviceDto> = error("unused")

    override suspend fun getDevice(id: Int): DeviceDto = error("unused")

    override suspend fun getDeviceType(id: Int): DeviceTypeDto {
        fetchCount++
        if (shouldFail) error("boom")
        return DeviceTypeDto(id = id, model = "Fetched-$id")
    }

    override suspend fun listImageAttachments(
        objectType: String,
        objectId: Int?,
        limit: Int,
        offset: Int,
    ): PagedResponseDto<ImageAttachmentDto> = error("unused")
}
