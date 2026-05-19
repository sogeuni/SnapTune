package dev.sogn.snaptune.shared.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "podcast_episodes",
    indices = [
        Index(value = ["feedId", "publishedAtMs"]),
        Index(value = ["feedId", "episodeGuid"], unique = true)
    ]
)
data class PodcastEpisodeEntity(
    @PrimaryKey
    val episodeId: String,
    val feedId: String,
    val episodeGuid: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val artworkUrl: String?,
    val episodeUrl: String?,
    val durationText: String?,
    val publishedAtMs: Long
)
