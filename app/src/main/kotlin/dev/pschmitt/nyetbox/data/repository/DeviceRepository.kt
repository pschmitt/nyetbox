package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.NetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.DeviceDto
import dev.pschmitt.nyetbox.data.db.DeviceDao
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.db.DeviceLookup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Cache-first: reads always come from Room; [syncAll]/[refreshDevice] pull from the API and upsert.
 */
@Singleton
class DeviceRepository @Inject constructor(private val api: NetBoxApi, private val dao: DeviceDao) {

    /**
     * Understands the same `key:value` structured filter syntax as NBC-13's Global Search
     * (`parseGlobalSearchQuery`) - e.g. `status:active site:hq router` narrows to devices whose
     * `status` field contains "active" and whose `site` field contains "hq", plus free-text
     * "router" against the existing [DeviceDao.search] columns. The `type:`/`tpe:`
     * collection-scoping token is a no-op here (excluded from
     * [dev.pschmitt.nyetbox.data.repository.ParsedGlobalSearchQuery.objectFilters] already) - this
     * list is already scoped to devices, so there is nothing left for it to select. Free text still
     * goes through Room's `LIKE` query (cheap, indexed columns); structured filters are applied
     * in-memory afterwards since they need field-scoped matching `LIKE` can't express.
     */
    fun observeDevices(query: String): Flow<List<DeviceEntity>> {
        val parsed = parseGlobalSearchQuery(query)
        val base = if (parsed.freeText.isBlank()) dao.observeAll() else dao.search(parsed.freeText)
        if (parsed.objectFilters.isEmpty()) return base
        return base.map { devices ->
            devices.filter { it.createSearchFields().matchesFilters(parsed.objectFilters) }
        }
    }

    fun observeDevice(id: Int): Flow<DeviceEntity?> = dao.observeById(id)

    /** See [DeviceLookup] - backs dashboard/search list-row thumbnail, asset tag, and status. */
    fun observeDeviceLookup(): Flow<List<DeviceLookup>> = dao.observeLookup()

    suspend fun refreshDevice(id: Int): Result<DeviceEntity> = runCatching {
        val entity = api.getDevice(id).toEntity()
        dao.upsert(entity)
        entity
    }

    /**
     * Resolves a scanner asset-tag payload from Room first, then performs a narrow best-effort API
     * search.
     */
    suspend fun findByAssetTag(assetTag: String): DeviceEntity? {
        val trimmed = assetTag.trim()
        val withoutPrefix = trimmed.removePrefix("#")
        dao.getByAssetTag(trimmed, withoutPrefix)?.let {
            return it
        }
        return runCatching {
            api.listDevices(limit = 50, search = withoutPrefix)
                .results
                .firstOrNull { normalizeAssetTag(it.assetTag) == normalizeAssetTag(trimmed) }
                ?.toEntity()
                ?.also { dao.upsert(it) }
        }
            .getOrNull()
    }

    /**
     * Full (or, with [lastUpdatedGte], incremental) paginated sync of every device NetBox knows
     * about.
     */
    suspend fun syncAll(pageSize: Int = 200, lastUpdatedGte: String? = null): Result<Int> =
        runCatching {
            var offset = 0
            var total = 0
            while (true) {
                val page =
                    api.listDevices(
                        limit = pageSize,
                        offset = offset,
                        lastUpdatedGte = lastUpdatedGte,
                    )
                if (page.results.isEmpty()) break
                dao.upsertAll(page.results.map { it.toEntity() })
                total += page.results.size
                offset += pageSize
                if (page.next == null) break
            }
            Timber.i("Synced %d devices", total)
            total
        }

    /**
     * The incremental-sync watermark - see
     * [dev.pschmitt.nyetbox.data.db.NetBoxObjectDao.maxLastUpdated].
     */
    suspend fun lastUpdatedWatermark(): String? = dao.maxLastUpdated()

    /** See [dev.pschmitt.nyetbox.data.db.NetBoxObjectDao.pruneStale]. */
    suspend fun pruneStale(cutoff: Long) {
        dao.pruneStale(cutoff)
    }

    suspend fun cachedDeviceCount(): Int = dao.count()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun cachedDevices(): List<DeviceEntity> = dao.getAll()

    suspend fun removeCachedDevice(id: Int) {
        dao.deleteById(id)
    }

    /** See [dev.pschmitt.nyetbox.data.db.DeviceDao.countChangedInRack]. */
    suspend fun countChangedInRack(rackId: Int, cutoff: Long): Int =
        dao.countChangedInRack(rackId, cutoff)
}

internal fun DeviceDto.toEntity(): DeviceEntity =
    DeviceEntity(
        id = id,
        name = name ?: display ?: "Device $id",
        url = url ?: "",
        statusValue = status?.value,
        statusLabel = status?.label,
        siteName = site?.display ?: site?.name,
        siteId = site?.id,
        rackName = rack?.display ?: rack?.name,
        rackId = rack?.id,
        position = position,
        roleName = effectiveRole?.display ?: effectiveRole?.name,
        manufacturerName = deviceType?.manufacturer?.name,
        deviceTypeModel = deviceType?.model,
        deviceTypeId = deviceType?.id,
        serial = serial,
        assetTag = assetTag,
        primaryIp = primaryIp?.address ?: primaryIp?.display,
        comments = comments,
        lastUpdated = lastUpdated,
        syncedAt = System.currentTimeMillis(),
        customFieldsJson = customFields?.toString(),
        primaryIpId = primaryIp?.id,
    )

private fun normalizeAssetTag(value: String?): String =
    value.orEmpty().trim().removePrefix("#").lowercase()
