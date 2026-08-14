package dev.pschmitt.nyetbox.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.pschmitt.nyetbox.di.DownloadClient
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The actual download-or-revalidate mechanics behind [FileDownloadRepository.downloadToPersistent]
 * (NBC-434), pulled out as a plain, `Context`-free function so it can be unit tested directly
 * against a [okhttp3.mockwebserver.MockWebServer] instead of needing an Android `Context` (this
 * project has no Robolectric) to reach a real cache directory. Must run on a background
 * dispatcher - callers are responsible for that, same as before this was extracted.
 *
 * Verified live against a real deployment (netbox.brkn.lol): its media server ignores
 * `If-Modified-Since` entirely and always answers 200 with the full body, even when the file is
 * byte-for-byte identical - conditional GET alone would have turned every 24h full-sync pass into a
 * full re-download of every cached attachment (hundreds of files, hundreds of MB), the exact
 * bandwidth waste this ticket exists to avoid. [isUnchangedByHead] is tried first specifically to
 * cover that case: a HEAD carries no body either way, so a server that *does* honor conditional
 * requests answers 304 there too, and one that doesn't still lets us compare `Content-Length`
 * against the cached file size without ever pulling the body over the wire.
 */
internal fun downloadOrRevalidate(
    okHttpClient: OkHttpClient,
    url: String,
    target: File,
    revalidate: Boolean,
    onBytesDownloaded: (Long) -> Unit = {},
): File {
    val existing = target.isFile && target.length() > 0L
    if (existing && !revalidate) return target
    if (existing && isUnchangedByHead(okHttpClient, url, target)) {
        target.setLastModified(System.currentTimeMillis())
        return target
    }
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.part")
    okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
        response.body.byteStream().use { input ->
            temp.outputStream().use { output -> input.copyToWithProgress(output, onBytesDownloaded) }
        }
    }
    check(temp.renameTo(target)) { "Couldn't finalize downloaded attachment" }
    return target
}

/**
 * Like [java.io.InputStream.copyTo], but reports the running byte count after each buffer flush
 * (NBC-331) so a caller can surface live "N bytes downloaded" progress instead of only knowing the
 * total once the whole file has landed.
 */
private fun java.io.InputStream.copyToWithProgress(
    output: java.io.OutputStream,
    onBytesDownloaded: (Long) -> Unit,
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var bytesCopied = 0L
    var bytes = read(buffer)
    while (bytes >= 0) {
        output.write(buffer, 0, bytes)
        bytesCopied += bytes
        onBytesDownloaded(bytesCopied)
        bytes = read(buffer)
    }
    return bytesCopied
}

/**
 * Cheap staleness check with zero body bytes transferred either way: a HEAD carrying the same
 * `If-Modified-Since` header a conditional GET would use, so a compliant server can still answer
 * 304 directly; otherwise falls back to comparing the HEAD response's `Content-Length` against the
 * cached file's size, which is the only signal a non-compliant server (confirmed live: NetBox's own
 * `/media/` static-file serving) gives us for free. A HEAD that fails outright (network error, 405
 * Method Not Allowed, no `Content-Length` header) is treated as "can't tell, redownload" rather
 * than risking silently keeping stale content.
 */
private fun isUnchangedByHead(okHttpClient: OkHttpClient, url: String, target: File): Boolean =
    runCatching {
        val request =
            Request.Builder()
                .url(url)
                .head()
                .header("If-Modified-Since", httpDate(target.lastModified()))
                .build()
        okHttpClient.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> true
                response.isSuccessful ->
                    response.header("Content-Length")?.toLongOrNull() == target.length()
                else -> false
            }
        }
    }
    .getOrDefault(false)

/** RFC 1123 (`If-Modified-Since`-compatible) rendering of an epoch-millis timestamp. */
internal fun httpDate(epochMillis: Long): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC)
    )

/**
 * Downloads a NetBox attachment (document, image, ...) to the app's cache dir, ready to be opened
 * via FileProvider. Uses [DownloadClient] (auth only, no base-URL rewriting - the media URL NetBox
 * returned is already complete/correct) - NetBox media URLs commonly require the API token too, not
 * just the REST API itself (confirmed against a real instance: unauthenticated media requests 302
 * to the login page).
 */
@Singleton
class FileDownloadRepository
@Inject
constructor(
    @DownloadClient private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    data class PersistentStats(val fileCount: Int, val bytes: Long)

    fun persistentStats(): PersistentStats {
        val files = persistentDirectory().listFiles().orEmpty().filter { it.isFile }
        return PersistentStats(files.size, files.sumOf { it.length() })
    }

    /**
     * Returns a previously synced durable copy, if one exists for this exact media URL.
     *
     * Some callers use a generated logical name (for example, `device-type-72-front`) while the
     * sync worker uses that same name to derive the stored extension from the downloaded media.
     * Resolve the URL hash independently of the requested display name so those callers still find
     * `hash.png`/`hash.avif` in the offline cache.
     */
    fun persistentFile(url: String, filename: String): File? {
        val exact = persistentPath(url, filename)
        if (exact.isFile) return exact

        val hash = persistentHash(url)
        return exact.parentFile?.listFiles()?.firstOrNull {
            it.isFile && it.name.substringBeforeLast('.', it.name) == hash
        }
    }

    /** Looks up a durable copy by URL alone, for callers with no meaningful display filename. */
    fun persistentFile(url: String): File? = persistentFile(url, "")

    /**
     * Downloads an attachment into filesDir so Android's cache eviction cannot remove it.
     *
     * @param revalidate NBC-434: when `false` (the default, used by incremental sync passes and
     *   every on-demand caller), an existing persisted file is trusted as-is with zero network
     *   cost - this is deliberately how the app has always behaved for a normal sync. When `true`
     *   (only the 24h/forced full sync pass sets this), an existing file is instead revalidated
     *   with a conditional `If-Modified-Since` request: a 304 keeps the file untouched (just bumps
     *   its local mtime so the next check window moves forward), while a 200 means the server has
     *   new content and replaces it - the only way a NetBox-side replacement of the same URL's
     *   media (e.g. a re-uploaded rack photo) is ever picked up, since the file would otherwise be
     *   trusted forever once downloaded once.
     */
    suspend fun downloadToPersistent(
        url: String,
        filename: String,
        revalidate: Boolean = false,
        onBytesDownloaded: (Long) -> Unit = {},
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                downloadOrRevalidate(
                    okHttpClient,
                    url,
                    persistentPath(url, filename),
                    revalidate,
                    onBytesDownloaded,
                )
            }
        }

    /** Stores a generated or API-exported artifact alongside durable attachments. */
    suspend fun writeToPersistent(url: String, filename: String, content: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = persistentPath(url, filename)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.part")
                temp.writeText(content)
                if (target.exists() && !target.delete()) {
                    error("Couldn't replace persistent cache file")
                }
                check(temp.renameTo(target)) { "Couldn't finalize persistent cache file" }
                target
            }
        }

    suspend fun downloadToCache(url: String, filename: String): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
                    val downloadsDir = transientDirectory().apply { mkdirs() }
                    val safeFilename = filename.substringAfterLast('/').substringAfterLast('\\')
                    val outFile = File(downloadsDir, safeFilename.ifBlank { "attachment" })
                    response.body.byteStream().use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    outFile
                }
            }
        }

    private fun persistentPath(url: String, filename: String): File {
        val hash = persistentHash(url)
        val extension =
            filename
                .substringAfterLast('.', "")
                .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
                ?.let { ".${it.lowercase()}" } ?: ""
        return File(persistentDirectory(), "$hash$extension")
    }

    /**
     * Deletes durable media for one profile, called only after the user confirms profile removal.
     */
    suspend fun deletePersistentCache(profile: ServerProfile) =
        withContext(Dispatchers.IO) {
            val root = File(context.filesDir, "offline-attachments")
            val directory =
                if (profile.cacheNamespace == LEGACY_CACHE_NAMESPACE) root
                else File(root, profile.cacheNamespace)
            directory.deleteRecursively()
        }

    private fun persistentDirectory(): File {
        val root = File(context.filesDir, "offline-attachments")
        val namespace = settingsRepository.activeServer.value?.cacheNamespace
        return if (namespace == null || namespace == LEGACY_CACHE_NAMESPACE) root
        else File(root, namespace)
    }

    private fun transientDirectory(): File {
        val namespace =
            settingsRepository.activeServer.value?.cacheNamespace ?: LEGACY_CACHE_NAMESPACE
        return File(context.cacheDir, "downloads/$namespace")
    }

    private companion object {
        const val LEGACY_CACHE_NAMESPACE = "legacy"
    }

    private fun persistentHash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
