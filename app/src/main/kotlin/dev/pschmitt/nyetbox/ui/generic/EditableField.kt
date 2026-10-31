package dev.pschmitt.nyetbox.ui.generic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class EditFieldKind {
    STRING,
    LONG_TEXT,
    JSON,
    NUMBER,
    INTEGER,
    BOOLEAN,
    REFERENCE,
    CHOICE,
    MULTI_REFERENCE,
    MULTI_CHOICE,
}

data class EditOption(
    val value: String,
    val label: String,
    val frontImageUrl: String? = null,
    val rearImageUrl: String? = null,
    val searchFields: Map<String, String> = emptyMap(),
)

/**
 * A field that can be edited and PATCHed back, e.g. via NBC-5's edit mode on the generic detail
 * screen. Reference and choice fields carry their current display label; their picker options are
 * loaded separately from the cache/NetBox OPTIONS metadata by [GenericDetailViewModel].
 */
data class EditableField(
    val key: String,
    val label: String,
    val kind: EditFieldKind,
    val value: String,
    val referenceEndpointPath: String? = null,
    val currentDisplay: String? = null,
    val customFieldName: String? = null,
    val markdown: Boolean = false,
    /** The admin-defined custom-field group (see `CustomFieldDefinition.group`), if any. */
    val group: String? = null,
)

/**
 * Converts an edit draft value into the label a user should see in the review dialog. NetBox
 * represents references as numeric IDs and choices as wire values, but the cache/OPTIONS response
 * already contains the human-readable labels needed for a useful before/after diff.
 */
fun displayEditValue(
    field: EditableField?,
    rawValue: String?,
    referenceOptions: Map<String, List<EditOption>> = emptyMap(),
    choiceOptions: Map<String, List<EditOption>> = emptyMap(),
): String {
    val raw = rawValue?.takeIf { it.isNotBlank() } ?: return "(empty)"
    if (field == null) return raw

    val options =
        when (field.kind) {
            EditFieldKind.REFERENCE,
            EditFieldKind.MULTI_REFERENCE -> referenceOptions[field.key].orEmpty()
            EditFieldKind.CHOICE,
            EditFieldKind.MULTI_CHOICE -> choiceOptions[field.key].orEmpty()
            else -> emptyList()
        }
    if (field.currentDisplay != null && raw == field.value) return field.currentDisplay

    return when (field.kind) {
        EditFieldKind.MULTI_REFERENCE,
        EditFieldKind.MULTI_CHOICE -> {
            val values = selectedValuesFromJson(raw)
            if (values.isEmpty()) "(empty)"
            else
                values.joinToString(", ") { value ->
                    options.firstOrNull { it.value == value }?.label ?: value
                }
        }
        EditFieldKind.REFERENCE,
        EditFieldKind.CHOICE -> options.firstOrNull { it.value == raw }?.label ?: raw
        else -> raw
    }
}

fun selectedValuesFromJson(text: String): List<String> =
    runCatching {
            Json.decodeFromString(JsonArray.serializer(), text).mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
        }
        .getOrDefault(emptyList())

fun selectedValuesToJson(values: Collection<String>): String =
    JsonArray(values.map(::JsonPrimitive)).toString()
