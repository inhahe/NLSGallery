package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import com.lithic.gallery.core.ai.ModelTestSupport.rawImage
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * The OCR port against EasyOCR itself (tools/reference/ocr.json: float32 on the CPU, unquantized,
 * which is what thumbsup2 computes on a GPU), stage by stage: grouped boxes, then text.
 */
class EasyOcrTest {
    companion object {
        private var ocr: EasyOcr? = null

        @BeforeClass @JvmStatic
        fun open() {
            ModelTestSupport.store.weightsDir("craft")
            ModelTestSupport.store.weightsDir("english_g2")
            ocr = EasyOcr(ModelTestSupport.onnx)
        }

        @AfterClass @JvmStatic
        fun close() {
            ocr?.close()
        }
    }

    private val ref = TestFiles.reference("ocr.json").jsonObject

    @Test
    fun detectsTheSameBoxes() {
        val wrong = ArrayList<String>()
        for ((name, r) in ref) {
            val groups = ocr!!.detect(rawImage(name))
            val wantH = r.jsonObject["horizontal"]!!.jsonArray.map { b -> b.jsonArray.map { it.jsonPrimitive.int } }
            val gotH = groups.horizontal.map { it.toList() }
            if (gotH != wantH) wrong.add("$name horizontal: got $gotH, EasyOCR $wantH")
            val wantF = r.jsonObject["free"]!!.jsonArray.map { b -> b.jsonArray.flatMap { p -> p.jsonArray.map { it.jsonPrimitive.double } } }
            val gotF = groups.free.map { it.toList() }
            if (gotF.size != wantF.size || gotF.zip(wantF).any { (g, w) -> g.zip(w).any { (a, b) -> Math.abs(a - b) > 1e-6 } }) {
                wrong.add("$name free: got $gotF, EasyOCR $wantF")
            }
        }
        assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
    }

    @Test
    fun readsTheSameText() {
        val wrong = ArrayList<String>()
        for ((name, r) in ref) {
            val want = r.jsonObject["text"]!!.jsonPrimitive.content
            val got = ocr!!.text(rawImage(name))
            if (got != want) wrong.add("$name: got '$got', EasyOCR '$want'")
        }
        assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
    }
}
