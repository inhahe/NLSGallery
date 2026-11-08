package com.lithic.gallery.work

/**
 * Tells, over one describe run, the files the models fail on from models that fail on anything
 * (out of memory, say). A file the models failed on is a suspect until the models are seen to
 * work: another file in the run described, or a probe run through them. Then the suspects'
 * failures are theirs. [stopAfter] failures in a row call for that probe ([shouldProbe]).
 *
 * @param T the run's items.
 */
class ModelFailureTracker<T>(private val stopAfter: Int = 3) {
    private val suspects = ArrayList<Pair<T, String>>()
    private var inARow = 0
    private var worked = false

    /**
     * The models worked: a file was described, or a probe went through. Returns the suspects now
     * known to be at fault, with their messages.
     */
    fun modelsWorked(): List<Pair<T, String>> {
        inARow = 0
        worked = true
        return drain()
    }

    /** The models failed on [item] with [message]. */
    fun modelsFailed(item: T, message: String) {
        suspects.add(item to message)
        inARow++
    }

    /** The models have failed on [stopAfter] files in a row: see whether they work at all. */
    val shouldProbe: Boolean get() = inARow >= stopAfter

    /**
     * The run ended (all its files tried). Returns the suspects at fault: all of them if the models
     * worked in the run, else none (nothing shows the models work).
     */
    fun finished(): List<Pair<T, String>> = if (worked) drain() else emptyList<Pair<T, String>>().also { suspects.clear() }

    private fun drain(): List<Pair<T, String>> = suspects.toList().also { suspects.clear() }
}
