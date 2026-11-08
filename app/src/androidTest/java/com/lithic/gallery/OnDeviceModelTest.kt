package com.lithic.gallery

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.core.ai.BlipCaptioner
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.OnnxOptions
import com.lithic.gallery.core.ai.embeddingText
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import com.lithic.gallery.core.image.RgbImage
import com.lithic.gallery.core.text.BertTokenizer
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The models on the phone itself (ONNX Runtime for Android, ARM kernels): the same captions, OCR
 * text and embeddings as thumbsup2 on the test images, and how long each takes.
 *
 * Needs the weights and the test data in the app's folder on shared storage: run
 * push-models.bat and tools/push-test-data.bat first. Logs timings under the tag "OnDeviceModelTest".
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testDir = File(context.getExternalFilesDir(null), "test")

    private fun raw(name: String): RgbImage {
        val (w, h) = File(testDir, "raw/$name.size").readText().trim().split(" ").map { it.toInt() }
        return RgbImage(w, h, File(testDir, "raw/$name.rgb").readBytes())
    }

    @Test
    fun matchesThumbsup2() {
        val models = ModelManager(context)
        val refFile = File(testDir, "thumbsup2_cuda.json")
        assumeTrue("no test data: run tools/push-test-data.bat", refFile.exists())
        assumeTrue("weights not on the phone: run push-models.bat",
            File(models.store.weightsDir(ModelManager.BLIP), "model.safetensors").exists())
        val ref = JSONObject(refFile.readText())
        val ocrRef = JSONObject(File(testDir, "ocr.json").readText())
        val tokenizer = BertTokenizer(context.assets.open("models/vocab.txt").bufferedReader().readLines())
        val onnx = Onnx(models.store, OnnxOptions())
        val t0 = System.nanoTime()
        val blip = BlipCaptioner(onnx, tokenizer)
        val ocr = EasyOcr(onnx)
        val minilm = MiniLmEmbedder(onnx, tokenizer)
        Log.i(TAG, "models opened in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val wrong = ArrayList<String>()
        var capMs = 0L; var ocrMs = 0L; var embMs = 0L; var n = 0
        for (name in ref.keys()) {
            val r = ref.getJSONObject(name)
            val img = raw(name)
            var t = System.nanoTime()
            val caption = blip.caption(img)
            val c = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val text = ocr.text(img)
            val o = (System.nanoTime() - t) / 1_000_000
            t = System.nanoTime()
            val emb = embeddingText(caption, text)?.let(minilm::embed)
            val e = (System.nanoTime() - t) / 1_000_000
            capMs += c; ocrMs += o; embMs += e; n++
            Log.i(TAG, "$name ${img.width}x${img.height}: caption ${c} ms, ocr ${o} ms, embed ${e} ms")
            if (caption != r.getString("caption")) wrong.add("$name caption '$caption' vs '${r.getString("caption")}'")
            val wantText = ocrRef.getJSONObject(name).getString("text")
            if (text != wantText) wrong.add("$name ocr '$text' vs '$wantText'")
            val stored = r.optJSONArray("embedding")
            if (stored != null && emb != null) {
                var dot = 0.0
                for (i in 0 until stored.length()) dot += emb[i] * stored.getDouble(i)
                if (dot < 0.9999) wrong.add("$name embedding cosine $dot")
            }
        }
        Log.i(TAG, "mean over $n images: caption ${capMs / n} ms, ocr ${ocrMs / n} ms, embed ${embMs / n} ms")
        wrong.forEach { Log.w(TAG, it) }
        blip.close(); ocr.close(); minilm.close()
        assertWithMessage(wrong.joinToString("\n")).that(wrong).isEmpty()
    }

    private companion object { const val TAG = "OnDeviceModelTest" }
}
