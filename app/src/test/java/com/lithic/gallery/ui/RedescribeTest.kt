package com.lithic.gallery.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Why describing a file afresh left it as it was is said only while its description is still the one left. */
class RedescribeTest {
    @Test
    fun theProblemHoldsUntilTheFileIsDescribedAgain() {
        val left = Redescribe(going = false, problem = "it could not be opened just now", describedAt = 5)
        assertThat(left.problemWith(5)).isEqualTo("it could not be opened just now")
        // Described since, by a run: no longer so.
        assertThat(left.problemWith(9)).isNull()
        // None to begin with, and none since.
        assertThat(Redescribe(problem = "x", describedAt = 0).problemWith(0)).isEqualTo("x")
        assertThat(Redescribe().problemWith(5)).isNull()
    }
}
