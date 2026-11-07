package com.lithic.gallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lithic.gallery.ai.ModelManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.FloatBuffer

/**
 * How fast the models run on this phone with different ONNX Runtime settings, at the sizes real
 * photos give them (CRAFT at thumbsup2's 2560 canvas and smaller, BLIP's vision encoder).
 * Results go to the log under "OnDeviceBenchmark". Needs the weights (push-models.bat).
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceBenchmark {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ModelManager(context)
    private val env = OrtEnvironment.getEnvironment()

    private fun session(model: String, graph: String, configure: (OrtSession.SessionOptions) -> Unit): OrtSession {
        val so = OrtSession.SessionOptions()
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        so.addConfigEntry("session.model_external_initializers_file_folder_path", models.store.weightsDir(model).absolutePath)
        configure(so)
        return env.createSession(models.store.graph(model, graph), so)
    }

    private fun time(s: OrtSession, name: String, shape: LongArray, runs: Int): Long {
        val n = shape.fold(1L) { a, b -> a * b }.toInt()
        val data = FloatArray(n) { ((it * 2654435761L) % 1000).toFloat() / 500f - 1f }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { t ->
            s.run(mapOf(name to t)).close() // warm-up
            val t0 = System.nanoTime()
            repeat(runs) { s.run(mapOf(name to t)).close() }
            return (System.nanoTime() - t0) / 1_000_000 / runs
        }
    }

    @Test
    fun craftAndBlipSpeed() {
        assumeTrue("weights not on the phone", File(models.store.weightsDir(ModelManager.CRAFT), "craft_mlt_25k.pth").exists())
        val configs: List<Pair<String, (OrtSession.SessionOptions) -> Unit>> = listOf(
            "nnapi fp16" to { so -> so.addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16)) },
            "nnapi fp32" to { so -> so.addNnapi() },
            "default threads" to { _ -> },
            "4 threads" to { so -> so.setIntraOpNumThreads(4) },
            "xnnpack 4" to { so -> so.setIntraOpNumThreads(1); so.addXnnpack(mapOf("intra_op_num_threads" to "4")) },
        )
        for ((label, cfg) in configs) {
            try {
                // A session per input size, its shape fixed (NNAPI compiles only static shapes).
                for ((h, w) in listOf(1920 to 2560, 960 to 1280, 2368 to 1088)) {
                    session(ModelManager.CRAFT, "craft.onnx") { so ->
                        so.setSymbolicDimensionValue("h", h.toLong())
                        so.setSymbolicDimensionValue("w", w.toLong())
                        cfg(so)
                    }.use { s ->
                        val ms = time(s, "image", longArrayOf(1, 3, h.toLong(), w.toLong()), 2)
                        Log.i(TAG, "craft $label ${w}x$h: $ms ms")
                    }
                }
                session(ModelManager.BLIP, "blip_vision.onnx", cfg).use { s ->
                    Log.i(TAG, "blip vision $label: ${time(s, "pixel_values", longArrayOf(1, 3, 384, 384), 3)} ms")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
            }
        }
    }

    private companion object { const val TAG = "OnDeviceBenchmark" }
}
