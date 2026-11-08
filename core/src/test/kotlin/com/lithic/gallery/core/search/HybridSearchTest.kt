package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Narrowing a search ([HybridSearch.search]'s accept) before its results are ranked and cut. */
class HybridSearchTest {
    /**
     * 300 documents about a beach, the first ones the best matches (the word more often), each
     * embedded nearer the query the earlier it is; the accepted ones are the 20 worst.
     */
    @Test
    fun narrowedSearchFindsWhatTheWholeOneCutsOff() {
        val fields = HybridSearch.FIELDS
        val index = MiniSearch(fields)
        val embeddings = ArrayList<Pair<Int, FloatArray>>()
        val n = 300
        for (id in 0 until n) {
            val caption = "beach ".repeat(1 + (n - id) / 20) + "sand dune walk path rock sea sky".split(" ").take(id % 7 + 1).joinToString(" ")
            index.add(id, listOf(caption, "", "", "photo$id.jpg", "DCIM/Camera/"))
            embeddings.add(id to floatArrayOf(1f - id / 1000f, id / 1000f))
        }
        val query = floatArrayOf(1f, 0f)
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
}
