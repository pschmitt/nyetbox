package dev.pschmitt.nyetbox.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val TARGET_PACKAGE = "dev.pschmitt.nyetbox"
private const val SEED_ACTION = "dev.pschmitt.nyetbox.benchmark.SEED"
private const val SEED_RECEIVER = "$TARGET_PACKAGE/.benchmark.BenchmarkSeedReceiver"
private const val SEED_COMPLETE_MARKER = "NYETBOX_BENCHMARK_SEED_COMPLETE"
private const val UI_TIMEOUT_MS = 10_000L

/**
 * Generates `app/src/main/baselineProfiles/baseline-prof.txt` (NBC-426) by exercising the "cold
 * start -> dashboard -> device list scroll -> device detail -> global search" journey the perf
 * audit called out as this app's steady-state usage pattern, against the `benchmark` build type
 * (release-derived, non-debuggable, profileable - see app/build.gradle.kts).
 *
 * Runs as a black-box UiAutomator journey (no Compose semantics access into the target process),
 * matched against the app's own `e2e-*` Compose test tags exposed as UiAutomator resource-ids via
 * `testTagsAsResourceId` (MainActivity.kt) - the same tags NetBoxJourneyTest's in-process journeys
 * already use. [seedFixtureState] configures a fixture server profile and cache rows directly via
 * [dev.pschmitt.nyetbox.benchmark.BenchmarkSeedReceiver] before every profiled run, so the app
 * lands straight on a populated Dashboard without driving onboarding through a live NetBox instance
 * or waiting on a real sync - see that receiver's doc comment for why.
 */
class BaselineProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun seedFixtureState() {
        device.executeShellCommand("am force-stop $TARGET_PACKAGE")
        device.executeShellCommand("pm clear $TARGET_PACKAGE")
        // A println emitted before logcat -c below races the test framework's own log-tailing
        // (which also reads the device's logcat) and can vanish before it's ever relayed to the
        // CI console - printing dumpsys package's *return value* here instead sidesteps that,
        // since it's captured directly rather than round-tripped through logcat.
        val receiverDump =
            device
                .executeShellCommand("dumpsys package $TARGET_PACKAGE")
                .lineSequence()
                .filter { "BenchmarkSeedReceiver" in it || "receivers:" in it }
                .joinToString("\n")
        println("BaselineProfileGenerator: dumpsys package receiver info:\n$receiverDump")
        device.executeShellCommand("logcat -c")
        // pm clear above puts the app into Android's "stopped" state, in which normal broadcasts
        // - even explicit ones targeting a specific component - are enqueued but never delivered
        // (confirmed via logcat: "Enqueued broadcast ... : 0", no onReceive ever logged).
        // -f 0x20 is Intent.FLAG_INCLUDE_STOPPED_PACKAGES, overriding that.
        val broadcastResult =
            device.executeShellCommand("am broadcast -f 0x20 -n $SEED_RECEIVER -a $SEED_ACTION")
        println("BaselineProfileGenerator: am broadcast result: $broadcastResult")
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (device.executeShellCommand("logcat -d").contains(SEED_COMPLETE_MARKER)) return
            Thread.sleep(500)
        }
        val logcat = device.executeShellCommand("logcat -d")
        error("Benchmark seed broadcast did not complete within 30s. Full logcat:\n$logcat")
    }

    @Test
    fun generate() =
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()

            // Dashboard -> open the nav drawer -> device list.
            device.wait(Until.hasObject(By.desc("Open navigation")), UI_TIMEOUT_MS)
            device.findObject(By.desc("Open navigation"))?.click()
            device.wait(
                Until.hasObject(By.res(TARGET_PACKAGE, "e2e-device-list-entry")),
                UI_TIMEOUT_MS,
            )
            device.findObject(By.res(TARGET_PACKAGE, "e2e-device-list-entry"))?.click()

            // Fling the device list.
            device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "e2e-device-list")), UI_TIMEOUT_MS)
            val list = device.findObject(By.res(TARGET_PACKAGE, "e2e-device-list"))
            list?.setGestureMargin(device.displayWidth / 5)
            list?.fling(Direction.DOWN)
            device.waitForIdle()
            list?.fling(Direction.UP)
            device.waitForIdle()

            // Open the first device's detail screen, then back out to the dashboard.
            device.findObject(By.res(TARGET_PACKAGE, "e2e-device-list-row"))?.click()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()

            // Global search.
            device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "e2e-search-card")), UI_TIMEOUT_MS)
            device.findObject(By.res(TARGET_PACKAGE, "e2e-search-card"))?.click()
            device.wait(Until.hasObject(By.res(TARGET_PACKAGE, "e2e-global-search")), UI_TIMEOUT_MS)
            device.findObject(By.res(TARGET_PACKAGE, "e2e-global-search"))?.text = "benchmark"
            device.waitForIdle()
        }
}
