package com.lithic.gallery.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import android.os.Build
import android.util.Log
import com.lithic.gallery.core.ai.Accelerator

/**
 * A Snapdragon phone's Adreno GPU, through Qualcomm's QNN execution provider: a plugin to ONNX
 * Runtime, packaged with QNN's GPU backend (which runs on OpenCL, declared in the manifest).
 * QNN offers one device for its backends; the GPU is picked by provider option. On other chips,
 * and in the x86 build (which has no plugin), there is none.
 */
object SnapdragonGpu {
    private const val TAG = "SnapdragonGpu"
    private var tried = false
    private var device: OrtEpDevice? = null

    private fun isSnapdragon(): Boolean =
        (Build.VERSION.SDK_INT >= 31 && Build.SOC_MANUFACTURER.equals("QTI", ignoreCase = true)) ||
            Build.HARDWARE.equals("qcom", ignoreCase = true)

    /** The GPU as an [Accelerator] for sessions in [env], registering the plugin the first time; null when there is none. */
    @Synchronized
    fun accelerator(env: OrtEnvironment): Accelerator? {
        if (!tried) {
            tried = true
            device = try {
                if (!isSnapdragon()) null else {
                    if (env.epDevices.none { it.epName == EP }) env.registerExecutionProviderLibrary(EP, "libonnxruntime_providers_qnn.so")
                    env.epDevices.firstOrNull { it.epName == EP }
                }
            } catch (e: Exception) {
                Log.i(TAG, "no QNN execution provider here", e)
                null
            }
            Log.i(TAG, "Snapdragon GPU: ${if (device != null) "found" else "none"}")
        }
        val d = device ?: return null
        return Accelerator { so -> so.addExecutionProvider(listOf(d), mapOf("backend_type" to "gpu")) }
    }

    private const val EP = "QNN"
}
