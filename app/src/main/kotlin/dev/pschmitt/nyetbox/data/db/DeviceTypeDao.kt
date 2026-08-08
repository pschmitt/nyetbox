package dev.pschmitt.nyetbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceTypeDao {
    @Query("SELECT * FROM device_types") fun observeAll(): Flow<List<DeviceTypeEntity>>

    @Query("SELECT * FROM device_types WHERE id = :id")
    fun observeById(id: Int): Flow<DeviceTypeEntity?>

    @Query("SELECT * FROM device_types WHERE id = :id")
    suspend fun getById(id: Int): DeviceTypeEntity?

    @Query("SELECT * FROM device_types") suspend fun getAll(): List<DeviceTypeEntity>

    @Query(
        "SELECT COUNT(*) FROM device_types WHERE frontImageUrl IS NOT NULL OR rearImageUrl IS NOT NULL"
    )
    suspend fun countWithImages(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(deviceType: DeviceTypeEntity)
}
