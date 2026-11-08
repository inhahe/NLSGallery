package com.lithic.gallery.work

/**
 * Tells, over one pass of a describe run (one set of models on files of one kind), the files the
 * models fail on from models that fail on anything (out of memory, say). A file the models failed
 * on is a suspect until the pass describes another file: then the models work, and the suspects'
 * failures are theirs. [giveUpAfter] failures with none described between say the models fail on
 * everything just now: the pass gives up ([shouldGiveUp]), blaming none of them.
 *
 * @param T the pass's items.
 */
class ModelFailureTracker<T>(private val giveUpAfter: Int = 6) {
    private val suspects = ArrayList<Pair<T, String>>()
    private var sinceWorked = 0
    private var worked = false

    /** The models worked (a file was described). Returns the suspects now known to be at fault. */
    fun modelsWorked(): List<Pair<T, String>> {
        sinceWorked = 0
        worked = true
        return drain()
    }

    /** The models failed on [item] with [message]. */
    fun modelsFailed(item: T, message: String) {
        suspects.add(item to message)
        sinceWorked++
    }

    /** The models failed on [giveUpAfter] files with none described between. */
    val shouldGiveUp: Boolean get() = sinceWorked >= giveUpAfter

    /** The pass gives up: its current suspects are no one's fault. */
    fun gaveUp() = suspects.clear()

    /**
     * The pass ended (all its files tried). Returns the suspects at fault: all of them if the
     * models worked in the pass, else none (nothing shows they work).
     */
    fun finished(): List<Pair<T, String>> = if (worked) drain() else emptyList<Pair<T, String>>().also { suspects.clear() }

    private fun drain(): List<Pair<T, String>> = suspects.toList().also { suspects.clear() }
}
