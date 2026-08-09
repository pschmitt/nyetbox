package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.NetBoxApi
import dev.pschmitt.nyetbox.data.api.dto.DeviceTypeDto
import dev.pschmitt.nyetbox.data.db.DeviceTypeDao
import dev.pschmitt.nyetbox.data.db.DeviceTypeEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Cache-first, like [DeviceRepository] - only the front/rear stock-photo URLs are of interest here.
 */
@Singleton
class DeviceTypeRepository
@Inject
constructor(private val api: NetBoxApi, private val dao: DeviceTypeDao) {

    fun observeAll(): Flow<List<DeviceTypeEntity>> = dao.observeAll()

    fun observe(id: Int): Flow<DeviceTypeEntity?> = dao.observeById(id)

    suspend fun cachedAll(): List<DeviceTypeEntity> = dao.getAll()

    /**
     * Fetches and caches [id] only if it isn't already cached - device-type photos rarely change.
     * Returns success immediately for an already-cached id; otherwise mirrors [refresh]'s result.
     */
    suspend fun ensureCached(id: Int): Result<Unit> =
        if (dao.getById(id) != null) Result.success(Unit) else refresh(id).map {}

    suspend fun refresh(id: Int): Result<DeviceTypeEntity> = runCatching {
        val entity = api.getDeviceType(id).toEntity()
        dao.upsert(entity)
        entity
    }
}

private fun DeviceTypeDto.toEntity(): DeviceTypeEntity =
    DeviceTypeEntity(
        id = id,
        model = model,
        frontImageUrl = frontImage,
        rearImageUrl = rearImage,
        syncedAt = System.currentTimeMillis(),
    )
