package com.lithic.gallery.ai

import android.content.Context
import android.util.Log
import com.lithic.gallery.core.ai.BlipCaptioner
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.ai.OnnxOptions
import com.lithic.gallery.core.ai.embeddingText
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import com.lithic.gallery.core.image.RgbImage
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

    /** What [describe] (or [embedMissing]) did with a file. */
    sealed interface Outcome {
        /** Its description (and embedding) saved. */
        data object Described : Outcome
        /** Nothing to do, on record as failed, or not to be opened just now: left as it was. */
        data object Skipped : Outcome
        /** It could not be read (or ran out of memory being read): saved as failed. */
        data object Failed : Outcome
        /**
         * The models failed on it and made nothing ([describe] also notes the time on the file).
         * Whose fault it was shows over the run (see [recordModelFailure]).
         */
        data class ModelsFailed(val message: String) : Outcome
    }

    /** The models' work run by [modelWork]: its result, or how the models failed. */
    private sealed interface Attempt<out T> {
        data class Done<T>(val value: T) : Attempt<T>
        data class Failed(val message: String) : Attempt<Nothing>
    }

    /**
     * Runs [block], the models' work on [what] (opening them included): an exception or running
     * out of memory is their failure, not the file's (the file was read; its image is bounded).
     */
    private inline fun <T> modelWork(what: String, block: () -> T): Attempt<T> = try {
        Attempt.Done(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "the models failed on $what", e)
        Attempt.Failed(e.message ?: e.javaClass.simpleName)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "the models ran out of memory on $what", e)
        Attempt.Failed("out of memory")
    }

    /**
     * Makes the parts of [item]'s description it lacks among those asked for ([captions], [ocr]),
     * and saves the description with the embedding of its text. Only the models the asked-for
     * parts need are kept open.
     *
     * What a failure leaves depends on where it happens. Reading the file: the file is at fault,
     * and the error is saved, so it is not tried again until it changes (a new key) or is reset;
     * but a file that cannot be opened at all just now (deleted while waiting, card removed,
     * access revoked) is left for the next run, and running out of memory reading it is its size.
     * Opening or running the models: nothing is made and the time is noted (runs take the file
     * last); the caller, which sees whether the models work for other files, decides whose fault
     * it was. The embedding failing alone: the description is saved without it, for
     * [embedMissing] to make. Any part made resets the file's model failures.
     */
    suspend fun describe(item: MediaEntity, s: Settings, captions: Boolean = s.captions, ocr: Boolean = s.ocr): Outcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            val existing = dao.description(item.aiKey)
            if (existing?.error != null) return@withContext Outcome.Skipped
            val wantCaption = captions && existing?.caption == null
            val wantOcr = ocr && existing?.ocr == null
            if (!wantCaption && !wantOcr) return@withContext Outcome.Skipped
            var manual = existing?.manualCaption ?: ""
            suspend fun fail(error: String): Outcome {
                dao.upsertDescription(DescriptionEntity(item.aiKey, existing?.caption, existing?.ocr, manual,
                    writeTime(), error, existing?.modelFailures ?: 0, existing?.lastModelFailure))
                return Outcome.Failed
            }

            val t0 = System.nanoTime()
            val frames: List<RgbImage> = try {
                if (item.isVideo) {
                    MediaDecoding.decodeVideoFrames(context, item.contentUri, VIDEO_FRAMES)
                        .ifEmpty { throw IllegalStateException("no frames could be read") }
                } else {
                    listOf(MediaDecoding.decodeImage(context.contentResolver, item.contentUri))
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Whether the file is gone or broken is not in the exception (each decoder reports
                // failures its own way), so the file itself is asked.
                if (!canOpen(item)) {
                    Log.i(TAG, "skipped ${item.id}: it cannot be opened now")
                    return@withContext Outcome.Skipped
                }
                Log.w(TAG, "reading ${item.uri} failed", e)
                return@withContext fail(e.message ?: e.javaClass.simpleName)
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "reading ${item.uri} ran out of memory", e)
                return@withContext fail("out of memory")
            }
            // Read at every attempt (a few milliseconds): a row saved before the file was read
            // (a noted model failure) must not keep it unread.
            manual = if (item.isVideo) MediaDecoding.videoTitle(context, item.contentUri)
            else MediaDecoding.manualCaption(context.contentResolver, item.contentUri)
            val tDecode = ms(t0)

            var tCaption = 0L
            var tOcr = 0L
            val made = modelWork("file ${item.id}") {
                open(captions, ocr, s)
                var caption = existing?.caption
                var text = existing?.ocr
                if (wantCaption) {
                    val t = System.nanoTime()
                    // A video: each sampled frame's caption, repeats dropped, for what it shows over time.
                    caption = frames.map { blip!!.caption(it) }.filter { it.isNotBlank() }.distinct().joinToString("; ")
                    tCaption = ms(t)
                }
                if (wantOcr) {
                    val t = System.nanoTime()
                    easyOcr!!.canvasSize = s.textDetail.canvasFor(item.folderCategory, item.isVideo)
                    text = easyOcr!!.text(frames[frames.size / 2])
                    tOcr = ms(t)
                }
                caption to text
            }
            when (made) {
                is Attempt.Failed -> {
                    dao.noteModelFailure(item.aiKey, manual, writeTime())
                    Outcome.ModelsFailed(made.message)
                }
                is Attempt.Done -> {
                    val (caption, text) = made.value
                    save(DescriptionEntity(item.aiKey, caption, text, manual, writeTime(), null), item.id)
                    // Timings only: what the pictures show stays out of the log.
                    Log.i(TAG, "described ${item.id}: decode ${tDecode}ms caption ${tCaption}ms ocr ${tOcr}ms")
                    Outcome.Described
                }
            }
        }
    }

    /**
     * Saves [description] with the embedding of its text. MiniLM failing is never the file's
     * fault (it reads at most 256 tokens of text): the description is saved without one (any
     * older embedding, now of other text, removed with it) for [embedMissing] to make.
     */
    private suspend fun save(description: DescriptionEntity, mediaId: Long) {
        when (val e = modelWork("the embedding of file $mediaId") { embed(description.hash, description.caption ?: "", description.ocr ?: "") }) {
            is Attempt.Done -> dao.saveDescribed(description, e.value)
            is Attempt.Failed -> dao.saveUnembedded(description)
        }
    }

    /**
     * Makes the embedding description [d] lacks (MiniLM failed when it was saved), if it has text
     * and is still the same when it is made. Only MiniLM is opened.
     */
    suspend fun embedMissing(d: DescriptionEntity, s: Settings): Outcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            when (val e = modelWork("a missing embedding") { open(captions = false, ocr = false, s); embed(d.hash, d.caption ?: "", d.ocr ?: "") }) {
                is Attempt.Failed -> Outcome.ModelsFailed(e.message)
                is Attempt.Done -> {
                    val embedding = e.value ?: return@withContext Outcome.Skipped
                    // Not over a description reset or remade meanwhile (by the user, outside this lock).
                    if (dao.saveEmbeddingIfCurrent(embedding, d, writeTime())) Outcome.Described else Outcome.Skipped
                }
            }
        }
    }

    /**
     * Counts a run in which the models failed on description [key] while they worked on other
     * input. A describing failure (one [describe] noted) counts only while it is current, and the
     * [DescriptionEntity.MAX_MODEL_FAILURES]th sets the file aside as failed with [message]; an
     * embedding failure ([describing] false) never sets it aside (its description is fine), but
     * stops it being tried after as many.
     */
    suspend fun recordModelFailure(key: String, message: String, describing: Boolean) = mutex.withLock {
        dao.countModelFailure(key, message, writeTime(), DescriptionEntity.MAX_MODEL_FAILURES,
            setAside = describing, noted = describing)
    }

    /** What a pass's models are run on: captions, the text in pictures, or embeddings of text. */
    enum class Part { CAPTIONS, TEXT, EMBEDDINGS }

    /**
     * Whether the models for [part] still work on input they handled before, like [like] (a file
     * they just failed on): the latest file of its kind (photo or video, and for text, its folder,
     * so read at the same detail) whose [part] they made; for embeddings, text as long as MiniLM
     * reads. Null when there is no such file. Nothing is saved.
     */
    suspend fun modelsStillWork(part: Part, like: MediaEntity?, s: Settings): Boolean? = mutex.withLock {
        withContext(Dispatchers.Default) {
            val reference = when (part) {
                Part.CAPTIONS -> dao.captionedReference(like?.isVideo ?: false)
                Part.TEXT -> dao.readReference(like?.folder ?: 0, like?.isVideo ?: false)
                Part.EMBEDDINGS -> null
            }
            if (part != Part.EMBEDDINGS && reference == null) return@withContext null
            val frames = if (reference == null) emptyList() else try {
                if (reference.isVideo) MediaDecoding.decodeVideoFrames(context, reference.contentUri, VIDEO_FRAMES)
                else listOf(MediaDecoding.decodeImage(context.contentResolver, reference.contentUri))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext null // the reference itself is gone or unreadable: no answer
            } catch (e: OutOfMemoryError) {
                return@withContext false
            }
            val ran = modelWork("the ${part.name.lowercase()} probe") {
                when (part) {
                    Part.CAPTIONS -> { open(captions = true, ocr = false, s); frames.forEach { blip!!.caption(it) } }
                    Part.TEXT -> {
                        open(captions = false, ocr = true, s)
                        easyOcr!!.canvasSize = s.textDetail.canvasFor(reference!!.folderCategory, reference.isVideo)
                        easyOcr!!.text(frames[frames.size / 2])
                    }
                    Part.EMBEDDINGS -> { open(captions = false, ocr = false, s); minilm!!.embed(PROBE_TEXT) }
                }
            }
            ran is Attempt.Done
        }
    }

    /**
     * A description's describedAt: the time, but later than any given before, so every write
     * changes it (the search index tells changed descriptions by it).
     */
    private fun writeTime(): Long = lastWrite.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
    private val lastWrite = java.util.concurrent.atomic.AtomicLong()

    /** Whether [item]'s file can be opened for reading at the moment. */
    private fun canOpen(item: MediaEntity): Boolean = try {
        context.contentResolver.openFileDescriptor(item.contentUri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /**
     * thumbsup2's embedding of caption and text ([embeddingText]) for description [key]: null when
     * both are empty, or when the stored one is already of this text.
     */
    private suspend fun embed(key: String, caption: String, text: String): EmbeddingEntity? {
        val joined = embeddingText(caption, text) ?: return null
        val textHash = sha1(joined)
        if (dao.embedding(key, MiniLmEmbedder.MODEL_ID)?.textHash == textHash) return null
        return EmbeddingEntity(key, MiniLmEmbedder.MODEL_ID, toBytes(minilm!!.embed(joined)), textHash)
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
        /** Text that fills MiniLM's 256 tokens, the most it reads: as heavy as any description. */
        private val PROBE_TEXT = List(300) { "photograph" }.joinToString(" ")

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
