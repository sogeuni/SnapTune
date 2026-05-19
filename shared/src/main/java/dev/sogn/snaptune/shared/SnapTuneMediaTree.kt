package dev.sogn.snaptune.shared

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import dev.sogn.snaptune.shared.data.PodcastEpisodeEntity
import dev.sogn.snaptune.shared.data.PodcastFeedEntity
import dev.sogn.snaptune.shared.data.PodcastRepository
import dev.sogn.snaptune.shared.data.RecentPodcastEpisodeRow
import java.util.Date

/**
 * 미디어 ID 관련 상수
 */
object MediaId {
    const val ROOT = "root"
    const val TAB_STREAMING = "streaming_tab"
    const val TAB_PODCAST = "podcast_tab"
    const val PODCAST_RECENT_UPDATES = "podcast_recent_updates"
    const val EXTRA_PLAYBACK_PARENT_ID = "dev.sogn.snaptune.shared.EXTRA_PLAYBACK_PARENT_ID"
    private const val PODCAST_FEED_PREFIX = "podcast_feed:"
    private const val PODCAST_EPISODE_PREFIX = "podcast_episode:"

    fun podcastFeed(feedId: String): String = "$PODCAST_FEED_PREFIX$feedId"

    fun podcastEpisode(episodeId: String): String = "$PODCAST_EPISODE_PREFIX$episodeId"

    fun extractPodcastFeedId(parentId: String): String? {
        return parentId.removePrefix(PODCAST_FEED_PREFIX).takeIf {
            parentId.startsWith(PODCAST_FEED_PREFIX) && it.isNotBlank()
        }
    }

    fun extractPodcastEpisodeId(mediaId: String): String? {
        return mediaId.removePrefix(PODCAST_EPISODE_PREFIX).takeIf {
            mediaId.startsWith(PODCAST_EPISODE_PREFIX) && it.isNotBlank()
        }
    }
}

/**
 * 미디어 계층 구조 및 아이템 제공을 담당하는 클래스
 */
class SnapTuneMediaTree(
    private val context: Context,
    private val podcastRepository: PodcastRepository
) {

    data class PlaybackQueue(
        val mediaItems: List<MediaItem>,
        val startIndex: Int
    )

    private val artworkCache = PodcastArtworkCache.getInstance(context)
    private val streamingIconUri = localResourceUri(R.drawable.ic_streaming)
    private val podcastIconUri = localResourceUri(R.drawable.ic_podcast)

    @get:OptIn(UnstableApi::class)
    val rootExtras: Bundle
        get() = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }

    private fun localResourceUri(resourceId: Int): Uri {
        val resources = context.resources
        return Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(resources.getResourcePackageName(resourceId))
            .appendPath(resources.getResourceTypeName(resourceId))
            .appendPath(resources.getResourceEntryName(resourceId))
            .build()
    }

    val rootItem: MediaItem by lazy {
        MediaItem.Builder()
            .setMediaId(MediaId.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("SnapTune")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()
    }

    val tabs: List<MediaItem> by lazy {
        @OptIn(UnstableApi::class)
        val tabExtras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }

        val streamingTab = MediaItem.Builder()
            .setMediaId(MediaId.TAB_STREAMING)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.tab_streaming))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(streamingIconUri)
                    .setExtras(tabExtras)
                    .build()
            )
            .build()

        @OptIn(UnstableApi::class)
        val podcastExtras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }

        val podcastTab = MediaItem.Builder()
            .setMediaId(MediaId.TAB_PODCAST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.tab_podcast))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(podcastIconUri)
                    .setExtras(podcastExtras)
                    .build()
            )
            .build()

        listOf(streamingTab, podcastTab)
    }

    fun isPodcastFeed(parentId: String): Boolean = MediaId.extractPodcastFeedId(parentId) != null

    fun isPodcastContainer(parentId: String): Boolean {
        return parentId == MediaId.PODCAST_RECENT_UPDATES || isPodcastFeed(parentId)
    }

    fun resolvePlayableItem(mediaId: String): MediaItem? {
        val episodeId = MediaId.extractPodcastEpisodeId(mediaId) ?: return null
        val episode = podcastRepository.getEpisode(episodeId) ?: return null
        val feedTitle = podcastRepository.getFeed(episode.feedId)?.title.orEmpty()
        return buildEpisodeItem(feedTitle, episode, null)
    }

    fun resolvePlaybackQueue(mediaId: String, parentId: String? = null): PlaybackQueue? {
        if (parentId == MediaId.PODCAST_RECENT_UPDATES) {
            val queue = podcastRepository.getRecentEpisodes().map { episode ->
                buildRecentEpisodeItem(episode, null)
            }
            val startIndex = queue.indexOfFirst { it.mediaId == mediaId }.takeIf { it >= 0 } ?: 0
            return queue.takeIf { it.isNotEmpty() }?.let { PlaybackQueue(it, startIndex) }
        }

        val parentFeedId = parentId?.let(MediaId::extractPodcastFeedId)
        if (parentFeedId != null) {
            val feedTitle = podcastRepository.getFeed(parentFeedId)?.title.orEmpty()
            val queue = podcastRepository.getEpisodes(parentFeedId).map { episode ->
                buildEpisodeItem(feedTitle, episode, null, MediaId.podcastFeed(parentFeedId))
            }
            val startIndex = queue.indexOfFirst { it.mediaId == mediaId }.takeIf { it >= 0 } ?: 0
            return queue.takeIf { it.isNotEmpty() }?.let { PlaybackQueue(it, startIndex) }
        }

        val episodeId = MediaId.extractPodcastEpisodeId(mediaId)
        if (episodeId != null) {
            val episode = podcastRepository.getEpisode(episodeId) ?: return null
            val feedTitle = podcastRepository.getFeed(episode.feedId)?.title.orEmpty()
            val queue = podcastRepository.getEpisodes(episode.feedId).map { feedEpisode ->
                buildEpisodeItem(feedTitle, feedEpisode, null, MediaId.podcastFeed(episode.feedId))
            }
            val startIndex = queue.indexOfFirst { it.mediaId == mediaId }
            return queue.takeIf { it.isNotEmpty() && startIndex >= 0 }?.let {
                PlaybackQueue(it, startIndex)
            }
        }

        val feedId = MediaId.extractPodcastFeedId(mediaId)
        if (feedId != null) {
            val feedTitle = podcastRepository.getFeed(feedId)?.title.orEmpty()
            val queue = podcastRepository.getEpisodes(feedId).map { episode ->
                buildEpisodeItem(feedTitle, episode, null, MediaId.podcastFeed(feedId))
            }
            return queue.takeIf { it.isNotEmpty() }?.let { PlaybackQueue(it, 0) }
        }

        if (mediaId == MediaId.PODCAST_RECENT_UPDATES) {
            val queue = podcastRepository.getRecentEpisodes().map { episode ->
                buildRecentEpisodeItem(episode, null)
            }
            return queue.takeIf { it.isNotEmpty() }?.let { PlaybackQueue(it, 0) }
        }

        return null
    }

    fun getChildren(parentId: String, artSizePixels: Int? = null): List<MediaItem> {
        return when (parentId) {
            MediaId.ROOT, "" -> tabs
            MediaId.TAB_STREAMING -> getStreamingItems()
            MediaId.TAB_PODCAST -> listOf(buildRecentUpdatesItem()) + podcastRepository.getFeeds()
                .map { feed ->
                    buildFeedItem(feed, artSizePixels)
                }

            MediaId.PODCAST_RECENT_UPDATES -> podcastRepository.getRecentEpisodes().map { episode ->
                buildRecentEpisodeItem(episode, artSizePixels)
            }

            else -> {
                val feedId = MediaId.extractPodcastFeedId(parentId)
                if (feedId != null) {
                    val feedTitle = podcastRepository.getFeed(feedId)?.title.orEmpty()
                    podcastRepository.getEpisodes(feedId).map { episode ->
                        buildEpisodeItem(
                            feedTitle,
                            episode,
                            artSizePixels,
                            MediaId.podcastFeed(feedId)
                        )
                    }
                } else {
                    emptyList()
                }
            }
        }
    }

    private fun buildRecentUpdatesItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(MediaId.PODCAST_RECENT_UPDATES)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.podcast_recent_updates))
                    .setArtist(context.getString(R.string.podcast_recent_updates_description))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(podcastIconUri)
                    .build()
            )
            .build()
    }

    private fun getStreamingItems(): List<MediaItem> {
        return (1..5).map { index ->
            MediaItem.Builder()
                .setMediaId("lib_song_$index")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Library Song $index")
                        .setArtist("Artist $index")
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setArtworkUri(streamingIconUri)
                        .build()
                )
                .build()
        }
    }

    private fun buildFeedItem(feed: PodcastFeedEntity, artSizePixels: Int?): MediaItem {
        @OptIn(UnstableApi::class)
        val extras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        return MediaItem.Builder()
            .setMediaId(MediaId.podcastFeed(feed.feedId))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(feed.title)
                    .setArtist(feed.description ?: context.getString(R.string.podcast_feed_label))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(
                        artworkCache.contentUriForUrl(feed.artworkUrl, artSizePixels)
                            ?: podcastIconUri
                    )
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun buildEpisodeItem(
        feedTitle: String,
        episode: PodcastEpisodeEntity,
        artSizePixels: Int?,
        parentId: String? = null
    ): MediaItem {
        val extras = Bundle().apply {
            parentId?.let { putString(MediaId.EXTRA_PLAYBACK_PARENT_ID, it) }
        }
        return MediaItem.Builder()
            .setMediaId(MediaId.podcastEpisode(episode.episodeId))
            .setUri(episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(buildEpisodeSubtitle(feedTitle, episode))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(
                        artworkCache.contentUriForUrl(episode.artworkUrl, artSizePixels)
                            ?: podcastIconUri
                    )
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun buildRecentEpisodeItem(
        episode: RecentPodcastEpisodeRow,
        artSizePixels: Int?
    ): MediaItem {
        val extras = Bundle().apply {
            putString(MediaId.EXTRA_PLAYBACK_PARENT_ID, MediaId.PODCAST_RECENT_UPDATES)
        }
        return MediaItem.Builder()
            .setMediaId(MediaId.podcastEpisode(episode.episodeId))
            .setUri(episode.audioUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(episode.title)
                    .setArtist(
                        buildEpisodeSubtitle(
                            episode.feedTitle,
                            episode.publishedAtMs,
                            episode.durationText
                        )
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(
                        artworkCache.contentUriForUrl(episode.artworkUrl, artSizePixels)
                            ?: podcastIconUri
                    )
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    private fun buildEpisodeSubtitle(feedTitle: String, episode: PodcastEpisodeEntity): String {
        return buildEpisodeSubtitle(feedTitle, episode.publishedAtMs, episode.durationText)
    }

    private fun buildEpisodeSubtitle(
        feedTitle: String,
        publishedAtMs: Long,
        durationText: String?
    ): String {
        val publishedText = if (publishedAtMs > 0L) {
            DateFormat.format("yyyy-MM-dd", Date(publishedAtMs)).toString()
        } else {
            ""
        }
        return listOf(feedTitle, publishedText, durationText.orEmpty())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { context.getString(R.string.podcast_episode_label) }
    }
}
