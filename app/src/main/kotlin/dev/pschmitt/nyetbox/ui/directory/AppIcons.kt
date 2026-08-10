package dev.pschmitt.nyetbox.ui.directory

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Factory
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerInput
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
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
            "api/dcim/device-types" to Icons.Default.DeveloperBoard,
            "api/dcim/racks" to Icons.Default.Storage,
            "api/dcim/sites" to Icons.Default.Public,
            "api/dcim/locations" to Icons.Default.LocationOn,
            "api/dcim/manufacturers" to Icons.Default.Factory,
            "api/dcim/platforms" to Icons.Default.Code,
            "api/dcim/modules" to Icons.Default.Memory,
            "api/dcim/module-types" to Icons.Default.DeveloperBoard,
            "api/dcim/interfaces" to Icons.Default.Lan,
            "api/dcim/console-ports" to Icons.Default.Terminal,
            "api/dcim/console-server-ports" to Icons.AutoMirrored.Filled.Dvr,
            "api/dcim/front-ports" to Icons.Default.SettingsInputComponent,
            "api/dcim/rear-ports" to Icons.Default.Output,
            "api/dcim/power-ports" to Icons.Default.PowerInput,
            "api/dcim/power-outlets" to Icons.Default.ElectricalServices,
            "api/dcim/inventory-items" to Icons.Default.Inventory2,
            "api/dcim/virtual-chassis" to Icons.Default.Hub,
            "api/dcim/cables" to Icons.Default.Cable,
            "api/dcim/device-bays" to Icons.Default.ViewModule,
            "api/dcim/module-bays" to Icons.Default.Memory,
            "api/dcim/device-roles" to Icons.Default.Badge,
            "api/dcim/rack-roles" to Icons.Default.Badge,
            "api/dcim/power-feeds" to Icons.Default.ElectricalServices,
            "api/ipam/ip-addresses" to Icons.Default.Lan,
            "api/ipam/prefixes" to Icons.Default.AccountTree,
            "api/ipam/aggregates" to Icons.Default.AccountTree,
            "api/ipam/ip-ranges" to Icons.AutoMirrored.Filled.CallSplit,
            "api/ipam/vlans" to Icons.Default.Lan,
            "api/ipam/vlan-groups" to Icons.Default.AccountTree,
            "api/ipam/vrfs" to Icons.Default.Tune,
            "api/ipam/asns" to Icons.Default.Numbers,
            "api/ipam/services" to Icons.Default.Dns,
            "api/circuits/circuits" to Icons.Default.Route,
            "api/circuits/providers" to Icons.Default.Business,
            "api/circuits/provider-accounts" to Icons.Default.Business,
            "api/circuits/circuit-types" to Icons.Default.Tune,
            "api/circuits/circuit-groups" to Icons.Default.AccountTree,
            "api/virtualization/virtual-machines" to Icons.Default.Computer,
            "api/virtualization/clusters" to Icons.Default.Cloud,
            "api/virtualization/cluster-types" to Icons.Default.Tune,
            "api/virtualization/cluster-groups" to Icons.Default.AccountTree,
            "api/tenancy/tenants" to Icons.Default.Group,
            "api/tenancy/tenant-groups" to Icons.Default.Groups,
            "api/tenancy/contacts" to Icons.Default.Person,
            "api/wireless/wireless-links" to Icons.Default.Link,
            "api/wireless/wireless-lans" to Icons.Default.Wifi,
            "api/wireless/wireless-roles" to Icons.Default.Tune,
            "api/vpn/tunnels" to Icons.Default.VpnLock,
            "api/vpn/tunnel-groups" to Icons.Default.AccountTree,
            "api/extras/tags" to Icons.Default.LocalOffer,
            "api/extras/custom-fields" to Icons.Default.Tune,
            "api/extras/image-attachments" to Icons.Default.AttachFile,
            "api/extras/journal-entries" to Icons.Default.History,
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
