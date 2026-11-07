package com.lithic.gallery.ui

import android.content.ContentResolver
import android.net.Uri
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * A grid thumbnail: MediaStore's own cached thumbnail of [uri] (made by the system, already
 * upright, a frame for a video), at least [size] pixels on its longer side.
 */
data class MediaThumb(val uri: String, val size: Int, val dateModified: Long) {
    companion object {
        /** Thumbnail sizes requested, so a little resizing reuses the cached ones. */
        private val BUCKETS = intArrayOf(160, 256, 384, 512, 768, 1024)

        fun forCell(uri: String, cellPx: Int, dateModified: Long) =
            MediaThumb(uri, BUCKETS.firstOrNull { it >= cellPx } ?: BUCKETS.last(), dateModified)
    }
}

class MediaThumbFetcher(
    private val data: MediaThumb,
    private val resolver: ContentResolver,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bitmap = runInterruptible {
            resolver.loadThumbnail(Uri.parse(data.uri), Size(data.size, data.size), null)
        }
        ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, resolver)
    }
}

/** Memory-cache key: the file (and its version) and the size bucket. */
class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "${data.uri}#${data.dateModified}#${data.size}"
}
