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

/** Opens sessions for the graphs, each reading its weights from the official file. */
class Onnx(private val store: ModelStore, private val options: OnnxOptions = OnnxOptions()) {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    fun session(model: String, graph: String): OrtSession {
        val so = OrtSession.SessionOptions()
        so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (options.threads > 0) so.setIntraOpNumThreads(options.threads)
        // The graph is loaded from memory, so ONNX Runtime needs telling where its external
        // data (the weight file the initializers point into) lives.
        so.addConfigEntry("session.model_external_initializers_file_folder_path",
            store.weightsDir(model).absolutePath)
        return env.createSession(store.graph(model, graph), so)
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
