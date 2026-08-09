package dev.pschmitt.nyetbox.widget

import androidx.annotation.DrawableRes
import dev.pschmitt.nyetbox.R

/**
 * [dev.pschmitt.nyetbox.ui.directory.AppIcons]'s exact same per-app-namespace/endpoint mapping,
 * mirrored here as drawable resource ids instead of Compose `ImageVector`s - Glance's `Image` can't
 * render an `ImageVector` directly, so the widget's bookmark/change rows need a real resource to
 * match what the app itself would show for that object type.
 */
internal object WidgetObjectIcons {
    private val BY_APP_KEY: Map<String, Int> =
        mapOf(
            "dcim" to R.drawable.ic_object_dns,
            "ipam" to R.drawable.ic_object_lan,
            "circuits" to R.drawable.ic_object_cable,
            "tenancy" to R.drawable.ic_object_group,
            "virtualization" to R.drawable.ic_object_storage,
            "wireless" to R.drawable.ic_object_wifi,
            "vpn" to R.drawable.ic_object_vpn_lock,
            "extras" to R.drawable.ic_object_layers,
        )

    private val BY_ENDPOINT_PATH: Map<String, Int> =
        mapOf(
            "api/dcim/devices" to R.drawable.ic_object_hub,
            "api/dcim/device-types" to R.drawable.ic_object_dns,
            "api/dcim/racks" to R.drawable.ic_object_storage,
            "api/dcim/sites" to R.drawable.ic_object_place,
            "api/dcim/locations" to R.drawable.ic_object_place,
            "api/dcim/manufacturers" to R.drawable.ic_object_dns,
            "api/dcim/interfaces" to R.drawable.ic_object_lan,
            "api/dcim/console-ports" to R.drawable.ic_object_cable,
            "api/dcim/console-server-ports" to R.drawable.ic_object_cable,
            "api/dcim/front-ports" to R.drawable.ic_object_cable,
            "api/dcim/rear-ports" to R.drawable.ic_object_cable,
            "api/dcim/power-ports" to R.drawable.ic_object_power,
            "api/dcim/power-outlets" to R.drawable.ic_object_power,
            "api/dcim/device-bays" to R.drawable.ic_object_layers,
            "api/dcim/module-bays" to R.drawable.ic_object_layers,
            "api/ipam/ip-addresses" to R.drawable.ic_object_lan,
            "api/ipam/prefixes" to R.drawable.ic_object_layers,
            "api/circuits/circuits" to R.drawable.ic_object_cable,
            "api/virtualization/virtual-machines" to R.drawable.ic_object_computer,
            "api/virtualization/clusters" to R.drawable.ic_object_storage,
            "api/tenancy/tenants" to R.drawable.ic_object_group,
            "api/wireless/wireless-links" to R.drawable.ic_object_wifi,
            "api/vpn/tunnels" to R.drawable.ic_object_vpn_lock,
        )

    private fun forAppKey(appKey: String): Int =
        BY_APP_KEY[appKey]
            ?: if (appKey.startsWith("plugins/")) R.drawable.ic_object_extension
            else R.drawable.ic_object_category

    @DrawableRes
    fun forEndpointPath(endpointPath: String?): Int {
        if (endpointPath == null) return R.drawable.ic_object_category
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
