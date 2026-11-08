package com.lithic.gallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.os.Debug
import android.os.PowerManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.ai.SnapdragonGpu
import com.lithic.gallery.core.ai.Accelerator
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.OnnxOptions
import com.lithic.gallery.core.ai.toFloatArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The text detector (CRAFT) on a Snapdragon's GPU, as the app runs it there (the float16 graph),
 * against the float32 graph on the processor: how long a GPU session takes to compile for a
 * shape, how long a run takes on each, how much memory a GPU session holds, how far its scores
 * are from the CPU's, and how both slow as the phone warms. Results go to the log under
 * "GpuProbe"; nothing is asserted (OnDeviceModelTest checks the text read).
 */
@RunWith(AndroidJUnit4::class)
class GpuProbe {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ModelManager(context)
    private val power = context.getSystemService(PowerManager::class.java)

    private fun log(msg: String) = Log.i(TAG, msg)

    /** This process's memory and the system's, in MB. */
    private fun memory(): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val sys = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(sys)
        return "pss ${mi.totalPss / 1024} MB (graphics ${(mi.getMemoryStat("summary.graphics")?.toInt() ?: 0) / 1024}), " +
            "system free ${sys.availMem shr 20} MB, thermal status ${power.currentThermalStatus}"
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** The ONNX setup and the GPU, or the test skipped where either is missing. */
    private fun setup(): Pair<Onnx, Accelerator> {
        assumeTrue("weights not on the phone (push-models.bat debug)",
            File(models.store.weightsDir(ModelManager.CRAFT), models.sources.getValue(ModelManager.CRAFT).file).exists())
        val onnx = Onnx(models.store, OnnxOptions())
        val gpu = SnapdragonGpu.accelerator(onnx.env)
        assumeTrue("no Snapdragon GPU", gpu != null)
        return onnx to gpu!!
    }

    private class Tile(val h: Int, val w: Int) {
        val input = FloatArray(3 * h * w) { ((it * 2654435761L) % 1000).toFloat() / 500f - 1f }
        override fun toString() = "${h}x$w (${h * w / 1000} kpx)"
    }

    /** [s]'s scores for [t] and how long the run took. */
    private fun run(onnx: Onnx, s: OrtSession, t: Tile): Pair<FloatArray, Long> =
        onnx.floats(t.input, 1, 3, t.h.toLong(), t.w.toLong()).use { x ->
            val t0 = System.nanoTime()
            val out = s.run(mapOf("image" to x)).use { r -> (r[0] as OnnxTensor).toFloatArray() }
            out to ms(t0)
        }

    private fun gpuSession(onnx: Onnx, gpu: Accelerator, t: Tile) =
        onnx.session("craft", "craft_fp16.onnx", mapOf("h" to t.h.toLong(), "w" to t.w.toLong()), gpu)

    /** One shape at a time, each session closed before the next opens. */
    @Test
    fun craftShapes() {
        val (onnx, gpu) = setup()
        log("start: ${memory()}")
        // Tiles the app makes: a small photo's, near the GPU threshold, a half-detail 4:3 camera
        // photo (1280×960), a full-detail tile at the CPU's 1.6 MP, a whole screenshot, and a
        // 1920×1440 canvas whole.
        for (t in listOf(Tile(480, 640), Tile(768, 800), Tile(960, 1280), Tile(1216, 1312), Tile(2368, 1088), Tile(1440, 1920))) {
            val (want, cpuMs) = onnx.session("craft", "craft.onnx").use { cpu ->
                run(onnx, cpu, t) // warming up
                run(onnx, cpu, t)
            }
            log("$t CPU: $cpuMs ms; ${memory()}")
            val t0 = System.nanoTime()
            gpuSession(onnx, gpu, t).use { g ->
                log("$t GPU compiled in ${ms(t0)} ms; ${memory()}")
                val (got, first) = run(onnx, g, t)
                val second = run(onnx, g, t).second
                var worst = 0f
                for (i in want.indices) worst = maxOf(worst, Math.abs(want[i] - got[i]))
                log("$t GPU: first run $first ms, then $second ms, max |CPU - GPU| = $worst " +
                    "(largest ${want.maxOf { Math.abs(it) }}); ${memory()}")
            }
            log("$t GPU closed: ${memory()}")
        }
    }

    /** CPU and GPU in turn on a half-detail camera photo's tile for a few minutes, as the phone warms. */
    @Test
    fun sustained() {
        val (onnx, gpu) = setup()
        val t = Tile(960, 1280)
        onnx.session("craft", "craft.onnx").use { cpu ->
            gpuSession(onnx, gpu, t).use { g ->
                run(onnx, cpu, t); run(onnx, g, t)
                for (round in 1..15) {
                    val c = run(onnx, cpu, t).second
                    val gg = run(onnx, g, t).second
                    log("round $round: CPU $c ms, GPU $gg ms, thermal status ${power.currentThermalStatus}, " +
                        "headroom ${"%.2f".format(power.getThermalHeadroom(0))}")
                }
            }
        }
    }

    private companion object { const val TAG = "GpuProbe" }
}
