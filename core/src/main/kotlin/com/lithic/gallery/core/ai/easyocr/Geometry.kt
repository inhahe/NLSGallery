package com.lithic.gallery.core.ai.easyocr

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The OpenCV geometry CRAFT's post-processing (EasyOCR's craft_utils.getDetBoxes_core) relies on,
 * computed the way OpenCV computes it, down to float32 rounding, because the boxes' corners are
 * truncated to integers afterwards.
 */
object Geometry {

    /** Connected components of a binary image (cv2.connectedComponentsWithStats, 4-connected). */
    class Components(
        /** Per pixel, 0 for background or the component's label 1..count. */
        val labels: IntArray,
        /** Number of labels including the background 0. */
        val count: Int,
        val left: IntArray, val top: IntArray, val width: IntArray, val height: IntArray, val area: IntArray,
    )

    /**
     * Labels the nonzero pixels of [mask] ([w]×[h]) into 4-connected components, numbered in the
     * raster order of each component's first pixel, which is how OpenCV numbers them.
     */
    fun connectedComponents(mask: BooleanArray, w: Int, h: Int): Components {
        val labels = IntArray(w * h)
        // 4-connected, at most every other pixel starts a label (a checkerboard).
        val parent = IntArray((w * h + 1) / 2 + 2)
        var next = 1
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }
        fun union(a: Int, b: Int): Int {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return ra
            return if (ra < rb) { parent[rb] = ra; ra } else { parent[ra] = rb; rb }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!mask[i]) continue
            val up = if (y > 0) labels[i - w] else 0
            val lf = if (x > 0) labels[i - 1] else 0
            labels[i] = when {
                up == 0 && lf == 0 -> {
                    parent[next] = next
                    next++
                }
                up != 0 && lf != 0 -> union(up, lf)
                up != 0 -> up
                else -> lf
            }
        }
        // Union-find keeps the smaller label as root, so roots, renumbered in order, follow the
        // raster order of each component's first pixel.
        val final = IntArray(next)
        var count = 1
        for (l in 1 until next) {
            val r = find(l)
            final[l] = if (r == l) count++ else final[r]
        }
        val left = IntArray(count) { Int.MAX_VALUE }
        val top = IntArray(count) { Int.MAX_VALUE }
        val right = IntArray(count) { -1 }
        val bottom = IntArray(count) { -1 }
        val area = IntArray(count)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (labels[i] == 0) continue
            val l = final[labels[i]]
            labels[i] = l
            area[l]++
            if (x < left[l]) left[l] = x
            if (x > right[l]) right[l] = x
            if (y < top[l]) top[l] = y
            if (y > bottom[l]) bottom[l] = y
        }
        val width = IntArray(count) { if (right[it] < 0) 0 else right[it] - left[it] + 1 }
        val height = IntArray(count) { if (bottom[it] < 0) 0 else bottom[it] - top[it] + 1 }
        return Components(labels, count, left, top, width, height, area)
    }

    /**
     * cv2.minAreaRect: centre, size and angle in degrees, all float32 as OpenCV keeps them. The
     * angle is in [-90, 0), which fixes which side is the width (OpenCV 4.13's convention; older
     * versions described the same rectangle differently, and boxPoints rounds each description's
     * corners differently).
     */
    class RotatedRect(val cx: Float, val cy: Float, val width: Float, val height: Float, val angle: Float)

    /**
     * The minimum-area rectangle around integer points ([xs], [ys]), listed in raster order (the
     * order np.where gives them, which OpenCV's hull uses to pick its first vertex).
     */
    fun minAreaRect(xs: IntArray, ys: IntArray): RotatedRect {
        require(xs.isNotEmpty() && xs.size == ys.size) { "minAreaRect needs points" }
        val hull = convexHull(xs, ys)
        val n = hull.size
        val px = FloatArray(n) { xs[hull[it]].toFloat() }
        val py = FloatArray(n) { ys[hull[it]].toFloat() }
        // An upright box and a single point get -90 degrees.
        var angle = -Math.PI / 2
        val cx: Float
        val cy: Float
        var w: Float
        var h: Float
        when {
            n > 2 -> {
                // A corner, then the side vectors (ax, ay) and (bx, by).
                val out = rotatingCalipers(px, py)
                cx = out[0] + (out[2] + out[4]) * 0.5f
                cy = out[1] + (out[3] + out[5]) * 0.5f
                w = sqrt(out[4].toDouble() * out[4] + out[5].toDouble() * out[5]).toFloat()
                h = sqrt(out[2].toDouble() * out[2] + out[3].toDouble() * out[3]).toFloat()
                if (out[2] == 0f && out[3] > 0f) { val t = w; w = h; h = t }
                else angle = -atan2(out[2].toDouble(), out[3].toDouble())
            }
            n == 2 -> {
                cx = (px[0] + px[1]) * 0.5f
                cy = (py[0] + py[1]) * 0.5f
                val dx = (px[0] - px[1]).toDouble()
                val dy = (py[0] - py[1]).toDouble()
                w = 0f
                h = sqrt(dx * dx + dy * dy).toFloat()
                when {
                    dx == 0.0 -> { w = h; h = 0f }
                    dy < 0 -> { angle = atan2(dy, dx); w = h; h = 0f }
                    dy > 0 -> angle = -atan2(dx, dy)
                }
            }
            else -> { cx = px[0]; cy = py[0]; w = 0f; h = 0f }
        }
        return RotatedRect(cx, cy, w, h, (angle * 180 / Math.PI).toFloat())
    }

    /** cv2.boxPoints: the rectangle's corners as (x0, y0, …, x3, y3), float32 arithmetic. */
    fun boxPoints(r: RotatedRect): FloatArray {
        val angle = r.angle * Math.PI / 180.0
        val b = cos(angle).toFloat() * 0.5f
        val a = sin(angle).toFloat() * 0.5f
        val p = FloatArray(8)
        p[0] = r.cx - a * r.height - b * r.width
        p[1] = r.cy + b * r.height - a * r.width
        p[2] = r.cx + a * r.height - b * r.width
        p[3] = r.cy - b * r.height - a * r.width
        p[4] = 2 * r.cx - p[0]
        p[5] = 2 * r.cy - p[1]
        p[6] = 2 * r.cx - p[2]
        p[7] = 2 * r.cy - p[3]
        return p
    }

    /**
     * The convex hull of the points as indices into them, counter-clockwise with y up, starting
     * where cv::convexHull(clockwise=false) starts: at the largest x (then largest y), cyclically
     * shifted when that makes the indices run in order, as OpenCV does. Collinear points are dropped.
     */
    internal fun convexHull(xs: IntArray, ys: IntArray): IntArray {
        val total = xs.size
        val order = (0 until total).sortedWith(compareBy<Int>({ xs[it] }, { ys[it] }, { it })).toIntArray()
        if (xs[order[0]] == xs[order[total - 1]] && ys[order[0]] == ys[order[total - 1]]) {
            return intArrayOf(order[0])
        }
        fun cross(o: Int, a: Int, b: Int): Long =
            (xs[a] - xs[o]).toLong() * (ys[b] - ys[o]) - (ys[a] - ys[o]).toLong() * (xs[b] - xs[o])
        // Andrew's monotone chain: lower then upper, counter-clockwise (y up), no collinear points.
        val hull = IntArray(2 * total)
        var k = 0
        for (p in order) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        val lowerEnd = k + 1
        for (i in total - 2 downTo 0) {
            val p = order[i]
            while (k >= lowerEnd && cross(hull[k - 2], hull[k - 1], p) <= 0) k--
            hull[k++] = p
        }
        var ccw = hull.copyOf(k - 1) // the last point repeats the first
        // Start at the largest x, then largest y.
        var start = 0
        for (i in ccw.indices) {
            val a = ccw[i]; val b = ccw[start]
            if (xs[a] > xs[b] || (xs[a] == xs[b] && ys[a] > ys[b])) start = i
        }
        ccw = IntArray(ccw.size) { ccw[(start + it) % ccw.size] }
        return orderByIndex(ccw)
    }

    /**
     * cv::convexHull's last step: "try to make the convex hull indices form an ascending or
     * descending sequence by the cyclic shift of the output sequence".
     */
    private fun orderByIndex(hull: IntArray): IntArray {
        val nout = hull.size
        if (nout < 3) return hull
        var minIdx = 0
        var maxIdx = 0
        var lt = 0
        for (i in 1 until nout) {
            val idx = hull[i]
            if (hull[i - 1] < idx) lt++
            if (lt > 1 && lt <= i - 2) break
            if (idx < hull[minIdx]) minIdx = i
            if (idx > hull[maxIdx]) maxIdx = i
        }
        val mmdist = Math.abs(maxIdx - minIdx)
        if ((mmdist == 1 || mmdist == nout - 1) && (lt <= 1 || lt >= nout - 2)) {
            val ascending = (maxIdx + 1) % nout == minIdx
            val i0 = if (ascending) minIdx else maxIdx
            var j = i0
            if (i0 > 0) {
                val shifted = IntArray(nout)
                var i = 0
                while (i < nout) {
                    val cur = hull[j]
                    shifted[i] = cur
                    val nj = if (j + 1 < nout) j + 1 else 0
                    val nxt = hull[nj]
                    if (i < nout - 1 && (ascending != (cur < nxt))) break
                    j = nj
                    i++
                }
                if (i == nout) return shifted
            }
        }
        return hull
    }

    /**
     * OpenCV's rotatingCalipers in CALIPERS_MINAREARECT mode over the counter-clockwise hull
     * points ([px], [py]): a corner and the two side vectors of the smallest rectangle,
     * (x, y, ax, ay, bx, by).
     */
    private fun rotatingCalipers(px: FloatArray, py: FloatArray): FloatArray {
        val n = px.size
        var minArea = Float.MAX_VALUE
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        val invLen = FloatArray(n)
        var left = 0; var bottom = 0; var right = 0; var top = 0
        var leftX = px[0]; var rightX = px[0]; var topY = py[0]; var bottomY = py[0]
        var p0x = px[0]; var p0y = py[0]
        for (i in 0 until n) {
            if (p0x < leftX) { leftX = p0x; left = i }
            if (p0x > rightX) { rightX = p0x; right = i }
            if (p0y > topY) { topY = p0y; top = i }
            if (p0y < bottomY) { bottomY = p0y; bottom = i }
            val ni = if (i + 1 < n) i + 1 else 0
            val dx = (px[ni] - p0x).toDouble()
            val dy = (py[ni] - p0y).toDouble()
            vx[i] = dx.toFloat()
            vy[i] = dy.toFloat()
            invLen[i] = (1.0 / sqrt(dx * dx + dy * dy)).toFloat()
            p0x = px[ni]; p0y = py[ni]
        }
        // minAreaRect passes the hull's orientation (counter-clockwise) rather than measuring it.
        var baseA = 1f
        var baseB = 0f
        val seq = intArrayOf(bottom, right, top, left)
        var bufLeft = 0; var bufA = 0f; var bufWidth = 0f; var bufB = 0f; var bufHeight = 0f; var bufBottom = 0
        val rotX = FloatArray(4)
        val rotY = FloatArray(4)
        for (k in 0 until n) {
            // Each caliper's next edge, turned to the first caliper's direction; the one turning
            // least (furthest clockwise) leads.
            rotX[0] = vx[seq[0]]; rotY[0] = vy[seq[0]]
            rotX[1] = vy[seq[1]]; rotY[1] = -vx[seq[1]]
            rotX[2] = -vx[seq[2]]; rotY[2] = -vy[seq[2]]
            rotX[3] = -vy[seq[3]]; rotY[3] = vx[seq[3]]
            var main = 0
            for (i in 1 until 4) {
                // firstVecIsRight(rot[i], rot[main]): rot[i] turned 90 degrees clockwise, dotted.
                if (rotY[i] * rotX[main] + -rotX[i] * rotY[main] < 0f) main = i
            }
            val pi = seq[main]
            val leadX = vx[pi] * invLen[pi]
            val leadY = vy[pi] * invLen[pi]
            when (main) {
                0 -> { baseA = leadX; baseB = leadY }
                1 -> { baseA = leadY; baseB = -leadX }
                2 -> { baseA = -leadX; baseB = -leadY }
                else -> { baseA = -leadY; baseB = leadX }
            }
            seq[main] += 1
            if (seq[main] == n) seq[main] = 0
            var dx = px[seq[1]] - px[seq[3]]
            var dy = py[seq[1]] - py[seq[3]]
            val width = dx * baseA + dy * baseB
            dx = px[seq[2]] - px[seq[0]]
            dy = py[seq[2]] - py[seq[0]]
            val height = -dx * baseB + dy * baseA
            val area = width * height
            if (area <= minArea) {
                minArea = area
                bufLeft = seq[3]; bufA = baseA; bufWidth = width; bufB = baseB; bufHeight = height
                bufBottom = seq[0]
            }
        }
        val a1 = bufA; val b1 = bufB
        val a2 = -bufB; val b2 = bufA
        val c1 = a1 * px[bufLeft] + py[bufLeft] * b1
        val c2 = a2 * px[bufBottom] + py[bufBottom] * b2
        val idet = 1f / (a1 * b2 - a2 * b1)
        val x = (c1 * b2 - c2 * b1) * idet
        val y = (a1 * c2 - a2 * c1) * idet
        return floatArrayOf(x, y, a1 * bufWidth, b1 * bufWidth, a2 * bufHeight, b2 * bufHeight)
    }
}
