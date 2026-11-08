package com.lithic.gallery.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface

/**
 * Where photos and videos were taken, read from the files themselves: a photo's EXIF GPS, a
 * video's location tag. Android gives both only to an app holding ACCESS_MEDIA_LOCATION that asks
 * for the original file ([MediaStore.setRequireOriginal]); otherwise they read as nowhere, so
 * nothing is read without it.
 */
object MediaLocations {
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * Where [item] was taken, as its row in `locations` (latitude and longitude null when it does
     * not say), or null when it could not be read (an I/O error, a card taken out, the permission
     * taken back meanwhile): no row, so it is read again next time.
     */
    fun read(context: Context, item: MediaEntity): LocationEntity? {
        val uri = MediaStore.setRequireOriginal(item.contentUri)
        val at = try {
            if (item.isVideo) {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                }
            } else {
                val stream = context.contentResolver.openInputStream(uri) ?: return null
                stream.use { ExifInterface(it).latLong }?.let { it[0] to it[1] }
            }
        } catch (e: Exception) {
            Log.i("MediaLocations", "could not read where ${item.displayName} was taken: $e")
            return null
        }
        // 0, 0 is where a GPS that has no fix puts pictures, not the Gulf of Guinea.
        val valid = at?.takeIf { (lat, lon) -> lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) }
        return LocationEntity(item.aiKey, valid?.first, valid?.second)
    }

    /** "+37.4220-122.0841/" (ISO 6709, as videos store it) as (latitude, longitude). */
    internal fun parseIso6709(s: String?): Pair<Double, Double>? {
        val m = ISO_6709.find(s ?: return null) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    private val ISO_6709 = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")
}
