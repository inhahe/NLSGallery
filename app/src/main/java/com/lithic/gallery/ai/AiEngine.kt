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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private data class Config(val captions: Boolean, val ocr: Boolean, val threads: Int, val gpu: Boolean)

    /** Where the GPU stands for reading text, in this process. */
    enum class Gpu {
        /** Not looked for yet (looking loads its libraries, so it waits until wanted). */
        UNKNOWN,
        /** There is none the text detector runs on: not a Snapdragon, or no QNN plugin. */
        NONE,
        /** There is one: used, unless it fails its check. */
        FOUND,
        /** It failed its check (computed the detector differently from the CPU): the CPU reads text until the app restarts. */
        DIFFERS,
        /** It failed while reading text, or its check could not run three times: the CPU reads text until the app restarts. */
        FAILED,
    }

    private val _gpu = MutableStateFlow(Gpu.UNKNOWN)
    val gpu: StateFlow<Gpu> = _gpu

    /** Whether the GPU has been checked against the CPU this process (once: compiling a check takes seconds). */
    private var gpuChecked = false

    /** Times the check could not run this process. */
    private var gpuCheckFailures = 0

    /** Looks for the GPU, loading its libraries the first time: call it off the main thread. */
    fun lookForGpu() {
        probeGpu(ai.onnxruntime.OrtEnvironment.getEnvironment())
    }

    /** The GPU for sessions in [env], or null; what is found is noted in [gpu] the first time. */
    private fun probeGpu(env: ai.onnxruntime.OrtEnvironment): com.lithic.gallery.core.ai.Accelerator? =
        SnapdragonGpu.accelerator(env).also { _gpu.compareAndSet(Gpu.UNKNOWN, if (it != null) Gpu.FOUND else Gpu.NONE) }

    /** Whether describing can run: this phone may run the AI, and the models for these settings are downloaded. */
    fun ready(s: Settings): Boolean =
        (s.captions || s.ocr) && allowed(s) && ModelManager.needed(s.captions, s.ocr).all(models::isReady)

    /** Whether this phone may run the AI: it has the memory, or the user asked for it anyway. */
    fun allowed(s: Settings): Boolean = Hardware.memory(context).enough || s.aiOnWeakPhone

    /** What [describe] (or [embedMissing]) did with a file. */
    sealed interface Outcome {
        /** Its description saved, with its embedding unless MiniLM failed (then made later). */
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
        /** The models could not be opened (removed during the run, say): nothing about the file. */
        data object ModelsUnavailable : Outcome
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
     * or with [again] all of those parts afresh (and a file set aside as failed is tried too), and
     * saves the description with the embedding of its text; until then the description stays as
     * it was. Only the models the asked-for parts need are kept open.
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
    suspend fun describe(
        item: MediaEntity, s: Settings, captions: Boolean = s.captions, ocr: Boolean = s.ocr, again: Boolean = false,
    ): Outcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            val existing = dao.description(item.aiKey)
            if (existing?.error != null && !again) return@withContext Outcome.Skipped
            val wantCaption = captions && (again || existing?.caption == null)
            val wantOcr = ocr && (again || existing?.ocr == null)
            if (!wantCaption && !wantOcr) return@withContext Outcome.Skipped
            var manual = existing?.manualCaption ?: ""
            suspend fun fail(error: String): Outcome {
                dao.upsertDescription(DescriptionEntity(item.aiKey, existing?.caption, existing?.ocr, manual,
                    writeTime(), error, existing?.modelFailures ?: 0, existing?.lastModelFailure))
                return Outcome.Failed
            }

            val t0 = System.nanoTime()
            val frames: List<RgbImage> = try {
                frames(item, captions = wantCaption).ifEmpty { throw IllegalStateException("no frames could be read") }
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

            // Opening them failing says nothing about this file: no note on it.
            if (modelWork("opening the models") { open(captions, ocr, s) } is Attempt.Failed) {
                return@withContext Outcome.ModelsUnavailable
            }
            var tCaption = 0L
            var tOcr = 0L
            val made = modelWork("file ${item.id}") {
                var caption = existing?.caption
                var text = existing?.ocr
                if (wantCaption) {
                    val t = System.nanoTime()
                    caption = captionOf(frames)
                    tCaption = ms(t)
                }
                if (wantOcr) {
                    val t = System.nanoTime()
                    text = textOf(item, frames, s)
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
     * Saves [description] with the embedding of its text. MiniLM failing does not hold up the
     * description (MiniLM reads only its text): it is saved without one (any older embedding, now
     * of other text, removed with it) for [embedMissing] to make, and the models are closed, so
     * a shortage of memory that made it fail does not carry over to the next file.
     */
    private suspend fun save(description: DescriptionEntity, mediaId: Long) {
        when (val e = modelWork("the embedding of file $mediaId") { embed(description.hash, description.caption ?: "", description.ocr ?: "") }) {
            is Attempt.Done -> dao.saveDescribed(description, e.value)
            is Attempt.Failed -> {
                dao.saveUnembedded(description)
                closeModels()
            }
        }
    }

    /**
     * Makes the embedding description [d] lacks (MiniLM failed when it was saved), if it has text
     * and is still the same when it is made. Only MiniLM is opened.
     */
    suspend fun embedMissing(d: DescriptionEntity, s: Settings): Outcome = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (modelWork("opening MiniLM") { open(captions = false, ocr = false, s) } is Attempt.Failed) {
                return@withContext Outcome.ModelsUnavailable
            }
            when (val e = modelWork("a missing embedding") { embed(d.hash, d.caption ?: "", d.ocr ?: "") }) {
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
     * Counts a run in which the caption or text models failed on file [key] while they were seen
     * to work on other input; the [DescriptionEntity.MAX_MODEL_FAILURES]th sets it aside as failed
     * with [message]. Only while the failure [describe] noted is current. Whether it was counted.
     */
    suspend fun recordModelFailure(key: String, message: String): Boolean = mutex.withLock {
        dao.countModelFailure(key, message, writeTime(), DescriptionEntity.MAX_MODEL_FAILURES)
    }

    /** A pass's models and what they make; [phase] names it in progress and notifications. */
    enum class Part(val phase: String) { CAPTIONS("captions"), TEXT("text"), EMBEDDINGS("embeddings") }

    /**
     * Whether the models for [part] still work on input they handled before, like [like] (a file
     * they just failed on). Captions: a file whose caption they made (BLIP sees every picture at
     * 384x384, so any will do). Text: one whose text they read at [like]'s detail or more (the
     * nearest first: more detail needs more memory, so it proves at least as much).
     * Embeddings: text as long as MiniLM reads. Up to [REFERENCE_TRIES] files are tried, newest
     * first, passing over any that cannot be read now. True or false is the models' answer; null
     * means there was nothing to try them on. Nothing is saved.
     */
    suspend fun modelsStillWork(part: Part, like: MediaEntity?, s: Settings): Boolean? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (part == Part.EMBEDDINGS) {
                return@withContext modelWork("the embedding probe") { open(captions = false, ocr = false, s); minilm!!.embed(PROBE_TEXT) } is Attempt.Done
            }
            val candidates = when (part) {
                Part.CAPTIONS -> dao.captionedReferences(REFERENCE_TRIES)
                else -> {
                    val canvas = like?.let { s.textDetail.canvasFor(it.folderCategory, it.isVideo) } ?: 0
                    fun canvasOf(m: MediaEntity) = s.textDetail.canvasFor(m.folderCategory, m.isVideo)
                    dao.readReferences(200)
                        .filter { canvasOf(it) >= canvas }
                        .sortedBy { canvasOf(it) } // stable: newest first within each detail
                        .take(REFERENCE_TRIES)
                }
            }
            for (reference in candidates) {
                val frames = try {
                    frames(reference, captions = part == Part.CAPTIONS)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    continue // gone or unreadable now: the next one
                } catch (e: OutOfMemoryError) {
                    return@withContext false // no memory even to read a picture they handled
                }
                if (frames.isEmpty()) continue
                val ran = modelWork("the ${part.phase} probe") {
                    open(captions = part == Part.CAPTIONS, ocr = part == Part.TEXT, s)
                    if (part == Part.CAPTIONS) captionOf(frames) else textOf(reference, frames, s)
                }
                return@withContext ran is Attempt.Done
            }
            null
        }
    }

    /**
     * The frames the models see of [item]: a photo whole; a video's frames at [VIDEO_FRAMES] for
     * [captions], else its middle one alone (text is read from that), or, if that one cannot be
     * extracted, whichever of the others can. Empty when a video yields none.
     */
    private fun frames(item: MediaEntity, captions: Boolean): List<RgbImage> = when {
        !item.isVideo -> listOf(MediaDecoding.decodeImage(context.contentResolver, item.contentUri))
        captions -> MediaDecoding.decodeVideoFrames(context, item.contentUri, VIDEO_FRAMES)
        else -> MediaDecoding.decodeVideoFrames(context, item.contentUri, listOf(VIDEO_FRAMES[1]))
            .ifEmpty { MediaDecoding.decodeVideoFrames(context, item.contentUri, VIDEO_FRAMES) }
    }

    /** The caption of [frames]: a video's, each sampled frame's caption, repeats dropped. */
    private fun captionOf(frames: List<RgbImage>): String =
        frames.map { blip!!.caption(it) }.filter { it.isNotBlank() }.distinct().joinToString("; ")

    /** The text in [item]'s middle frame (its one, for a photo), read at its detail. */
    private fun textOf(item: MediaEntity, frames: List<RgbImage>, s: Settings): String {
        easyOcr!!.canvasSize = s.textDetail.canvasFor(item.folderCategory, item.isVideo)
        return easyOcr!!.text(frames[frames.size / 2])
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
        val want = Config(captions, ocr, s.aiThreads, ocr && s.gpuText)
        if (want == config) return
        closeModels()
        val o = Onnx(models.store, OnnxOptions(threads = s.aiThreads))
        onnx = o
        if (captions) blip = BlipCaptioner(o, tokenizer)
        if (ocr) {
            // Kept before the GPU is tried, so closeModels closes it if trying throws.
            val e = EasyOcr(o).also { easyOcr = it }
            e.maxTilePixels = maxTilePixels()
            if (want.gpu) useGpu(e, o)
        }
        minilm = MiniLmEmbedder(o, tokenizer)
        config = want
    }

    /** The phone's memory, in bytes. */
    private fun totalMemory(): Long {
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem
    }

    /**
     * The most pixels the text detector runs at once on the CPU: it needs ~1.3 KB per pixel
     * (3.5 GB for a whole screenshot), so phones with less memory cut images into more, smaller tiles.
     */
    private fun maxTilePixels(): Long = if (totalMemory() >= 7L shl 30) EasyOcr.DEFAULT_MAX_TILE_PIXELS else 1_200_000L

    /** Hands [ocr]'s text detector to the GPU, if there is one and it computes it right. */
    private fun useGpu(ocr: EasyOcr, onnx: Onnx) {
        if (_gpu.value == Gpu.DIFFERS || _gpu.value == Gpu.FAILED) return
        val gpu = probeGpu(onnx.env) ?: return
        if (!gpuChecked) {
            // Noted only once answered. A check that cannot run (short of memory a moment, say)
            // leaves the CPU reading text this time and is tried again with the models' next
            // opening; failing so three times, the GPU is given up for the run.
            val agrees = try {
                ocr.acceleratorAgrees(gpu)
            } catch (e: Exception) {
                gpuCheckFailures++
                Log.w(TAG, "the GPU check could not run ($gpuCheckFailures): text is read on the CPU for now", e)
                if (gpuCheckFailures >= 3) _gpu.value = Gpu.FAILED
                return
            }
            gpuChecked = true
            if (!agrees) {
                Log.w(TAG, "the GPU computes the text detector differently from the CPU: text is read on the CPU")
                _gpu.value = Gpu.DIFFERS
                return
            }
            Log.i(TAG, "the GPU computes the text detector as the CPU does")
        }
        ocr.acceleratedCachePixels = gpuPixels(totalMemory())
        ocr.acceleratedMaxTilePixels = ocr.acceleratedCachePixels
        ocr.accelerator = gpu
        ocr.onAcceleratorFailed = { e ->
            Log.w(TAG, "the GPU failed; text is read on the CPU until the app restarts", e)
            _gpu.value = Gpu.FAILED
        }
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

        /**
         * The input pixels the GPU's text detector sessions may hold open together on a phone
         * with [totalMemory] bytes (what Android reports: about 10.9 GiB for 12 GB, 7.3 for 8 GB).
         * A session holds ~1.8 KB per pixel (float16; on an Adreno 730, 1.70-1.83 KB): on a 12 GB
         * phone the CPU's whole-screenshot budget, 4.9 GB; 2.9 GB on an 8 GB one. One tile may
         * use it all, as describing meets the shapes in runs (screenshots, then downloads...).
         */
        fun gpuPixels(totalMemory: Long): Long = when {
            totalMemory >= 10L shl 30 -> EasyOcr.DEFAULT_MAX_TILE_PIXELS
            totalMemory >= 7L shl 30 -> 1_600_000L
            else -> 800_000L
        }

        /** How many files a probe tries before it gives no answer. */
        private const val REFERENCE_TRIES = 3

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
