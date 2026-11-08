package com.lithic.gallery.core.search

/**
 * thumbsup2's hybrid search (src/website/search-assets/search.js, client mode): the best 200
 * keyword (BM25) hits and the 200 nearest embeddings, blended by Reciprocal Rank Fusion with
 * k = 60, best 500 kept. Semantic ranking runs only when the AI fields (description, OCR text)
 * are among those searched, as on thumbsup2's page.
 */
object HybridSearch {
    const val TOP = 200
    const val LIMIT = 500
    const val RRF_K = 60

    /** A result: the document, its fused score, and its cosine similarity when it was embedded. */
    data class Hit(
        val id: Int,
        val score: Double,
        /** Found by meaning alone (no keyword match): thumbsup2 badges these "semantic". */
        val semanticOnly: Boolean,
        val similarity: Float?,
    )

    /**
     * @param keyword the keyword index.
     * @param fields the fields searched.
     * @param queryEmbedding the query's embedding, or null to rank by keywords alone.
     * @param embeddings the documents' embeddings in a stable order (ties keep it).
     * @param tieBreak orders equal keyword scores, or null for insertion order.
     * @param accept the documents that may be found (a date, a tab), or null for all. It narrows
     *   the candidates before they are ranked and cut, so the best 200 are the best among them.
     *   Keyword scores still weigh words by how common they are in the whole index.
     * @param favoured the documents to rank higher (taken at a place the query names in
     *   passing: "beach hawaii"), or null for none. Among those found they count as found by one
     *   more list, fused as the others are, in the order fused so far; none is added. thumbsup2
     *   has no such thing: without it the ranking is thumbsup2's.
     */
    fun search(
        query: String,
        keyword: MiniSearch,
        fields: List<String>,
        queryEmbedding: FloatArray?,
        embeddings: Iterable<Pair<Int, FloatArray>>,
        tieBreak: Comparator<Int>? = null,
        accept: ((Int) -> Boolean)? = null,
        favoured: ((Int) -> Boolean)? = null,
    ): List<Hit> {
        if (query.isBlank() || fields.isEmpty()) return emptyList()
        val bm25 = keyword.search(query, fields, tieBreak).let { all -> if (accept == null) all else all.filter { accept(it.id) } }.take(TOP)
        val semanticWanted = queryEmbedding != null && (AI_CAPTION in fields || AI_OCR in fields)
        val semantic: List<Pair<Int, Float>> = if (!semanticWanted) emptyList() else {
            val scored = ArrayList<Pair<Int, Float>>()
            for ((id, v) in embeddings) if (accept == null || accept(id)) scored.add(id to dot(v, queryEmbedding))
            scored.sortedByDescending { it.second }.take(TOP)
        }
        val fused = LinkedHashMap<Int, Double>()
        for ((rank, h) in bm25.withIndex()) fused[h.id] = (fused[h.id] ?: 0.0) + 1.0 / (RRF_K + rank + 1)
        for ((rank, s) in semantic.withIndex()) fused[s.first] = (fused[s.first] ?: 0.0) + 1.0 / (RRF_K + rank + 1)
        val keywordIds = bm25.mapTo(HashSet()) { it.id }
        val similarity = semantic.toMap()
        var ranked = fused.entries.map { it.key to it.value }.sortedByDescending { it.second }
        if (favoured != null) {
            var rank = 0
            ranked = ranked.map { (id, score) -> id to if (favoured(id)) score + 1.0 / (RRF_K + ++rank) else score }
                .sortedByDescending { it.second }
        }
        return ranked.take(LIMIT).map { (id, score) ->
            Hit(id, score, id !in keywordIds && id in similarity, similarity[id])
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    // thumbsup2's field names, in the order its search page lists them.
    const val AI_CAPTION = "aiCaption"
    const val AI_OCR = "aiOcr"
    const val CAPTION = "caption"
    const val FILENAME = "filename"
    const val PATH = "path"
    val FIELDS = listOf(AI_CAPTION, AI_OCR, CAPTION, FILENAME, PATH)
}
