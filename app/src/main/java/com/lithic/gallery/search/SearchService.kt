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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Search over every file's description, OCR text, own caption, file name and folder, as
 * thumbsup2's search page does it: keyword ranking (MiniSearch's BM25+, prefix and fuzzy) blended
 * with meaning (MiniLM embeddings) by Reciprocal Rank Fusion.
 *
 * The index follows the database: the first search after [invalidate] compares every file's
 * searchable text with what was indexed for it and re-indexes only the files that changed, so
 * describing (a change every couple of seconds) costs a query and a few documents, not a
 * re-tokenization of the whole library.
 */
open class SearchService(
    private val dao: AiDao,
    private val models: ModelManager,
    private val tokenizer: () -> BertTokenizer,
) {
    /** Guards the index and everything below it; never held while the database is read. */
    private val mutex = Mutex()
    private val index = MiniSearch(HybridSearch.FIELDS)
    /** What was indexed for each file, by media id. */
    private val docs = HashMap<Long, Doc>()
    /** MiniSearch's int ids ↔ media ids, and each one's date for breaking ties (newest first). */
    private val mediaIds = HashMap<Int, Long>()
    private val dates = HashMap<Int, Long>()
    private var nextId = 0
    private val vectors = HashMap<Int, FloatArray>()
    /** [vectors] newest first, the order equally near ones keep; null after a change. */
    private var ordered: List<Pair<Int, FloatArray>>? = null
    private var embedder: MiniLmEmbedder? = null

    /** One refresh at a time, so an older reading of the database never lands after a newer one. */
    private val refreshing = Mutex()
    /** Bumped by [invalidate]; the index is current when [built] equals it. */
    private val wanted = AtomicLong(1)
    @Volatile private var built = 0L

    /** A file as indexed: its MiniSearch id, a hash of its fields and key, its date. */
    private class Doc(val id: Int, val hash: Long, var date: Long)

    /** Files or descriptions changed: the next search brings the index up to date first. */
    fun invalidate() {
        wanted.incrementAndGet()
    }

    /** A result: the media id and how it was found. */
    data class Result(val mediaId: Long, val score: Double, val semanticOnly: Boolean, val similarity: Float?)

    /** The changes a refresh found: files to (re-)index with their fields, files gone, dates moved. */
    private class Plan(
        val changed: List<Pair<MediaWithDescription, List<String>>>,
        val gone: List<Long>,
        val redated: List<MediaWithDescription>,
    )

    private suspend fun refreshIfStale() = refreshing.withLock {
        if (built == wanted.get()) return@withLock
        val target = wanted.get()
        val rows = dao.searchable()
        val plan = mutex.withLock { plan(rows) }
        val keys = plan.changed.mapTo(HashSet()) { it.first.aiKey }
        val fresh = embeddingsOf(keys)
        mutex.withLock {
            apply(plan, fresh)
            built = target
        }
    }

    /** What differs between [rows] (the database now) and the index. */
    private fun plan(rows: List<MediaWithDescription>): Plan {
        val seen = HashSet<Long>(rows.size * 2)
        val changed = ArrayList<Pair<MediaWithDescription, List<String>>>()
        val redated = ArrayList<MediaWithDescription>()
        for (r in rows) {
            seen.add(r.id)
            val values = listOf(r.caption ?: "", r.ocr ?: "", r.manualCaption ?: "", r.displayName, r.relativePath + r.displayName)
            val doc = docs[r.id]
            when {
                doc == null || doc.hash != hash(r.aiKey, values) -> changed.add(r to values)
                doc.date != r.sortDate -> redated.add(r)
            }
        }
        // Newest first, as a fresh index adds them: MiniSearch keeps that order for equal scores.
        changed.sortWith(compareByDescending<Pair<MediaWithDescription, List<String>>> { it.first.sortDate }.thenByDescending { it.first.id })
        return Plan(changed, docs.keys.filter { it !in seen }, redated)
    }

    private fun apply(plan: Plan, fresh: Map<String, FloatArray>) {
        for (mediaId in plan.gone) {
            val doc = docs.remove(mediaId) ?: continue
            index.remove(doc.id)
            mediaIds.remove(doc.id)
            dates.remove(doc.id)
            vectors.remove(doc.id)
        }
        for ((r, values) in plan.changed) {
            val id = docs[r.id]?.id ?: nextId++
            index.add(id, values)
            docs[r.id] = Doc(id, hash(r.aiKey, values), r.sortDate)
            mediaIds[id] = r.id
            dates[id] = r.sortDate
            val v = fresh[r.aiKey]
            if (v != null) vectors[id] = v else vectors.remove(id)
        }
        for (r in plan.redated) {
            val doc = docs[r.id] ?: continue
            doc.date = r.sortDate
            dates[doc.id] = r.sortDate
        }
        if (plan.gone.isNotEmpty() || plan.changed.isNotEmpty() || plan.redated.isNotEmpty()) ordered = null
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
     * thumbsup2's hybrid search for [query] in [fields]; meaning is only used when the MiniLM
     * model is downloaded.
     */
    suspend fun search(query: String, fields: List<String>): List<Result> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        if (built != wanted.get()) refreshIfStale()
        val queryVector = embedQuery(q)
        mutex.withLock {
            val near = ordered ?: vectors.entries
                .sortedWith(compareByDescending<Map.Entry<Int, FloatArray>> { dates.getValue(it.key) }.thenByDescending { mediaIds.getValue(it.key) })
                .map { it.key to it.value }
                .also { ordered = it }
            HybridSearch.search(q, index, fields, queryVector, near) { -dates.getValue(it) }
                // thumbsup2 lists its 200 nearest by meaning however weak; a gallery would then
                // "match" everything. Drop the meaning-only ones that are unrelated (unchanged order).
                .filter { !it.semanticOnly || (it.similarity ?: 0f) >= MIN_SIMILARITY }
                .map { Result(mediaIds.getValue(it.id), it.score, it.semanticOnly, it.similarity) }
        }
    }

    /** [text]'s MiniLM embedding, or null without the model (tests give their own). */
    internal open suspend fun embedQuery(text: String): FloatArray? {
        if (!models.isReady(ModelManager.MINILM)) return null
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

        /** More changed descriptions than this: their embeddings are read with one full scan. */
        private const val WHOLE_TABLE = 4000

        /** 64-bit FNV-1a over a file's description key and searchable fields. */
        private fun hash(key: String, values: List<String>): Long {
            var h = -0x340d631b7bdddcdbL
            fun mix(s: String) {
                for (ch in s) {
                    h = (h xor ch.code.toLong()) * 0x100000001b3L
                }
                h = (h xor 0x1fL) * 0x100000001b3L // a separator, so "ab"+"c" and "a"+"bc" differ
            }
            mix(key)
            values.forEach(::mix)
            return h
        }
    }
}
