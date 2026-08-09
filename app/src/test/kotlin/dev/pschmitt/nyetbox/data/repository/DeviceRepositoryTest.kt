package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.NetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.DeviceDto
import dev.pschmitt.nyetbox.data.api.dto.DeviceTypeDto
import dev.pschmitt.nyetbox.data.api.dto.ImageAttachmentDto
import dev.pschmitt.nyetbox.data.api.dto.IpAddressRefDto
import dev.pschmitt.nyetbox.data.api.dto.PagedResponseDto
import dev.pschmitt.nyetbox.data.db.DeviceDao
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.db.DeviceLookup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRepositoryTest {
    @Test
    fun `preserves IPv6 primary address and prefix in the typed cache`() {
        val entity =
            DeviceDto(
                    id = 17,
                    primaryIp =
                        IpAddressRefDto(
                            id = 42,
                            address = "2001:db8:1::17/64",
                        ),
                )
                .toEntity()

        assertEquals("2001:db8:1::17/64", entity.primaryIp)
        assertEquals(42, entity.primaryIpId)
    }

    // NBC-372: DeviceListScreen's search bar now understands the same `key:value` structured
    // filter syntax as NBC-13's Global Search.

    @Test
    fun `structured filters narrow by field instead of matching any column`() = runTest {
        val dao =
            FakeDeviceDao(
                listOf(
                    device(id = 1, name = "Router1", statusLabel = "Active", siteName = "HQ"),
                    device(id = 2, name = "Router2", statusLabel = "Active", siteName = "Branch"),
                    device(id = 3, name = "Router3", statusLabel = "Offline", siteName = "HQ"),
                )
            )
        val repository = DeviceRepository(FakeNetBoxApi(), dao)

        val result = repository.observeDevices("status:active site:hq").first()

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun `combines free text with a structured filter`() = runTest {
        val dao =
            FakeDeviceDao(
                listOf(
                    device(id = 1, name = "Edge Switch", statusLabel = "Active", siteName = "HQ"),
                    device(id = 2, name = "Core Router", statusLabel = "Active", siteName = "HQ"),
                )
            )
        val repository = DeviceRepository(FakeNetBoxApi(), dao)

        val result = repository.observeDevices("site:hq switch").first()

        assertEquals(listOf(1), result.map { it.id })
    }

    @Test
    fun `a lone type filter is a no-op since the list is already scoped to devices`() = runTest {
        val dao =
            FakeDeviceDao(
                listOf(
                    device(id = 1, name = "Router1", statusLabel = "Active", siteName = "HQ"),
                    device(id = 2, name = "Router2", statusLabel = "Offline", siteName = "Branch"),
                )
            )
        val repository = DeviceRepository(FakeNetBoxApi(), dao)

        val withTypeFilter = repository.observeDevices("type:dev").first()
        val withoutQuery = repository.observeDevices("").first()

        assertEquals(withoutQuery.map { it.id }, withTypeFilter.map { it.id })
        assertEquals(listOf(1, 2), withTypeFilter.map { it.id })
    }

    private fun device(
        id: Int,
        name: String,
        statusLabel: String? = null,
        siteName: String? = null,
    ) =
        DeviceEntity(
            id = id,
            name = name,
            url = "https://netbox.example/dcim/devices/$id/",
            statusValue = statusLabel?.lowercase(),
            statusLabel = statusLabel,
            siteName = siteName,
            siteId = null,
            rackName = null,
            rackId = null,
            position = null,
            roleName = null,
            manufacturerName = null,
            deviceTypeModel = null,
            deviceTypeId = null,
            serial = null,
            assetTag = null,
            primaryIp = null,
            comments = null,
            lastUpdated = null,
            syncedAt = 0,
        )
}

/**
 * Approximates `DeviceDao.search`'s Room `LIKE` query (same column list, case-insensitive
 * substring) purely in-memory, so [DeviceRepository.observeDevices]'s free-text + structured-filter
 * split can be exercised without a real Room database.
 */
private class FakeDeviceDao(private val devices: List<DeviceEntity>) : DeviceDao {
    override fun observeAll(): Flow<List<DeviceEntity>> = flowOf(devices)

    override fun observeLookup(): Flow<List<DeviceLookup>> =
        flowOf(
            devices.map {
                DeviceLookup(
                    id = it.id,
                    deviceTypeId = it.deviceTypeId,
                    assetTag = it.assetTag,
                    statusLabel = it.statusLabel,
                )
            }
        )

    override fun search(query: String): Flow<List<DeviceEntity>> =
        flowOf(
            devices.filter { device ->
                listOfNotNull(
                        device.name,
                        device.serial,
                        device.assetTag,
                        device.primaryIp,
                        device.statusLabel,
                        device.siteName,
                        device.rackName,
                        device.roleName,
                        device.manufacturerName,
                        device.deviceTypeModel,
                        device.comments,
                    )
                    .any { it.contains(query, ignoreCase = true) }
            }
        )

    override fun observeById(id: Int): Flow<DeviceEntity?> =
        flowOf(devices.firstOrNull { it.id == id })

    override suspend fun getById(id: Int): DeviceEntity? = devices.firstOrNull { it.id == id }

    override suspend fun getByAssetTag(
        assetTag: String,
        assetTagWithoutPrefix: String,
    ): DeviceEntity? = devices.firstOrNull {
        it.assetTag.equals(assetTag, ignoreCase = true) ||
            it.assetTag.equals(assetTagWithoutPrefix, ignoreCase = true)
    }

    override suspend fun getAll(): List<DeviceEntity> = devices

    override suspend fun deleteById(id: Int) = error("unused")

    override suspend fun upsertAll(devices: List<DeviceEntity>) = error("unused")

    override suspend fun upsert(device: DeviceEntity) = error("unused")

    override suspend fun clear() = error("unused")

    override suspend fun count(): Int = devices.size

    override fun observeCount(): Flow<Int> = flowOf(devices.size)

    override suspend fun maxLastUpdated(): String? = error("unused")

    override suspend fun pruneStale(cutoff: Long) = error("unused")

    override suspend fun countChangedInRack(rackId: Int, cutoff: Long): Int = devices.count {
        it.rackId == rackId && it.syncedAt >= cutoff
    }
}

private class FakeNetBoxApi : NetBoxApi {
    override suspend fun listDevices(
        limit: Int,
        offset: Int,
        search: String?,
        lastUpdatedGte: String?,
    ): PagedResponseDto<DeviceDto> = error("unused")

    override suspend fun getDevice(id: Int): DeviceDto = error("unused")

    override suspend fun getDeviceType(id: Int): DeviceTypeDto = error("unused")

    override suspend fun listImageAttachments(
        objectType: String,
        objectId: Int?,
        limit: Int,
        offset: Int,
    ): PagedResponseDto<ImageAttachmentDto> = error("unused")
}
