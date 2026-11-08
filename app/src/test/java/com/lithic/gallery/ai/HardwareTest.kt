package com.lithic.gallery.ai

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Phones' memory as Android reports it, against what the AI needs and what they are sold as. */
class HardwareTest {
    private fun gib(x: Double) = (x * (1L shl 30)).toLong()

    @Test
    fun enoughFromSixGigabytes() {
        // Reported by Android (GiB) -> sold as (GB), enough for the AI.
        for ((reported, sold, enough) in listOf(
            Triple(1.8, 2, false), Triple(3.6, 4, false), Triple(4.9, 6, false),
            Triple(5.3, 6, true), Triple(5.6, 6, true), Triple(7.3, 8, true), Triple(10.9, 12, true), Triple(15.2, 16, true),
        )) {
            val m = Hardware.Memory(gib(reported))
            assertWithMessage("$reported GiB sold as").that(m.soldAsGb).isEqualTo(sold)
            assertWithMessage("$reported GiB enough").that(m.enough).isEqualTo(enough)
        }
    }
}
