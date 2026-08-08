package dev.pschmitt.nyetbox.benchmark

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.data.db.CacheDatabaseManager
import dev.pschmitt.nyetbox.data.db.DeviceEntity
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Broadcast action [BenchmarkSeedReceiver] responds to - see its own doc comment. */
const val BENCHMARK_SEED_ACTION = "dev.pschmitt.nyetbox.benchmark.SEED"

/**
 * Logcat marker emitted once [BenchmarkSeedReceiver] finishes - the `:baselineprofile` module's
 * generator polls logcat for this the same way [dev.pschmitt.nyetbox.data.repository.E2E_SYNC_COMPLETE_MARKER]
 * is polled by the instrumented E2E journeys (`NetBoxJourneyTest.waitForLogcatMarker`), since a
 * plain `BroadcastReceiver` has no result channel a shell-invoked `am broadcast` can wait on.
 */
const val BENCHMARK_SEED_COMPLETE_MARKER = "NYETBOX_BENCHMARK_SEED_COMPLETE"

private const val TAG = "BenchmarkSeedReceiver"
private const val FIXTURE_BASE_URL = "https://benchmark.invalid"
private const val FIXTURE_TOKEN = "benchmark-fixture-token"

/**
 * The androidx.baselineprofile plugin's build types this receiver may legitimately act on -
 * `benchmarkRelease` (the real profile-collection run) and `nonMinifiedRelease` (a correctness
 * pass `generateBaselineProfile` also runs against, on a debuggable-adjacent variant closer to
 * Studio's own profiler). Compiled into `main` (all build types, including real `release`) since
 * Gradle source sets can't easily target two specific non-default build types without
 * duplicating this file - see the runtime guard below instead.
 */
private val BENCHMARK_BUILD_TYPES = setOf("benchmarkRelease", "nonMinifiedRelease")

/**
 * A no-op on the real `debug`/`release` builds shipped to users (see [BENCHMARK_BUILD_TYPES]) -
 * lets the `:baselineprofile` module's [androidx.benchmark.macro.junit4.BaselineProfileRule]
 * journey start from an already-configured, already-synced Dashboard instead of driving
 * onboarding through a live NetBox instance every generation run. `BaselineProfileRule` drives
 * the target app as a black box via UiAutomator with no Compose semantics access of its own, and
 * this app's onboarding has no non-UI shortcut otherwise (credentials persist through
 * [SettingsRepository]'s `EncryptedSharedPreferences`, which can't be pre-seeded from outside the
 * app's own process) - so the generator's setup step instead force-stops the app, `am broadcast`s
 * this receiver to configure a fixture server profile and seed a few cache rows directly via the
 * real repository/DAO write paths, then relaunches for the actual profiled cold start. See
 * NBC-426.
 *
 * Logs via [android.util.Log], not Timber: [dev.pschmitt.nyetbox.NyetboxApp.onCreate] only plants
 * a Timber tree for `BuildConfig.DEBUG` builds, and this receiver only ever does anything on the
 * non-debuggable `benchmarkRelease`/`nonMinifiedRelease` build types, where Timber would be a
 * silent no-op.
 */
@SuppressLint("LogNotTimber")
@AndroidEntryPoint
class BenchmarkSeedReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CacheDatabaseManagerEntryPoint {
        fun cacheDatabaseManager(): CacheDatabaseManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action} buildType=${BuildConfig.BUILD_TYPE}")
        if (
            BuildConfig.BUILD_TYPE !in BENCHMARK_BUILD_TYPES ||
                intent.action != BENCHMARK_SEED_ACTION
        ) {
            Log.w(TAG, "onReceive: ignoring - build type or action mismatch")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                seed(context.applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "seed() failed", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun seed(appContext: Context) {
        // settingsRepository.save() must run before CacheDatabaseManager is ever touched:
        // CacheDatabaseManager is a lazily-constructed Hilt singleton that snapshots
        // settingsRepository.activeServer.value the moment it's first built, so resolving it via
        // EntryPointAccessors only after save() guarantees it opens the fixture profile's database
        // rather than the pre-onboarding placeholder one (this is also why it's deliberately not a
        // plain @Inject field here - that would resolve it too early, alongside settingsRepository,
        // before save() has run).
        settingsRepository.save(FIXTURE_BASE_URL, FIXTURE_TOKEN)

        val cacheDatabaseManager =
            EntryPointAccessors.fromApplication(
                    appContext,
                    CacheDatabaseManagerEntryPoint::class.java,
                )
                .cacheDatabaseManager()
        val db = cacheDatabaseManager.activeDatabase.value
        db.deviceDao().upsertAll(FIXTURE_DEVICES)

        // Lets DashboardViewModel.showInitialSyncOverlay know a sync already happened, so the
        // profiled cold start lands straight on the real dashboard content instead of the
        // "setting up your NetBox instance" overlay.
        settingsRepository.recordSuccessfulSync()

        Log.i(TAG, BENCHMARK_SEED_COMPLETE_MARKER)
    }
}

private val FIXTURE_DEVICES =
    listOf(
        DeviceEntity(
            id = 1,
            name = "benchmark-switch-01",
            url = "$FIXTURE_BASE_URL/api/dcim/devices/1/",
            statusValue = "active",
            statusLabel = "Active",
            siteName = "Benchmark Site",
            siteId = 1,
            rackName = "Rack 1",
            rackId = 1,
            position = 10.0,
            roleName = "Switch",
            manufacturerName = "Benchmark Manufacturer",
            deviceTypeModel = "Benchmark Switch Model",
            deviceTypeId = 1,
            serial = "BM-0001",
            assetTag = "AT-0001",
            primaryIp = "192.0.2.1/24",
            comments = null,
            lastUpdated = null,
            syncedAt = System.currentTimeMillis(),
        ),
        DeviceEntity(
            id = 2,
            name = "benchmark-router-01",
            url = "$FIXTURE_BASE_URL/api/dcim/devices/2/",
            statusValue = "active",
            statusLabel = "Active",
            siteName = "Benchmark Site",
            siteId = 1,
            rackName = "Rack 1",
            rackId = 1,
            position = 20.0,
            roleName = "Router",
            manufacturerName = "Benchmark Manufacturer",
            deviceTypeModel = "Benchmark Router Model",
            deviceTypeId = 2,
            serial = "BM-0002",
            assetTag = "AT-0002",
            primaryIp = "192.0.2.2/24",
            comments = null,
            lastUpdated = null,
            syncedAt = System.currentTimeMillis(),
        ),
        DeviceEntity(
            id = 3,
            name = "benchmark-server-01",
            url = "$FIXTURE_BASE_URL/api/dcim/devices/3/",
            statusValue = "offline",
            statusLabel = "Offline",
            siteName = "Benchmark Site",
            siteId = 1,
            rackName = "Rack 2",
            rackId = 2,
            position = 5.0,
            roleName = "Server",
            manufacturerName = "Benchmark Manufacturer",
            deviceTypeModel = "Benchmark Server Model",
            deviceTypeId = 3,
            serial = "BM-0003",
            assetTag = "AT-0003",
            primaryIp = null,
            comments = null,
            lastUpdated = null,
            syncedAt = System.currentTimeMillis(),
        ),
    )
