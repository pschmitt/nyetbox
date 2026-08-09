package dev.pschmitt.nyetbox.ui.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Category
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Best-effort icon per NetBox app namespace - falls back to a generic icon for anything unknown
 * (custom plugins, apps NetBox adds after this list was written, etc).
 */
object AppIcons {
    private val BY_APP_KEY: Map<String, ImageVector> =
        mapOf(
            "dcim" to Icons.Default.Dns,
            "ipam" to Icons.Default.Lan,
            "circuits" to Icons.Default.Cable,
            "tenancy" to Icons.Default.Group,
            "virtualization" to Icons.Default.Storage,
            "wireless" to Icons.Default.Wifi,
            "vpn" to Icons.Default.VpnLock,
            "extras" to Icons.Default.Layers,
        )

    private val BY_ENDPOINT_PATH: Map<String, ImageVector> =
        mapOf(
            "api/dcim/devices" to Icons.Default.Hub,
            "api/dcim/device-types" to Icons.Default.Dns,
            "api/dcim/racks" to Icons.Default.Storage,
            "api/dcim/sites" to Icons.Default.Place,
            "api/dcim/locations" to Icons.Default.Place,
            "api/dcim/manufacturers" to Icons.Default.Dns,
            "api/dcim/interfaces" to Icons.Default.Lan,
            "api/dcim/console-ports" to Icons.Default.Cable,
            "api/dcim/console-server-ports" to Icons.Default.Cable,
            "api/dcim/front-ports" to Icons.Default.Cable,
            "api/dcim/rear-ports" to Icons.Default.Cable,
            "api/dcim/power-ports" to Icons.Default.Power,
            "api/dcim/power-outlets" to Icons.Default.Power,
            "api/dcim/cables" to Icons.Default.Cable,
            "api/dcim/device-bays" to Icons.Default.Layers,
            "api/dcim/module-bays" to Icons.Default.Layers,
            "api/ipam/ip-addresses" to Icons.Default.Lan,
            "api/ipam/prefixes" to Icons.Default.Layers,
            "api/circuits/circuits" to Icons.Default.Cable,
            "api/virtualization/virtual-machines" to Icons.Default.Computer,
            "api/virtualization/clusters" to Icons.Default.Storage,
            "api/tenancy/tenants" to Icons.Default.Group,
            "api/wireless/wireless-links" to Icons.Default.Wifi,
            "api/wireless/wireless-lans" to Icons.Default.Wifi,
            "api/vpn/tunnels" to Icons.Default.VpnLock,
            "api/dcim/device-roles" to Icons.Default.Group,
            "api/dcim/power-feeds" to Icons.Default.Power,
            "api/ipam/vlans" to Icons.Default.Lan,
            "api/ipam/vrfs" to Icons.Default.Lan,
            "api/ipam/asns" to Icons.Default.Lan,
            "api/circuits/providers" to Icons.Default.Cable,
            "api/tenancy/contacts" to Icons.Default.Group,
        )

    fun forAppKey(appKey: String): ImageVector =
        BY_APP_KEY[appKey]
            ?: if (appKey.startsWith("plugins/")) Icons.Default.Extension
            else Icons.Outlined.Category

    /** Return the most specific stable icon for an endpoint, falling back to its app namespace. */
    fun forEndpointPath(endpointPath: String): ImageVector {
        val normalized = endpointPath.trim('/')
        BY_ENDPOINT_PATH[normalized]?.let {
            return it
        }
        val segments = normalized.split('/')
        val appKey =
            if (segments.getOrNull(1) == "plugins") {
                "plugins/${segments.getOrNull(2).orEmpty()}"
            } else {
                segments.getOrNull(1).orEmpty()
            }
        return forAppKey(appKey)
    }
}
