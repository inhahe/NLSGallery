package com.lithic.gallery.core.layout

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One row of thumbnails: items [start, end) of the list, each placed at [x] with width [width]
 * (pixels), all [height] tall. Each item's image is fitted inside its cell, never cropped.
 */
class Row(val start: Int, val end: Int, val height: Int, val x: IntArray, val width: IntArray) {
    val count get() = end - start
}

/**
 * Lays thumbnails out in rows across [containerWidth] pixels, in list order, without cropping.
 */
object Layouts {

    /**
     * "Justified" rows: every row but the last exactly fills the width, its items scaled to one
     * height so their own aspect ratios fit edge to edge, and no image is cropped or letterboxed.
     * Where the rows break is chosen by dynamic programming over the whole list (like line breaking
     * in typesetting) to keep row heights as close to [targetHeight] as the images allow.
     *
     * @param aspects each item's displayed width / height.
     * @param spacing gap between neighbouring items, in pixels.
     * @param maxHeight a row is never taller; one that would need to be (a lone portrait image,
     *   say) is drawn at this height, centred, with space beside it.
     */
    fun justified(
        aspects: FloatArray, containerWidth: Int, targetHeight: Int, spacing: Int,
        maxHeight: Int = (targetHeight * 2.5f).roundToInt(),
    ): List<Row> {
        val n = aspects.size
        if (n == 0 || containerWidth <= 0) return emptyList()
        val a = FloatArray(n) { aspects[it].coerceIn(MIN_ASPECT, MAX_ASPECT) }
        val w = containerWidth.toDouble()
        val target = targetHeight.toDouble()
        // A row can hold no more items than would fit at a third of the target height.
        val minHeight = target / 3
        val cost = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val from = IntArray(n + 1)
        cost[0] = 0.0
        for (j in 1..n) {
            var sum = 0.0
            var i = j - 1
            while (i >= 0) {
                sum += a[i]
                val count = j - i
                val gaps = spacing * (count - 1)
                val h = (w - gaps) / sum
                if (h < minHeight && count > 1) break
                val last = j == n
                val c = rowCost(h, target, last)
                if (cost[i] + c < cost[j]) {
                    cost[j] = cost[i] + c
                    from[j] = i
                }
                i--
            }
        }
        val breaks = ArrayList<Int>()
        var j = n
        while (j > 0) { breaks.add(j); j = from[j] }
        breaks.reverse()
        val rows = ArrayList<Row>(breaks.size)
        var start = 0
        for (end in breaks) {
            rows.add(placeRow(a, start, end, w, target, spacing, maxHeight, last = end == n))
            start = end
        }
        return rows
    }

    /** Badness of a row [h] pixels tall: squared relative distance from the target height. A
     * last row may stay at the target height without filling the width, so only being too
     * short counts against it. */
    private fun rowCost(h: Double, target: Double, last: Boolean): Double {
        if (last && h >= target) return 0.0
        val d = (h - target) / target
        return d * d
    }

    private fun placeRow(
        a: FloatArray, start: Int, end: Int, w: Double, target: Double, spacing: Int, maxHeight: Int, last: Boolean,
    ): Row {
        val count = end - start
        var sum = 0.0
        for (k in start until end) sum += a[k]
        val gaps = spacing * (count - 1)
        val fill = (w - gaps) / sum
        // The last row keeps the target height unless that would overflow; any row is capped.
        val h = min(if (last) min(fill, target) else fill, maxHeight.toDouble())
        val height = max(1, h.roundToInt())
        val xs = IntArray(count)
        val widths = IntArray(count)
        val filled = !last && h == fill
        // Item edges at cumulative exact positions, rounded: widths then add up to the row exactly.
        val rowWidth = if (filled) w else sum * h + gaps
        var acc = if (filled) 0.0 else (w - rowWidth) / 2 * (if (last) 0 else 1)
        for (k in 0 until count) {
            val left = acc
            val right = acc + a[start + k] * h
            xs[k] = left.roundToInt()
            widths[k] = max(1, right.roundToInt() - xs[k])
            acc = right + spacing
        }
        if (filled) widths[count - 1] = w.toInt() - xs[count - 1]
        return Row(start, end, height, xs, widths)
    }

    /**
     * [columns] square cells per row, each image fitted inside its cell.
     */
    fun grid(itemCount: Int, containerWidth: Int, columns: Int, spacing: Int): List<Row> {
        if (itemCount == 0 || containerWidth <= 0) return emptyList()
        val cols = max(1, columns)
        val cell = (containerWidth - spacing * (cols - 1)).toDouble() / cols
        val rows = ArrayList<Row>((itemCount + cols - 1) / cols)
        var start = 0
        while (start < itemCount) {
            val end = min(itemCount, start + cols)
            val xs = IntArray(end - start)
            val widths = IntArray(end - start)
            for (k in xs.indices) {
                val left = k * (cell + spacing)
                xs[k] = left.roundToInt()
                widths[k] = (left + cell).roundToInt() - xs[k]
            }
            rows.add(Row(start, end, cell.roundToInt(), xs, widths))
            start = end
        }
        return rows
    }

    /** Aspect ratios outside this range are treated as at its ends (and the image letterboxed). */
    const val MIN_ASPECT = 0.05f
    const val MAX_ASPECT = 20f
}
