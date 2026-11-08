package com.lithic.gallery.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelFailureTrackerTest {
    @Test
    fun aFailureBeforeASuccessIsTheFiles() {
        val t = ModelFailureTracker<String>()
        // Files the models fail on at the head of the pass: suspects, until one is described.
        t.modelsFailed("a", "boom")
        t.modelsFailed("b", "boom")
        assertThat(t.shouldGiveUp).isFalse()
        assertThat(t.modelsWorked()).containsExactly("a" to "boom", "b" to "boom")
        assertThat(t.finished()).isEmpty()
    }

    @Test
    fun failuresWithNoSuccessBetweenGiveUpBlamingNoOne() {
        val t = ModelFailureTracker<String>(giveUpAfter = 3)
        t.modelsWorked()
        t.modelsFailed("a", "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldGiveUp).isFalse()
        t.modelsFailed("c", "x")
        assertThat(t.shouldGiveUp).isTrue()
        t.gaveUp()
        // The models worked earlier in the pass, but these three are not blamed.
        assertThat(t.finished()).isEmpty()
    }

    @Test
    fun aSuccessStartsTheCountAgain() {
        val t = ModelFailureTracker<String>(giveUpAfter = 2)
        t.modelsFailed("a", "x")
        assertThat(t.modelsWorked()).containsExactly("a" to "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldGiveUp).isFalse()
    }

    @Test
    fun failuresAtTheEndCountOnlyIfTheModelsWorkedInThePass() {
        val worked = ModelFailureTracker<String>()
        worked.modelsWorked()
        worked.modelsFailed("a", "x")
        assertThat(worked.finished()).containsExactly("a" to "x")

        val never = ModelFailureTracker<String>()
        never.modelsFailed("a", "x")
        assertThat(never.finished()).isEmpty()
    }
}
