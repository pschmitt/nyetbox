package dev.pschmitt.nyetbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** See [NetBoxObjectEntity.frontImageUrl] - a thumbnail-only projection, no `json` column. */
data class ObjectThumbnail(val id: Int, val frontImageUrl: String)

@Dao
interface NetBoxObjectDao {
    @Query(
        "SELECT id, frontImageUrl FROM netbox_objects WHERE endpointPath = :endpointPath AND frontImageUrl IS NOT NULL"
    )
    fun observeThumbnails(endpointPath: String): Flow<List<ObjectThumbnail>>

    @Query(
        "SELECT * FROM netbox_objects WHERE endpointPath = :endpointPath ORDER BY display COLLATE NOCASE"
    )
    fun observeAll(endpointPath: String): Flow<List<NetBoxObjectEntity>>

    @Query(
        """
        SELECT * FROM netbox_objects
        WHERE endpointPath = :endpointPath AND display LIKE '%' || :query || '%'
        ORDER BY display COLLATE NOCASE
        """
    )
    fun search(endpointPath: String, query: String): Flow<List<NetBoxObjectEntity>>

    @Query(
        """
        SELECT * FROM netbox_objects
        WHERE endpointPath = :endpointPath
          AND (
              display LIKE '%' || :query || '%'
              OR secondaryLine LIKE '%' || :query || '%'
              OR json LIKE '%' || :query || '%'
          )
        ORDER BY display COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun searchAllInEndpoint(
        endpointPath: String,
        query: String,
        limit: Int,
    ): Flow<List<NetBoxObjectEntity>>

    /**
     * Narrows to rows whose precomputed [NetBoxObjectEntity.relatedObjectId] matches - a real
     * indexed lookup instead of [observeAll]'s full-endpoint scan. Rows that predate the sync pass
     * which started populating that column (`relatedObjectId IS NULL`) are kept in the result so
     * the caller's existing JSON-based relation check stays the correctness authority: this query
     * only narrows the candidate set, it never has to be the sole source of truth.
     */
    @Query(
        """
        SELECT * FROM netbox_objects
        WHERE endpointPath = :endpointPath
          AND (relatedObjectId = :relatedObjectId OR relatedObjectId IS NULL)
        ORDER BY display COLLATE NOCASE
        """
    )
    fun observeByRelatedObjectId(
        endpointPath: String,
        relatedObjectId: Int,
    ): Flow<List<NetBoxObjectEntity>>

    /** Suspend twin of [observeByRelatedObjectId] for one-shot reads (e.g. a sync pass). */
    @Query(
        """
        SELECT * FROM netbox_objects
        WHERE endpointPath = :endpointPath
          AND (relatedObjectId = :relatedObjectId OR relatedObjectId IS NULL)
        ORDER BY display COLLATE NOCASE
        """
    )
    suspend fun getByRelatedObjectId(
        endpointPath: String,
        relatedObjectId: Int,
    ): List<NetBoxObjectEntity>

    @Query("SELECT * FROM netbox_objects WHERE endpointPath = :endpointPath AND id = :id")
    fun observeById(endpointPath: String, id: Int): Flow<NetBoxObjectEntity?>

    @Query("SELECT * FROM netbox_objects ORDER BY endpointPath, display COLLATE NOCASE")
    fun observeAllObjects(): Flow<List<NetBoxObjectEntity>>

    /**
     * Just the distinct endpoint paths currently cached - used by
     * [dev.pschmitt.nyetbox.data.repository.GlobalSearchRepository] to fan its whole-cache index
     * out into one bounded per-endpoint [observeAll] query each, instead of a single `SELECT *`
     * across every row of every endpoint. That single-cursor query used to hand Room's generated
     * code a CursorWindow spanning the entire table (every endpoint's full raw `json` payload at
     * once), which on a memory-constrained device (Pixel 5, 256MB heap) could fail to fully page
     * in under memory pressure and throw `IllegalStateException: Couldn't read row N, col 0 from
     * CursorWindow` well past the point where the window ran out of room.
     */
    @Query("SELECT DISTINCT endpointPath FROM netbox_objects")
    fun observeAllEndpointPaths(): Flow<List<String>>

    @Query("SELECT * FROM netbox_objects WHERE endpointPath = :endpointPath AND id = :id")
    suspend fun getById(endpointPath: String, id: Int): NetBoxObjectEntity?

    @Query(
        "SELECT * FROM netbox_objects WHERE endpointPath = :endpointPath ORDER BY display COLLATE NOCASE"
    )
    suspend fun getAll(endpointPath: String): List<NetBoxObjectEntity>

    /**
     * Cross-endpoint search (NBC-13's global search) - unlike [search], not scoped to one model,
     * since the whole point is finding a match regardless of which endpoint it's cached under.
     */
    @Query(
        """
        SELECT * FROM netbox_objects
        WHERE display LIKE '%' || :query || '%'
           OR secondaryLine LIKE '%' || :query || '%'
           OR json LIKE '%' || :query || '%'
        ORDER BY display COLLATE NOCASE
        LIMIT :limit
        """
    )
    fun searchAll(query: String, limit: Int): Flow<List<NetBoxObjectEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(objects: List<NetBoxObjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(obj: NetBoxObjectEntity)

    @Query("DELETE FROM netbox_objects WHERE endpointPath = :endpointPath AND id = :id")
    suspend fun delete(endpointPath: String, id: Int)

    @Query("SELECT COUNT(*) FROM netbox_objects WHERE endpointPath = :endpointPath")
    suspend fun count(endpointPath: String): Int

    @Query("SELECT COUNT(*) FROM netbox_objects") suspend fun countAll(): Int

    @Query("SELECT * FROM netbox_objects") suspend fun getAll(): List<NetBoxObjectEntity>

    /**
     * The most recent `last_updated` value cached for this endpoint - the incremental-sync
     * watermark. NetBox's ISO-8601 UTC timestamps sort correctly as plain TEXT, so MAX() works
     * without parsing.
     */
    @Query("SELECT MAX(lastUpdated) FROM netbox_objects WHERE endpointPath = :endpointPath")
    suspend fun maxLastUpdated(endpointPath: String): String?

    /**
     * Removes rows for this endpoint not touched by the full sync pass that started at [cutoff] -
     * i.e. objects the server no longer has. Only call this after a full (unfiltered) sync of this
     * endpoint has just completed successfully; every row it touched was re-stamped with a fresh
     * syncedAt, so anything older is stale.
     */
    @Query("DELETE FROM netbox_objects WHERE endpointPath = :endpointPath AND syncedAt < :cutoff")
    suspend fun pruneStale(endpointPath: String, cutoff: Long)
}
