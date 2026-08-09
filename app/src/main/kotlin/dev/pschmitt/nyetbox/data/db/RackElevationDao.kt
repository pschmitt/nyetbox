package dev.pschmitt.nyetbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RackElevationDao {
    @Query(
        """
        SELECT * FROM rack_elevation
        WHERE rackId = :rackId AND face = :face
        ORDER BY position DESC
        """
    )
    fun observe(rackId: Int, face: String): Flow<List<RackElevationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<RackElevationEntity>)

    @Query("DELETE FROM rack_elevation WHERE rackId = :rackId AND face = :face")
    suspend fun clear(rackId: Int, face: String)

    /** Whether any elevation slot (either face) is already cached for this rack (NBC-432). */
    @Query("SELECT COUNT(*) FROM rack_elevation WHERE rackId = :rackId")
    suspend fun count(rackId: Int): Int
}
