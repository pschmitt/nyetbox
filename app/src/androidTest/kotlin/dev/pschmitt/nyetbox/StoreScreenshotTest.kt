package dev.pschmitt.nyetbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Captures Play Store listing screenshots (en-US only, see fastlane/Screengrabfile) against a
 * disposable NetBox instance (`ci/netbox/docker-compose.yml`) seeded with a small realistic-
 * looking demo rack (`ci/netbox/seed_screenshots.py`), reusing the same
 * onboarding/dashboard/device-detail/topology/search/settings journey as [NetBoxE2eSmokeTest]
 * (shared plumbing lives in [NetBoxJourneyTest]). Never point this test at a real NetBox instance -
 * the screenshots it produces show whatever inventory data the connected instance has.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest : NetBoxJourneyTest() {
    companion object {
        @get:ClassRule @JvmStatic val localeTestRule = LocaleTestRule()
    }

    @get:Rule val anrDismissRule = AnrDismissRule()

    @Test
    fun captureStoreScreenshots() {
        try {
            hideSystemBars()

            val baseUrl = arguments.getString("e2e_base_url") ?: error("e2e_base_url is required")
            val token = arguments.getString("e2e_token") ?: error("e2e_token is required")

            connectToNetBox(baseUrl, token)

            captureJourney(suffix = "_light")
            // captureJourney("_light") always ends on Settings (see below); switch the color
            // scheme there for real, through the same UI a user would use, then repeat the whole
            // journey unsuffixed so the store listing gets both variants from one test run. The
            // dark pass is deliberately left unsuffixed (rather than the light one) so its
            // filenames sort alphabetically before the "_light" ones and the dark screenshots are
            // what the Play Store listing shows first.
            switchToDarkModeAndReturnToDashboard()
            captureJourney(suffix = "")
        } catch (t: Throwable) {
            // The emulator is gone by the time a later CI step could pull a screencap/logcat -
            // android-emulator-runner tears it down synchronously as part of its own failed step,
            // not via a job-level post hook. screengrab itself is no help either: it skips pulling
            // any Screengrab.screenshot() captures at all once the test class reports a failure,
            // dashboard/topology shots included. captureE2eScreenshot (already used by the E2E
            // suite) writes straight to the app's external files dir instead, which the workflow
            // can adb pull independently of screengrab's own success-gated pull step.
            captureE2eScreenshot("FAILURE_debug")
            throw t
        }
    }

    // Large-screen (7in/10in) AVDs in gesture-nav mode show a persistent taskbar dock (pinned app
    // icons) at the bottom that phone-sized screens never get - Screengrab.screenshot() takes a
    // whole-display capture via UiAutomator, so it lands in every tablet screenshot otherwise.
    // Fastlane's screengrab has no option to exclude it (only a "clean status bar" feature for the
    // top bar), so ask the window itself to go edge-to-edge before the journey starts.
    private fun hideSystemBars() {
        composeRule.activity.runOnUiThread {
            val window = composeRule.activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun switchToDarkModeAndReturnToDashboard() {
        // "Color scheme" lives under the "Display" category, not directly on the top-level
        // Settings list (see SettingsCategory.kt/SettingsCategoryContent.kt). That list is a
        // plain scrollable Column (every item stays composed, just not all on-screen at once),
        // and "Display" sits below the fold - performClick() alone dispatches a touch at the
        // node's true (off-screen) coordinates and silently does nothing, so scroll it into view
        // first (confirmed via the FAILURE_debug capture: this click landed with zero effect).
        composeRule.onNodeWithText("Display").performScrollTo().performClick()
        waitForText("Color scheme", 30_000)
        // "Appearance" is the first group card on this category screen too, but the same
        // scroll-into-view treatment is cheap insurance now that this exact failure mode has
        // shown up once already.
        composeRule.onNodeWithText("Color scheme").performScrollTo().performClick()
        waitForText("Dark", 30_000)
        // Two separate debug captures (with and without a settle delay first) both showed the
        // dropdown still fully open, all three options visible, right after
        // onNodeWithText("Dark").performClick() supposedly "landed" - Compose's own synthetic
        // touch dispatch isn't actually reaching this DropdownMenu item, consistently, regardless
        // of timing. Use UiAutomator's real system-level touch dispatch instead, which does not
        // go through Compose's semantics-tree coordinate math at all.
        // waitForText above only confirms "Dark" exists in Compose's own semantics tree - a raw
        // UiDevice.findObject() call reads the accessibility tree instead, which lags slightly
        // behind and can still come back empty right at this point (seen once as a flake:
        // IllegalStateException from the error() below). device.wait() polls the accessibility
        // tree itself until the node actually shows up there too.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wait(Until.findObject(By.text("Dark")), 10_000)?.click()
            ?: error("No \"Dark\" node found via UiAutomator")
        // Confirmed by repeated FAILURE_debug captures on tenInch/sevenInch (both with the Compose
        // click and this UiAutomator click): selecting Dark lands the app back on Dashboard
        // directly, in dark colors, without ever needing the Back presses this used to do to
        // unwind Display -> Settings -> Dashboard. That never reproduced on phone, so it's
        // presumably a race between this selection and some app-side reaction to the settings
        // change that resolves fast enough on phone for the old Back-based path to still work
        // there, but not on the larger/slower tablet emulators - wait for the real destination
        // directly instead of asserting anything about the Settings screen surviving the click.
        waitForText("Dashboard", 30_000)
    }

    private fun captureJourney(suffix: String) {
        // connectToNetBox (and switchToDarkModeAndReturnToDashboard, for the dark pass) already
        // waited for SettingsRepository's E2E_SYNC_COMPLETE_MARKER, not just the overlay's
        // fleeting absence - once that fires, DashboardViewModel.showInitialSyncOverlay can never
        // go true again for this profile (its own condition requires lastSuccessfulSyncAt to
        // still be null), so no re-check is needed here the way there once was.
        captureScreenshot("01_dashboard$suffix")

        clickUntilTagAppears(
            destinationTag = "e2e-device-list-entry",
            perAttemptTimeoutMillis = 60_000,
        ) {
            composeRule.onNodeWithContentDescription("Open navigation").performClick()
        }
        composeRule.onNodeWithTag("e2e-device-list-entry").performClick()
        waitForText("core-sw-01", 120_000)
        composeRule.onNodeWithText("core-sw-01", useUnmergedTree = true).performClick()
        // The detail screen's own per-device fetch is racy against just navigating in - even
        // though the underlying NetBox API responds in well under a second, this screen sometimes
        // still shows "Not cached yet" seconds later. Rather than fight that race with a longer
        // wait, force a deterministic refresh via the overflow menu (present in the TopAppBar
        // regardless of load state) before waiting for real content.
        waitForContentDescription("More actions", 30_000)
        composeRule.onNodeWithContentDescription("More actions").performClick()
        composeRule.onNodeWithText("Sync").performClick()
        // Wait for the rack name from the loaded identity card. Neither the generic "Device"
        // app-bar title nor the site name/asset tag work here: the device list row we just left
        // already renders "<site> · <device type>" plus the asset tag badge as its subtitle, so
        // waiting on any of those can be satisfied by that screen's residual composition during
        // the navigation transition instead of the detail screen's own fetch actually completing.
        // The manufacturer field is a safe value but risks being below the fold in this
        // LazyColumn and never composed without scrolling - rack name is both distinct from the
        // list row and rendered above the fold.
        waitForText("Rack A1", 60_000)
        // Let the "Sync queued" / "Sync complete" snackbars clear before capturing - one of
        // them otherwise overlaps the identity card. A text-absence wait raced with the
        // snackbar's exit-fade animation (semantics can report "gone" slightly before the fade
        // finishes, and there are two snackbars in sequence, not one), so a fixed settle delay
        // covering Material3's default SnackbarDuration.Short is simpler and more reliable here.
        Thread.sleep(5_000)
        captureScreenshot("02_device_detail$suffix")

        composeRule.onNodeWithContentDescription("More actions").performClick()
        waitForText("Open topology", 30_000)
        composeRule.onNodeWithText("Open topology").performClick()
        waitForContentDescription("Topology graph with 4 nodes and 3 connections", 120_000)
        captureScreenshot("03_topology$suffix")

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForContentDescription("More actions", 30_000)
        composeRule.onNodeWithContentDescription("Back").performClick()
        // "e2e-device-list-entry" is the sidebar drawer's own "Devices" item (see Sidebar.kt), not
        // a row on this screen - ModalNavigationDrawer keeps drawer content mounted in the
        // semantics tree even while closed, so waiting for that tag to merely *exist* is always
        // trivially true and never actually confirmed anything. The real, screenshot-confirmed
        // failure is that the drawer's scrim can still be open here (open since the very first
        // "Open navigation" click earlier in this journey) and intercepts the click on "Home"
        // meant for the NavigationRail/NavigationBar tab underneath it. Wait for that same tag to
        // stop being *displayed* instead, which only holds once the drawer has actually closed.
        waitForTagNotDisplayed("e2e-device-list-entry", 30_000)
        // assertIsDisplayed guards against NetBoxResponsiveScaffold's rail-vs-TopAppBar padding
        // bug (fixed alongside this test): the rail's first item was laid out underneath the
        // TopAppBar, present and "clickable" in the semantics tree at its true occluded bounds but
        // invisible and unreachable by a real tap - performClick() alone doesn't catch that.
        composeRule.onNodeWithText("Home").assertIsDisplayed().performClick()
        waitForTag("e2e-search-card", 30_000)
        // See clickUntilTagAppears's doc for why a single wait-then-click isn't reliable here.
        clickUntilTagAppears(clickTag = "e2e-search-card", destinationTag = "e2e-global-search")
        composeRule.onNodeWithTag("e2e-global-search").performTextInput("core-sw-01")
        // Wait for an actual result card, not text that may also be present in the search field or
        // in a previous composition. A missing result must fail the capture instead of silently
        // producing an empty store-listing asset.
        waitForTag("e2e-search-result", 60_000)
        // performTextInput leaves the field focused, which raises the on-screen keyboard and
        // covers the bottom half of the store screenshot. dismissKeyboard() (Espresso's
        // closeSoftKeyboard(), talking to the InputMethodManager directly) closes it without any
        // risk of falling through to real back-navigation the way a raw device.pressBack() can
        // when the IME isn't actually showing at that exact moment (confirmed elsewhere in this
        // journey - see NetBoxJourneyTest.dismissKeyboard()'s own doc comment).
        dismissKeyboard()
        Thread.sleep(500)
        captureScreenshot("04_search$suffix")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Home").performClick()
        waitForText("Search NetBox", 30_000)
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        waitForContentDescription("Settings", 30_000)
        composeRule.onNodeWithContentDescription("Settings").performClick()
        waitForText("Settings", 30_000)
        captureScreenshot("05_settings$suffix")
    }

    private fun captureScreenshot(name: String) {
        // AnrDismissRule's background watcher only polls once a second, so a Screengrab capture
        // can still land squarely on a transient ANR dialog between polls (confirmed: happened
        // once, on tenInch's 05_settings_dark, in an otherwise fully green run) - block
        // synchronously right before each capture instead of only relying on the watcher to have
        // already caught up.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = System.currentTimeMillis() + 15_000
        while (
            device.findObject(By.textContains("isn't responding")) != null &&
                System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(200)
        }
        logDiagnostic("capturing screenshot $name")
        Screengrab.screenshot(name)
    }
}
