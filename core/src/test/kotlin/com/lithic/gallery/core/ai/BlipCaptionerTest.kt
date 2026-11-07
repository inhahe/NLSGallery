package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.ai.ModelTestSupport.rawImage
import com.lithic.gallery.core.ai.ModelTestSupport.reference
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The on-device caption pipeline says, word for word, what thumbsup2 said about each test image. */
class BlipCaptionerTest {
    @Test
    fun captionsMatchThumbsup2() {
        ModelTestSupport.store.weightsDir("blip")
        BlipCaptioner(ModelTestSupport.onnx, ModelTestSupport.tokenizer).use { blip ->
            val wrong = ArrayList<String>()
            for ((name, ref) in reference) {
                val want = ref["caption"]!!.jsonPrimitive.content
                val got = blip.caption(rawImage(name))
                if (got != want) wrong.add("$name: got '$got', thumbsup2 said '$want'")
            }
            assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
        }
    }
}
