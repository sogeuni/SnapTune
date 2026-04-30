package dev.sogn.snaptune.shared

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

/**
 * 미디어 ID 관련 상수
 */
object MediaId {
    const val ROOT = "root"
    const val TAB_STREAMING = "streaming_tab"
    const val TAB_PODCAST = "podcast_tab"
}

/**
 * 미디어 계층 구조 및 아이템 제공을 담당하는 클래스
 */
class SnapTuneMediaTree(private val context: Context) {

    private val streamingIconUri = localResourceUri(R.drawable.ic_streaming)
    private val podcastIconUri = localResourceUri(R.drawable.ic_podcast)

    @OptIn(UnstableApi::class)
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

    /**
     * 최상위 루트 아이템. 처음 접근 시점에 한 번만 초기화됩니다.
     */
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

    /**
     * 상단 탭 목록. 처음 접근 시점에 한 번만 초기화됩니다.
     */

    val tabs: List<MediaItem> by lazy {
        @OptIn(UnstableApi::class)
        val libraryExtras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        val libraryTab = MediaItem.Builder()
            .setMediaId(MediaId.TAB_STREAMING)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.tab_streaming))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(streamingIconUri)
                    .setExtras(libraryExtras)
                    .build()
            )
            .build()

        @OptIn(UnstableApi::class)
        val recentExtras = Bundle().apply {
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
            )
        }
        val recentTab = MediaItem.Builder()
            .setMediaId(MediaId.TAB_PODCAST)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(context.getString(R.string.tab_podcast))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(podcastIconUri)
                    .setExtras(recentExtras)
                    .build()
            )
            .build()

        listOf(libraryTab, recentTab)
    }

    @OptIn(UnstableApi::class)
    fun getChildren(parentId: String): List<MediaItem> {
        Log.d("SnapTuneMediaTree", "getChildren called for parentId: $parentId")
        val items = mutableListOf<MediaItem>()
        
        when (parentId) {
            MediaId.ROOT, "" -> {
                // 상단 탭 리스트를 한 번에 추가
                items.addAll(tabs)
            }
            MediaId.TAB_STREAMING -> {
                for (i in 1..5) {
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("lib_song_$i")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Library Song $i")
                                    .setArtist("Artist $i")
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .setArtworkUri(streamingIconUri)
                                    .build()
                            )
                            .build()
                    )
                }
            }
            MediaId.TAB_PODCAST -> {
                val group1Extras = Bundle().apply {
                    putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE", "오늘 업데이트")
                }
                items.add(
                    MediaItem.Builder()
                        .setMediaId("recent_song_1")
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("최신 곡 1")
                                .setArtist("최신 아티스트 1")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                .setArtworkUri(podcastIconUri)
                                .setExtras(group1Extras)
                                .build()
                        )
                        .build()
                )
                for (i in 2..5) {
                    items.add(
                        MediaItem.Builder()
                            .setMediaId("recent_song_$i")
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("최신 곡 $i")
                                    .setArtist("최신 아티스트 $i")
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .setArtworkUri(podcastIconUri)
                                    .build()
                            )
                            .build()
                    )
                }
            }
        }
        Log.d("SnapTuneMediaTree", "Returning ${items.size} items for $parentId")
        return items
    }
}
