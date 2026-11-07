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
            val crop = Crops.free(grey, box) ?: continue
            val r = recognizer.read(crop)
            out.add(Text(crop.box, r.text, r.confidence))
        }
        return out
    }

    /** What thumbsup2 stores as an image's OCR text: every reading joined by spaces, stripped. */
    fun text(img: RgbImage): String = readText(img).joinToString(" ") { it.text }.trim()

    /** EasyOCR's `detect`: the text boxes in [img], grouped into lines and rotated boxes. */
    fun detect(img: RgbImage): TextGrouping.Groups {
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
        val scores = onnx.floats(input, 1, 3, th32.toLong(), tw32.toLong()).use { t ->
            craft.run(mapOf("image" to t)).use { r -> (r[0] as OnnxTensor).toFloatArray() }
        }
        val text = FloatArray(mapW * mapH) { scores[it * 2] }
        val link = FloatArray(mapW * mapH) { scores[it * 2 + 1] }
        val scale = (1.0 / ratio * 2).toFloat()
        val polys = CraftBoxes.detect(text, link, mapW, mapH).map { b -> IntArray(8) { (b[it] * scale).toInt() } }
        return TextGrouping.group(polys)
    }

    override fun close() {
        craft.close()
        recognizerSession.close()
    }

    companion object {
        const val DEFAULT_CANVAS = 2560
        private val MEAN = floatArrayOf((0.485 * 255.0).toFloat(), (0.456 * 255.0).toFloat(), (0.406 * 255.0).toFloat())
        private val STD = floatArrayOf((0.229 * 255.0).toFloat(), (0.224 * 255.0).toFloat(), (0.225 * 255.0).toFloat())
    }
}
