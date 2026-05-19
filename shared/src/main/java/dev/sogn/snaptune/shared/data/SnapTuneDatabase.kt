package dev.sogn.snaptune.shared.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PodcastFeedEntity::class,
        PodcastEpisodeEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class SnapTuneDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
}
