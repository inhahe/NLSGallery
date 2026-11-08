package com.lithic.gallery.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Videos' locations, as their ISO 6709 tag gives them. */
class MediaLocationsTest {
    @Test
    fun iso6709LocationsAreRead() {
        assertThat(MediaLocations.parseIso6709("+37.4220-122.0841/")).isEqualTo(37.4220 to -122.0841)
        // With an altitude, which is not wanted.
        assertThat(MediaLocations.parseIso6709("-33.8568+151.2153+010.000/")).isEqualTo(-33.8568 to 151.2153)
        assertThat(MediaLocations.parseIso6709("+48+002/")).isEqualTo(48.0 to 2.0)
        assertThat(MediaLocations.parseIso6709("")).isNull()
        assertThat(MediaLocations.parseIso6709(null)).isNull()
        assertThat(MediaLocations.parseIso6709("somewhere")).isNull()
    }
}
