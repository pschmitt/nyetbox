package dev.pschmitt.nyetbox.ui.navigation

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable data object Onboarding : Route

    /**
     * Home/dashboard screen (NBC-9) - the default post-login landing destination and a bottom-nav
     * tab, see NetBoxNavHost/MainActivity.
     */
    @Serializable data object Dashboard : Route

    @Serializable data object DeviceList : Route

    @Serializable data class Topology(val focusedDeviceId: Int? = null) : Route

    /** Component type picker for adding a device component from a device detail page. */
    @Serializable data class AddComponent(val deviceId: Int) : Route

    @Serializable data class DeviceDetail(val deviceId: Int) : Route

    @Serializable data class Scanner(val fromOnboarding: Boolean = false) : Route

    @Serializable data object Settings : Route

    @Serializable
    data class SettingsCategory(
        val category: dev.pschmitt.nyetbox.ui.settings.SettingsCategory,
        val openServerManager: Boolean = false,
    ) : Route

    /** OSS dependency + license list (NBC-448), reached from Settings > About. */
    @Serializable data object Libraries : Route

    /** Model picker for the metadata-driven create form, reachable from the global bottom bar. */
    @Serializable data object Add : Route

    /** Durable offline-edit conflict resolver (NBC-32). */
    @Serializable data object EditConflicts : Route

    /** Review and revert queued offline creates and edits (NBC-145). */
    @Serializable data object PendingChanges : Route

    /**
     * Cross-model search (NBC-13) - distinct from the sidebar's own section-name filter (NBC-6).
     */
    @Serializable data object GlobalSearch : Route

    /** Search for a cached target for a file shared into the app. */
    @Serializable
    data class SharedMedia(
        val uri: String,
        val mimeType: String? = null,
        val filename: String? = null,
    ) : Route

    /** Upload a previously shared file after the user selected its cached NetBox target. */
    @Serializable
    data class SharedMediaUpload(
        val endpointPath: String,
        val objectId: Int,
        val targetLabel: String,
        val uri: String,
        val mimeType: String? = null,
        val filename: String? = null,
    ) : Route

    /** Generated list/detail screens for any NetBox object type - see NBC-6/DirectoryRepository. */
    @Serializable
    data class GenericList(
        val endpointPath: String,
        val label: String,
        val filterKey: String? = null,
        val filterValue: Int? = null,
    ) : Route

    @Serializable
    data class Generic(
        val endpointPath: String,
        val id: Int,
        val breadcrumb: String? = null,
        val focusFieldKey: String? = null,
        val startInEdit: Boolean = false,
        val highlightDeviceId: Int? = null,
    ) : Route

    @Serializable
    data class GenericCreate(
        val endpointPath: String,
        val label: String,
        val returnFieldKey: String? = null,
        val reopenFocusedEditor: Boolean = false,
        val parentDeviceId: Int? = null,
    ) : Route

    /**
     * Field-by-field before/after view for one dashboard changelog entry (NBC-42) - distinct from
     * [Generic], which only ever shows the object's *current* state.
     */
    @Serializable data class ObjectChangeDiff(val changeId: Int) : Route

    /** Human-readable summary opened from a completed offline reconciliation notification. */
    @Serializable data class SyncSummary(val summary: String) : Route
}
