package dev.pschmitt.nyetbox.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.nyetbox.data.backup.settingsBackupFileName
import dev.pschmitt.nyetbox.qrsetup.QrBitmap
import dev.pschmitt.nyetbox.qrsetup.QrConfigCodec
import dev.pschmitt.nyetbox.qrsetup.QrConfigEnvelope
import dev.pschmitt.nyetbox.ui.common.PrintSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCategoryClick: (SettingsCategory) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val offlineMode by viewModel.settingsRepository.offlineMode.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.testTag("e2e-settings-screen"),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsGroupCard(title = "Account & Sync", icon = Icons.Default.Sync) {
                SettingsToggleItem(
                    checked = offlineMode,
                    onCheckedChange = viewModel::setOfflineMode,
                    leadingContent = { Icon(Icons.Default.CloudOff, contentDescription = null) },
                    headlineContent = { Text("Offline mode") },
                    supportingContent = {
                        Text(
                            if (offlineMode) "Network access is paused"
                            else "Allow background network sync"
                        )
                    },
                )
                listOf(SettingsCategory.Connection, SettingsCategory.Sync, SettingsCategory.Backup)
                    .forEach { category -> SettingsCategoryRow(category, onCategoryClick) }
            }
            SettingsGroupCard(title = "Hardware", icon = Icons.Default.Devices) {
                listOf(SettingsCategory.Camera, SettingsCategory.Printing).forEach { category ->
                    SettingsCategoryRow(category, onCategoryClick)
                }
            }
            SettingsGroupCard(title = "Appearance & Interaction", icon = Icons.Default.Palette) {
                listOf(
                        SettingsCategory.Display,
                        SettingsCategory.Gestures,
                        SettingsCategory.NavigationBar,
                        SettingsCategory.Shortcuts,
                        SettingsCategory.Notifications,
                    )
                    .forEach { category -> SettingsCategoryRow(category, onCategoryClick) }
            }
            SettingsSingleItemCard { SettingsCategoryRow(SettingsCategory.About, onCategoryClick) }
        }
    }
}

@Composable
private fun SettingsCategoryRow(category: SettingsCategory, onClick: (SettingsCategory) -> Unit) {
    SettingsListItem(
        modifier = Modifier.clickable { onClick(category) },
        leadingContent = { Icon(category.icon, contentDescription = null) },
        headlineContent = { Text(category.title) },
        supportingContent = { Text(category.subtitle) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    openServerManager: Boolean = false,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    printSettingsViewModel: PrintSettingsViewModel = hiltViewModel(),
    backupViewModel: SettingsBackupViewModel = hiltViewModel(),
) {
    val credentials by viewModel.settingsRepository.credentials.collectAsStateWithLifecycle()
    val serverProfiles by viewModel.settingsRepository.serverProfiles.collectAsStateWithLifecycle()
    val activeServerId by viewModel.settingsRepository.activeServerId.collectAsStateWithLifecycle()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val isLoadingCurrentUser by viewModel.isLoadingCurrentUser.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val isUpdatingBaseUrl by viewModel.isUpdatingBaseUrl.collectAsStateWithLifecycle()
    val cachedDeviceCount by viewModel.cachedDeviceCount.collectAsStateWithLifecycle()
    val cachedObjectCount by viewModel.cachedObjectCount.collectAsStateWithLifecycle()
    val cachedImageCount by viewModel.cachedImageCount.collectAsStateWithLifecycle()
    val persistentCacheBytes by viewModel.persistentCacheBytes.collectAsStateWithLifecycle()
    val persistentCacheFiles by viewModel.persistentCacheFiles.collectAsStateWithLifecycle()
    val syncAttachmentsToDisk by
        viewModel.settingsRepository.syncAttachmentsToDisk.collectAsStateWithLifecycle()
    val syncOnlyOnWifi by viewModel.settingsRepository.syncOnlyOnWifi.collectAsStateWithLifecycle()
    val syncWhileRoaming by
        viewModel.settingsRepository.syncWhileRoaming.collectAsStateWithLifecycle()
    val syncOnAppLaunch by
        viewModel.settingsRepository.syncOnAppLaunch.collectAsStateWithLifecycle()
    val syncConcurrency by
        viewModel.settingsRepository.syncConcurrency.collectAsStateWithLifecycle()
    val changeNotificationsEnabled by
        viewModel.settingsRepository.changeNotificationsEnabled.collectAsStateWithLifecycle()
    val changeNotificationFilters by
        viewModel.settingsRepository.changeNotificationFilters.collectAsStateWithLifecycle()
    val gestureActions by viewModel.settingsRepository.gestureActions.collectAsStateWithLifecycle()
    val gestureTargets by viewModel.gestureTargets.collectAsStateWithLifecycle()
    val gestureModels by viewModel.gestureModels.collectAsStateWithLifecycle()
    val navBarItems by viewModel.navBarItems.collectAsStateWithLifecycle()
    val shortcutItems by viewModel.shortcutItems.collectAsStateWithLifecycle()
    val scannerLens by viewModel.settingsRepository.scannerLens.collectAsStateWithLifecycle()
    val scannerRearLens by
        viewModel.settingsRepository.scannerRearLens.collectAsStateWithLifecycle()
    val printSettings by printSettingsViewModel.settings.collectAsStateWithLifecycle()
    val hiddenFieldKeys by
        viewModel.settingsRepository.hiddenFieldKeys.collectAsStateWithLifecycle()
    val pinnedModelPaths by
        viewModel.settingsRepository.pinnedModelPaths.collectAsStateWithLifecycle()
    val themeMode by viewModel.settingsRepository.themeMode.collectAsStateWithLifecycle()
    val themeAccent by viewModel.settingsRepository.themeAccent.collectAsStateWithLifecycle()
    val objectTypeAccents by
        viewModel.settingsRepository.objectTypeAccents.collectAsStateWithLifecycle()
    val showTopologyDeviceTypeImages by
        viewModel.settingsRepository.showTopologyDeviceTypeImages.collectAsStateWithLifecycle()
    val scheduledBackupEnabled by
        backupViewModel.settingsRepository.scheduledBackupEnabled.collectAsStateWithLifecycle()
    val scheduledBackupFrequency by
        backupViewModel.settingsRepository.scheduledBackupFrequency.collectAsStateWithLifecycle()
    val scheduledBackupFolderUri by
        backupViewModel.settingsRepository.scheduledBackupFolderUri.collectAsStateWithLifecycle()
    val scheduledBackupPasswordSet by
        backupViewModel.settingsRepository.scheduledBackupPasswordSet.collectAsStateWithLifecycle()
    val lastBackupAt by
        backupViewModel.settingsRepository.lastBackupAt.collectAsStateWithLifecycle()
    val backupError by backupViewModel.settingsRepository.backupError.collectAsStateWithLifecycle()
    val backupOperation by backupViewModel.operation.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val syncIssue by viewModel.settingsRepository.syncIssue.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditServerDialog by remember(openServerManager) { mutableStateOf(openServerManager) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var tokenVisible by remember { mutableStateOf(false) }
    var pendingTokenAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var tokenAuthError by remember { mutableStateOf<String?>(null) }
    var tokenCopied by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hiddenFieldsDialogVisible by remember { mutableStateOf(false) }
    var changeNotificationsDialogVisible by remember { mutableStateOf(false) }
    var objectTypeColorsDialogVisible by remember { mutableStateOf(false) }
    var exportPasswordDialogVisible by remember { mutableStateOf(false) }
    var scheduledPasswordDialogVisible by remember { mutableStateOf(false) }
    var importPasswordDialogVisible by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var scheduledPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    val currentPendingTokenAction by rememberUpdatedState(pendingTokenAction)

    val createBackupLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            uri?.let { backupViewModel.export(it, exportPassword) }
            exportPassword = ""
        }
    val openBackupLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { backupViewModel.restore(it) }
        }
    val backupFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                backupViewModel.setScheduledBackupFolderUri(it.toString())
            }
        }

    val biometricPrompt =
        remember(activity) {
            activity?.let { host ->
                BiometricPrompt(
                    host,
                    ContextCompat.getMainExecutor(host),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: BiometricPrompt.AuthenticationResult
                        ) {
                            currentPendingTokenAction?.invoke()
                            pendingTokenAction = null
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            pendingTokenAction = null
                            tokenAuthError = errString.toString()
                        }
                    },
                )
            }
        }
    val authenticateForToken: (() -> Unit) -> Unit = { action ->
        val host = activity
        if (host == null) {
            tokenAuthError = "Device authentication is unavailable"
        } else {
            val authenticators =
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            when (BiometricManager.from(host).canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> {
                    tokenAuthError = null
                    pendingTokenAction = action
                    biometricPrompt?.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Authenticate to access API token")
                            .setSubtitle("Confirm your fingerprint or device PIN")
                            .setAllowedAuthenticators(authenticators)
                            .build()
                    )
                }
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    tokenAuthError = "Set up a fingerprint or device PIN to access the API token"
                else -> tokenAuthError = "Device authentication is unavailable"
            }
        }
    }

    LaunchedEffect(credentials) {
        // A server switch or disconnect must never leave a previously-authorized token visible or
        // allow a pending authentication callback to act on credentials that are no longer shown.
        tokenVisible = false
        pendingTokenAction = null
        qrBitmap = null
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.errorShown()
        }
    }

    LaunchedEffect(tokenAuthError) {
        tokenAuthError?.let {
            snackbarHostState.showSnackbar(it)
            tokenAuthError = null
        }
    }

    LaunchedEffect(tokenCopied) {
        if (tokenCopied) {
            snackbarHostState.showSnackbar("API token copied")
            tokenCopied = false
        }
    }

    LaunchedEffect(backupOperation) {
        when (val operation = backupOperation) {
            is BackupOperationState.PasswordRequired -> {
                pendingImportUri = operation.uri
                importPassword = ""
                importPasswordDialogVisible = true
                backupViewModel.dismissOperation()
            }
            is BackupOperationState.Success,
            is BackupOperationState.Error -> {
                val message =
                    when (operation) {
                        is BackupOperationState.Success -> operation.message
                        is BackupOperationState.Error -> operation.message
                    }
                snackbarHostState.showSnackbar(message)
                backupViewModel.dismissOperation()
            }
            BackupOperationState.Idle,
            BackupOperationState.Working -> Unit
        }
    }

    if (exportPasswordDialogVisible) {
        BackupPasswordDialog(
            title = "Export settings",
            password = exportPassword,
            confirmLabel = "Choose file",
            supportingText = "Leave blank to create an unencrypted backup.",
            onPasswordChanged = { exportPassword = it },
            onDismiss = {
                exportPassword = ""
                exportPasswordDialogVisible = false
            },
            onConfirm = {
                exportPasswordDialogVisible = false
                createBackupLauncher.launch(settingsBackupFileName())
            },
        )
    }

    if (scheduledPasswordDialogVisible) {
        BackupPasswordDialog(
            title = "Scheduled backup password",
            password = scheduledPassword,
            confirmLabel = "Save",
            supportingText =
                if (scheduledBackupPasswordSet) {
                    "Enter a new password, or leave blank to remove protection."
                } else {
                    "Leave blank for an unencrypted scheduled backup."
                },
            onPasswordChanged = { scheduledPassword = it },
            onDismiss = {
                scheduledPassword = ""
                scheduledPasswordDialogVisible = false
            },
            onConfirm = {
                backupViewModel.setScheduledBackupPassword(scheduledPassword)
                scheduledPassword = ""
                scheduledPasswordDialogVisible = false
            },
        )
    }

    if (importPasswordDialogVisible && pendingImportUri != null) {
        BackupPasswordDialog(
            title = "Password required",
            password = importPassword,
            confirmLabel = "Restore",
            supportingText = "This settings backup is password-protected.",
            onPasswordChanged = { importPassword = it },
            onDismiss = {
                importPassword = ""
                pendingImportUri = null
                importPasswordDialogVisible = false
            },
            onConfirm = {
                pendingImportUri?.let { backupViewModel.restore(it, importPassword) }
                importPassword = ""
                pendingImportUri = null
                importPasswordDialogVisible = false
            },
        )
    }

    if (showEditServerDialog) {
        ServerProfilesDialog(
            profiles = serverProfiles,
            activeServerId = activeServerId,
            onSwitch = viewModel::switchServer,
            onAdd = viewModel::addServer,
            onUpdate = viewModel::updateServer,
            onRemove = { id -> viewModel.removeServer(id) { onLoggedOut() } },
            onDismiss = { showEditServerDialog = false },
        )
    }

    qrBitmap?.let { bitmap -> SetupQrDialog(bitmap = bitmap, onDismiss = { qrBitmap = null }) }

    if (hiddenFieldsDialogVisible) {
        HiddenFieldsDialog(
            keys = hiddenFieldKeys,
            onAdd = viewModel::addHiddenField,
            onRemove = viewModel::removeHiddenField,
            onDismiss = { hiddenFieldsDialogVisible = false },
        )
    }

    if (changeNotificationsDialogVisible) {
        ChangeNotificationsDialog(
            filters = changeNotificationFilters,
            onFilterChanged = viewModel::setChangeNotificationFilter,
            onDismiss = { changeNotificationsDialogVisible = false },
        )
    }

    if (objectTypeColorsDialogVisible) {
        ObjectTypeColorsDialog(
            models = gestureModels,
            accents = objectTypeAccents,
            onAccentChanged = viewModel.settingsRepository::setObjectTypeAccent,
            onDismiss = { objectTypeColorsDialogVisible = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(category.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            SettingsCategoryContent(
                category = category,
                state =
                    SettingsCategoryState(
                        credentials = credentials,
                        serverProfiles = serverProfiles,
                        activeServerId = activeServerId,
                        currentUser = currentUser,
                        isLoadingCurrentUser = isLoadingCurrentUser,
                        connectionTest = connectionTest,
                        tokenVisible = tokenVisible,
                        isSyncing = isSyncing,
                        syncIssue = syncIssue,
                        cachedDeviceCount = cachedDeviceCount,
                        cachedObjectCount = cachedObjectCount,
                        cachedImageCount = cachedImageCount,
                        persistentCacheBytes = persistentCacheBytes,
                        persistentCacheFiles = persistentCacheFiles,
                        syncAttachmentsToDisk = syncAttachmentsToDisk,
                        syncOnlyOnWifi = syncOnlyOnWifi,
                        syncWhileRoaming = syncWhileRoaming,
                        syncOnAppLaunch = syncOnAppLaunch,
                        syncConcurrency = syncConcurrency,
                        changeNotificationsEnabled = changeNotificationsEnabled,
                        changeNotificationFilters = changeNotificationFilters,
                        gestureActions = gestureActions,
                        gestureTargets = gestureTargets,
                        gestureModels = gestureModels,
                        objectChoices = viewModel::gestureObjectChoices,
                        navBarItems = navBarItems,
                        shortcutItems = shortcutItems,
                        scannerLens = scannerLens,
                        scannerRearLens = scannerRearLens,
                        printSettings = printSettings,
                        hiddenFieldKeys = hiddenFieldKeys,
                        pinnedModelPaths = pinnedModelPaths,
                        themeMode = themeMode,
                        themeAccent = themeAccent,
                        objectTypeAccents = objectTypeAccents,
                        showTopologyDeviceTypeImages = showTopologyDeviceTypeImages,
                        scheduledBackupEnabled = scheduledBackupEnabled,
                        scheduledBackupFrequency = scheduledBackupFrequency,
                        scheduledBackupFolderUri = scheduledBackupFolderUri,
                        scheduledBackupPasswordSet = scheduledBackupPasswordSet,
                        lastBackupAt = lastBackupAt,
                        backupError = backupError,
                        backupOperation = backupOperation,
                    ),
                actions =
                    SettingsCategoryActions(
                        onEditServer = { showEditServerDialog = true },
                        onSwitchServer = viewModel::switchServer,
                        onAddServer = viewModel::addServer,
                        onUpdateServer = viewModel::updateServer,
                        onRemoveServer = { id -> viewModel.removeServer(id) { onLoggedOut() } },
                        onTestConnection = viewModel::testConnection,
                        onShowToken = { authenticateForToken { tokenVisible = true } },
                        onHideToken = { tokenVisible = false },
                        onCopyToken = {
                            authenticateForToken {
                                context
                                    .getSystemService<ClipboardManager>()
                                    ?.setPrimaryClip(
                                        ClipData.newPlainText("API token", credentials.token)
                                    )
                                tokenCopied = true
                            }
                        },
                        onShareSetup = {
                            authenticateForToken {
                                val payload =
                                    QrConfigCodec.encodePayload(
                                        QrConfigEnvelope(
                                            createdAt = System.currentTimeMillis(),
                                            baseUrl = credentials.baseUrl,
                                            token = credentials.token,
                                        )
                                    )
                                qrBitmap = QrBitmap.encode(payload)
                            }
                        },
                        onSync = viewModel::syncNow,
                        onSetSyncAttachmentsToDisk = viewModel::setSyncAttachmentsToDisk,
                        onSetSyncOnlyOnWifi = viewModel::setSyncOnlyOnWifi,
                        onSetSyncWhileRoaming = viewModel::setSyncWhileRoaming,
                        onSetSyncOnAppLaunch = viewModel::setSyncOnAppLaunch,
                        onSetSyncConcurrency = viewModel.settingsRepository::setSyncConcurrency,
                        onSetThemeMode = viewModel.settingsRepository::setThemeMode,
                        onSetThemeAccent = viewModel.settingsRepository::setThemeAccent,
                        onShowObjectTypeColors = { objectTypeColorsDialogVisible = true },
                        onShowHiddenFields = { hiddenFieldsDialogVisible = true },
                        onSetScannerLens = viewModel::setScannerLens,
                        onSetScannerRearLens = viewModel::setScannerRearLens,
                        onUpdatePrintSettings = printSettingsViewModel::update,
                        onSetDefaultPrinter = printSettingsViewModel::setDefaultPrinter,
                        onClearDefaultPrinter = printSettingsViewModel::clearDefaultPrinter,
                        onSetShowTopologyDeviceTypeImages =
                            viewModel.settingsRepository::setShowTopologyDeviceTypeImages,
                        onExportBackup = { exportPasswordDialogVisible = true },
                        onImportBackup = {
                            openBackupLauncher.launch(
                                arrayOf("application/octet-stream", "application/json", "*/*")
                            )
                        },
                        onChooseBackupFolder = { backupFolderLauncher.launch(null) },
                        onEditScheduledBackupPassword = {
                            scheduledPassword = ""
                            scheduledPasswordDialogVisible = true
                        },
                        onSetScheduledBackupEnabled = backupViewModel::setScheduledBackupEnabled,
                        onSetScheduledBackupFrequency =
                            backupViewModel::setScheduledBackupFrequency,
                        onSetChangeNotificationsEnabled = viewModel::setChangeNotificationsEnabled,
                        onShowChangeNotifications = { changeNotificationsDialogVisible = true },
                        onSetGestureAction = viewModel::setGestureAction,
                        onSetGestureTarget = viewModel::setGestureTarget,
                        onSetGestureDetailTarget = viewModel::setGestureDetailTarget,
                        onAddNavBarItem = { action -> viewModel.addNavBarItem(action) },
                        onAddNavBarModelItem = { action, model ->
                            viewModel.addNavBarItem(action, model)
                        },
                        onAddNavBarObjectItem = { action, obj ->
                            viewModel.addNavBarItem(action, obj)
                        },
                        onRemoveNavBarItem = viewModel::removeNavBarItem,
                        onMoveNavBarItem = viewModel::moveNavBarItem,
                        onResetNavBarItems = viewModel::resetNavBarItems,
                        onAddShortcutItem = { action -> viewModel.addShortcutItem(action) },
                        onAddShortcutModelItem = { action, model ->
                            viewModel.addShortcutItem(action, model)
                        },
                        onAddShortcutObjectItem = { action, obj ->
                            viewModel.addShortcutItem(action, obj)
                        },
                        onRemoveShortcutItem = viewModel::removeShortcutItem,
                        onMoveShortcutItem = viewModel::moveShortcutItem,
                        onResetShortcutItems = viewModel::resetShortcutItems,
                    ),
            )
        }
    }
}

@Composable
private fun BackupPasswordDialog(
    title: String,
    password: String,
    confirmLabel: String,
    supportingText: String,
    onPasswordChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(supportingText, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChanged,
                    label = { Text("Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
