package com.lithic.gallery.core.ai

import com.lithic.gallery.core.TestFiles
import com.lithic.gallery.core.image.RgbImage
import com.lithic.gallery.core.text.BertTokenizer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** The models as the desktop tests run them: graphs from the app's assets, weights from tools/weights. */
object ModelTestSupport {
    val store = object : ModelStore {
        override fun graph(model: String, name: String): ByteArray = File(TestFiles.graphs, "$model/$name").readBytes()
        override fun weightsDir(model: String): File = TestFiles.weightsDir(model)
    }

    val onnx by lazy { Onnx(store, OnnxOptions(threads = Runtime.getRuntime().availableProcessors())) }
    val tokenizer by lazy { BertTokenizer(TestFiles.vocab()) }

    /** thumbsup2's output per test image (GPU run, fp32 throughout). */
    val reference: Map<String, JsonObject> by lazy {
        TestFiles.reference("thumbsup2_cuda.json").jsonObject.mapValues { it.value.jsonObject }
    }

    /** A test image's pixels exactly as PIL decoded them for thumbsup2. */
    fun rawImage(name: String): RgbImage {
        val rgb = TestFiles.require(File(TestFiles.testdata, "raw/$name.rgb"), "tools/make_image_reference.py raw")
        val (w, h) = File(TestFiles.testdata, "raw/$name.size").readText().trim().split(" ").map { it.toInt() }
        return RgbImage(w, h, rgb.readBytes())
    }
}
