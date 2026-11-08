package com.lithic.gallery.core.search

import com.lithic.gallery.core.places.Gazetteer

/**
 * Places in a search: "beach in hawaii", "tokyo", "photos from paris". The place is taken out of
 * the query and becomes a filter on where each picture was taken; the rest is searched as words.
 *
 * Only the places the library has pictures from count ([parse]'s known names), and a name that
 * is also an ordinary word ("nice", "reading", "mobile") is a place only after "in", "at",
 * "near", "from", "around" or "to", or when it is the whole query: "nice beach" looks for nice
 * beaches. Without the phrase, a place's name is still found by keyword search, in the place
 * field.
 */
object PlacePhrases {
    /** A query with its place taken out: [text] the words left, [key] the place's normalized name, [label] how it reads. */
    data class PlaceQuery(val text: String, val key: String, val label: String)

    /**
     * [text] (a query, its date and request already taken out) with its place taken out, or null
     * when it names none of [known] (normalized name to label) that way.
     */
    fun parse(text: String, known: Map<String, String>): PlaceQuery? {
        if (known.isEmpty()) return null
        val words = QueryRequest.words(QueryRequest.normalize(text))
        for (i in words.indices) for (len in minOf(MAX_WORDS, words.size - i) downTo 1) {
            val key = Gazetteer.normalize(words.subList(i, i + len).joinToString(" "))
            val label = known[key] ?: continue
            // What leads into it: a preposition, perhaps with "the" after it.
            var start = i
            while (start > 0 && words[start - 1] in QueryRequest.DETERMINERS) start--
            val led = start > 0 && words[start - 1] in LEAD_IN
            if (led) start--
            val alone = start == 0 && i + len == words.size
            if (!led && !alone) continue
            val rest = words.subList(0, start) + words.subList(i + len, words.size)
            return PlaceQuery(rest.joinToString(" "), key, label)
        }
        return null
    }

    /** Words that make a place's name one: "in hawaii", "trip to paris". */
    private val LEAD_IN = setOf("in", "at", "near", "from", "around", "to", "visiting")

    /** The longest name looked for, in words ("rio de janeiro", "bosnia and herzegovina"). */
    private const val MAX_WORDS = 5
}
