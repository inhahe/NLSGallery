package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.search.QueryRequest.Stripped
import org.junit.Test

/** A query without the request it opens with ("show me photos of"), and whether it asks for videos. */
class QueryRequestTest {
    private fun assertStrip(query: String, text: String, videosOnly: Boolean = false) {
        assertWithMessage("'$query'").that(QueryRequest.strip(query)).isEqualTo(Stripped(text, videosOnly))
    }

    @Test
    fun requestsAreTakenOff() {
        assertStrip("show me photos of dogs", "dogs")
        assertStrip("Show me photos of the dogs!", "dogs")
        assertStrip("find cats", "cats")
        assertStrip("find the photo booth", "photo booth")
        assertStrip("pictures of the beach", "beach")
        assertStrip("my photos from paris", "paris")
        assertStrip("all my pics with grandma", "grandma")
        assertStrip("show me photos", "")
        assertStrip("my photos", "")
        assertStrip("videos of dogs", "dogs", videosOnly = true)
        assertStrip("show me videos", "", videosOnly = true)
        assertStrip("some clips from the party", "party", videosOnly = true)
        assertStrip("image of a cat?", "cat")
    }

    @Test
    fun queriesWithoutARequestAreLeftAsTyped() {
        for (q in listOf("Dog on a Beach", "photo booth", "photos", "videos", "video game", "receipt", "the beach",
            "get together", "picture frame")) {
            assertWithMessage("'$q'").that(QueryRequest.strip(q)).isEqualTo(Stripped(q, false))
        }
    }
}
