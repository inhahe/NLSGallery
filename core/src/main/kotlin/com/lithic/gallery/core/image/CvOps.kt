package com.lithic.gallery.core.image

import kotlin.math.floor

/**
 * The OpenCV operations EasyOCR runs on its input, reproduced from OpenCV 4's imgproc for 8-bit
 * images so the recognizer sees what it sees under thumbsup2.
 */
object CvOps {

    /**
     * `cv2.cvtColor(arr, COLOR_BGR2GRAY)` on an RGB array, which is what EasyOCR computes from the
     * array thumbsup2 hands it: OpenCV takes the first channel for blue, so red gets blue's weight.
     */
    fun easyOcrGray(src: RgbImage): GrayImage {
        val out = ByteArray(src.width * src.height)
        val d = src.data
        for (i in out.indices) {
            val c0 = d[i * 3].toInt() and 0xFF
            val c1 = d[i * 3 + 1].toInt() and 0xFF
            val c2 = d[i * 3 + 2].toInt() and 0xFF
            // OpenCV's 15-bit luma weights (B 3735, G 19235, R 9798), blue taken from channel 0
            out[i] = ((c0 * 3735 + c1 * 19235 + c2 * 9798 + (1 shl 14)) shr 15).toByte()
        }
        return GrayImage(src.width, src.height, out)
    }

    private const val COEF_BITS = 11
    private const val COEF_SCALE = 1 shl COEF_BITS

    /** Per-output-position source index and the two fixed-point weights (cv::resize setup). */
    private class Linear1D(val src: IntArray, val a0: IntArray, val a1: IntArray)

    /**
     * @param clamp whether an edge position is pinned to the edge pixel with weight 1, as OpenCV
     *   does horizontally. Vertically it is not: the two source rows are clipped to the image
     *   instead, so an edge row is blended with itself.
     */
    private fun linear1D(inSize: Int, outSize: Int, clamp: Boolean): Linear1D {
        val scale = 1.0 / (outSize.toDouble() / inSize)
        val src = IntArray(outSize)
        val a0 = IntArray(outSize)
        val a1 = IntArray(outSize)
        for (d in 0 until outSize) {
            var f = ((d + 0.5) * scale - 0.5).toFloat()
            var s = floor(f).toInt()
            f -= s
            if (clamp && s < 0) { f = 0f; s = 0 }
            if (clamp && s >= inSize - 1) { f = 0f; s = inSize - 1 }
            src[d] = s
            a0[d] = roundHalfEven((1f - f) * COEF_SCALE)
            a1[d] = roundHalfEven(f * COEF_SCALE)
        }
        return Linear1D(src, a0, a1)
    }

    /** `saturate_cast<short>(float)`: rounds half to even, like lrintf. */
    private fun roundHalfEven(v: Float): Int = Math.rint(v.toDouble()).toInt()

    /**
     * `cv2.resize(src, (w, h), interpolation=INTER_LINEAR)` for [channels]-channel interleaved 8-bit
     * data. The vertical step reproduces OpenCV's 128-bit SIMD path (VResizeLinearVec_32s8u) for the
     * elements it covers and its scalar formula for the rest of each row, as OpenCV's x86 build does.
     */
    fun resizeLinear(src: ByteArray, w: Int, h: Int, channels: Int, outW: Int, outH: Int): ByteArray {
        if (outW == w && outH == h) return src.copyOf()
        // Halving both sides exactly, OpenCV switches INTER_LINEAR to its fast INTER_AREA path.
        if (outW * 2 == w && outH * 2 == h) return halve(src, w, channels, outW, outH)
        val xs = linear1D(w, outW, clamp = true)
        val ys = linear1D(h, outH, clamp = false)
        val rowLen = outW * channels
        // Horizontal pass, one source row at a time, into ints scaled by 2048.
        val hrows = HashMap<Int, IntArray>()
        fun hrow(sy: Int): IntArray = hrows.getOrPut(sy) {
            val r = IntArray(rowLen)
            val base = sy * w * channels
            for (dx in 0 until outW) {
                val sx = xs.src[dx]
                val sx1 = if (sx + 1 < w) sx + 1 else sx
                for (c in 0 until channels) {
                    val p0 = src[base + sx * channels + c].toInt() and 0xFF
                    val p1 = src[base + sx1 * channels + c].toInt() and 0xFF
                    r[dx * channels + c] = if (xs.a1[dx] == 0) p0 * COEF_SCALE
                    else p0 * xs.a0[dx] + p1 * xs.a1[dx]
                }
            }
            r
        }
        val out = ByteArray(rowLen * outH)
        for (dy in 0 until outH) {
            val sy = ys.src[dy].coerceIn(0, h - 1)
            val s0 = hrow(sy)
            val s1 = hrow((ys.src[dy] + 1).coerceIn(0, h - 1))
            val b0 = ys.a0[dy]
            val b1 = ys.a1[dy]
            val o = dy * rowLen
            // OpenCV's SIMD form (VResizeLinearVec_32s8u), which drops low bits before multiplying
            // and so can come out one lower than exact rounding; it covers whole rows.
            for (x in 0 until rowLen) {
                val v = ((((s0[x] shr 4) * b0) shr 16) + (((s1[x] shr 4) * b1) shr 16) + 2) shr 2
                out[o + x] = v.coerceIn(0, 255).toByte()
            }
            if (hrows.size > 4) hrows.keys.retainAll(setOf(sy, sy + 1))
        }
        return out
    }

    /** OpenCV's fast 2×2 INTER_AREA: each output the rounded mean of a 2×2 block. */
    private fun halve(src: ByteArray, w: Int, channels: Int, outW: Int, outH: Int): ByteArray {
        val out = ByteArray(outW * outH * channels)
        val step = w * channels
        for (y in 0 until outH) for (x in 0 until outW) for (c in 0 until channels) {
            val p = 2 * y * step + 2 * x * channels + c
            val s = (src[p].toInt() and 0xFF) + (src[p + channels].toInt() and 0xFF) +
                (src[p + step].toInt() and 0xFF) + (src[p + step + channels].toInt() and 0xFF)
            out[(y * outW + x) * channels + c] = ((s + 2) shr 2).toByte()
        }
        return out
    }

    fun resizeLinear(src: GrayImage, w: Int, h: Int): GrayImage =
        GrayImage(w, h, resizeLinear(src.data, src.width, src.height, 1, w, h))

    fun resizeLinear(src: RgbImage, w: Int, h: Int): RgbImage =
        RgbImage(w, h, resizeLinear(src.data, src.width, src.height, 3, w, h))

    /**
     * `cv2.getPerspectiveTransform(src, dst)` for float32 points (x0, y0, x1, y1, …): the 3×3
     * matrix, row-major, mapping [src] to [dst].
     */
    fun perspectiveTransform(src: FloatArray, dst: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(8) }
        val b = DoubleArray(8)
        for (i in 0 until 4) {
            val sx = src[2 * i]; val sy = src[2 * i + 1]
            val dx = dst[2 * i]; val dy = dst[2 * i + 1]
            a[i][0] = sx.toDouble(); a[i + 4][3] = sx.toDouble()
            a[i][1] = sy.toDouble(); a[i + 4][4] = sy.toDouble()
            a[i][2] = 1.0; a[i + 4][5] = 1.0
            a[i][6] = (-sx * dx).toDouble()
            a[i][7] = (-sy * dx).toDouble()
            a[i + 4][6] = (-sx * dy).toDouble()
            a[i + 4][7] = (-sy * dy).toDouble()
            b[i] = dx.toDouble()
            b[i + 4] = dy.toDouble()
        }
        luSolve(a, b)
        return doubleArrayOf(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], 1.0)
    }

    /** OpenCV's LUImpl: Gaussian elimination with partial pivoting; the solution replaces [b]. */
    private fun luSolve(a: Array<DoubleArray>, b: DoubleArray) {
        val m = b.size
        for (i in 0 until m) {
            var k = i
            for (j in i + 1 until m) if (Math.abs(a[j][i]) > Math.abs(a[k][i])) k = j
            require(a[k][i] != 0.0) { "singular perspective transform" }
            if (k != i) {
                val t = a[i]; a[i] = a[k]; a[k] = t
                val tb = b[i]; b[i] = b[k]; b[k] = tb
            }
            val d = -1.0 / a[i][i]
            for (j in i + 1 until m) {
                val alpha = a[j][i] * d
                for (c in i + 1 until m) a[j][c] += alpha * a[i][c]
                b[j] += alpha * b[i]
            }
        }
        for (i in m - 1 downTo 0) {
            var s = b[i]
            for (c in i + 1 until m) s -= a[i][c] * b[c]
            b[i] = s / a[i][i]
        }
    }

    /** `cv::invert` of a 3×3 matrix (its closed form for DECOMP_LU). */
    fun invert3(m: DoubleArray): DoubleArray {
        fun s(r: Int, c: Int) = m[r * 3 + c]
        val det = s(0, 0) * (s(1, 1) * s(2, 2) - s(1, 2) * s(2, 1)) -
            s(0, 1) * (s(1, 0) * s(2, 2) - s(1, 2) * s(2, 0)) +
            s(0, 2) * (s(1, 0) * s(2, 1) - s(1, 1) * s(2, 0))
        require(det != 0.0) { "singular matrix" }
        val d = 1.0 / det
        return doubleArrayOf(
            (s(1, 1) * s(2, 2) - s(1, 2) * s(2, 1)) * d,
            (s(0, 2) * s(2, 1) - s(0, 1) * s(2, 2)) * d,
            (s(0, 1) * s(1, 2) - s(0, 2) * s(1, 1)) * d,
            (s(1, 2) * s(2, 0) - s(1, 0) * s(2, 2)) * d,
            (s(0, 0) * s(2, 2) - s(0, 2) * s(2, 0)) * d,
            (s(0, 2) * s(1, 0) - s(0, 0) * s(1, 2)) * d,
            (s(1, 0) * s(2, 1) - s(1, 1) * s(2, 0)) * d,
            (s(0, 1) * s(2, 0) - s(0, 0) * s(2, 1)) * d,
            (s(0, 0) * s(1, 1) - s(0, 1) * s(1, 0)) * d,
        )
    }

    private const val INTER_BITS = 5
    private const val INTER_TAB_SIZE = 1 shl INTER_BITS
    private const val REMAP_BITS = 15
    private const val REMAP_SCALE = 1 shl REMAP_BITS

    /** The 32×32 table of fixed-point bilinear weights (initInterTab2D for INTER_LINEAR). */
    private val bilinearTab: Array<IntArray> by lazy {
        Array(INTER_TAB_SIZE * INTER_TAB_SIZE) { idx ->
            val i = idx / INTER_TAB_SIZE
            val j = idx % INTER_TAB_SIZE
            val fy = 1f * i / INTER_TAB_SIZE
            val fx = 1f * j / INTER_TAB_SIZE
            val vy = floatArrayOf(1f - fy, fy)
            val vx = floatArrayOf(1f - fx, fx)
            val t = IntArray(4)
            var sum = 0
            for (k1 in 0 until 2) for (k2 in 0 until 2) {
                val v = roundHalfEven(vy[k1] * vx[k2] * REMAP_SCALE)
                t[k1 * 2 + k2] = v
                sum += v
            }
            if (sum != REMAP_SCALE) {
                val diff = sum - REMAP_SCALE
                var mk = 0; var mK = 0
                for (k in 1 until 4) {
                    if (t[k] < t[mk]) mk = k else if (t[k] > t[mK]) mK = k
                }
                if (diff < 0) t[mK] -= diff else t[mk] -= diff
            }
            t
        }
    }

    /**
     * `cv2.warpPerspective(src, M, (w, h))` with INTER_LINEAR and a black constant border, [m]
     * mapping source to destination (it is inverted here, as OpenCV does).
     */
    fun warpPerspective(src: GrayImage, m: DoubleArray, w: Int, h: Int): GrayImage {
        val inv = invert3(m)
        val out = ByteArray(w * h)
        val sw = src.width
        val sh = src.height
        // OpenCV maps coordinates block by block: the source point is computed at each block's
        // left edge and stepped across it, which rounds differently from computing it afresh.
        val bw0 = minOf(1024 / minOf(16, h), w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val xb = x / bw0 * bw0
                val x1 = x - xb
                val bx0 = inv[0] * xb + inv[1] * y + inv[2]
                val by0 = inv[3] * xb + inv[4] * y + inv[5]
                val bw0w = inv[6] * xb + inv[7] * y + inv[8]
                var ww = bw0w + inv[6] * x1
                ww = if (ww != 0.0) INTER_TAB_SIZE / ww else 0.0
                val fx = ((bx0 + inv[0] * x1) * ww).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                val fy = ((by0 + inv[3] * x1) * ww).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                val ix = Math.rint(fx).toInt()
                val iy = Math.rint(fy).toInt()
                val sx = (ix shr INTER_BITS).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val sy = (iy shr INTER_BITS).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                val wt = bilinearTab[(iy and (INTER_TAB_SIZE - 1)) * INTER_TAB_SIZE + (ix and (INTER_TAB_SIZE - 1))]
                val v: Int
                if (sx >= 0 && sy >= 0 && sx < sw - 1 && sy < sh - 1) {
                    val p = sy * sw + sx
                    v = (src.data[p].toInt() and 0xFF) * wt[0] + (src.data[p + 1].toInt() and 0xFF) * wt[1] +
                        (src.data[p + sw].toInt() and 0xFF) * wt[2] + (src.data[p + sw + 1].toInt() and 0xFF) * wt[3]
                } else if (sx >= sw || sx + 1 < 0 || sy >= sh || sy + 1 < 0) {
                    v = 0
                } else {
                    fun px(xx: Int, yy: Int) =
                        if (xx in 0 until sw && yy in 0 until sh) src.data[yy * sw + xx].toInt() and 0xFF else 0
                    v = px(sx, sy) * wt[0] + px(sx + 1, sy) * wt[1] + px(sx, sy + 1) * wt[2] + px(sx + 1, sy + 1) * wt[3]
                }
                out[y * w + x] = ((v + (1 shl (REMAP_BITS - 1))) shr REMAP_BITS).coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(w, h, out)
    }
}
