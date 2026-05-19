package dev.sogn.snaptune.shared.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class PodcastDao {

    @Query("SELECT * FROM podcast_feeds ORDER BY sortOrder ASC, title ASC")
    abstract fun getFeeds(): List<PodcastFeedEntity>

    @Query("SELECT * FROM podcast_feeds WHERE feedId = :feedId LIMIT 1")
    abstract fun getFeed(feedId: String): PodcastFeedEntity?

    @Query(
        """
        SELECT * FROM podcast_feeds
        WHERE nextCheckAtMs <= :currentTimeMs
        ORDER BY nextCheckAtMs ASC, sortOrder ASC, title ASC
        LIMIT :limit
        """
    )
    abstract fun getFeedsDueForSync(currentTimeMs: Long, limit: Int): List<PodcastFeedEntity>

    @Query("SELECT * FROM podcast_episodes WHERE feedId = :feedId ORDER BY publishedAtMs DESC, title ASC")
    abstract fun getEpisodesForFeed(feedId: String): List<PodcastEpisodeEntity>

    @Query(
        """
        SELECT
            podcast_episodes.episodeId AS episodeId,
            podcast_episodes.feedId AS feedId,
            podcast_feeds.title AS feedTitle,
            podcast_episodes.title AS title,
            podcast_episodes.description AS description,
            podcast_episodes.audioUrl AS audioUrl,
            podcast_episodes.artworkUrl AS artworkUrl,
            podcast_episodes.episodeUrl AS episodeUrl,
            podcast_episodes.durationText AS durationText,
            podcast_episodes.publishedAtMs AS publishedAtMs
        FROM podcast_episodes
        INNER JOIN podcast_feeds ON podcast_feeds.feedId = podcast_episodes.feedId
        ORDER BY podcast_episodes.publishedAtMs DESC, podcast_episodes.title ASC
        LIMIT :limit
        """
    )
    abstract fun getRecentEpisodes(limit: Int): List<RecentPodcastEpisodeRow>

    @Query("SELECT * FROM podcast_episodes WHERE episodeId = :episodeId LIMIT 1")
    abstract fun getEpisode(episodeId: String): PodcastEpisodeEntity?

    @Query("SELECT COUNT(*) FROM podcast_episodes WHERE feedId = :feedId")
    abstract fun countEpisodesForFeed(feedId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertFeed(feed: PodcastFeedEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertFeeds(feeds: List<PodcastFeedEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertEpisodes(episodes: List<PodcastEpisodeEntity>)

    @Query("DELETE FROM podcast_episodes WHERE feedId = :feedId")
    abstract fun deleteEpisodesForFeed(feedId: String)

    @Query(
        """
        DELETE FROM podcast_episodes
        WHERE feedId = :feedId
          AND episodeId NOT IN (
              SELECT episodeId
              FROM podcast_episodes
              WHERE feedId = :feedId
              ORDER BY publishedAtMs DESC, title ASC
              LIMIT :limit
          )
        """
    )
    abstract fun trimEpisodesForFeed(feedId: String, limit: Int)

    @Transaction
    open fun replaceEpisodesForFeed(feedId: String, episodes: List<PodcastEpisodeEntity>) {
        deleteEpisodesForFeed(feedId)
        if (episodes.isNotEmpty()) {
            upsertEpisodes(episodes)
        }
    }
}
