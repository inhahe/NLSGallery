package com.lithic.gallery.ai

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.lithic.gallery.core.image.RgbImage
import kotlin.math.max

/** Reading photos and video frames into the pixels the models take. */
object MediaDecoding {
    /**
     * Images larger than this many pixels are decoded subsampled (a 12-megapixel photo is
     * decoded whole, as thumbsup2 reads it; a 108-megapixel one at a quarter of each side).
     */
    const val MAX_PIXELS = 16_800_000

    /**
     * The photo at [uri], upright (its EXIF rotation applied, which thumbsup2 does not do: it
     * describes a sideways photo sideways), as 8-bit RGB. Animated images give their first frame.
     */
    fun decodeImage(resolver: ContentResolver, uri: Uri): RgbImage {
        val source = ImageDecoder.createSource(resolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val px = info.size.width.toLong() * info.size.height
            var sample = 1
            while (px / (sample.toLong() * sample) > MAX_PIXELS) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        try {
            return toRgb(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Frames of the video at [uri] at the given fractions of its length, upright, scaled so
     * neither side exceeds [maxSide].
     */
    fun decodeVideoFrames(context: Context, uri: Uri, fractions: List<Double>, maxSide: Int = 1920): List<RgbImage> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durationUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frames = ArrayList<RgbImage>()
            for (f in fractions) {
                val t = (durationUs * f).toLong()
                val bmp = if (w > 0 && h > 0 && max(w, h) > maxSide) {
                    val s = maxSide.toDouble() / max(w, h)
                    r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, (w * s).toInt(), (h * s).toInt())
                } else {
                    r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: continue
                try {
                    frames.add(toRgb(bmp))
                } finally {
                    bmp.recycle()
                }
            }
            return frames
        } finally {
            r.release()
        }
    }

    /** A video's own title (thumbsup2's QuickTime Title), or "". */
    fun videoTitle(context: Context, uri: Uri): String {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() ?: ""
        } catch (e: RuntimeException) {
            ""
        } finally {
            r.release()
        }
    }

    /**
     * A photo's own caption, in thumbsup2's order of preference as far as Android can read it:
     * EXIF ImageDescription, then XMP description, title, label. "" when it has none.
     */
    fun manualCaption(resolver: ContentResolver, uri: Uri): String = try {
        resolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() && !it.isPlaceholder() }
                ?: exif.getAttribute(ExifInterface.TAG_XMP)?.let(::xmpCaption)
                ?: ""
        } ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Camera firmware fills ImageDescription with placeholders like "OLYMPUS DIGITAL CAMERA". */
    private fun String.isPlaceholder() = matches(Regex("(?i)(olympus digital camera|sony dsc|default|image|\\s+)"))

    internal fun xmpCaption(xmp: String): String? {
        for (tag in listOf("dc:description", "dc:title", "xmp:Label")) {
            val block = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xmp)?.groupValues?.get(1)
                ?: Regex("$tag=\"([^\"]*)\"").find(xmp)?.groupValues?.get(1)
                ?: continue
            val text = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1) ?: block
            val clean = text.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim()
            if (clean.isNotEmpty()) return clean
        }
        return null
    }

    /** The bitmap's pixels as RGB (alpha dropped, as PIL's convert("RGB") does), read in strips. */
    private fun toRgb(bitmap: Bitmap): RgbImage {
        val src = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val w = src.width
            val h = src.height
            val out = ByteArray(w * h * 3)
            val rows = max(1, 1 shl 20 / max(1, w))
            val buf = IntArray(w * rows)
            var y = 0
            while (y < h) {
                val n = minOf(rows, h - y)
                src.getPixels(buf, 0, w, 0, y, w, n)
                var o = y * w * 3
                for (i in 0 until w * n) {
                    val p = buf[i]
                    out[o++] = (p shr 16).toByte()
                    out[o++] = (p shr 8).toByte()
                    out[o++] = p.toByte()
                }
                y += n
            }
            return RgbImage(w, h, out)
        } finally {
            if (src !== bitmap) src.recycle()
        }
    }
}
