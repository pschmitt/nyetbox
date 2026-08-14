package dev.pschmitt.nyetbox.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.pschmitt.nyetbox.data.schema.NetBoxRef
import dev.pschmitt.nyetbox.scanner.NetBoxTarget
import dev.pschmitt.nyetbox.ui.common.LocalCurrentRoute
import dev.pschmitt.nyetbox.ui.common.SharedMediaUploadScreen
import dev.pschmitt.nyetbox.ui.conflicts.EditConflictsScreen
import dev.pschmitt.nyetbox.ui.dashboard.DashboardScreen
import dev.pschmitt.nyetbox.ui.dashboard.ObjectChangeDiffScreen
import dev.pschmitt.nyetbox.ui.devicedetail.DeviceDetailScreen
import dev.pschmitt.nyetbox.ui.devices.DeviceListScreen
import dev.pschmitt.nyetbox.ui.generic.AddComponentScreen
import dev.pschmitt.nyetbox.ui.generic.AddItemScreen
import dev.pschmitt.nyetbox.ui.generic.GenericCreateScreen
import dev.pschmitt.nyetbox.ui.generic.GenericDetailScreen
import dev.pschmitt.nyetbox.ui.generic.GenericListScreen
import dev.pschmitt.nyetbox.ui.generic.LINKED_CREATE_RESULT_KEY
import dev.pschmitt.nyetbox.ui.generic.LinkedCreateResult
import dev.pschmitt.nyetbox.ui.generic.encodeForSavedState
import dev.pschmitt.nyetbox.ui.onboarding.OnboardingScreen
import dev.pschmitt.nyetbox.ui.pending.PendingChangesScreen
import dev.pschmitt.nyetbox.ui.scanner.ScannerScreen
import dev.pschmitt.nyetbox.ui.search.GlobalSearchScreen
import dev.pschmitt.nyetbox.ui.settings.LibrariesScreen
import dev.pschmitt.nyetbox.ui.settings.SettingsCategoryScreen
import dev.pschmitt.nyetbox.ui.settings.SettingsScreen
import dev.pschmitt.nyetbox.ui.sync.SyncSummaryScreen
import dev.pschmitt.nyetbox.ui.topology.TopologyScreen

// The typed Device list/cache (NBC-1) is richer (thumbnails, status chips, already-synced) than
// the generic object cache for the same endpoint, which may be empty until separately visited -
// used to special-case the dashboard's "Devices" stat tile onto the existing typed screen instead
// of the generic list route the other stat tiles use (see NBC-9's TODO.md entry).
private const val DEVICES_ENDPOINT_PATH = NetBoxRef.DEVICES_ENDPOINT_PATH
private const val DEVICE_TYPES_ENDPOINT_PATH = NetBoxRef.DEVICE_TYPES_ENDPOINT_PATH

private fun NavHostController.navigateToObject(endpointPath: String, id: Int) {
    // Offline-created devices have a negative local cache ID until the POST is reconciled. The
    // typed device screen only reads the server-backed DeviceDao, so keep those local objects on
    // the generic cache-first detail screen instead of showing an empty typed page.
    if (endpointPath == DEVICES_ENDPOINT_PATH && id > 0) {
        navigate(Route.DeviceDetail(id))
    } else {
        navigate(Route.Generic(endpointPath, id))
    }
}

/**
 * Pops a detail/subscreen while keeping the app's root destination alive.
 *
 * Header back actions can receive a second tap before the first pop has finished recomposing. A raw
 * [popBackStack] then removes the dashboard too, leaving the NavHost with no destination and only a
 * black Compose surface.
 */
private fun NavHostController.navigateBackSafely() {
    val currentRoute = currentDestination?.route
    if (
        currentRoute == Route.Dashboard::class.qualifiedName ||
            currentRoute == Route.Onboarding::class.qualifiedName
    ) {
        return
    }
    popBackStack()
}

@Composable
fun NetBoxNavHost(
    navController: NavHostController,
    startDestination: Route,
    onOpenDrawer: () -> Unit,
    setup: NetBoxTarget.Setup?,
    onSetupImport: (NetBoxTarget.Setup) -> Unit,
    onSetupConsumed: () -> Unit,
    onSetupCompleted: () -> Unit,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable<Route.Onboarding> {
            OnboardingScreen(
                initialSetup = setup,
                onScanSetupClick = { navController.navigate(Route.Scanner(fromOnboarding = true)) },
                onDone = {
                    onSetupConsumed()
                    navController.navigate(Route.Dashboard) {
                        popUpTo(Route.Onboarding) { inclusive = true }
                    }
                    onSetupCompleted()
                },
            )
        }
        composable<Route.Dashboard> {
            CompositionLocalProvider(LocalCurrentRoute provides Route.Dashboard) {
                DashboardScreen(
                    onOpenDrawer = onOpenDrawer,
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                    onNavigateToReference = { endpointPath, id ->
                        navController.navigateToObject(endpointPath, id)
                    },
                    onStatClick = { endpointPath, label ->
                        if (endpointPath == DEVICES_ENDPOINT_PATH) {
                            navController.navigate(Route.DeviceList) { launchSingleTop = true }
                        } else {
                            navController.navigate(Route.GenericList(endpointPath, label)) {
                                launchSingleTop = true
                            }
                        }
                    },
                    onChangeDiffClick = { changeId ->
                        navController.navigate(Route.ObjectChangeDiff(changeId))
                    },
                    onConflictsClick = { navController.navigate(Route.EditConflicts) },
                    onPendingChangesClick = { navController.navigate(Route.PendingChanges) },
                )
            }
        }
        composable<Route.ObjectChangeDiff> {
            ObjectChangeDiffScreen(
                onBack = { navController.navigateBackSafely() },
                onOpenChangedObject = { endpointPath, id ->
                    navController.navigateToObject(endpointPath, id)
                },
            )
        }
        composable<Route.PendingChanges> {
            PendingChangesScreen(onBack = { navController.navigateBackSafely() })
        }
        composable<Route.SyncSummary> { backStackEntry ->
            val route: Route.SyncSummary = backStackEntry.toRoute()
            SyncSummaryScreen(
                summary = route.summary,
                onBack = { navController.navigateBackSafely() },
            )
        }
        composable<Route.DeviceList> {
            CompositionLocalProvider(LocalCurrentRoute provides Route.DeviceList) {
                DeviceListScreen(
                    onDeviceClick = { id -> navController.navigate(Route.DeviceDetail(id)) },
                    onCreateClick = {
                        navController.navigate(Route.GenericCreate(DEVICES_ENDPOINT_PATH, "device"))
                    },
                    onOpenDrawer = onOpenDrawer,
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }
        }
        composable<Route.Topology> { backStackEntry ->
            val route: Route.Topology = backStackEntry.toRoute()
            TopologyScreen(
                focusedDeviceId = route.focusedDeviceId,
                onBack = { navController.navigateBackSafely() },
                onOpenDevice = { id -> navController.navigate(Route.DeviceDetail(id)) },
            )
        }
        composable<Route.AddComponent> { backStackEntry ->
            val route: Route.AddComponent = backStackEntry.toRoute()
            AddComponentScreen(
                onBack = { navController.navigateBackSafely() },
                onComponentClick = { component ->
                    navController.navigate(
                        Route.GenericCreate(
                            endpointPath = component.endpointPath,
                            label = component.label,
                            parentDeviceId = route.deviceId,
                        )
                    )
                },
            )
        }
        composable<Route.DeviceDetail> { backStackEntry ->
            val route: Route.DeviceDetail = backStackEntry.toRoute()
            DeviceDetailScreen(
                deviceId = route.deviceId,
                onBack = { navController.navigateBackSafely() },
                onEditClick = {
                    navController.navigate(
                        Route.Generic(
                            endpointPath = DEVICES_ENDPOINT_PATH,
                            id = route.deviceId,
                            startInEdit = true,
                        )
                    )
                },
                onEditFieldClick = { fieldKey ->
                    navController.navigate(
                        Route.Generic(
                            endpointPath = DEVICES_ENDPOINT_PATH,
                            id = route.deviceId,
                            focusFieldKey = fieldKey,
                        )
                    )
                },
                onDeviceTypeClick = { id, breadcrumb ->
                    navController.navigate(
                        Route.Generic(DEVICE_TYPES_ENDPOINT_PATH, id, breadcrumb)
                    )
                },
                onReferenceClick = { endpointPath, id, breadcrumb ->
                    navController.navigate(Route.Generic(endpointPath, id, breadcrumb))
                },
                onRackPositionClick = { rackId, deviceId, breadcrumb ->
                    navController.navigate(
                        Route.Generic(
                            endpointPath = "api/dcim/racks/",
                            id = rackId,
                            breadcrumb = breadcrumb,
                            highlightDeviceId = deviceId,
                        )
                    )
                },
                onAddComponent = { navController.navigate(Route.AddComponent(route.deviceId)) },
                onOpenTopology = { navController.navigate(Route.Topology(route.deviceId)) },
                onChangeDiffClick = { changeId ->
                    navController.navigate(Route.ObjectChangeDiff(changeId))
                },
                onDeleted = { navController.navigateBackSafely() },
            )
        }
        composable<Route.GenericList> { backStackEntry ->
            val route: Route.GenericList = backStackEntry.toRoute()
            CompositionLocalProvider(LocalCurrentRoute provides route) {
                GenericListScreen(
                    onObjectClick = { id ->
                        navController.navigateToObject(route.endpointPath, id)
                    },
                    onCreateClick = {
                        navController.navigate(Route.GenericCreate(route.endpointPath, route.label))
                    },
                    onOpenDrawer = onOpenDrawer,
                    onNavigate = { target ->
                        navController.navigate(target) { launchSingleTop = true }
                    },
                )
            }
        }
        composable<Route.GlobalSearch> {
            CompositionLocalProvider(LocalCurrentRoute provides Route.GlobalSearch) {
                GlobalSearchScreen(
                    onResultClick = { endpointPath, id, _ ->
                        navController.navigateToObject(endpointPath, id)
                    },
                    onBack = { navController.navigateBackSafely() },
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }
        }
        composable<Route.SharedMedia> { backStackEntry ->
            val route: Route.SharedMedia = backStackEntry.toRoute()
            GlobalSearchScreen(
                selectionPrompt =
                    "Choose an item for ${route.filename?.takeIf(String::isNotBlank) ?: "the shared file"}",
                onResultClick = { endpointPath, id, display ->
                    navController.navigate(
                        Route.SharedMediaUpload(
                            endpointPath = endpointPath,
                            objectId = id,
                            targetLabel = display,
                            uri = route.uri,
                            mimeType = route.mimeType,
                            filename = route.filename,
                        )
                    )
                },
                onBack = { navController.navigateBackSafely() },
                onNavigate = { target ->
                    navController.navigate(target) { launchSingleTop = true }
                },
            )
        }
        composable<Route.SharedMediaUpload> { backStackEntry ->
            val route: Route.SharedMediaUpload = backStackEntry.toRoute()
            SharedMediaUploadScreen(
                endpointPath = route.endpointPath,
                objectId = route.objectId,
                targetLabel = route.targetLabel,
                uri = route.uri,
                mimeType = route.mimeType,
                filename = route.filename,
                onBack = { navController.navigateBackSafely() },
                onUploaded = { navController.popBackStack(Route.SharedMedia, inclusive = true) },
            )
        }
        composable<Route.Generic> { backStackEntry ->
            val route: Route.Generic = backStackEntry.toRoute()
            GenericDetailScreen(
                highlightDeviceId = route.highlightDeviceId,
                onBack = { navController.navigateBackSafely() },
                onNavigateToReference = { endpointPath, id, _ ->
                    navController.navigateToObject(endpointPath, id)
                },
                onCreateLinkedItem = { fieldKey, endpointPath, label, reopenFocusedEditor ->
                    navController.navigate(
                        Route.GenericCreate(
                            endpointPath = endpointPath,
                            label = label,
                            returnFieldKey = fieldKey,
                            reopenFocusedEditor = reopenFocusedEditor,
                        )
                    )
                },
                onAddComponent = { navController.navigate(Route.AddComponent(route.id)) },
                onChangeDiffClick = { changeId ->
                    navController.navigate(Route.ObjectChangeDiff(changeId))
                },
            )
        }
        composable<Route.Add> {
            CompositionLocalProvider(LocalCurrentRoute provides Route.Add) {
                AddItemScreen(
                    onBack = { navController.navigateBackSafely() },
                    onModelClick = { model ->
                        navController.navigate(
                            Route.GenericCreate(model.endpointPath, model.modelLabel)
                        )
                    },
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }
        }
        composable<Route.GenericCreate> { backStackEntry ->
            val route: Route.GenericCreate = backStackEntry.toRoute()
            GenericCreateScreen(
                onBack = { navController.navigateBackSafely() },
                onCreated = { endpointPath, id, display ->
                    if (route.returnFieldKey != null) {
                        val result =
                            LinkedCreateResult(
                                fieldKey = route.returnFieldKey,
                                endpointPath = endpointPath,
                                id = id,
                                display = display ?: "#$id",
                                reopenFocusedEditor = route.reopenFocusedEditor,
                            )
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(
                                LINKED_CREATE_RESULT_KEY,
                                result.encodeForSavedState(),
                            )
                        navController.popBackStack()
                    } else {
                        navController.popBackStack()
                        navController.navigateToObject(endpointPath, id)
                    }
                },
            )
        }
        composable<Route.Scanner> { backStackEntry ->
            val route: Route.Scanner = backStackEntry.toRoute()
            CompositionLocalProvider(LocalCurrentRoute provides route) {
                ScannerScreen(
                    onTargetFound = { target ->
                        val destination =
                            when (target) {
                                is NetBoxTarget.Device -> Route.DeviceDetail(target.id)
                                is NetBoxTarget.DeviceAssetTag -> return@ScannerScreen
                                is NetBoxTarget.Object ->
                                    Route.Generic(target.endpointPath, target.id)
                                is NetBoxTarget.Setup -> {
                                    onSetupImport(target)
                                    navController.navigate(Route.Onboarding) {
                                        popUpTo(Route.Scanner()) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                    return@ScannerScreen
                                }
                            }
                        navController.navigate(destination) {
                            popUpTo(Route.Scanner()) { inclusive = true }
                        }
                    },
                    onBack = { navController.navigateBackSafely() },
                    onNavigate = { target ->
                        navController.navigate(target) { launchSingleTop = true }
                    },
                    showBottomBar = !route.fromOnboarding,
                )
            }
        }
        composable<Route.Settings> {
            SettingsScreen(
                onBack = { navController.navigateBackSafely() },
                onCategoryClick = { category ->
                    navController.navigate(Route.SettingsCategory(category))
                },
            )
        }
        composable<Route.SettingsCategory> { backStackEntry ->
            val route: Route.SettingsCategory = backStackEntry.toRoute()
            SettingsCategoryScreen(
                category = route.category,
                openServerManager = route.openServerManager,
                onBack = { navController.navigateBackSafely() },
                onLoggedOut = {
                    navController.navigate(Route.Onboarding) { popUpTo(0) { inclusive = true } }
                },
                onShowLibraries = { navController.navigate(Route.Libraries) },
            )
        }
        composable<Route.Libraries> {
            LibrariesScreen(onBack = { navController.navigateBackSafely() })
        }
        composable<Route.EditConflicts> {
            EditConflictsScreen(onBack = { navController.navigateBackSafely() })
        }
    }
}
