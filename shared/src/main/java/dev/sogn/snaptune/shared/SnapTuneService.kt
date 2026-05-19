package dev.sogn.snaptune.shared

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import dev.sogn.snaptune.shared.data.PodcastRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Media3 MediaLibraryService implementation.
 */
class SnapTuneService : MediaLibraryService() {

    companion object {
        private const val TAG = "SnapTuneService"
    }

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var podcastRepository: PodcastRepository
    private val browseExecutor = Executors.newSingleThreadExecutor()
    private val mediaArtSizeHints = ConcurrentHashMap<String, Int>()
    private val mediaTree by lazy { SnapTuneMediaTree(this, podcastRepository) }

    private val callback = object : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {

            if (!controller.isTrusted) {
                Log.w(TAG, "onConnect from: ${controller.packageName} denied!")
                return MediaSession.ConnectionResult.reject()
            }

            val availableSessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_CHILDREN)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_ITEM)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_UNSUBSCRIBE)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                    .add(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                    .build()

            val availablePlayerCommands =
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS

            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                availablePlayerCommands
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolvedItems = mediaItems.map { mediaItem ->
                mediaTree.resolvePlayableItem(mediaItem.mediaId) ?: mediaItem
            }.toMutableList()
            return Futures.immediateFuture(resolvedItems)
        }

        @OptIn(UnstableApi::class)
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.size == 1) {
                val requestedItem = mediaItems.first()
                val parentId =
                    requestedItem.mediaMetadata.extras?.getString(MediaId.EXTRA_PLAYBACK_PARENT_ID)
                val playbackQueue = mediaTree.resolvePlaybackQueue(requestedItem.mediaId, parentId)
                if (playbackQueue != null) {
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(
                            playbackQueue.mediaItems,
                            playbackQueue.startIndex,
                            startPositionMs
                        )
                    )
                }
            }

            val resolvedItems = mediaItems.map { mediaItem ->
                mediaTree.resolvePlayableItem(mediaItem.mediaId) ?: mediaItem
            }
            val resolvedStartIndex = if (resolvedItems.isEmpty()) {
                startIndex
            } else {
                startIndex.coerceIn(0, resolvedItems.lastIndex)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    resolvedItems,
                    resolvedStartIndex,
                    startPositionMs
                )
            )
        }

        @OptIn(UnstableApi::class)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(
                TAG,
                "onGetLibraryRoot package=${browser.packageName} params=${
                    describeLibraryParams(
                        params
                    )
                }"
            )
            params?.extras
                ?.getInt(MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS)
                ?.takeIf { it > 0 }
                ?.let { mediaArtSizeHints[browser.packageName] = it }
            val rootParams = LibraryParams.Builder()
                .setExtras(mediaTree.rootExtras)
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(mediaTree.rootItem, rootParams))
        }

        @OptIn(UnstableApi::class)
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            Log.d(
                TAG,
                "onGetChildren package=${browser.packageName} parentId=$parentId page=$page pageSize=$pageSize params=${
                    describeLibraryParams(
                        params
                    )
                }"
            )
            val artSizePixels = params?.extras
                ?.getInt(MediaConstants.EXTRAS_KEY_MEDIA_ART_SIZE_PIXELS)
                ?.takeIf { it > 0 }
                ?: mediaArtSizeHints[browser.packageName]
            artSizePixels?.let { mediaArtSizeHints[browser.packageName] = it }
            if (parentId == MediaId.TAB_PODCAST || mediaTree.isPodcastContainer(parentId)) {
                val task = ListenableFutureTask.create<LibraryResult<ImmutableList<MediaItem>>> {
                    if (parentId != MediaId.TAB_PODCAST && parentId != MediaId.PODCAST_RECENT_UPDATES) {
                        val feedId = MediaId.extractPodcastFeedId(parentId)
                        if (feedId != null) {
                            runCatching { podcastRepository.refreshFeedEpisodesIfNeeded(feedId) }
                                .onFailure { throwable ->
                                    Log.w(
                                        TAG,
                                        "Failed to refresh podcast feed $feedId: ${throwable.message}"
                                    )
                                }
                        }
                    }
                    val children = mediaTree.getChildren(parentId, artSizePixels)
                    LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
                }
                browseExecutor.execute(task)
                return task
            }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(
                        mediaTree.getChildren(
                            parentId,
                            artSizePixels
                        )
                    ), params
                )
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        PodcastSyncScheduler.ensureScheduled(this)
        podcastRepository = PodcastRepository.getInstance(this)
        player = ExoPlayer.Builder(this).build()
        mediaLibrarySession = MediaLibrarySession.Builder(this, player!!, callback).build()
        Log.d(TAG, "Service created")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        browseExecutor.shutdownNow()
        player?.release()
        mediaLibrarySession?.release()
        player = null
        mediaLibrarySession = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    private fun describeLibraryParams(params: LibraryParams?): String {
        if (params == null) {
            return "null"
        }

        return "isRecent=${params.isRecent} isOffline=${params.isOffline} isSuggested=${params.isSuggested} extras=${params.extras}"
    }
}
