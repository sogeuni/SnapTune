package dev.sogn.snaptune.shared.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val DATABASE_NAME = "snaptune.db"
private const val FULL_FEED_REFRESH_TTL_MS = 15 * 60 * 1000L
private const val BACKGROUND_SYNC_INTERVAL_MS = 60 * 60 * 1000L
private const val BACKGROUND_RETRY_BASE_MS = 15 * 60 * 1000L
private const val BACKGROUND_RETRY_MAX_MS = 6 * 60 * 60 * 1000L
private const val RECENT_EPISODE_SYNC_LIMIT = 10
private const val MAX_STORED_EPISODES_PER_FEED = 200
private const val FEED_SYNC_BATCH_LIMIT = 200

class PodcastRepository private constructor(context: Context) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        SnapTuneDatabase::class.java,
        DATABASE_NAME
    )
        .fallbackToDestructiveMigration(true)
        .allowMainThreadQueries()
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()

    private val dao = database.podcastDao()

    init {
        seedDefaultFeeds()
    }

    fun getFeeds(): List<PodcastFeedEntity> = dao.getFeeds()

    fun getFeed(feedId: String): PodcastFeedEntity? = dao.getFeed(feedId)

    fun getEpisode(episodeId: String): PodcastEpisodeEntity? = dao.getEpisode(episodeId)

    fun getEpisodes(feedId: String): List<PodcastEpisodeEntity> = dao.getEpisodesForFeed(feedId)

    fun getRecentEpisodes(limit: Int = 50): List<RecentPodcastEpisodeRow> =
        dao.getRecentEpisodes(limit)

    @Synchronized
    fun refreshFeedEpisodesIfNeeded(
        feedId: String,
        force: Boolean = false
    ): List<PodcastEpisodeEntity> {
        return refreshFeedEpisodes(feedId, force = force)
    }

    fun syncDueFeedUpdates(
        maxFeeds: Int = FEED_SYNC_BATCH_LIMIT,
        recentEpisodeLimit: Int = RECENT_EPISODE_SYNC_LIMIT,
        maxParallel: Int = 4
    ): Int {
        val dueFeeds = dao.getFeedsDueForSync(System.currentTimeMillis(), maxFeeds)
        if (dueFeeds.isEmpty()) {
            return 0
        }

        val executor = Executors.newFixedThreadPool(minOf(maxParallel, dueFeeds.size))
        return try {
            val tasks = dueFeeds.map { feed ->
                Callable {
                    runCatching {
                        refreshRecentEpisodes(feed.feedId, recentEpisodeLimit)
                    }.getOrDefault(0)
                }
            }
            executor.invokeAll(tasks).sumOf(Future<Int>::get)
        } finally {
            executor.shutdown()
        }
    }

    private fun refreshFeedEpisodes(feedId: String, force: Boolean): List<PodcastEpisodeEntity> {
        val existingFeed = dao.getFeed(feedId) ?: return emptyList()
        val hasEpisodeData = dao.countEpisodesForFeed(feedId) > 0
        val isFresh = !force &&
                hasEpisodeData &&
                existingFeed.lastEpisodeFetchedAtMs > 0L &&
                System.currentTimeMillis() - existingFeed.lastEpisodeFetchedAtMs < FULL_FEED_REFRESH_TTL_MS

        if (isFresh) {
            return dao.getEpisodesForFeed(feedId)
        }

        return try {
            val result = fetchAndParseFeed(
                existingFeed,
                maxEpisodes = null
            )
            val fetchedAtMs = System.currentTimeMillis()
            when (result.statusCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    dao.upsertFeed(
                        markFeedNotModified(
                            existingFeed,
                            result,
                            fetchedAtMs,
                            fullEpisodesFetched = true
                        )
                    )
                }

                else -> {
                    val parsedFeed = checkNotNull(result.parsedFeed)
                    val episodes = parsedFeed.episodes.map { episode ->
                        PodcastEpisodeEntity(
                            buildEpisodeId(existingFeed.feedId, episode.guid),
                            existingFeed.feedId,
                            episode.guid,
                            episode.title,
                            episode.description,
                            episode.audioUrl,
                            episode.artworkUrl ?: parsedFeed.artworkUrl,
                            episode.episodeUrl,
                            episode.durationText,
                            episode.publishedAtMs
                        )
                    }
                    dao.upsertFeed(
                        markFeedSuccess(
                            existingFeed,
                            parsedFeed,
                            result,
                            fetchedAtMs,
                            fullEpisodesFetched = true
                        )
                    )
                    dao.replaceEpisodesForFeed(existingFeed.feedId, episodes)
                    dao.trimEpisodesForFeed(existingFeed.feedId, MAX_STORED_EPISODES_PER_FEED)
                }
            }
            dao.getEpisodesForFeed(feedId)
        } catch (throwable: Throwable) {
            recordFeedFailure(existingFeed, throwable)
            throw throwable
        }
    }

    private fun refreshRecentEpisodes(feedId: String, episodeLimit: Int): Int {
        val existingFeed = dao.getFeed(feedId) ?: return 0
        return try {
            val result = fetchAndParseFeed(
                existingFeed,
                maxEpisodes = episodeLimit
            )
            val fetchedAtMs = System.currentTimeMillis()
            when (result.statusCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    dao.upsertFeed(
                        markFeedNotModified(
                            existingFeed,
                            result,
                            fetchedAtMs,
                            fullEpisodesFetched = false
                        )
                    )
                    0
                }

                else -> {
                    val parsedFeed = checkNotNull(result.parsedFeed)
                    val episodes = parsedFeed.episodes.map { episode ->
                        PodcastEpisodeEntity(
                            buildEpisodeId(existingFeed.feedId, episode.guid),
                            existingFeed.feedId,
                            episode.guid,
                            episode.title,
                            episode.description,
                            episode.audioUrl,
                            episode.artworkUrl ?: parsedFeed.artworkUrl,
                            episode.episodeUrl,
                            episode.durationText,
                            episode.publishedAtMs
                        )
                    }
                    dao.upsertFeed(
                        markFeedSuccess(
                            existingFeed,
                            parsedFeed,
                            result,
                            fetchedAtMs,
                            fullEpisodesFetched = false
                        )
                    )
                    if (episodes.isNotEmpty()) {
                        dao.upsertEpisodes(episodes)
                        dao.trimEpisodesForFeed(existingFeed.feedId, MAX_STORED_EPISODES_PER_FEED)
                    }
                    episodes.size
                }
            }
        } catch (throwable: Throwable) {
            recordFeedFailure(existingFeed, throwable)
            0
        }
    }

    private fun recordFeedFailure(feed: PodcastFeedEntity, throwable: Throwable) {
        val fetchedAtMs = System.currentTimeMillis()
        val statusCode = (throwable as? FeedRequestException)?.statusCode
        val failureCount = feed.consecutiveFailureCount + 1
        val multiplier = 1L shl minOf(feed.consecutiveFailureCount, 5)
        val retryDelayMs = minOf(BACKGROUND_RETRY_BASE_MS * multiplier, BACKGROUND_RETRY_MAX_MS)
        dao.upsertFeed(
            feed.copy(
                lastCheckedAtMs = fetchedAtMs,
                nextCheckAtMs = fetchedAtMs + retryDelayMs,
                consecutiveFailureCount = failureCount,
                lastHttpStatus = statusCode
            )
        )
    }

    private fun markFeedSuccess(
        existingFeed: PodcastFeedEntity,
        parsedFeed: ParsedPodcastFeed,
        result: FeedFetchResult,
        fetchedAtMs: Long,
        fullEpisodesFetched: Boolean
    ): PodcastFeedEntity {
        return existingFeed.copy(
            title = parsedFeed.title.ifBlank { existingFeed.title },
            description = parsedFeed.description ?: existingFeed.description,
            artworkUrl = parsedFeed.artworkUrl ?: existingFeed.artworkUrl,
            lastSummaryFetchedAtMs = fetchedAtMs,
            lastRecentEpisodeFetchedAtMs = fetchedAtMs,
            etag = result.etag ?: existingFeed.etag,
            lastModified = result.lastModified ?: existingFeed.lastModified,
            lastCheckedAtMs = fetchedAtMs,
            lastSuccessAtMs = fetchedAtMs,
            nextCheckAtMs = fetchedAtMs + BACKGROUND_SYNC_INTERVAL_MS,
            consecutiveFailureCount = 0,
            lastHttpStatus = result.statusCode,
            lastEpisodeFetchedAtMs = if (fullEpisodesFetched) fetchedAtMs else existingFeed.lastEpisodeFetchedAtMs
        )
    }

    private fun markFeedNotModified(
        existingFeed: PodcastFeedEntity,
        result: FeedFetchResult,
        fetchedAtMs: Long,
        fullEpisodesFetched: Boolean
    ): PodcastFeedEntity {
        return existingFeed.copy(
            lastSummaryFetchedAtMs = fetchedAtMs,
            lastRecentEpisodeFetchedAtMs = fetchedAtMs,
            etag = result.etag ?: existingFeed.etag,
            lastModified = result.lastModified ?: existingFeed.lastModified,
            lastCheckedAtMs = fetchedAtMs,
            lastSuccessAtMs = fetchedAtMs,
            nextCheckAtMs = fetchedAtMs + BACKGROUND_SYNC_INTERVAL_MS,
            consecutiveFailureCount = 0,
            lastHttpStatus = result.statusCode,
            lastEpisodeFetchedAtMs = if (fullEpisodesFetched) fetchedAtMs else existingFeed.lastEpisodeFetchedAtMs
        )
    }

    private fun seedDefaultFeeds() {
        val seededFeedIds = dao.getFeeds().map { it.feedId }.toSet()
        val missingFeeds = DEFAULT_FEEDS
            .filterNot { seededFeedIds.contains(it.feedId) }
            .map {
                PodcastFeedEntity(
                    feedId = it.feedId,
                    title = it.title,
                    rssUrl = it.rssUrl,
                    description = it.description,
                    artworkUrl = null,
                    sortOrder = it.sortOrder,
                    lastSummaryFetchedAtMs = 0L,
                    lastRecentEpisodeFetchedAtMs = 0L,
                    etag = null,
                    lastModified = null,
                    lastCheckedAtMs = 0L,
                    lastSuccessAtMs = 0L,
                    nextCheckAtMs = 0L,
                    consecutiveFailureCount = 0,
                    lastHttpStatus = null,
                    lastEpisodeFetchedAtMs = 0L
                )
            }
        if (missingFeeds.isNotEmpty()) {
            dao.upsertFeeds(missingFeeds)
        }
    }

    private fun fetchAndParseFeed(
        feed: PodcastFeedEntity,
        maxEpisodes: Int?
    ): FeedFetchResult {
        val connection = (URL(feed.rssUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml")
            setRequestProperty("User-Agent", "SnapTune/1.0")
            feed.etag?.takeIf { it.isNotBlank() }?.let { setRequestProperty("If-None-Match", it) }
            feed.lastModified?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("If-Modified-Since", it)
            }
        }
        connection.connect()
        try {
            val statusCode = connection.responseCode
            val etag = connection.getHeaderField("ETag")
            val lastModified = connection.getHeaderField("Last-Modified")
            if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return FeedFetchResult(statusCode, etag, lastModified, null)
            }
            if (statusCode !in 200..299) {
                throw FeedRequestException(
                    statusCode,
                    "Failed to fetch feed ${feed.rssUrl}: HTTP $statusCode"
                )
            }
            BufferedInputStream(connection.inputStream).use { inputStream ->
                return FeedFetchResult(
                    statusCode = statusCode,
                    etag = etag,
                    lastModified = lastModified,
                    parsedFeed = PodcastRssParser.parse(
                        inputStream,
                        maxEpisodes = maxEpisodes
                    )
                )
            }
        } catch (ioException: IOException) {
            throw ioException
        } finally {
            connection.disconnect()
        }
    }

    private fun buildEpisodeId(feedId: String, guid: String): String {
        return UUID.nameUUIDFromBytes("$feedId|$guid".toByteArray()).toString()
    }

    private data class SeedFeed(
        val feedId: String,
        val title: String,
        val rssUrl: String,
        val description: String,
        val sortOrder: Int
    )

    private data class FeedFetchResult(
        val statusCode: Int,
        val etag: String?,
        val lastModified: String?,
        val parsedFeed: ParsedPodcastFeed?
    )

    private class FeedRequestException(
        val statusCode: Int,
        message: String
    ) : IOException(message)

    companion object {
        private val DEFAULT_FEEDS = listOf(
            SeedFeed(
                feedId = "bbc-global-news",
                title = "Global News Podcast",
                rssUrl = "https://podcasts.files.bbci.co.uk/p02nq0gn.rss",
                description = "BBC World Service daily news roundup.",
                sortOrder = 0
            ),
            SeedFeed(
                feedId = "bbc-word-of-mouth",
                title = "Word of Mouth",
                rssUrl = "https://podcasts.files.bbci.co.uk/b006qtnz.rss",
                description = "BBC Radio 4 stories about words, language and linguistics.",
                sortOrder = 1
            ),
            SeedFeed(
                feedId = "mbc",
                title = "권순표의 뉴스하이킥",
                rssUrl = "https://minicast.imbc.com/PodCast/pod.aspx?code=1005071100000100000",
                description = "MBC 표준FM 평일 저녁 6시5분 ~ 8시",
                sortOrder = 2
            )
        )

        @Volatile
        private var instance: PodcastRepository? = null

        fun getInstance(context: Context): PodcastRepository {
            return instance ?: synchronized(this) {
                instance ?: PodcastRepository(context).also { instance = it }
            }
        }
    }
}
