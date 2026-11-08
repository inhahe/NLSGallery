package com.lithic.gallery.ai

import android.content.Context
import com.lithic.gallery.core.ai.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Held while a model's file is downloaded, checked or deleted, so these never meet on one file. */
    private val locks = sources.keys.associateWith { Mutex() }

    /** Models a download is fetching: [verifyCopied] leaves them alone rather than wait minutes. */
    private val downloading: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The weight file (length, modification time) each Failed model failed with. */
    private val failedWith = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

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
     * A file that is present, of the right size, and verified as it is now (its marker records
     * its hash, length and modification time), is ready. One of the right size not verified as it
     * is (copied in over USB, or copied over a verified one) is checked by [verifyCopied].
     */
    private fun initialState(key: String): ModelState = when {
        weights(key).length() != sources.getValue(key).size -> ModelState.Missing
        verifiedAsIs(key) -> ModelState.Ready
        else -> ModelState.Verifying
    }

    private fun verifiedAsIs(key: String): Boolean {
        val marker = verifiedMarker(key)
        val s = stamp(key) ?: return false
        return marker.exists() && marker.readText() == markerText(sources.getValue(key).sha256, s)
    }

    private fun markerText(sha: String, stamp: Pair<Long, Long>) = "$sha\n${stamp.first}\n${stamp.second}\n"

    /**
     * Checks weight files that arrived without being downloaded (push-models.bat), including
     * ones copied in while the app was running, over one that failed, or over a verified one.
     * A model being downloaded is left alone; one being checked is waited for, so a caller sees
     * the verdict.
     */
    suspend fun verifyCopied() = withContext(Dispatchers.IO) {
        for (key in sources.keys) {
            if (key in downloading) continue
            locks.getValue(key).withLock {
                val s = _state.value[key]
                val look = when (s) {
                    ModelState.Missing -> true
                    is ModelState.Failed -> stamp(key) != failedWith[key]
                    ModelState.Ready -> !verifiedAsIs(key)
                    else -> false
                }
                if (look) set(key, initialState(key))
                if (_state.value[key] == ModelState.Verifying) check(key)
            }
        }
    }

    /** Hashes [key]'s weight file: Ready if it is the published one, else Failed. */
    private fun check(key: String): Boolean {
        val sha = try {
            sha256(weights(key))
        } catch (e: IOException) {
            // Removed or replaced while being read: whatever is there now is looked at next time.
            set(key, initialState(key).takeIf { it != ModelState.Verifying } ?: ModelState.Missing)
            return false
        }
        if (sha == sources.getValue(key).sha256) {
            if (!markVerified(key, sha)) return false
            set(key, ModelState.Ready)
            return true
        }
        fail(key, "the copied file does not match (SHA-256 $sha)")
        return false
    }

    /** Records that [key]'s file, as it is now, hashes to [sha]; false if it vanished meanwhile. */
    private fun markVerified(key: String, sha: String): Boolean {
        val s = stamp(key)
        return try {
            if (s == null) throw IOException("gone")
            verifiedMarker(key).writeText(markerText(sha, s))
            true
        } catch (e: IOException) {
            set(key, ModelState.Missing)
            false
        }
    }

    /** The weight file's length and modification time, or null when there is none. */
    private fun stamp(key: String): Pair<Long, Long>? = weights(key).takeIf { it.exists() }?.let { it.length() to it.lastModified() }

    private fun fail(key: String, message: String) {
        stamp(key)?.let { failedWith[key] = it } ?: failedWith.remove(key)
        set(key, ModelState.Failed(message))
    }

    private fun verifiedMarker(key: String) = File(File(root, key), sources.getValue(key).file + ".verified")

    private fun set(key: String, s: ModelState) = _state.update { it + (key to s) }

    /**
     * Downloads every model in [keys] that is not ready, one after another, resuming partial
     * downloads, and verifies each against its SHA-256. A complete file already in place (copied
     * in over USB, not yet checked) is checked instead, and downloaded only if it is not the
     * published one. Throws on failure (the model is marked failed), and stops at cancellation.
     *
     * @param progress called with bytes done and total across all of [keys].
     */
    suspend fun download(keys: Collection<String>, progress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val todo = keys.filter { !isReady(it) }
            val total = todo.sumOf { sources.getValue(it).size }
            var before = 0L
            for (key in todo) {
                downloading.add(key)
                try {
                    locks.getValue(key).withLock {
                        if (isReady(key)) return@withLock
                        // A complete copy is checked rather than fetched again, unless it failed already.
                        if (initialState(key) == ModelState.Verifying && stamp(key) != failedWith[key]) {
                            set(key, ModelState.Verifying)
                            if (check(key)) return@withLock
                        }
                        try {
                            downloadOne(key) { done -> progress(before + done, total) }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            // Stopped (cancelled, or the network went): the partial file stays to resume from.
                            set(key, ModelState.Missing)
                            throw e
                        } catch (e: Exception) {
                            fail(key, e.message ?: e.javaClass.simpleName)
                            throw e
                        }
                    }
                } finally {
                    downloading.remove(key)
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
        if (!markVerified(key, sha)) throw IOException("${src.file} vanished once in place")
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

    /** Deletes [key]'s downloaded files (after a check of them in progress). */
    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        locks.getValue(key).withLock {
            File(root, key).deleteRecursively()
            failedWith.remove(key)
            set(key, ModelState.Missing)
        }
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
