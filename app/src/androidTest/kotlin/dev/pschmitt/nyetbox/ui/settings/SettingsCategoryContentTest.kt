package dev.pschmitt.nyetbox.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pschmitt.nyetbox.data.repository.*
import dev.pschmitt.nyetbox.ui.theme.NyetboxTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCategoryContentTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun aboutCategoryRendersThroughExtractedContent() {
        composeRule.setContent {
            NyetboxTheme(
                themeMode = ThemeMode.Light,
                accent = ThemeAccent.Teal,
            ) {
                SettingsCategoryContent(SettingsCategory.About, state(), actions())
            }
        }

        composeRule.onNodeWithText("Build").assertExists()
        composeRule.onNodeWithContentDescription("Nyetbox app icon").assertExists()
        composeRule.onNodeWithText("License").assertExists()
        composeRule.onNodeWithText("GPLv3").assertExists()
        composeRule.onNodeWithText("Build type").assertExists()
        composeRule.onNodeWithText("Debug build").assertExists()
        composeRule.onNodeWithText("4.6.8").assertExists()
        composeRule.onNodeWithText("GitHub repository").assertExists()
    }

    @Test
    fun categoryPickerWiresPreferenceChangesToTheActionBoundary() {
        var updated: ScannerLens? = null
        composeRule.setContent {
            NyetboxTheme(
                themeMode = ThemeMode.Light,
                accent = ThemeAccent.Teal,
            ) {
                SettingsCategoryContent(
                    SettingsCategory.Camera,
                    state(),
                    actions().copy(onSetScannerLens = { updated = it }),
                )
            }
        }

        // No node in CameraSettingsContent (SettingsCategoryContent.kt) ever had a "Configure
        // scanner camera" contentDescription - every icon there is contentDescription = null. The
        // dropdown opens via clicking the list item itself.
        composeRule.onNodeWithText("Scanner default camera").performClick()
        composeRule.onNodeWithText("Front camera").performClick()

        assertEquals(ScannerLens.Front, updated)
    }

    @Test
    fun connectionCategoryRendersCachedServerVersion() {
        composeRule.setContent {
            NyetboxTheme(
                themeMode = ThemeMode.Light,
                accent = ThemeAccent.Teal,
            ) {
                SettingsCategoryContent(SettingsCategory.Connection, state(), actions())
            }
        }

        composeRule.onNodeWithText("NetBox version").assertExists()
        composeRule.onNodeWithText("4.6.8").assertExists()
    }

    private fun state() =
        SettingsCategoryState(
            credentials = NetBoxCredentials("https://netbox.test", "nbp_test.value"),
            currentUser = null,
            serverVersion = "4.6.8",
            isLoadingCurrentUser = false,
            connectionTest = ConnectionTestState.Idle,
            tokenVisible = false,
            isSyncing = false,
            syncIssue = null,
            cachedDeviceCount = 0,
            cachedObjectCount = 0,
            cachedImageCount = 0,
            persistentCacheBytes = 0,
            persistentCacheFiles = 0,
            syncAttachmentsToDisk = false,
            syncOnlyOnWifi = false,
            syncWhileRoaming = false,
            syncOnAppLaunch = true,
            changeNotificationsEnabled = false,
            changeNotificationFilters = emptySet(),
            gestureActions = emptyMap(),
            gestureTargets = emptyMap(),
            gestureModels = emptyList(),
            objectChoices = { _, _ -> flowOf(emptyList()) },
            navBarItems = emptyList(),
            shortcutItems = emptyList(),
            scannerLens = ScannerLens.Back,
            scannerRearLens = ScannerRearLens.Automatic,
            scannerResolution = ScannerResolution.Auto,
            printSettings = PrintSettings(),
            hiddenFieldKeys = emptySet(),
            pinnedModelPaths = emptySet(),
            themeMode = ThemeMode.Light,
            themeAccent = ThemeAccent.Teal,
            objectTypeAccents = emptyMap(),
            showTopologyDeviceTypeImages = false,
        )

    private fun actions() =
        SettingsCategoryActions(
            onEditServer = {},
            onTestConnection = {},
            onShowToken = {},
            onHideToken = {},
            onCopyToken = {},
            onShareSetup = {},
            onSync = {},
            onSetSyncAttachmentsToDisk = {},
            onSetSyncOnlyOnWifi = {},
            onSetSyncWhileRoaming = {},
            onSetSyncOnAppLaunch = {},
            onSetThemeMode = {},
            onSetThemeAccent = {},
            onShowObjectTypeColors = {},
            onShowHiddenFields = {},
            onSetScannerLens = {},
            onSetScannerRearLens = {},
            onSetScannerResolution = {},
            onUpdatePrintSettings = { transform -> transform(PrintSettings()) },
            onSetDefaultPrinter = { _, _ -> },
            onClearDefaultPrinter = {},
            onSetShowTopologyDeviceTypeImages = {},
            onSetChangeNotificationsEnabled = {},
            onShowChangeNotifications = {},
            onSetGestureAction = { _, _ -> },
            onSetGestureTarget = { _, _ -> },
            onSetGestureDetailTarget = { _, _ -> },
            onAddNavBarItem = {},
            onAddNavBarModelItem = { _, _ -> },
            onAddNavBarObjectItem = { _, _ -> },
            onRemoveNavBarItem = {},
            onMoveNavBarItem = { _, _ -> },
            onResetNavBarItems = {},
            onAddShortcutItem = {},
            onAddShortcutModelItem = { _, _ -> },
            onAddShortcutObjectItem = { _, _ -> },
            onRemoveShortcutItem = {},
            onMoveShortcutItem = { _, _ -> },
            onResetShortcutItems = {},
        )
}
