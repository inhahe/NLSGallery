package com.lithic.gallery.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelFailureTrackerTest {
    @Test
    fun aFailureAmongSuccessesIsTheFiles() {
        val t = ModelFailureTracker<String>()
        assertThat(t.described()).isEmpty()
        t.modelsFailed("a", "boom")
        assertThat(t.described()).containsExactly("a" to "boom")
        assertThat(t.finished()).isEmpty()
    }

    @Test
    fun failuresInARowStopTheRunBlamingNoOne() {
        val t = ModelFailureTracker<String>(stopAfter = 3)
        t.described()
        t.modelsFailed("a", "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldStop).isFalse()
        t.modelsFailed("c", "x")
        assertThat(t.shouldStop).isTrue()
        // A stopped run does not call finished(); nothing was handed out as the files' fault.
    }

    @Test
    fun aSuccessResetsTheCountInARow() {
        val t = ModelFailureTracker<String>(stopAfter = 2)
        t.modelsFailed("a", "x")
        assertThat(t.described()).containsExactly("a" to "x")
        t.modelsFailed("b", "x")
        assertThat(t.shouldStop).isFalse()
    }

    @Test
    fun failuresAtTheEndCountOnlyIfTheModelsWorkedInTheRun() {
        val worked = ModelFailureTracker<String>()
        worked.described()
        worked.modelsFailed("a", "x")
        assertThat(worked.finished()).containsExactly("a" to "x")

        val never = ModelFailureTracker<String>()
        never.modelsFailed("a", "x")
        assertThat(never.finished()).isEmpty()
    }
}
