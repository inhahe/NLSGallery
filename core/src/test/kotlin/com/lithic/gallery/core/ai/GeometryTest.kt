package com.lithic.gallery.core.ai

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import com.lithic.gallery.core.ai.easyocr.Geometry
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * minAreaRect and boxPoints against OpenCV's own (tools/reference/geometry.json), bit for bit,
 * on masks shaped like CRAFT's components and on the degenerate ones.
 */
class GeometryTest {
    @Test
    fun matchesOpenCv() {
        val ref = TestFiles.json(
            TestFiles.require(File(TestFiles.reference, "geometry.json"), "tools/make_geometry_reference.py")
        ).jsonObject
        val cases = ref["cases"]!!.jsonArray
        assertThat(cases.size).isGreaterThan(500)
        val wrong = ArrayList<String>()
        for ((n, c) in cases.withIndex()) {
            val o = c.jsonObject
            val w = o["w"]!!.jsonPrimitive.int
            val rows = o["rows"]!!.jsonArray.map { it.jsonPrimitive.content }
            val xs = ArrayList<Int>()
            val ys = ArrayList<Int>()
            for ((y, hex) in rows.withIndex()) for (x in 0 until w) {
                val byte = hex.substring(x / 8 * 2, x / 8 * 2 + 2).toInt(16)
                if (byte shr (7 - x % 8) and 1 == 1) { xs.add(x); ys.add(y) }
            }
            val r = Geometry.minAreaRect(xs.toIntArray(), ys.toIntArray())
            val got = floatArrayOf(r.cx, r.cy, r.width, r.height, r.angle).toList()
            val want = o["rect"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }
            val gotBox = Geometry.boxPoints(r).toList()
            val wantBox = o["box"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }
            // Compared as bits, so -0 and 0 differ as they would after OpenCV's arithmetic.
            if (got.map { it.toBits() } != want.map { it.toBits() }) wrong.add("case $n rect: got $got, OpenCV $want")
            else if (gotBox.map { it.toBits() } != wantBox.map { it.toBits() }) wrong.add("case $n box: got $gotBox, OpenCV $wantBox")
        }
        assertWithMessage("${wrong.size} of ${cases.size} differ (OpenCV ${ref["opencv"]}):\n" + wrong.take(20).joinToString("\n"))
            .that(wrong).isEmpty()
    }
}
