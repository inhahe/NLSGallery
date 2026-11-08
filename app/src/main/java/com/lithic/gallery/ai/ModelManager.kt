package com.lithic.gallery.ai

import android.content.Context
import com.lithic.gallery.core.ai.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** Where one model's weights come from (assets/models/models.json, written by tools/export_models.py). */
@Serializable
data class ModelSource(
    /** The weight file's name, which the graphs' external-data references name. */
    val file: String,
    /** Its publisher's URL (HuggingFace, or EasyOCR's GitHub release). */
    val url: String,
    val size: Long,
    val sha256: String,
    val graphs: List<String>,
    /** When [url] is a zip: the entry holding [file]. */
    val member: String? = null,
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data object Verifying : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The AI models' files: the ONNX graphs, which ship in the app's assets, and the official weight
 * files they read, which are downloaded once (about 1.2 GB) into the app's own folder on shared
 * storage (Android/data/<app>/files/models: removed with the app, and reachable over USB, so
 * push-models.bat can copy them from a computer instead).
 */
class ModelManager(private val context: Context) {
    val sources: Map<String, ModelSource> = context.assets.open("models/models.json").use {
        Json.decodeFromString<Map<String, ModelSource>>(it.readBytes().decodeToString())
    }
    // Made by the app itself: a folder adb creates first belongs to the shell user, and the app
    // could not read what is copied into it (push-models.bat waits for this one).
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    private val _state = MutableStateFlow(sources.keys.associateWith { initialState(it) })
    val state: StateFlow<Map<String, ModelState>> = _state

    /** Graphs from the app's assets, weights from its models folder. */
    val store: ModelStore = object : ModelStore {
        override fun graph(model: String, name: String): ByteArray =
            context.assets.open("models/$model/$name").use { it.readBytes() }
        override fun weightsDir(model: String): File = File(root, model)
    }

    fun isReady(key: String) = _state.value[key] == ModelState.Ready

    fun totalSize(keys: Collection<String>) = keys.sumOf { sources.getValue(it).size }

    private fun weights(key: String) = File(File(root, key), sources.getValue(key).file)

    /**
     * A file that is present, of the right size, and was verified once, is ready. One of the
     * right size not yet verified (copied in over USB) is checked by [verifyCopied].
     */
    private fun initialState(key: String): ModelState {
        val f = weights(key)
        return when {
            f.length() != sources.getValue(key).size -> ModelState.Missing
            verifiedMarker(key).exists() -> ModelState.Ready
            else -> ModelState.Verifying
        }
    }

    /**
     * Checks weight files that arrived without being downloaded (push-models.bat), including
     * ones copied in while the app was running.
     */
    suspend fun verifyCopied() = withContext(Dispatchers.IO) {
        for (key in sources.keys) {
            if (_state.value[key] == ModelState.Missing) set(key, initialState(key))
        }
        for (key in sources.keys) {
            if (_state.value[key] != ModelState.Verifying || !weights(key).exists()) continue
            val sha = sha256(weights(key))
            if (sha == sources.getValue(key).sha256) {
                verifiedMarker(key).writeText(sha)
                set(key, ModelState.Ready)
            } else {
                set(key, ModelState.Failed("the copied file does not match (SHA-256 $sha)"))
            }
        }
    }

    private fun verifiedMarker(key: String) = File(File(root, key), sources.getValue(key).file + ".verified")

    private fun set(key: String, s: ModelState) = _state.update { it + (key to s) }

    /**
     * Downloads every model in [keys] that is not ready, one after another, resuming partial
     * downloads, and verifies each against its SHA-256. Throws on failure (the model is marked
     * failed), and stops at cancellation.
     *
     * @param progress called with bytes done and total across all of [keys].
     */
    suspend fun download(keys: Collection<String>, progress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val todo = keys.filter { !isReady(it) }
            val total = todo.sumOf { sources.getValue(it).size }
            var before = 0L
            for (key in todo) {
                try {
                    downloadOne(key) { done -> progress(before + done, total) }
                } catch (e: IOException) {
                    set(key, ModelState.Failed(e.message ?: e.javaClass.simpleName))
                    throw e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Stopped (cancelled, or the network went): the partial file stays to resume from.
                    set(key, ModelState.Missing)
                    throw e
                }
                before += sources.getValue(key).size
            }
        }

    private suspend fun downloadOne(key: String, progress: (Long) -> Unit) {
        val src = sources.getValue(key)
        val dir = File(root, key).apply { mkdirs() }
        val dest = weights(key)
        val part = File(dir, src.file + ".part")
        // What the URL serves: the file itself, or a zip holding it (its size unknown up front).
        val expected = if (src.member == null) src.size else -1L
        fetch(src.url, part, expected) { done, total ->
            set(key, ModelState.Downloading(done, if (total > 0) total else src.size))
            progress(if (src.member == null) done else minOf(done, src.size))
        }
        set(key, ModelState.Verifying)
        val tmp = File(dir, src.file + ".tmp")
        if (src.member != null) {
            extract(part, src.member, tmp)
            part.delete()
        } else {
            part.renameTo(tmp)
        }
        val sha = sha256(tmp)
        if (tmp.length() != src.size || sha != src.sha256) {
            tmp.delete()
            throw IOException("${src.file}: downloaded file does not match (SHA-256 $sha)")
        }
        dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("could not move ${tmp.name} into place")
        verifiedMarker(key).writeText(sha)
        set(key, ModelState.Ready)
    }

    /** Downloads [url] into [part], continuing where a previous attempt stopped. */
    private suspend fun fetch(url: String, part: File, expected: Long, progress: (Long, Long) -> Unit) {
        var have = if (part.exists()) part.length() else 0L
        if (expected > 0 && have == expected) return
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 416 && have > 0) {
                // Nothing left to send: the part file is complete if it is as long as the file.
                val full = conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (full == have) { progress(have, have); return }
                part.delete()
                throw IOException("the partial download did not match; it will start over")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append) {
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from $url")
                have = 0
            }
            val length = conn.contentLengthLong
            val total = if (length >= 0) have + length else expected
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport >= (1 shl 20)) { progress(have, total); lastReport = have }
                    }
                }
            }
            progress(have, total)
            if (total > 0 && have != total) throw IOException("download ended early ($have of $total bytes)")
        } finally {
            conn.disconnect()
        }
    }

    private fun extract(zip: File, member: String, dest: File) {
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: throw IOException("$member is not in ${zip.name}")
                if (e.name == member || e.name.endsWith("/$member")) {
                    dest.outputStream().use { z.copyTo(it, 1 shl 16) }
                    return
                }
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Deletes [key]'s downloaded files. */
    fun delete(key: String) {
        File(root, key).deleteRecursively()
        set(key, ModelState.Missing)
    }

    /** Bytes the downloaded models take. */
    fun bytesOnDisk(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val BLIP = "blip"
        const val MINILM = "minilm"
        const val CRAFT = "craft"
        const val RECOGNIZER = "english_g2"

        /** The models the given features need. Semantic search always wants MiniLM. */
        fun needed(captions: Boolean, ocr: Boolean): List<String> = buildList {
            if (captions) add(BLIP)
            if (ocr) { add(CRAFT); add(RECOGNIZER) }
            add(MINILM)
        }
    }
}
