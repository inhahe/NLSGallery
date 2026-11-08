package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import com.lithic.gallery.core.ai.ModelTestSupport.rawImage
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * The OCR port against EasyOCR itself (float32 on the CPU, unquantized, which is what thumbsup2
 * computes on a GPU), stage by stage: grouped boxes, then text. At EasyOCR's default canvas
 * (tools/reference/ocr.json) and at 448, which shrinks every test image first, as reduced text
 * detail does a camera photo (ocr_canvas448.json).
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

    private fun reference(file: String): JsonObject =
        TestFiles.require(java.io.File(TestFiles.reference, file), "tools/make_image_reference.py").let {
            TestFiles.json(it).jsonObject
        }

    private fun boxes(file: String, canvas: Int) {
        ocr!!.canvasSize = canvas
        val wrong = ArrayList<String>()
        for ((name, r) in reference(file)) {
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

    private fun text(file: String, canvas: Int) {
        ocr!!.canvasSize = canvas
        val wrong = ArrayList<String>()
        for ((name, r) in reference(file)) {
            val want = r.jsonObject["text"]!!.jsonPrimitive.content
            val got = ocr!!.text(rawImage(name))
            if (got != want) wrong.add("$name: got '$got', EasyOCR '$want'")
        }
        assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
    }

    @Test fun detectsTheSameBoxes() = boxes("ocr.json", EasyOcr.DEFAULT_CANVAS)

    @Test fun readsTheSameText() = text("ocr.json", EasyOcr.DEFAULT_CANVAS)

    @Test fun detectsTheSameBoxesOnAShrunkCanvas() = boxes("ocr_canvas448.json", 448)

    @Test fun readsTheSameTextOnAShrunkCanvas() = text("ocr_canvas448.json", 448)

    /**
     * The path a phone's GPU takes, on the CPU: every input through a session of its own fixed
     * shape, tiled to the accelerator's smaller budget. The text is still EasyOCR's.
     */
    @Test
    fun fixedShapeSessionsReadTheSameText() {
        val o = ocr!!
        o.accelerator = com.lithic.gallery.core.ai.Accelerator { } // adds no provider: the CPU, shapes fixed
        o.acceleratedFrom = 0
        try {
            assertWithMessage("the CPU agrees with itself").that(o.acceleratorAgrees(o.accelerator!!)).isTrue()
            text("ocr.json", EasyOcr.DEFAULT_CANVAS)
        } finally {
            o.accelerator = null
            o.acceleratedFrom = EasyOcr.DEFAULT_ACCELERATED_FROM
        }
    }
}
