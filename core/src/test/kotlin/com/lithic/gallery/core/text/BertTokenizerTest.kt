package com.lithic.gallery.core.text

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Against tools/reference/tokenizer.json, made by HuggingFace's tokenizers in thumbsup2's setup. */
class BertTokenizerTest {
    private val tokenizer = BertTokenizer(TestFiles.vocab())
    private val ref = TestFiles.reference("tokenizer.json").jsonObject

    @Test
    fun encodesLikeSentenceTransformers() {
        val max = ref["maxSeqLength"]!!.jsonPrimitive.int
        for (case in ref["encode"]!!.jsonArray) {
            val text = case.jsonObject["text"]!!.jsonPrimitive.content
            val want = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertWithMessage("encode %s", text).that(tokenizer.encode(text, max).toList()).isEqualTo(want)
        }
    }

    @Test
    fun decodesLikeBlipsProcessor() {
        for (case in ref["decode"]!!.jsonArray) {
            val ids = case.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.int }.toIntArray()
            val want = case.jsonObject["text"]!!.jsonPrimitive.content
            assertWithMessage("decode %s", ids.toList()).that(tokenizer.decode(ids)).isEqualTo(want)
        }
    }
}
