package com.lithic.gallery.core.image

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The two antialiased bicubic resizers thumbsup2's models see images through, reproduced to the
 * bit so the app feeds the models what thumbsup2 does:
 *
 *  - [pil]: Pillow's `Image.resize(size, BICUBIC)` (Resample.c), used by EasyOCR's recognizer;
 *  - [torch]: torchvision's `resize(uint8 tensor, BICUBIC, antialias=True)` on x86 (ATen's
 *    UpSampleKernelAVXAntialias), used by transformers' BLIP image processor.
 *
 * Both are separable, horizontal pass then vertical, with an 8-bit image in between and the same
 * Keys cubic (a = -0.5) widened by the downscale factor. They differ in how they round the
 * weights to integers, which moves some pixels by one.
 */
object Resample {

    /** Pillow's bicubic resize of [src] to [w]×[h]. */
    fun pil(src: GrayImage, w: Int, h: Int): GrayImage {
        if (w == src.width && h == src.height) return GrayImage(w, h, src.data.copyOf())
        val out = pilPlanes(arrayOf(src.data), src.width, src.height, w, h)
        return GrayImage(w, h, out[0])
    }

    /** Pillow's bicubic resize of [src] to [w]×[h]. */
    fun pil(src: RgbImage, w: Int, h: Int): RgbImage {
        if (w == src.width && h == src.height) return RgbImage(w, h, src.data.copyOf())
        return interleave(pilPlanes(planes(src), src.width, src.height, w, h), w, h)
    }

    /**
     * torchvision's antialiased bicubic resize of [src] to [w]×[h], as channel planes (CHW): the
     * layout the model's input tensor is built from.
     */
    fun torch(src: RgbImage, w: Int, h: Int): Array<ByteArray> {
        var cur = planes(src)
        var cw = src.width
        if (w != src.width) {
            val k = torchKernel(src.width, w)
            cur = Array(3) { c -> horizontal(cur[c], cw, src.height, w, k) }
            cw = w
        }
        if (h != src.height) {
            val k = torchKernel(src.height, h)
            cur = Array(3) { c -> vertical(cur[c], cw, src.height, h, k) }
        }
        return cur
    }

    // ---------------------------------------------------------------- kernels

    /**
     * Integer weights of a 1-D resize from [inSize] to [outSize] samples: output i reads inputs
     * [start(i), start(i) + count(i)) with weights [weights] at i*stride, summed with
     * `(sum + 2^(precision-1)) >> precision` and clamped to 0..255.
     */
    private class Kernel(
        val start: IntArray, val count: IntArray, val weights: IntArray,
        val stride: Int, val precision: Int,
    )

    private const val PIL_PRECISION = 22

    /** Pillow's filter, `bicubic_filter` in Resample.c. */
    private fun pilCubic(x0: Double): Double {
        val a = -0.5
        val x = abs(x0)
        if (x < 1.0) return ((a + 2.0) * x - (a + 3.0)) * x * x + 1
        if (x < 2.0) return (((x - 5) * x + 8) * x - 4) * a
        return 0.0
    }

    /** ATen's `aa_filter` for bicubic: the same cubic, evaluated in a different order. */
    private fun torchCubic(x0: Double): Double {
        val a = -0.5
        val x = abs(x0)
        if (x < 1.0) return ((a + 2) * x - (a + 3)) * x * x + 1
        if (x < 2.0) return ((a * x - 5 * a) * x + 8 * a) * x - 4 * a
        return 0.0
    }

    /** Pillow's `precompute_coeffs` + `normalize_coeffs_8bpc`. */
    private fun pilKernel(inSize: Int, outSize: Int): Kernel {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 2.0 * filterScale
        val ksize = ceil(support).toInt() * 2 + 1
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val weights = IntArray(outSize * ksize)
        val k = DoubleArray(ksize)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale
            var xmin = (center - support + 0.5).toInt()   // C cast: toward zero
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            var ww = 0.0
            for (x in 0 until xmax) {
                val wgt = pilCubic((x + xmin - center + 0.5) * ss)
                k[x] = wgt
                ww += wgt
            }
            for (x in 0 until xmax) {
                if (ww != 0.0) k[x] /= ww
                val v = k[x] * (1 shl PIL_PRECISION)
                weights[xx * ksize + x] = if (k[x] < 0) (-0.5 + v).toInt() else (0.5 + v).toInt()
            }
            start[xx] = xmin
            count[xx] = xmax
        }
        return Kernel(start, count, weights, ksize, PIL_PRECISION)
    }

    /** ATen's `_compute_index_ranges_int16_weights` for antialiased bicubic. */
    private fun torchKernel(inSize: Int, outSize: Int): Kernel {
        val scale = inSize.toDouble() / outSize
        val support = if (scale >= 1.0) 2.0 * scale else 2.0
        val maxSize = ceil(support).toInt() * 2 + 1
        val invScale = if (scale >= 1.0) 1.0 / scale else 1.0
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val f = DoubleArray(outSize * maxSize)
        var wtMax = 0.0
        for (i in 0 until outSize) {
            val center = scale * (i + 0.5)
            val xmin = max((center - support + 0.5).toLong(), 0L).toInt()
            var xsize = (min((center + support + 0.5).toLong(), inSize.toLong()) - xmin).toInt()
            xsize = xsize.coerceIn(0, maxSize)
            var total = 0.0
            for (j in 0 until xsize) {
                val w = torchCubic((j + xmin - center + 0.5) * invScale)
                f[i * maxSize + j] = w
                total += w
            }
            if (total != 0.0) {
                for (j in 0 until xsize) {
                    f[i * maxSize + j] /= total
                    wtMax = max(wtMax, f[i * maxSize + j])
                }
            }
            start[i] = xmin
            count[i] = xsize
        }
        var precision = 0
        while (precision < 22) {
            val next = (0.5 + wtMax * (1 shl (precision + 1))).toInt()
            if (next >= (1 shl 15)) break
            precision++
        }
        val weights = IntArray(f.size)
        for (i in f.indices) {
            val v = f[i] * (1 shl precision)
            // int16 storage, as ATen keeps them
            weights[i] = (if (v < 0) (-0.5 + v).toInt() else (0.5 + v).toInt()).toShort().toInt()
        }
        return Kernel(start, count, weights, maxSize, precision)
    }

    // ----------------------------------------------------------------- passes

    private fun clip8(sum: Long, precision: Int): Byte {
        val v = sum shr precision
        return (if (v < 0) 0 else if (v > 255) 255 else v.toInt()).toByte()
    }

    private fun horizontal(src: ByteArray, w: Int, h: Int, outW: Int, k: Kernel): ByteArray {
        val out = ByteArray(outW * h)
        val round = 1L shl (k.precision - 1)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until outW) {
                var sum = round
                val s = k.start[x]
                val base = x * k.stride
                for (i in 0 until k.count[x]) {
                    sum += (src[row + s + i].toInt() and 0xFF).toLong() * k.weights[base + i]
                }
                out[y * outW + x] = clip8(sum, k.precision)
            }
        }
        return out
    }

    private fun vertical(src: ByteArray, w: Int, h: Int, outH: Int, k: Kernel): ByteArray {
        val out = ByteArray(w * outH)
        val round = 1L shl (k.precision - 1)
        for (y in 0 until outH) {
            val s = k.start[y]
            val base = y * k.stride
            for (x in 0 until w) {
                var sum = round
                for (i in 0 until k.count[y]) {
                    sum += (src[(s + i) * w + x].toInt() and 0xFF).toLong() * k.weights[base + i]
                }
                out[y * w + x] = clip8(sum, k.precision)
            }
        }
        return out
    }

    /**
     * Pillow's two-pass resize. The horizontal pass covers only the rows the vertical pass reads,
     * which changes nothing in the result.
     */
    private fun pilPlanes(src: Array<ByteArray>, w: Int, h: Int, outW: Int, outH: Int): Array<ByteArray> {
        var cur = src
        var cw = w
        if (outW != w) {
            val k = pilKernel(w, outW)
            cur = Array(cur.size) { c -> horizontal(cur[c], cw, h, outW, k) }
            cw = outW
        }
        if (outH != h) {
            val k = pilKernel(h, outH)
            cur = Array(cur.size) { c -> vertical(cur[c], cw, h, outH, k) }
        }
        return cur
    }

    internal fun planes(src: RgbImage): Array<ByteArray> {
        val n = src.width * src.height
        val p = Array(3) { ByteArray(n) }
        for (i in 0 until n) {
            p[0][i] = src.data[i * 3]
            p[1][i] = src.data[i * 3 + 1]
            p[2][i] = src.data[i * 3 + 2]
        }
        return p
    }

    internal fun interleave(p: Array<ByteArray>, w: Int, h: Int): RgbImage {
        val out = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            out[i * 3] = p[0][i]
            out[i * 3 + 1] = p[1][i]
            out[i * 3 + 2] = p[2][i]
        }
        return RgbImage(w, h, out)
    }
}
