package com.lithic.gallery.work

/**
 * Tells, over one describe run, the files the models fail on from models that fail on anything
 * (out of memory, say). A file the models failed on is a suspect until another file in the run is
 * described: then the models work, and the suspects' failures are theirs. [stopAfter] failures in
 * a row say the models are failing: the run should stop, blaming no one.
 *
 * @param T the run's items.
 */
class ModelFailureTracker<T>(private val stopAfter: Int = 3) {
    private val suspects = ArrayList<Pair<T, String>>()
    private var inARow = 0
    private var anyDescribed = false

    /** A file was described. Returns the suspects now known to be at fault, with their messages. */
    fun described(): List<Pair<T, String>> {
        inARow = 0
        anyDescribed = true
        return drain()
    }

    /** The models failed on [item] with [message]. */
    fun modelsFailed(item: T, message: String) {
        suspects.add(item to message)
        inARow++
    }

    /** The models have failed on [stopAfter] files in a row: stop the run. */
    val shouldStop: Boolean get() = inARow >= stopAfter

    /**
     * The run ended (all its files tried). Returns the suspects at fault: all of them if the models
     * worked for some file in the run, else none (nothing shows the models work).
     */
    fun finished(): List<Pair<T, String>> = if (anyDescribed) drain() else emptyList<Pair<T, String>>().also { suspects.clear() }

    private fun drain(): List<Pair<T, String>> = suspects.toList().also { suspects.clear() }
}
