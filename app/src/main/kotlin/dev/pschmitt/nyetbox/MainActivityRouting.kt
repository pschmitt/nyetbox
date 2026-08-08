package dev.pschmitt.nyetbox

import android.content.Intent
import android.net.Uri
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.scanner.NetBoxTarget
import dev.pschmitt.nyetbox.scanner.NetBoxUrlParser
import dev.pschmitt.nyetbox.ui.navigation.Route
import dev.pschmitt.nyetbox.ui.settings.SettingsCategory
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SharedMediaPayload(
    val uri: String,
    val mimeType: String?,
    val filename: String? = null,
)

/** Extracts a NetBox deep-link/setup payload from the intent shapes Android can deliver. */
internal fun extractNetBoxTarget(intent: Intent?): NetBoxTarget? {
    return extractNetBoxTargetText(
        action = intent?.action,
        dataString = intent?.dataString,
        sharedText = intent?.getStringExtra(Intent.EXTRA_TEXT),
    )
}

internal fun extractNetBoxTargetText(
    action: String?,
    dataString: String?,
    sharedText: String?,
): NetBoxTarget? {
    val text =
        when (action) {
            Intent.ACTION_VIEW -> dataString
            Intent.ACTION_SEND -> sharedText
            else -> null
        }
    return text?.let(NetBoxUrlParser::parse)
}

internal fun extractSharedMedia(intent: Intent?): SharedMediaPayload? {
    if (intent?.action != Intent.ACTION_SEND) return null
    @Suppress("DEPRECATION")
    val streamUri: Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM) ?: intent.clipData?.getItemAt(0)?.uri
    return sharedMediaPayload(
        action = intent.action,
        streamUri = streamUri?.toString(),
        mimeType = intent.type,
    )
}

internal fun sharedMediaPayload(
    action: String?,
    streamUri: String?,
    mimeType: String?,
): SharedMediaPayload? =
    if (action == Intent.ACTION_SEND) {
        streamUri?.takeIf(String::isNotBlank)?.let { SharedMediaPayload(it, mimeType) }
    } else {
        null
    }

/** Maps a parsed target to a route when the target needs no repository lookup. */
internal fun routeForTarget(target: NetBoxTarget): Route? =
    when (target) {
        is NetBoxTarget.Setup -> Route.Onboarding
        is NetBoxTarget.Device -> Route.DeviceDetail(target.id)
        is NetBoxTarget.Object -> Route.Generic(target.endpointPath, target.id)
        is NetBoxTarget.DeviceAssetTag -> null
    }

const val ACTION_GESTURE = "dev.pschmitt.nyetbox.action.GESTURE"
const val EXTRA_GESTURE_ACTION = "dev.pschmitt.nyetbox.extra.GESTURE_ACTION"
const val EXTRA_GESTURE_TARGET = "dev.pschmitt.nyetbox.extra.GESTURE_TARGET"

/**
 * Encodes a [GestureAction] (+ optional [GestureTarget]) as intent extras so launcher shortcuts
 * and widget taps - both of which must hand Android a plain [Intent] rather than call into
 * Compose directly - resolve through the exact same [routeForGesture] as an in-app gesture
 * shortcut, instead of each inventing its own routing.
 */
fun Intent.putGestureExtras(action: GestureAction, target: GestureTarget?): Intent = apply {
    this.action = ACTION_GESTURE
    putExtra(EXTRA_GESTURE_ACTION, action.storageKey)
    target?.let { putExtra(EXTRA_GESTURE_TARGET, Json.encodeToString(it)) }
}

internal fun extractGestureAction(intent: Intent?): GestureAction? =
    GestureAction.fromStorageOrNull(intent?.getStringExtra(EXTRA_GESTURE_ACTION))

internal fun extractGestureTarget(intent: Intent?): GestureTarget? =
    intent?.getStringExtra(EXTRA_GESTURE_TARGET)?.let {
        runCatching { Json.decodeFromString<GestureTarget>(it) }.getOrNull()
    }

/** Pure route selection for configured gesture actions; side effects remain in MainActivity. */
internal fun routeForGesture(action: GestureAction, target: GestureTarget?): Route? =
    when (action) {
        GestureAction.Dashboard -> Route.Dashboard
        GestureAction.GlobalSearch -> Route.GlobalSearch
        GestureAction.Scanner -> Route.Scanner()
        GestureAction.Settings -> Route.Settings
        GestureAction.SwitchServer ->
            Route.SettingsCategory(SettingsCategory.Connection, openServerManager = true)
        GestureAction.Add -> Route.Add
        GestureAction.AddSpecific ->
            target?.let { Route.GenericCreate(it.endpointPath, it.label) } ?: Route.Add
        GestureAction.DeviceList -> Route.DeviceList
        GestureAction.ListSpecific -> target?.let { Route.GenericList(it.endpointPath, it.label) }
        GestureAction.DetailSpecific ->
            target?.id?.let { id ->
                if (target.endpointPath == NetBoxRef.DEVICES_ENDPOINT_PATH && id > 0) {
                    Route.DeviceDetail(id)
                } else {
                    Route.Generic(target.endpointPath, id, target.label)
                }
            }
        GestureAction.Off,
        GestureAction.Sync,
        GestureAction.OfflineOn,
        GestureAction.OfflineOff -> null
    }

/**
 * Whether [current] is showing the same destination as a nav-bar slot resolved to [target] - used
 * to decide which bottom-bar/rail item highlights as selected. Routes with fields that only ever
 * differ based on *how* you arrived (a breadcrumb, a focused field, an edit-mode flag, an inherited
 * list filter) are compared on their identifying fields only, since a nav-bar-resolved target
 * always has those extras at their defaults.
 */
internal fun matchesCurrentRoute(current: Route?, target: Route): Boolean =
    when (target) {
        is Route.Generic ->
            current is Route.Generic &&
                current.endpointPath == target.endpointPath &&
                current.id == target.id
        is Route.GenericList ->
            current is Route.GenericList && current.endpointPath == target.endpointPath
        else -> current == target
    }
