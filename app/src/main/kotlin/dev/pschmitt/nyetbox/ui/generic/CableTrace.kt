package dev.pschmitt.nyetbox.ui.generic

import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One termination (interface, console port, power port, front/rear port, ...) at one end of a
 * cable-trace segment. [deviceTarget] is null for terminations with no parent device (e.g. some
 * provider-network/circuit terminations).
 */
data class CableTraceNode(
    val target: RefTarget,
    val deviceTarget: RefTarget?,
    val portLabel: String,
)

data class CableTraceCableInfo(
    val target: RefTarget?,
    val label: String,
    val statusLabel: String?,
    val color: String?,
)

/**
 * One `[nearEnds, cable, farEnds]` step of a cable trace - see
 * [dev.pschmitt.nyetbox.data.repository.CableTraceRepository].
 */
data class CableTraceSegment(
    val index: Int,
    val nearEnds: List<CableTraceNode>,
    val cable: CableTraceCableInfo?,
    val farEnds: List<CableTraceNode>,
)

private val traceJson = Json { ignoreUnknownKeys = true }

internal fun parseCableTraceSegment(index: Int, rawJson: String): CableTraceSegment? =
    runCatching {
            val array = traceJson.decodeFromString(JsonArray.serializer(), rawJson)
            val nearEnds =
                (array.getOrNull(0) as? JsonArray)?.mapNotNull { it.asCableTraceNode() }.orEmpty()
            val cable = (array.getOrNull(1) as? JsonObject)?.asCableTraceCableInfo()
            val farEnds =
                (array.getOrNull(2) as? JsonArray)?.mapNotNull { it.asCableTraceNode() }.orEmpty()
            if (nearEnds.isEmpty() && cable == null && farEnds.isEmpty()) null
            else CableTraceSegment(index, nearEnds, cable, farEnds)
        }
        .getOrNull()

private fun JsonElement.asCableTraceNode(): CableTraceNode? {
    val obj = this as? JsonObject ?: return null
    val target = obj.toTraceRefTarget() ?: return null
    val device = (obj["device"] as? JsonObject)?.toTraceRefTarget()
    val portLabel =
        (obj["display"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["name"] as? JsonPrimitive)?.contentOrNull
            ?: target.display
    return CableTraceNode(target, device, portLabel)
}

private fun JsonObject.asCableTraceCableInfo(): CableTraceCableInfo {
    val target = toTraceRefTarget()
    val label =
        (this["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: (this["display"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: target?.let { "Cable #${it.id}" }
            ?: "Cable"
    val statusRaw = this["status"]
    val statusLabel =
        when (statusRaw) {
            is JsonObject ->
                (statusRaw["label"] as? JsonPrimitive)?.contentOrNull
                    ?: (statusRaw["value"] as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> statusRaw.contentOrNull
            else -> null
        }
    val color = (this["color"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    return CableTraceCableInfo(target, label, statusLabel, color)
}

private fun JsonObject.toTraceRefTarget(): RefTarget? {
    val id = (this["id"] as? JsonPrimitive)?.intOrNull ?: return null
    val url = (this["url"] as? JsonPrimitive)?.contentOrNull ?: return null
    val display =
        (this["display"] as? JsonPrimitive)?.contentOrNull
            ?: (this["name"] as? JsonPrimitive)?.contentOrNull
            ?: "#$id"
    val endpointPath = NetBoxRef.endpointFromDetailUrl(url) ?: return null
    return RefTarget(display, endpointPath, id)
}
