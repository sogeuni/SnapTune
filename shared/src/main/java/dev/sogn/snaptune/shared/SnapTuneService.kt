package dev.sogn.snaptune.shared

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Media3 MediaLibraryService implementation.
 */
class SnapTuneService : MediaLibraryService() {

    companion object {
        private const val TAG = "SnapTuneService"
        private val trustedBrowserPackages = setOf(
            "com.android.car.media",
            "com.volvocars.launcher"
        )
    }

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private val mediaTree by lazy { SnapTuneMediaTree(this) }

    private val callback = object : MediaLibrarySession.Callback {

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (!controller.isTrusted && controller.packageName !in trustedBrowserPackages) {
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
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon().build()

            return MediaSession.ConnectionResult.accept(
                availableSessionCommands,
                availablePlayerCommands
            )
        }

        @OptIn(UnstableApi::class)
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(TAG, "onGetLibraryRoot for ${browser.packageName}")
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
            Log.d(TAG, "onGetChildren: parentId=$parentId, page=$page, pageSize=$pageSize")

//            if (parentId == MediaId.TAB_STREAMING) {
//                return Futures.immediateFuture(
//                    LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
//                )
//            }

            val children = mediaTree.getChildren(parentId)

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(children), params)
            )
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
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
        player?.release()
        mediaLibrarySession?.release()
        player = null
        mediaLibrarySession = null
        super.onDestroy()
    }
}
