package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import com.lithic.gallery.core.ai.ModelTestSupport.rawImage
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Running CRAFT in overlapping tiles (to bound its memory on a phone) against running it whole,
 * as EasyOCR does. Each test image is given a budget of 60% of its area, so it is cut once or
 * twice, as a large photo is on a phone; the report says how far the score maps move with each
 * margin and whether the text read changes.
 */
class CraftTilingTest {
    private val ref = TestFiles.reference("ocr.json").jsonObject

    @Test
    fun tilePlansFitTheBudgetAndCoverTheImage() {
        val margin = EasyOcr.DEFAULT_TILE_MARGIN
        // Canvases phones produce (4:3 and 20:9 photos, screenshots) under both budgets.
        for ((w, h, budget) in listOf(Triple(2560, 1920, 2_700_000L), Triple(2560, 1920, 1_200_000L),
            Triple(1088, 2368, 1_200_000L), Triple(1920, 1440, 1_200_000L), Triple(2560, 1152, 1_200_000L))) {
            val (xs, ys) = EasyOcr.tilePlan(w, h, budget, margin)
            for (spans in listOf(xs to w, ys to h)) {
                assertThat(spans.first.first().first).isEqualTo(0)
                assertThat(spans.first.last().second).isEqualTo(spans.second)
                spans.first.zipWithNext().forEach { (a, b) -> assertThat(b.first).isEqualTo(a.second) }
                spans.first.forEach { assertThat(it.first % 32).isEqualTo(0) }
            }
            val ext = { s: List<Pair<Int, Int>>, len: Int ->
                s.map { (a, b) -> minOf(len, b + margin) - maxOf(0, a - margin) }
            }
            val largest = ext(xs, w).max().toLong() * ext(ys, h).max()
            val total = ext(xs, w).sum().toLong() * ext(ys, h).sum()
            println("${w}x$h budget $budget: ${xs.size}x${ys.size} tiles, largest $largest px, " +
                "computing ${"%.2f".format(total.toDouble() / (w.toLong() * h))}× the pixels")
            assertWithMessage("${w}x$h largest tile").that(largest).isAtMost(budget)
            // The accelerator's tiles: one size, each holding what the plain tile holds.
            for ((cores, len) in listOf(xs to w, ys to h)) {
                val plain = EasyOcr.windows(cores, len, margin, uniform = false)
                val uniform = EasyOcr.windows(cores, len, margin, uniform = true)
                assertWithMessage("${w}x$h one size").that(uniform.map { it.second - it.first }.toSet()).hasSize(1)
                for ((p, u) in plain.zip(uniform)) {
                    assertThat(u.first).isAtMost(p.first)
                    assertThat(u.second).isAtLeast(p.second)
                    assertThat(u.first).isAtLeast(0)
                    assertThat(u.second).isAtMost(len)
                    assertThat(u.first % 32).isEqualTo(0)
                }
            }
            val uniformTile = EasyOcr.windows(xs, w, margin, true).first().let { it.second - it.first }.toLong() *
                EasyOcr.windows(ys, h, margin, true).first().let { it.second - it.first }
            assertWithMessage("${w}x$h uniform tile").that(uniformTile).isEqualTo(largest)
            // Planned for the accelerator, the work counted is what its tiles compute.
            val gpuWork = { (gx, gy): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> ->
                val xw = EasyOcr.windows(gx, w, margin, true)
                val yw = EasyOcr.windows(gy, h, margin, true)
                xw.size.toLong() * (xw[0].second - xw[0].first) * yw.size * (yw[0].second - yw[0].first)
            }
            val forGpu = EasyOcr.tilePlan(w, h, budget, margin, uniform = true)
            println("    for an accelerator: ${forGpu.first.size}x${forGpu.second.size} tiles, " +
                "computing ${"%.2f".format(gpuWork(forGpu).toDouble() / (w.toLong() * h))}× the pixels")
            assertThat(gpuWork(forGpu)).isAtMost(gpuWork(xs to ys))
        }
        // The case it was for: 3 strips of 1344×1920 (7.7 MP), where 2×2 tiles compute 7.5 MP.
        val strips = EasyOcr.tilePlan(2560, 1920, 2_700_000L, margin, uniform = true)
        assertThat(strips.first.size to strips.second.size).isEqualTo(2 to 2)
    }

    /**
     * The accelerator's way (tiles all one size, each through a fixed-shape session), with the
     * float32 graph and an accelerator that adds nothing: the same score maps as one run of the
     * whole canvas, so the float16 graph is the only thing a GPU changes.
     */
    @Test
    fun acceleratorTilesComputeTheSameScores() {
        ModelTestSupport.store.weightsDir("craft")
        EasyOcr(ModelTestSupport.onnx).use { ocr ->
            ocr.accelerator = Accelerator { }
            ocr.acceleratedGraph = "craft.onnx"
            ocr.acceleratedFrom = 0
            ocr.acceleratedCachePixels = Long.MAX_VALUE
            var maxDiff = 0f
            for (name in ref.keys.sorted()) {
                val img = rawImage(name)
                ocr.acceleratedMaxTilePixels = Long.MAX_VALUE
                val whole = ocr.scoreMaps(img)
                ocr.acceleratedMaxTilePixels = (whole.width * 2L) * (whole.height * 2L) * 6 / 10
                val compiled = ocr.acceleratedCompiles
                val b = ocr.scoreMaps(img)
                // One session serves the canvas: its tiles are all one shape.
                assertWithMessage("$name sessions compiled").that(ocr.acceleratedCompiles - compiled).isAtMost(1)
                for (i in whole.text.indices) {
                    maxDiff = maxOf(maxDiff, Math.abs(whole.text[i] - b.text[i]), Math.abs(whole.link[i] - b.link[i]))
                }
            }
            println("accelerator tiles: max score difference from whole $maxDiff")
            assertThat(maxDiff).isEqualTo(0f)
        }
    }

    @Test
    fun tiledDetectionReadsTheSameText() {
        ModelTestSupport.store.weightsDir("craft")
        EasyOcr(ModelTestSupport.onnx).use { ocr ->
            val names = ref.keys.sorted()
            val whole = names.associateWith { ocr.scoreMaps(rawImage(it)) }
            val report = StringBuilder()
            var chosenWrong = emptyList<String>()
            for (margin in listOf(128, 192, 256)) {
                ocr.tileMargin = margin
                var maxDiff = 0f
                var flips = 0
                val wrong = ArrayList<String>()
                for (name in names) {
                    val img = rawImage(name)
                    val a = whole.getValue(name)
                    ocr.maxTilePixels = (a.width * 2L) * (a.height * 2L) * 6 / 10
                    val b = ocr.scoreMaps(img)
                    for (i in a.text.indices) {
                        maxDiff = maxOf(maxDiff, Math.abs(a.text[i] - b.text[i]), Math.abs(a.link[i] - b.link[i]))
                        if ((a.text[i] > 0.4f) != (b.text[i] > 0.4f) || (a.link[i] > 0.4f) != (b.link[i] > 0.4f)) flips++
                    }
                    val want = ref.getValue(name).jsonObject["text"]!!.jsonPrimitive.content
                    val got = ocr.text(img)
                    if (got != want) wrong.add("$name: '$got' vs '$want'")
                }
                report.append("margin $margin: max score difference $maxDiff, " +
                    "$flips thresholded pixels differ, ${wrong.size} images read differently\n")
                wrong.forEach { report.append("    $it\n") }
                if (margin == EasyOcr.DEFAULT_TILE_MARGIN) chosenWrong = wrong
            }
            println(report)
            assertWithMessage("with the default margin, tiling changed what was read:\n$report")
                .that(chosenWrong).isEmpty()
        }
    }
}
