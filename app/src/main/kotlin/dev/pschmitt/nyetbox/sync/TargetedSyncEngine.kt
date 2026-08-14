package dev.pschmitt.nyetbox.sync

import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.DeviceTypeRepository
import dev.pschmitt.nyetbox.data.repository.GenericObjectRepository
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import timber.log.Timber

/** One (endpoint, id) node in a [TargetedSyncEngine] traversal. */
internal data class SyncNode(val endpointPath: String, val id: Int, val expandReverse: Boolean)

/**
 * A parent -> children relation not discoverable from an object's own JSON (unlike a forward
 * reference) - requires a real, live-verified NetBox API filter param. See [TargetedSyncEngine]'s
 * doc comment for why guessing this is unsafe.
 */
internal data class ReverseRelation(
    val childEndpointPath: String,
    val filterQueryParam: String,
    val relationKey: String,
)

/**
 * Declarative, live-verified (against a real NetBox instance) parent -> children relations.
 * Deliberately small: NetBox silently ignores an unrecognized filter param and returns the
 * endpoint's full unfiltered contents instead of erroring (confirmed live with
 * `?bogus_filter_xyz=1` on `/api/dcim/devices/`), so a wrong guess here would silently turn a
 * targeted sync into an accidental full sync of that endpoint with no error signal. Every entry's
 * filter param was checked live to actually narrow the result count before being added here.
 */
internal val REVERSE_RELATIONS: Map<String, List<ReverseRelation>> =
    mapOf(
        NetBoxRef.RACKS_ENDPOINT_PATH to
            listOf(ReverseRelation(NetBoxRef.DEVICES_ENDPOINT_PATH, "rack_id", "rack")),
        NetBoxRef.SITES_ENDPOINT_PATH to
            listOf(
                ReverseRelation(NetBoxRef.RACKS_ENDPOINT_PATH, "site_id", "site"),
                ReverseRelation(NetBoxRef.DEVICES_ENDPOINT_PATH, "site_id", "site"),
            ),
        NetBoxRef.LOCATIONS_ENDPOINT_PATH to
            listOf(
                ReverseRelation(NetBoxRef.RACKS_ENDPOINT_PATH, "location_id", "location"),
                ReverseRelation(NetBoxRef.DEVICES_ENDPOINT_PATH, "location_id", "location"),
            ),
        NetBoxRef.DEVICES_ENDPOINT_PATH to
            listOf(
                ReverseRelation(NetBoxRef.INTERFACES_ENDPOINT_PATH, "device_id", "device"),
                ReverseRelation("api/dcim/front-ports/", "device_id", "device"),
                ReverseRelation("api/dcim/rear-ports/", "device_id", "device"),
                ReverseRelation("api/dcim/power-ports/", "device_id", "device"),
                ReverseRelation("api/dcim/console-ports/", "device_id", "device"),
                ReverseRelation("api/dcim/power-outlets/", "device_id", "device"),
                ReverseRelation("api/dcim/module-bays/", "device_id", "device"),
                ReverseRelation(NetBoxRef.IP_ADDRESSES_ENDPOINT_PATH, "device_id", "device"),
            ),
    )

/**
 * Every nested `{id, url, ...}` object anywhere in [json] is a forward reference to another NetBox
 * object - NetBox's DRF nested serializers always shape a related object this way (confirmed live
 * for site/rack/location/cable/termination `device`). Resolving these needs no per-endpoint code,
 * unlike a [ReverseRelation].
 */
internal fun forwardReferences(json: JsonObject): List<Pair<String, Int>> {
    val found = mutableListOf<Pair<String, Int>>()

    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val id = (element["id"] as? JsonPrimitive)?.intOrNull
                val url = (element["url"] as? JsonPrimitive)?.contentOrNull
                if (id != null && url != null) {
                    NetBoxRef.endpointFromDetailUrl(url)?.let { found.add(it to id) }
                }
                element.values.forEach(::visit)
            }
            is JsonArray -> element.forEach(::visit)
            else -> Unit
        }
    }

    json.values.forEach(::visit)
    return found
}

data class TargetedSyncResult(val rootSucceeded: Boolean, val otherFailureCount: Int)

/**
 * Recursively refreshes a NetBox object and everything it's meaningfully linked to, for
 * pull-to-refresh: a rack's devices, their device types/interfaces/ports/IP addresses, the cables
 * on those interfaces, and the device on the other end of each cable - uniformly, for any root
 * object type, with no per-screen fan-out list.
 *
 * Safety property: a node discovered via a *reverse* relation ([REVERSE_RELATIONS]) from an
 * already-expandable node stays expandable, continuing the containment chain (rack -> devices ->
 * interfaces). A node discovered via a *forward* reference ([forwardReferences] - any nested `{id,
 * url}` in an object's own JSON) is refreshed for its own sake but never itself reverse-expanded.
 * This is what bounds a cable's far-end device to being refreshed once (with its own device
 * type/site/rack context) without re-exploding into that device's other interfaces or its site's
 * other devices - and, symmetrically, what stops a device's own `site`/`rack` pointers from
 * re-triggering that site/rack's full device list. There is deliberately no numeric node-count cap;
 * this forward/reverse rule is what bounds the graph instead.
 */
@Singleton
class TargetedSyncEngine
@Inject
constructor(
    private val genericObjectRepository: GenericObjectRepository,
    private val deviceRepository: DeviceRepository,
    private val deviceTypeRepository: DeviceTypeRepository,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) {

    suspend fun sync(rootEndpointPath: String, rootId: Int): TargetedSyncResult = coroutineScope {
        val visited = mutableSetOf(rootEndpointPath to rootId)
        val semaphore = Semaphore(settingsRepository.syncConcurrency.value.coerceAtLeast(1))

        var rootSucceeded = false
        var otherFailureCount = 0
        var frontier = listOf(SyncNode(rootEndpointPath, rootId, expandReverse = true))
        var isRootLevel = true

        while (frontier.isNotEmpty()) {
            val outcomes =
                frontier
                    .map { node -> async { semaphore.withPermit { processNode(node) } } }
                    .awaitAll()
            if (isRootLevel) {
                rootSucceeded = outcomes.first().succeeded
            } else {
                otherFailureCount += outcomes.count { !it.succeeded }
            }
            // Sequential on purpose: this is the only writer of `visited`, so no lock is needed -
            // the concurrent work above only refreshes objects, it never touches this set.
            frontier =
                outcomes.flatMap { it.discovered }.filter { visited.add(it.endpointPath to it.id) }
            isRootLevel = false
        }
        TargetedSyncResult(rootSucceeded, otherFailureCount)
    }

    private data class NodeOutcome(val succeeded: Boolean, val discovered: List<SyncNode>)

    private suspend fun processNode(node: SyncNode): NodeOutcome {
        val objectJson =
            refreshAndDecode(node.endpointPath, node.id).getOrNull()
                ?: return NodeOutcome(succeeded = false, discovered = emptyList())

        val discovered =
            forwardReferences(objectJson)
                .map { (endpointPath, id) -> SyncNode(endpointPath, id, expandReverse = false) }
                .toMutableList()

        if (node.expandReverse) {
            REVERSE_RELATIONS[node.endpointPath]?.forEach { relation ->
                genericObjectRepository
                    .syncAllAndFetchIds(
                        relation.childEndpointPath,
                        relation.filterQueryParam,
                        node.id,
                        relation.relationKey,
                    )
                    .onSuccess { ids ->
                        ids.forEach { id ->
                            discovered.add(
                                SyncNode(relation.childEndpointPath, id, expandReverse = true)
                            )
                        }
                    }
                    .onFailure {
                        Timber.w(
                            it,
                            "Couldn't sync %s (parent %s#%d) during targeted sync",
                            relation.childEndpointPath,
                            node.endpointPath,
                            node.id,
                        )
                    }
            }
        }
        return NodeOutcome(succeeded = true, discovered = discovered)
    }

    /**
     * Refreshes [endpointPath]#[id] and returns its JSON for [forwardReferences] to walk.
     * [NetBoxRef.DEVICES_ENDPOINT_PATH]/[NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH] are the only
     * endpoints this app caches twice - a typed table the device/device-type screens themselves
     * read, plus the generic cache everything else (e.g. rack elevation previews) reads - so both
     * get refreshed; the typed refresh's own failure is logged but doesn't fail the node, since the
     * generic refresh (which also supplies the JSON to walk) is the source of truth here.
     */
    private suspend fun refreshAndDecode(endpointPath: String, id: Int): Result<JsonObject> {
        when (endpointPath) {
            NetBoxRef.DEVICES_ENDPOINT_PATH ->
                deviceRepository.refreshDevice(id).onFailure {
                    Timber.w(it, "Couldn't refresh typed device cache for device %d", id)
                }
            NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH ->
                deviceTypeRepository.refresh(id).onFailure {
                    Timber.w(it, "Couldn't refresh typed device type cache for device type %d", id)
                }
        }
        return genericObjectRepository.refreshObject(endpointPath, id).mapCatching { entity ->
            json.decodeFromString(JsonObject.serializer(), entity.json)
        }
    }
}
