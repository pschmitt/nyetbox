package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.RackElevationDao
import dev.pschmitt.nyetbox.data.db.RackElevationEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

enum class RackFace(val apiValue: String, val label: String) {
    FRONT("front", "Front"),
    REAR("rear", "Rear"),
}

@Singleton
class RackElevationRepository
@Inject
constructor(private val api: GenericNetBoxApi, private val dao: RackElevationDao) {

    fun observe(rackId: Int, face: RackFace): Flow<List<RackElevationEntity>> =
        dao.observe(rackId, face.apiValue)

    /** Whether this rack has any cached elevation slots at all, either face (NBC-432). */
    suspend fun hasCached(rackId: Int): Boolean = dao.count(rackId) > 0

    /** Refreshes one face while leaving the existing Room snapshot intact on failure. */
    suspend fun refresh(rackId: Int, face: RackFace): Result<Int> = runCatching {
        val slots =
            api.listObjects(
                    "api/dcim/racks/$rackId/elevation/",
                    mapOf("face" to face.apiValue, "limit" to "1000"),
                )
                .results
                .mapNotNull { it.toRackElevationEntity(rackId, face.apiValue) }
        dao.clear(rackId, face.apiValue)
        dao.upsertAll(slots)
        slots.size
    }
}

internal fun JsonObject.toRackElevationEntity(rackId: Int, face: String): RackElevationEntity? {
    val slotName = (this["name"] as? JsonPrimitive)?.contentOrNull ?: return null
    val position =
        (this["id"] as? JsonPrimitive)?.doubleOrNull
            ?: slotName.removePrefix("U").toDoubleOrNull()
            ?: return null
    val device = this["device"] as? JsonObject
    val deviceId = (device?.get("id") as? JsonPrimitive)?.intOrNull
    val deviceDisplay =
        (device?.get("display") as? JsonPrimitive)?.contentOrNull
            ?: (device?.get("name") as? JsonPrimitive)?.contentOrNull
    val occupied =
        (this["occupied"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: (deviceId != null)
    return RackElevationEntity(
        rackId = rackId,
        face = face,
        slotName = slotName,
        position = position,
        deviceId = deviceId,
        deviceDisplay = deviceDisplay,
        occupied = occupied,
        syncedAt = System.currentTimeMillis(),
    )
}
