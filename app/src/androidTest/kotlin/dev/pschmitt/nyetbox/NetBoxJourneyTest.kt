package dev.pschmitt.nyetbox

import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.pschmitt.nyetbox.data.repository.E2E_SYNC_COMPLETE_MARKER
import org.junit.Rule

/**
 * Shared Compose-test-rule plumbing and wait/retry helpers for the real-device journeys that
 * exercise a disposable NetBox instance against MainActivity (NetBoxE2eSmokeTest, NetBoxE2eTest,
 * StoreScreenshotTest) - all three type into the same onboarding fields, wait out the same
 * dashboard initial-sync overlay, and retry clicks the same way. Each subclass still owns its own
 * distinct journey, assertions, and screenshot capture; only the plumbing lives here.
 */
abstract class NetBoxJourneyTest {

    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    protected val arguments
        get() = InstrumentationRegistry.getArguments()

    protected fun typeOnboardingCredentials(baseUrl: String, token: String) {
        composeRule.onNodeWithTag("e2e-onboarding-url").performTextInput(baseUrl)
        composeRule.onNodeWithTag("e2e-onboarding-token").performTextInput(token)
    }

    // Every journey waits out the dashboard's initial-sync overlay
    // (DashboardViewModel.showInitialSyncOverlay) the same way right after connecting: its
    // "Setting up your NetBox instance" dialog owns its own window and blocks interaction (or, for
    // screenshots, shows stale "nothing cached yet" sections) until the first sync actually
    // completes. The overlay's own visibility alone is a poor proxy for that: the first sync is
    // chunked into ~8 steps, each with its own brief isRefreshing=false gap, so waiting for the
    // overlay to merely be *absent* can return true mid-sync (confirmed: a store screenshot once
    // captured "Step 2 of 8"). Waiting for SettingsRepository's own completion marker instead
    // fixes that, but isn't sufficient alone either (confirmed on a second CI run, still racy):
    // recordSuccessfulSync() (and the logcat line) fire the instant the sync coroutine finishes,
    // but showInitialSyncOverlay is a combine().stateIn() a couple of dispatcher hops downstream
    // of that same state change, and SyncWorker resets syncProgress to null in the very same
    // breath - so the marker can already be in logcat while the overlay is still one recomposition
    // away from actually disappearing, showing its syncProgress-less fallback copy in the meantime
    // ("Fetching your inventory for the first time..."). Wait for the marker first to rule out the
    // mid-sync false positive, then the overlay's absence to let that trailing recomposition land.
    // Still not quite enough on its own (confirmed via a diagnostic logcat dump on a third CI run:
    // the marker-then-overlay-absent sequence completed, but the very next capture still caught
    // "Step 2 of 8" on a *different*, presumably more loaded, CI run - the timestamps showed only
    // ~200ms between the overlay tag reading absent from Compose's semantics tree and the
    // screenshot firing). The overlay is its own Dialog with its own Android Window - Compose's
    // semantics tree can report that window's content gone a beat before the WindowManager
    // actually finishes tearing the window down and the compositor flushes a frame without it, a
    // real OS-level step outside Compose's own idling/recomposition machinery. A short fixed
    // settle delay after the tag reads absent buys margin against exactly that gap.
    protected fun clickConnectAndWaitForDashboard() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("logcat -c")
        logDiagnostic("logcat cleared, clicking Connect")
        composeRule.onNodeWithText("Connect").performClick()
        // typeOnboardingCredentials leaves the token field focused, which raises the on-screen
        // keyboard - confirmed still up in a real dashboard store screenshot once the overlay race
        // above was otherwise fixed (it was there all along, just hidden behind the overlay's own
        // full-screen dialog in every prior failing capture). dismissKeyboard() only talks to the
        // InputMethodManager, unlike a raw back-press: confirmed on a real CI run that
        // device.pressBack() here navigated tenInch/sevenInch all the way out to the launcher home
        // screen instead - the IME apparently wasn't actually showing yet at this exact point on
        // those form factors (onboarding validation completes fast enough there to have already
        // dropped focus), so the back event fell through to real back-stack navigation instead of
        // being consumed by the (absent) IME.
        dismissKeyboard()
        waitForText("Dashboard", timeoutMillis = 45_000)
        logDiagnostic("Dashboard text visible")
        waitForLogcatMarker(E2E_SYNC_COMPLETE_MARKER, timeoutMillis = 90_000)
        logDiagnostic("sync-complete marker observed")
        waitForTagAbsent("e2e-initial-sync-overlay", timeoutMillis = 15_000)
        logDiagnostic("overlay tag confirmed absent")
        Thread.sleep(1_000)
        logDiagnostic("post-overlay settle delay elapsed")
    }

    // Closes the on-screen keyboard without risking real back-navigation. A raw
    // device.pressBack() only dismisses the IME if it happens to actually be showing at that
    // exact moment - otherwise the back event falls through to the activity's own back stack
    // (confirmed: this once navigated a journey all the way out to the launcher home screen on
    // tablet form factors). Espresso's closeSoftKeyboard() talks to the InputMethodManager
    // directly instead, so it's a safe no-op when the keyboard isn't shown.
    protected fun dismissKeyboard() {
        onView(isRoot()).perform(closeSoftKeyboard())
    }

    // Temporary diagnostic aid for NBC-416 (screenshot dashboard race): writes a timestamped
    // logcat line via the standalone `log` binary (distinct from the app's own Timber output) so
    // a downloaded full logcat dump can be correlated against exactly when each wait step and
    // screenshot capture actually happened, instead of only inferring it from the final image.
    protected fun logDiagnostic(message: String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("log -t NYETBOX_E2E_DIAG \"$message\"")
    }

    /**
     * Polls `logcat` (rather than the Compose semantics tree) for [marker] - used for signals that
     * come from a background coroutine/worker with no Compose node of its own, or whose nearest UI
     * proxy is unreliable (see [clickConnectAndWaitForDashboard]).
     */
    protected fun waitForLogcatMarker(marker: String, timeoutMillis: Long) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (device.executeShellCommand("logcat -d").contains(marker)) return
            Thread.sleep(500)
        }
        error("Logcat marker \"$marker\" did not appear within ${timeoutMillis}ms")
    }

    protected fun connectToNetBox(baseUrl: String, token: String) {
        typeOnboardingCredentials(baseUrl, token)
        clickConnectAndWaitForDashboard()
    }

    protected fun waitForText(text: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    protected fun waitForContentDescription(description: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    protected fun waitForTag(tag: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun waitForTagNotDisplayed(tag: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) { !composeRule.onNodeWithTag(tag).isDisplayed() }
    }

    // Unlike waitForTagNotDisplayed (for nodes that stay mounted but hidden, e.g. inside a closed
    // drawer), the initial-sync overlay is conditionally composed and fully leaves the semantics
    // tree once dismissed - onNodeWithTag(...).isDisplayed() throws once no node matches at all,
    // so this checks for the absence of any match instead.
    protected fun waitForTagAbsent(tag: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    // The dashboard's initial-sync overlay (DashboardScreen.InitialSyncOverlay) can reappear
    // briefly - a background sync tick (observed firing roughly every 10s throughout these
    // journeys, well beyond the one that onboarding already waited out) retriggers it for its
    // duration. Its invisible full-screen no-op-clickable Box absorbs any click while it's up, so
    // waiting for its absence once immediately before a click isn't quite enough to rule out it
    // reappearing in the instant between that check and the click actually landing. Retry the
    // click itself instead of just the wait: if the destination tag hasn't shown up shortly after
    // clicking, the overlay most likely ate that click - wait it out again and click again.
    protected fun clickUntilTagAppears(
        destinationTag: String,
        overlayTag: String = "e2e-initial-sync-overlay",
        maxAttempts: Int = 5,
        perAttemptTimeoutMillis: Long = 12_000,
        click: () -> Unit,
    ) {
        repeat(maxAttempts) { attempt ->
            waitForTagAbsent(overlayTag, timeoutMillis = 60_000)
            click()
            val landed =
                runCatching {
                        waitForTag(destinationTag, timeoutMillis = perAttemptTimeoutMillis)
                    }
                    .isSuccess
            if (landed) return
            check(attempt < maxAttempts - 1) {
                "Never reached tag '$destinationTag' after $maxAttempts clicks"
            }
        }
    }

    protected fun clickUntilTagAppears(clickTag: String, destinationTag: String) {
        clickUntilTagAppears(destinationTag = destinationTag) {
            composeRule.onNodeWithTag(clickTag).performClick()
        }
    }
}
