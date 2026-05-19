package dev.sogn.snaptune.shared.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "podcast_feeds")
data class PodcastFeedEntity(
    @PrimaryKey
    val feedId: String,
    val title: String,
    val rssUrl: String,
    val description: String?,
    val artworkUrl: String?,
    val sortOrder: Int,
    val lastSummaryFetchedAtMs: Long,
    val lastRecentEpisodeFetchedAtMs: Long,
    val etag: String?,
    val lastModified: String?,
    val lastCheckedAtMs: Long,
    val lastSuccessAtMs: Long,
    val nextCheckAtMs: Long,
    val consecutiveFailureCount: Int,
    val lastHttpStatus: Int?,
    val lastEpisodeFetchedAtMs: Long
)
