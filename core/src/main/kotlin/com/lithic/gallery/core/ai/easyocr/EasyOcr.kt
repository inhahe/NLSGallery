package com.lithic.gallery.core.ai.easyocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.toFloatArray
import com.lithic.gallery.core.image.CvOps
import com.lithic.gallery.core.image.RgbImage
import kotlin.math.max

/**
 * EasyOCR's `Reader(['en']).readtext(image)` with its defaults (CRAFT detector, english_g2
 * recognizer, greedy decoding), as thumbsup2's `ai_describe.py` calls it, on ONNX Runtime.
 */
class EasyOcr(private val onnx: Onnx) : AutoCloseable {
    private val craft: OrtSession = onnx.session("craft", "craft.onnx")
    private val recognizerSession: OrtSession = onnx.session("english_g2", "english_g2.onnx")
    private val recognizer = Recognizer(onnx, recognizerSession)

    /**
     * The detector's working size: images whose longer side exceeds it are scaled down first.
     * EasyOCR's default is 2560; smaller is faster and misses more small text.
     */
    var canvasSize = DEFAULT_CANVAS

    /** One piece of text found, with its box's corners (x, y)×4 in the image. */
    data class Text(val box: DoubleArray, val text: String, val confidence: Double)

    /** Everything readable in [img], in EasyOCR's order: lines top to bottom, then rotated text. */
    fun readText(img: RgbImage): List<Text> {
        val groups = detect(img)
        val grey = CvOps.easyOcrGray(img)
        val out = ArrayList<Text>()
        for (box in groups.horizontal) {
            val crop = Crops.horizontal(grey, box) ?: continue
            val r = recognizer.read(crop)
            out.add(Text(crop.box, r.text, r.confidence))
        }
        for (box in groups.free) {
            val crop = Crops.free(grey, box)
            val r = recognizer.read(crop)
            out.add(Text(crop.box, r.text, r.confidence))
        }
        return out
    }

    /** What thumbsup2 stores as an image's OCR text: every reading joined by spaces, stripped. */
    fun text(img: RgbImage): String = readText(img).joinToString(" ") { it.text }.trim()

    /** EasyOCR's `detect`: the text boxes in [img], grouped into lines and rotated boxes. */
    fun detect(img: RgbImage): TextGrouping.Groups {
        val maps = scoreMaps(img)
        // adjustResultCoordinates: the float32 boxes times a float64 (ratio * 2) pair, computed
        // in float64 and stored back as float32, then truncated to int32.
        val scale = 1.0 / maps.ratio * 2
        val polys = CraftBoxes.detect(maps.text, maps.link, maps.width, maps.height)
            .map { b -> IntArray(8) { (b[it].toDouble() * scale).toFloat().toInt() } }
        return TextGrouping.group(polys)
    }

    /**
     * CRAFT's score maps for [img] (half its working size), and the scale it was brought to the
     * canvas by.
     */
    class ScoreMaps(val text: FloatArray, val link: FloatArray, val width: Int, val height: Int, val ratio: Double)

    /** EasyOCR's `resize_aspect_ratio` + normalization + CRAFT, tiled when large (see [maxTilePixels]). */
    fun scoreMaps(img: RgbImage): ScoreMaps {
        val h = img.height
        val w = img.width
        var target = max(h, w).toDouble()
        if (target > canvasSize) target = canvasSize.toDouble()
        val ratio = target / max(h, w)
        val th = (h * ratio).toInt()
        val tw = (w * ratio).toInt()
        val proc = CvOps.resizeLinear(img, tw, th)
        val th32 = (th + 31) / 32 * 32
        val tw32 = (tw + 31) / 32 * 32
        val plane = th32 * tw32
        val input = FloatArray(3 * plane)
        for (c in 0 until 3) {
            val m = MEAN[c]
            val s = STD[c]
            val pad = (0f - m) / s
            for (y in 0 until th32) for (x in 0 until tw32) {
                input[c * plane + y * tw32 + x] =
                    if (y < th && x < tw) (proc.get(x, y, c).toFloat() - m) / s else pad
            }
        }
        val mapW = tw32 / 2
        val mapH = th32 / 2
        val text = FloatArray(mapW * mapH)
        val link = FloatArray(mapW * mapH)
        // CRAFT's activations take ~1.3 KB per input pixel (6 GB at thumbsup2's 2560×1920), so a
        // large canvas is run in overlapping tiles: each output pixel comes from the tile in whose
        // core it lies, at least [tileMargin] input pixels from a tile edge that is not the image's.
        val (xs, ys) = tilePlan(tw32, th32, maxTilePixels, tileMargin)
        for ((cy0, cy1) in ys) for ((cx0, cx1) in xs) {
            val y0 = maxOf(0, cy0 - tileMargin); val y1 = minOf(th32, cy1 + tileMargin)
            val x0 = maxOf(0, cx0 - tileMargin); val x1 = minOf(tw32, cx1 + tileMargin)
            val h = y1 - y0
            val w = x1 - x0
            val tile = if (h == th32 && w == tw32) input else FloatArray(3 * h * w).also { t ->
                for (c in 0 until 3) for (y in 0 until h) {
                    System.arraycopy(input, c * plane + (y0 + y) * tw32 + x0, t, c * h * w + y * w, w)
                }
            }
            val scores = onnx.floats(tile, 1, 3, h.toLong(), w.toLong()).use { t ->
                craft.run(mapOf("image" to t)).use { r -> (r[0] as OnnxTensor).toFloatArray() }
            }
            val outW = w / 2
            for (my in cy0 / 2 until cy1 / 2) for (mx in cx0 / 2 until cx1 / 2) {
                val s = ((my - y0 / 2) * outW + (mx - x0 / 2)) * 2
                text[my * mapW + mx] = scores[s]
                link[my * mapW + mx] = scores[s + 1]
            }
        }
        return ScoreMaps(text, link, mapW, mapH, ratio)
    }

    /**
     * The most input pixels the detector runs at once, bounding its memory (~1.3 KB a pixel). A
     * canvas within it is run whole, exactly as EasyOCR runs it; a larger one in tiles.
     */
    var maxTilePixels = DEFAULT_MAX_TILE_PIXELS

    /** Context each tile carries beyond its core on every side that is not the image's edge (a multiple of 32). */
    var tileMargin = DEFAULT_TILE_MARGIN

    override fun close() {
        craft.close()
        recognizerSession.close()
    }

    companion object {
        /**
         * How a [w]×[h] input (multiples of 32) is cut: the cores along x and along y. Of the layouts
         * of up to 16×16 tiles whose largest tile (core plus margins) fits [maxTilePixels] (with [tileMargin] around each core), the one
         * computing the fewest pixels in all; if none fits, the one with the smallest largest tile.
         */
        internal fun tilePlan(w: Int, h: Int, maxTilePixels: Long, tileMargin: Int): Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>> {
            if (w.toLong() * h <= maxTilePixels) return listOf(0 to w) to listOf(0 to h)
            require(tileMargin % 32 == 0) { "tile margin $tileMargin" }
            var best: Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>>? = null
            var bestTotal = Long.MAX_VALUE
            var fallback: Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>>? = null
            var fallbackArea = Long.MAX_VALUE
            for (nx in 1..minOf(16, w / 32)) for (ny in 1..minOf(16, h / 32)) {
                val xs = evenSpans(w, nx)
                val ys = evenSpans(h, ny)
                if (xs.any { it.first >= it.second } || ys.any { it.first >= it.second }) continue
                val xe = xs.map { (s, e) -> minOf(w, e + tileMargin) - maxOf(0, s - tileMargin) }
                val ye = ys.map { (s, e) -> minOf(h, e + tileMargin) - maxOf(0, s - tileMargin) }
                val largest = xe.max().toLong() * ye.max()
                if (largest <= maxTilePixels) {
                    val total = xe.sum().toLong() * ye.sum()
                    if (total < bestTotal) { bestTotal = total; best = xs to ys }
                } else if (largest < fallbackArea) {
                    fallbackArea = largest; fallback = xs to ys
                }
            }
            return best ?: fallback!!
        }

        /** [length] (a multiple of 32) cut into [n] consecutive ranges of nearly equal length, multiples of 32. */
        private fun evenSpans(length: Int, n: Int): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>(n)
            var start = 0
            for (i in 0 until n) {
                val end = if (i == n - 1) length else ((length.toLong() * (i + 1) / n).toInt() + 16) / 32 * 32
                out.add(start to end)
                start = end
            }
            return out
        }

        const val DEFAULT_CANVAS = 2560
        /** 2.7 MP: every phone screenshot and a 1920×1440 canvas run whole (about 3.5 GB). */
        const val DEFAULT_MAX_TILE_PIXELS = 2_700_000L
        const val DEFAULT_TILE_MARGIN = 256
        private val MEAN = floatArrayOf((0.485 * 255.0).toFloat(), (0.456 * 255.0).toFloat(), (0.406 * 255.0).toFloat())
        private val STD = floatArrayOf((0.229 * 255.0).toFloat(), (0.224 * 255.0).toFloat(), (0.225 * 255.0).toFloat())
    }
}
