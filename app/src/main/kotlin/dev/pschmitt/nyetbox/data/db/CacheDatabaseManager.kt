package dev.pschmitt.nyetbox.data.db

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.data.repository.ServerProfile
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns one Room file per saved server and exposes the currently selected database. */
@Singleton
class CacheDatabaseManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
) {
    private val databases = mutableMapOf<String, AppDatabase>()
    private val unconfiguredKey = "__unconfigured__"
    private val operationMutex = Mutex()

    private val _activeDatabase =
        MutableStateFlow(
            openDatabase(
                settingsRepository.activeServer.value
                    ?: ServerProfile(
                        id = unconfiguredKey,
                        displayName = "",
                        baseUrl = "",
                        token = "",
                        cacheDatabaseName = "nyetbox.db",
                    )
            )
        )
    val activeDatabase: StateFlow<AppDatabase> = _activeDatabase.asStateFlow()

    suspend fun switchTo(profile: ServerProfile) {
        operationMutex.withLock {
            val database = withContext(Dispatchers.IO) { openDatabase(profile) }
            _activeDatabase.value = database
        }
    }

    suspend fun delete(profile: ServerProfile) {
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val databaseKeys =
                    databases.keys.filter { key ->
                        key == profile.id ||
                            (profile.cacheDatabaseName == "nyetbox.db" && key == unconfiguredKey)
                    }
                val databasesToClose = databaseKeys.mapNotNull { databases.remove(it) }.distinct()
                databasesToClose.forEach { it.close() }
                context.deleteDatabase(profile.cacheDatabaseName)
                // Room may leave these sidecars behind if a process was interrupted during a write.
                context.getDatabasePath("${profile.cacheDatabaseName}-wal").delete()
                context.getDatabasePath("${profile.cacheDatabaseName}-shm").delete()
            }
        }
    }

    suspend fun <T> withActiveServer(block: suspend () -> T): T = operationMutex.withLock {
        block()
    }

    private fun openDatabase(profile: ServerProfile): AppDatabase {
        if (profile.cacheDatabaseName == "nyetbox.db") {
            databases[unconfiguredKey]?.let {
                databases[profile.id] = it
                return it
            }
        }
        return databases.getOrPut(profile.id) {
            Room.databaseBuilder(context, AppDatabase::class.java, profile.cacheDatabaseName)
                .addMigrations(*MIGRATIONS)
                // The cache is a disposable, sync-repopulated mirror of NetBox (see
                // GenericObjectRepository) - never the source of truth - so a schema downgrade
                // (e.g. switching between worktrees/branches with different Room versions on the
                // same device during development) can safely drop and rebuild it instead of
                // crashing the app on launch.
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
        }
    }

    private companion object {
        val MIGRATIONS =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
            )
    }
}
