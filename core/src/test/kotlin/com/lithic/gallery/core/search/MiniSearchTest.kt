package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The port ranks like thumbsup2's MiniSearch (tools/reference/search.json). */
class MiniSearchTest {
    private val ref = TestFiles.reference("search.json").jsonObject
    private val fields = ref["fields"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun build(): MiniSearch {
        val ms = MiniSearch(fields)
        for (d in ref["docs"]!!.jsonArray) {
            val o = d.jsonObject
            ms.add(o["id"]!!.jsonPrimitive.int, fields.map { o[it]!!.jsonPrimitive.content })
        }
        return ms
    }

    @Test
    fun ranksLikeMiniSearch() {
        val ms = build()
        for (r in ref["results"]!!.jsonArray) {
            val o = r.jsonObject
            val query = o["query"]!!.jsonPrimitive.content
            val searchFields = o["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
            val want = o["hits"]!!.jsonArray.map { h -> h.jsonArray[0].jsonPrimitive.int to h.jsonArray[1].jsonPrimitive.double }
            val got = ms.search(query, searchFields)
            val what = "'$query' in $searchFields"
            assertWithMessage("$what ids").that(got.map { it.id }).isEqualTo(want.map { it.first })
            for ((g, w) in got.zip(want)) {
                assertWithMessage("$what score of ${g.id}").that(g.score).isWithin(1e-9 * Math.abs(w.second) + 1e-12).of(w.second)
            }
        }
    }

    @Test
    fun removingADocumentLeavesTheIndexAsIfBuiltWithoutIt() {
        val ms = build()
        val docs = ref["docs"]!!.jsonArray
        val without = MiniSearch(fields)
        for (d in docs) {
            val o = d.jsonObject
            val id = o["id"]!!.jsonPrimitive.int
            if (id % 3 == 0) continue
            without.add(id, fields.map { o[it]!!.jsonPrimitive.content })
        }
        for (d in docs) {
            val id = d.jsonObject["id"]!!.jsonPrimitive.int
            if (id % 3 == 0) ms.remove(id)
        }
        for (q in listOf("cat", "two cats", "sleping", "IMG", "bus street", "whatsapp")) {
            val a = ms.search(q).sortedBy { it.id }
            val b = without.search(q).sortedBy { it.id }
            assertWithMessage("'$q' ids").that(a.map { it.id }).isEqualTo(b.map { it.id })
            for ((x, y) in a.zip(b)) assertWithMessage("'$q' ${x.id}").that(x.score).isWithin(1e-9).of(y.score)
        }
    }
}
