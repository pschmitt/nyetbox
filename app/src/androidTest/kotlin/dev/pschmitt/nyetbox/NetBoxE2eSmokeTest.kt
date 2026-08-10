package dev.pschmitt.nyetbox

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Short PR-gate journey: onboarding, cache-backed detail navigation, and settings routing. */
@RunWith(AndroidJUnit4::class)
class NetBoxE2eSmokeTest : NetBoxJourneyTest() {

    @Test
    fun onboardingDetailAndSettingsRoutes() {
        val baseUrl = arguments.getString("e2e_base_url") ?: error("e2e_base_url is required")
        val token = arguments.getString("e2e_token") ?: error("e2e_token is required")

        connectToNetBox(baseUrl, token)
        captureE2eScreenshot("smoke-01-dashboard")

        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        waitForTag("e2e-device-list-entry", 60_000)
        composeRule.onNodeWithTag("e2e-device-list-entry").performClick()
        waitForText("CI E2E Device", 120_000)
        composeRule.onNodeWithText("CI E2E Device", useUnmergedTree = true).performClick()
        waitForText("Device", 30_000)
        captureE2eScreenshot("smoke-02-device-detail")

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithContentDescription("Open navigation").performClick()
        composeRule.onNodeWithTag("e2e-settings-action").performClick()
        waitForTag("e2e-settings-screen", 30_000)
        composeRule.onNodeWithTag("e2e-settings-category-about").performClick()
        waitForTag("e2e-settings-category-screen", 30_000)
        waitForText("Build", 30_000)
        captureE2eScreenshot("smoke-03-about")
    }
}
