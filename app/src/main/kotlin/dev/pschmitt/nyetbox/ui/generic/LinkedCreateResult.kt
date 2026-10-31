package dev.pschmitt.nyetbox.ui.generic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val LINKED_CREATE_RESULT_KEY = "linked_create_result"

@Serializable
data class LinkedCreateResult(
    val fieldKey: String,
    val endpointPath: String,
    val id: Int,
    val display: String,
    val reopenFocusedEditor: Boolean,
)

private val linkedCreateJson = Json

fun LinkedCreateResult.encodeForSavedState(): String =
    linkedCreateJson.encodeToString(LinkedCreateResult.serializer(), this)

fun decodeLinkedCreateResult(raw: String?): LinkedCreateResult? = raw?.let {
    runCatching {
            linkedCreateJson.decodeFromString(LinkedCreateResult.serializer(), it)
        }
        .getOrNull()
}
