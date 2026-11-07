package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.ai.ModelTestSupport.reference
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * Embeddings of thumbsup2's caption + OCR text match the ones thumbsup2 stored (cosine similarity
 * within float32 rounding of 1), and are null where it stored none.
 */
class MiniLmEmbedderTest {
    @Test
    fun embeddingsMatchThumbsup2() {
        ModelTestSupport.store.weightsDir("minilm")
        MiniLmEmbedder(ModelTestSupport.onnx, ModelTestSupport.tokenizer).use { minilm ->
            val describer = Describer(null, null, minilm)
            for ((name, ref) in reference) {
                val caption = ref["caption"]!!.jsonPrimitive.content
                val ocr = ref["ocr"]!!.jsonPrimitive.content
                val got = describer.embed(caption, ocr)
                val stored = ref["embedding"]
                if (stored == null || stored is JsonNull) {
                    assertWithMessage("$name: thumbsup2 stored no embedding").that(got).isNull()
                    continue
                }
                val want = stored.jsonArray.map { it.jsonPrimitive.float }
                assertWithMessage("$name embedding").that(got).isNotNull()
                var dot = 0.0
                for (i in want.indices) dot += got!![i] * want[i]
                assertWithMessage("$name: cosine to thumbsup2's embedding").that(dot).isGreaterThan(0.99999)
            }
        }
    }
}
