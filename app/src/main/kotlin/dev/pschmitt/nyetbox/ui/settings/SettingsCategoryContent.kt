package dev.pschmitt.nyetbox.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.R
import dev.pschmitt.nyetbox.data.db.NetBoxModelEntity
import dev.pschmitt.nyetbox.data.db.NetBoxObjectEntity
import dev.pschmitt.nyetbox.data.repository.*
import dev.pschmitt.nyetbox.sync.SyncProgress
import dev.pschmitt.nyetbox.ui.common.RotatingSyncIcon
import dev.pschmitt.nyetbox.ui.common.SyncIssueCard
import dev.pschmitt.nyetbox.ui.common.SyncStatusCard
import dev.pschmitt.nyetbox.ui.common.iconForGestureAction
import kotlinx.coroutines.flow.Flow

internal fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KiB".format(bytes / 1024.0)
        bytes < 1024L * 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

/**
 * The "Cached data" row's supporting text: live attachment-download progress (NBC-331) while the
 * sync's attachment phase (see `OfflineSyncRepository.syncAttachments`'s `itemLabel =
 * "images/documents"`) is running, prepended to the usual cache totals - falling back to just the
 * cache totals once that phase finishes (or the sync fails), since `SettingsViewModel` refreshes
 * those totals as soon as `isSyncing` goes false. Takes plain values rather than the whole
 * [SettingsCategoryState] so it stays trivially unit-testable.
 */
internal fun cachedDataSupportingText(
    isSyncing: Boolean,
    syncProgress: SyncProgress?,
    cachedDeviceCount: Int,
    cachedObjectCount: Int,
    cachedImageCount: Int,
    persistentCacheFiles: Int,
    persistentCacheBytes: Long,
): String {
    val liveAttachmentProgress =
        if (isSyncing && syncProgress?.itemLabel == "images/documents") {
            val completed = syncProgress.itemCompleted ?: 0
            val total = syncProgress.itemTotal ?: 0
            val bytes = formatBytes(syncProgress.bytesDownloaded ?: 0L)
            "Downloading images and documents… $completed of $total · $bytes downloaded\n"
        } else {
            ""
        }
    return liveAttachmentProgress +
        "$cachedDeviceCount devices · $cachedObjectCount other objects · " +
        "$cachedImageCount image records\n" +
        "$persistentCacheFiles downloaded files · ${formatBytes(persistentCacheBytes)}\n" +
        "Downloaded images and documents are kept in app storage for offline use and are not temporary Android cache files."
}

private const val MAX_NAV_BAR_ITEMS = 5
private const val MAX_SHORTCUT_ITEMS = 4

private val TWO_FINGER_SHORTCUTS =
    setOf(
        GestureShortcut.TwoFingerDown,
        GestureShortcut.TwoFingerLeft,
        GestureShortcut.TwoFingerRight,
    )

private val THREE_FINGER_SHORTCUTS =
    setOf(
        GestureShortcut.ThreeFingerUp,
        GestureShortcut.ThreeFingerDown,
        GestureShortcut.ThreeFingerLeft,
        GestureShortcut.ThreeFingerRight,
    )

internal data class SettingsCategoryState(
    val credentials: NetBoxCredentials,
    val serverProfiles: List<ServerProfile> = emptyList(),
    val activeServerId: String? = null,
    val currentUser: NetBoxUserIdentity?,
    val isLoadingCurrentUser: Boolean,
    val connectionTest: ConnectionTestState,
    val tokenVisible: Boolean,
    val isSyncing: Boolean,
    val syncIssue: SyncIssue?,
    val lastSuccessfulSyncAt: Long? = null,
    val syncProgress: SyncProgress? = null,
    val cachedDeviceCount: Int,
    val cachedObjectCount: Int,
    val cachedImageCount: Int,
    val persistentCacheBytes: Long,
    val persistentCacheFiles: Int,
    val syncAttachmentsToDisk: Boolean,
    val syncOnlyOnWifi: Boolean,
    val syncWhileRoaming: Boolean,
    val syncOnAppLaunch: Boolean,
    val syncConcurrency: Int = 3,
    val syncOnlyWhenCharging: Boolean = false,
    val syncIntervalHours: Int = 6,
    val changeNotificationsEnabled: Boolean,
    val changeNotificationFilters: Set<String>,
    val gestureActions: Map<GestureShortcut, GestureAction>,
    val gestureTargets: Map<GestureShortcut, GestureTarget>,
    val gestureModels: List<NetBoxModelEntity>,
    val objectChoices: (endpointPath: String, query: String) -> Flow<List<NetBoxObjectEntity>>,
    val navBarItems: List<NavBarItem>,
    val shortcutItems: List<NavBarItem>,
    val scannerLens: ScannerLens,
    val scannerRearLens: ScannerRearLens,
    val scannerResolution: ScannerResolution,
    val printSettings: PrintSettings,
    val hiddenFieldKeys: Set<String>,
    val pinnedModelPaths: Set<String>,
    val themeMode: ThemeMode,
    val themeAccent: ThemeAccent,
    val objectTypeAccents: Map<String, ThemeAccent>,
    val showTopologyDeviceTypeImages: Boolean,
    val scheduledBackupEnabled: Boolean = false,
    val scheduledBackupFrequency: BackupFrequency = BackupFrequency.Weekly,
    val scheduledBackupFolderUri: String? = null,
    val scheduledBackupPasswordSet: Boolean = false,
    val lastBackupAt: Long? = null,
    val backupError: String? = null,
    val backupOperation: BackupOperationState = BackupOperationState.Idle,
)

internal data class SettingsCategoryActions(
    val onEditServer: () -> Unit,
    val onSwitchServer: (String) -> Unit = {},
    val onAddServer: (String, String, String?) -> Unit = { _, _, _ -> },
    val onUpdateServer: (String, String, String, String?) -> Unit = { _, _, _, _ -> },
    val onRemoveServer: (String) -> Unit = {},
    val onTestConnection: () -> Unit,
    val onShowToken: () -> Unit,
    val onHideToken: () -> Unit,
    val onCopyToken: () -> Unit,
    val onShareSetup: () -> Unit,
    val onSync: () -> Unit,
    val onSetSyncAttachmentsToDisk: (Boolean) -> Unit,
    val onSetSyncOnlyOnWifi: (Boolean) -> Unit,
    val onSetSyncWhileRoaming: (Boolean) -> Unit,
    val onSetSyncOnAppLaunch: (Boolean) -> Unit,
    val onSetSyncConcurrency: (Int) -> Unit = {},
    val onSetSyncOnlyWhenCharging: (Boolean) -> Unit = {},
    val onSetSyncIntervalHours: (Int) -> Unit = {},
    val onSetThemeMode: (ThemeMode) -> Unit,
    val onSetThemeAccent: (ThemeAccent) -> Unit,
    val onShowObjectTypeColors: () -> Unit,
    val onShowHiddenFields: () -> Unit,
    val onShowLibraries: () -> Unit = {},
    val onSetScannerLens: (ScannerLens) -> Unit,
    val onSetScannerRearLens: (ScannerRearLens) -> Unit,
    val onSetScannerResolution: (ScannerResolution) -> Unit,
    val onUpdatePrintSettings: ((PrintSettings) -> PrintSettings) -> Unit,
    val onSetDefaultPrinter: (String, String) -> Unit,
    val onClearDefaultPrinter: () -> Unit,
    val onSetShowTopologyDeviceTypeImages: (Boolean) -> Unit,
    val onExportBackup: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
    val onChooseBackupFolder: () -> Unit = {},
    val onEditScheduledBackupPassword: () -> Unit = {},
    val onSetScheduledBackupEnabled: (Boolean) -> Unit = {},
    val onSetScheduledBackupFrequency: (BackupFrequency) -> Unit = {},
    val onSetChangeNotificationsEnabled: (Boolean) -> Unit,
    val onShowChangeNotifications: () -> Unit,
    val onSetGestureAction: (GestureShortcut, GestureAction) -> Unit,
    val onSetGestureTarget: (GestureShortcut, NetBoxModelEntity) -> Unit,
    val onSetGestureDetailTarget: (GestureShortcut, NetBoxObjectEntity) -> Unit,
    val onAddNavBarItem: (GestureAction) -> Unit,
    val onAddNavBarModelItem: (GestureAction, NetBoxModelEntity) -> Unit,
    val onAddNavBarObjectItem: (GestureAction, NetBoxObjectEntity) -> Unit,
    val onRemoveNavBarItem: (Int) -> Unit,
    val onMoveNavBarItem: (Int, Int) -> Unit,
    val onResetNavBarItems: () -> Unit,
    val onAddShortcutItem: (GestureAction) -> Unit,
    val onAddShortcutModelItem: (GestureAction, NetBoxModelEntity) -> Unit,
    val onAddShortcutObjectItem: (GestureAction, NetBoxObjectEntity) -> Unit,
    val onRemoveShortcutItem: (Int) -> Unit,
    val onMoveShortcutItem: (Int, Int) -> Unit,
    val onResetShortcutItems: () -> Unit,
)

@Composable
internal fun SettingsCategoryContent(
    category: SettingsCategory,
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    when (category) {
        SettingsCategory.Connection -> ConnectionSettingsContent(state, actions)
        SettingsCategory.Backup -> BackupSettingsContent(state, actions)
        SettingsCategory.Sync -> SyncSettingsContent(state, actions)
        SettingsCategory.Display -> DisplaySettingsContent(state, actions)
        SettingsCategory.Camera -> CameraSettingsContent(state, actions)
        SettingsCategory.Printing ->
            PrintingSettingsSection(
                settings = state.printSettings,
                onUpdate = actions.onUpdatePrintSettings,
                onSetDefaultPrinter = actions.onSetDefaultPrinter,
                onClearDefaultPrinter = actions.onClearDefaultPrinter,
            )
        SettingsCategory.Gestures -> GestureSettingsContent(state, actions)
        SettingsCategory.NavigationBar -> NavBarSettingsContent(state, actions)
        SettingsCategory.Shortcuts -> ShortcutSettingsContent(state, actions)
        SettingsCategory.Notifications -> NotificationSettingsContent(state, actions)
        SettingsCategory.About -> AboutSettingsContent(onShowLibraries = actions.onShowLibraries)
    }
}

@Composable
private fun BackupSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    var frequencyMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = "Settings backup", icon = Icons.Default.FolderSpecial) {
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onExportBackup),
                leadingContent = { Icon(Icons.Default.Upload, contentDescription = null) },
                headlineContent = { Text("Export settings") },
                supportingContent = {
                    Text("Create a portable backup without cached objects, images, or documents")
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onImportBackup),
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                headlineContent = { Text("Restore settings") },
                supportingContent = { Text("Import a backup created by Nyetbox") },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
        }
        SettingsGroupCard(title = "Scheduled backups", icon = Icons.Default.Schedule) {
            SettingsToggleItem(
                checked = state.scheduledBackupEnabled,
                onCheckedChange = actions.onSetScheduledBackupEnabled,
                leadingContent = { Icon(Icons.Default.Autorenew, contentDescription = null) },
                headlineContent = { Text("Create backups automatically") },
                supportingContent = {
                    Text(
                        if (state.scheduledBackupFolderUri.isNullOrBlank()) {
                            "Choose a directory below before enabling this"
                        } else {
                            "Runs ${state.scheduledBackupFrequency.label.lowercase()}"
                        }
                    )
                },
            )
            SettingsListItem(
                modifier =
                    Modifier.clickable(
                        enabled = state.scheduledBackupEnabled,
                        onClick = { frequencyMenuExpanded = true },
                    ),
                leadingContent = { Icon(Icons.Default.Repeat, contentDescription = null) },
                headlineContent = { Text("Frequency") },
                supportingContent = { Text(state.scheduledBackupFrequency.label) },
                trailingContent = {
                    Box {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Choose frequency")
                        DropdownMenu(
                            expanded = frequencyMenuExpanded,
                            onDismissRequest = { frequencyMenuExpanded = false },
                        ) {
                            BackupFrequency.entries.forEach { frequency ->
                                DropdownMenuItem(
                                    text = { Text(frequency.label) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Schedule, contentDescription = null)
                                    },
                                    onClick = {
                                        actions.onSetScheduledBackupFrequency(frequency)
                                        frequencyMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onChooseBackupFolder),
                leadingContent = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                headlineContent = { Text("Backup directory") },
                supportingContent = {
                    Text(
                        state.scheduledBackupFolderUri?.let { "Directory selected" }
                            ?: "Not configured"
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            SettingsListItem(
                modifier =
                    Modifier.clickable(
                        enabled = state.scheduledBackupEnabled,
                        onClick = actions.onEditScheduledBackupPassword,
                    ),
                leadingContent = { Icon(Icons.Default.Password, contentDescription = null) },
                headlineContent = { Text("Backup password") },
                supportingContent = {
                    Text(
                        if (state.scheduledBackupPasswordSet) {
                            "Automatic backups are encrypted"
                        } else {
                            "Optional; leave empty for an unencrypted backup"
                        }
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            state.lastBackupAt?.let { timestamp ->
                SettingsListItem(
                    leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    headlineContent = { Text("Last backup") },
                    supportingContent = { Text(formatBackupTimestamp(timestamp)) },
                )
            }
            state.backupError?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private fun formatBackupTimestamp(timestamp: Long): String =
    java.text.DateFormat.getDateTimeInstance().format(java.util.Date(timestamp))

@Composable
private fun ConnectionSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = "Connection", icon = Icons.Default.Dns) {
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onEditServer),
                leadingContent = { Icon(Icons.Default.Cloud, contentDescription = null) },
                headlineContent = { Text("Active NetBox instance") },
                supportingContent = {
                    Text(
                        state.serverProfiles
                            .firstOrNull { it.id == state.activeServerId }
                            ?.displayName
                            ?.takeIf { it.isNotBlank() } ?: state.credentials.baseUrl
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onEditServer),
                leadingContent = { Icon(Icons.Default.Dns, contentDescription = null) },
                headlineContent = { Text("Manage server connections") },
                supportingContent = {
                    Text(
                        if (state.serverProfiles.size == 1) "1 saved instance"
                        else "${state.serverProfiles.size} saved instances"
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Person, contentDescription = null) },
                headlineContent = { Text("Signed in as") },
                supportingContent = {
                    Text(
                        when {
                            state.currentUser != null ->
                                buildString {
                                    append(state.currentUser.summary)
                                    state.currentUser.email?.let { append(" · ").append(it) }
                                }
                            state.isLoadingCurrentUser -> "Checking NetBox credentials…"
                            else -> "Not available from this API token"
                        }
                    )
                },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Key, contentDescription = null) },
                headlineContent = { Text("API token") },
                supportingContent = {
                    Text(
                        when {
                            state.credentials.token.isBlank() -> "Not configured"
                            state.tokenVisible -> state.credentials.token
                            else -> "••••••••••••"
                        }
                    )
                },
                trailingContent = {
                    Row {
                        IconButton(
                            onClick =
                                if (state.credentials.token.isBlank()) actions.onShowToken
                                else if (state.tokenVisible) actions.onHideToken
                                else actions.onShowToken
                        ) {
                            Icon(
                                if (state.tokenVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                contentDescription =
                                    if (state.tokenVisible) "Hide API token" else "Show API token",
                            )
                        }
                        IconButton(
                            onClick = actions.onCopyToken,
                            enabled = state.credentials.token.isNotBlank(),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy API token")
                        }
                    }
                },
            )
            SettingsListItem(
                modifier =
                    Modifier.clickable(
                        enabled = state.credentials.isValid,
                        onClick = actions.onShareSetup,
                    ),
                leadingContent = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                headlineContent = { Text("Share connection setup") },
                supportingContent = { Text("Show a QR code with this server URL and API token") },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(
                    onClick = actions.onTestConnection,
                    enabled =
                        state.credentials.isValid &&
                            state.connectionTest !is ConnectionTestState.Testing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (state.connectionTest is ConnectionTestState.Testing) Icons.Default.Sync
                        else Icons.Default.NetworkCheck,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.connectionTest is ConnectionTestState.Testing) {
                            "Testing connection…"
                        } else {
                            "Test connection"
                        }
                    )
                }
                when (val result = state.connectionTest) {
                    is ConnectionTestState.Success ->
                        Text(
                            result.message,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    is ConnectionTestState.Failure ->
                        Text(
                            result.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    ConnectionTestState.Idle,
                    ConnectionTestState.Testing -> Unit
                }
            }
        }
    }
}

private val SYNC_CONCURRENCY_PRESETS = listOf(1, 2, 3, 4, 6, 8)
private val SYNC_INTERVAL_HOUR_PRESETS = listOf(1, 3, 6, 12, 24)

@Composable
private fun SyncSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    var concurrencyMenuExpanded by remember { mutableStateOf(false) }
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // A single consolidated sync-status indicator (NBC-334) - SyncIssueCard and SyncStatusCard
        // are mutually exclusive, mirroring DashboardScreen's shouldShowSyncIssue/
        // shouldShowSyncStatus idiom, instead of this screen's former separate top card (shown
        // only on error) plus a second bare "Syncing…" label on the "Cached data" button below.
        state.syncIssue?.let { issue ->
            SyncIssueCard(issue, onRetry = actions.onSync, isSyncing = state.isSyncing)
        }
            ?: SyncStatusCard(
                lastSuccessfulSyncAt = state.lastSuccessfulSyncAt,
                isSyncing = state.isSyncing,
                syncProgress = state.syncProgress,
            )
        SettingsGroupCard(title = "Sync policy", icon = Icons.Default.Sync) {
            SettingsToggleItem(
                checked = state.syncAttachmentsToDisk,
                onCheckedChange = actions.onSetSyncAttachmentsToDisk,
                leadingContent = { Icon(Icons.Default.Download, contentDescription = null) },
                headlineContent = { Text("Sync attachments to disk") },
                supportingContent = {
                    Text("Download documents and images on sync for full offline access")
                },
            )
            SettingsToggleItem(
                checked = state.syncOnlyOnWifi,
                onCheckedChange = actions.onSetSyncOnlyOnWifi,
                leadingContent = { Icon(Icons.Default.Wifi, contentDescription = null) },
                headlineContent = { Text("Sync only on Wi-Fi") },
                supportingContent = {
                    Text("Use an unmetered connection for background and manual sync")
                },
            )
            SettingsToggleItem(
                checked = state.syncWhileRoaming,
                onCheckedChange = actions.onSetSyncWhileRoaming,
                enabled = !state.syncOnlyOnWifi,
                leadingContent = {
                    Icon(Icons.Default.SignalCellularAlt, contentDescription = null)
                },
                headlineContent = { Text("Sync while roaming") },
                supportingContent = {
                    Text(
                        if (state.syncOnlyOnWifi) {
                            "No effect while Wi-Fi-only sync is enabled"
                        } else {
                            "Allow sync over a roaming mobile connection"
                        }
                    )
                },
            )
            SettingsToggleItem(
                checked = state.syncOnAppLaunch,
                onCheckedChange = actions.onSetSyncOnAppLaunch,
                leadingContent = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                headlineContent = { Text("Sync on app launch") },
                supportingContent = {
                    Text("Refresh NetBox in the background when the app starts")
                },
            )
            SettingsToggleItem(
                checked = state.syncOnlyWhenCharging,
                onCheckedChange = actions.onSetSyncOnlyWhenCharging,
                leadingContent = {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                },
                headlineContent = { Text("Sync only while charging") },
                supportingContent = {
                    Text("Restrict background and manual sync to while the device is charging")
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable { intervalMenuExpanded = true },
                leadingContent = { Icon(Icons.Default.Schedule, contentDescription = null) },
                headlineContent = { Text("Background sync interval") },
                supportingContent = {
                    Text("Check for changes every ${state.syncIntervalHours} hour(s)")
                },
                trailingContent = {
                    Box {
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                        DropdownMenu(
                            expanded = intervalMenuExpanded,
                            onDismissRequest = { intervalMenuExpanded = false },
                        ) {
                            SYNC_INTERVAL_HOUR_PRESETS.forEach { hours ->
                                DropdownMenuItem(
                                    text = { Text("$hours hour(s)") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Schedule, contentDescription = null)
                                    },
                                    onClick = {
                                        actions.onSetSyncIntervalHours(hours)
                                        intervalMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable { concurrencyMenuExpanded = true },
                leadingContent = { Icon(Icons.Default.Speed, contentDescription = null) },
                headlineContent = { Text("Sync concurrency") },
                supportingContent = {
                    Text(
                        "${state.syncConcurrency} endpoint(s) at once - higher finishes sync " +
                            "faster but may overload a small self-hosted NetBox instance"
                    )
                },
                trailingContent = {
                    Box {
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                        DropdownMenu(
                            expanded = concurrencyMenuExpanded,
                            onDismissRequest = { concurrencyMenuExpanded = false },
                        ) {
                            SYNC_CONCURRENCY_PRESETS.forEach { concurrency ->
                                DropdownMenuItem(
                                    text = { Text(concurrency.toString()) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Speed, contentDescription = null)
                                    },
                                    onClick = {
                                        actions.onSetSyncConcurrency(concurrency)
                                        concurrencyMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
        SettingsSingleItemCard {
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                headlineContent = { Text("Cached data") },
                supportingContent = {
                    Text(
                        cachedDataSupportingText(
                            isSyncing = state.isSyncing,
                            syncProgress = state.syncProgress,
                            cachedDeviceCount = state.cachedDeviceCount,
                            cachedObjectCount = state.cachedObjectCount,
                            cachedImageCount = state.cachedImageCount,
                            persistentCacheFiles = state.persistentCacheFiles,
                            persistentCacheBytes = state.persistentCacheBytes,
                        )
                    )
                },
            )
            // Status lives solely in the SyncStatusCard/SyncIssueCard above (NBC-334) - this button
            // is purely the manual-sync action/control, not a second status indicator, so its
            // label doesn't change while syncing (just disables like any other in-flight action).
            Button(
                onClick = actions.onSync,
                enabled = !state.isSyncing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                RotatingSyncIcon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    syncing = state.isSyncing,
                )
                Spacer(Modifier.width(8.dp))
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun DisplaySettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    var themeModeMenuExpanded by remember { mutableStateOf(false) }
    var themeAccentMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(
            title = "Appearance",
            icon = Icons.Default.Palette,
        ) {
            SettingsListItem(
                modifier = Modifier.clickable { themeModeMenuExpanded = true },
                leadingContent = {
                    Icon(
                        when (state.themeMode) {
                            ThemeMode.FollowSystem -> Icons.Default.BrightnessAuto
                            ThemeMode.Light -> Icons.Default.LightMode
                            ThemeMode.Dark -> Icons.Default.DarkMode
                        },
                        contentDescription = null,
                    )
                },
                headlineContent = { Text("Color scheme") },
                supportingContent = { Text(state.themeMode.label) },
                trailingContent = {
                    Box {
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                        DropdownMenu(
                            expanded = themeModeMenuExpanded,
                            onDismissRequest = { themeModeMenuExpanded = false },
                        ) {
                            ThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Palette, contentDescription = null)
                                    },
                                    onClick = {
                                        actions.onSetThemeMode(mode)
                                        themeModeMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable { themeAccentMenuExpanded = true },
                leadingContent = { Icon(Icons.Default.Colorize, contentDescription = null) },
                headlineContent = { Text("Accent color") },
                supportingContent = { Text(state.themeAccent.label) },
                trailingContent = {
                    Box {
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                        DropdownMenu(
                            expanded = themeAccentMenuExpanded,
                            onDismissRequest = { themeAccentMenuExpanded = false },
                        ) {
                            ThemeAccent.entries.forEach { accent ->
                                DropdownMenuItem(
                                    text = { Text(accent.label) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Palette, contentDescription = null)
                                    },
                                    onClick = {
                                        actions.onSetThemeAccent(accent)
                                        themeAccentMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onShowObjectTypeColors),
                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                headlineContent = { Text("Object type colors") },
                supportingContent = {
                    Text(
                        if (state.objectTypeAccents.isEmpty()) "Automatic colors"
                        else "${state.objectTypeAccents.size} customized object types"
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
        }
        SettingsGroupCard(title = "Content", icon = Icons.Default.Visibility) {
            SettingsListItem(
                modifier = Modifier.clickable(onClick = actions.onShowHiddenFields),
                leadingContent = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                headlineContent = { Text("Hidden fields") },
                supportingContent = {
                    Text(
                        if (state.hiddenFieldKeys.isEmpty()) {
                            "No fields hidden by default"
                        } else {
                            val countLabel =
                                if (state.hiddenFieldKeys.size == 1) "field" else "fields"
                            "$countLabel hidden by default · ${state.hiddenFieldKeys.sorted().joinToString(", ")}"
                        }
                    )
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.hiddenFieldKeys.isNotEmpty()) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Hidden fields configured",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.PushPin, contentDescription = null) },
                headlineContent = { Text("Pinned item types") },
                supportingContent = {
                    Text(
                        if (state.pinnedModelPaths.isEmpty()) "No item types pinned"
                        else
                            "${state.pinnedModelPaths.size} pinned · Long-press an item type on Add to change this"
                    )
                },
            )
            SettingsToggleItem(
                checked = state.showTopologyDeviceTypeImages,
                onCheckedChange = actions.onSetShowTopologyDeviceTypeImages,
                leadingContent = { Icon(Icons.Default.Hub, contentDescription = null) },
                headlineContent = { Text("Topology device images") },
                supportingContent = {
                    Text("Use cached device-type front images for matching topology nodes")
                },
            )
        }
    }
}

@Composable
private fun CameraSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    var scannerLensMenuExpanded by remember { mutableStateOf(false) }
    var scannerRearLensMenuExpanded by remember { mutableStateOf(false) }
    var scannerResolutionMenuExpanded by remember { mutableStateOf(false) }
    SettingsGroupCard(title = "Scanner", icon = Icons.Default.Cameraswitch) {
        SettingsListItem(
            modifier = Modifier.clickable { scannerLensMenuExpanded = true },
            leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            headlineContent = { Text("Scanner default camera") },
            supportingContent = { Text("${state.scannerLens.label}; falls back when unavailable") },
            trailingContent = {
                Box {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    DropdownMenu(
                        expanded = scannerLensMenuExpanded,
                        onDismissRequest = { scannerLensMenuExpanded = false },
                    ) {
                        ScannerLens.entries.forEach { lens ->
                            DropdownMenuItem(
                                text = { Text(lens.label) },
                                leadingIcon = {
                                    Icon(Icons.Default.Cameraswitch, contentDescription = null)
                                },
                                onClick = {
                                    actions.onSetScannerLens(lens)
                                    scannerLensMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            },
        )
        SettingsListItem(
            modifier = Modifier.clickable { scannerRearLensMenuExpanded = true },
            leadingContent = { Icon(Icons.Default.Cameraswitch, contentDescription = null) },
            headlineContent = { Text("Default rear lens") },
            supportingContent = {
                Text(
                    "${state.scannerRearLens.label}; uses the closest available lens when unavailable"
                )
            },
            trailingContent = {
                Box {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    DropdownMenu(
                        expanded = scannerRearLensMenuExpanded,
                        onDismissRequest = { scannerRearLensMenuExpanded = false },
                    ) {
                        ScannerRearLens.entries.forEach { lens ->
                            DropdownMenuItem(
                                text = { Text(lens.label) },
                                leadingIcon = {
                                    Icon(Icons.Default.Cameraswitch, contentDescription = null)
                                },
                                onClick = {
                                    actions.onSetScannerRearLens(lens)
                                    scannerRearLensMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            },
        )
        SettingsListItem(
            modifier = Modifier.clickable { scannerResolutionMenuExpanded = true },
            leadingContent = { Icon(Icons.Default.HighQuality, contentDescription = null) },
            headlineContent = { Text("Scan resolution") },
            supportingContent = {
                Text(
                    "${state.scannerResolution.label}; higher catches smaller or farther codes but decodes slower"
                )
            },
            trailingContent = {
                Box {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    DropdownMenu(
                        expanded = scannerResolutionMenuExpanded,
                        onDismissRequest = { scannerResolutionMenuExpanded = false },
                    ) {
                        ScannerResolution.entries.forEach { resolution ->
                            DropdownMenuItem(
                                text = { Text(resolution.label) },
                                leadingIcon = {
                                    Icon(Icons.Default.HighQuality, contentDescription = null)
                                },
                                onClick = {
                                    actions.onSetScannerResolution(resolution)
                                    scannerResolutionMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun GestureSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(
            title = "Two-finger gestures",
            icon = Icons.Default.TouchApp,
        ) {
            GestureShortcut.entries
                .filter { it in TWO_FINGER_SHORTCUTS }
                .forEach { shortcut ->
                    GestureShortcutRow(
                        shortcut = shortcut,
                        action = state.gestureActions[shortcut] ?: GestureAction.Off,
                        target = state.gestureTargets[shortcut],
                        models = state.gestureModels,
                        objectChoices = state.objectChoices,
                        onActionSelected = { action ->
                            actions.onSetGestureAction(shortcut, action)
                        },
                        onTargetSelected = { model -> actions.onSetGestureTarget(shortcut, model) },
                        onDetailTargetSelected = { obj ->
                            actions.onSetGestureDetailTarget(shortcut, obj)
                        },
                    )
                }
        }
        SettingsGroupCard(
            title = "Three-finger gestures",
            icon = Icons.Default.TouchApp,
        ) {
            GestureShortcut.entries
                .filter { it in THREE_FINGER_SHORTCUTS }
                .forEach { shortcut ->
                    GestureShortcutRow(
                        shortcut = shortcut,
                        action = state.gestureActions[shortcut] ?: GestureAction.Off,
                        target = state.gestureTargets[shortcut],
                        models = state.gestureModels,
                        objectChoices = state.objectChoices,
                        onActionSelected = { action ->
                            actions.onSetGestureAction(shortcut, action)
                        },
                        onTargetSelected = { model -> actions.onSetGestureTarget(shortcut, model) },
                        onDetailTargetSelected = { obj ->
                            actions.onSetGestureDetailTarget(shortcut, obj)
                        },
                    )
                }
        }
    }
}

@Composable
private fun NavBarSettingsContent(state: SettingsCategoryState, actions: SettingsCategoryActions) {
    var pickerAction by remember { mutableStateOf<GestureAction?>(null) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    val items = state.navBarItems

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = "Navigation bar buttons", icon = Icons.Default.ViewCarousel) {
            items.forEachIndexed { index, item ->
                SettingsListItem(
                    leadingContent = {
                        Icon(iconForGestureAction(item.action), contentDescription = null)
                    },
                    headlineContent = { Text(item.target?.label ?: item.action.label) },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { actions.onMoveNavBarItem(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { actions.onMoveNavBarItem(index, index + 1) },
                                enabled = index < items.lastIndex,
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move down",
                                )
                            }
                            IconButton(
                                onClick = { actions.onRemoveNavBarItem(index) },
                                enabled = items.size > 1,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    },
                )
            }
            if (items.size < MAX_NAV_BAR_ITEMS) {
                Box {
                    SettingsListItem(
                        modifier = Modifier.clickable { addMenuExpanded = true },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        headlineContent = { Text("Add button") },
                    )
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        GestureAction.navigational.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label) },
                                leadingIcon = {
                                    Icon(iconForGestureAction(candidate), contentDescription = null)
                                },
                                onClick = {
                                    addMenuExpanded = false
                                    when (candidate) {
                                        GestureAction.AddSpecific,
                                        GestureAction.ListSpecific,
                                        GestureAction.DetailSpecific -> pickerAction = candidate
                                        else -> actions.onAddNavBarItem(candidate)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        SettingsSingleItemCard {
            SettingsListItem(
                modifier = Modifier.clickable { actions.onResetNavBarItems() },
                leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                headlineContent = { Text("Reset to defaults") },
            )
        }
    }
    pickerAction?.let { action ->
        ActionTargetPickerDialog(
            action = action,
            models = state.gestureModels,
            objectChoices = state.objectChoices,
            onDismiss = { pickerAction = null },
            onModelSelected = { model ->
                actions.onAddNavBarModelItem(action, model)
                pickerAction = null
            },
            onObjectSelected = { obj ->
                actions.onAddNavBarObjectItem(action, obj)
                pickerAction = null
            },
        )
    }
}

@Composable
private fun ShortcutSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    var pickerAction by remember { mutableStateOf<GestureAction?>(null) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    val items = state.shortcutItems

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(title = "Launcher shortcuts", icon = Icons.Default.AppShortcut) {
            items.forEachIndexed { index, item ->
                SettingsListItem(
                    leadingContent = {
                        Icon(iconForGestureAction(item.action), contentDescription = null)
                    },
                    headlineContent = { Text(item.target?.label ?: item.action.label) },
                    trailingContent = {
                        Row {
                            IconButton(
                                onClick = { actions.onMoveShortcutItem(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(
                                onClick = { actions.onMoveShortcutItem(index, index + 1) },
                                enabled = index < items.lastIndex,
                            ) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Move down",
                                )
                            }
                            IconButton(
                                onClick = { actions.onRemoveShortcutItem(index) },
                                enabled = items.size > 1,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    },
                )
            }
            if (items.size < MAX_SHORTCUT_ITEMS) {
                Box {
                    SettingsListItem(
                        modifier = Modifier.clickable { addMenuExpanded = true },
                        leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                        headlineContent = { Text("Add shortcut") },
                    )
                    DropdownMenu(
                        expanded = addMenuExpanded,
                        onDismissRequest = { addMenuExpanded = false },
                    ) {
                        GestureAction.shortcutable.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label) },
                                leadingIcon = {
                                    Icon(iconForGestureAction(candidate), contentDescription = null)
                                },
                                onClick = {
                                    addMenuExpanded = false
                                    when (candidate) {
                                        GestureAction.AddSpecific,
                                        GestureAction.ListSpecific,
                                        GestureAction.DetailSpecific -> pickerAction = candidate
                                        else -> actions.onAddShortcutItem(candidate)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        SettingsSingleItemCard {
            SettingsListItem(
                modifier = Modifier.clickable { actions.onResetShortcutItems() },
                leadingContent = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
                headlineContent = { Text("Reset to defaults") },
            )
        }
    }
    pickerAction?.let { action ->
        ActionTargetPickerDialog(
            action = action,
            models = state.gestureModels,
            objectChoices = state.objectChoices,
            onDismiss = { pickerAction = null },
            onModelSelected = { model ->
                actions.onAddShortcutModelItem(action, model)
                pickerAction = null
            },
            onObjectSelected = { obj ->
                actions.onAddShortcutObjectItem(action, obj)
                pickerAction = null
            },
        )
    }
}

@Composable
private fun NotificationSettingsContent(
    state: SettingsCategoryState,
    actions: SettingsCategoryActions,
) {
    SettingsSingleItemCard {
        SettingsToggleItem(
            checked = state.changeNotificationsEnabled,
            onCheckedChange = actions.onSetChangeNotificationsEnabled,
            leadingContent = { Icon(Icons.Default.Notifications, contentDescription = null) },
            headlineContent = { Text("NetBox change notifications") },
            supportingContent = {
                Text(
                    if (state.changeNotificationsEnabled) {
                        selectedChangeNotificationSummary(state.changeNotificationFilters)
                    } else {
                        "Disabled by default; notify only about changes you choose"
                    }
                )
            },
        )
        if (state.changeNotificationsEnabled) {
            OutlinedButton(
                onClick = actions.onShowChangeNotifications,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(Icons.Default.FilterList, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Choose change types")
            }
        }
    }
}

@Composable
private fun AboutSettingsContent(onShowLibraries: () -> Unit) {
    val context = LocalContext.current
    val appIconBitmap = remember {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap()?.asImageBitmap()
    }
    var buildTapCount by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroupCard(
            title = "Nyetbox",
            icon = Icons.Default.Info,
            headerContent = {
                if (appIconBitmap != null) {
                    Image(
                        bitmap = appIconBitmap,
                        contentDescription = "Nyetbox app icon",
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
                    )
                } else {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "Nyetbox app icon",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        ) {
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Apps, contentDescription = null) },
                headlineContent = { Text("Version") },
                supportingContent = { Text(BuildConfig.VERSION_NAME) },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                headlineContent = { Text("License") },
                supportingContent = { Text("GPLv3") },
            )
            SettingsListItem(
                modifier =
                    Modifier.clickable {
                        val tapCount = buildTapCount + 1
                        buildTapCount = if (tapCount >= 7) 0 else tapCount
                        Toast.makeText(
                                context,
                                if (tapCount >= 7) "Developer mode enabled"
                                else "${7 - tapCount} more taps to enable developer mode",
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    },
                leadingContent = { Icon(Icons.Default.Tag, contentDescription = null) },
                headlineContent = { Text("Build") },
                supportingContent = { Text(BuildConfig.GIT_REVISION) },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.DateRange, contentDescription = null) },
                headlineContent = { Text("Build date") },
                supportingContent = { Text(BuildConfig.BUILD_DATE) },
            )
            SettingsListItem(
                leadingContent = { Icon(Icons.Default.Apps, contentDescription = null) },
                headlineContent = { Text("Build type") },
                supportingContent = { Text(aboutBuildTypeLabel(BuildConfig.DEBUG)) },
            )
        }
        SettingsGroupCard(title = "Project", icon = Icons.Default.Code) {
            ExternalLinkRow(
                context = context,
                url = "https://github.com/pschmitt/nyetbox",
                icon = Icons.Default.Public,
                title = "GitHub repository",
                subtitle = "View the source code and report issues",
            )
            ExternalLinkRow(
                context = context,
                url = "https://github.com/sponsors/pschmitt",
                icon = Icons.Default.Favorite,
                title = "Sponsor the project",
                subtitle = "Support development on GitHub Sponsors",
            )
            ExternalLinkRow(
                context = context,
                url = "https://github.com/pschmitt/nyetbox/blob/main/PRIVACY.md",
                icon = Icons.Default.PrivacyTip,
                title = "Privacy policy",
                subtitle = "How Nyetbox handles data and network access",
            )
            SettingsListItem(
                modifier = Modifier.clickable(onClick = onShowLibraries),
                leadingContent = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                headlineContent = { Text("Libraries") },
                supportingContent = { Text("Open-source dependencies and their licenses") },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                },
            )
        }
    }
}

internal fun aboutBuildTypeLabel(isDebug: Boolean): String =
    if (isDebug) "Debug build" else "Release build"

@Composable
private fun ExternalLinkRow(
    context: Context,
    url: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    SettingsListItem(
        modifier =
            Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open $title")
        },
    )
}
