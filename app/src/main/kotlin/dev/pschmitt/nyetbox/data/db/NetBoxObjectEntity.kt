package dev.pschmitt.nyetbox.data.db

import androidx.room.Entity
import androidx.room.Index

/**
 * Generic cache for any NetBox object (any endpoint, not just devices) - the raw API response is
 * kept as JSON and rendered field-by-field at the UI layer (see GenericDetailScreen) rather than
 * mapped into a typed entity per object type, since covering NetBox's full data model with a typed
 * entity per model would mean hand-writing 100+ near-duplicate entities.
 */
@Entity(
    tableName = "netbox_objects",
    primaryKeys = ["endpointPath", "id"],
    indices = [Index(value = ["endpointPath", "relatedObjectId"])],
)
data class NetBoxObjectEntity(
    val endpointPath: String,
    val id: Int,
    val display: String,
    val secondaryLine: String?,
    val json: String,
    val syncedAt: Long,
    /** NetBox's own `last_updated` field, used as an incremental-sync watermark. */
    val lastUpdated: String? = null,
    /**
     * The id of this row's single "parent" NetBox object, precomputed at sync/write time for the
     * handful of relations queried on every normal screen open (a device's interfaces/ports, a
     * device's journal entries, a rack's devices) - see
     * [dev.pschmitt.nyetbox.data.repository.GenericObjectRepository]'s
     * `precomputedRelatedObjectId`. Without this, [NetBoxObjectDao.observeAll] plus an in-memory
     * `matchesRelation` scan was the only way to answer "does this device have any interfaces,"
     * which meant decoding and checking every cached row of that endpoint *across every device in
     * the install* each time a detail screen opened, not just this one device's rows. `null` means
     * either this endpoint's relation isn't precomputed (arbitrary relations, e.g. the
     * related-items bottom sheet, still fall back to the old scan-and-decode path) or this row
     * predates the sync pass that started filling it in.
     */
    val relatedObjectId: Int? = null,
    /**
     * `front_image` pulled from this row's own JSON at sync/write time (only populated for
     * `api/dcim/device-types/` rows; `null` elsewhere) - see
     * [dev.pschmitt.nyetbox.data.schema.frontImageUrlFromRawJson]. Avoids decoding every cached
     * device-type's JSON on the main thread on every dashboard/search read (NBC-422). `null` also
     * covers rows cached before this column existed; callers needing a value for those should fall
     * back to [dev.pschmitt.nyetbox.data.schema.frontImageUrlFromRawJson] on [json] directly.
     */
    val frontImageUrl: String? = null,
)
