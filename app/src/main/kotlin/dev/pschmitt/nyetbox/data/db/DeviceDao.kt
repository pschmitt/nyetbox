package dev.pschmitt.nyetbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Narrow projection of [DeviceEntity] for read paths that only ever need identity plus the fields
 * used to render a dashboard/search list row (thumbnail, asset tag, status) - not every column,
 * including the potentially sizable `comments`/`customFieldsJson` ones (NBC-422).
 */
data class DeviceLookup(
    val id: Int,
    val deviceTypeId: Int?,
    val assetTag: String?,
    val statusLabel: String?,
)

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT id, deviceTypeId, assetTag, statusLabel FROM devices")
    fun observeLookup(): Flow<List<DeviceLookup>>

    @Query(
        """
        SELECT * FROM devices
        WHERE name LIKE '%' || :query || '%'
           OR serial LIKE '%' || :query || '%'
           OR assetTag LIKE '%' || :query || '%'
           OR primaryIp LIKE '%' || :query || '%'
           OR statusLabel LIKE '%' || :query || '%'
           OR siteName LIKE '%' || :query || '%'
           OR rackName LIKE '%' || :query || '%'
           OR roleName LIKE '%' || :query || '%'
           OR manufacturerName LIKE '%' || :query || '%'
           OR deviceTypeModel LIKE '%' || :query || '%'
           OR comments LIKE '%' || :query || '%'
        ORDER BY name COLLATE NOCASE
        """
    )
    fun search(query: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE id = :id") fun observeById(id: Int): Flow<DeviceEntity?>

    @Query("SELECT * FROM devices WHERE id = :id") suspend fun getById(id: Int): DeviceEntity?

    @Query(
        """
        SELECT * FROM devices
        WHERE lower(assetTag) = lower(:assetTag)
           OR lower(assetTag) = lower(:assetTagWithoutPrefix)
        LIMIT 1
        """
    )
    suspend fun getByAssetTag(assetTag: String, assetTagWithoutPrefix: String): DeviceEntity?

    @Query("SELECT * FROM devices") suspend fun getAll(): List<DeviceEntity>

    @Query("DELETE FROM devices WHERE id = :id") suspend fun deleteById(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<DeviceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(device: DeviceEntity)

    @Query("DELETE FROM devices") suspend fun clear()

    @Query("SELECT COUNT(*) FROM devices") suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM devices") fun observeCount(): Flow<Int>

    /**
     * The incremental-sync watermark - see
     * [dev.pschmitt.nyetbox.data.db.NetBoxObjectDao.maxLastUpdated].
     */
    @Query("SELECT MAX(lastUpdated) FROM devices") suspend fun maxLastUpdated(): String?

    /** See [dev.pschmitt.nyetbox.data.db.NetBoxObjectDao.pruneStale]. */
    @Query("DELETE FROM devices WHERE syncedAt < :cutoff") suspend fun pruneStale(cutoff: Long)
}
