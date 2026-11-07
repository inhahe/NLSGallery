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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Search over every file's description, OCR text, own caption, file name and folder, as
 * thumbsup2's search page does it: keyword ranking (MiniSearch's BM25+, prefix and fuzzy) blended
 * with meaning (MiniLM embeddings) by Reciprocal Rank Fusion.
 *
 * The index is rebuilt from the database by [refresh] (cheap: tokenizing text) whenever files or
 * descriptions change.
 */
class SearchService(
    private val dao: AiDao,
    private val models: ModelManager,
    private val tokenizer: () -> BertTokenizer,
) {
    private val mutex = Mutex()
    private var index = MiniSearch(HybridSearch.FIELDS)
    private var embeddings: List<Pair<Int, FloatArray>> = emptyList()
    /** MiniSearch's int ids ↔ media ids, and each one's date for breaking ties (newest first). */
    private var mediaIds = LongArray(0)
    private var dates = LongArray(0)
    private var embedder: MiniLmEmbedder? = null

    /** A result: the media id and how it was found. */
    data class Result(val mediaId: Long, val score: Double, val semanticOnly: Boolean, val similarity: Float?)

    suspend fun refresh() = withContext(Dispatchers.Default) {
        val rows = dao.searchable().sortedByDescending { it.sortDate }
        val ms = MiniSearch(HybridSearch.FIELDS)
        val ids = LongArray(rows.size)
        val ds = LongArray(rows.size)
        val byKey = HashMap<String, MutableList<Int>>()
        rows.forEachIndexed { i, r ->
            ids[i] = r.id
            ds[i] = r.sortDate
            byKey.getOrPut(r.aiKey) { ArrayList(1) }.add(i)
            ms.add(i, listOf(r.caption ?: "", r.ocr ?: "", r.manualCaption ?: "", r.displayName, r.relativePath + r.displayName))
        }
        val vectors = ArrayList<Pair<Int, FloatArray>>()
        for (e in dao.embeddings(MiniLmEmbedder.MODEL_ID)) {
            val v = AiEngine.toFloats(e.vector)
            byKey[e.hash]?.forEach { vectors.add(it to v) }
        }
        vectors.sortBy { it.first }
        mutex.withLock {
            index = ms
            embeddings = vectors
            mediaIds = ids
            dates = ds
        }
    }

    /**
     * thumbsup2's hybrid search for [query] in [fields]; meaning is only used when the MiniLM
     * model is downloaded.
     */
    suspend fun search(query: String, fields: List<String>): List<Result> = withContext(Dispatchers.Default) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val queryVector = embed(q)
        mutex.withLock {
            val ds = dates
            HybridSearch.search(q, index, fields, queryVector, embeddings) { -ds[it] }.map {
                Result(mediaIds[it.id], it.score, it.semanticOnly, it.similarity)
            }
        }
    }

    private suspend fun embed(text: String): FloatArray? {
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
}
