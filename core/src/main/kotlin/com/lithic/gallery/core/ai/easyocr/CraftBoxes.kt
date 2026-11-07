package com.lithic.gallery.core.ai.easyocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CRAFT's score maps to text boxes: EasyOCR's `craft_utils.getDetBoxes_core` with poly=False.
 */
object CraftBoxes {
    const val TEXT_THRESHOLD = 0.7f
    const val LINK_THRESHOLD = 0.4f
    const val LOW_TEXT = 0.4f

    /**
     * Boxes in score-map coordinates, each four corners (x, y)×4 starting with the one nearest
     * the top-left and going clockwise, in component order.
     *
     * @param text the text (character region) score per map pixel, [w]×[h].
     * @param link the affinity (link between characters) score per map pixel.
     */
    fun detect(text: FloatArray, link: FloatArray, w: Int, h: Int): List<FloatArray> {
        val n = w * h
        val textOn = BooleanArray(n) { text[it] > LOW_TEXT }
        val linkOn = BooleanArray(n) { link[it] > LINK_THRESHOLD }
        val cc = Geometry.connectedComponents(BooleanArray(n) { textOn[it] || linkOn[it] }, w, h)
        val maxText = FloatArray(cc.count) { Float.NEGATIVE_INFINITY }
        for (i in 0 until n) {
            val l = cc.labels[i]
            if (l != 0 && text[i] > maxText[l]) maxText[l] = text[i]
        }
        val boxes = ArrayList<FloatArray>()
        for (k in 1 until cc.count) {
            val size = cc.area[k]
            if (size < 10) continue
            if (maxText[k] < TEXT_THRESHOLD) continue
            val x = cc.left[k]; val y = cc.top[k]; val bw = cc.width[k]; val bh = cc.height[k]
            // int32 product, then true division, as numpy does with OpenCV's int32 stats
            val niter = (sqrt((size * min(bw, bh)).toDouble() / (bw * bh)) * 2).toInt()
            val sx = max(x - niter, 0)
            val sy = max(y - niter, 0)
            val ex = min(x + bw + niter + 1, w)
            val ey = min(y + bh + niter + 1, h)
            val rw = ex - sx
            val rh = ey - sy
            // The component, without pixels that are link but not text, in its region.
            val seg = BooleanArray(rw * rh)
            for (yy in y until y + bh) for (xx in x until x + bw) {
                val i = yy * w + xx
                if (cc.labels[i] == k && !(linkOn[i] && !textOn[i])) seg[(yy - sy) * rw + (xx - sx)] = true
            }
            val dil = dilate(seg, rw, rh, 1 + niter)
            var count = 0
            for (v in dil) if (v) count++
            val xs = IntArray(count)
            val ys = IntArray(count)
            var c = 0
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = Int.MAX_VALUE; var b = Int.MIN_VALUE
            for (yy in 0 until rh) for (xx in 0 until rw) {
                if (!dil[yy * rw + xx]) continue
                val px = xx + sx; val py = yy + sy
                xs[c] = px; ys[c] = py; c++
                if (px < l) l = px
                if (px > r) r = px
                if (py < t) t = py
                if (py > b) b = py
            }
            var box = Geometry.boxPoints(Geometry.minAreaRect(xs, ys))
            // Nearly square boxes ("diamonds") become the points' upright bounding box.
            val side1 = norm(box[0] - box[2], box[1] - box[3])
            val side2 = norm(box[2] - box[4], box[3] - box[5])
            val ratio = max(side1, side2) / (min(side1, side2) + 1e-5f)
            if (Math.abs(1f - ratio) <= 0.1f) {
                box = floatArrayOf(
                    l.toFloat(), t.toFloat(), r.toFloat(), t.toFloat(),
                    r.toFloat(), b.toFloat(), l.toFloat(), b.toFloat(),
                )
            }
            // Start from the corner with the smallest x + y.
            var start = 0
            for (i in 1 until 4) if (box[2 * i] + box[2 * i + 1] < box[2 * start] + box[2 * start + 1]) start = i
            boxes.add(FloatArray(8) { box[(2 * start + it) % 8] })
        }
        return boxes
    }

    /** np.linalg.norm of a float32 2-vector: float32 throughout. */
    private fun norm(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

    /**
     * cv2.dilate with a [k]×[k] rectangle anchored at its centre (k/2), the region taken on its
     * own: pixels outside it count as background.
     */
    private fun dilate(src: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        if (k <= 1) return src
        val a = k / 2
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var on = false
            for (d in 0 until k) {
                val xx = x + d - a
                if (xx in 0 until w && src[y * w + xx]) { on = true; break }
            }
            tmp[y * w + x] = on
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var on = false
            for (d in 0 until k) {
                val yy = y + d - a
                if (yy in 0 until h && tmp[yy * w + x]) { on = true; break }
            }
            out[y * w + x] = on
        }
        return out
    }
}
