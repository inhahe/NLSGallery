package com.lithic.gallery.ai

import android.content.Context
import android.util.Log
import com.lithic.gallery.core.ai.BlipCaptioner
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.OnnxOptions
import com.lithic.gallery.core.ai.embeddingText
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
    private var easyOcr: EasyOcr? = null
    private var minilm: MiniLmEmbedder? = null

    val tokenizer: BertTokenizer by lazy {
        BertTokenizer(context.assets.open("models/vocab.txt").bufferedReader().readLines())
    }

    private data class Config(val captions: Boolean, val ocr: Boolean, val threads: Int)

    /** Whether the models for these settings are downloaded. */
    fun ready(s: Settings): Boolean =
        (s.captions || s.ocr) && ModelManager.needed(s.captions, s.ocr).all(models::isReady)

    /**
     * Makes the parts of [item]'s description it lacks among those asked for ([captions], [ocr]),
     * and saves the description and its embedding. A file that opens but cannot be decoded is
     * saved with its error, so it is not retried until it changes; one that cannot be opened now
     * is left as it was, for the next run. Only the models the asked-for parts need are kept open.
     */
    suspend fun describe(item: MediaEntity, s: Settings, captions: Boolean = s.captions, ocr: Boolean = s.ocr) = mutex.withLock {
        withContext(Dispatchers.Default) {
            open(captions, ocr, s)
            val existing = dao.description(item.aiKey)?.takeIf { it.error == null }
            val wantCaption = captions && existing?.caption == null
            val wantOcr = ocr && existing?.ocr == null
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
                        easyOcr!!.canvasSize = s.textDetail.canvasFor(item.folderCategory, isVideo = true)
                        text = easyOcr!!.text(frames[frames.size / 2])
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
                        easyOcr!!.canvasSize = s.textDetail.canvasFor(item.folderCategory, isVideo = false)
                        text = easyOcr!!.text(img)
                        tOcr = ms(t)
                    }
                    if (existing == null) manual = MediaDecoding.manualCaption(context.contentResolver, item.contentUri)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!canOpen(item)) {
                    // Gone or out of reach for now (deleted while waiting, card removed, access
                    // revoked): nothing is recorded, so the next run tries it again, and a sync
                    // drops it if it is gone. The exception cannot tell: a missing video and a
                    // corrupt one both make MediaMetadataRetriever throw IllegalArgumentException.
                    Log.i(TAG, "skipped ${item.id}: it cannot be opened now")
                    return@withContext
                }
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
            saveEmbedding(item.aiKey, caption ?: "", text ?: "")
            // Timings only: what the pictures show stays out of the log.
            Log.i(TAG, "described ${item.id}: decode ${tDecode}ms caption ${tCaption}ms ocr ${tOcr}ms")
        }
    }

    /** Whether [item]'s file can be opened for reading at the moment. */
    private fun canOpen(item: MediaEntity): Boolean = try {
        context.contentResolver.openFileDescriptor(item.contentUri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** thumbsup2's embedding of caption and text ([embeddingText]), none when both are empty. */
    private suspend fun saveEmbedding(hash: String, caption: String, text: String) {
        val joined = embeddingText(caption, text) ?: return
        val textHash = sha1(joined)
        if (dao.embedding(hash, MiniLmEmbedder.MODEL_ID)?.textHash == textHash) return
        val v = minilm!!.embed(joined)
        dao.upsertEmbedding(EmbeddingEntity(hash, MiniLmEmbedder.MODEL_ID, toBytes(v), textHash))
    }

    private fun open(captions: Boolean, ocr: Boolean, s: Settings) {
        val want = Config(captions, ocr, s.aiThreads)
        if (want == config) return
        closeModels()
        val o = Onnx(models.store, OnnxOptions(threads = s.aiThreads))
        onnx = o
        if (captions) blip = BlipCaptioner(o, tokenizer)
        if (ocr) easyOcr = EasyOcr(o).also { it.maxTilePixels = maxTilePixels() }
        minilm = MiniLmEmbedder(o, tokenizer)
        config = want
    }

    /**
     * The most pixels the text detector runs at once: it needs ~1.3 KB per pixel (3.5 GB for a
     * whole screenshot), so phones with less memory cut images into more, smaller tiles.
     */
    private fun maxTilePixels(): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return if (mi.totalMem >= 7L shl 30) EasyOcr.DEFAULT_MAX_TILE_PIXELS else 1_200_000L
    }

    /** Closes the models, freeing their memory (about 1.5 GB with all of them open). */
    suspend fun release() = mutex.withLock { closeModels() }

    private fun closeModels() {
        blip?.close(); blip = null
        easyOcr?.close(); easyOcr = null
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
