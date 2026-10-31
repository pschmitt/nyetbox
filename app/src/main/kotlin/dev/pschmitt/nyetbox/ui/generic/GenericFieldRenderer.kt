package dev.pschmitt.nyetbox.ui.generic

import dev.pschmitt.nyetbox.data.repository.CustomFieldDefinition
import dev.pschmitt.nyetbox.data.schema.Humanize
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.data.schema.isHttpUrl
import dev.pschmitt.nyetbox.data.schema.isMediaUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val SKIPPED_KEYS = setOf("id", "url", "display", "display_url", "custom_fields")

// NetBox documents these specific fields as Markdown-enabled across (almost) every model -
// "description" is deliberately not included, it's plain short text, not Markdown.
private val MARKDOWN_KEYS = setOf("comments")

// Device-type stock photos are shown inline; other media URLs remain downloadable attachments.
private val IMAGE_KEYS = setOf("front_image", "rear_image")

// Keep copy actions focused on values users commonly transfer elsewhere, rather than adding one
// to every free-text field.
private val COPYABLE_KEYS =
    setOf("serial", "asset_tag", "address", "primary_ip", "primary_ip4", "primary_ip6")

private val USER_REFERENCE_KEYS = setOf("created_by", "last_updated_by")
private val METADATA_KEYS = setOf("created", "last_updated")
private const val CUSTOM_FIELDS_ENDPOINT_PATH = "api/extras/custom-fields/"

// Synthetic bucket for custom fields with no admin-defined group - shared with
// GenericDetailFields.kt so it can tell a real NetBox custom-field group apart from this fallback
// when deciding what to cluster into a shared card.
internal const val UNGROUPED_CUSTOM_FIELD_GROUP_LABEL = "Other"

private val MATTER_PAIRING_CODE_PATTERN = Regex("^\\d{4}-\\d{3}-\\d{4}$")

internal fun isMatterPairingCode(value: String): Boolean =
    MATTER_PAIRING_CODE_PATTERN.matches(value)

// Meta/system fields NetBox manages itself - not user-editable, or too complex to round-trip as
// plain text yet (custom_fields needs its own per-field-type handling, not a blanket text field).
private val EDIT_BLOCKLIST =
    setOf("id", "url", "display", "display_url", "created", "last_updated", "custom_fields")

/**
 * Turns a raw NetBox object (any type) into a generic list of fields to render, without needing any
 * type-specific knowledge: nested objects with `id`+`url` are treated as references to another
 * object (tappable, navigable to that object's own generic detail screen - this is also how tags
 * end up as tappable chips, since NetBox tags are real objects with their own detail view too).
 */
fun buildFieldRows(
    obj: JsonObject,
    markdownCustomFieldNames: Set<String> = emptySet(),
    endpointPath: String? = null,
): List<FieldRow> =
    buildFieldRows(
        obj,
        markdownCustomFieldNames.map {
            CustomFieldDefinition(it, "markdown", null, null, Int.MAX_VALUE)
        },
        endpointPath,
    )

fun buildFieldRows(
    obj: JsonObject,
    customFieldDefinitions: List<CustomFieldDefinition>,
    endpointPath: String? = null,
): List<FieldRow> = buildList {
    for ((key, value) in obj) {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        when {
            key in SKIPPED_KEYS -> Unit
            key.endsWith("_display") && key.removeSuffix("_display") in USER_REFERENCE_KEYS -> Unit
            key in METADATA_KEYS ->
                text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(FieldRow.Metadata(Humanize.label(key), it)) }
            key in MARKDOWN_KEYS ->
                text
                    ?.takeIf { it.isNotBlank() }
                    ?.let { add(FieldRow.Markdown(Humanize.label(key), it)) }
            key in IMAGE_KEYS && text != null && isMediaUrl(text) ->
                add(FieldRow.Image(Humanize.label(key), text))
            text != null && isMediaUrl(text) -> {
                val filename =
                    (obj["filename"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: text.substringAfterLast('/')
                add(FieldRow.FileAttachment(Humanize.label(key), text, filename))
            }
            else -> {
                val count = (value as? JsonPrimitive)?.intOrNull
                val target = countTargetFor(key, obj, endpointPath)
                if (count != null && target != null) {
                    if (count > 0) add(FieldRow.Count(target.listLabel, count.toString(), target))
                } else if (key in USER_REFERENCE_KEYS) {
                    val display =
                        (obj["${key}_display"] as? JsonPrimitive)?.contentOrNull
                            ?: userReferenceDisplay(value)
                            ?: value.toString().takeIf { it.isNotBlank() }
                    display?.let { add(FieldRow.PlainText(Humanize.label(key), it)) }
                } else {
                    renderField(key, Humanize.label(key), value)?.let(::add)
                }
            }
        }
    }
    // custom_fields is a NetBox-instance-specific map of {field_name: value} - each one gets its
    // own row via the same generic rendering used for top-level fields, rather than being folded
    // into one flattened blob or dropped for non-primitive values (object/multi-select custom
    // fields).
    (obj["custom_fields"] as? JsonObject)?.let { customFields ->
        val definitions = customFieldDefinitions.associateBy { it.name }
        val customFieldRows = buildList {
            val sortedFields =
                customFields.entries
                    .withIndex()
                    .sortedWith { left, right ->
                        val leftDefinition = definitions[left.value.key]
                        val rightDefinition = definitions[right.value.key]
                        val leftGroup = leftDefinition?.group?.trim().orEmpty()
                        val rightGroup = rightDefinition?.group?.trim().orEmpty()
                        when {
                            leftGroup.isNotBlank() && rightGroup.isBlank() -> -1
                            leftGroup.isBlank() && rightGroup.isNotBlank() -> 1
                            leftGroup.isBlank() -> left.index.compareTo(right.index)
                            else ->
                                String.CASE_INSENSITIVE_ORDER.compare(leftGroup, rightGroup)
                                    .takeIf { it != 0 }
                                    ?: (leftDefinition?.weight ?: Int.MAX_VALUE)
                                        .compareTo(rightDefinition?.weight ?: Int.MAX_VALUE)
                                        .takeIf { it != 0 }
                                    ?: String.CASE_INSENSITIVE_ORDER.compare(
                                        leftDefinition?.label ?: Humanize.label(left.value.key),
                                        rightDefinition?.label ?: Humanize.label(right.value.key),
                                    )
                        }
                    }
                    .map { it.value }
            var activeGroup: String? = null
            for ((key, value) in sortedFields) {
                val definition = definitions[key]
                val group =
                    definition?.group?.trim()?.takeIf { it.isNotBlank() }
                        ?: UNGROUPED_CUSTOM_FIELD_GROUP_LABEL
                val label = definition?.label?.takeIf { it.isNotBlank() } ?: Humanize.label(key)
                val textValue = (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val fieldRow =
                    if (textValue != null && isMatterPairingCode(textValue)) {
                        FieldRow.PlainText(label, textValue, matterPairingCode = true)
                    } else if (definition?.type in setOf("markdown", "text", "longtext")) {
                        textValue?.let { FieldRow.Markdown(label, it) }
                    } else {
                        renderField(key, label, value)
                    }
                // Add the heading only when this field produces a visible row. This prevents an
                // empty/hidden field from leaving an orphaned group heading above the next group.
                fieldRow?.let {
                    if (group != activeGroup) add(FieldRow.CustomGroup(group))
                    activeGroup = group
                    add(it)
                }
            }
        }
        if (customFieldRows.isNotEmpty()) {
            add(FieldRow.Section("Custom fields"))
            addAll(customFieldRows)
        }
    }
}

private fun countTargetFor(key: String, obj: JsonObject, endpointPath: String?): CountTarget? {
    val parentId = (obj["id"] as? JsonPrimitive)?.intOrNull ?: return null
    val parentEndpoint = endpointPath ?: return null
    val countModel = key.removeSuffix("_count").takeIf { it != key } ?: return null
    val modelKey = canonicalCountModelKey(countModel)
    val endpoint =
        COUNT_MODEL_ENDPOINTS[modelKey]
            ?: inferredCountEndpoint(parentEndpoint, modelKey)
            ?: return null
    val relationKey = countRelationKey(parentEndpoint, key, modelKey)
    return CountTarget(endpoint, countListLabel(endpoint), relationKey, parentId)
}

/**
 * Resolves reverse-relation counts for every ordinary NetBox collection, not just a fixed list of
 * screens. Most serializers follow the `{model}_count`/`{parent_model}` convention, so the model
 * endpoint and parent relation can be inferred. This registry supplies cross-app and irregular
 * model names where that convention alone is insufficient.
 */
private val COUNT_MODEL_ENDPOINTS =
    mapOf(
        "aggregate" to "api/ipam/aggregates/",
        "asn" to "api/ipam/asns/",
        "asn_range" to "api/ipam/asn-ranges/",
        "circuit" to "api/circuits/circuits/",
        "cluster" to "api/virtualization/clusters/",
        "cluster_group" to "api/virtualization/cluster-groups/",
        "cluster_type" to "api/virtualization/cluster-types/",
        "contact" to "api/tenancy/contacts/",
        "device" to "api/dcim/devices/",
        "device_bay" to "api/dcim/device-bays/",
        "device_role" to "api/dcim/device-roles/",
        "device_type" to "api/dcim/device-types/",
        "console_port_template" to "api/dcim/console-port-templates/",
        "device_bay_template" to "api/dcim/device-bay-templates/",
        "front_port" to "api/dcim/front-ports/",
        "front_port_template" to "api/dcim/front-port-templates/",
        "interface" to "api/dcim/interfaces/",
        "ip_address" to "api/ipam/ip-addresses/",
        "ip_range" to "api/ipam/ip-ranges/",
        "inventory_item" to "api/dcim/inventory-items/",
        "location" to "api/dcim/locations/",
        "module" to "api/dcim/modules/",
        "module_bay" to "api/dcim/module-bays/",
        "module_bay_template" to "api/dcim/module-bay-templates/",
        "power_feed" to "api/dcim/power-feeds/",
        "power_outlet" to "api/dcim/power-outlets/",
        "power_port" to "api/dcim/power-ports/",
        "power_port_template" to "api/dcim/power-port-templates/",
        "prefix" to "api/ipam/prefixes/",
        "provider" to "api/circuits/providers/",
        "provider_network" to "api/circuits/provider-networks/",
        "rack" to "api/dcim/racks/",
        "rack_group" to "api/dcim/rack-groups/",
        "rack_role" to "api/dcim/rack-roles/",
        "rear_port" to "api/dcim/rear-ports/",
        "rear_port_template" to "api/dcim/rear-port-templates/",
        "region" to "api/dcim/regions/",
        "site" to "api/dcim/sites/",
        "tenant" to "api/tenancy/tenants/",
        "tunnel" to "api/vpn/tunnels/",
        "tunnel_group" to "api/vpn/tunnel-groups/",
        "virtual_disk" to "api/virtualization/virtual-disks/",
        "virtual_machine" to "api/virtualization/virtual-machines/",
        "vlan" to "api/ipam/vlans/",
        "vlan_group" to "api/ipam/vlan-groups/",
        "vrf" to "api/ipam/vrfs/",
        "wireless_lan" to "api/wireless/wireless-lans/",
        "wireless_link" to "api/wireless/wireless-links/",
    )

private fun canonicalCountModelKey(modelKey: String): String =
    when (modelKey.removePrefix("child_")) {
        "ipaddress",
        "ipaddresses" -> "ip_address"
        "iprange",
        "ipranges" -> "ip_range"
        "virtualmachine",
        "virtualmachines" -> "virtual_machine"
        "virtualdisk",
        "virtualdisks" -> "virtual_disk"
        "devicetype",
        "devicetypes" -> "device_type"
        "frontport",
        "frontports" -> "front_port"
        "rearport",
        "rearports" -> "rear_port"
        "powerport",
        "powerports" -> "power_port"
        "poweroutlet",
        "poweroutlets" -> "power_outlet"
        "inventoryitem",
        "inventoryitems" -> "inventory_item"
        else -> modelKey
    }

private fun inferredCountEndpoint(parentEndpoint: String, modelKey: String): String? {
    val prefix = parentEndpoint.trimEnd('/').substringBeforeLast('/')
    val collection = pluralCollectionSegment(modelKey)
    return "$prefix/$collection/"
}

private fun countRelationKey(parentEndpoint: String, countKey: String, modelKey: String): String {
    if (
        countKey == "prefix_count" &&
            parentEndpoint in setOf("api/dcim/sites/", "api/dcim/locations/")
    ) {
        return "scope"
    }
    if (countKey == "child_prefix_count") return "parent"
    return parentModelKey(parentEndpoint).takeIf { it.isNotBlank() } ?: modelKey
}

private fun parentModelKey(endpointPath: String): String {
    val collection = endpointPath.trimEnd('/').substringAfterLast('/')
    val model = collection.replace('-', '_')
    val singular =
        when {
            model.endsWith("_types") || model.endsWith("_groups") -> model.dropLast(1)
            model.endsWith("ies") -> model.dropLast(3) + "y"
            model.endsWith('s') -> model.dropLast(1)
            else -> model
        }
    return canonicalCountModelKey(singular)
}

private fun pluralCollectionSegment(modelKey: String): String {
    val kebab = modelKey.replace('_', '-')
    val lastWord = kebab.substringAfterLast('-')
    return when {
        lastWord.endsWith("y") &&
            lastWord.length > 1 &&
            lastWord[lastWord.lastIndex - 1] !in "aeiou" -> kebab.dropLast(1) + "ies"
        kebab.endsWith("s") -> kebab
        else -> "${kebab}s"
    }
}

private fun countListLabel(endpointPath: String): String =
    Humanize.label(endpointPath.trimEnd('/').substringAfterLast('/'))

private fun renderField(key: String, label: String, value: JsonElement): FieldRow? =
    when (value) {
        is JsonNull -> null
        is JsonPrimitive -> renderPrimitive(key, label, value)
        is JsonObject -> renderObject(key, label, value)
        is JsonArray -> renderArray(key, label, value)
    }

private fun renderPrimitive(key: String, label: String, value: JsonPrimitive): FieldRow? {
    value.booleanOrNull?.let {
        return FieldRow.BooleanValue(label, it)
    }
    val text = value.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    if (value.isString && isHttpUrl(text)) return FieldRow.ExternalLink(label, text)
    return FieldRow.PlainText(label, text, copyable = key in COPYABLE_KEYS)
}

/** Shortens only URLs served by the configured NetBox origin; external links stay complete. */
fun shortenDisplayedUrl(url: String, netboxBaseUrl: String? = null): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    val configured = netboxBaseUrl?.toHttpUrlOrNull() ?: return url
    if (
        !parsed.scheme.equals(configured.scheme, ignoreCase = true) ||
            !parsed.host.equals(configured.host, ignoreCase = true) ||
            parsed.port != configured.port
    ) {
        return url
    }
    return buildString {
        append(parsed.encodedPath.ifBlank { "/" })
        parsed.encodedQuery?.let { append('?').append(it) }
        parsed.encodedFragment?.let { append('#').append(it) }
    }
}

private fun renderObject(key: String, label: String, value: JsonObject): FieldRow? {
    if (key in USER_REFERENCE_KEYS) {
        userReferenceDisplay(value)?.let {
            return FieldRow.PlainText(label, it)
        }
    }
    asRefTarget(value)?.let {
        return FieldRow.Reference(label, it, copyable = key in COPYABLE_KEYS)
    }
    // Choice-style field, e.g. status: {"value": "active", "label": "Active"}.
    val choiceLabel = (value["label"] as? JsonPrimitive)?.contentOrNull
    if (choiceLabel != null) return FieldRow.PlainText(label, choiceLabel)
    // Unrecognized nested object (e.g. weight: {value, unit}) - best-effort flatten.
    val flattened =
        value.entries.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { "$k: $it" }
        }
    return flattened
        .takeIf { it.isNotEmpty() }
        ?.let { FieldRow.PlainText(label, it.joinToString(", ")) }
}

private fun userReferenceDisplay(value: JsonElement): String? {
    val user =
        when (value) {
            is JsonObject -> (value["user"] as? JsonObject) ?: value
            else -> return (value as? JsonPrimitive)?.contentOrNull
        }
    listOf("display", "username", "name", "full_name", "email").forEach { key ->
        (user[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
    }
    val firstName = (user["first_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val lastName = (user["last_name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return "$firstName $lastName".trim().takeIf { it.isNotBlank() }
        ?: (user["id"] as? JsonPrimitive)?.contentOrNull
}

private fun renderArray(key: String, label: String, value: JsonArray): FieldRow? {
    if (value.isEmpty()) return null
    val refs = value.mapNotNull { (it as? JsonObject)?.let(::asRefTarget) }
    if (refs.size == value.size) {
        return if (key == "tags") FieldRow.TagList(label, refs)
        else FieldRow.ReferenceList(label, refs)
    }
    val chips = value.mapNotNull {
        when (it) {
            is JsonPrimitive -> it.contentOrNull
            is JsonObject ->
                (it["display"] as? JsonPrimitive)?.contentOrNull
                    ?: (it["name"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }
    return chips.takeIf { it.isNotEmpty() }?.let { FieldRow.ChipList(label, it) }
}

private fun asRefTarget(value: JsonObject): RefTarget? {
    val id = (value["id"] as? JsonPrimitive)?.intOrNull ?: return null
    val url = (value["url"] as? JsonPrimitive)?.contentOrNull ?: return null
    val display =
        (value["display"] as? JsonPrimitive)?.contentOrNull
            ?: (value["name"] as? JsonPrimitive)?.contentOrNull
            ?: "#$id"
    val endpointPath = NetBoxRef.endpointFromDetailUrl(url) ?: return null
    return RefTarget(display, endpointPath, id)
}

/** The fields that can round-trip through the generic edit form and PATCH cleanly. */
fun buildEditableFields(obj: JsonObject): List<EditableField> =
    buildEditableFields(obj, emptyList())

fun buildEditableFields(
    obj: JsonObject,
    customFieldDefinitions: List<CustomFieldDefinition>,
): List<EditableField> = buildEditableFields(obj, customFieldDefinitions, null)

fun buildEditableFields(
    obj: JsonObject,
    customFieldDefinitions: List<CustomFieldDefinition>,
    endpointPath: String?,
): List<EditableField> = buildList {
    for ((key, value) in obj) {
        if (key in EDIT_BLOCKLIST) continue
        if (endpointPath == CUSTOM_FIELDS_ENDPOINT_PATH) {
            customFieldAdminEditableField(key, value)?.let(::add)
                ?: editableTopLevelField(key, value)?.let(::add)
        } else {
            editableTopLevelField(key, value)?.let(::add)
        }
    }
    val definitions = customFieldDefinitions.associateBy { it.name }
    (obj["custom_fields"] as? JsonObject)?.forEach { (name, value) ->
        customFieldEditableField(name, value, definitions[name])?.let(::add)
    }
}

private fun customFieldAdminEditableField(
    key: String,
    value: JsonElement,
): EditableField? {
    val label = Humanize.label(key)

    fun choiceField(): EditableField {
        val primitive = value as? JsonPrimitive
        val objectValue = value as? JsonObject
        return EditableField(
            key = key,
            label = label,
            kind = EditFieldKind.CHOICE,
            value =
                primitive?.contentOrNull
                    ?: (objectValue?.get("value") as? JsonPrimitive)?.contentOrNull
                    ?: "",
            currentDisplay =
                (objectValue?.get("label") as? JsonPrimitive)?.contentOrNull
                    ?: (objectValue?.get("display") as? JsonPrimitive)?.contentOrNull,
        )
    }

    return when (key) {
        "type",
        "related_object_type",
        "filter_logic",
        "ui_visible",
        "ui_editable" -> choiceField()
        "object_types" ->
            EditableField(
                key = key,
                label = label,
                kind = EditFieldKind.MULTI_CHOICE,
                value =
                    selectedValuesToJson(
                        (value as? JsonArray).orEmpty().mapNotNull {
                            (it as? JsonPrimitive)?.contentOrNull
                        }
                    ),
            )
        "choice_set" -> {
            val target = (value as? JsonObject)?.let(::asRefTarget)
            EditableField(
                key = key,
                label = label,
                kind = EditFieldKind.REFERENCE,
                value = target?.id?.toString().orEmpty(),
                referenceEndpointPath = "api/extras/custom-field-choice-sets/",
                currentDisplay = target?.display,
            )
        }
        "default",
        "related_object_filter" ->
            EditableField(
                key = key,
                label = label,
                kind = EditFieldKind.JSON,
                value = value.takeUnless { it is JsonNull }?.toString().orEmpty(),
            )
        else -> null
    }
}

private fun editableTopLevelField(key: String, value: JsonElement): EditableField? =
    when (value) {
        is JsonNull -> null
        is JsonObject -> {
            asRefTarget(value)?.let { target ->
                EditableField(
                    key = key,
                    label = Humanize.label(key),
                    kind = EditFieldKind.REFERENCE,
                    value = target.id.toString(),
                    referenceEndpointPath = target.endpointPath,
                    currentDisplay = target.display,
                )
            }
                ?: run {
                    val choiceValue = (value["value"] as? JsonPrimitive)?.contentOrNull
                    val choiceLabel = (value["label"] as? JsonPrimitive)?.contentOrNull
                    if (choiceValue != null && choiceLabel != null) {
                        EditableField(
                            key = key,
                            label = Humanize.label(key),
                            kind = EditFieldKind.CHOICE,
                            value = choiceValue,
                            currentDisplay = choiceLabel,
                        )
                    } else null
                }
        }
        is JsonPrimitive -> {
            // Fields like device-type's front_image/rear_image are absolute media URLs computed by
            // NetBox, not plain text - PATCHing one back as-is fails server-side.
            if (value.isString && value.contentOrNull?.let(::isMediaUrl) == true) return null
            val kind =
                when {
                    !value.isString && (value.content == "true" || value.content == "false") ->
                        EditFieldKind.BOOLEAN
                    !value.isString && value.doubleOrNull != null -> EditFieldKind.NUMBER
                    else -> EditFieldKind.STRING
                }
            EditableField(
                key,
                Humanize.label(key),
                if (key == "comments") EditFieldKind.LONG_TEXT else kind,
                value.contentOrNull ?: "",
                markdown = key == "comments",
            )
        }
        is JsonArray -> null
    }

private fun customFieldEditableField(
    name: String,
    value: JsonElement,
    definition: CustomFieldDefinition?,
): EditableField? {
    definition ?: return null
    val type = definition.type.lowercase()
    val key = "custom_fields.$name"
    val label = definition.label?.takeIf { it.isNotBlank() } ?: Humanize.label(name)
    val group = definition.group?.trim()?.takeIf { it.isNotBlank() }
    fun common(
        kind: EditFieldKind,
        currentValue: String,
        endpoint: String? = null,
        display: String? = null,
        markdown: Boolean = false,
    ): EditableField =
        EditableField(
            key = key,
            label = label,
            kind = kind,
            value = currentValue,
            referenceEndpointPath = endpoint,
            currentDisplay = display,
            customFieldName = name,
            markdown = markdown,
            group = group,
        )
    return when (type) {
        "object" -> {
            val target = (value as? JsonObject)?.let(::asRefTarget) ?: return null
            common(
                EditFieldKind.REFERENCE,
                target.id.toString(),
                target.endpointPath,
                target.display,
            )
        }
        "multiple-object",
        "multiple_object" -> {
            val targets =
                (value as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::asRefTarget) }
                    ?: return null
            val endpoint = targets.firstOrNull()?.endpointPath ?: return null
            common(
                EditFieldKind.MULTI_REFERENCE,
                selectedValuesToJson(targets.map { it.id.toString() }),
                endpoint,
                targets.joinToString(", ") { it.display },
            )
        }
        "select",
        "choice" -> common(EditFieldKind.CHOICE, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        "multiselect",
        "multi-select",
        "multiple-choice",
        "multiple_choice" ->
            common(
                EditFieldKind.MULTI_CHOICE,
                if (value is JsonArray) value.toString() else selectedValuesToJson(emptyList()),
            )
        "boolean" ->
            common(EditFieldKind.BOOLEAN, (value as? JsonPrimitive)?.contentOrNull ?: "false")
        "integer" ->
            common(EditFieldKind.INTEGER, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        "decimal",
        "float" -> common(EditFieldKind.NUMBER, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        "longtext",
        "markdown" ->
            common(
                EditFieldKind.LONG_TEXT,
                (value as? JsonPrimitive)?.contentOrNull.orEmpty(),
                markdown = true,
            )
        "text",
        "url",
        "date",
        "datetime" ->
            common(EditFieldKind.STRING, (value as? JsonPrimitive)?.contentOrNull.orEmpty())
        else -> null
    }
}

/** Converts edited text back to the JSON type NetBox expects for that field. */
fun EditFieldKind.toJsonElement(text: String): JsonElement =
    when (this) {
        EditFieldKind.STRING,
        EditFieldKind.LONG_TEXT -> JsonPrimitive(text)
        EditFieldKind.JSON ->
            runCatching {
                    Json.decodeFromString(JsonElement.serializer(), text)
                }
                .getOrElse { JsonPrimitive(text) }
        EditFieldKind.CHOICE -> text.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull
        EditFieldKind.NUMBER -> text.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        EditFieldKind.INTEGER -> text.toIntOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        EditFieldKind.BOOLEAN -> JsonPrimitive(text.toBooleanStrictOrNull() ?: false)
        EditFieldKind.REFERENCE -> text.toIntOrNull()?.let(::JsonPrimitive) ?: JsonNull
        EditFieldKind.MULTI_REFERENCE ->
            runCatching {
                    Json.decodeFromString(JsonArray.serializer(), text)
                        .mapNotNull {
                            (it as? JsonPrimitive)
                                ?.contentOrNull
                                ?.toIntOrNull()
                                ?.let(::JsonPrimitive)
                        }
                        .let(::JsonArray)
                }
                .getOrDefault(JsonArray(emptyList()))
        EditFieldKind.MULTI_CHOICE ->
            runCatching { Json.decodeFromString(JsonArray.serializer(), text) }
                .getOrDefault(JsonArray(emptyList()))
    }

/** Kept as a convenience for callers/tests that only handle primitive field kinds. */
fun EditFieldKind.toJsonPrimitive(text: String): JsonPrimitive =
    (toJsonElement(text) as? JsonPrimitive) ?: JsonPrimitive(text)

fun buildPatchBody(edits: Map<String, Pair<EditFieldKind, String>>): JsonObject {
    val topLevel = linkedMapOf<String, JsonElement>()
    val customFields = linkedMapOf<String, JsonElement>()
    edits.forEach { (key, kindAndValue) ->
        val target = kindAndValue.first.toJsonElement(kindAndValue.second)
        if (key.startsWith("custom_fields."))
            customFields[key.removePrefix("custom_fields.")] = target
        else topLevel[key] = target
    }
    if (customFields.isNotEmpty()) topLevel["custom_fields"] = JsonObject(customFields)
    return JsonObject(topLevel)
}
