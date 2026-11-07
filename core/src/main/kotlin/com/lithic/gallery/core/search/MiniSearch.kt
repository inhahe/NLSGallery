package com.lithic.gallery.core.search

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min

/**
 * A port of the parts of MiniSearch 7.2 thumbsup2's search page uses: BM25+ ranking over several
 * fields, with prefix and fuzzy term matching, terms combined with AND. Same tokenizer
 * (`text.split(/[^\p{L}\p{N}]+/u)`), same term processing (lowercase), same scoring, so the same
 * query ranks the same documents the same way.
 *
 * Unlike MiniSearch, [remove] takes a document's postings out at once (MiniSearch leaves them
 * until a vacuum), so the statistics are always those of a freshly built index.
 *
 * Not thread-safe: build or change it on one thread at a time.
 */
class MiniSearch(val fields: List<String>) {
    /** term → field index → document → occurrences */
    private val index = HashMap<String, Array<LinkedHashMap<Int, Int>?>>()
    /** Terms in sorted order, for prefix lookups. */
    private val sortedTerms = java.util.TreeSet<String>()
    /** document → per-field number of distinct raw tokens */
    private val fieldLength = HashMap<Int, IntArray>()
    /** document → the terms it was indexed under, to take it out again */
    private val docTerms = HashMap<Int, Array<Set<String>>>()
    private val avgFieldLength = DoubleArray(fields.size)
    private var documentCount = 0

    val size: Int get() = documentCount

    operator fun contains(id: Int) = id in fieldLength

    /**
     * Indexes document [id] with one string per field (in [fields] order; empty for none),
     * replacing it if present. Ties in score keep the order documents were added in.
     */
    fun add(id: Int, values: List<String>) {
        require(values.size == fields.size) { "${values.size} values for ${fields.size} fields" }
        remove(id)
        documentCount++
        val lengths = IntArray(fields.size)
        fieldLength[id] = lengths
        val termsByField = Array<Set<String>>(fields.size) { emptySet() }
        for ((f, value) in values.withIndex()) {
            val tokens = tokenize(value)
            lengths[f] = tokens.toSet().size
            // MiniSearch's running average: (avg * count + length) / (count + 1), count = docs before this one
            val count = documentCount - 1
            avgFieldLength[f] = (avgFieldLength[f] * count + lengths[f]) / (count + 1)
            val seen = LinkedHashSet<String>()
            for (token in tokens) {
                val term = processTerm(token)
                if (term.isEmpty()) continue
                seen.add(term)
                val perField = index.getOrPut(term) { sortedTerms.add(term); arrayOfNulls(fields.size) }
                val docs = perField[f] ?: LinkedHashMap<Int, Int>().also { perField[f] = it }
                docs[id] = (docs[id] ?: 0) + 1
            }
            termsByField[f] = seen
        }
        docTerms[id] = termsByField
    }

    /** Takes document [id] out of the index, if it is in it. */
    fun remove(id: Int) {
        val lengths = fieldLength.remove(id) ?: return
        val terms = docTerms.remove(id)!!
        // MiniSearch's removeFieldLength: (avg * count - length) / (count - 1)
        for (f in fields.indices) {
            avgFieldLength[f] = if (documentCount > 1)
                (avgFieldLength[f] * documentCount - lengths[f]) / (documentCount - 1) else 0.0
        }
        documentCount--
        for (f in fields.indices) for (term in terms[f]) {
            val perField = index[term] ?: continue
            perField[f]?.let { docs ->
                docs.remove(id)
                if (docs.isEmpty()) perField[f] = null
            }
            if (perField.all { it == null }) {
                index.remove(term)
                sortedTerms.remove(term)
            }
        }
    }

    /** One search result: the document and MiniSearch's score for it. */
    data class Hit(val id: Int, val score: Double)

    /**
     * thumbsup2's query: `search(query, {fields, prefix: true, fuzzy: 0.2, combineWith: 'AND'})`,
     * best first.
     *
     * @param searchFields the fields to look in (a subset of [fields]); their order only breaks ties.
     * @param tieBreak orders documents with equal scores (smaller first); null keeps MiniSearch's
     *   order, which is the order they were added in.
     */
    fun search(query: String, searchFields: List<String> = fields, tieBreak: ((Int) -> Long)? = null): List<Hit> {
        val fieldIds = searchFields.map { f -> fields.indexOf(f).also { require(it >= 0) { "unknown field $f" } } }
        val terms = tokenize(query).map(::processTerm).filter { it.isNotEmpty() }
        if (terms.isEmpty() || fieldIds.isEmpty()) return emptyList()
        var combined: LinkedHashMap<Int, Result>? = null
        for (term in terms) {
            val r = termQuery(term, fieldIds)
            combined = if (combined == null) r else and(combined, r)
        }
        val hits = combined!!.map { (id, res) -> Hit(id, res.score * maxOf(res.terms.size, 1)) }
        return if (tieBreak == null) hits.sortedByDescending { it.score } // stable
        else hits.sortedWith(compareByDescending<Hit> { it.score }.thenBy { tieBreak(it.id) })
    }

    private class Result(var score: Double, val terms: MutableList<String>)

    /** MiniSearch's AND combinator: documents in both, scores added, in [b]'s order. */
    private fun and(a: LinkedHashMap<Int, Result>, b: LinkedHashMap<Int, Result>): LinkedHashMap<Int, Result> {
        val out = LinkedHashMap<Int, Result>()
        for ((id, rb) in b) {
            val ra = a[id] ?: continue
            for (t in rb.terms) if (t !in ra.terms) ra.terms.add(t)
            out[id] = Result(ra.score + rb.score, ra.terms)
        }
        return out
    }

    /** MiniSearch's `executeQuerySpec` for one term with prefix and fuzzy 0.2 on. */
    private fun termQuery(term: String, fieldIds: List<Int>): LinkedHashMap<Int, Result> {
        val results = LinkedHashMap<Int, Result>()
        termResults(term, term, 1.0, index[term], fieldIds, results)
        // JS Math.round rounds halves up; Kotlin's round would round them to even.
        val maxDistance = min(MAX_FUZZY, floor(term.length * FUZZY + 0.5).toInt())
        val fuzzyMatches = if (maxDistance > 0) fuzzy(term, maxDistance) else LinkedHashMap()
        for (t in sortedTerms.subSet(term, false, term + Char.MAX_VALUE, true)) {
            if (!t.startsWith(term)) continue
            fuzzyMatches.remove(t) // a prefix match is scored as one, not as fuzzy
            val distance = t.length - term.length
            val weight = PREFIX_WEIGHT * t.length / (t.length + 0.3 * distance)
            termResults(term, t, weight, index[t], fieldIds, results)
        }
        for ((t, distance) in fuzzyMatches) {
            if (distance == 0) continue
            val weight = FUZZY_WEIGHT * t.length / (t.length + distance)
            termResults(term, t, weight, index[t], fieldIds, results)
        }
        return results
    }

    private fun termResults(
        sourceTerm: String, derivedTerm: String, weight: Double,
        data: Array<LinkedHashMap<Int, Int>?>?, fieldIds: List<Int>, results: LinkedHashMap<Int, Result>,
    ) {
        if (data == null) return
        for (f in fieldIds) {
            val docs = data[f] ?: continue
            val matching = docs.size
            for ((doc, freq) in docs) {
                val len = fieldLength[doc]!![f]
                val score = weight * bm25(freq, matching, documentCount, len, avgFieldLength[f])
                val r = results[doc]
                if (r != null) {
                    r.score += score
                    if (sourceTerm !in r.terms) r.terms.add(sourceTerm)
                } else {
                    results[doc] = Result(score, mutableListOf(sourceTerm))
                }
            }
        }
    }

    /** Terms within [maxDistance] edits (Levenshtein) of [term], with their distance. */
    private fun fuzzy(term: String, maxDistance: Int): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val prev = IntArray(term.length + 1)
        val cur = IntArray(term.length + 1)
        terms@ for (t in sortedTerms) {
            if (abs(t.length - term.length) > maxDistance) continue
            for (j in prev.indices) prev[j] = j
            for (i in 1..t.length) {
                cur[0] = i
                var rowMin = i
                for (j in 1..term.length) {
                    val cost = if (t[i - 1] == term[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
                    if (cur[j] < rowMin) rowMin = cur[j]
                }
                if (rowMin > maxDistance) continue@terms
                System.arraycopy(cur, 0, prev, 0, cur.size)
            }
            val d = prev[term.length]
            if (d <= maxDistance) out[t] = d
        }
        return out
    }

    companion object {
        private const val FUZZY = 0.2
        private const val MAX_FUZZY = 6
        private const val PREFIX_WEIGHT = 0.375
        private const val FUZZY_WEIGHT = 0.45
        private const val K = 1.2
        private const val B = 0.7
        private const val D = 0.5

        private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

        /** thumbsup2's tokenizer: runs of letters and digits. */
        fun tokenize(text: String): List<String> = text.split(SPLIT).filter { it.isNotEmpty() }

        /** JavaScript's toLowerCase (locale-independent, with the final-sigma rule). */
        fun processTerm(term: String): String = term.lowercase()

        /** MiniSearch's BM25+. */
        private fun bm25(tf: Int, matching: Int, total: Int, len: Int, avgLen: Double): Double {
            val idf = ln(1 + (total - matching + 0.5) / (matching + 0.5))
            return idf * (D + tf * (K + 1) / (tf + K * (1 - B + B * len / avgLen)))
        }
    }
}
