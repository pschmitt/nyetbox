package dev.pschmitt.nyetbox

import androidx.test.espresso.IdlingPolicies
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import java.util.concurrent.TimeUnit
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * CI's software-rendered, high-resolution tablet emulator occasionally gets slow enough for
 * unrelated system apps (observed: Pixel Launcher) to ANR mid-test, covering the screen with a
 * blocking "isn't responding" dialog unrelated to this app. Dismiss it by clicking "Wait" whenever
 * it appears so a transient system hiccup doesn't fail the whole capture.
 *
 * Also raises Espresso's own idling-resource timeouts (independent of any explicit
 * `waitUntil`/`waitForTag` timeout in a test itself): its defaults assume a responsive device and
 * were tuned for KVM-accelerated emulators, not this CI's unaccelerated software rendering, where
 * Compose recomposition genuinely taking longer to settle can trip Espresso's own watchdog
 * ("IdlingResourceTimeoutException") well before any test-level timeout is reached.
 */
class AnrDismissRule : TestWatcher() {
    private var watcherThread: Thread? = null

    override fun starting(description: Description) {
        IdlingPolicies.setMasterPolicyTimeout(120, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(120, TimeUnit.SECONDS)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        watcherThread =
            Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        runCatching {
                            if (device.findObject(By.textContains("isn't responding")) != null) {
                                device.findObject(By.text("Wait"))?.click()
                            }
                        }
                        try {
                            Thread.sleep(1_000)
                        } catch (_: InterruptedException) {
                            // finished() below interrupts this thread to stop it - almost always
                            // while it's inside this sleep, not the runCatching block above. Left
                            // unhandled, that InterruptedException escapes the thread uncaught and
                            // crashes the whole instrumentation process (observed: killed the
                            // suite's next test mid-run). Restore the interrupt flag and let the
                            // while condition above exit the loop normally instead.
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
    }

    override fun finished(description: Description) {
        watcherThread?.interrupt()
        watcherThread = null
    }
}
