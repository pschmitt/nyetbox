package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import dev.pschmitt.nyetbox.data.db.DeviceDao
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectDao
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.db.RecentVisitEntity
import dev.pschmitt.nyetbox.data.schema.AssetTagState
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.data.schema.assetTagState
import dev.pschmitt.nyetbox.data.schema.jsonInt
import dev.pschmitt.nyetbox.data.schema.jsonReference
import dev.pschmitt.nyetbox.data.schema.jsonString
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import timber.log.Timber

/**
 * A single global-search hit - just enough to render a result row and navigate into NBC-6's generic
 * detail screen (`Route.Generic(endpointPath, id)`), not a full cached object.
 */
data class SearchHit(
    val endpointPath: String,
    val id: Int,
    val display: String,
    val secondaryLine: String?,
    val assetTag: String? = null,
    val hasAssetTagField: Boolean = false,
    val status: String? = null,
    /** Human-readable field/value that caused a recursive related-field match. */
    val matchHint: String? = null,
)

data class SearchQueryFilter(
    val key: String,
    val value: String,
    val tokenRange: IntRange,
    val keyRange: IntRange,
    val valueRange: IntRange,
)

data class ParsedGlobalSearchQuery(
    val raw: String,
    val freeText: String,
    val filters: List<SearchQueryFilter>,
) {
    val objectFilters: List<SearchQueryFilter> = filters.filterNot { it.key == "type" }

    val networkQuery: String
        get() = (filters.map { it.value } + freeText).filter(String::isNotBlank).joinToString(" ")
}

private val SPECIAL_SEARCH_FILTER_REGEX =
    Regex("(?<!\\S)([a-z][a-z0-9_.-]*)(?::\\s+|=|:)([^\\s]+)", RegexOption.IGNORE_CASE)

private val SUPPORTED_SEARCH_FILTER_KEYS =
    setOf(
        "address",
        "asset",
        "asset_tag",
        "comments",
        "description",
        "device",
        "device_type",
        "display",
        "id",
        "ip",
        "ip_address",
        "mac",
        "mac_address",
        "manufacturer",
        "model",
        "name",
        "platform",
        "primary_ip",
        "rack",
        "role",
        "serial",
        "site",
        "slug",
        "status",
        "tag",
        "tenant",
        "type",
        "url",
    )

private val SEARCH_FILTER_ALIASES = mapOf("man" to "manufacturer", "tpe" to "type")

fun parseGlobalSearchQuery(queryText: String): ParsedGlobalSearchQuery {
    val filters =
        SPECIAL_SEARCH_FILTER_REGEX.findAll(queryText)
            .mapNotNull { match ->
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[2]
                if (key !in SUPPORTED_SEARCH_FILTER_KEYS && key !in SEARCH_FILTER_ALIASES) {
                    return@mapNotNull null
                }
                SearchQueryFilter(
                    key = SEARCH_FILTER_ALIASES[key] ?: key,
                    value = value,
                    tokenRange = match.range,
                    keyRange = match.groups[1]!!.range,
                    valueRange = match.groups[2]!!.range,
                )
            }
            .toList()
    if (filters.isEmpty()) {
        return ParsedGlobalSearchQuery(queryText, queryText.trim(), emptyList())
    }

    val freeText = buildString {
        var cursor = 0
        filters.forEach { filter ->
            append(queryText.substring(cursor, filter.tokenRange.first))
            cursor = filter.tokenRange.last + 1
        }
        append(queryText.substring(cursor))
    }
        .trim()
        .replace(Regex("\\s+"), " ")
    return ParsedGlobalSearchQuery(queryText, freeText, filters)
}

fun recentVisitsToSearchHits(visits: List<RecentVisitEntity>): List<SearchHit> =
    visits.map { visit ->
        SearchHit(
            endpointPath = visit.endpointPath,
            id = visit.id,
            display = visit.display,
            secondaryLine = visit.secondaryLine,
        )
    }

/**
 * Cross-model search (NBC-13). **Cache-first**, matching every other repository in this app
 * (`AGENTS.md`: "reads come from Room, writes/refreshes come from the API") - this app's whole
 * point is working with no connectivity, so a search that only worked online would be a real
 * regression, not a first-pass shortcut. [observeCached] reads instantly from Room - across every
 * endpoint in `netbox_objects`, plus the typed `devices` table NBC-6 deliberately kept separate -
 * and works fully offline. [refresh] is a best-effort network pass that both broadens what's
 * findable right now *and* upserts hits back into `netbox_objects` via
 * [GenericObjectRepository.cacheSearchResults], so they're cached for next time too - a *refresh*
 * on top of the cache, not a parallel live-only path.
 *
 * NetBox has no global-search REST endpoint to call into for the refresh step - confirmed against
 * the real instance (netbox.brkn.lol, NetBox 4.5): `GET /api/extras/` lists no `search` key, `GET
 * /api/extras/search/` 404s, and the full `/api/schema/` OpenAPI document has zero paths containing
 * "search". What *does* work, also confirmed live: NetBox's per-model list endpoints accept a
 * free-text `?q=<term>` filter - used here as the refresh mechanism.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class GlobalSearchRepository
@Inject
constructor(
    private val api: GenericNetBoxApi,
    private val netBoxObjectDao: NetBoxObjectDao,
    private val deviceDao: DeviceDao,
    private val genericObjectRepository: GenericObjectRepository,
    private val json: Json,
) {

    // Deliberately doesn't hold the source `NetBoxObjectEntity` (and thus its raw `json` string) -
    // only the handful of scalar fields actually needed post-index-build, plus the already-parsed
    // JsonObject. Retaining the full entity here duplicated every cached object's payload three
    // times over (raw JSON string + parsed JsonObject tree + derived search text) for as long as
    // this index lives, which contributed to an OOM on a 256MB-heap device (Pixel 5).
    private data class IndexedGenericObject(
        val endpointPath: String,
        val id: Int,
        val display: String,
        val secondaryLine: String?,
        /** Retained only for interfaces/IP addresses, which need recursive network matching. */
        val objectJson: JsonObject?,
        val searchFields: Map<String, String>,
        val assetTag: AssetTagState,
        val normalizedSearchText: String,
    ) {
        fun matches(candidate: String, query: ParsedGlobalSearchQuery): Boolean {
            val normalizedCandidate = candidate.lowercase()
            val candidateMatches =
                candidate.isBlank() || normalizedSearchText.contains(normalizedCandidate)
            if (!candidateMatches) return false
            val objectFilters = query.objectFilters
            if (
                objectFilters.any { it.key == "manufacturer" } &&
                    endpointPath !in
                        setOf(
                            GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH,
                            GlobalSearchRepository.DEVICES_ENDPOINT_PATH,
                        )
            ) {
                return false
            }
            return searchFields.matchesFilters(objectFilters)
        }
    }

    private data class IndexedDevice(
        val entity: DeviceEntity,
        val searchFields: Map<String, String>,
        val normalizedSearchText: String,
    ) {
        fun matches(candidate: String, query: ParsedGlobalSearchQuery): Boolean {
            val normalizedCandidate = candidate.lowercase()
            if (candidate.isNotBlank() && !normalizedSearchText.contains(normalizedCandidate)) {
                return false
            }
            return searchFields.matchesFilters(query.objectFilters)
        }
    }

    /**
     * Parse generic JSON once when Room changes, instead of decoding every candidate for every
     * keystroke. This is deliberately a small application-lifetime index: it is rebuilt from the
     * cache after sync and never makes a network request.
     */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // A full sync upserts each model's cache in ~200-row pages (see GenericObjectRepository /
    // OfflineSyncRepository.syncAll), and each page upsert invalidates this whole-table Room flow.
    // Without debouncing, this rebuilds the entire (potentially thousands-of-rows) search index
    // from scratch on every single page of every model during that burst - reparsing JSON and
    // recursively walking every nested field each time. `SharingStarted.WhileSubscribed` (rather
    // than `Eagerly`) matters just as much as the debounce: this index is otherwise rebuilt for the
    // *entire app lifetime*, including while the user is nowhere near Search - e.g. mid-sync while
    // just browsing the device list - which OOMed on a real 256MB-heap device (Pixel 5) even with
    // the debounce in place. Scoping it to "only while Search is actually being observed" means a
    // sync burst elsewhere in the app no longer competes for heap with whatever screen is active.
    //
    // Deliberately fanned out per endpoint (`observeAllEndpointPaths()` + one `observeAll(path)`
    // per endpoint, `combine`d) rather than one `observeAllObjects()` `SELECT *` across every
    // endpoint's rows: that single-cursor query used to hand Room a CursorWindow spanning the
    // *entire* table's raw JSON at once, which on the same memory-constrained Pixel 5 could fail to
    // page in fully under memory pressure and throw `IllegalStateException: Couldn't read row N,
    // col 0 from CursorWindow` - a crash, not just the slow-path OOM the rest of this comment
    // covers. Each per-endpoint query is bounded to that endpoint's own (typically page-sized)
    // rows, matching the sync's own per-model page size instead of the whole cache at once. Each
    // endpoint flow maps its raw rows to the compact index before the outer combine sees them -
    // otherwise combine retains every raw entity list while the full index is being built, which
    // recreates the same peak-memory pressure in a different place. The parsed JsonObject is also
    // retained only for interfaces/IP addresses, the two endpoint families used by
    // networkDeviceMatch; all other endpoints keep only their scalar search projection.
    private val cachedGenericObjects =
        netBoxObjectDao
            .observeAllEndpointPaths()
            .distinctUntilChanged()
            .flatMapLatest { endpointPaths ->
                if (endpointPaths.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        endpointPaths.map { endpointPath ->
                            netBoxObjectDao
                                .observeAll(endpointPath)
                                .map { objects ->
                                    objects.mapNotNull(::indexGenericObject)
                                }
                        }
                    ) { perEndpoint -> perEndpoint.asList().flatten() }
                }
            }
            .debounce(300)
            .stateIn(searchScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun indexGenericObject(entity: NetBoxObjectEntity): IndexedGenericObject? {
        val decodedObject = decodeObject(entity.json) ?: return null
        val tag = decodedObject.assetTagState()
        val searchFields = decodedObject.createChoiceSearchFields()
        return IndexedGenericObject(
            endpointPath = entity.endpointPath,
            id = entity.id,
            display = entity.display,
            secondaryLine = entity.secondaryLine,
            objectJson =
                decodedObject.takeIf { searchIndexNeedsObjectJson(entity.endpointPath) },
            searchFields = searchFields,
            assetTag = tag,
            normalizedSearchText = buildString {
                    append(entity.display)
                    append('\n')
                    append(entity.secondaryLine.orEmpty())
                    searchFields.values.forEach { value ->
                        append('\n')
                        append(value)
                    }
                }
                .lowercase(),
        )
    }

    private val cachedDevices =
        deviceDao
            .observeAll()
            .debounce(300)
            .map { devices -> devices.map(::indexDevice) }
            .stateIn(searchScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Instant, offline-capable read - the primary result source, not scoped to
     * [BASELINE_ENDPOINT_PATHS]: anything ever cached under any endpoint is findable offline.
     */
    fun observeCached(
        queryText: String,
        endpointPath: String? = null,
        limitPerSource: Int = 50,
    ): Flow<List<SearchHit>> =
        parseGlobalSearchQuery(queryText)
            .let { parsedQuery ->
                val cacheCandidateQuery =
                    parsedQuery.freeText.ifBlank {
                        parsedQuery.objectFilters.firstOrNull()?.value.orEmpty()
                    }
                val typeFilters = parsedQuery.filters.filter { it.key == "type" }
                fun endpointMatchesType(endpointPath: String): Boolean = typeFilters.all { filter ->
                    searchTypeMatches(endpointPath, filter.value)
                }
                cachedGenericObjects.let { genericIndex ->
                    val genericHits = genericIndex.map { rows ->
                        rows
                            .asSequence()
                            .filter { indexed ->
                                (endpointPath == null || indexed.endpointPath == endpointPath) &&
                                    endpointMatchesType(indexed.endpointPath) &&
                                    indexed.matches(cacheCandidateQuery, parsedQuery)
                            }
                            .take(limitPerSource)
                            .map { indexed ->
                                indexed.toSearchHit(
                                    queryText = queryText,
                                    parsedQuery = parsedQuery,
                                )
                            }
                            .toList()
                    }
                    val networkFilterKeys =
                        setOf("ip", "ip_address", "primary_ip", "mac", "mac_address")
                    val networkSearchEnabled =
                        parsedQuery.objectFilters.isEmpty() ||
                            parsedQuery.objectFilters.any { it.key in networkFilterKeys }
                    val cachedInterfaces =
                        if (networkSearchEnabled) {
                            cachedGenericObjects.map { rows ->
                                rows.filter { it.endpointPath == INTERFACES_ENDPOINT_PATH }
                            }
                        } else {
                            flowOf(emptyList())
                        }
                    val directDeviceHits =
                        if (endpointPath == null || endpointPath == DEVICES_ENDPOINT_PATH) {
                            cachedDevices.map { rows ->
                                rows
                                    .filter {
                                        endpointMatchesType(DEVICES_ENDPOINT_PATH) &&
                                            it.matches(cacheCandidateQuery, parsedQuery)
                                    }
                                    .take(limitPerSource)
                                    .map { indexed ->
                                        indexed.entity.toSearchHit(
                                            queryText = queryText,
                                            parsedQuery = parsedQuery,
                                        )
                                    }
                            }
                        } else {
                            flowOf(emptyList())
                        }
                    val matchingDeviceTypes = cachedGenericObjects.map { rows ->
                        if (
                            (endpointPath == null || endpointPath == DEVICES_ENDPOINT_PATH) &&
                                endpointMatchesType(DEVICE_TYPES_ENDPOINT_PATH)
                        ) {
                            rows
                                .filter { indexed ->
                                    indexed.endpointPath == DEVICE_TYPES_ENDPOINT_PATH &&
                                        indexed.matches(cacheCandidateQuery, parsedQuery)
                                }
                                .associate { it.id to it.display }
                        } else {
                            emptyMap()
                        }
                    }
                    val devicesOfMatchingTypes =
                        if (
                            (endpointPath == null || endpointPath == DEVICES_ENDPOINT_PATH) &&
                                endpointMatchesType(DEVICES_ENDPOINT_PATH)
                        ) {
                            combine(matchingDeviceTypes, cachedDevices) { typeLabels, devices ->
                                devices
                                    .filter { it.entity.deviceTypeId in typeLabels.keys }
                                    .map { indexed ->
                                        indexed.entity.toSearchHit(
                                            secondaryLine =
                                                "Device type: " +
                                                    (typeLabels[indexed.entity.deviceTypeId]
                                                        ?: "matching type"),
                                            matchHint =
                                                "Device type: " +
                                                    (typeLabels[indexed.entity.deviceTypeId]
                                                        ?: "matching type"),
                                        )
                                    }
                            }
                        } else {
                            flowOf(emptyList())
                        }
                    val devicesMatchingNetworkDetails =
                        if (
                            networkSearchEnabled &&
                                (endpointPath == null || endpointPath == DEVICES_ENDPOINT_PATH) &&
                                endpointMatchesType(DEVICES_ENDPOINT_PATH)
                        ) {
                            combine(cachedGenericObjects, cachedInterfaces, cachedDevices) {
                                matchingObjects,
                                interfaces,
                                devices ->
                                val interfaceDeviceIds =
                                    interfaces
                                        .mapNotNull { indexed ->
                                            indexed.objectJson
                                                ?.let {
                                                    networkDeviceMatch(
                                                        INTERFACES_ENDPOINT_PATH,
                                                        it,
                                                        emptyMap(),
                                                    )
                                                }
                                                ?.let { indexed.id to it.deviceId }
                                        }
                                        .toMap()
                                val matchingDevices =
                                    matchingObjects
                                        .filter { indexed ->
                                            indexed.matches(cacheCandidateQuery, parsedQuery)
                                        }
                                        .mapNotNull { indexed ->
                                            indexed.objectJson?.let {
                                                networkDeviceMatch(
                                                    indexed.endpointPath,
                                                    it,
                                                    interfaceDeviceIds,
                                                )
                                            }
                                        }
                                        .associateBy { it.deviceId }
                                devices
                                    .filter { it.entity.id in matchingDevices.keys }
                                    .map { indexed ->
                                        val source = matchingDevices[indexed.entity.id]?.source
                                        indexed.entity.toSearchHit(
                                            secondaryLine = source,
                                            matchHint = source,
                                        )
                                    }
                            }
                        } else {
                            flowOf(emptyList())
                        }
                    combine(
                        genericHits,
                        directDeviceHits,
                        devicesOfMatchingTypes,
                        devicesMatchingNetworkDetails,
                    ) { generic, direct, recursive, network ->
                        generic + direct + recursive + network
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    /**
     * Best-effort live refresh: fans [endpointPaths] out in parallel via `?q=`, upserting
     * successful hits into `netbox_objects` so [observeCached] reflects them immediately and
     * they're offline-findable from now on. Devices are deliberately skipped here - `DeviceDao`'s
     * cache already gets a full periodic sync via `DeviceRepository`/`SyncWorker`, so it's already
     * comprehensive without a redundant `?q=` round trip. A single model failing (unreachable,
     * doesn't support `q`, offline entirely, ...) is logged and skipped rather than failing the
     * whole refresh, mirroring [DirectoryRepository.refresh]'s per-app `runCatching` - the caller
     * already has [observeCached]'s answer regardless of whether this succeeds.
     */
    suspend fun refresh(queryText: String, endpointPaths: List<String>, limitPerModel: Int = 15) {
        val networkQuery = parseGlobalSearchQuery(queryText).networkQuery
        if (networkQuery.isBlank()) return
        coroutineScope {
            endpointPaths
                .filter { it != DEVICES_ENDPOINT_PATH }
                .map { endpointPath ->
                    async { refreshOne(endpointPath, networkQuery, limitPerModel) }
                }
                .awaitAll()
        }
    }

    private suspend fun refreshOne(endpointPath: String, queryText: String, limitPerModel: Int) {
        try {
            val results =
                api.listObjects(
                        endpointPath,
                        mapOf("q" to queryText, "limit" to limitPerModel.toString()),
                    )
                    .results
            genericObjectRepository.cacheSearchResults(endpointPath, results)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Global search refresh failed for %s", endpointPath)
        }
    }

    private fun IndexedGenericObject.toSearchHit(
        queryText: String? = null,
        parsedQuery: ParsedGlobalSearchQuery = parseGlobalSearchQuery(queryText.orEmpty()),
    ): SearchHit =
        SearchHit(
            endpointPath = endpointPath,
            id = id,
            display = display,
            secondaryLine = secondaryLine,
            assetTag = assetTag.value,
            hasAssetTagField = assetTag.hasField,
            status =
                searchFields.entries
                    .firstOrNull { (key, _) -> searchFieldKeyMatches("status", key) }
                    ?.value,
            matchHint =
                structuredSearchHint(searchFields, parsedQuery)
                    ?: queryText
                        ?.takeIf { it.isNotBlank() }
                        ?.let { query ->
                            choiceSearchHint(display, id.toString(), searchFields, query)
                        },
        )

    private fun DeviceEntity.toSearchHit(
        secondaryLine: String? = siteName ?: statusLabel,
        matchHint: String? = null,
        queryText: String? = null,
        parsedQuery: ParsedGlobalSearchQuery = parseGlobalSearchQuery(queryText.orEmpty()),
    ) =
        SearchHit(
            endpointPath = DEVICES_ENDPOINT_PATH,
            id = id,
            display = name,
            secondaryLine = secondaryLine,
            assetTag = assetTag,
            hasAssetTagField = true,
            status = statusLabel,
            matchHint =
                matchHint
                    ?: structuredSearchHint(
                        mapOf(
                                "name" to name,
                                "status" to statusLabel,
                                "site" to siteName,
                                "rack" to rackName,
                                "role" to roleName,
                                "manufacturer" to manufacturerName,
                                "device_type" to deviceTypeModel,
                                "model" to deviceTypeModel,
                                "serial" to serial,
                                "asset_tag" to assetTag,
                                "primary_ip" to primaryIp,
                                "comments" to comments,
                            )
                            .filterValues { !it.isNullOrBlank() }
                            .mapValues { it.value!! },
                        parsedQuery,
                    )
                    ?: queryText?.let { query ->
                        choiceSearchHint(
                            name,
                            id.toString(),
                            mapOf(
                                    "status" to statusLabel,
                                    "site" to siteName,
                                    "rack" to rackName,
                                    "role" to roleName,
                                    "manufacturer" to manufacturerName,
                                    "device_type" to deviceTypeModel,
                                    "serial" to serial,
                                    "asset_tag" to assetTag,
                                    "primary_ip" to primaryIp,
                                )
                                .filterValues { !it.isNullOrBlank() }
                                .mapValues { it.value!! },
                            query,
                        )
                    },
        )

    private fun indexDevice(device: DeviceEntity): IndexedDevice {
        val searchFields = device.createSearchFields()
        return IndexedDevice(
            entity = device,
            searchFields = searchFields,
            normalizedSearchText = searchFields.values.joinToString("\n").lowercase(),
        )
    }

    private fun structuredSearchHint(
        fields: Map<String, String>,
        query: ParsedGlobalSearchQuery,
    ): String? =
        query.filters
            .filterNot { it.key == "type" }
            .firstNotNullOfOrNull { filter ->
                fields.entries
                    .firstOrNull { (key, value) ->
                        searchFieldKeyMatches(filter.key, key) &&
                            value.contains(filter.value, ignoreCase = true)
                    }
                    ?.let { (_, value) ->
                        "${formatSearchFieldLabel(filter.key)}: ${compactSearchMatchValue(value)}"
                    }
            }
            ?.takeIf(String::isNotBlank)

    private fun decodeObject(raw: String): JsonObject? = runCatching {
        json.decodeFromString(JsonObject.serializer(), raw)
    }
        .getOrNull()

    companion object {
        const val DEVICES_ENDPOINT_PATH = NetBoxRef.DEVICES_ENDPOINT_PATH
        const val DEVICE_TYPES_ENDPOINT_PATH = NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH
        const val INTERFACES_ENDPOINT_PATH = NetBoxRef.INTERFACES_ENDPOINT_PATH
        const val IP_ADDRESSES_ENDPOINT_PATH = NetBoxRef.IP_ADDRESSES_ENDPOINT_PATH

        // Baseline model set for the network refresh + result labeling - GlobalSearchViewModel
        // unions this with the user's pinned model paths so anything explicitly starred in the
        // sidebar is searchable too, not just this baseline. Kept short so one search doesn't fan
        // out to dozens of parallel requests; the *cached* read side has no such limit.
        val BASELINE_ENDPOINT_PATHS =
            listOf(
                DEVICES_ENDPOINT_PATH,
                DEVICE_TYPES_ENDPOINT_PATH,
                NetBoxRef.SITES_ENDPOINT_PATH,
                NetBoxRef.RACKS_ENDPOINT_PATH,
                NetBoxRef.IP_ADDRESSES_ENDPOINT_PATH,
                "api/ipam/prefixes/",
                "api/circuits/circuits/",
                "api/virtualization/virtual-machines/",
                "api/tenancy/tenants/",
            )
    }
}

/**
 * Flattened field/value pairs (e.g. `"name" to "Router 1"`, `"site" to "HQ"`) that NBC-13's Global
 * Search matches `key:value` filters against. Promoted out of [GlobalSearchRepository] (it never
 * touched instance state) so the per-list-screen search bars (NBC-372's `DeviceRepository`) can
 * reuse the exact same field mapping instead of duplicating it.
 */
internal fun DeviceEntity.createSearchFields(): Map<String, String> =
    mapOf(
            "name" to name,
            "status" to statusLabel,
            "site" to siteName,
            "rack" to rackName,
            "role" to roleName,
            "manufacturer" to manufacturerName,
            "device_type" to deviceTypeModel,
            "model" to deviceTypeModel,
            "serial" to serial,
            "asset_tag" to assetTag,
            "primary_ip" to primaryIp,
            "comments" to comments,
        )
        .filterValues { !it.isNullOrBlank() }
        .mapValues { it.value!! }

/**
 * True when every filter in [filters] matches some field in this field/value
 * map - [searchFieldKeyMatches] resolves aliasing between a filter's key (e.g. `ip`) and the actual
 * field key (e.g. `primary_ip`). Shared by [GlobalSearchRepository]'s in-memory indexes and
 * NBC-372's per-list-screen `key:value` filtering (`DeviceRepository`/`GenericObjectRepository`) so
 * there is exactly one place this matching rule lives.
 */
internal fun Map<String, String>.matchesFilters(filters: List<SearchQueryFilter>): Boolean =
    filters.all { filter ->
        entries.any { (key, value) ->
            searchFieldKeyMatches(filter.key, key) &&
                value.contains(filter.value, ignoreCase = true)
        }
    }

internal fun searchFieldKeyMatches(filterKey: String, fieldKey: String): Boolean {
    val filter = normalizeSearchFieldKey(filterKey)
    val actual = normalizeSearchFieldKey(fieldKey.substringAfterLast('.'))
    return when (filter) {
        "ip" ->
            actual == "ip" ||
                actual == "address" ||
                actual.endsWith("ip") ||
                actual.contains("ipaddress")
        "mac" -> actual == "mac" || actual.contains("mac")
        "asset" -> actual == "asset" || actual == "assettag"
        else -> actual == filter || actual.endsWith(filter)
    }
}

internal fun formatSearchFieldLabel(key: String): String {
    val acronyms =
        mapOf(
            "api" to "API",
            "dns" to "DNS",
            "id" to "ID",
            "ip" to "IP",
            "mac" to "MAC",
            "url" to "URL",
            "usb" to "USB",
        )
    return key.split('_', '-', '.').filter(String::isNotBlank).joinToString(" ") { segment ->
        acronyms[segment.lowercase()] ?: segment.replaceFirstChar { it.titlecase() }
    }
}

/** Matches a short collection name such as `dev`, `dt`, or `ip` to a cached endpoint. */
internal fun searchTypeMatches(endpointPath: String, requestedType: String): Boolean {
    val normalizedType = requestedType.lowercase().filter(Char::isLetterOrDigit)
    if (normalizedType.isBlank()) return false
    val endpointModel = endpointPath.trim('/').substringAfterLast('/').lowercase()
    val canonicalModel =
        when (normalizedType) {
            "dev",
            "device",
            "devices" -> "devices"
            "dt",
            "devicetype",
            "devicetypes" -> "device-types"
            "ip",
            "address",
            "addresses",
            "ipaddress",
            "ipaddresses" -> "ip-addresses"
            "vm",
            "virtualmachine",
            "virtualmachines" -> "virtual-machines"
            else -> normalizedType
        }
    val normalizedModel = endpointModel.filter(Char::isLetterOrDigit)
    val singularModel = normalizedModel.removeSuffix("s")
    return normalizedModel == canonicalModel.filter(Char::isLetterOrDigit) ||
        singularModel == canonicalModel.filter(Char::isLetterOrDigit) ||
        normalizedModel.startsWith(canonicalModel.filter(Char::isLetterOrDigit))
}

private fun normalizeSearchFieldKey(key: String): String =
    key.lowercase().replace(Regex("[^a-z0-9]"), "")

internal fun JsonObject.assetTag(): String? = assetTagState().value

data class NetworkDeviceMatch(val deviceId: Int, val source: String)

/** Only these cached endpoints need raw JSON after indexing for recursive device-network matches. */
internal fun searchIndexNeedsObjectJson(endpointPath: String): Boolean =
    endpointPath == GlobalSearchRepository.INTERFACES_ENDPOINT_PATH ||
        endpointPath == GlobalSearchRepository.IP_ADDRESSES_ENDPOINT_PATH

internal fun networkDeviceMatch(
    endpointPath: String,
    objectJson: JsonObject,
    interfaceDeviceIds: Map<Int, Int>,
): NetworkDeviceMatch? {
    if (endpointPath == GlobalSearchRepository.INTERFACES_ENDPOINT_PATH) {
        val deviceId = objectJson.jsonReference("device")?.id
        if (deviceId != null) {
            val macAddress = objectJson.jsonString("mac_address") ?: objectJson.jsonString("mac")
            if (!macAddress.isNullOrBlank()) {
                return NetworkDeviceMatch(deviceId, "MAC $macAddress")
            }
            val display =
                objectJson.jsonString("display") ?: objectJson.jsonString("name") ?: "interface"
            return NetworkDeviceMatch(deviceId, "Interface $display")
        }
    }
    if (endpointPath == GlobalSearchRepository.IP_ADDRESSES_ENDPOINT_PATH) {
        val assigned = objectJson["assigned_object"] as? JsonObject
        val directDeviceId = assigned?.jsonReference("device")?.id
        val assignedType = objectJson.jsonString("assigned_object_type")
        val assignedId = objectJson.jsonInt("assigned_object_id")
        val deviceId =
            directDeviceId
                ?: assignedId
                    ?.takeIf { assignedType == "dcim.interface" }
                    ?.let(interfaceDeviceIds::get)
        if (deviceId != null) {
            val display =
                objectJson.jsonString("address") ?: objectJson.jsonString("display") ?: "IP address"
            return NetworkDeviceMatch(deviceId, "IP $display")
        }
    }
    return null
}

/** Ranks cached hits so exact and prefix matches are useful before alphabetical tie-breaking. */
fun rankSearchHits(queryText: String, hits: List<SearchHit>): List<SearchHit> {
    val query = queryText.trim().lowercase()
    if (query.isBlank()) return hits.distinctBy { it.endpointPath to it.id }
    return hits
        .distinctBy { it.endpointPath to it.id }
        .sortedWith(
            compareByDescending<SearchHit> { hit -> searchRelevance(query, hit) }
                .thenByDescending { hit -> searchObjectTypePriority(hit) }
                .thenBy { it.display.lowercase() }
                .thenBy { it.endpointPath }
                .thenBy { it.id }
        )
}

/** Prefer the two most frequently used inventory objects when match quality is otherwise equal. */
private fun searchObjectTypePriority(hit: SearchHit): Int =
    when (hit.endpointPath) {
        GlobalSearchRepository.DEVICES_ENDPOINT_PATH -> 2
        GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH -> 1
        else -> 0
    }

/**
 * Name matches always outrank everything else, however weak the match - even a "contains" hit on
 * the item's own name beats an exact asset-tag match. Asset tag is the next strongest signal (it's
 * a deliberate unique identifier, unlike the free-text secondary line or a matched custom field),
 * then secondary line, then everything else.
 */
private fun searchRelevance(query: String, hit: SearchHit): Int {
    val display = hit.display.trim().lowercase()
    val assetTag = hit.assetTag.orEmpty().trim().lowercase()
    val secondary = hit.secondaryLine.orEmpty().trim().lowercase()
    return when {
        display == query -> 1000
        display.startsWith(query) -> 900
        display.contains(query) -> 800
        assetTag == query -> 700
        assetTag.startsWith(query) -> 600
        assetTag.contains(query) -> 500
        secondary == query -> 400
        secondary.startsWith(query) -> 350
        secondary.contains(query) -> 300
        hit.matchHint?.lowercase()?.contains(query) == true -> 200
        else -> 0
    }
}

/** Returns cached directory model types whose label/key can complete the first query word. */
fun typeFilterSuggestions(
    queryText: String,
    models: Collection<NetBoxModelEntity>,
    limit: Int = 6,
): List<NetBoxModelEntity> {
    val prefix = queryText.trimStart().substringBefore(' ').lowercase()
    if (prefix.length < 2) return emptyList()
    return models
        .filter {
            it.modelLabel.lowercase().startsWith(prefix) ||
                it.modelKey.lowercase().startsWith(prefix)
        }
        .distinctBy { it.endpointPath }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.modelLabel })
        .take(limit)
}

fun queryRemainderAfterTypeSelection(queryText: String): String =
    queryText.trimStart().substringAfter(' ', missingDelimiterValue = "").trimStart()
