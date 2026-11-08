package com.lithic.gallery.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelFailureTrackerTest {
    @Test
    fun aFailureAmongSuccessesIsTheFiles() {
        val t = ModelFailureTracker<String>()
        assertThat(t.modelsWorked()).isEmpty()
        t.modelsFailed("a", "boom")
        assertThat(t.modelsWorked()).containsExactly("a" to "boom")
        assertThat(t.finished()).isEmpty()
    }

    @Test
    fun failuresInARowCallForAProbe() {
        val t = ModelFailureTracker<String>(stopAfter = 3)
        t.modelsFailed("a", "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldProbe).isFalse()
        t.modelsFailed("c", "x")
        assertThat(t.shouldProbe).isTrue()
        // The probe went through: the three are at fault, and the run goes on.
        assertThat(t.modelsWorked()).containsExactly("a" to "x", "b" to "x", "c" to "x")
        assertThat(t.shouldProbe).isFalse()
    }

    @Test
    fun aSuccessResetsTheCountInARow() {
        val t = ModelFailureTracker<String>(stopAfter = 2)
        t.modelsFailed("a", "x")
        assertThat(t.modelsWorked()).containsExactly("a" to "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldProbe).isFalse()
    }

    @Test
    fun failuresAtTheEndCountOnlyIfTheModelsWorkedInTheRun() {
        val worked = ModelFailureTracker<String>()
        worked.modelsWorked()
        worked.modelsFailed("a", "x")
        assertThat(worked.finished()).containsExactly("a" to "x")

        val never = ModelFailureTracker<String>()
        never.modelsFailed("a", "x")
        assertThat(never.finished()).isEmpty()
    }
}
