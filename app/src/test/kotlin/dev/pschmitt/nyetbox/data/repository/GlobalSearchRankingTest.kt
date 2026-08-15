package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.RecentVisitEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalSearchRankingTest {
    @Test
    fun `recent visits become navigable search hits in visit order`() {
        val hits =
            recentVisitsToSearchHits(
                listOf(
                    RecentVisitEntity("api/dcim/devices/", 1, "Router", "active", 2),
                    RecentVisitEntity("api/dcim/sites/", 4, "HQ", null, 1),
                )
            )

        assertEquals(listOf("Router", "HQ"), hits.map { it.display })
        assertEquals("api/dcim/sites/", hits[1].endpointPath)
    }

    @Test
    fun `ranks exact and prefix display matches before substring and secondary matches`() {
        val hits =
            listOf(
                SearchHit("api/dcim/devices/", 1, "Router Office", "active"),
                SearchHit("api/dcim/devices/", 2, "Router", "active"),
                SearchHit("api/dcim/devices/", 3, "Main Switch", "router"),
                SearchHit("api/dcim/devices/", 4, "Backup Router", "active"),
            )

        assertEquals(listOf(2, 1, 4, 3), rankSearchHits("router", hits).map { it.id })
    }

    @Test
    fun `name matches outrank asset tag matches, which outrank secondary line matches`() {
        val hits =
            listOf(
                // Only a weak "contains" match on the name, but a name match nonetheless.
                SearchHit("api/dcim/devices/", 1, "Old Router Backup", "active", assetTag = null),
                // Exact asset tag match - a strong signal, but not a name match.
                SearchHit(
                    "api/dcim/devices/",
                    2,
                    "Switch",
                    "active",
                    assetTag = "router-42",
                ),
                // Only matches via the secondary line (e.g. site/device-type text).
                SearchHit("api/dcim/devices/", 3, "Access Point", "Router Closet"),
            )

        assertEquals(listOf(1, 2, 3), rankSearchHits("router", hits).map { it.id })
    }

    @Test
    fun `deduplicates hits from typed and generic caches`() {
        val hits =
            listOf(
                SearchHit("api/dcim/devices/", 7, "Router", null),
                SearchHit("api/dcim/devices/", 7, "Router", null),
            )

        assertEquals(1, rankSearchHits("router", hits).size)
    }

    @Test
    fun `prioritizes devices and device types when match quality is equal`() {
        val hits =
            listOf(
                SearchHit("api/dcim/sites/", 3, "Router site", null),
                SearchHit(
                    GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH,
                    2,
                    "Router type",
                    null,
                ),
                SearchHit(GlobalSearchRepository.DEVICES_ENDPOINT_PATH, 1, "Router device", null),
            )

        assertEquals(listOf(1, 2, 3), rankSearchHits("router", hits).map { it.id })
    }

    @Test
    fun typePrefixSuggestionsMatchLabelsAndPreserveRemainingQuery() {
        val suggestions =
            typeFilterSuggestions(
                "tena office",
                listOf(
                    model("tenants", "Tenants", "api/tenancy/tenants/"),
                    model("devices", "Devices", "api/dcim/devices/"),
                ),
            )

        assertEquals(listOf("Tenants"), suggestions.map { it.modelLabel })
        assertEquals("office", queryRemainderAfterTypeSelection("tena office"))
    }

    @Test
    fun structuredFiltersAcceptColonEqualsAliasesAndSubstringValues() {
        val colon = parseGlobalSearchQuery("MANUFACTURER:Shelly router")
        val spacedColon = parseGlobalSearchQuery("manufacturer: Shelly")
        val equals = parseGlobalSearchQuery("mac=AA:BB ip:192.0")
        val spacedEquals = parseGlobalSearchQuery("manufacturer= Shelly")

        assertEquals("router", colon.freeText)
        assertEquals("manufacturer", colon.filters.single().key)
        assertEquals("Shelly", colon.filters.single().value)
        assertEquals("Shelly", spacedColon.filters.single().value)
        assertEquals("AA:BB 192.0", equals.networkQuery)
        assertTrue(spacedEquals.filters.isEmpty())
        assertTrue(searchFieldKeyMatches("ip", "primary_ip"))
        assertTrue(searchFieldKeyMatches("mac", "mac_address"))
        assertEquals("IP", formatSearchFieldLabel("ip"))
        assertEquals("MAC Address", formatSearchFieldLabel("mac_address"))
        assertTrue("Acme Shelly Labs".contains(colon.filters.single().value, ignoreCase = true))
    }

    @Test
    fun manufacturerAliasUsesCanonicalFilterMatching() {
        listOf("man:Shelly", "man: Shelly", "man=Shelly").forEach { query ->
            val parsed = parseGlobalSearchQuery(query)
            assertEquals("manufacturer", parsed.filters.single().key)
            assertEquals("Shelly", parsed.filters.single().value)
        }
    }

    @Test
    fun typeFiltersResolveShortCollectionNames() {
        assertTrue(searchTypeMatches("api/dcim/devices/", "dev"))
        assertTrue(searchTypeMatches("api/dcim/device-types/", "dt"))
        assertTrue(searchTypeMatches("api/ipam/ip-addresses/", "ip"))
        assertTrue(searchTypeMatches("api/dcim/racks/", "rack"))
        assertTrue(!searchTypeMatches("api/dcim/devices/", "ip"))
    }

    @Test
    fun typeAliasParsesAsCanonicalCollectionFilter() {
        assertEquals("type", parseGlobalSearchQuery("tpe:dt").filters.single().key)
        assertEquals("dt", parseGlobalSearchQuery("type:dt").filters.single().value)
    }

    @Test
    fun interfaceAndAssignedIpReferencesResolveToTheirDevice() {
        val interfaceObject =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(44),
                    "display" to JsonPrimitive("Ethernet"),
                    "device" to JsonObject(mapOf("id" to JsonPrimitive(7))),
                )
            )
        val ipObject =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(88),
                    "address" to JsonPrimitive("192.0.2.7/24"),
                    "assigned_object_type" to JsonPrimitive("dcim.interface"),
                    "assigned_object_id" to JsonPrimitive(44),
                )
            )
        val macInterface =
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(44),
                    "device" to JsonObject(mapOf("id" to JsonPrimitive(7))),
                    "mac_address" to JsonPrimitive("AA:BB:CC:DD:EE:FF"),
                )
            )

        assertEquals(
            NetworkDeviceMatch(7, "Interface Ethernet"),
            networkDeviceMatch(
                GlobalSearchRepository.INTERFACES_ENDPOINT_PATH,
                interfaceObject,
                emptyMap(),
            ),
        )
        assertEquals(
            NetworkDeviceMatch(7, "IP 192.0.2.7/24"),
            networkDeviceMatch(
                GlobalSearchRepository.IP_ADDRESSES_ENDPOINT_PATH,
                ipObject,
                mapOf(44 to 7),
            ),
        )
        assertEquals(
            NetworkDeviceMatch(7, "MAC AA:BB:CC:DD:EE:FF"),
            networkDeviceMatch(
                GlobalSearchRepository.INTERFACES_ENDPOINT_PATH,
                macInterface,
                emptyMap(),
            ),
        )
    }

    @Test
    fun `search index retains raw JSON only for network endpoints`() {
        assertTrue(searchIndexNeedsObjectJson(GlobalSearchRepository.INTERFACES_ENDPOINT_PATH))
        assertTrue(searchIndexNeedsObjectJson(GlobalSearchRepository.IP_ADDRESSES_ENDPOINT_PATH))
        assertTrue(!searchIndexNeedsObjectJson(GlobalSearchRepository.DEVICE_TYPES_ENDPOINT_PATH))
        assertTrue(!searchIndexNeedsObjectJson("api/dcim/racks/"))
    }

    private fun model(modelKey: String, label: String, endpointPath: String) =
        NetBoxModelEntity("app", "App", modelKey, label, endpointPath)
}
