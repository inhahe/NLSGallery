package com.lithic.gallery.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Where the models' files are: the small ONNX graphs (shipped with the app, in its assets) and
 * the official weight files they point into (downloaded, see models.json).
 */
interface ModelStore {
    /** The bytes of graph [name] (e.g. "blip_vision.onnx") of [model] (e.g. "blip"). */
    fun graph(model: String, name: String): ByteArray

    /** The directory holding [model]'s weight file. */
    fun weightsDir(model: String): File
}

/** How ONNX Runtime should run the models. */
data class OnnxOptions(
    /** Threads for one operator; 0 lets ONNX Runtime choose (one per physical core). */
    val threads: Int = 0,
)

/**
 * Hardware that runs a graph faster than the CPU once its input shapes are fixed (a phone's GPU):
 * it adds its execution provider to a session's options.
 */
fun interface Accelerator {
    fun addTo(options: OrtSession.SessionOptions)
}

/** Opens sessions for the graphs, each reading its weights from the official file. */
class Onnx(private val store: ModelStore, private val options: OnnxOptions = OnnxOptions()) {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /**
     * A session for [graph] of [model]. [dims] fixes the graph's named free dimensions (an
     * [accelerator] needs every shape fixed); the accelerator, if any, runs what it can.
     */
    fun session(model: String, graph: String, dims: Map<String, Long> = emptyMap(), accelerator: Accelerator? = null): OrtSession =
        // The session copies its options when it is made: they are freed right after.
        OrtSession.SessionOptions().use { so ->
            so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (options.threads > 0) so.setIntraOpNumThreads(options.threads)
            // The graph is loaded from memory, so ONNX Runtime needs telling where its external
            // data (the weight file the initializers point into) lives.
            so.addConfigEntry("session.model_external_initializers_file_folder_path",
                store.weightsDir(model).absolutePath)
            for ((name, value) in dims) so.setSymbolicDimensionValue(name, value)
            accelerator?.addTo(so)
            env.createSession(store.graph(model, graph), so)
        }

    fun floats(data: FloatArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    fun longs(data: LongArray, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
}

/** The tensor's contents as a new array. */
fun OnnxTensor.toFloatArray(): FloatArray {
    val b = floatBuffer
    return FloatArray(b.remaining()).also { b.get(it) }
}
