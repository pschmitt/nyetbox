package dev.pschmitt.nyetbox.scanner

import dev.pschmitt.nyetbox.qrsetup.QrConfigCodec
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * What a scanned/opened NetBox URL (or a bare numeric id, which is assumed to be a device) resolves
 * to.
 */
sealed interface NetBoxTarget {
    data class Device(val id: Int) : NetBoxTarget

    data class DeviceAssetTag(val assetTag: String) : NetBoxTarget

    data class Object(val endpointPath: String, val id: Int) : NetBoxTarget

    data class Setup(val baseUrl: String, val token: String) : NetBoxTarget
}

object NetBoxUrlParser {
    // Matches any NetBox object detail path, e.g. /dcim/devices/393/, /ipam/prefixes/12,
    // /plugins/netbox-documents/documents/7/ (an extra segment before the id is fine - "find"
    // just needs *a* two-or-more-segment/id tail to match, and NetBox web paths are always
    // <app>/<model>/<id>/, optionally with more segments in front for a reverse-proxy subpath).
    private val OBJECT_PATH_FINDER = Regex("""/([a-z0-9_-]+)/([a-z0-9_-]+)/(\d+)/?""")
    private val ASSET_TAG_PATTERN = Regex("""#?[A-Za-z0-9][A-Za-z0-9._/-]{0,127}""")
    private val SIMPLE_OBJECT_ENDPOINTS =
        mapOf(
            "site" to "api/dcim/sites/",
            "location" to "api/dcim/locations/",
            "rack" to "api/dcim/racks/",
            "device-type" to "api/dcim/device-types/",
            "device-role" to "api/dcim/device-roles/",
            "platform" to "api/dcim/platforms/",
            "manufacturer" to "api/dcim/manufacturers/",
            "module" to "api/dcim/modules/",
            "module-type" to "api/dcim/module-types/",
            "interface" to "api/dcim/interfaces/",
            "console-port" to "api/dcim/console-ports/",
            "console-server-port" to "api/dcim/console-server-ports/",
            "power-port" to "api/dcim/power-ports/",
            "power-outlet" to "api/dcim/power-outlets/",
            "front-port" to "api/dcim/front-ports/",
            "rear-port" to "api/dcim/rear-ports/",
            "device-bay" to "api/dcim/device-bays/",
            "virtual-chassis" to "api/dcim/virtual-chassis/",
            "cable" to "api/dcim/cables/",
            "tenant" to "api/tenancy/tenants/",
            "tenant-group" to "api/tenancy/tenant-groups/",
            "cluster" to "api/virtualization/clusters/",
            "cluster-type" to "api/virtualization/cluster-types/",
            "cluster-group" to "api/virtualization/cluster-groups/",
            "virtual-machine" to "api/virtualization/virtual-machines/",
            "provider" to "api/circuits/providers/",
            "provider-account" to "api/circuits/provider-accounts/",
            "circuit-type" to "api/circuits/circuit-types/",
            "circuit" to "api/circuits/circuits/",
            "circuit-group" to "api/circuits/circuit-groups/",
            "wireless-lan" to "api/wireless/wireless-lans/",
            "wireless-link" to "api/wireless/wireless-links/",
            "wireless-role" to "api/wireless/wireless-roles/",
            "aggregate" to "api/ipam/aggregates/",
            "prefix" to "api/ipam/prefixes/",
            "ip-range" to "api/ipam/ip-ranges/",
            "ip-address" to "api/ipam/ip-addresses/",
            "vlan" to "api/ipam/vlans/",
            "vlan-group" to "api/ipam/vlan-groups/",
            "vrf" to "api/ipam/vrfs/",
            "service" to "api/ipam/services/",
            "tunnel-group" to "api/vpn/tunnel-groups/",
            "tunnel" to "api/vpn/tunnels/",
            "tag" to "api/extras/tags/",
            "custom-field" to "api/extras/custom-fields/",
            "image-attachment" to "api/extras/image-attachments/",
            "journal-entry" to "api/extras/journal-entries/",
        )
    private val API_APP_KEYS =
        setOf(
            "core",
            "dcim",
            "ipam",
            "circuits",
            "tenancy",
            "virtualization",
            "wireless",
            "vpn",
            "extras",
        )

    /** Extracts a NetBox device/object target from a scanned/opened URL, or a bare numeric id. */
    fun parse(text: String): NetBoxTarget? {
        val trimmed = text.trim()
        parseNyetbox(trimmed)?.let {
            return it
        }
        if (QrConfigCodec.looksLikeQrConfigUri(trimmed)) {
            return runCatching {
                    QrConfigCodec.decodePayload(trimmed).let {
                        NetBoxTarget.Setup(it.baseUrl, it.token)
                    }
                }
                .getOrNull()
        }
        trimmed.toIntOrNull()?.let {
            return NetBoxTarget.Device(it)
        }

        val match = OBJECT_PATH_FINDER.findAll(trimmed).lastOrNull() ?: return null
        val (app, model, idText) = match.destructured
        val id = idText.toIntOrNull() ?: return null
        return if (app == "dcim" && model == "devices") {
            NetBoxTarget.Device(id)
        } else {
            NetBoxTarget.Object(endpointPath = "api/$app/$model/", id = id)
        }
    }

    /** Parses the app-owned `nyetbox://` deep-link format. */
    private fun parseNyetbox(text: String): NetBoxTarget? {
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("nyetbox", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        val segments =
            uri.rawPath
                .orEmpty()
                .trim('/')
                .split('/')
                .filter(String::isNotEmpty)
                .map(::decodeSegment)
        return when {
            host == "device" || host == "devices" -> parseDeviceLink(segments, uri.fragment)
            host == "object" -> parseApiLink(segments.dropWhile { it == "api" })
            host in API_APP_KEYS -> parseApiLink(listOf(host) + segments)
            host == "plugins" -> parseApiLink(listOf(host) + segments)
            else ->
                SIMPLE_OBJECT_ENDPOINTS[host]?.let { endpoint ->
                    segments.firstOrNull()?.toIntOrNull()?.let { id ->
                        NetBoxTarget.Object(endpoint, id)
                    }
                }
        }
    }

    private fun parseDeviceLink(segments: List<String>, fragment: String?): NetBoxTarget? {
        segments.firstOrNull()?.toIntOrNull()?.let {
            return NetBoxTarget.Device(it)
        }
        if (segments.firstOrNull()?.lowercase() !in setOf("asset-tag", "asset_tag")) return null
        val tag = buildString {
            append(segments.drop(1).joinToString("/"))
            if (isEmpty() && fragment != null) append('#').append(fragment)
        }
        return tag.takeIf { ASSET_TAG_PATTERN.matches(it) }?.let(NetBoxTarget::DeviceAssetTag)
    }

    private fun parseApiLink(segments: List<String>): NetBoxTarget? {
        if (segments.firstOrNull() == "plugins") {
            if (segments.size < 4) return null
            val plugin = segments[1]
            val model = segments[2]
            val id = segments[3].toIntOrNull() ?: return null
            return NetBoxTarget.Object("api/plugins/$plugin/$model/", id)
        }
        if (segments.size < 3) return null
        val app = segments[0]
        val model = segments[1]
        val id = segments[2].toIntOrNull() ?: return null
        return if (app == "dcim" && model == "devices") {
            NetBoxTarget.Device(id)
        } else {
            NetBoxTarget.Object("api/$app/$model/", id)
        }
    }

    private fun decodeSegment(segment: String): String =
        runCatching {
                URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
            }
            .getOrDefault(segment)

    /** Returns a barcode-friendly asset-tag candidate without changing URL/ID parsing semantics. */
    fun parseAssetTag(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.toIntOrNull() != null || parse(trimmed) != null)
            return null
        return trimmed.takeIf { ASSET_TAG_PATTERN.matches(it) }
    }
}
