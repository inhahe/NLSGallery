package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.LocalDate

/** Queries read for their date, place, request and words together, as the gallery reads them. */
class SearchReadingTest {
    private val dates = DatePhrases(LocalDate.of(2026, 11, 7))

    // The library's places by normalized name. March and Reading are towns in England.
    private val known = mapOf(
        "hawaii" to "Hawaii, United States", "honolulu" to "Honolulu, Hawaii, United States",
        "paris" to "Paris, France", "nice" to "Nice, France", "tokyo" to "Tokyo, Japan",
        "march" to "March, England, United Kingdom", "reading" to "Reading, England, United Kingdom",
        "new york city" to "New York City, New York, United States",
    ).mapValues { PlacePhrases.Known(it.value) } + mapOf(
        "united kingdom" to PlacePhrases.Known("United Kingdom", country = true),
        "uk" to PlacePhrases.Known("United Kingdom", country = true),
    )

    private fun read(q: String, placeOff: Boolean = false) = SearchReading.read(q, dates, known, placeOff)

    /** [q] read as the place [key] (null: none) with [words] left, videos only or not. */
    private fun assertRead(q: String, key: String?, words: String, videos: Boolean = false, date: Boolean = false) {
        val r = read(q)
        assertWithMessage("'$q': place").that(r.place?.key).isEqualTo(key)
        assertWithMessage("'$q': words").that(r.words).isEqualTo(words)
        assertWithMessage("'$q': videos").that(r.videosOnly).isEqualTo(videos)
        assertWithMessage("'$q': date").that(r.date != null).isEqualTo(date)
    }

    @Test
    fun theRequestAroundAPlaceIsNotSearchedFor() {
        assertRead("pictures of me in paris", "paris", "")
        assertRead("photos near nice", "nice", "")
        assertRead("photos visiting tokyo", "tokyo", "")
        assertRead("videos in paris", "paris", "", videos = true)
        assertRead("hawaii photos", "hawaii", "")
        assertRead("my trip to tokyo", "tokyo", "")
        assertRead("tokyo vacation", "tokyo", "")
        assertRead("dog videos in hawaii", "hawaii", "dog", videos = true)
        assertRead("show me the beach in hawaii", "hawaii", "beach")
    }

    @Test
    fun anOrdinaryWordIsAPlaceOnlyWhenSaidToBe() {
        assertRead("nice beach", null, "nice beach")
        assertRead("dinner in a nice restaurant", null, "dinner in a nice restaurant")
        assertRead("from my reading list", null, "from my reading list")
        assertRead("in the nice weather", null, "in the nice weather")
        assertRead("dinner in nice", "nice", "dinner")
        // "the" before a country.
        assertRead("rain in the uk", "uk", "rain")
    }

    @Test
    fun aPlaceAfterADateIsStillOne() {
        assertRead("beach last summer in hawaii", "hawaii", "beach", date = true)
        assertRead("beach in hawaii last summer", "hawaii", "beach", date = true)
        assertRead("last summer in hawaii", "hawaii", "", date = true)
        assertRead("christmas in paris", "paris", "", date = true)
        assertRead("videos from last summer in tokyo", "tokyo", "", videos = true, date = true)
        // Words like "trip" are filler beside a date too.
        assertRead("vacation last summer", null, "", date = true)
        assertRead("my trip last summer to tokyo", "tokyo", "", date = true)
    }

    @Test
    fun aDateWinsOverATownOfItsName() {
        // March is a town: "in march" is still the month, here and with words.
        assertRead("in march", null, "", date = true)
        assertRead("beach in march", null, "beach", date = true)
        // A month is not a place's name when the place is said: "christmas in reading" is Reading.
        assertRead("christmas in reading", "reading", "", date = true)
    }

    @Test
    fun placesNamedInPassingRankTheirPictures() {
        assertThat(read("beach hawaii").named).containsExactly("hawaii")
        assertThat(read("new york city skyline").named).containsExactly("new york city")
        assertThat(read("honolulu sunset in hawaii").let { it.place?.key to it.named }).isEqualTo("hawaii" to listOf("honolulu"))
        assertThat(read("beach").named).isEmpty()
        // Set aside: the words alone, nothing filtered or ranked by place.
        val off = read("beach in hawaii", placeOff = true)
        assertThat(off.place).isNull()
        assertThat(off.words).isEqualTo("beach in hawaii")
        assertThat(off.named).isEmpty()
    }

    @Test
    fun withoutPlacesAQueryReadsAsBefore() {
        val r = SearchReading.read("show me photos of dogs from last summer", dates, emptyMap(), false)
        assertThat(r.words).isEqualTo("dogs")
        assertThat(r.date).isNotNull()
        assertThat(SearchReading.read("dog", null, emptyMap(), false)).isEqualTo(SearchReading("dog", null, null, "dog", false))
        assertThat(SearchReading.read("  ", dates, known, false).words).isEmpty()
    }
}
