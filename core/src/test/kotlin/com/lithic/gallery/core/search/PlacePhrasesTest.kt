package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.search.PlacePhrases.PlaceQuery
import org.junit.Test

/** Place names taken out of queries, among the places a library has pictures from. */
class PlacePhrasesTest {
    // The library's places: normalized name -> label.
    private val known = mapOf(
        "hawaii" to "Hawaii, United States", "honolulu" to "Honolulu, Hawaii, United States",
        "united states" to "United States", "usa" to "United States", "paris" to "Paris, France",
        "france" to "France", "new york" to "New York, United States", "nice" to "Nice, France",
        "the netherlands" to "Netherlands", "netherlands" to "Netherlands", "ile de france" to "Île-de-France, France",
    )

    private fun parse(q: String) = PlacePhrases.parse(q, known)

    @Test
    fun placesAfterAPreposition() {
        assertThat(parse("beach in hawaii")).isEqualTo(PlaceQuery("beach", "hawaii", "Hawaii, United States"))
        assertThat(parse("sunset at honolulu")).isEqualTo(PlaceQuery("sunset", "honolulu", "Honolulu, Hawaii, United States"))
        assertThat(parse("trip to new york")!!.let { it.text to it.key }).isEqualTo("trip" to "new york")
        assertThat(parse("snow in the netherlands")!!.let { it.text to it.key }).isEqualTo("snow" to "the netherlands")
        assertThat(parse("dinner in Île-de-France")!!.key).isEqualTo("ile de france")
        assertThat(parse("photos near nice")!!.let { it.text to it.key }).isEqualTo("photos" to "nice")
    }

    @Test
    fun aPlaceAloneIsOne() {
        assertThat(parse("paris")).isEqualTo(PlaceQuery("", "paris", "Paris, France"))
        assertThat(parse("New York")!!.key).isEqualTo("new york")
        assertThat(parse("usa")!!.label).isEqualTo("United States")
    }

    @Test
    fun namesThatAreWordsStayWords() {
        for (q in listOf("nice beach", "paris hilton", "hawaii shirt", "new york cheesecake", "dog", "in the snow", "")) {
            assertWithMessage("'$q'").that(parse(q)).isNull()
        }
        assertThat(PlacePhrases.parse("beach in hawaii", emptyMap())).isNull()
    }
}
