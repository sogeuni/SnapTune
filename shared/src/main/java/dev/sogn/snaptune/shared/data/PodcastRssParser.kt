package dev.sogn.snaptune.shared.data

import android.text.Html
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class ParsedPodcastFeed(
    val title: String,
    val description: String?,
    val artworkUrl: String?,
    val episodes: List<ParsedPodcastEpisode>
)

data class ParsedPodcastEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val artworkUrl: String?,
    val episodeUrl: String?,
    val durationText: String?,
    val publishedAtMs: Long
)

object PodcastRssParser {

    fun parse(
        inputStream: InputStream,
        includeEpisodes: Boolean = true,
        maxEpisodes: Int? = null
    ): ParsedPodcastFeed {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(inputStream, null)

        val state = ParseState()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                handleStartTag(parser, includeEpisodes, state)?.let { return it }
            } else if (parser.eventType == XmlPullParser.END_TAG) {
                handleEndTag(parser, maxEpisodes, state)?.let { return it }
            }
            parser.next()
        }

        return buildParsedFeed(state)
    }

    private fun handleStartTag(
        parser: XmlPullParser,
        includeEpisodes: Boolean,
        state: ParseState
    ): ParsedPodcastFeed? {
        val tagName = parser.name.orEmpty()
        val artworkUrl = extractArtworkUrl(parser)
        when {
            tagName == "item" && !includeEpisodes -> {
                return buildParsedFeed(state, emptyList())
            }

            tagName == "item" -> state.currentItem = MutableEpisode()
            state.currentItem != null -> parseEpisodeTag(
                parser,
                tagName,
                checkNotNull(state.currentItem)
            )

            tagName == "image" && artworkUrl == null -> state.insideChannelImage = true
            isArtworkTag(parser, tagName) -> state.feedArtworkUrl =
                artworkUrl ?: state.feedArtworkUrl

            tagName == "title" -> state.feedTitle = parser.readTextSafely()
            tagName == "description" -> state.feedDescription = parser.readTextSafely()
            state.insideChannelImage && tagName == "url" -> state.feedArtworkUrl =
                parser.readTextSafely()
        }
        return null
    }

    private fun handleEndTag(
        parser: XmlPullParser,
        maxEpisodes: Int?,
        state: ParseState
    ): ParsedPodcastFeed? {
        return when (parser.name.orEmpty()) {
            "image" -> {
                state.insideChannelImage = false
                null
            }

            "item" -> {
                state.currentItem?.toParsedEpisode(state.feedArtworkUrl)?.let(state.episodes::add)
                state.currentItem = null
                if (maxEpisodes != null && state.episodes.size >= maxEpisodes) {
                    buildParsedFeed(state)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private fun buildParsedFeed(
        state: ParseState,
        episodes: List<ParsedPodcastEpisode> = state.episodes
    ): ParsedPodcastFeed {
        return ParsedPodcastFeed(
            title = state.feedTitle,
            description = sanitizeText(state.feedDescription),
            artworkUrl = state.feedArtworkUrl,
            episodes = episodes
        )
    }

    private class ParseState {
        var feedTitle: String = ""
        var feedDescription: String? = null
        var feedArtworkUrl: String? = null
        var insideChannelImage: Boolean = false
        val episodes = mutableListOf<ParsedPodcastEpisode>()
        var currentItem: MutableEpisode? = null
    }

    private fun parseEpisodeTag(
        parser: XmlPullParser,
        tagName: String,
        currentItem: MutableEpisode
    ) {
        when {
            tagName == "title" -> currentItem.title = parser.readTextSafely()
            tagName == "description" -> currentItem.description = parser.readTextSafely()
            tagName == "guid" -> currentItem.guid = parser.readTextSafely()
            tagName == "link" -> currentItem.episodeUrl = parser.readTextSafely()
            tagName == "pubDate" -> currentItem.publishedAtMs =
                parsePubDate(parser.readTextSafely())

            tagName.endsWith("duration") -> currentItem.durationText = parser.readTextSafely()
            isArtworkTag(parser, tagName) -> currentItem.artworkUrl =
                extractArtworkUrl(parser) ?: currentItem.artworkUrl

            tagName == "enclosure" -> {
                val type = parser.getAttributeValue(null, "type").orEmpty()
                val url = parser.getAttributeValue(null, "url").orEmpty()
                if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio"))) {
                    currentItem.audioUrl = url
                }
            }
        }
    }

    private fun parsePubDate(pubDate: String): Long {
        if (pubDate.isBlank()) {
            return 0L
        }
        return try {
            ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }

    private fun isArtworkTag(parser: XmlPullParser, tagName: String): Boolean {
        return when {
            tagName.endsWith("image") -> true
            tagName == "thumbnail" -> parser.getAttributeValue(null, "url") != null
            tagName == "content" -> {
                val medium = parser.getAttributeValue(null, "medium").orEmpty()
                val type = parser.getAttributeValue(null, "type").orEmpty()
                parser.getAttributeValue(null, "url") != null &&
                        (medium.equals("image", ignoreCase = true) || type.startsWith("image/"))
            }

            else -> false
        }
    }

    private fun extractArtworkUrl(parser: XmlPullParser): String? {
        return parser.getAttributeValue(null, "href")
            ?: parser.getAttributeValue(null, "url")
    }

    private fun XmlPullParser.readTextSafely(): String {
        return if (next() == XmlPullParser.TEXT) {
            val text = text.orEmpty()
            nextTag()
            text.trim()
        } else {
            ""
        }
    }

    private class MutableEpisode {
        var guid: String = ""
        var title: String = ""
        var description: String? = null
        var audioUrl: String = ""
        var artworkUrl: String? = null
        var episodeUrl: String? = null
        var durationText: String? = null
        var publishedAtMs: Long = 0L

        fun toParsedEpisode(feedArtworkUrl: String?): ParsedPodcastEpisode? {
            if (title.isBlank() || audioUrl.isBlank()) {
                return null
            }
            val resolvedGuid = guid.ifBlank { audioUrl }
            return ParsedPodcastEpisode(
                guid = resolvedGuid,
                title = title,
                description = sanitizeText(description),
                audioUrl = audioUrl,
                artworkUrl = artworkUrl ?: feedArtworkUrl,
                episodeUrl = episodeUrl,
                durationText = durationText,
                publishedAtMs = publishedAtMs
            )
        }
    }

    private fun sanitizeText(rawText: String?): String? {
        if (rawText.isNullOrBlank()) {
            return null
        }
        val plainText = Html.fromHtml(rawText, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00A0', ' ')
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        return plainText.ifBlank { null }
    }
}
