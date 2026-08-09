package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.data.api.GenericNetBoxApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cache-first access to NetBox's `?render=svg` diagrams (rack elevations, cable traces) - an
 * alternative, visual rendering of data the app already shows as a JSON-derived list (elevation
 * slots / trace segments). Persisted the same way [FileDownloadRepository] persists any other
 * durable, API-exported artifact: outside Android's cache eviction, keyed by a logical [cacheKey]
 * (not the literal fetch URL, since the same diagram can be prefetched during sync and re-read from
 * a detail screen without needing to reconstruct an identical URL string both times).
 */
@Singleton
class SvgDiagramRepository
@Inject
constructor(
    private val api: GenericNetBoxApi,
    private val fileDownloadRepository: FileDownloadRepository,
) {
    suspend fun cachedContent(cacheKey: String): String? =
        withContext(Dispatchers.IO) {
            fileDownloadRepository.persistentFile(cacheKey, FILENAME)?.let {
                runCatching { it.readText() }.getOrNull()
            }
        }

    /** Whether a diagram is already persisted for [cacheKey] (NBC-433) - no network/read cost. */
    suspend fun isCached(cacheKey: String): Boolean =
        withContext(Dispatchers.IO) {
            fileDownloadRepository.persistentFile(cacheKey, FILENAME) != null
        }

    /** Refreshes the cached SVG while leaving the existing persistent copy intact on failure. */
    suspend fun refresh(url: String, cacheKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val svg = api.getSvg(url).use { it.string() }
                fileDownloadRepository.writeToPersistent(cacheKey, FILENAME, svg)
                svg
            }
        }

    private companion object {
        const val FILENAME = "diagram.svg"
    }
}

/**
 * Logical [SvgDiagramRepository] cache keys - shared between the on-demand loaders in
 * `GenericDetailViewModel` and `OfflineSyncRepository`'s background prefetch, so both agree on
 * where the same diagram lives regardless of which one fetched it first.
 */
fun rackElevationSvgCacheKey(rackId: Int, face: RackFace): String =
    "rack-elevation:$rackId:${face.apiValue}"

fun cableTraceSvgCacheKey(terminationEndpointPath: String, terminationId: Int): String =
    "cable-trace:$terminationEndpointPath$terminationId"
