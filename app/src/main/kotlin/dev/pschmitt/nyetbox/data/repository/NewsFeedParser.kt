package dev.pschmitt.nyetbox.data.repository

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal data class NewsFeedItem(
    val guid: String,
    val title: String,
    val link: String,
    val summary: String?,
    val publishedAt: Long,
)

/** Small RSS 2.0 parser for the stable title/link/summary/date fields used by the dashboard. */
internal fun parseNewsFeed(xml: String): List<NewsFeedItem> =
    ITEM_PATTERN.findAll(xml)
        .mapNotNull { match ->
            val item = match.value
            val title = item.xmlValue("title")?.cleanText() ?: return@mapNotNull null
            val link =
                item.xmlValue("link")?.trim()?.takeIf { it.startsWith("http") }
                    ?: return@mapNotNull null
            val guid = item.xmlValue("guid")?.cleanText()?.takeIf { it.isNotBlank() } ?: link
            val published =
                item.xmlValue("pubDate")?.parseFeedDate()
                    ?: item.xmlValue("date")?.parseFeedDate()
                    ?: 0L
            NewsFeedItem(
                guid = guid,
                title = title,
                link = link,
                summary =
                    (item.xmlValue("description") ?: item.xmlValue("encoded"))
                        ?.cleanText()
                        ?.takeIf { it.isNotBlank() },
                publishedAt = published,
            )
        }
        .distinctBy(NewsFeedItem::guid)
        .sortedByDescending(NewsFeedItem::publishedAt)
        .toList()

private val ITEM_PATTERN =
    Regex(
        "<item\\b[^>]*>.*?</item>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private fun String.xmlValue(name: String): String? {
    val pattern =
        Regex(
            "<(?:(?:[A-Za-z0-9_-]+):)?$name\\b[^>]*>(.*?)</(?:(?:[A-Za-z0-9_-]+):)?$name>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    return pattern.find(this)?.groupValues?.getOrNull(1)?.trim()
}

private fun String.cleanText(): String =
    replace(Regex("<!\\[CDATA\\[(.*?)]]>", setOf(RegexOption.DOT_MATCHES_ALL))) {
            it.groupValues[1]
        }
        .decodeXmlEntities()
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.decodeXmlEntities(): String =
    replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        .replace(Regex("&#([0-9]+);")) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }

private fun String.parseFeedDate(): Long =
    runCatching {
            ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }
        .recoverCatching { Instant.parse(this).toEpochMilli() }
        .getOrDefault(0L)
