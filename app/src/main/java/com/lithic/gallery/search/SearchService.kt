package com.lithic.gallery.search

import android.util.Log
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.Onnx
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.core.search.MiniSearch
import com.lithic.gallery.core.text.BertTokenizer
import com.lithic.gallery.data.AiDao
import com.lithic.gallery.data.MediaWithDescription
import com.lithic.gallery.data.SearchSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Search over every file's description, OCR text, own caption, file name and folder, as
 * thumbsup2's search page does it: keyword ranking (MiniSearch's BM25+, prefix and fuzzy) blended
 * with meaning (MiniLM embeddings) by Reciprocal Rank Fusion.
 *
 * The index follows the database: the first search after [invalidate] reads every file's
 * [SearchSignature] and re-indexes only the files whose signature changed, reading their text and
 * embeddings alone. A description written while a refresh reads changes its signature again, so
 * the next refresh corrects whatever this one read in between.
 */
open class SearchService(
    private val dao: AiDao,
    private val models: ModelManager,
    private val tokenizer: () -> BertTokenizer,
    /** Whether search by meaning may run (the phone may run the AI). */
    private val meaningAllowed: suspend () -> Boolean = { true },
) {
    /** Guards the index and the documents; never held while the database is read. */
    private val mutex = Mutex()
    private val index = MiniSearch(HybridSearch.FIELDS)

    /** A file as indexed: its MiniSearch id, its signature's hash, its date, its embedding. */
    private class Doc(val id: Int, val mediaId: Long, var signature: Long, var date: Long, var vector: FloatArray?)

    private val byMedia = HashMap<Long, Doc>()
    private val byId = HashMap<Int, Doc>()
    private var nextId = 0
    /** The embedded documents in [newestFirst] order, which equally near ones keep; null after a change. */
    private var nearest: List<Pair<Int, FloatArray>>? = null
    private var embedder: MiniLmEmbedder? = null

    /**
     * The order equal scores and equal meanings keep: newest first, then the larger media id. It is
     * total, so the order files were indexed in never shows.
     */
    private val newestFirst = compareByDescending<Doc> { it.date }.thenByDescending { it.mediaId }
    private val tieBreak = Comparator<Int> { a, b -> newestFirst.compare(byId.getValue(a), byId.getValue(b)) }

    /**
     * Refreshes run here, one at a time, so a search cancelled (the user typed on) leaves the
     * refresh it waited for running for the next one.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshing: Deferred<Unit>? = null
    /** Bumped by [invalidate]; the index reflects the database as of [built]. */
    private val wanted = AtomicLong(1)
    @Volatile private var built = 0L

    /** Files or descriptions changed: the next search brings the index up to date first. */
    fun invalidate() {
        wanted.incrementAndGet()
    }

    /** A result: the media id and how it was found. */
    data class Result(val mediaId: Long, val score: Double, val semanticOnly: Boolean, val similarity: Float?)

    /** Waits until the index is at least as new as the database was when this was called. */
    private suspend fun awaitCurrent() {
        val need = wanted.get()
        while (built < need) {
            val job = synchronized(this) {
                refreshing?.takeIf { it.isActive } ?: scope.async { refresh() }.also { refreshing = it }
            }
            job.await()
        }
    }

    /** The changes a refresh found: files to (re-)index with their signatures' hashes, files gone, dates moved. */
    private class Plan(val changed: Map<Long, Long>, val gone: List<Long>, val redated: List<SearchSignature>)

    private suspend fun refresh() {
        val target = wanted.get()
        val signatures = dao.searchSignatures(MiniLmEmbedder.MODEL_ID)
        val plan = mutex.withLock { plan(signatures) }
        val ids = plan.changed.keys
        val rows = when {
            ids.isEmpty() -> emptyList()
            ids.size > WHOLE_TABLE -> dao.searchable().filter { it.id in ids }
            else -> ids.chunked(900).flatMap { dao.searchable(it) }
        }
        val vectors = embeddingsOf(rows.mapTo(HashSet()) { it.aiKey })
        mutex.withLock {
            apply(plan, rows, vectors)
            if (target > built) built = target
        }
    }

    /** What differs between [signatures] (the database now) and the index. */
    private fun plan(signatures: List<SearchSignature>): Plan {
        val seen = HashSet<Long>(signatures.size * 2)
        val changed = HashMap<Long, Long>()
        val redated = ArrayList<SearchSignature>()
        for (s in signatures) {
            seen.add(s.id)
            val h = hash(s)
            val doc = byMedia[s.id]
            when {
                doc == null || doc.signature != h -> changed[s.id] = h
                doc.date != s.sortDate -> redated.add(s)
            }
        }
        return Plan(changed, byMedia.keys.filter { it !in seen }, redated)
    }

    private fun apply(plan: Plan, rows: List<MediaWithDescription>, vectors: Map<String, FloatArray>) {
        for (mediaId in plan.gone) {
            val doc = byMedia.remove(mediaId) ?: continue
            byId.remove(doc.id)
            index.remove(doc.id)
        }
        // A file deleted between the reads has no row: the next refresh, which its deletion
        // asked for, takes it out.
        for (r in rows) {
            val doc = byMedia[r.id] ?: Doc(nextId++, r.id, 0, 0, null).also { byMedia[r.id] = it; byId[it.id] = it }
            index.add(doc.id, listOf(r.caption ?: "", r.ocr ?: "", r.manualCaption ?: "", r.displayName, r.relativePath + r.displayName))
            doc.signature = plan.changed.getValue(r.id)
            doc.date = r.sortDate
            doc.vector = vectors[r.aiKey]
        }
        for (s in plan.redated) byMedia[s.id]?.date = s.sortDate
        if (plan.gone.isNotEmpty() || rows.isNotEmpty() || plan.redated.isNotEmpty()) nearest = null
    }

    /** The stored embeddings of descriptions [keys], by key. */
    private suspend fun embeddingsOf(keys: Set<String>): Map<String, FloatArray> {
        if (keys.isEmpty()) return emptyMap()
        val rows = if (keys.size > WHOLE_TABLE) {
            dao.embeddings(MiniLmEmbedder.MODEL_ID).filter { it.hash in keys }
        } else {
            keys.chunked(900).flatMap { dao.embeddings(MiniLmEmbedder.MODEL_ID, it) }
        }
        return rows.associate { it.hash to AiEngine.toFloats(it.vector) }
    }

    /**
     * thumbsup2's hybrid search for [query] in [fields], among the files [accept] takes (by media
     * id; null for all) when it is given: a date, a tab. Meaning is only used when the MiniLM
     * model is downloaded.
     */
    suspend fun search(query: String, fields: List<String>, accept: ((Long) -> Boolean)? = null): List<Result> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        awaitCurrent()
        val queryVector = embedQuery(q)
        mutex.withLock {
            val near = nearest ?: byId.values.filter { it.vector != null }
                .sortedWith(newestFirst)
                .map { it.id to it.vector!! }
                .also { nearest = it }
            HybridSearch.search(q, index, fields, queryVector, near, tieBreak, accept?.let { a -> { id -> a(byId.getValue(id).mediaId) } })
                // thumbsup2 lists its 200 nearest by meaning however weak; a gallery would then
                // "match" everything. Drop the meaning-only ones that are unrelated (unchanged order).
                .filter { !it.semanticOnly || (it.similarity ?: 0f) >= MIN_SIMILARITY }
                .map { Result(byId.getValue(it.id).mediaId, it.score, it.semanticOnly, it.similarity) }
        }
    }

    /** [text]'s MiniLM embedding, or null without the model (tests give their own). */
    internal open suspend fun embedQuery(text: String): FloatArray? {
        if (!meaningAllowed() || !models.isReady(ModelManager.MINILM)) return null
        return mutex.withLock {
            try {
                val e = embedder ?: MiniLmEmbedder(Onnx(models.store), tokenizer()).also { embedder = it }
                e.embed(text)
            } catch (e: Exception) {
                Log.w("SearchService", "query embedding failed", e)
                null
            }
        }
    }

    /** Frees the query embedder (about 90 MB). */
    suspend fun releaseEmbedder() = mutex.withLock {
        embedder?.close()
        embedder = null
    }

    companion object {
        /**
         * Cosine similarity a result found by meaning alone must reach. With MiniLM, captions
         * related to a query score about 0.3-0.6 and unrelated ones about 0.1.
         */
        const val MIN_SIMILARITY = 0.2f

        /** More changed files than this: their rows and embeddings are read with one full scan each. */
        private const val WHOLE_TABLE = 4000

        /** 64-bit FNV-1a over a signature's fields but its date (a date alone moves no text). */
        private fun hash(s: SearchSignature): Long {
            var h = -0x340d631b7bdddcdbL
            fun mix(v: String?) {
                for (ch in v ?: "\u0000") h = (h xor ch.code.toLong()) * 0x100000001b3L
                h = (h xor 0x1fL) * 0x100000001b3L // a separator, so "ab"+"c" and "a"+"bc" differ
            }
            mix(s.aiKey)
            mix(s.displayName)
            mix(s.relativePath)
            mix(s.describedAt?.toString())
            mix(s.textHash)
            return h
        }
    }
}
