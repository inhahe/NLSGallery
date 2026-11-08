package com.lithic.gallery.core.ai.easyocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.lithic.gallery.core.ai.Accelerator
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

    /**
     * Runs the detector (CRAFT) where set, in float16 ([acceleratedGraph]), on canvases whose
     * tiles (of at most [acceleratedMaxTilePixels], all one shape) are at least [acceleratedFrom]
     * pixels; the CPU runs the rest, tiled to its own budget. It needs fixed shapes, so each tile
     * shape gets a session of its own, kept while the shapes open add up to
     * [acceleratedCachePixels]. If it fails, it is dropped (the CPU takes over) and
     * [onAcceleratorFailed] told.
     *
     * Float16 departs from EasyOCR's float32 by about one part in a thousand of a score, which
     * moves a text box by a pixel now and then (see design.md).
     */
    var accelerator: Accelerator? = null
        set(value) {
            field = value
            closeAccelerated()
        }

    /**
     * Tiles this large go to the accelerator at once; smaller ones from [acceleratedRepeatFrom]
     * once their shape has been seen before. Compiling a session for a shape costs about as much
     * as the CPU takes for this many pixels, so a shape met once is not worth it below it, and
     * shapes do recur (a camera's photos, a phone's screenshots).
     */
    var acceleratedFrom = DEFAULT_ACCELERATED_FROM

    /** The smallest tile the accelerator takes, the second time its shape comes. */
    var acceleratedRepeatFrom = DEFAULT_ACCELERATED_REPEAT_FROM

    /** The accelerator's tile shapes met so far, (height, width). */
    private val shapesSeen = HashSet<Pair<Int, Int>>()

    /** The accelerator's tile budget, bounding a session's memory as [maxTilePixels] does the CPU's. */
    var acceleratedMaxTilePixels = DEFAULT_ACCELERATED_MAX_TILE_PIXELS

    /**
     * The input pixels of the accelerator's sessions kept open together. Each holds its
     * activations while open, so this bounds their memory; at least the one in use is kept.
     */
    var acceleratedCachePixels = DEFAULT_ACCELERATED_MAX_TILE_PIXELS

    var onAcceleratorFailed: (Exception) -> Unit = {}

    /** The graph the accelerator runs (tests give it the float32 one, to check its tiling to the bit). */
    var acceleratedGraph = ACCELERATED_GRAPH

    /** Fixed-shape sessions on the accelerator, by (height, width), least recently used first. */
    private val accelerated = LinkedHashMap<Pair<Int, Int>, OrtSession>(4, 0.75f, true)

    private fun closeAccelerated() {
        accelerated.values.forEach { it.close() }
        accelerated.clear()
    }

    /** The accelerator's session for [h]×[w], compiled after closing the least used ones it would not fit beside. */
    private fun acceleratedSession(acc: Accelerator, h: Int, w: Int): OrtSession {
        accelerated[h to w]?.let { return it }
        val need = h.toLong() * w
        while (accelerated.isNotEmpty() && accelerated.keys.sumOf { (sh, sw) -> sh.toLong() * sw } + need > acceleratedCachePixels) {
            accelerated.remove(accelerated.keys.first())?.close()
        }
        return onnx.session("craft", acceleratedGraph, mapOf("h" to h.toLong(), "w" to w.toLong()), acc).also { accelerated[h to w] = it }
    }

    /** Whether the accelerator should take tiles of [h]×[w]; the shape is noted as seen either way. */
    private fun wantsAccelerator(h: Int, w: Int): Boolean {
        val pixels = h.toLong() * w
        val repeated = !shapesSeen.add(h to w)
        return accelerator != null && (pixels >= acceleratedFrom || (repeated && pixels >= acceleratedRepeatFrom))
    }

    /** CRAFT on [input] (1×3×[h]×[w]) on the accelerator; on the CPU once it has failed (it is dropped then). */
    private fun runAccelerated(input: FloatArray, h: Int, w: Int): FloatArray {
        val acc = accelerator
        if (acc != null) {
            try {
                return run(acceleratedSession(acc, h, w), input, h, w)
            } catch (e: Exception) {
                accelerator = null
                onAcceleratorFailed(e)
            }
        }
        return run(craft, input, h, w)
    }

    private fun run(session: OrtSession, input: FloatArray, h: Int, w: Int): FloatArray =
        onnx.floats(input, 1, 3, h.toLong(), w.toLong()).use { t ->
            session.run(mapOf("image" to t)).use { r -> (r[0] as OnnxTensor).toFloatArray() }
        }

    /**
     * Whether the [accelerator] computes CRAFT as the CPU does, on a made-up 256×384 input: its
     * largest difference within [tolerance]. Scores run from 0 to 1 (EasyOCR's thresholds are 0.4
     * and 0.7); float16 alone moves them by about 0.001, a driver computing the graph wrongly by
     * the order of the scores themselves.
     */
    fun acceleratorAgrees(tolerance: Float = 0.01f): Boolean {
        val acc = accelerator ?: return false
        val h = 256
        val w = 384
        val input = FloatArray(3 * h * w) { ((it * 2654435761L) % 1000).toFloat() / 500f - 1f }
        val cpu = run(craft, input, h, w)
        val got = try {
            onnx.session("craft", acceleratedGraph, mapOf("h" to h.toLong(), "w" to w.toLong()), acc).use { run(it, input, h, w) }
        } catch (e: Exception) {
            return false
        }
        return got.size == cpu.size && got.indices.all { Math.abs(got[it] - cpu[it]) <= tolerance }
    }

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
        // The accelerator's tiles are all one shape (edge tiles reach further in), so one session
        // serves a canvas; when that shape is not one it should take, the CPU runs the canvas, cut
        // to its own budget.
        var onAccelerator = false
        var plan = tilePlan(tw32, th32, maxTilePixels, tileMargin)
        if (accelerator != null) {
            val tiled = tilePlan(tw32, th32, minOf(maxTilePixels, acceleratedMaxTilePixels), tileMargin)
            val shapeH = windows(tiled.second, th32, tileMargin, uniform = true).first().let { (a, b) -> b - a }
            val shapeW = windows(tiled.first, tw32, tileMargin, uniform = true).first().let { (a, b) -> b - a }
            if (wantsAccelerator(shapeH, shapeW)) {
                plan = tiled
                onAccelerator = true
            }
        }
        val (xs, ys) = plan
        val xw = windows(xs, tw32, tileMargin, uniform = onAccelerator)
        val yw = windows(ys, th32, tileMargin, uniform = onAccelerator)
        for (i in ys.indices) for (j in xs.indices) {
            val (cy0, cy1) = ys[i]
            val (cx0, cx1) = xs[j]
            val (y0, y1) = yw[i]
            val (x0, x1) = xw[j]
            val h = y1 - y0
            val w = x1 - x0
            val tile = if (h == th32 && w == tw32) input else FloatArray(3 * h * w).also { t ->
                for (c in 0 until 3) for (y in 0 until h) {
                    System.arraycopy(input, c * plane + (y0 + y) * tw32 + x0, t, c * h * w + y * w, w)
                }
            }
            val scores = if (onAccelerator) runAccelerated(tile, h, w) else run(craft, tile, h, w)
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
        closeAccelerated()
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

        /**
         * What the tiles with [cores] (from [tilePlan]) along a side of [length] see: each core
         * with [margin] more on both sides, within the image. With [uniform], each is widened
         * inward to the widest of them, so every tile has one size (an accelerator compiles a
         * session per size); the widest is the one [tilePlan] fitted to the budget.
         */
        internal fun windows(cores: List<Pair<Int, Int>>, length: Int, margin: Int, uniform: Boolean): List<Pair<Int, Int>> {
            val plain = cores.map { (s, e) -> maxOf(0, s - margin) to minOf(length, e + margin) }
            if (!uniform) return plain
            val size = plain.maxOf { (a, b) -> b - a }
            return plain.map { (a, _) ->
                val end = minOf(length, a + size)
                (end - size) to end
            }
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
        /** The detector in float16, for the accelerator (made by tools/export_models.py from craft.onnx). */
        const val ACCELERATED_GRAPH = "craft_fp16.onnx"
        /**
         * On a Snapdragon 8 Gen 1's GPU, the phone warm: compiling a session for a new shape takes
         * about 2.8 s, and a run is five times as quick as the CPU's (0.6 MP in 1.0 s against
         * 4.8 s), so a shape met once pays for its compiling from about 0.6 MP.
         */
        const val DEFAULT_ACCELERATED_FROM = 600_000L
        /** A shape met again is compiled already: 0.2 MP takes the CPU about 1.6 s, that GPU 0.3 s. */
        const val DEFAULT_ACCELERATED_REPEAT_FROM = 200_000L
        /** A session holds about 1.8 KB of GPU memory a pixel: 1.6 MP about 2.9 GB. */
        const val DEFAULT_ACCELERATED_MAX_TILE_PIXELS = 1_600_000L
        private val MEAN = floatArrayOf((0.485 * 255.0).toFloat(), (0.456 * 255.0).toFloat(), (0.406 * 255.0).toFloat())
        private val STD = floatArrayOf((0.229 * 255.0).toFloat(), (0.224 * 255.0).toFloat(), (0.225 * 255.0).toFloat())
    }
}
