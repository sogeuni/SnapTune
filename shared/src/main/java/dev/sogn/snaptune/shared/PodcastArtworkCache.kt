package dev.sogn.snaptune.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class PodcastArtworkCache private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val artworkDirectory = File(appContext.cacheDir, ARTWORK_CACHE_DIRECTORY).apply {
        mkdirs()
    }

    fun contentUriForUrl(url: String?, sizePixels: Int? = null): Uri? {
        val sourceUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val builder = Uri.Builder()
            .scheme("content")
            .authority(authority(appContext))
            .appendPath(cacheKey(sourceUrl, sizePixels))
            .appendQueryParameter(QUERY_SOURCE_URL, sourceUrl)
        sizePixels?.takeIf { it > 0 }?.let { size ->
            builder.appendQueryParameter(QUERY_SIZE_PIXELS, size.toString())
        }
        return builder.build()
    }

    fun resolveCachedFile(uri: Uri): File? {
        val key = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val targetFile = fileForKey(key)
        if (targetFile.isFile && targetFile.length() > 0L) {
            return targetFile
        }

        val sourceUrl =
            uri.getQueryParameter(QUERY_SOURCE_URL)?.takeIf { it.isNotBlank() } ?: return null
        val sizePixels = uri.getQueryParameter(QUERY_SIZE_PIXELS)?.toIntOrNull()?.takeIf { it > 0 }
        return runCatching {
            downloadToFile(sourceUrl, targetFile, sizePixels)
            targetFile
        }.getOrNull()
    }

    private fun fileForKey(key: String): File = File(artworkDirectory, key)

    @Throws(IOException::class)
    private fun downloadToFile(sourceUrl: String, targetFile: File, sizePixels: Int?) {
        val downloadedFile = File(artworkDirectory, "${targetFile.name}.download.tmp")
        val tempFile = File(artworkDirectory, "${targetFile.name}.tmp")
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", "SnapTune/1.0")
        }
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Failed to fetch artwork $sourceUrl: HTTP ${connection.responseCode}")
            }
            downloadedFile.outputStream().use { output ->
                connection.inputStream.use { input ->
                    input.copyTo(output)
                }
            }
            if (sizePixels != null && sizePixels > 0) {
                resizeToHint(downloadedFile, tempFile, sizePixels)
            } else {
                copyOrMove(downloadedFile, tempFile)
            }
            copyOrMove(tempFile, targetFile)
        } finally {
            cleanupTempFile(downloadedFile)
            cleanupTempFile(tempFile)
            connection.disconnect()
        }
    }

    private fun resizeToHint(sourceFile: File, targetFile: File, sizePixels: Int) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            copyOrMove(sourceFile, targetFile)
            return
        }

        val targetDimensions = scaleToFit(bounds.outWidth, bounds.outHeight, sizePixels)
        if (targetDimensions.first >= bounds.outWidth && targetDimensions.second >= bounds.outHeight) {
            copyOrMove(sourceFile, targetFile)
            return
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetDimensions.first,
                targetDimensions.second
            )
        }
        val decodedBitmap = BitmapFactory.decodeFile(sourceFile.absolutePath, decodeOptions)
        if (decodedBitmap == null) {
            copyOrMove(sourceFile, targetFile)
            return
        }

        val scaledBitmap =
            if (decodedBitmap.width == targetDimensions.first && decodedBitmap.height == targetDimensions.second) {
                decodedBitmap
            } else {
                decodedBitmap.scale(targetDimensions.first, targetDimensions.second)
            }

        targetFile.outputStream().use { output ->
            val format = if (scaledBitmap.hasAlpha()) {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            scaledBitmap.compress(format, JPEG_QUALITY, output)
        }

        if (scaledBitmap !== decodedBitmap) {
            decodedBitmap.recycle()
        }
        scaledBitmap.recycle()
    }

    private fun scaleToFit(width: Int, height: Int, sizePixels: Int): Pair<Int, Int> {
        val maxDimension = maxOf(width, height)
        if (maxDimension <= sizePixels) {
            return width to height
        }
        val scale = sizePixels.toFloat() / maxDimension.toFloat()
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var inSampleSize = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= targetHeight) {
            currentWidth /= 2
            currentHeight /= 2
            inSampleSize *= 2
        }
        return inSampleSize
    }

    @Throws(IOException::class)
    private fun copyOrMove(sourceFile: File, targetFile: File) {
        if (sourceFile.renameTo(targetFile)) return

        sourceFile.copyTo(targetFile, overwrite = true)
        if (!sourceFile.delete()) {
            val rollbackException =
                if (!targetFile.delete() && targetFile.exists()) {
                    IOException("Failed to roll back target file: ${targetFile.absolutePath}")
                } else {
                    null
                }

            throw IOException("Failed to delete source file: ${sourceFile.absolutePath}").apply {
                rollbackException?.let(::addSuppressed)
            }
        }
    }

    private fun cleanupTempFile(file: File) {
        if (!file.exists()) {
            return
        }
        if (!file.delete()) {
            file.deleteOnExit()
        }
    }

    private fun cacheKey(url: String, sizePixels: Int?): String {
        val keySource = buildString {
            append(url)
            sizePixels?.takeIf { it > 0 }?.let {
                append('#')
                append(it)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(keySource.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val ARTWORK_CACHE_DIRECTORY = "podcast_artwork"
        private const val QUERY_SOURCE_URL = "source"
        private const val QUERY_SIZE_PIXELS = "size"
        private const val JPEG_QUALITY = 90

        @Volatile
        private var instance: PodcastArtworkCache? = null

        fun getInstance(context: Context): PodcastArtworkCache {
            return instance ?: synchronized(this) {
                instance ?: PodcastArtworkCache(context).also { instance = it }
            }
        }

        fun authority(context: Context): String = "${context.packageName}.artwork"
    }
}
