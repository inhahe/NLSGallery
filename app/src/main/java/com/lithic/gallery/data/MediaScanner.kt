package com.lithic.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.security.MessageDigest

/**
 * Reads every photo and video MediaStore knows, on all external volumes: the whole library,
 * whenever it was added, not only what arrives after the app is installed.
 */
class MediaScanner(private val resolver: ContentResolver) {

    fun scan(): List<MediaEntity> = query(isVideo = false) + query(isVideo = true)

    private fun query(isVideo: Boolean): List<MediaEntity> {
        val collection = if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val cols = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.VOLUME_NAME)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.ORIENTATION)
            add(MediaStore.MediaColumns.DURATION)
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.MediaColumns.GENERATION_MODIFIED)
        }.toTypedArray()
        val out = ArrayList<MediaEntity>()
        resolver.query(collection, cols, null, null, null)?.use { c ->
            val iId = c.idx(MediaStore.MediaColumns._ID)
            val iVol = c.idx(MediaStore.MediaColumns.VOLUME_NAME)
            val iName = c.idx(MediaStore.MediaColumns.DISPLAY_NAME)
            val iPath = c.idx(MediaStore.MediaColumns.RELATIVE_PATH)
            val iMime = c.idx(MediaStore.MediaColumns.MIME_TYPE)
            val iTaken = c.idx(MediaStore.MediaColumns.DATE_TAKEN)
            val iMod = c.idx(MediaStore.MediaColumns.DATE_MODIFIED)
            val iAdded = c.idx(MediaStore.MediaColumns.DATE_ADDED)
            val iSize = c.idx(MediaStore.MediaColumns.SIZE)
            val iW = c.idx(MediaStore.MediaColumns.WIDTH)
            val iH = c.idx(MediaStore.MediaColumns.HEIGHT)
            val iOr = c.idx(MediaStore.MediaColumns.ORIENTATION)
            val iDur = c.idx(MediaStore.MediaColumns.DURATION)
            val iGen = if (Build.VERSION.SDK_INT >= 30) c.idx(MediaStore.MediaColumns.GENERATION_MODIFIED) else -1
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val volume = c.getString(iVol) ?: MediaStore.VOLUME_EXTERNAL
                val name = c.getString(iName) ?: ""
                val path = c.getString(iPath) ?: ""
                val modified = c.longOrZero(iMod)
                val size = c.longOrZero(iSize)
                val taken = c.longOrZero(iTaken)
                val orientation = c.intOrZero(iOr)
                var w = c.intOrZero(iW)
                var h = c.intOrZero(iH)
                // MediaStore reports the stored size; turned a quarter, it displays the other way round.
                if (orientation == 90 || orientation == 270) { val t = w; w = h; h = t }
                val uri = ContentUris.withAppendedId(
                    if (isVideo) MediaStore.Video.Media.getContentUri(volume)
                    else MediaStore.Images.Media.getContentUri(volume), id,
                )
                out.add(MediaEntity(
                    id = id,
                    uri = uri.toString(),
                    isVideo = isVideo,
                    displayName = name,
                    relativePath = path,
                    mimeType = c.getString(iMime) ?: "",
                    sortDate = when {
                        taken > 0 -> taken
                        modified > 0 -> modified * 1000
                        else -> c.longOrZero(iAdded) * 1000
                    },
                    dateModified = modified,
                    size = size,
                    width = w,
                    height = h,
                    orientation = orientation,
                    durationMs = c.longOrZero(iDur),
                    folder = Categories.folderOf(path, name).ordinal,
                    aiKey = aiKey(name, size, modified),
                    generation = if (iGen >= 0) c.longOrZero(iGen) else 0,
                ))
            }
        }
        return out
    }

    companion object {
        /**
         * The description cache key: sha1 of name, size and modification time, so that moving or
         * renaming a folder keeps a file's description (thumbsup2 keys its cache the same way).
         */
        fun aiKey(name: String, size: Long, modifiedSeconds: Long): String {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(name.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(size.toString().toByteArray())
            md.update(0)
            md.update(modifiedSeconds.toString().toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun Cursor.idx(name: String) = getColumnIndexOrThrow(name)
        private fun Cursor.longOrZero(i: Int) = if (isNull(i)) 0L else getLong(i)
        private fun Cursor.intOrZero(i: Int) = if (isNull(i)) 0 else getInt(i)

        /** The image or video collection URIs the app watches for changes. */
        val watchedUris: List<Uri> = listOf(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        )
    }
}
