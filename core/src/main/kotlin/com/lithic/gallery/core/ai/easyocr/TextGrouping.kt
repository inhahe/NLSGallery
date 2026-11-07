package com.lithic.gallery.core.ai.easyocr

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * EasyOCR's `utils.group_text_box` with readtext's defaults, then its `min_size` filter: detected
 * boxes become horizontal line boxes (merged words) and free (rotated) boxes.
 */
object TextGrouping {
    private const val SLOPE_THS = 0.1
    private const val YCENTER_THS = 0.5
    private const val HEIGHT_THS = 0.5
    private const val WIDTH_THS = 0.5
    private const val ADD_MARGIN = 0.1
    private const val MIN_SIZE = 20

    class Groups(
        /** [x_min, x_max, y_min, y_max] per line, top to bottom. */
        val horizontal: List<IntArray>,
        /** Four corners (x, y)×4 per rotated box, in detection order. */
        val free: List<DoubleArray>,
    )

    /** @param polys detected boxes, eight integer coordinates each (tl, tr, br, bl). */
    fun group(polys: List<IntArray>): Groups {
        class H(val xMin: Int, val xMax: Int, val yMin: Int, val yMax: Int) {
            val yCenter = 0.5 * (yMin + yMax)
            val height = yMax - yMin
        }
        val horizontal = ArrayList<H>()
        val free = ArrayList<DoubleArray>()
        for (p in polys) {
            val slopeUp = (p[3] - p[1]).toDouble() / max(10, p[2] - p[0])
            val slopeDown = (p[5] - p[7]).toDouble() / max(10, p[4] - p[6])
            if (max(abs(slopeUp), abs(slopeDown)) < SLOPE_THS) {
                horizontal.add(H(
                    minOf(p[0], p[2], p[4], p[6]), maxOf(p[0], p[2], p[4], p[6]),
                    minOf(p[1], p[3], p[5], p[7]), maxOf(p[1], p[3], p[5], p[7]),
                ))
            } else {
                val height = hypot(p[6] - p[0], p[7] - p[1])
                val width = hypot(p[2] - p[0], p[3] - p[1])
                val margin = (1.44 * ADD_MARGIN * min(width, height)).toInt()
                val theta13 = abs(atan((p[1] - p[5]).toDouble() / max(10, p[0] - p[4])))
                val theta24 = abs(atan((p[3] - p[7]).toDouble() / max(10, p[2] - p[6])))
                free.add(doubleArrayOf(
                    p[0] - cos(theta13) * margin, p[1] - sin(theta13) * margin,
                    p[2] + cos(theta24) * margin, p[3] - sin(theta24) * margin,
                    p[4] + cos(theta13) * margin, p[5] + sin(theta13) * margin,
                    p[6] - cos(theta24) * margin, p[7] + sin(theta24) * margin,
                ))
            }
        }
        val sorted = horizontal.sortedBy { it.yCenter } // stable, like Python's sorted

        // Lines: boxes whose centres sit within half the line's mean height of its mean centre.
        val lines = ArrayList<List<H>>()
        var line = ArrayList<H>()
        var heights = ArrayList<Int>()
        var centers = ArrayList<Double>()
        for (b in sorted) {
            if (line.isEmpty()) {
                heights = arrayListOf(b.height); centers = arrayListOf(b.yCenter); line.add(b)
            } else if (abs(centers.average() - b.yCenter) < YCENTER_THS * heights.average()) {
                heights.add(b.height); centers.add(b.yCenter); line.add(b)
            } else {
                heights = arrayListOf(b.height); centers = arrayListOf(b.yCenter)
                lines.add(line)
                line = arrayListOf(b)
            }
        }
        lines.add(line)

        val merged = ArrayList<IntArray>()
        for (boxes in lines) {
            if (boxes.isEmpty()) continue
            if (boxes.size == 1) {
                val b = boxes[0]
                val margin = (ADD_MARGIN * min(b.xMax - b.xMin, b.height)).toInt()
                merged.add(intArrayOf(b.xMin - margin, b.xMax + margin, b.yMin - margin, b.yMax + margin))
                continue
            }
            // Within a line, neighbours of similar height and close enough join one box.
            val byX = boxes.sortedBy { it.xMin }
            val groups = ArrayList<List<H>>()
            var group = ArrayList<H>()
            var gHeights = ArrayList<Int>()
            var xMax = 0
            for (b in byX) {
                if (group.isEmpty()) {
                    gHeights = arrayListOf(b.height); xMax = b.xMax; group.add(b)
                } else if (abs(gHeights.average() - b.height) < HEIGHT_THS * gHeights.average() &&
                    (b.xMin - xMax) < WIDTH_THS * (b.yMax - b.yMin)
                ) {
                    gHeights.add(b.height); xMax = b.xMax; group.add(b)
                } else {
                    gHeights = arrayListOf(b.height); xMax = b.xMax
                    groups.add(group)
                    group = arrayListOf(b)
                }
            }
            if (group.isNotEmpty()) groups.add(group)
            for (g in groups) {
                val x0 = g.minOf { it.xMin }; val x1 = g.maxOf { it.xMax }
                val y0 = g.minOf { it.yMin }; val y1 = g.maxOf { it.yMax }
                val margin = (ADD_MARGIN * min(x1 - x0, y1 - y0)).toInt()
                merged.add(intArrayOf(x0 - margin, x1 + margin, y0 - margin, y1 + margin))
            }
        }
        val keepH = merged.filter { max(it[1] - it[0], it[3] - it[2]) > MIN_SIZE }
        val keepF = free.filter { b ->
            val xs = doubleArrayOf(b[0], b[2], b[4], b[6])
            val ys = doubleArrayOf(b[1], b[3], b[5], b[7])
            max(xs.max() - xs.min(), ys.max() - ys.min()) > MIN_SIZE
        }
        return Groups(keepH, keepF)
    }

    private fun hypot(dx: Int, dy: Int): Double = sqrt(dx.toDouble() * dx + dy.toDouble() * dy)
}
