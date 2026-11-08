package com.lithic.gallery

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.ai.MediaDecoding
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The app's own decoding (Android's decoders, not the PIL pixels the model tests are fed) gives
 * the models what PIL gives thumbsup2: the test images to the byte, transparent pixels with their
 * colour, a wide-gamut image's stored values, and captions in any language.
 */
@RunWith(AndroidJUnit4::class)
class MediaDecodingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val testDir = File(context.getExternalFilesDir(null), "test")
    private val scratch = context.cacheDir

    @Test
    fun decodesTheTestImagesAsPillowDoes() {
        val images = File(testDir, "images").listFiles()?.sortedBy { it.name }.orEmpty()
        assumeTrue("no test images: run tools\\push-test-data.bat", images.isNotEmpty())
        val wrong = ArrayList<String>()
        for (f in images) {
            val got = MediaDecoding.decodeImage(resolver, Uri.fromFile(f))
            var (w, h) = File(testDir, "raw/${f.name}.size").readText().trim().split(" ").map { it.toInt() }
            var want = File(testDir, "raw/${f.name}.rgb").readBytes()
            // The app turns photos upright; PIL, as thumbsup2 uses it, does not.
            val turns = when (ExifInterface(f).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 1
                ExifInterface.ORIENTATION_ROTATE_180 -> 2
                ExifInterface.ORIENTATION_ROTATE_270 -> 3
                else -> 0
            }
            repeat(turns) { want = rotateClockwise(want, w, h); val t = w; w = h; h = t }
            if (got.width != w || got.height != h) {
                wrong.add("${f.name}: ${got.width}x${got.height}, PIL ${w}x$h")
                continue
            }
            val differ = got.data.indices.count { got.data[it] != want[it] }
            if (differ > 0) wrong.add("${f.name}: $differ of ${want.size} bytes differ")
        }
        assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
    }

    private fun rotateClockwise(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(rgb.size)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (x * h + (h - 1 - y)) * 3
            System.arraycopy(rgb, (y * w + x) * 3, out, o, 3)
        }
        return out
    }

    @Test
    fun transparentPixelsKeepTheirColour() {
        // PIL's convert("RGB") drops alpha and keeps the stored colour.
        val rgba = byteArrayOf(
            255.toByte(), 255.toByte(), 255.toByte(), 0, // white, fully transparent
            200.toByte(), 40, 90, 128.toByte(), // half transparent
            10, 220.toByte(), 30, 1, // nearly transparent
            0, 0, 0, 255.toByte(),
        )
        val file = File(scratch, "transparent.png").apply { writeBytes(png(2, 2, rgba)) }
        val got = MediaDecoding.decodeImage(resolver, Uri.fromFile(file))
        val want = ByteArray(12) { rgba[it / 3 * 4 + it % 3] }
        assertThat(got.data.toList()).isEqualTo(want.toList())
    }

    @Test
    fun aWideGamutImageGivesItsStoredValues() {
        // Phones take Display P3 photos; PIL reads their numbers without converting them to sRGB.
        val p3 = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val bmp = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888, false, p3)
        val stored = byteArrayOf(20, 230.toByte(), 40, 255.toByte(), 240.toByte(), 30, 200.toByte(), 255.toByte())
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(stored))
        val file = File(scratch, "p3.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val got = MediaDecoding.decodeImage(resolver, Uri.fromFile(file))
        assertThat(got.data.toList()).isEqualTo(listOf<Byte>(20, 230.toByte(), 40, 240.toByte(), 30, 200.toByte()))
    }

    @Test
    fun captionsInAnyLanguageSurvive() {
        val text = "Café mit Großmutter, 東京"
        val exif = jpegWith(app1("Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiffWithDescription(text.toByteArray())))
        assertThat(MediaDecoding.manualCaption(resolver, Uri.fromFile(File(scratch, "exif.jpg").apply { writeBytes(exif) })))
            .isEqualTo(text)
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:description><rdf:Alt>" +
            "<rdf:li xml:lang=\"x-default\">$text</rdf:li></rdf:Alt></dc:description></rdf:Description></rdf:RDF></x:xmpmeta>"
        val withXmp = jpegWith(app1("http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()))
        assertThat(MediaDecoding.manualCaption(resolver, Uri.fromFile(File(scratch, "xmp.jpg").apply { writeBytes(withXmp) })))
            .isEqualTo(text)
    }

    /** A small JPEG with [segment] right after its start marker. */
    private fun jpegWith(segment: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out)
        val jpeg = out.toByteArray()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun app1(payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    /** A little-endian TIFF header and IFD0 holding one ImageDescription (raw bytes, NUL-ended). */
    private fun tiffWithDescription(value: ByteArray): ByteArray {
        val data = value + 0
        val b = ByteBuffer.allocate(8 + 18 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        b.putShort(1) // one entry
        b.putShort(0x010E).putShort(2).putInt(data.size).putInt(26) // ImageDescription, ASCII, at 26
        b.putInt(0) // no next IFD
        b.put(data)
        return b.array()
    }

    /** An 8-bit RGBA PNG of [rgba] (not premultiplied, as PNG stores it). */
    private fun png(w: Int, h: Int, rgba: ByteArray): ByteArray {
        fun chunk(type: String, body: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(t); update(body) }.value
            return ByteBuffer.allocate(12 + body.size).putInt(body.size).put(t).put(body).putInt(crc.toInt()).array()
        }
        val ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array()
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            raw.write(rgba, y * w * 4, w * 4)
        }
        val deflater = Deflater().apply { setInput(raw.toByteArray()); finish() }
        val z = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", z.toByteArray()) + chunk("IEND", ByteArray(0))
    }
}
