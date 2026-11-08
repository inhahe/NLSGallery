package com.lithic.gallery.core.image

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Against tools/testdata/ops, written by tools/make_image_reference.py with torchvision, Pillow
 * and OpenCV from thumbsup2's environment. The inputs are PNGs, so Java reads the same pixels.
 */
class ImageOpsTest {
    private val names = listOf("noise517x389", "grad1000x700", "small200x150", "strip3000x100", "checker1000x700")

    private fun load(name: String): RgbImage {
        val img = ImageIO.read(TestFiles.op("$name.png"))
        val argb = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        val rgb = ByteArray(argb.size * 3)
        for ((i, p) in argb.withIndex()) {
            rgb[i * 3] = (p shr 16).toByte()
            rgb[i * 3 + 1] = (p shr 8).toByte()
            rgb[i * 3 + 2] = p.toByte()
        }
        return RgbImage(img.width, img.height, rgb)
    }

    /** How many bytes differ, and by at most how much. */
    private fun compare(what: String, got: ByteArray, want: ByteArray) {
        assertWithMessage("$what size").that(got.size).isEqualTo(want.size)
        var differ = 0
        var worst = 0
        for (i in got.indices) {
            val d = Math.abs((got[i].toInt() and 0xFF) - (want[i].toInt() and 0xFF))
            if (d != 0) { differ++; worst = maxOf(worst, d) }
        }
        assertWithMessage("$what: $differ of ${got.size} bytes differ, by up to $worst").that(differ).isEqualTo(0)
    }

    @Test
    fun torchvisionBicubicLikeBlipsProcessor() {
        for (n in names) {
            val got = Resample.torch(load(n), 384, 384)
            val flat = ByteArray(3 * 384 * 384)
            for (c in 0 until 3) System.arraycopy(got[c], 0, flat, c * 384 * 384, 384 * 384)
            compare("torch $n", flat, TestFiles.op("blip_$n.u8").readBytes())
        }
    }

    @Test
    fun pillowBicubicOnGrey() {
        for (n in names) {
            val grey = pilGrey(load(n))
            for ((w, h) in listOf(64 to 64, 37 to 64, 300 to 64, 64 to 211)) {
                compare("pil $n ${w}x$h", Resample.pil(grey, w, h).data,
                    TestFiles.op("pilL_${n}_${w}x$h.u8").readBytes())
            }
        }
    }

    @Test
    fun openCvLinearResize() {
        for (n in names) {
            val img = load(n)
            for ((w, h) in listOf(257 to 193, 1000 to 700, 2560 to 1920, 64 to 64, 91 to 64)) {
                compare("cv $n ${w}x$h", CvOps.resizeLinear(img, w, h).data,
                    TestFiles.op("cvlin_${n}_${w}x$h.u8").readBytes())
            }
        }
    }

    @Test
    fun easyOcrGrey() {
        for (n in names) {
            compare("grey $n", CvOps.easyOcrGray(load(n)).data, TestFiles.op("gray_$n.u8").readBytes())
        }
    }

    @Test
    fun perspectiveWarpLikeFourPointTransform() {
        for (n in names) {
            val grey = CvOps.easyOcrGray(load(n))
            val w = grey.width.toDouble()
            val h = grey.height.toDouble()
            // As the reference script builds it: products in double, then float32.
            val rect = doubleArrayOf(w * 0.1, h * 0.2, w * 0.8, h * 0.1, w * 0.9, h * 0.7, w * 0.2, h * 0.9)
                .map { it.toFloat() }.toFloatArray()
            val got = fourPointTransformForTest(grey, rect)
            val (ww, hh) = File(TestFiles.testdata, "ops/warp_$n.shape").readText().trim().split(" ").map { it.toInt() }
            assertWithMessage("warp $n size").that(listOf(got.width, got.height)).isEqualTo(listOf(ww, hh))
            compare("warp $n", got.data, TestFiles.op("warp_$n.u8").readBytes())
        }
    }

    /** PIL's convert("L"): ITU-R 601-2 luma, (R*19595 + G*38470 + B*7471 + 0x8000) >> 16. */
    private fun pilGrey(img: RgbImage): GrayImage {
        val out = ByteArray(img.width * img.height)
        for (i in out.indices) {
            val r = img.data[i * 3].toInt() and 0xFF
            val g = img.data[i * 3 + 1].toInt() and 0xFF
            val b = img.data[i * 3 + 2].toInt() and 0xFF
            out[i] = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16).toByte()
        }
        return GrayImage(img.width, img.height, out)
    }

    private fun fourPointTransformForTest(img: GrayImage, rect: FloatArray): GrayImage =
        com.lithic.gallery.core.ai.easyocr.Crops.fourPointTransform(img, rect)
}
