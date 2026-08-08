package dev.pschmitt.nyetbox

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.pschmitt.nyetbox.crash.CrashReportStore
import dev.pschmitt.nyetbox.data.repository.DeviceRepository
import dev.pschmitt.nyetbox.data.repository.GestureAction
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.scanner.NetBoxTarget
import dev.pschmitt.nyetbox.shortcuts.ShortcutSyncer
import dev.pschmitt.nyetbox.sync.SyncNotifier
import dev.pschmitt.nyetbox.sync.SyncScheduler
import dev.pschmitt.nyetbox.ui.common.CrashReportDialog
import dev.pschmitt.nyetbox.ui.gestures.LocalActivePointerCount
import dev.pschmitt.nyetbox.ui.gestures.trackActivePointerCount
import dev.pschmitt.nyetbox.ui.gestures.withConfiguredGestures
import dev.pschmitt.nyetbox.ui.navigation.MainNavigationDrawer
import dev.pschmitt.nyetbox.ui.navigation.NetBoxNavHost
import dev.pschmitt.nyetbox.ui.navigation.Route
import dev.pschmitt.nyetbox.ui.settings.SettingsCategory
import dev.pschmitt.nyetbox.ui.theme.NyetboxTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var syncNotifier: SyncNotifier
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var shortcutSyncer: ShortcutSyncer

    private var pendingTarget by mutableStateOf<NetBoxTarget?>(null)
    private var pendingSharedMedia by mutableStateOf<SharedMediaPayload?>(null)
    private var pendingSetup by mutableStateOf<NetBoxTarget.Setup?>(null)
    private var pendingReconciliationSummary by mutableStateOf<String?>(null)
    private var pendingCrashReport by mutableStateOf<String?>(null)
    // Populated by a launcher shortcut or widget tap (see ShortcutSyncer/NyetboxGlanceWidget) -
    // both hand Android a plain Intent carrying the same extras an in-app gesture shortcut would
    // resolve, so this dispatches through the exact same dispatchGestureAction used below.
    private var pendingGesture by mutableStateOf<Pair<GestureAction, GestureTarget?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingCrashReport = CrashReportStore(applicationContext).takePending()
        pendingTarget = extractNetBoxTarget(intent)
        pendingSharedMedia = extractSharedMedia(intent)
        pendingReconciliationSummary =
            intent.getStringExtra(SyncNotifier.EXTRA_RECONCILIATION_SUMMARY)
        pendingGesture = extractGestureAction(intent)?.let { it to extractGestureTarget(intent) }

        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle()
            val themeAccent by settingsRepository.themeAccent.collectAsStateWithLifecycle()
            NyetboxTheme(themeMode = themeMode, accent = themeAccent) {
                val navController = rememberNavController()
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val coroutineScope = rememberCoroutineScope()
                val gestureActions by
                    settingsRepository.gestureActions.collectAsStateWithLifecycle()
                val gestureTargets by
                    settingsRepository.gestureTargets.collectAsStateWithLifecycle()
                val shortcutItems by
                    settingsRepository.shortcutItems.collectAsStateWithLifecycle()
                // Publishes launcher shortcuts on first composition (covering cold start) and
                // republishes on every edit in Settings, so a reorder/add/remove takes effect
                // without needing to relaunch the app. This is the only sync() call site.
                LaunchedEffect(shortcutItems) { shortcutSyncer.sync() }
                val startDestination =
                    if (settingsRepository.isConfigured) Route.Dashboard else Route.Onboarding

                // Background sync failure notifications (NBC-23) need POST_NOTIFICATIONS on API
                // 33+ - requested once at startup, same shape as ScannerScreen's CAMERA request.
                // Denial just means SyncNotifier silently skips posting later; nothing here
                // blocks on the result.
                val notificationPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) {}
                LaunchedEffect(Unit) {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                }

                // Handles both cold start (pendingTarget set above, before setContent) and a warm
                // relaunch of this singleTask activity via onNewIntent below.
                LaunchedEffect(pendingTarget) {
                    val target = pendingTarget ?: return@LaunchedEffect
                    when (target) {
                        is NetBoxTarget.Setup -> {
                            pendingSetup = target
                            if (settingsRepository.isConfigured) {
                                navController.navigate(Route.Onboarding) { launchSingleTop = true }
                            }
                        }
                        is NetBoxTarget.Device,
                        is NetBoxTarget.Object -> {
                            if (settingsRepository.isConfigured) {
                                val destination = routeForTarget(target) ?: return@LaunchedEffect
                                navController.navigate(destination)
                            }
                        }
                        is NetBoxTarget.DeviceAssetTag -> {
                            if (settingsRepository.isConfigured) {
                                deviceRepository.findByAssetTag(target.assetTag)?.let { device ->
                                    navController.navigate(Route.DeviceDetail(device.id))
                                }
                            }
                        }
                    }
                    pendingTarget = null
                }

                LaunchedEffect(pendingSharedMedia) {
                    val sharedMedia = pendingSharedMedia ?: return@LaunchedEffect
                    if (settingsRepository.isConfigured) {
                        navController.navigate(
                            Route.SharedMedia(
                                uri = sharedMedia.uri,
                                mimeType = sharedMedia.mimeType,
                                filename = sharedMedia.filename,
                            )
                        )
                        pendingSharedMedia = null
                    }
                }

                LaunchedEffect(pendingReconciliationSummary) {
                    val summary = pendingReconciliationSummary ?: return@LaunchedEffect
                    if (settingsRepository.isConfigured) {
                        navController.navigate(Route.SyncSummary(summary))
                    }
                    pendingReconciliationSummary = null
                }

                // Handles a launcher shortcut or widget tap arriving via onCreate/onNewIntent -
                // shares dispatchGestureAction with the in-app swipe-gesture handler below, so a
                // shortcut/widget action resolves identically to the same action configured as a
                // gesture.
                LaunchedEffect(pendingGesture) {
                    val (action, target) = pendingGesture ?: return@LaunchedEffect
                    if (settingsRepository.isConfigured) {
                        dispatchGestureAction(action, target, navController)
                    }
                    pendingGesture = null
                }

                MainNavigationDrawer(
                    drawerState = drawerState,
                    onDeviceListClick = {
                        navController.navigate(Route.DeviceList) { launchSingleTop = true }
                    },
                    onModelClick = { model ->
                        navController.navigate(
                            Route.GenericList(model.endpointPath, model.modelLabel)
                        ) {
                            launchSingleTop = true
                        }
                    },
                    onTopologyClick = {
                        navController.navigate(Route.Topology()) { launchSingleTop = true }
                    },
                    onSettingsClick = { navController.navigate(Route.Settings) },
                    onAboutClick = {
                        navController.navigate(Route.SettingsCategory(SettingsCategory.About))
                    },
                ) {
                    val gestureModifier =
                        Modifier.withConfiguredGestures(
                            enabled = settingsRepository.isConfigured,
                            actions = gestureActions,
                            targets = gestureTargets,
                        ) { shortcut, action, target ->
                            dispatchGestureAction(action, target, navController)
                        }
                    var activePointerCount by remember { mutableIntStateOf(0) }
                    CompositionLocalProvider(LocalActivePointerCount provides activePointerCount) {
                        Box(
                            Modifier.fillMaxSize()
                                .trackActivePointerCount { activePointerCount = it }
                                .then(gestureModifier)
                        ) {
                            NetBoxNavHost(
                                navController = navController,
                                startDestination = startDestination,
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                setup = pendingSetup,
                                onSetupImport = { setup -> pendingSetup = setup },
                                onSetupConsumed = { pendingSetup = null },
                                onSetupCompleted = {
                                    pendingSharedMedia?.let { sharedMedia ->
                                        navController.navigate(
                                            Route.SharedMedia(
                                                uri = sharedMedia.uri,
                                                mimeType = sharedMedia.mimeType,
                                                filename = sharedMedia.filename,
                                            )
                                        )
                                        pendingSharedMedia = null
                                    }
                                },
                            )
                        }
                    }
                }
                pendingCrashReport?.let { report ->
                    CrashReportDialog(
                        report = report,
                        onCopy = { copyCrashReport(report) },
                        onRestart = ::restartApplication,
                        onDismiss = { pendingCrashReport = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingTarget = extractNetBoxTarget(intent)
        pendingSharedMedia = extractSharedMedia(intent)
        pendingReconciliationSummary =
            intent.getStringExtra(SyncNotifier.EXTRA_RECONCILIATION_SUMMARY)
        pendingGesture = extractGestureAction(intent)?.let { it to extractGestureTarget(intent) }
    }

    override fun onStart() {
        super.onStart()
        syncNotifier.onAppForeground()
    }

    override fun onStop() {
        syncNotifier.onAppBackground()
        super.onStop()
    }

    // Shared by the in-app swipe-gesture handler and pendingGesture's LaunchedEffect (launcher
    // shortcut/widget taps), so the same action+target pair always resolves the same way
    // regardless of which of the three producers triggered it.
    private fun dispatchGestureAction(
        action: GestureAction,
        target: GestureTarget?,
        navController: NavController,
    ) {
        when (action) {
            GestureAction.Sync -> syncScheduler.syncNow()
            GestureAction.OfflineOn -> settingsRepository.setOfflineMode(true)
            GestureAction.OfflineOff -> settingsRepository.setOfflineMode(false)
            else -> routeForGesture(action, target)?.let(navController::navigate)
        }
    }

    private fun copyCrashReport(report: String) {
        getSystemService<ClipboardManager>()
            ?.setPrimaryClip(ClipData.newPlainText("Crash report", report))
        Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
    }

    private fun restartApplication() {
        pendingCrashReport = null
        packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(launchIntent)
        }
        finishAffinity()
    }
}
