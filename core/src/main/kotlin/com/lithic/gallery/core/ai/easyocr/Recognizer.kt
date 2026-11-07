package com.lithic.gallery.core.ai.easyocr

import ai.onnxruntime.OrtSession
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.toFloatArray
import com.lithic.gallery.core.image.GrayImage
import com.lithic.gallery.core.image.Resample
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/** EasyOCR's english_g2 recognizer and its decoding: `recognition.get_text` with batch_size 1. */
class Recognizer(private val onnx: Onnx, private val session: OrtSession) {

    /** One crop's reading: its text and EasyOCR's confidence in it. */
    data class Reading(val text: String, val confidence: Double)

    /**
     * Reads [crop]; when EasyOCR's confidence is under 0.1 it reads it again with the contrast
     * stretched, and keeps the more confident reading.
     */
    fun read(crop: Crop): Reading {
        val width = Crops.maxWidth(crop)
        val first = predict(crop.image, width)
        if (first.confidence >= CONTRAST_THS) return first
        val second = predict(adjustContrast(crop.image, ADJUST_CONTRAST), width)
        return if (first.confidence > second.confidence) first else second
    }

    private fun predict(img: GrayImage, imgW: Int): Reading {
        // AlignCollate: scale to height 64 keeping the aspect ratio (Pillow bicubic), at most imgW
        val ratio = img.width / img.height.toDouble()
        val resizedW = minOf(ceil(HEIGHT * ratio).toInt(), imgW)
        val resized = Resample.pil(img, resizedW, HEIGHT)
        // NormalizePAD: to [-1, 1], padded to imgW by repeating the last column
        val input = FloatArray(HEIGHT * imgW)
        for (y in 0 until HEIGHT) {
            for (x in 0 until imgW) {
                val sx = if (x < resizedW) x else resizedW - 1
                val v = (resized.data[y * resizedW + sx].toInt() and 0xFF) / 255f
                input[y * imgW + x] = (v - 0.5f) / 0.5f
            }
        }
        val scores = onnx.floats(input, 1, 1, HEIGHT.toLong(), imgW.toLong()).use { t ->
            session.run(mapOf("image" to t)).use { r -> (r[0] as ai.onnxruntime.OnnxTensor).toFloatArray() }
        }
        return decode(scores)
    }

    /** Softmax, greedy CTC decoding and EasyOCR's confidence (`custom_mean`). */
    internal fun decode(scores: FloatArray): Reading {
        val steps = scores.size / CLASSES
        val sb = StringBuilder()
        var prev = -1
        var product = 1f
        var counted = 0
        val prob = FloatArray(CLASSES)
        for (t in 0 until steps) {
            val off = t * CLASSES
            var mx = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) mx = maxOf(mx, scores[off + c])
            var sum = 0f
            for (c in 0 until CLASSES) { prob[c] = exp(scores[off + c] - mx); sum += prob[c] }
            for (c in 0 until CLASSES) prob[c] /= sum
            // EasyOCR renormalizes after zeroing ignored characters; English ignores none, so
            // this only reproduces the rounding.
            val norm = numpySum(prob)
            var best = 0
            var bestP = prob[0] / norm
            for (c in 1 until CLASSES) {
                val p = prob[c] / norm
                if (p > bestP) { best = c; bestP = p }
            }
            if (best != 0) { product *= bestP; counted++ }
            if (best != 0 && best != prev) sb.append(CHARACTERS[best - 1])
            prev = best
        }
        val confidence = if (counted == 0) 0.0 else product.toDouble().pow(2.0 / sqrt(counted.toDouble()))
        return Reading(sb.toString(), confidence)
    }

    companion object {
        const val HEIGHT = 64
        private const val CONTRAST_THS = 0.1
        private const val ADJUST_CONTRAST = 0.5

        /** english_g2's characters; class i+1 is CHARACTERS[i] and class 0 the CTC blank. */
        const val CHARACTERS = "0123456789!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~ €" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val CLASSES = CHARACTERS.length + 1

        /** numpy's float32 sum over 97 values: pairwise, 8 accumulators. */
        internal fun numpySum(a: FloatArray): Float {
            val n = a.size
            if (n < 8) {
                var s = 0f
                for (v in a) s += v
                return s
            }
            val r = FloatArray(8) { a[it] }
            var i = 8
            while (i < n - (n % 8)) {
                for (j in 0 until 8) r[j] += a[i + j]
                i += 8
            }
            var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7]))
            while (i < n) { res += a[i]; i++ }
            return res
        }

        /** `adjust_contrast_grey(img, target)`: stretch a low-contrast crop's levels. */
        internal fun adjustContrast(img: GrayImage, target: Double): GrayImage {
            val sorted = IntArray(img.data.size) { img.data[it].toInt() and 0xFF }.also { it.sort() }
            val high = percentile(sorted, 90.0)
            val low = percentile(sorted, 10.0)
            val contrast = (high - low) / maxOf(10.0, high + low)
            if (contrast >= target) return img
            val ratio = 200.0 / maxOf(10.0, high - low)
            val out = ByteArray(img.data.size) {
                val v = ((img.data[it].toInt() and 0xFF) - low + 25) * ratio
                v.coerceIn(0.0, 255.0).toInt().toByte()
            }
            return GrayImage(img.width, img.height, out)
        }

        /** np.percentile with linear interpolation over sorted values. */
        private fun percentile(sorted: IntArray, q: Double): Double {
            val pos = (sorted.size - 1) * q / 100.0
            val lo = floor(pos).toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            val frac = pos - lo
            return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
        }
    }
}
