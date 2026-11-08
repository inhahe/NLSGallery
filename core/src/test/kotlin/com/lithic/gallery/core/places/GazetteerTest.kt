package com.lithic.gallery.core.places

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.TestFiles
import org.junit.Test
import java.io.File

/** Coordinates to places, with the gazetteer the app ships (tools/make_places.py). */
class GazetteerTest {
    private val gazetteer = Gazetteer(File(TestFiles.graphs.parentFile, "places.bin").readBytes())

    @Test
    fun placesOfKnownPoints() {
        // (latitude, longitude) -> a name it goes by, its region, its country.
        val cases = listOf(
            Triple(21.2766, -157.8253, Triple("Honolulu", "Hawaii", "United States")), // Waikiki, a district of Honolulu
            Triple(48.8584, 2.2945, Triple("Paris", "Île-de-France", "France")), // the Eiffel Tower
            Triple(-33.8568, 151.2153, Triple("Sydney", "New South Wales", "Australia")), // the Opera House
            Triple(35.6586, 139.7454, Triple(null, "Tokyo", "Japan")), // Tokyo Tower: whichever ward
        )
        for ((lat, lon, want) in cases) {
            val p = gazetteer.place(lat, lon) ?: throw AssertionError("nothing at $lat, $lon")
            if (want.first != null) assertWithMessage("$lat, $lon names").that(p.names).contains(want.first)
            assertWithMessage("$lat, $lon region").that(p.region).isEqualTo(want.second)
            assertWithMessage("$lat, $lon country").that(p.country).isEqualTo(want.third)
        }
    }

    @Test
    fun suburbsAreTheirCitysToo() {
        // Boulogne-Billancourt, a suburb of Paris: its own name, and Paris.
        val p = gazetteer.place(48.8352, 2.2410)!!
        assertThat(p.names).contains("Paris")
        assertThat(p.country).isEqualTo("France")
    }

    @Test
    fun nothingFarFromAnyPlace() {
        assertThat(gazetteer.place(0.0, -140.0)).isNull() // the middle of the Pacific
        assertThat(gazetteer.place(0.0, 0.0)).isNull() // "null island", where a broken GPS puts pictures
    }

    @Test
    fun namesAreMatchedWithoutAccentsOrPunctuation() {
        assertThat(Gazetteer.normalize("Île-de-France")).isEqualTo("ile de france")
        assertThat(Gazetteer.normalize("St. John's")).isEqualTo("st john s")
        assertThat(gazetteer.countryAliases["usa"]).isEqualTo("United States")
        assertThat(gazetteer.countryAliases["holland"]).isEqualTo("The Netherlands")
        assertThat(gazetteer.countryAliases["netherlands"]).isEqualTo("The Netherlands")
    }
}
