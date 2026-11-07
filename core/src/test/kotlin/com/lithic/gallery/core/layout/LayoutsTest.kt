package com.lithic.gallery.core.layout

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

class LayoutsTest {
    private val photos = FloatArray(500) { i ->
        val r = Random(i)
        when (r.nextInt(6)) {
            0 -> 0.5625f          // phone portrait
            1, 2 -> 4f / 3        // landscape
            3 -> 0.75f            // portrait
            4 -> 1f               // square
            else -> 0.46f + r.nextFloat() * 3 // screenshots, panoramas, anything
        }
    }

    @Test
    fun justifiedRowsFillTheWidthKeepOrderAndNeverCrop() {
        val width = 1080
        val spacing = 4
        val rows = Layouts.justified(photos, width, 300, spacing)
        assertThat(rows.first().start).isEqualTo(0)
        assertThat(rows.last().end).isEqualTo(photos.size)
        for ((k, row) in rows.withIndex()) {
            if (k > 0) assertThat(row.start).isEqualTo(rows[k - 1].end)
            for (i in 0 until row.count) {
                // Each cell has its image's aspect ratio (to within rounding): nothing cropped,
                // nothing letterboxed.
                val aspect = row.width[i].toDouble() / row.height
                assertWithMessage("row $k item $i aspect").that(aspect)
                    .isWithin(2.0 / row.height + 0.01).of(photos[row.start + i].toDouble())
                if (i > 0) assertThat(row.x[i]).isEqualTo(row.x[i - 1] + row.width[i - 1] + spacing)
            }
            if (k < rows.size - 1) {
                val right = row.x[row.count - 1] + row.width[row.count - 1]
                assertWithMessage("row $k fills the width").that(right).isEqualTo(width)
            }
        }
        // The row heights stay near the target.
        val heights = rows.dropLast(1).map { it.height }
        assertThat(heights.average()).isWithin(60.0).of(300.0)
    }

    @Test
    fun aLonePortraitIsCappedAndCentred() {
        val rows = Layouts.justified(floatArrayOf(0.5f), 1080, 300, 4)
        assertThat(rows).hasSize(1)
        // Last (only) row: kept at the target height, left-aligned.
        assertThat(rows[0].height).isEqualTo(300)
        assertThat(rows[0].x[0]).isEqualTo(0)
        assertThat(rows[0].width[0]).isEqualTo(150)
    }

    @Test
    fun gridHasNSquareCellsPerRow() {
        val rows = Layouts.grid(10, 1000, 4, 10)
        assertThat(rows.map { it.count }).containsExactly(4, 4, 2).inOrder()
        val r = rows[0]
        assertThat(r.x.toList()).containsExactly(0, 253, 505, 758).inOrder()
        assertThat(r.x[3] + r.width[3]).isEqualTo(1000)
        assertThat(r.height).isEqualTo(243)
    }
}
