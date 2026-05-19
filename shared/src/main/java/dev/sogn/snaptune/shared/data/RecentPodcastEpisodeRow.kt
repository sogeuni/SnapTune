package dev.sogn.snaptune.shared.data

data class RecentPodcastEpisodeRow(
    val episodeId: String,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val description: String?,
    val audioUrl: String,
    val artworkUrl: String?,
    val episodeUrl: String?,
    val durationText: String?,
    val publishedAtMs: Long
)
