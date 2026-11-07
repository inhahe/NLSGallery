package com.lithic.gallery.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import com.lithic.gallery.core.image.Resample
import com.lithic.gallery.core.image.RgbImage
import com.lithic.gallery.core.text.BertTokenizer
import java.nio.FloatBuffer

/**
 * Salesforce/blip-image-captioning-base captions, as thumbsup2 makes them: transformers' BLIP
 * processor (384×384 antialiased bicubic, CLIP mean and deviation), then `generate(...,
 * max_new_tokens=40)`, which is greedy, then `decode(..., skip_special_tokens=True).strip()`.
 *
 * Runs on two graphs (tools/export_models.py): blip_vision gives the image's cross-attention keys
 * and values for all decoder layers; blip_decoder takes one token and the cache so far.
 */
class BlipCaptioner(private val onnx: Onnx, private val tokenizer: BertTokenizer) : AutoCloseable {
    private val vision: OrtSession = onnx.session("blip", "blip_vision.onnx")
    private val decoder: OrtSession = onnx.session("blip", "blip_decoder.onnx")

    fun caption(img: RgbImage, maxNewTokens: Int = 40): String {
        val pixels = pixelValues(img)
        val crossResult = onnx.floats(pixels, 1, 3, SIZE.toLong(), SIZE.toLong()).use { t ->
            vision.run(mapOf("pixel_values" to t))
        }
        crossResult.use { cross ->
            val feeds = HashMap<String, OnnxTensor>()
            for (i in 0 until LAYERS) for (kv in arrayOf("k", "v")) {
                feeds["cross_${kv}_$i"] = cross.get("cross_${kv}_$i").get() as OnnxTensor
            }
            val empty = ArrayList<OnnxTensor>()
            for (i in 0 until LAYERS) for (kv in arrayOf("k", "v")) {
                val t = OnnxTensor.createTensor(onnx.env, FloatBuffer.allocate(0), longArrayOf(1, HEADS, 0, HEAD_DIM))
                empty.add(t)
                feeds["past_${kv}_$i"] = t
            }
            val ids = arrayListOf(BOS)
            var previous: OrtSession.Result? = null
            try {
                repeat(maxNewTokens) {
                    val result = onnx.longs(longArrayOf(ids.last().toLong()), 1, 1).use { input ->
                        feeds["input_ids"] = input
                        decoder.run(feeds)
                    }
                    previous?.close()
                    previous = result
                    val next = argmax((result.get("logits").get() as OnnxTensor).floatBuffer)
                    ids.add(next)
                    for (i in 0 until LAYERS) for (kv in arrayOf("k", "v")) {
                        feeds["past_${kv}_$i"] = result.get("present_${kv}_$i").get() as OnnxTensor
                    }
                    if (next == tokenizer.sepId) return tokenizer.decode(ids.toIntArray())
                }
                return tokenizer.decode(ids.toIntArray())
            } finally {
                previous?.close()
                empty.forEach(OnnxValue::close)
            }
        }
    }

    override fun close() {
        vision.close()
        decoder.close()
    }

    companion object {
        const val SIZE = 384
        private const val LAYERS = 12
        private const val HEADS = 12L
        private const val HEAD_DIM = 64L
        /** BLIP's `[DEC]` token, which starts every caption (text_config.bos_token_id). */
        const val BOS = 30522

        // transformers fuses rescaling into normalization: mean and std are float32 tensors
        // multiplied by 1/rescale_factor (255) in float32.
        private val MEAN = floatArrayOf(0.48145466f * 255f, 0.4578275f * 255f, 0.40821073f * 255f)
        private val STD = floatArrayOf(0.26862954f * 255f, 0.26130258f * 255f, 0.27577711f * 255f)

        /** The processor's `pixel_values` for [img]: CHW, 3×384×384. */
        fun pixelValues(img: RgbImage): FloatArray {
            val planes = Resample.torch(img, SIZE, SIZE)
            val n = SIZE * SIZE
            val out = FloatArray(3 * n)
            for (c in 0 until 3) {
                val p = planes[c]
                for (i in 0 until n) out[c * n + i] = ((p[i].toInt() and 0xFF).toFloat() - MEAN[c]) / STD[c]
            }
            return out
        }

        /** The first index of the largest value, as torch.argmax picks it. */
        private fun argmax(b: FloatBuffer): Int {
            var best = 0
            var bestV = b.get(0)
            for (i in 1 until b.limit()) {
                val v = b.get(i)
                if (v > bestV) { best = i; bestV = v }
            }
            return best
        }
    }
}
