package com.lithic.gallery.work

import com.lithic.gallery.ai.AiEngine

/**
 * One pass of a describe run: one set of models over files of one kind (captions, text in
 * pictures, embeddings), telling the files the models fail on from models that fail on anything
 * (out of memory, say).
 *
 * A file the models failed on is a suspect. Suspects are blamed (counted against the file) once
 * the models are seen to work: the pass describes another file, or, when [giveUpAfter] failures
 * come with none described between or the pass ends with suspects, a [probe] re-runs the models
 * on input they handled before. If the probe fails too, the models are failing just now: the pass
 * ends ([End.ModelsFailing]) blaming none. If there is nothing to probe on, nothing can be told:
 * the pass ends ([End.Undecided]) blaming none, its suspects left for a later run.
 *
 * @param T the pass's items.
 * @param attempt describes one item.
 * @param probe whether the models still work on input they handled before, like the last
 *   suspect; null when there is no such input to try them on.
 * @param blame counts the suspects shown to be at fault, with their messages.
 * @param afterFailure runs after each model failure (closing the models, to give memory back).
 * @param progress runs after each item.
 */
class DescribePass<T>(
    private val attempt: suspend (T) -> AiEngine.Outcome,
    private val probe: suspend (lastSuspect: T) -> Boolean?,
    private val blame: suspend (List<Pair<T, String>>) -> Unit,
    private val afterFailure: suspend () -> Unit = {},
    private val progress: suspend () -> Unit = {},
    private val giveUpAfter: Int = 6,
) {
    enum class End {
        /** Every item tried (or [run]'s stop asked for). */
        Done,
        /** The models fail even on input they handled before: try again later. */
        ModelsFailing,
        /** The models failed with nothing to try them on to tell why: stopped, blaming none. */
        Undecided,
    }

    /** Runs the pass over [items]; [stopped] is asked before each. */
    suspend fun run(items: List<T>, stopped: () -> Boolean = { false }): End {
        val suspects = ArrayList<Pair<T, String>>()
        suspend fun cleared() = blame(suspects.toList()).also { suspects.clear() }
        /** After failures with no success since: whether to go on. */
        suspend fun decide(): End? = when (probe(suspects.last().first)) {
            true -> { cleared(); null }
            false -> End.ModelsFailing
            null -> End.Undecided
        }
        for (item in items) {
            if (stopped()) return End.Done
            when (val outcome = attempt(item)) {
                AiEngine.Outcome.Described -> cleared()
                is AiEngine.Outcome.ModelsFailed -> {
                    suspects.add(item to outcome.message)
                    afterFailure()
                }
                else -> {}
            }
            progress()
            if (suspects.size >= giveUpAfter) decide()?.let { return it }
        }
        if (suspects.isNotEmpty()) decide()?.let { return it }
        return End.Done
    }
}
