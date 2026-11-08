package com.lithic.gallery.work

import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.ai.AiEngine.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Who a pass blames for model failures. Items are names: "ok…" are described, "bad…" are files
 * the models always fail on; [Run.modelsWork] says whether the models work at all (the probe's
 * answer), and the first [Run.failFirst] attempts fail whatever the file (a passing shortage).
 */
class DescribePassTest {
    private class Run(var modelsWork: Boolean? = true, val failFirst: Int = 0, val unavailableAt: Int = -1) {
        val attempted = ArrayList<String>()
        val blamed = ArrayList<String>()
        var probes = 0
        val pass = DescribePass<String>(
            attempt = { item ->
                attempted.add(item)
                when {
                    attempted.size - 1 == unavailableAt -> Outcome.ModelsUnavailable
                    attempted.size <= failFirst || item.startsWith("bad") || modelsWork == false -> Outcome.ModelsFailed("boom")
                    else -> Outcome.Described
                }
            },
            probe = { probes++; modelsWork },
            blame = { files -> blamed.addAll(files.map { it.first }) },
        )
    }

    @Test
    fun aFileThatFailsAgainOnceTheModelsWorkIsBlamed(): Unit = runBlocking {
        val r = Run()
        assertThat(r.pass.run(listOf("bad1", "ok1", "ok2"))).isEqualTo(DescribePass.End.Done)
        assertThat(r.blamed).containsExactly("bad1")
        // Tried once more, after ok1 showed the models working.
        assertThat(r.attempted).containsExactly("bad1", "ok1", "bad1", "ok2").inOrder()
        assertThat(r.probes).isEqualTo(0)
    }

    @Test
    fun aPassingShortageIsNotBlamed(): Unit = runBlocking {
        // The models fail on the first three files (memory, say), then work again: tried once
        // more, those three are described.
        val r = Run(failFirst = 3)
        assertThat(r.pass.run(listOf("ok1", "ok2", "ok3", "ok4"))).isEqualTo(DescribePass.End.Done)
        assertThat(r.blamed).isEmpty()
        assertThat(r.attempted).containsExactly("ok1", "ok2", "ok3", "ok4", "ok1", "ok2", "ok3").inOrder()
    }

    @Test
    fun aBlockOfFailingFilesAtTheEndIsBlamedWhenTheProbeWorks(): Unit = runBlocking {
        // Files that failed before come last: six or more of them in a row, after successes.
        val r = Run()
        val bad = (1..8).map { "bad$it" }
        assertThat(r.pass.run(listOf("ok1", "ok2") + bad)).isEqualTo(DescribePass.End.Done)
        assertThat(r.blamed).containsExactlyElementsIn(bad).inOrder()
        assertThat(r.probes).isEqualTo(2) // at six in a row, and at the end for the last two
    }

    @Test
    fun aFewFailingFilesAloneInAPassAreBlamedWhenTheProbeWorks(): Unit = runBlocking {
        val r = Run()
        assertThat(r.pass.run(listOf("bad1", "bad2"))).isEqualTo(DescribePass.End.Done)
        assertThat(r.blamed).containsExactly("bad1", "bad2")
        assertThat(r.probes).isEqualTo(1)
    }

    @Test
    fun modelsFailingOnEverythingBlameNoOneAndStopThePass(): Unit = runBlocking {
        val r = Run(modelsWork = false)
        val items = (1..20).map { "ok$it" }
        assertThat(r.pass.run(items)).isEqualTo(DescribePass.End.ModelsFailing)
        assertThat(r.blamed).isEmpty()
        assertThat(r.attempted).hasSize(6)
    }

    @Test
    fun withNothingToProbeOnNoOneIsBlamedAndThePassStops(): Unit = runBlocking {
        val r = Run(modelsWork = null)
        assertThat(r.pass.run(listOf("bad1"))).isEqualTo(DescribePass.End.Undecided)
        assertThat(r.blamed).isEmpty()
        // Six failures in a row with nothing to probe on: the pass stops there.
        val s = Run(modelsWork = null)
        val items = (1..10).map { "bad$it" }
        assertThat(s.pass.run(items)).isEqualTo(DescribePass.End.Undecided)
        assertThat(s.attempted).hasSize(6)
        assertThat(s.blamed).isEmpty()
    }

    @Test
    fun modelsThatCannotBeOpenedEndThePassAtOnce(): Unit = runBlocking {
        val r = Run(unavailableAt = 1)
        assertThat(r.pass.run(listOf("ok1", "ok2", "ok3"))).isEqualTo(DescribePass.End.ModelsFailing)
        assertThat(r.attempted).containsExactly("ok1", "ok2").inOrder()
        assertThat(r.blamed).isEmpty()
    }
}
