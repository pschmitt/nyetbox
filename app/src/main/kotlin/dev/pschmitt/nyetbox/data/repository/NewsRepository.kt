package dev.pschmitt.nyetbox.data.repository

import dev.pschmitt.nyetbox.BuildConfig
import dev.pschmitt.nyetbox.data.db.NewsDao
import dev.pschmitt.nyetbox.data.db.NewsItemEntity
import dev.pschmitt.nyetbox.di.NewsClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Cache-first dashboard news. The public feed is deliberately independent from the configured
 * NetBox host, and this request never carries the user's NetBox API token.
 */
@Singleton
class NewsRepository
@Inject
constructor(
    private val dao: NewsDao,
    @NewsClient private val client: OkHttpClient,
) {
    fun observeLatest(limit: Int = DEFAULT_LIMIT): Flow<List<NewsItemEntity>> =
        dao.observeLatest(limit)

    suspend fun refresh(): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                    val request =
                        Request.Builder()
                            .url(FEED_URL)
                            .header("Accept", "application/rss+xml, application/xml, text/xml")
                            .header("User-Agent", "Nyetbox/${BuildConfig.VERSION_NAME}")
                            .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("News feed returned HTTP ${response.code}")
                        }
                        val parsed = parseNewsFeed(response.body.string())
                        if (parsed.isEmpty()) throw IOException("News feed contained no items")
                        val now = System.currentTimeMillis()
                        dao.upsertAll(
                            parsed.map { item ->
                                NewsItemEntity(
                                    guid = item.guid,
                                    title = item.title,
                                    link = item.link,
                                    summary = item.summary,
                                    publishedAt = item.publishedAt,
                                    syncedAt = now,
                                )
                            }
                        )
                        parsed.size
                    }
                }
                .onFailure { Timber.w(it, "Couldn't refresh NetBox news") }
        }

    private companion object {
        const val FEED_URL = "https://netboxlabs.com/feed/"
        const val DEFAULT_LIMIT = 6
    }
}
