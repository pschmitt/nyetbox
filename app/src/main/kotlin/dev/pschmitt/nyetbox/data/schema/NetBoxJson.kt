package dev.pschmitt.nyetbox.data.schema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Common, null-safe projection of the reference shape returned by NetBox serializers. */
data class NetBoxJsonReference(
    val id: Int,
    val url: String? = null,
    val display: String? = null,
)

fun JsonObject.jsonString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.jsonInt(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.jsonReference(key: String): NetBoxJsonReference? =
    (this[key] as? JsonObject)?.let { value ->
        value.jsonInt("id")?.let { id ->
            NetBoxJsonReference(
                id = id,
                url = value.jsonString("url"),
                display = value.jsonString("display") ?: value.jsonString("name"),
            )
        }
    }

fun JsonElement.jsonContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

fun isHttpUrl(text: String): Boolean =
    (text.startsWith("http://") || text.startsWith("https://")) && text.toHttpUrlOrNull() != null

/** NetBox-served uploaded files are always under a `/media/` path, regardless of app/plugin. */
fun isMediaUrl(text: String): Boolean {
    if (!text.startsWith("http://") && !text.startsWith("https://")) return false
    return text.toHttpUrlOrNull()?.encodedPath?.contains("/media/") == true
}

/**
 * Pulls `front_image` straight from an object's own JSON - the same source the detail screen uses -
 * rather than a separately-synced lookup table that may not cover every object (see
 * DeviceTypeEntity, which is only populated for device types referenced by a synced Device).
 */
fun JsonObject.frontImageUrl(): String? =
    jsonString("front_image")?.takeIf { it.isNotBlank() && isMediaUrl(it) }

/**
 * [frontImageUrl] from a not-yet-parsed JSON string - see [GenericObjectRepository.toEntity] for
 * the write-time, already-parsed equivalent this exists to avoid a redundant re-decode of.
 */
fun frontImageUrlFromRawJson(raw: String): String? = runCatching {
    Json.parseToJsonElement(raw) as? JsonObject
}
    .getOrNull()
    ?.frontImageUrl()
