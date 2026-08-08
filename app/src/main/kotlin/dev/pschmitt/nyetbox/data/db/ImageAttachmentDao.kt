package dev.pschmitt.nyetbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageAttachmentDao {
    @Query(
        "SELECT * FROM image_attachments WHERE objectType = :objectType AND objectId = :objectId"
    )
    fun observeFor(objectType: String, objectId: Int): Flow<List<ImageAttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(attachments: List<ImageAttachmentEntity>)

    @Query("SELECT * FROM image_attachments") suspend fun getAll(): List<ImageAttachmentEntity>

    @Query("SELECT COUNT(*) FROM image_attachments") suspend fun count(): Int

    @Query("DELETE FROM image_attachments WHERE objectType = :objectType AND objectId = :objectId")
    suspend fun clearFor(objectType: String, objectId: Int)

    @Query("DELETE FROM image_attachments WHERE objectType = :objectType")
    suspend fun clearForObjectType(objectType: String)
}
