package com.lithic.gallery.core.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.lithic.gallery.core.text.BertTokenizer

/**
 * sentence-transformers/all-MiniLM-L6-v2 embeddings, as thumbsup2 makes them for semantic search:
 * `encode([text], normalize_embeddings=True)`, i.e. tokens truncated to 256, mean pooling, unit length.
 */
class MiniLmEmbedder(private val onnx: Onnx, private val tokenizer: BertTokenizer) : AutoCloseable {
    private val session: OrtSession = onnx.session("minilm", "minilm.onnx")

    /** A unit vector of [DIM] floats; dot products between them are cosine similarities. */
    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text, MAX_TOKENS)
        val n = ids.size.toLong()
        return onnx.longs(LongArray(ids.size) { ids[it].toLong() }, 1, n).use { input ->
            onnx.longs(LongArray(ids.size) { 1L }, 1, n).use { mask ->
                session.run(mapOf("input_ids" to input, "attention_mask" to mask)).use { r ->
                    (r[0] as OnnxTensor).toFloatArray()
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        /** The model id stored with each embedding, so a different model's vectors are never mixed in. */
        const val MODEL_ID = "sentence-transformers/all-MiniLM-L6-v2"
        const val DIM = 384
        /** sentence-transformers' max_seq_length for this model. */
        const val MAX_TOKENS = 256
    }
}
