package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Narrowing a search ([HybridSearch.search]'s accept) before its results are ranked and cut, and
 * favouring some of them (its favoured).
 */
class HybridSearchTest {
    private val fields = HybridSearch.FIELDS
    private val index = MiniSearch(fields)
    private val embeddings = ArrayList<Pair<Int, FloatArray>>()
    private val n = 300
    private val query = floatArrayOf(1f, 0f)

    /**
     * 300 documents about a beach, the first ones the best matches (the word more often), each
     * embedded nearer the query the earlier it is.
     */
    init {
        for (id in 0 until n) {
            val caption = "beach ".repeat(1 + (n - id) / 20) + "sand dune walk path rock sea sky".split(" ").take(id % 7 + 1).joinToString(" ")
            index.add(id, listOf(caption, "", "", "photo$id.jpg", "DCIM/Camera/"))
            embeddings.add(id to floatArrayOf(1f - id / 1000f, id / 1000f))
        }
    }

    /** The accepted ones are the 20 worst. */
    @Test
    fun narrowedSearchFindsWhatTheWholeOneCutsOff() {
        val late = (n - 20 until n).toSet()

        val whole = HybridSearch.search("beach", index, fields, query, embeddings)
        assertThat(whole.map { it.id }.filter { it in late }).isEmpty()

        val narrowed = HybridSearch.search("beach", index, fields, query, embeddings, accept = { it in late })
        assertThat(narrowed.map { it.id }).containsExactlyElementsIn(late)
        assertThat(narrowed.none { it.semanticOnly }).isTrue()

        // By meaning alone (no word matches), the accepted ones are still the nearest among them.
        val meaning = HybridSearch.search("zzzz", index, fields, query, embeddings, accept = { it in late })
        assertThat(meaning.map { it.id }).containsExactlyElementsIn(late).inOrder()
        assertThat(meaning.all { it.semanticOnly }).isTrue()
    }

    @Test
    fun favouredOnesRankHigherAndNoneIsAdded() {
        val plain = HybridSearch.search("beach", index, fields, query, embeddings)
        val favoured = setOf(150, 151, 1000)
        val ranked = HybridSearch.search("beach", index, fields, query, embeddings, favoured = { it in favoured })
        assertThat(ranked.map { it.id }.toSet()).isEqualTo(plain.map { it.id }.toSet())
        // As found by one more list, in the order they had: 150 first, then 151.
        val before = plain.associateBy { it.id }
        val after = ranked.associateBy { it.id }
        assertThat(after.getValue(150).score - before.getValue(150).score).isWithin(1e-12).of(1.0 / 61)
        assertThat(after.getValue(151).score - before.getValue(151).score).isWithin(1e-12).of(1.0 / 62)
        assertThat(ranked.indexOfFirst { it.id == 150 }).isLessThan(plain.indexOfFirst { it.id == 150 })
        assertThat(ranked.map { it.id }.filter { it !in favoured }).isEqualTo(plain.map { it.id }.filter { it !in favoured })
        // Favouring none is thumbsup2's ranking.
        assertThat(HybridSearch.search("beach", index, fields, query, embeddings, favoured = { false })).isEqualTo(plain)
    }
}
