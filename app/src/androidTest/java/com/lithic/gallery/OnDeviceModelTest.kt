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

    /**
     * The text detector on this phone's GPU (a Snapdragon's) reads the test images as EasyOCR
     * does, but for what float16 moves (a character or two in a few of them: on an S22 Ultra, one
     * stray "M" in one of 42), and how much faster than the processor. A GPU computing the graph
     * wrongly reads far more differently. Skipped where there is no such GPU.
     */
    @Test
    fun gpuReadsAsEasyOcrDoes() {
        val models = ModelManager(context)
        val ocrFile = File(testDir, "ocr.json")
        assumeTrue("no test data: run tools/push-test-data.bat", ocrFile.exists())
        assumeTrue("weights not on the phone: run push-models.bat",
            File(models.store.weightsDir(ModelManager.CRAFT), "craft_mlt_25k.pth").exists())
        val onnx = Onnx(models.store, OnnxOptions())
        val gpu = com.lithic.gallery.ai.SnapdragonGpu.accelerator(onnx.env)
        assumeTrue("no Snapdragon GPU", gpu != null)
        val ocrRef = JSONObject(ocrFile.readText())
        val names = ocrRef.keys().asSequence().toList()
        // The CPU's time to compare with (its text is EasyOCR's: matchesThumbsup2). One reader at
        // a time: a CPU session keeps its largest run's memory (3.4 GB for a whole screenshot)
        // until it is closed, and the GPU's sessions need theirs on top.
        val cpuMs = HashMap<String, Long>()
        EasyOcr(onnx).use { cpu ->
            for (name in names) {
                val t = System.nanoTime()
                cpu.text(raw(name))
                cpuMs[name] = (System.nanoTime() - t) / 1_000_000
            }
            Log.i(TAG, "CPU pass: ${android.os.Debug.getPss() / 1024} MB in use")
        }
        val differ = ArrayList<String>()
        val wrong = ArrayList<String>()
        var gpuTotal = 0L
        EasyOcr(onnx).use { onGpu ->
            // Sized as the app sizes it on this phone.
            val mi = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            onGpu.acceleratedCachePixels = com.lithic.gallery.ai.AiEngine.gpuPixels(mi.totalMem)
            onGpu.acceleratedMaxTilePixels = onGpu.acceleratedCachePixels
            onGpu.accelerator = gpu
            onGpu.onAcceleratorFailed = { e -> throw AssertionError("the GPU failed", e) }
            assertWithMessage("the GPU computes the detector as the CPU does").that(onGpu.acceleratorAgrees()).isTrue()
            for (name in names) {
                val img = raw(name)
                val t = System.nanoTime()
                val got = onGpu.text(img)
                val ms = (System.nanoTime() - t) / 1_000_000
                gpuTotal += ms
                Log.i(TAG, "$name ${img.width}x${img.height}: CPU ${cpuMs[name]} ms, with the GPU $ms ms")
                val easyOcr = ocrRef.getJSONObject(name).getString("text")
                if (got != easyOcr) {
                    val d = editDistance(got, easyOcr)
                    val line = "$name: GPU '$got', EasyOCR '$easyOcr' ($d characters apart)"
                    differ.add(line)
                    if (d > maxOf(2, easyOcr.length / 20)) wrong.add(line)
                }
            }
            Log.i(TAG, "GPU pass: ${android.os.Debug.getPss() / 1024} MB in use")
        }
        Log.i(TAG, "all ${names.size} images: CPU ${cpuMs.values.sum()} ms, with the GPU $gpuTotal ms (compiling included)")
        differ.forEach { Log.w(TAG, it) }
        assertWithMessage("read more than a character or two differently:\n" + wrong.joinToString("\n")).that(wrong).isEmpty()
        assertWithMessage("read differently:\n" + differ.joinToString("\n")).that(differ.size).isAtMost(names.size / 10)
    }

    /** Levenshtein distance: the characters to insert, delete or change to make [a] into [b]. */
    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private companion object { const val TAG = "OnDeviceModelTest" }
}
