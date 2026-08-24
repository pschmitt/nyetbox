package dev.pschmitt.nyetbox.data.backup

import android.content.Context
import android.net.Uri
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.data.repository.BackupFrequency
import dev.pschmitt.nyetbox.data.repository.DEFAULT_SYNC_ATTACHMENTS_TO_DISK
import dev.pschmitt.nyetbox.data.repository.DEFAULT_SYNC_INTERVAL_HOURS
import dev.pschmitt.nyetbox.data.repository.GestureTarget
import dev.pschmitt.nyetbox.data.repository.NavBarItem
import dev.pschmitt.nyetbox.data.repository.PrintSettings
import dev.pschmitt.nyetbox.data.repository.ScannerLens
import dev.pschmitt.nyetbox.data.repository.ScannerRearLens
import dev.pschmitt.nyetbox.data.repository.ScannerResolution
import dev.pschmitt.nyetbox.data.repository.ServerProfile
import dev.pschmitt.nyetbox.data.repository.SettingsRepository
import dev.pschmitt.nyetbox.data.repository.ThemeAccent
import dev.pschmitt.nyetbox.data.repository.ThemeMode
import dev.pschmitt.nyetbox.data.topology.TopologyPosition
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SETTINGS_BACKUP_FORMAT = "dev.pschmitt.nyetbox.settings"
const val SETTINGS_BACKUP_FORMAT_VERSION = 1

/**
 * The portable, settings-only part of a backup. Cache databases and downloaded files are absent.
 */
@Keep
@Serializable
data class SettingsBackupSettings(
    val serverProfiles: List<ServerProfile> = emptyList(),
    val activeServerId: String? = null,
    val syncAttachmentsToDisk: Boolean = DEFAULT_SYNC_ATTACHMENTS_TO_DISK,
    val syncOnlyOnWifi: Boolean = false,
    val syncWhileRoaming: Boolean = true,
    val syncOnAppLaunch: Boolean = true,
    val syncConcurrency: Int = 3,
    val syncOnlyWhenCharging: Boolean = false,
    val syncIntervalHours: Int = DEFAULT_SYNC_INTERVAL_HOURS,
    val changeNotificationsEnabled: Boolean = false,
    val changeNotificationFilters: Set<String> = emptySet(),
    val gestureActions: Map<String, String> = emptyMap(),
    val gestureTargets: Map<String, GestureTarget> = emptyMap(),
    val navBarItems: List<NavBarItem> = emptyList(),
    val scannerLens: String = ScannerLens.Back.storageKey,
    val scannerRearLens: String = ScannerRearLens.Automatic.storageKey,
    val scannerResolution: String = ScannerResolution.Auto.storageKey,
    val themeMode: String = ThemeMode.FollowSystem.storageKey,
    val themeAccent: String = ThemeAccent.System.storageKey,
    val objectTypeAccents: Map<String, String> = emptyMap(),
    val printSettings: PrintSettings = PrintSettings(),
    val offlineMode: Boolean = false,
    val hiddenFieldKeys: Set<String> = emptySet(),
    val pinnedModelPaths: Set<String> = emptySet(),
    val sidebarAppOrder: List<String> = emptyList(),
    val sidebarModelOrders: Map<String, List<String>> = emptyMap(),
    val hiddenSidebarApps: Set<String> = emptySet(),
    val dashboardSectionOrder: List<String> = emptyList(),
    val hiddenDashboardSections: Set<String> = emptySet(),
    val statsOrder: List<String> = emptyList(),
    val hiddenStats: Set<String> = emptySet(),
    val showTopologyDeviceTypeImages: Boolean = true,
    val topologyNodePositions: Map<String, TopologyPosition> = emptyMap(),
    val scheduledBackupEnabled: Boolean = false,
    val scheduledBackupFrequency: String = BackupFrequency.Weekly.storageKey,
    val scheduledBackupFolderUri: String? = null,
)

@Keep
@Serializable
data class SettingsBackupEnvelope(
    val format: String = SETTINGS_BACKUP_FORMAT,
    val formatVersion: Int = SETTINGS_BACKUP_FORMAT_VERSION,
    val appId: String = BuildConfig.APPLICATION_ID,
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val createdAt: Long,
    val settings: SettingsBackupSettings,
)

class SettingsBackupFormatException(message: String) : Exception(message)

class SettingsBackupPasswordRequiredException : Exception("This backup is password-protected")

class SettingsBackupWrongPasswordException : Exception("Incorrect backup password")

/**
 * Small self-contained wrapper so backups remain readable without adding another archive library.
 */
object SettingsBackupCrypto {
    private val magic =
        byteArrayOf('N'.code.toByte(), 'Y'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val plainFlag: Byte = 0
    private const val encryptedFlag: Byte = 1
    private const val saltSize = 16
    private const val ivSize = 12
    private const val tagBits = 128
    private const val iterations = 210_000
    private const val keyBits = 256

    fun encode(data: ByteArray, password: String?): ByteArray {
        if (password.isNullOrEmpty()) return magic + byteArrayOf(plainFlag) + data

        val salt = ByteArray(saltSize).also(SecureRandom()::nextBytes)
        val iv = ByteArray(ivSize).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(tagBits, iv))
        return magic + byteArrayOf(encryptedFlag) + salt + iv + cipher.doFinal(data)
    }

    fun decode(bytes: ByteArray, password: String?): ByteArray {
        if (bytes.size < magic.size + 1 || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw SettingsBackupFormatException("Not a valid Nyetbox settings backup")
        }
        val payload = bytes.copyOfRange(magic.size + 1, bytes.size)
        return when (bytes[magic.size]) {
            plainFlag -> payload
            encryptedFlag -> {
                if (password.isNullOrEmpty()) throw SettingsBackupPasswordRequiredException()
                if (payload.size < saltSize + ivSize) {
                    throw SettingsBackupFormatException("The backup file is incomplete")
                }
                val salt = payload.copyOfRange(0, saltSize)
                val iv = payload.copyOfRange(saltSize, saltSize + ivSize)
                val ciphertext = payload.copyOfRange(saltSize + ivSize, payload.size)
                try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(
                            Cipher.DECRYPT_MODE,
                            deriveKey(password, salt),
                            GCMParameterSpec(tagBits, iv),
                        )
                        doFinal(ciphertext)
                    }
                } catch (_: Exception) {
                    throw SettingsBackupWrongPasswordException()
                }
            }
            else ->
                throw SettingsBackupFormatException("The backup uses an unknown encryption mode")
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < magic.size + 1 || !bytes.copyOfRange(0, magic.size).contentEquals(magic)) {
            throw SettingsBackupFormatException("Not a valid Nyetbox settings backup")
        }
        return bytes[magic.size] == encryptedFlag
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, keyBits)
        return SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
    }
}

@Singleton
class SettingsBackupManager
@Inject
constructor(
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun buildEnvelope(): SettingsBackupEnvelope =
        SettingsBackupEnvelope(
            createdAt = System.currentTimeMillis(),
            settings =
                SettingsBackupSettings(
                    serverProfiles = settingsRepository.serverProfiles.value,
                    activeServerId = settingsRepository.activeServerId.value,
                    syncAttachmentsToDisk = settingsRepository.syncAttachmentsToDisk.value,
                    syncOnlyOnWifi = settingsRepository.syncOnlyOnWifi.value,
                    syncWhileRoaming = settingsRepository.syncWhileRoaming.value,
                    syncOnAppLaunch = settingsRepository.syncOnAppLaunch.value,
                    syncConcurrency = settingsRepository.syncConcurrency.value,
                    syncOnlyWhenCharging = settingsRepository.syncOnlyWhenCharging.value,
                    syncIntervalHours = settingsRepository.syncIntervalHours.value,
                    changeNotificationsEnabled =
                        settingsRepository.changeNotificationsEnabled.value,
                    changeNotificationFilters = settingsRepository.changeNotificationFilters.value,
                    gestureActions =
                        settingsRepository.gestureActions.value
                            .mapKeys { it.key.storageKey }
                            .mapValues { it.value.storageKey },
                    gestureTargets =
                        settingsRepository.gestureTargets.value.mapKeys { it.key.storageKey },
                    navBarItems = settingsRepository.navBarItems.value,
                    scannerLens = settingsRepository.scannerLens.value.storageKey,
                    scannerRearLens = settingsRepository.scannerRearLens.value.storageKey,
                    scannerResolution = settingsRepository.scannerResolution.value.storageKey,
                    themeMode = settingsRepository.themeMode.value.storageKey,
                    themeAccent = settingsRepository.themeAccent.value.storageKey,
                    objectTypeAccents =
                        settingsRepository.objectTypeAccents.value.mapValues {
                            it.value.storageKey
                        },
                    printSettings = settingsRepository.printSettings.value,
                    offlineMode = settingsRepository.offlineMode.value,
                    hiddenFieldKeys = settingsRepository.hiddenFieldKeys.value,
                    pinnedModelPaths = settingsRepository.pinnedModelPaths.value,
                    sidebarAppOrder = settingsRepository.sidebarAppOrder.value,
                    sidebarModelOrders = settingsRepository.sidebarModelOrders.value,
                    hiddenSidebarApps = settingsRepository.hiddenSidebarApps.value,
                    dashboardSectionOrder = settingsRepository.dashboardSectionOrder.value,
                    hiddenDashboardSections = settingsRepository.hiddenDashboardSections.value,
                    statsOrder = settingsRepository.statsOrder.value,
                    hiddenStats = settingsRepository.hiddenStats.value,
                    showTopologyDeviceTypeImages =
                        settingsRepository.showTopologyDeviceTypeImages.value,
                    topologyNodePositions = settingsRepository.topologyNodePositions.value,
                    scheduledBackupEnabled = settingsRepository.scheduledBackupEnabled.value,
                    scheduledBackupFrequency =
                        settingsRepository.scheduledBackupFrequency.value.storageKey,
                    scheduledBackupFolderUri = settingsRepository.scheduledBackupFolderUri.value,
                ),
        )

    suspend fun write(uri: Uri, password: String?) {
        withContext(Dispatchers.IO) {
            val plain =
                json
                    .encodeToString(SettingsBackupEnvelope.serializer(), buildEnvelope())
                    .toByteArray()
            val bytes = SettingsBackupCrypto.encode(plain, password)
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Could not open the selected file for writing")
        }
    }

    suspend fun read(uri: Uri, password: String?): SettingsBackupEnvelope =
        withContext(Dispatchers.IO) {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not open the selected backup")
            val plain = SettingsBackupCrypto.decode(bytes, password)
            val envelope = json.decodeFromString(SettingsBackupEnvelope.serializer(), String(plain))
            if (envelope.format != SETTINGS_BACKUP_FORMAT) {
                throw SettingsBackupFormatException("This is not a Nyetbox settings backup")
            }
            if (envelope.formatVersion > SETTINGS_BACKUP_FORMAT_VERSION) {
                throw SettingsBackupFormatException(
                    "This backup was created by a newer Nyetbox version"
                )
            }
            envelope
        }

    suspend fun restore(uri: Uri, password: String?): SettingsBackupEnvelope {
        val envelope = read(uri, password)
        settingsRepository.restoreBackupSettings(envelope.settings)
        return envelope
    }

    suspend fun isEncrypted(uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val bytes =
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Could not open the selected backup")
            SettingsBackupCrypto.isEncrypted(bytes)
        }
}
