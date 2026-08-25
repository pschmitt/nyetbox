package dev.pschmitt.nyetbox.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.data.backup.SettingsBackupSettings
import dev.pschmitt.nyetbox.data.schema.NetBoxEndpointCatalog
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.data.topology.TopologyPosition
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Debug-only logcat marker emitted the moment [SettingsRepository.recordSuccessfulSync] runs - the
 * E2E/screenshot instrumented tests grep for this via `logcat` instead of polling
 * [dev.pschmitt.nyetbox.ui.dashboard.DashboardViewModel.showInitialSyncOverlay]'s own visibility,
 * which can toggle several times during the first, multi-step sync before settling for good.
 */
const val E2E_SYNC_COMPLETE_MARKER = "NYETBOX_E2E_SYNC_COMPLETE"

/** Durable media is part of the offline cache by default; users may explicitly opt out. */
internal const val DEFAULT_SYNC_ATTACHMENTS_TO_DISK = true

internal fun resolveSyncAttachmentsToDisk(stored: Boolean?): Boolean =
    stored ?: DEFAULT_SYNC_ATTACHMENTS_TO_DISK

/** Matches the interval periodic sync used before it became user-configurable. */
internal const val DEFAULT_SYNC_INTERVAL_HOURS = 6

data class NetBoxCredentials(val baseUrl: String, val token: String) {
    val isValid: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/** A saved connection. Only one profile is active, but every profile keeps its own cache. */
@Serializable
data class ServerProfile(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val token: String,
    val cacheDatabaseName: String,
    val cacheNamespace: String = id,
) {
    val credentials: NetBoxCredentials
        get() = NetBoxCredentials(baseUrl, token)
}

data class NetBoxUserIdentity(
    val username: String,
    val fullName: String? = null,
    val email: String? = null,
    val id: Int? = null,
) {
    val displayName: String
        get() = fullName?.takeIf { it.isNotBlank() } ?: username

    val summary: String
        get() =
            if (displayName.equals(username, ignoreCase = true)) username
            else "$displayName ($username)"
}

@Serializable
data class PrintSettings(
    val defaultPrinterName: String? = null,
    val defaultPrinterAddress: String? = null,
    val invertColors: Boolean = true,
    val verticalText: Boolean = false,
    val longLabel: Boolean = false,
    val copies: Int = 1,
    val qrSize: Int = 64,
) {
    fun normalized(): PrintSettings =
        copy(
            defaultPrinterName = defaultPrinterName?.takeIf { it.isNotBlank() },
            defaultPrinterAddress = defaultPrinterAddress?.takeIf { it.isNotBlank() },
            copies = copies.coerceIn(1, 9),
            qrSize = qrSize.takeIf { it == 48 || it == 56 || it == 64 } ?: 64,
        )
}

data class SyncIssue(val message: String, val occurredAt: Long, val details: String)

/**
 * Compact record of the most recently *completed* sync pass (NBC-435), so the UI can answer "was
 * that sync as cheap as it should have been?" after the fact instead of showing identical progress
 * text for a quick incremental check and the multi-minute full reconciliation pass.
 *
 * [itemsRefreshed] deliberately uses the neutral word "refreshed" rather than "changed": it's
 * [OfflineSyncSummary]'s `devices + genericObjects` count, which for a handful of endpoints that
 * can't be synced incrementally (see `OfflineSyncRepository`'s `SYNC_EXCLUDED_ENDPOINTS`/NBC-429)
 * counts every fetched row, not just changed ones. The number gets more truthful as more of those
 * gaps close.
 */
data class LastSyncSummary(
    val isFullSync: Boolean,
    val durationMillis: Long,
    val itemsRefreshed: Int,
)

enum class BackupFrequency(val storageKey: String, val label: String, val intervalDays: Long) {
    Daily("daily", "Daily", 1),
    Weekly("weekly", "Weekly", 7),
    Monthly("monthly", "Monthly", 30);

    companion object {
        fun fromStorage(value: String?): BackupFrequency =
            entries.firstOrNull { it.storageKey == value } ?: Weekly
    }
}

/** Stable object key used by field preferences, e.g. `device`. */
fun hiddenFieldObjectKey(endpointPath: String): String =
    endpointPath.trim('/').substringAfterLast('/').lowercase().let(::singularizeModel)

/** Stable user-facing key for a field preference, e.g. `device/model`. */
fun hiddenFieldPreferenceKey(endpointPath: String, fieldName: String): String {
    val model = hiddenFieldObjectKey(endpointPath)
    val field = fieldName.trim().lowercase().replace(Regex("\\s+"), "_")
    return "$model/$field"
}

private fun singularizeModel(model: String): String =
    when {
        model.endsWith("ies") -> model.removeSuffix("ies") + "y"
        model.endsWith("sses") -> model.removeSuffix("es")
        model.endsWith("xes") -> model.removeSuffix("es")
        model.endsWith("s") -> model.removeSuffix("s")
        else -> model
    }

fun normalizeHiddenFieldPreferenceKey(value: String): String? {
    val normalized =
        value
            .trim()
            .lowercase()
            .replace(Regex("\\s*/\\s*"), "/")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("[^a-z0-9_./-]"), "")
    val parts = normalized.split('/', limit = 2)
    return normalized.takeIf { parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() }
}

@Serializable
data class GestureTarget(val endpointPath: String, val label: String, val id: Int? = null)

enum class GestureAction(val storageKey: String, val label: String) {
    Off("off", "Off"),
    Dashboard("dashboard", "Home"),
    GlobalSearch("global_search", "Global search"),
    Scanner("scanner", "QR scanner"),
    Settings("settings", "Settings"),
    Add("add", "Add item"),
    AddSpecific("add_specific", "Add specific item type"),
    Sync("sync", "Sync now"),
    OfflineOn("offline_on", "Turn offline mode on"),
    OfflineOff("offline_off", "Turn offline mode off"),
    SwitchServer("switch_server", "Switch NetBox server"),
    DeviceList("device_list", "Device list"),
    ListSpecific("list_specific", "Specific item list"),
    DetailSpecific("detail_specific", "Specific item detail");

    companion object {
        fun fromStorage(value: String?, fallback: GestureAction = GlobalSearch): GestureAction =
            entries.firstOrNull { it.storageKey == value } ?: fallback

        /**
         * Like [fromStorage], but an absent/unrecognized [value] means "nothing to dispatch" rather
         * than silently falling back to [GlobalSearch] - used for intent extras (launcher
         * shortcuts, widget taps) where a missing extra genuinely means no gesture was intended.
         */
        fun fromStorageOrNull(value: String?): GestureAction? = entries.firstOrNull {
            it.storageKey == value
        }

        /**
         * Actions that always resolve to a [dev.pschmitt.nyetbox.ui.navigation.Route] via
         * [dev.pschmitt.nyetbox.routeForGesture] - the only ones a nav-bar slot can use, since a
         * slot must always be able to show a clear "selected" state.
         */
        val navigational: List<GestureAction>
            get() = entries.filterNot {
                it == Off || it == Sync || it == OfflineOn || it == OfflineOff
            }

        /**
         * The subset of [navigational] offered as launcher shortcuts. Excludes [Dashboard]:
         * long-pressing the launcher icon and picking "Home" duplicates what a plain tap on the
         * icon already does, wasting one of Android's ~4 shortcut slots on a no-op.
         */
        val shortcutable: List<GestureAction>
            get() = navigational - Dashboard
    }
}

/**
 * One configurable slot in the bottom navigation bar/rail (up to 5, see
 * [dev.pschmitt.nyetbox.ui.common.NetBoxBottomBar]).
 */
@Serializable data class NavBarItem(val action: GestureAction, val target: GestureTarget? = null)

enum class GestureShortcut(val storageKey: String, val label: String) {
    TwoFingerDown("two_finger_down", "Two-finger swipe down"),
    TwoFingerLeft("two_finger_left", "Two-finger swipe left"),
    TwoFingerRight("two_finger_right", "Two-finger swipe right"),
    ThreeFingerUp("three_finger_up", "Three-finger swipe up"),
    ThreeFingerDown("three_finger_down", "Three-finger swipe down"),
    ThreeFingerLeft("three_finger_left", "Three-finger swipe left"),
    ThreeFingerRight("three_finger_right", "Three-finger swipe right"),
}

enum class ScannerLens(val storageKey: String, val label: String) {
    Back("back", "Back camera"),
    Front("front", "Front camera");

    companion object {
        fun fromStorage(value: String?): ScannerLens =
            entries.firstOrNull { it.storageKey == value } ?: Back
    }
}

enum class ScannerRearLens(val storageKey: String, val label: String) {
    Automatic("automatic", "Automatic (main rear lens)"),
    UltraWide("ultra_wide", "Ultra-wide (0.6×)"),
    Wide("wide", "Wide (1×)"),
    Telephoto("telephoto", "Telephoto (2×)");

    companion object {
        fun fromStorage(value: String?): ScannerRearLens =
            entries.firstOrNull { it.storageKey == value } ?: Automatic
    }
}

enum class ScannerResolution(val storageKey: String, val label: String) {
    Auto("auto", "Automatic (based on device)"),
    Standard("standard", "Standard (720p, faster)"),
    High("high", "High (1080p, better range)");

    companion object {
        fun fromStorage(value: String?): ScannerResolution =
            entries.firstOrNull { it.storageKey == value } ?: Auto
    }
}

enum class ThemeMode(val storageKey: String, val label: String) {
    FollowSystem("system", "Follow system"),
    Light("light", "Light"),
    Dark("dark", "Dark");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: FollowSystem
    }
}

enum class ThemeAccent(val storageKey: String, val label: String) {
    System("system", "System default"),
    Teal("teal", "Teal"),
    Blue("blue", "Blue"),
    Purple("purple", "Purple"),
    Orange("orange", "Orange"),
    Pink("pink", "Pink"),
    Green("green", "Green");

    companion object {
        fun fromStorage(value: String?): ThemeAccent =
            entries.firstOrNull { it.storageKey == value } ?: System
    }
}

/**
 * Base URL and API token, backed by [EncryptedSharedPreferences] (Android Keystore-tied, hence
 * `allowBackup=false` in the manifest - a restored backup couldn't decrypt these anyway).
 */
// AndroidX Security Crypto currently deprecates this API without providing a replacement for
// the same encrypted SharedPreferences migration path. Keep it until the library offers one;
// the suppression makes this intentional compatibility boundary visible to the compiler.
@Suppress("DEPRECATION", "UseKtx")
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "netbox_settings",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    private val settingsJson = Json { ignoreUnknownKeys = true }

    private val _serverProfiles = MutableStateFlow(loadServerProfiles())
    val serverProfiles: StateFlow<List<ServerProfile>> = _serverProfiles.asStateFlow()

    private val _activeServerId = MutableStateFlow(loadActiveServerId())
    val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()

    private val _activeServer = MutableStateFlow(activeServerProfile())
    val activeServer: StateFlow<ServerProfile?> = _activeServer.asStateFlow()

    private val _credentials = MutableStateFlow(loadCredentials())
    val credentials: StateFlow<NetBoxCredentials> = _credentials.asStateFlow()

    private val _currentUser = MutableStateFlow(loadCurrentUser())
    val currentUser: StateFlow<NetBoxUserIdentity?> = _currentUser.asStateFlow()

    private val _serverVersion = MutableStateFlow(loadServerVersion())
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    val isConfigured: Boolean
        get() = _credentials.value.isValid

    // Endpoint paths (e.g. "api/dcim/racks/") pinned to the top of the sidebar - user-configurable
    // via the star toggle on each model row. Defaults to just Devices, this app's original focus.
    private val _pinnedModelPaths = MutableStateFlow(loadPinnedModelPaths())
    val pinnedModelPaths: StateFlow<Set<String>> = _pinnedModelPaths.asStateFlow()

    // Keep the binary media cache enabled by default so Room metadata does not misleadingly imply
    // offline availability when the actual thumbnail/document bytes were never downloaded. The
    // setting remains user-configurable for installs that need to conserve storage or bandwidth.
    private val _syncAttachmentsToDisk =
        MutableStateFlow(
            resolveSyncAttachmentsToDisk(
                if (prefs.contains(KEY_SYNC_ATTACHMENTS))
                    prefs.getBoolean(KEY_SYNC_ATTACHMENTS, false)
                else null
            )
        )
    val syncAttachmentsToDisk: StateFlow<Boolean> = _syncAttachmentsToDisk.asStateFlow()

    // Preserve the existing connected-network behavior by default; users can opt into the safer
    // Wi-Fi-only policy when a full cache (especially attachments) should never use mobile data.
    private val _syncOnlyOnWifi = MutableStateFlow(prefs.getBoolean(KEY_SYNC_ONLY_ON_WIFI, false))
    val syncOnlyOnWifi: StateFlow<Boolean> = _syncOnlyOnWifi.asStateFlow()

    private val _syncWhileRoaming = MutableStateFlow(prefs.getBoolean(KEY_SYNC_WHILE_ROAMING, true))
    val syncWhileRoaming: StateFlow<Boolean> = _syncWhileRoaming.asStateFlow()

    // Preserve the existing behavior for existing installs; users can opt out when startup
    // refreshes are undesirable on metered or otherwise constrained devices.
    private val _syncOnAppLaunch = MutableStateFlow(prefs.getBoolean(KEY_SYNC_ON_APP_LAUNCH, true))
    val syncOnAppLaunch: StateFlow<Boolean> = _syncOnAppLaunch.asStateFlow()

    // Conservative by default: a small home-lab NetBox instance may only run a handful of
    // application-server workers, so syncing too many endpoints at once can saturate it rather
    // than actually finishing sooner.
    private val _syncConcurrency =
        MutableStateFlow(prefs.getInt(KEY_SYNC_CONCURRENCY, DEFAULT_SYNC_CONCURRENCY))
    val syncConcurrency: StateFlow<Int> = _syncConcurrency.asStateFlow()

    // Off by default, preserving existing behavior - background/manual sync already respects
    // requiresBatteryNotLow; restricting it to only while charging is an extra opt-in for
    // battery-sensitive devices.
    private val _syncOnlyWhenCharging =
        MutableStateFlow(prefs.getBoolean(KEY_SYNC_ONLY_WHEN_CHARGING, false))
    val syncOnlyWhenCharging: StateFlow<Boolean> = _syncOnlyWhenCharging.asStateFlow()

    private val _syncIntervalHours =
        MutableStateFlow(prefs.getInt(KEY_SYNC_INTERVAL_HOURS, DEFAULT_SYNC_INTERVAL_HOURS))
    val syncIntervalHours: StateFlow<Int> = _syncIntervalHours.asStateFlow()

    private val _changeNotificationsEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_CHANGE_NOTIFICATIONS_ENABLED, false))
    val changeNotificationsEnabled: StateFlow<Boolean> = _changeNotificationsEnabled.asStateFlow()

    private val _changeNotificationFilters = MutableStateFlow(loadChangeNotificationFilters())
    val changeNotificationFilters: StateFlow<Set<String>> = _changeNotificationFilters.asStateFlow()

    /** Highest object-change id seen during a changelog refresh, used to avoid historical spam. */
    var changeNotificationCursor: Int
        get() =
            prefs.getInt(
                serverScopedKey(KEY_CHANGE_NOTIFICATION_CURSOR),
                if (_activeServerId.value == LEGACY_SERVER_ID) {
                    prefs.getInt(KEY_CHANGE_NOTIFICATION_CURSOR, 0)
                } else 0,
            )
        private set(value) {
            prefs.edit().putInt(serverScopedKey(KEY_CHANGE_NOTIFICATION_CURSOR), value).apply()
        }

    /**
     * When this server profile's cache last completed a full (non-incremental) sync pass, used to
     * decide when the next periodic sync should force one to catch server-side deletions that an
     * incremental, `last_updated`-filtered sync can't see. Internal sync bookkeeping, not a user
     * setting - not part of the portable settings backup, which already excludes the cache database
     * this value describes.
     */
    var lastFullSyncAt: Long
        get() = prefs.getLong(serverScopedKey(KEY_LAST_FULL_SYNC_AT), 0L)
        set(value) {
            prefs.edit().putLong(serverScopedKey(KEY_LAST_FULL_SYNC_AT), value).apply()
        }

    private val _gestureActions = MutableStateFlow(loadGestureActions())
    val gestureActions: StateFlow<Map<GestureShortcut, GestureAction>> =
        _gestureActions.asStateFlow()

    private val _gestureTargets = MutableStateFlow(loadGestureTargets())
    val gestureTargets: StateFlow<Map<GestureShortcut, GestureTarget>> =
        _gestureTargets.asStateFlow()

    private val _navBarItems = MutableStateFlow(loadNavBarItems())
    val navBarItems: StateFlow<List<NavBarItem>> = _navBarItems.asStateFlow()

    private val _shortcutItems = MutableStateFlow(loadShortcutItems())
    val shortcutItems: StateFlow<List<NavBarItem>> = _shortcutItems.asStateFlow()

    private val _scannerLens = MutableStateFlow(loadScannerLens())
    val scannerLens: StateFlow<ScannerLens> = _scannerLens.asStateFlow()

    private val _scannerRearLens = MutableStateFlow(loadScannerRearLens())
    val scannerRearLens: StateFlow<ScannerRearLens> = _scannerRearLens.asStateFlow()

    private val _scannerResolution = MutableStateFlow(loadScannerResolution())
    val scannerResolution: StateFlow<ScannerResolution> = _scannerResolution.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _themeAccent = MutableStateFlow(loadThemeAccent())
    val themeAccent: StateFlow<ThemeAccent> = _themeAccent.asStateFlow()

    private val _objectTypeAccents = MutableStateFlow(loadObjectTypeAccents())
    val objectTypeAccents: StateFlow<Map<String, ThemeAccent>> = _objectTypeAccents.asStateFlow()

    private val _printSettings = MutableStateFlow(loadPrintSettings())
    val printSettings: StateFlow<PrintSettings> = _printSettings.asStateFlow()

    private val _offlineMode = MutableStateFlow(prefs.getBoolean(KEY_OFFLINE_MODE, false))
    val offlineMode: StateFlow<Boolean> = _offlineMode.asStateFlow()

    private val _syncIssue = MutableStateFlow(loadSyncIssue())
    val syncIssue: StateFlow<SyncIssue?> = _syncIssue.asStateFlow()

    private val _lastSuccessfulSyncAt = MutableStateFlow(loadLastSuccessfulSyncAt())
    val lastSuccessfulSyncAt: StateFlow<Long?> = _lastSuccessfulSyncAt.asStateFlow()

    private val _lastSyncSummary = MutableStateFlow(loadLastSyncSummary())
    val lastSyncSummary: StateFlow<LastSyncSummary?> = _lastSyncSummary.asStateFlow()

    private val _hiddenFieldKeys = MutableStateFlow(loadHiddenFieldKeys())
    val hiddenFieldKeys: StateFlow<Set<String>> = _hiddenFieldKeys.asStateFlow()

    private val _sidebarAppOrder = MutableStateFlow(loadOrder(KEY_SIDEBAR_APP_ORDER))
    val sidebarAppOrder: StateFlow<List<String>> = _sidebarAppOrder.asStateFlow()

    private val _sidebarModelOrders = MutableStateFlow(loadModelOrders())
    val sidebarModelOrders: StateFlow<Map<String, List<String>>> = _sidebarModelOrders.asStateFlow()

    private val _hiddenSidebarApps = MutableStateFlow(loadHiddenSidebarApps())
    val hiddenSidebarApps: StateFlow<Set<String>> = _hiddenSidebarApps.asStateFlow()

    private val _dashboardSectionOrder = MutableStateFlow(loadOrder(KEY_DASHBOARD_SECTION_ORDER))
    val dashboardSectionOrder: StateFlow<List<String>> = _dashboardSectionOrder.asStateFlow()

    private val _hiddenDashboardSections = MutableStateFlow(loadHiddenDashboardSections())
    val hiddenDashboardSections: StateFlow<Set<String>> = _hiddenDashboardSections.asStateFlow()

    private val _statsOrder = MutableStateFlow(loadOrder(KEY_STATS_ORDER))
    val statsOrder: StateFlow<List<String>> = _statsOrder.asStateFlow()

    // Preserves existing behavior for installs from before the dashboard's stat tiles became
    // user-choosable (NBC-437): only the four that used to be hardcoded stay visible by default,
    // the rest opt-in.
    private val _hiddenStats = MutableStateFlow(loadHiddenStats())
    val hiddenStats: StateFlow<Set<String>> = _hiddenStats.asStateFlow()

    private val _showTopologyDeviceTypeImages =
        MutableStateFlow(prefs.getBoolean(KEY_SHOW_TOPOLOGY_DEVICE_TYPE_IMAGES, true))
    val showTopologyDeviceTypeImages: StateFlow<Boolean> =
        _showTopologyDeviceTypeImages.asStateFlow()

    private val _topologyNodePositions = MutableStateFlow(loadTopologyNodePositions())
    val topologyNodePositions: StateFlow<Map<String, TopologyPosition>> =
        _topologyNodePositions.asStateFlow()

    private val _scheduledBackupEnabled =
        MutableStateFlow(prefs.getBoolean(KEY_SCHEDULED_BACKUP_ENABLED, false))
    val scheduledBackupEnabled: StateFlow<Boolean> = _scheduledBackupEnabled.asStateFlow()

    private val _scheduledBackupFrequency =
        MutableStateFlow(
            BackupFrequency.fromStorage(
                prefs.getString(KEY_SCHEDULED_BACKUP_FREQUENCY, BackupFrequency.Weekly.storageKey)
            )
        )
    val scheduledBackupFrequency: StateFlow<BackupFrequency> =
        _scheduledBackupFrequency.asStateFlow()

    private val _scheduledBackupFolderUri =
        MutableStateFlow(prefs.getString(KEY_SCHEDULED_BACKUP_FOLDER_URI, null))
    val scheduledBackupFolderUri: StateFlow<String?> = _scheduledBackupFolderUri.asStateFlow()

    private val _scheduledBackupPasswordSet =
        MutableStateFlow(prefs.contains(KEY_SCHEDULED_BACKUP_PASSWORD))
    val scheduledBackupPasswordSet: StateFlow<Boolean> = _scheduledBackupPasswordSet.asStateFlow()

    private val _lastBackupAt =
        MutableStateFlow(prefs.getLong(KEY_LAST_BACKUP_AT, 0L).takeIf { it > 0L })
    val lastBackupAt: StateFlow<Long?> = _lastBackupAt.asStateFlow()

    private val _backupError = MutableStateFlow(prefs.getString(KEY_BACKUP_ERROR, null))
    val backupError: StateFlow<String?> = _backupError.asStateFlow()

    fun setSyncAttachmentsToDisk(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ATTACHMENTS, enabled).apply()
        _syncAttachmentsToDisk.value = enabled
    }

    fun setSyncOnlyOnWifi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ONLY_ON_WIFI, enabled).apply()
        _syncOnlyOnWifi.value = enabled
    }

    fun setSyncWhileRoaming(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_WHILE_ROAMING, enabled).apply()
        _syncWhileRoaming.value = enabled
    }

    fun setSyncOnAppLaunch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ON_APP_LAUNCH, enabled).apply()
        _syncOnAppLaunch.value = enabled
    }

    fun setSyncConcurrency(concurrency: Int) {
        val clamped = concurrency.coerceIn(1, 8)
        prefs.edit().putInt(KEY_SYNC_CONCURRENCY, clamped).apply()
        _syncConcurrency.value = clamped
    }

    fun setSyncOnlyWhenCharging(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SYNC_ONLY_WHEN_CHARGING, enabled).apply()
        _syncOnlyWhenCharging.value = enabled
    }

    fun setSyncIntervalHours(hours: Int) {
        val clamped = hours.coerceIn(1, 24)
        prefs.edit().putInt(KEY_SYNC_INTERVAL_HOURS, clamped).apply()
        _syncIntervalHours.value = clamped
    }

    fun setChangeNotificationsEnabled(enabled: Boolean) {
        var filters = _changeNotificationFilters.value
        if (enabled && filters.isEmpty()) {
            filters = setOf(ChangeNotificationFilter.All.storageKey)
            persistChangeNotificationFilters(filters)
        }
        prefs.edit().putBoolean(KEY_CHANGE_NOTIFICATIONS_ENABLED, enabled).apply()
        _changeNotificationsEnabled.value = enabled
    }

    fun setChangeNotificationFilter(filter: ChangeNotificationFilter, enabled: Boolean) {
        val current = _changeNotificationFilters.value
        val updated =
            when {
                filter == ChangeNotificationFilter.All && enabled ->
                    setOf(ChangeNotificationFilter.All.storageKey)
                filter == ChangeNotificationFilter.All -> current - filter.storageKey
                enabled -> (current - ChangeNotificationFilter.All.storageKey) + filter.storageKey
                else -> current - filter.storageKey
            }
        persistChangeNotificationFilters(updated)
    }

    fun recordChangeNotificationCursor(id: Int) {
        if (id > changeNotificationCursor) changeNotificationCursor = id
    }

    fun setGestureAction(action: GestureAction) {
        setGestureAction(GestureShortcut.TwoFingerDown, action)
    }

    fun setGestureAction(shortcut: GestureShortcut, action: GestureAction) {
        prefs.edit().putString(gesturePreferenceKey(shortcut), action.storageKey).apply()
        _gestureActions.value = _gestureActions.value + (shortcut to action)
    }

    fun setGestureTarget(shortcut: GestureShortcut, target: GestureTarget) {
        prefs.edit().putString(gestureTargetKey(shortcut), encodeGestureTarget(target)).apply()
        _gestureTargets.value = _gestureTargets.value + (shortcut to target)
    }

    fun clearGestureTarget(shortcut: GestureShortcut) {
        prefs.edit().remove(gestureTargetKey(shortcut)).apply()
        _gestureTargets.value = _gestureTargets.value - shortcut
    }

    fun setNavBarItems(items: List<NavBarItem>) {
        val truncated = items.take(MAX_NAV_BAR_ITEMS)
        prefs
            .edit()
            .putString(
                KEY_NAV_BAR_ITEMS,
                settingsJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(NavBarItem.serializer()),
                    truncated,
                ),
            )
            .apply()
        _navBarItems.value = truncated
    }

    fun resetNavBarItems() {
        prefs.edit().remove(KEY_NAV_BAR_ITEMS).apply()
        _navBarItems.value = defaultNavBarItems()
    }

    fun setShortcutItems(items: List<NavBarItem>) {
        val truncated = items.take(MAX_SHORTCUT_ITEMS)
        prefs
            .edit()
            .putString(
                KEY_SHORTCUT_ITEMS,
                settingsJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(NavBarItem.serializer()),
                    truncated,
                ),
            )
            .apply()
        _shortcutItems.value = truncated
    }

    fun resetShortcutItems() {
        prefs.edit().remove(KEY_SHORTCUT_ITEMS).apply()
        _shortcutItems.value = defaultShortcutItems()
    }

    fun setScannerLens(lens: ScannerLens) {
        prefs.edit().putString(KEY_SCANNER_LENS, lens.storageKey).apply()
        _scannerLens.value = lens
    }

    fun setScannerRearLens(lens: ScannerRearLens) {
        prefs.edit().putString(KEY_SCANNER_REAR_LENS, lens.storageKey).apply()
        _scannerRearLens.value = lens
    }

    fun setScannerResolution(resolution: ScannerResolution) {
        prefs.edit().putString(KEY_SCANNER_RESOLUTION, resolution.storageKey).apply()
        _scannerResolution.value = resolution
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
        _themeMode.value = mode
    }

    fun setThemeAccent(accent: ThemeAccent) {
        prefs.edit().putString(KEY_THEME_ACCENT, accent.storageKey).apply()
        _themeAccent.value = accent
    }

    fun setObjectTypeAccent(endpointPath: String, accent: ThemeAccent?) {
        val normalizedPath = endpointPath.trim('/')
        val key = objectTypeAccentKey(normalizedPath)
        val updated = _objectTypeAccents.value.toMutableMap()
        if (accent == null || accent == ThemeAccent.System) {
            prefs.edit().remove(key).apply()
            updated.remove(normalizedPath)
        } else {
            prefs.edit().putString(key, accent.storageKey).apply()
            updated[normalizedPath] = accent
        }
        _objectTypeAccents.value = updated
    }

    fun updatePrintSettings(settings: PrintSettings) {
        val normalized = settings.normalized()
        prefs
            .edit()
            .putString(KEY_DEFAULT_PRINTER_NAME, normalized.defaultPrinterName)
            .putString(KEY_DEFAULT_PRINTER_ADDRESS, normalized.defaultPrinterAddress)
            .putBoolean(KEY_PRINT_INVERT_COLORS, normalized.invertColors)
            .putBoolean(KEY_PRINT_VERTICAL_TEXT, normalized.verticalText)
            .putBoolean(KEY_PRINT_LONG_LABEL, normalized.longLabel)
            .putInt(KEY_PRINT_COPIES, normalized.copies)
            .putInt(KEY_PRINT_QR_SIZE, normalized.qrSize)
            .apply()
        _printSettings.value = normalized
    }

    fun setOfflineMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
        _offlineMode.value = enabled
        if (enabled) clearSyncIssue()
    }

    fun recordSyncIssue(error: Throwable) {
        recordSyncIssue(
            error.message?.takeIf { it.isNotBlank() }
                ?: error::class.simpleName?.takeIf { it.isNotBlank() }
                ?: "Sync failed"
        )
    }

    fun recordSyncIssue(message: String) {
        val issue =
            SyncIssue(
                message = summarizeSyncIssueMessage(message).take(MAX_SYNC_MESSAGE_LENGTH),
                occurredAt = System.currentTimeMillis(),
                details = message.take(MAX_SYNC_DETAILS_LENGTH),
            )
        prefs
            .edit()
            .putString(serverScopedKey(KEY_SYNC_ISSUE_MESSAGE), issue.message)
            .putLong(serverScopedKey(KEY_SYNC_ISSUE_TIME), issue.occurredAt)
            .putString(serverScopedKey(KEY_SYNC_ISSUE_DETAILS), issue.details)
            .apply()
        _syncIssue.value = issue
    }

    fun clearSyncIssue() {
        val editor =
            prefs
                .edit()
                .remove(serverScopedKey(KEY_SYNC_ISSUE_MESSAGE))
                .remove(serverScopedKey(KEY_SYNC_ISSUE_TIME))
                .remove(serverScopedKey(KEY_SYNC_ISSUE_DETAILS))
        if (_activeServerId.value == LEGACY_SERVER_ID) {
            editor
                .remove(KEY_SYNC_ISSUE_MESSAGE)
                .remove(KEY_SYNC_ISSUE_TIME)
                .remove(KEY_SYNC_ISSUE_DETAILS)
        }
        editor.apply()
        _syncIssue.value = null
    }

    fun recordSuccessfulSync() {
        val timestamp = System.currentTimeMillis()
        prefs.edit().putLong(serverScopedKey(KEY_LAST_SUCCESSFUL_SYNC), timestamp).apply()
        _lastSuccessfulSyncAt.value = timestamp
        // A debug-only logcat marker the E2E/screenshot journeys grep for (see
        // NetBoxJourneyTest.waitForLogcatMarker) - this is the one moment a sync pass is known
        // good, unlike DashboardViewModel.showInitialSyncOverlay, which can toggle visible/hidden
        // several times during the chunked first sync (each of its ~8 steps has its own brief
        // isRefreshing=false gap) well before the overlay is gone for good.
        Timber.i(E2E_SYNC_COMPLETE_MARKER)
    }

    /** See [LastSyncSummary] - recorded alongside [recordSuccessfulSync] by the same clean pass. */
    fun recordSyncSummary(summary: LastSyncSummary) {
        prefs
            .edit()
            .putBoolean(serverScopedKey(KEY_LAST_SYNC_SUMMARY_FULL), summary.isFullSync)
            .putLong(serverScopedKey(KEY_LAST_SYNC_SUMMARY_DURATION), summary.durationMillis)
            .putInt(serverScopedKey(KEY_LAST_SYNC_SUMMARY_ITEMS), summary.itemsRefreshed)
            .apply()
        _lastSyncSummary.value = summary
    }

    fun addHiddenField(key: String) {
        val normalized = normalizeHiddenFieldPreferenceKey(key) ?: return
        val updated = _hiddenFieldKeys.value + normalized
        prefs.edit().putStringSet(KEY_HIDDEN_FIELDS, updated).apply()
        _hiddenFieldKeys.value = updated
    }

    fun removeHiddenField(key: String) {
        val updated = _hiddenFieldKeys.value - key
        prefs.edit().putStringSet(KEY_HIDDEN_FIELDS, updated).apply()
        _hiddenFieldKeys.value = updated
    }

    fun setSidebarAppOrder(order: List<String>) {
        val normalized = order.distinct().filter(String::isNotBlank)
        prefs
            .edit()
            .putString(KEY_SIDEBAR_APP_ORDER, normalized.joinToString(ORDER_SEPARATOR))
            .apply()
        _sidebarAppOrder.value = normalized
    }

    fun setSidebarModelOrder(appKey: String, order: List<String>) {
        val normalized = order.distinct().filter(String::isNotBlank)
        val updated = _sidebarModelOrders.value.toMutableMap()
        updated[appKey] = normalized
        prefs.edit().putString(KEY_SIDEBAR_MODEL_ORDERS, encodeModelOrders(updated)).apply()
        _sidebarModelOrders.value = updated
    }

    fun setSidebarAppHidden(appKey: String, hidden: Boolean) {
        val updated = updateStringSet(_hiddenSidebarApps.value, appKey, hidden)
        prefs.edit().putStringSet(KEY_HIDDEN_SIDEBAR_APPS, updated).apply()
        _hiddenSidebarApps.value = updated
    }

    fun setDashboardSectionOrder(order: List<String>) {
        val normalized = order.distinct().filter(String::isNotBlank)
        prefs
            .edit()
            .putString(KEY_DASHBOARD_SECTION_ORDER, normalized.joinToString(ORDER_SEPARATOR))
            .apply()
        _dashboardSectionOrder.value = normalized
    }

    fun setDashboardSectionHidden(sectionKey: String, hidden: Boolean) {
        val updated = updateStringSet(_hiddenDashboardSections.value, sectionKey, hidden)
        prefs.edit().putStringSet(KEY_HIDDEN_DASHBOARD_SECTIONS, updated).apply()
        _hiddenDashboardSections.value = updated
    }

    fun setStatsOrder(order: List<String>) {
        val normalized = order.distinct().filter(String::isNotBlank)
        prefs.edit().putString(KEY_STATS_ORDER, normalized.joinToString(ORDER_SEPARATOR)).apply()
        _statsOrder.value = normalized
    }

    fun setStatHidden(endpointPath: String, hidden: Boolean) {
        val updated = updateStringSet(_hiddenStats.value, endpointPath, hidden)
        prefs.edit().putStringSet(KEY_HIDDEN_STATS, updated).apply()
        _hiddenStats.value = updated
    }

    fun setShowTopologyDeviceTypeImages(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_TOPOLOGY_DEVICE_TYPE_IMAGES, enabled).apply()
        _showTopologyDeviceTypeImages.value = enabled
    }

    fun setTopologyNodePosition(nodeId: String, position: TopologyPosition) {
        if (nodeId.isBlank() || !position.x.isFinite() || !position.y.isFinite()) return
        val updated = _topologyNodePositions.value + (nodeId to position)
        prefs
            .edit()
            .putString(KEY_TOPOLOGY_NODE_POSITIONS, encodeTopologyNodePositions(updated))
            .apply()
        _topologyNodePositions.value = updated
    }

    fun setScheduledBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCHEDULED_BACKUP_ENABLED, enabled).apply()
        _scheduledBackupEnabled.value = enabled
    }

    fun setScheduledBackupFrequency(frequency: BackupFrequency) {
        prefs.edit().putString(KEY_SCHEDULED_BACKUP_FREQUENCY, frequency.storageKey).apply()
        _scheduledBackupFrequency.value = frequency
    }

    fun setScheduledBackupFolderUri(uri: String?) {
        val editor = prefs.edit()
        if (uri.isNullOrBlank()) editor.remove(KEY_SCHEDULED_BACKUP_FOLDER_URI)
        else editor.putString(KEY_SCHEDULED_BACKUP_FOLDER_URI, uri)
        editor.apply()
        _scheduledBackupFolderUri.value = uri?.takeIf { it.isNotBlank() }
    }

    fun setScheduledBackupPassword(password: String?) {
        val editor = prefs.edit()
        if (password.isNullOrEmpty()) editor.remove(KEY_SCHEDULED_BACKUP_PASSWORD)
        else editor.putString(KEY_SCHEDULED_BACKUP_PASSWORD, password)
        editor.apply()
        _scheduledBackupPasswordSet.value = !password.isNullOrEmpty()
    }

    fun scheduledBackupPassword(): String? =
        prefs.getString(KEY_SCHEDULED_BACKUP_PASSWORD, null)?.takeIf { it.isNotEmpty() }

    fun recordBackupSuccess() {
        val timestamp = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, timestamp).remove(KEY_BACKUP_ERROR).apply()
        _lastBackupAt.value = timestamp
        _backupError.value = null
    }

    fun recordBackupError(message: String) {
        val brief = message.trim().takeIf { it.isNotBlank() } ?: "Backup failed"
        prefs.edit().putString(KEY_BACKUP_ERROR, brief).apply()
        _backupError.value = brief
    }

    fun clearBackupError() {
        prefs.edit().remove(KEY_BACKUP_ERROR).apply()
        _backupError.value = null
    }

    fun togglePinned(endpointPath: String) {
        val current = _pinnedModelPaths.value
        val updated =
            when {
                endpointPath in current -> current - endpointPath
                current.size >= MAX_PINNED_MODEL_PATHS -> current
                else -> current + endpointPath
            }
        if (updated == current) return
        prefs.edit().putStringSet(KEY_PINNED_MODELS, updated).apply()
        _pinnedModelPaths.value = updated
    }

    fun setPinnedModelPaths(paths: Set<String>) {
        val normalized = paths.filter(String::isNotBlank).take(MAX_PINNED_MODEL_PATHS).toSet()
        prefs.edit().putStringSet(KEY_PINNED_MODELS, normalized).apply()
        _pinnedModelPaths.value = normalized
    }

    private fun loadPinnedModelPaths(): Set<String> = run {
        val stored = prefs.getStringSet(KEY_PINNED_MODELS, null)
        val migrated =
            if (prefs.getInt(KEY_PINNED_MODELS_VERSION, 0) < PINNED_MODEL_PATHS_VERSION) {
                val customPaths =
                    stored.orEmpty().filterNot { it in DEFAULT_PINNED_MODEL_PATHS }.sorted()
                (DEFAULT_PINNED_MODEL_PATHS + customPaths)
                    .distinct()
                    .take(MAX_PINNED_MODEL_PATHS)
                    .toSet()
            } else {
                (stored ?: DEFAULT_PINNED_MODEL_PATHS).take(MAX_PINNED_MODEL_PATHS).toSet()
            }
        if (prefs.getInt(KEY_PINNED_MODELS_VERSION, 0) < PINNED_MODEL_PATHS_VERSION) {
            prefs
                .edit()
                .putStringSet(KEY_PINNED_MODELS, migrated)
                .putInt(KEY_PINNED_MODELS_VERSION, PINNED_MODEL_PATHS_VERSION)
                .apply()
        }
        migrated
    }

    fun save(baseUrl: String, token: String) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val trimmedToken = token.trim()
        val current = activeServerProfile()
        val profile =
            current?.copy(baseUrl = normalizedBaseUrl, token = trimmedToken)
                ?: newServerProfile(normalizedBaseUrl, trimmedToken)
        val profiles =
            if (current == null) listOf(profile)
            else _serverProfiles.value.map { if (it.id == profile.id) profile else it }
        persistServerProfiles(profiles)
        if (current == null) {
            prefs.edit().putString(KEY_ACTIVE_SERVER_ID, profile.id).apply()
            _activeServerId.value = profile.id
        }
        _activeServer.value = profile
        clearCurrentUser()
        clearServerVersion()
        _credentials.value = NetBoxCredentials(normalizedBaseUrl, trimmedToken)
    }

    /** Adds a profile without activating it. The caller can validate, then call [switchServer]. */
    fun addServer(baseUrl: String, token: String, displayName: String? = null): ServerProfile {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val profile =
            newServerProfile(
                normalizedBaseUrl,
                token.trim(),
                displayName?.trim()?.takeIf { it.isNotBlank() },
            )
        persistServerProfiles(_serverProfiles.value + profile)
        return profile
    }

    fun updateServer(
        id: String,
        baseUrl: String,
        token: String,
        displayName: String?,
    ): ServerProfile? {
        val existing = _serverProfiles.value.firstOrNull { it.id == id } ?: return null
        val updated =
            existing.copy(
                baseUrl = baseUrl.trim().trimEnd('/'),
                token = token.trim(),
                displayName =
                    displayName?.trim()?.takeIf { it.isNotBlank() } ?: existing.displayName,
            )
        persistServerProfiles(_serverProfiles.value.map { if (it.id == id) updated else it })
        if (_activeServerId.value == id) {
            _activeServer.value = updated
            clearCurrentUser()
            clearServerVersion()
            _credentials.value = updated.credentials
        }
        return updated
    }

    fun renameServer(id: String, displayName: String) {
        val normalized = displayName.trim().takeIf { it.isNotBlank() } ?: return
        persistServerProfiles(
            _serverProfiles.value.map { if (it.id == id) it.copy(displayName = normalized) else it }
        )
    }

    /** Selects the active profile while leaving every profile's Room/media cache untouched. */
    fun switchServer(id: String): ServerProfile? {
        val profile = _serverProfiles.value.firstOrNull { it.id == id } ?: return null
        if (_activeServerId.value == id) return profile
        prefs.edit().putString(KEY_ACTIVE_SERVER_ID, id).apply()
        _activeServerId.value = id
        _activeServer.value = profile
        _credentials.value = profile.credentials
        _currentUser.value = loadCurrentUser()
        _serverVersion.value = loadServerVersion()
        _syncIssue.value = loadSyncIssue()
        _lastSuccessfulSyncAt.value = loadLastSuccessfulSyncAt()
        _lastSyncSummary.value = loadLastSyncSummary()
        return profile
    }

    /** Removes only the profile metadata. The cache is deleted by the caller after confirmation. */
    fun removeServer(id: String): ServerProfile? {
        val removed = _serverProfiles.value.firstOrNull { it.id == id } ?: return null
        val remaining = _serverProfiles.value.filterNot { it.id == id }
        persistServerProfiles(remaining)
        if (_activeServerId.value == id) {
            val next = remaining.firstOrNull()
            prefs.edit().putString(KEY_ACTIVE_SERVER_ID, next?.id).apply()
            _activeServerId.value = next?.id
            _activeServer.value = next
            _credentials.value = next?.credentials ?: NetBoxCredentials("", "")
            _currentUser.value = loadCurrentUser()
            _serverVersion.value = loadServerVersion()
            _syncIssue.value = loadSyncIssue()
            _lastSuccessfulSyncAt.value = loadLastSuccessfulSyncAt()
            _lastSyncSummary.value = loadLastSyncSummary()
        }
        return removed
    }

    fun setCurrentUser(user: NetBoxUserIdentity) {
        prefs
            .edit()
            .putString(serverScopedKey(KEY_CURRENT_USER_BASE_URL), credentials.value.baseUrl)
            .putString(serverScopedKey(KEY_CURRENT_USER_NAME), user.username)
            .putString(serverScopedKey(KEY_CURRENT_USER_FULL_NAME), user.fullName)
            .putString(serverScopedKey(KEY_CURRENT_USER_EMAIL), user.email)
            .putString(serverScopedKey(KEY_CURRENT_USER_ID), user.id?.toString())
            .apply()
        _currentUser.value = user
    }

    fun setServerVersion(version: String) {
        val normalized = version.trim().takeIf { it.isNotBlank() } ?: return
        prefs
            .edit()
            .putString(serverScopedKey(KEY_SERVER_VERSION_BASE_URL), credentials.value.baseUrl)
            .putString(serverScopedKey(KEY_SERVER_VERSION), normalized)
            .apply()
        _serverVersion.value = normalized
    }

    fun clearServerVersion() {
        val editor =
            prefs
                .edit()
                .remove(serverScopedKey(KEY_SERVER_VERSION_BASE_URL))
                .remove(serverScopedKey(KEY_SERVER_VERSION))
        if (_activeServerId.value == LEGACY_SERVER_ID) {
            editor.remove(KEY_SERVER_VERSION_BASE_URL).remove(KEY_SERVER_VERSION)
        }
        editor.apply()
        _serverVersion.value = null
    }

    fun clearCurrentUser() {
        val editor =
            prefs
                .edit()
                .remove(serverScopedKey(KEY_CURRENT_USER_BASE_URL))
                .remove(serverScopedKey(KEY_CURRENT_USER_NAME))
                .remove(serverScopedKey(KEY_CURRENT_USER_FULL_NAME))
                .remove(serverScopedKey(KEY_CURRENT_USER_EMAIL))
                .remove(serverScopedKey(KEY_CURRENT_USER_ID))
        if (_activeServerId.value == LEGACY_SERVER_ID) {
            editor
                .remove(KEY_CURRENT_USER_BASE_URL)
                .remove(KEY_CURRENT_USER_NAME)
                .remove(KEY_CURRENT_USER_FULL_NAME)
                .remove(KEY_CURRENT_USER_EMAIL)
                .remove(KEY_CURRENT_USER_ID)
        }
        editor.apply()
        _currentUser.value = null
    }

    fun clear() {
        prefs.edit().clear().apply()
        _serverProfiles.value = emptyList()
        _activeServerId.value = null
        _activeServer.value = null
        _credentials.value = NetBoxCredentials("", "")
        _currentUser.value = null
        _serverVersion.value = null
        _offlineMode.value = false
        _printSettings.value = PrintSettings()
        _themeMode.value = ThemeMode.FollowSystem
        _themeAccent.value = ThemeAccent.System
        _objectTypeAccents.value = emptyMap()
        _gestureActions.value = defaultGestureActions()
        _gestureTargets.value = emptyMap()
        _hiddenFieldKeys.value = emptySet()
        _hiddenSidebarApps.value = emptySet()
        _sidebarAppOrder.value = emptyList()
        _sidebarModelOrders.value = emptyMap()
        _dashboardSectionOrder.value = emptyList()
        _hiddenDashboardSections.value = DEFAULT_HIDDEN_DASHBOARD_SECTIONS
        _statsOrder.value = emptyList()
        _hiddenStats.value = DEFAULT_HIDDEN_STATS
        _showTopologyDeviceTypeImages.value = true
        _topologyNodePositions.value = emptyMap()
        _changeNotificationsEnabled.value = false
        _changeNotificationFilters.value = setOf(ChangeNotificationFilter.All.storageKey)
        _syncAttachmentsToDisk.value = DEFAULT_SYNC_ATTACHMENTS_TO_DISK
        _syncOnlyOnWifi.value = false
        _syncWhileRoaming.value = true
        _syncOnAppLaunch.value = true
        _syncConcurrency.value = DEFAULT_SYNC_CONCURRENCY
        _syncOnlyWhenCharging.value = false
        _syncIntervalHours.value = DEFAULT_SYNC_INTERVAL_HOURS
        _scheduledBackupEnabled.value = false
        _scheduledBackupFrequency.value = BackupFrequency.Weekly
        _scheduledBackupFolderUri.value = null
        _scheduledBackupPasswordSet.value = false
        _lastBackupAt.value = null
        _backupError.value = null
        clearSyncIssue()
    }

    /** Restores only portable settings; Room databases and downloaded files are untouched. */
    fun restoreBackupSettings(settings: SettingsBackupSettings) {
        clear()
        if (settings.serverProfiles.isNotEmpty()) {
            val profiles = settings.serverProfiles.distinctBy { it.id }
            persistServerProfiles(profiles)
            val activeId =
                settings.activeServerId?.takeIf { id -> profiles.any { it.id == id } }
                    ?: profiles.first().id
            prefs.edit().putString(KEY_ACTIVE_SERVER_ID, activeId).apply()
            _serverProfiles.value = profiles
            _activeServerId.value = activeId
            _activeServer.value = profiles.first { it.id == activeId }
            _credentials.value = _activeServer.value?.credentials ?: NetBoxCredentials("", "")
        }
        setSyncAttachmentsToDisk(settings.syncAttachmentsToDisk)
        setSyncOnlyOnWifi(settings.syncOnlyOnWifi)
        setSyncWhileRoaming(settings.syncWhileRoaming)
        setSyncOnAppLaunch(settings.syncOnAppLaunch)
        setSyncConcurrency(settings.syncConcurrency)
        setSyncOnlyWhenCharging(settings.syncOnlyWhenCharging)
        setSyncIntervalHours(settings.syncIntervalHours)
        setChangeNotificationsEnabled(settings.changeNotificationsEnabled)
        settings.changeNotificationFilters.forEach { key ->
            ChangeNotificationFilter.fromStorage(key)?.let { setChangeNotificationFilter(it, true) }
        }
        settings.gestureActions.forEach { (shortcutKey, actionKey) ->
            val shortcut = GestureShortcut.entries.firstOrNull { it.storageKey == shortcutKey }
            val action = GestureAction.fromStorage(actionKey, GestureAction.Off)
            shortcut?.let { setGestureAction(it, action) }
        }
        settings.gestureTargets.forEach { (shortcutKey, target) ->
            GestureShortcut.entries
                .firstOrNull { it.storageKey == shortcutKey }
                ?.let { setGestureTarget(it, target) }
        }
        if (settings.navBarItems.isNotEmpty()) setNavBarItems(settings.navBarItems)
        setScannerLens(ScannerLens.fromStorage(settings.scannerLens))
        setScannerRearLens(ScannerRearLens.fromStorage(settings.scannerRearLens))
        setScannerResolution(ScannerResolution.fromStorage(settings.scannerResolution))
        setThemeMode(ThemeMode.fromStorage(settings.themeMode))
        setThemeAccent(ThemeAccent.fromStorage(settings.themeAccent))
        settings.objectTypeAccents.forEach { (path, accentKey) ->
            setObjectTypeAccent(path, ThemeAccent.fromStorage(accentKey))
        }
        updatePrintSettings(settings.printSettings)
        setOfflineMode(settings.offlineMode)
        settings.hiddenFieldKeys.forEach(::addHiddenField)
        setPinnedModelPaths(settings.pinnedModelPaths)
        setSidebarAppOrder(settings.sidebarAppOrder)
        settings.sidebarModelOrders.forEach { (appKey, order) ->
            setSidebarModelOrder(appKey, order)
        }
        settings.hiddenSidebarApps.forEach { setSidebarAppHidden(it, true) }
        setDashboardSectionOrder(settings.dashboardSectionOrder)
        settings.hiddenDashboardSections.forEach { setDashboardSectionHidden(it, true) }
        setStatsOrder(settings.statsOrder)
        settings.hiddenStats.forEach { setStatHidden(it, true) }
        setShowTopologyDeviceTypeImages(settings.showTopologyDeviceTypeImages)
        settings.topologyNodePositions.forEach { (nodeId, position) ->
            setTopologyNodePosition(nodeId, position)
        }
        setScheduledBackupEnabled(settings.scheduledBackupEnabled)
        setScheduledBackupFrequency(BackupFrequency.fromStorage(settings.scheduledBackupFrequency))
        setScheduledBackupFolderUri(settings.scheduledBackupFolderUri)
        clearCurrentUser()
    }

    private fun loadServerProfiles(): List<ServerProfile> {
        val stored = prefs.getString(KEY_SERVER_PROFILES, null)
        if (!stored.isNullOrBlank()) {
            runCatching {
                settingsJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(ServerProfile.serializer()),
                    stored,
                )
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    return it
                }
        }

        // Migrate the pre-1.2 single-server settings without touching its existing Room file.
        val baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().trim().trimEnd('/')
        val token = prefs.getString(KEY_TOKEN, "").orEmpty().trim()
        if (baseUrl.isBlank() || token.isBlank()) return emptyList()
        val profile =
            ServerProfile(
                id = LEGACY_SERVER_ID,
                displayName = displayNameFor(baseUrl),
                baseUrl = baseUrl,
                token = token,
                cacheDatabaseName = LEGACY_DATABASE_NAME,
                cacheNamespace = LEGACY_CACHE_NAMESPACE,
            )
        prefs
            .edit()
            .putString(
                KEY_SERVER_PROFILES,
                settingsJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ServerProfile.serializer()),
                    listOf(profile),
                ),
            )
            .putString(KEY_ACTIVE_SERVER_ID, profile.id)
            .apply()
        return listOf(profile)
    }

    private fun loadActiveServerId(): String? {
        val stored = prefs.getString(KEY_ACTIVE_SERVER_ID, null)
        return _serverProfiles.value.firstOrNull { it.id == stored }?.id
            ?: _serverProfiles.value.firstOrNull()?.id
    }

    private fun activeServerProfile(): ServerProfile? =
        _serverProfiles.value.firstOrNull { it.id == _activeServerId.value }
            ?: _serverProfiles.value.firstOrNull()

    private fun credentialsOrEmpty(): NetBoxCredentials =
        activeServerProfile()?.credentials ?: NetBoxCredentials("", "")

    private fun newServerProfile(
        baseUrl: String,
        token: String,
        displayName: String? = null,
    ): ServerProfile {
        val id = UUID.randomUUID().toString()
        return ServerProfile(
            id = id,
            displayName = displayName ?: displayNameFor(baseUrl),
            baseUrl = baseUrl,
            token = token,
            cacheDatabaseName = "nyetbox-$id.db",
            cacheNamespace = id,
        )
    }

    private fun persistServerProfiles(profiles: List<ServerProfile>) {
        prefs
            .edit()
            .putString(
                KEY_SERVER_PROFILES,
                settingsJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(ServerProfile.serializer()),
                    profiles,
                ),
            )
            .apply()
        _serverProfiles.value = profiles
        _activeServer.value = activeServerProfile()
    }

    private fun serverScopedKey(key: String): String =
        "${key}:${_activeServerId.value ?: LEGACY_SERVER_ID}"

    private fun displayNameFor(baseUrl: String): String =
        baseUrl.substringAfter("://", baseUrl).substringBefore('/').ifBlank { baseUrl }

    private fun loadCredentials(): NetBoxCredentials =
        activeServerProfile()?.credentials ?: NetBoxCredentials("", "")

    private fun loadCurrentUser(): NetBoxUserIdentity? {
        val baseUrl = credentialsOrEmpty().baseUrl
        val useLegacyKeys = _activeServerId.value == LEGACY_SERVER_ID
        val storedBaseUrl =
            prefs.getString(serverScopedKey(KEY_CURRENT_USER_BASE_URL), null)
                ?: if (useLegacyKeys) prefs.getString(KEY_CURRENT_USER_BASE_URL, null) else null
        val username =
            prefs.getString(serverScopedKey(KEY_CURRENT_USER_NAME), null)?.takeIf {
                it.isNotBlank()
            }
                ?: if (useLegacyKeys) {
                    prefs.getString(KEY_CURRENT_USER_NAME, null)?.takeIf { it.isNotBlank() }
                } else null
        if (baseUrl.isBlank() || storedBaseUrl != baseUrl || username == null) return null
        return NetBoxUserIdentity(
            username = username,
            fullName =
                prefs.getString(serverScopedKey(KEY_CURRENT_USER_FULL_NAME), null)
                    ?: if (useLegacyKeys) prefs.getString(KEY_CURRENT_USER_FULL_NAME, null)
                    else null,
            email =
                prefs.getString(serverScopedKey(KEY_CURRENT_USER_EMAIL), null)
                    ?: if (useLegacyKeys) prefs.getString(KEY_CURRENT_USER_EMAIL, null) else null,
            id =
                (prefs.getString(serverScopedKey(KEY_CURRENT_USER_ID), null)
                        ?: if (useLegacyKeys) prefs.getString(KEY_CURRENT_USER_ID, null) else null)
                    ?.toIntOrNull(),
        )
    }

    private fun loadServerVersion(): String? {
        val useLegacyKeys = _activeServerId.value == LEGACY_SERVER_ID
        val storedBaseUrl =
            prefs.getString(serverScopedKey(KEY_SERVER_VERSION_BASE_URL), null)
                ?: if (useLegacyKeys) prefs.getString(KEY_SERVER_VERSION_BASE_URL, null) else null
        if (storedBaseUrl != credentialsOrEmpty().baseUrl) return null
        return prefs.getString(serverScopedKey(KEY_SERVER_VERSION), null)?.takeIf {
            it.isNotBlank()
        }
            ?: if (useLegacyKeys) {
                prefs.getString(KEY_SERVER_VERSION, null)?.takeIf { it.isNotBlank() }
            } else null
    }

    private fun loadSyncIssue(): SyncIssue? {
        val message =
            prefs.getString(serverScopedKey(KEY_SYNC_ISSUE_MESSAGE), null)?.takeIf {
                it.isNotBlank()
            }
                ?: if (_activeServerId.value == LEGACY_SERVER_ID) {
                    prefs.getString(KEY_SYNC_ISSUE_MESSAGE, null)?.takeIf { it.isNotBlank() }
                } else null
        val occurredAt =
            prefs.getLong(
                serverScopedKey(KEY_SYNC_ISSUE_TIME),
                if (_activeServerId.value == LEGACY_SERVER_ID) {
                    prefs.getLong(KEY_SYNC_ISSUE_TIME, 0L)
                } else 0L,
            )
        val details =
            prefs.getString(serverScopedKey(KEY_SYNC_ISSUE_DETAILS), null)?.takeIf {
                it.isNotBlank()
            }
                ?: if (_activeServerId.value == LEGACY_SERVER_ID) {
                    prefs.getString(KEY_SYNC_ISSUE_DETAILS, null)?.takeIf { it.isNotBlank() }
                } else null
        return if (message != null && occurredAt > 0L) {
            SyncIssue(summarizeSyncIssueMessage(message), occurredAt, details ?: message)
        } else null
    }

    private fun loadLastSuccessfulSyncAt(): Long? =
        prefs
            .getLong(
                serverScopedKey(KEY_LAST_SUCCESSFUL_SYNC),
                if (_activeServerId.value == LEGACY_SERVER_ID) {
                    prefs.getLong(KEY_LAST_SUCCESSFUL_SYNC, 0L)
                } else 0L,
            )
            .takeIf { it > 0L }

    private fun loadLastSyncSummary(): LastSyncSummary? {
        if (!prefs.contains(serverScopedKey(KEY_LAST_SYNC_SUMMARY_DURATION))) return null
        return LastSyncSummary(
            isFullSync = prefs.getBoolean(serverScopedKey(KEY_LAST_SYNC_SUMMARY_FULL), false),
            durationMillis = prefs.getLong(serverScopedKey(KEY_LAST_SYNC_SUMMARY_DURATION), 0L),
            itemsRefreshed = prefs.getInt(serverScopedKey(KEY_LAST_SYNC_SUMMARY_ITEMS), 0),
        )
    }

    private fun loadThemeMode(): ThemeMode =
        ThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, ThemeMode.FollowSystem.storageKey))

    private fun loadThemeAccent(): ThemeAccent =
        ThemeAccent.fromStorage(prefs.getString(KEY_THEME_ACCENT, ThemeAccent.System.storageKey))

    private fun loadObjectTypeAccents(): Map<String, ThemeAccent> =
        prefs.all
            .mapNotNull { (key, value) ->
                if (!key.startsWith(KEY_OBJECT_TYPE_ACCENT_PREFIX) || value !is String) {
                    return@mapNotNull null
                }
                val endpointPath = key.removePrefix(KEY_OBJECT_TYPE_ACCENT_PREFIX)
                ThemeAccent.fromStorage(value)
                    .takeIf { it != ThemeAccent.System }
                    ?.let { endpointPath to it }
            }
            .toMap()

    private fun loadGestureActions(): Map<GestureShortcut, GestureAction> =
        GestureShortcut.entries.associateWith { shortcut ->
            val default =
                if (shortcut == GestureShortcut.TwoFingerDown) {
                    GestureAction.GlobalSearch
                } else {
                    GestureAction.Off
                }
            GestureAction.fromStorage(
                prefs.getString(gesturePreferenceKey(shortcut), default.storageKey),
                default,
            )
        }

    private fun defaultGestureActions(): Map<GestureShortcut, GestureAction> =
        GestureShortcut.entries.associateWith { shortcut ->
            if (shortcut == GestureShortcut.TwoFingerDown) GestureAction.GlobalSearch
            else GestureAction.Off
        }

    private fun loadGestureTargets(): Map<GestureShortcut, GestureTarget> =
        GestureShortcut.entries
            .mapNotNull { shortcut ->
                prefs.getString(gestureTargetKey(shortcut), null)?.let(::decodeGestureTarget)?.let {
                    shortcut to it
                }
            }
            .toMap()

    private fun loadNavBarItems(): List<NavBarItem> {
        val stored = prefs.getString(KEY_NAV_BAR_ITEMS, null)
        if (!stored.isNullOrBlank()) {
            runCatching {
                settingsJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(NavBarItem.serializer()),
                    stored,
                )
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    return it.take(MAX_NAV_BAR_ITEMS)
                }
        }
        return defaultNavBarItems()
    }

    private fun defaultNavBarItems(): List<NavBarItem> =
        listOf(
            NavBarItem(GestureAction.Dashboard),
            NavBarItem(GestureAction.GlobalSearch),
            NavBarItem(GestureAction.Scanner),
            NavBarItem(GestureAction.Add),
        )

    private fun loadShortcutItems(): List<NavBarItem> {
        val stored = prefs.getString(KEY_SHORTCUT_ITEMS, null)
        if (!stored.isNullOrBlank()) {
            runCatching {
                settingsJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(NavBarItem.serializer()),
                    stored,
                )
            }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    return it.take(MAX_SHORTCUT_ITEMS)
                }
        }
        return defaultShortcutItems()
    }

    // Mirrors defaultNavBarItems() minus Dashboard (see GestureAction.shortcutable) - the same
    // three non-Dashboard actions the nav bar already defaults to, so day-one shortcuts feel like
    // a natural extension of what's already on screen rather than a new concept to learn.
    private fun defaultShortcutItems(): List<NavBarItem> =
        listOf(
            NavBarItem(GestureAction.GlobalSearch),
            NavBarItem(GestureAction.Scanner),
            NavBarItem(GestureAction.Add),
        )

    private fun gesturePreferenceKey(shortcut: GestureShortcut): String =
        if (shortcut == GestureShortcut.TwoFingerDown) {
            KEY_GESTURE_ACTION
        } else {
            "gesture_action_${shortcut.storageKey}"
        }

    private fun gestureTargetKey(shortcut: GestureShortcut): String =
        "gesture_target_${shortcut.storageKey}"

    private fun encodeGestureTarget(target: GestureTarget): String = buildString {
        append(target.endpointPath)
        append(TARGET_SEPARATOR)
        append(target.label)
        target.id?.let {
            append(TARGET_SEPARATOR)
            append(it)
        }
    }

    private fun decodeGestureTarget(value: String): GestureTarget? {
        val parts = value.split(TARGET_SEPARATOR, limit = 3)
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return GestureTarget(parts[0], parts[1], parts.getOrNull(2)?.toIntOrNull())
    }

    private fun loadScannerLens(): ScannerLens =
        ScannerLens.fromStorage(prefs.getString(KEY_SCANNER_LENS, ScannerLens.Back.storageKey))

    private fun loadScannerRearLens(): ScannerRearLens =
        ScannerRearLens.fromStorage(
            prefs.getString(KEY_SCANNER_REAR_LENS, ScannerRearLens.Automatic.storageKey)
        )

    private fun loadScannerResolution(): ScannerResolution =
        ScannerResolution.fromStorage(
            prefs.getString(KEY_SCANNER_RESOLUTION, ScannerResolution.Auto.storageKey)
        )

    private fun loadPrintSettings(): PrintSettings =
        PrintSettings(
                defaultPrinterName = prefs.getString(KEY_DEFAULT_PRINTER_NAME, null),
                defaultPrinterAddress = prefs.getString(KEY_DEFAULT_PRINTER_ADDRESS, null),
                invertColors = prefs.getBoolean(KEY_PRINT_INVERT_COLORS, true),
                verticalText = prefs.getBoolean(KEY_PRINT_VERTICAL_TEXT, false),
                longLabel = prefs.getBoolean(KEY_PRINT_LONG_LABEL, false),
                copies = prefs.getInt(KEY_PRINT_COPIES, 1),
                qrSize = prefs.getInt(KEY_PRINT_QR_SIZE, 64),
            )
            .normalized()

    private fun loadChangeNotificationFilters(): Set<String> {
        val stored = prefs.getStringSet(KEY_CHANGE_NOTIFICATION_FILTERS, null)
        if (stored == null) return setOf(ChangeNotificationFilter.All.storageKey)
        return stored.mapNotNull { ChangeNotificationFilter.fromStorage(it)?.storageKey }.toSet()
    }

    private fun persistChangeNotificationFilters(filters: Set<String>) {
        prefs.edit().putStringSet(KEY_CHANGE_NOTIFICATION_FILTERS, filters).apply()
        _changeNotificationFilters.value = filters
    }

    private fun loadHiddenFieldKeys(): Set<String> =
        prefs
            .getStringSet(KEY_HIDDEN_FIELDS, null)
            .orEmpty()
            .mapNotNull(::normalizeHiddenFieldPreferenceKey)
            .toSet()

    private fun loadOrder(key: String): List<String> =
        prefs.getString(key, null).orEmpty().split(ORDER_SEPARATOR).filter(String::isNotBlank)

    private fun loadModelOrders(): Map<String, List<String>> =
        prefs
            .getString(KEY_SIDEBAR_MODEL_ORDERS, null)
            .orEmpty()
            .split(MODEL_ENTRY_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(ORDER_SEPARATOR, limit = 2)
                if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
                parts[0] to parts[1].split(ITEM_SEPARATOR).filter(String::isNotBlank)
            }
            .toMap()

    private fun loadHiddenSidebarApps(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_SIDEBAR_APPS, null).orEmpty().toSet()

    private fun loadHiddenDashboardSections(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_DASHBOARD_SECTIONS, null)?.toSet()
            ?: DEFAULT_HIDDEN_DASHBOARD_SECTIONS

    private fun loadHiddenStats(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_STATS, null)?.toSet() ?: DEFAULT_HIDDEN_STATS

    private fun loadTopologyNodePositions(): Map<String, TopologyPosition> =
        prefs
            .getString(KEY_TOPOLOGY_NODE_POSITIONS, null)
            .orEmpty()
            .split(ITEM_SEPARATOR)
            .mapNotNull { entry ->
                val parts = entry.split(TARGET_SEPARATOR, limit = 3)
                val x = parts.getOrNull(1)?.toFloatOrNull()
                val y = parts.getOrNull(2)?.toFloatOrNull()
                if (
                    parts.size == 3 &&
                        parts[0].isNotBlank() &&
                        x?.isFinite() == true &&
                        y?.isFinite() == true
                ) {
                    parts[0] to TopologyPosition(x, y)
                } else null
            }
            .toMap()

    private fun updateStringSet(current: Set<String>, key: String, enabled: Boolean): Set<String> =
        if (key.isBlank()) current else if (enabled) current + key else current - key

    private fun encodeModelOrders(orders: Map<String, List<String>>): String =
        orders.entries.joinToString(MODEL_ENTRY_SEPARATOR) { (appKey, modelKeys) ->
            appKey + ORDER_SEPARATOR + modelKeys.joinToString(ITEM_SEPARATOR)
        }

    private fun encodeTopologyNodePositions(positions: Map<String, TopologyPosition>): String =
        positions.entries.joinToString(ITEM_SEPARATOR) { (id, position) ->
            id + TARGET_SEPARATOR + position.x + TARGET_SEPARATOR + position.y
        }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_SERVER_PROFILES = "server_profiles"
        const val KEY_ACTIVE_SERVER_ID = "active_server_id"
        const val LEGACY_SERVER_ID = "legacy"
        const val LEGACY_DATABASE_NAME = "nyetbox.db"
        const val LEGACY_CACHE_NAMESPACE = "legacy"
        const val KEY_CURRENT_USER_BASE_URL = "current_user_base_url"
        const val KEY_CURRENT_USER_NAME = "current_user_name"
        const val KEY_CURRENT_USER_FULL_NAME = "current_user_full_name"
        const val KEY_CURRENT_USER_EMAIL = "current_user_email"
        const val KEY_CURRENT_USER_ID = "current_user_id"
        const val KEY_SERVER_VERSION_BASE_URL = "server_version_base_url"
        const val KEY_SERVER_VERSION = "server_version"
        const val KEY_PINNED_MODELS = "pinned_model_paths"
        const val KEY_PINNED_MODELS_VERSION = "pinned_model_paths_version"
        val DEFAULT_PINNED_MODEL_PATHS =
            listOf(NetBoxRef.DEVICES_ENDPOINT_PATH, NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH)
        const val PINNED_MODEL_PATHS_VERSION = 1
        const val MAX_PINNED_MODEL_PATHS = 5
        const val KEY_NAV_BAR_ITEMS = "nav_bar_items"
        const val MAX_NAV_BAR_ITEMS = 5
        const val KEY_SHORTCUT_ITEMS = "shortcut_items"
        const val MAX_SHORTCUT_ITEMS = 4
        const val KEY_SYNC_ATTACHMENTS = "sync_attachments_to_disk"
        const val KEY_SYNC_ONLY_ON_WIFI = "sync_only_on_wifi"
        const val KEY_SYNC_WHILE_ROAMING = "sync_while_roaming"
        const val KEY_SYNC_ON_APP_LAUNCH = "sync_on_app_launch"
        const val KEY_SYNC_CONCURRENCY = "sync_concurrency"
        const val DEFAULT_SYNC_CONCURRENCY = 3
        const val KEY_SYNC_ONLY_WHEN_CHARGING = "sync_only_when_charging"
        const val KEY_SYNC_INTERVAL_HOURS = "sync_interval_hours"
        const val KEY_LAST_FULL_SYNC_AT = "last_full_sync_at"
        const val KEY_CHANGE_NOTIFICATIONS_ENABLED = "change_notifications_enabled"
        const val KEY_CHANGE_NOTIFICATION_FILTERS = "change_notification_filters"
        const val KEY_CHANGE_NOTIFICATION_CURSOR = "change_notification_cursor"
        const val KEY_GESTURE_ACTION = "two_finger_swipe_action"
        const val TARGET_SEPARATOR = "\u001F"
        const val KEY_SCANNER_LENS = "scanner_default_lens"
        const val KEY_SCANNER_REAR_LENS = "scanner_default_rear_lens"
        const val KEY_SCANNER_RESOLUTION = "scanner_resolution"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_THEME_ACCENT = "theme_accent"
        const val KEY_OBJECT_TYPE_ACCENT_PREFIX = "object_type_accent:"
        const val KEY_DEFAULT_PRINTER_NAME = "default_printer_name"
        const val KEY_DEFAULT_PRINTER_ADDRESS = "default_printer_address"
        const val KEY_PRINT_INVERT_COLORS = "print_invert_colors"
        const val KEY_PRINT_VERTICAL_TEXT = "print_vertical_text"
        const val KEY_PRINT_LONG_LABEL = "print_long_label"
        const val KEY_PRINT_COPIES = "print_copies"
        const val KEY_PRINT_QR_SIZE = "print_qr_size"
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val KEY_SYNC_ISSUE_MESSAGE = "sync_issue_message"
        const val KEY_SYNC_ISSUE_TIME = "sync_issue_time"
        const val KEY_SYNC_ISSUE_DETAILS = "sync_issue_details"
        const val KEY_LAST_SUCCESSFUL_SYNC = "last_successful_sync"
        const val KEY_LAST_SYNC_SUMMARY_FULL = "last_sync_summary_full"
        const val KEY_LAST_SYNC_SUMMARY_DURATION = "last_sync_summary_duration"
        const val KEY_LAST_SYNC_SUMMARY_ITEMS = "last_sync_summary_items"
        const val MAX_SYNC_MESSAGE_LENGTH = 1000
        const val MAX_SYNC_DETAILS_LENGTH = 4000
        const val KEY_HIDDEN_FIELDS = "hidden_field_keys"
        const val KEY_SIDEBAR_APP_ORDER = "sidebar_app_order"
        const val KEY_SIDEBAR_MODEL_ORDERS = "sidebar_model_orders"
        const val KEY_HIDDEN_SIDEBAR_APPS = "hidden_sidebar_apps"
        const val KEY_DASHBOARD_SECTION_ORDER = "dashboard_section_order"
        const val KEY_HIDDEN_DASHBOARD_SECTIONS = "hidden_dashboard_sections"
        const val KEY_STATS_ORDER = "stats_order"
        const val KEY_HIDDEN_STATS = "hidden_stats"
        const val KEY_SHOW_TOPOLOGY_DEVICE_TYPE_IMAGES = "show_topology_device_type_images"
        const val KEY_TOPOLOGY_NODE_POSITIONS = "topology_node_positions"
        const val KEY_SCHEDULED_BACKUP_ENABLED = "scheduled_backup_enabled"
        const val KEY_SCHEDULED_BACKUP_FREQUENCY = "scheduled_backup_frequency"
        const val KEY_SCHEDULED_BACKUP_FOLDER_URI = "scheduled_backup_folder_uri"
        const val KEY_SCHEDULED_BACKUP_PASSWORD = "scheduled_backup_password"
        const val KEY_LAST_BACKUP_AT = "last_backup_at"
        const val KEY_BACKUP_ERROR = "backup_error"
        val DEFAULT_HIDDEN_DASHBOARD_SECTIONS = setOf("news")
        // Only the four stat tiles that used to be the entire, hardcoded set (Devices, Device
        // Types, Sites, Racks) stay visible by default (NBC-437) - the rest of the shared
        // core-model registry is opt-in, so an existing install's dashboard doesn't suddenly
        // sprout five new tiles it never asked for.
        val DEFAULT_HIDDEN_STATS =
            NetBoxEndpointCatalog.coreModels.drop(4).map { it.endpointPath }.toSet()
        const val ORDER_SEPARATOR = "\u001F"
        const val ITEM_SEPARATOR = "\u001E"
        const val MODEL_ENTRY_SEPARATOR = "\u001D"
    }

    private fun objectTypeAccentKey(endpointPath: String): String =
        KEY_OBJECT_TYPE_ACCENT_PREFIX + endpointPath
}
