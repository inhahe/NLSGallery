package com.lithic.gallery.ai

import android.content.Context
import android.util.Log
import com.lithic.gallery.core.ai.BlipCaptioner
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.OnnxOptions
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import com.lithic.gallery.core.text.BertTokenizer
import com.lithic.gallery.data.AiDao
import com.lithic.gallery.data.DescriptionEntity
import com.lithic.gallery.data.EmbeddingEntity
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Describes photos and videos the way thumbsup2 does (BLIP caption, EasyOCR text, MiniLM
 * embedding of both) and stores the result. The models are opened on first use and stay open
 * until [release]; describing is serialized, one file at a time.
 */
class AiEngine(private val context: Context, private val models: ModelManager, private val dao: AiDao) {
    private val mutex = Mutex()
    private var config: Config? = null
    private var onnx: Onnx? = null
    private var blip: BlipCaptioner? = null
    private var ocr: EasyOcr? = null
    private var minilm: MiniLmEmbedder? = null

    val tokenizer: BertTokenizer by lazy {
        BertTokenizer(context.assets.open("models/vocab.txt").bufferedReader().readLines())
    }

    private data class Config(val captions: Boolean, val ocr: Boolean, val threads: Int)

    /** Whether the models for these settings are downloaded. */
    fun ready(s: Settings): Boolean =
        (s.captions || s.ocr) && ModelManager.needed(s.captions, s.ocr).all(models::isReady)

    /** Timing of the last file described, for the log and the details panel. */
    data class Timing(val decodeMs: Long, val captionMs: Long, val ocrMs: Long, val embedMs: Long)

    var lastTiming: Timing? = null
        private set

    /**
     * Describes [item] (the parts [s] asks for that it lacks) and saves the description and
     * embedding. A file that cannot be read is saved with its error, so it is not retried until
     * it changes.
     */
    suspend fun describe(item: MediaEntity, s: Settings) = mutex.withLock {
        withContext(Dispatchers.Default) {
            open(s)
            val existing = dao.description(item.aiKey)?.takeIf { it.error == null }
            val wantCaption = s.captions && existing?.caption == null
            val wantOcr = s.ocr && existing?.ocr == null
            var caption = existing?.caption
            var text = existing?.ocr
            var manual = existing?.manualCaption ?: ""
            val t0 = System.nanoTime()
            var tDecode = 0L; var tCaption = 0L; var tOcr = 0L
            try {
                if (item.isVideo) {
                    val frames = MediaDecoding.decodeVideoFrames(context, item.contentUri, VIDEO_FRAMES)
                    tDecode = ms(t0)
                    if (frames.isEmpty()) throw IllegalStateException("no frames could be read")
                    if (wantCaption) {
                        val t = System.nanoTime()
                        // Each sampled frame's caption, repeats dropped: what the video shows over time.
                        caption = frames.map { blip!!.caption(it) }.filter { it.isNotBlank() }.distinct().joinToString("; ")
                        tCaption = ms(t)
                    }
                    if (wantOcr) {
                        val t = System.nanoTime()
                        text = ocr!!.text(frames[frames.size / 2])
                        tOcr = ms(t)
                    }
                    if (existing == null) manual = MediaDecoding.videoTitle(context, item.contentUri)
                } else {
                    val img = MediaDecoding.decodeImage(context.contentResolver, item.contentUri)
                    tDecode = ms(t0)
                    if (wantCaption) {
                        val t = System.nanoTime()
                        caption = blip!!.caption(img)
                        tCaption = ms(t)
                    }
                    if (wantOcr) {
                        val t = System.nanoTime()
                        text = ocr!!.text(img)
                        tOcr = ms(t)
                    }
                    if (existing == null) manual = MediaDecoding.manualCaption(context.contentResolver, item.contentUri)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "describing ${item.uri} failed", e)
                dao.upsertDescription(DescriptionEntity(item.aiKey, caption, text, manual, System.currentTimeMillis(),
                    error = e.message ?: e.javaClass.simpleName))
                return@withContext
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "describing ${item.uri} ran out of memory", e)
                dao.upsertDescription(DescriptionEntity(item.aiKey, caption, text, manual, System.currentTimeMillis(),
                    error = "out of memory"))
                return@withContext
            }
            dao.upsertDescription(DescriptionEntity(item.aiKey, caption, text, manual, System.currentTimeMillis(), null))
            val t = System.nanoTime()
            saveEmbedding(item.aiKey, caption ?: "", text ?: "")
            lastTiming = Timing(tDecode, tCaption, tOcr, ms(t))
            // Timings only: what the pictures show stays out of the log.
            Log.i(TAG, "described ${item.id}: decode ${tDecode}ms caption ${tCaption}ms ocr ${tOcr}ms")
        }
    }

    /** thumbsup2's embedding: of `" ".join(filter(None, [caption, ocr]))`, none when both are empty. */
    private suspend fun saveEmbedding(hash: String, caption: String, text: String) {
        val joined = listOf(caption.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (joined.isEmpty()) return
        val textHash = sha1(joined)
        if (dao.embedding(hash, MiniLmEmbedder.MODEL_ID)?.textHash == textHash) return
        val v = minilm!!.embed(joined)
        dao.upsertEmbedding(EmbeddingEntity(hash, MiniLmEmbedder.MODEL_ID, toBytes(v), textHash))
    }

    private fun open(s: Settings) {
        val want = Config(s.captions, s.ocr, s.aiThreads)
        if (want == config) { ocr?.canvasSize = s.ocrCanvas; return }
        closeModels()
        val o = Onnx(models.store, OnnxOptions(threads = s.aiThreads))
        onnx = o
        if (s.captions) blip = BlipCaptioner(o, tokenizer)
        if (s.ocr) ocr = EasyOcr(o).also { it.canvasSize = s.ocrCanvas }
        minilm = MiniLmEmbedder(o, tokenizer)
        config = want
    }

    /** Closes the models, freeing their memory (about 1.5 GB with all of them open). */
    suspend fun release() = mutex.withLock { closeModels() }

    private fun closeModels() {
        blip?.close(); blip = null
        ocr?.close(); ocr = null
        minilm?.close(); minilm = null
        onnx = null
        config = null
    }

    companion object {
        private const val TAG = "AiEngine"
        /** Where in a video its frames are captioned: a tenth, half and nine tenths in. */
        val VIDEO_FRAMES = listOf(0.1, 0.5, 0.9)

        private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

        fun toBytes(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (x in v) b.putFloat(x)
            return b.array()
        }

        fun toFloats(b: ByteArray): FloatArray {
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { buf.getFloat() }
        }

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
