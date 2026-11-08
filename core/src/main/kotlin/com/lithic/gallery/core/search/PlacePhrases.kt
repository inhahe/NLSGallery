package com.lithic.gallery.core.search

import com.lithic.gallery.core.places.Gazetteer

/**
 * Places in a search: "beach in hawaii", "tokyo", "photos from paris", "my trip to rome". The
 * place is taken out of the query and becomes a filter on where each picture was taken; the
 * rest is searched as words.
 *
 * Only the places the library has pictures from count ([parse]'s known names), and a name that
 * is also an ordinary word ("nice", "reading", "mobile") is a place only after "in", "at",
 * "near", "from", "around", "to" or "visiting" ("the" between only for a country: "in the uk"),
 * or when nothing but a request and words like "photos", "my", "trip" are with it: "nice beach"
 * looks for nice beaches, and so do "dinner in a nice restaurant" and "in the nice weather". A
 * place named without those ([namesIn]) ranks the pictures taken there higher among what the
 * words find.
 */
object PlacePhrases {
    /** A name the library's places go by: [label] how it reads, [country] when it names a country. */
    data class Known(val label: String, val country: Boolean = false)

    /**
     * A query with its place taken out: [text] the words left (empty when only a request and
     * filler were with it), [key] the place's normalized name, [label] how it reads,
     * [videosOnly] when the words with it asked for videos ("videos in paris").
     */
    data class PlaceQuery(val text: String, val key: String, val label: String, val videosOnly: Boolean = false)

    /**
     * [text] (a query, its date and request already taken out) with its place taken out, or null
     * when it names none of [known] (normalized names) that way.
     */
    fun parse(text: String, known: Map<String, Known>): PlaceQuery? {
        if (known.isEmpty()) return null
        val words = QueryRequest.words(QueryRequest.normalize(text))
        for (i in words.indices) for (len in minOf(MAX_WORDS, words.size - i) downTo 1) {
            val key = Gazetteer.normalize(words.subList(i, i + len).joinToString(" "))
            val name = known[key] ?: continue
            // What leads into it: a preposition, and "the" before a country ("in the uk"; a
            // town's own "the" is in its name: "the hague").
            var start = i
            if (name.country && start > 0 && words[start - 1] == "the") start--
            val led = start > 0 && words[start - 1] in LEAD_IN
            if (led) start-- else start = i
            // The words around it, without the request they open with ("photos near nice",
            // "videos in paris") and a closing "videos".
            val around = words.subList(0, start) + words.subList(i + len, words.size)
            val request = QueryRequest.read(around, nounAlone = true)
            val (rest, videos) = QueryRequest.trailingVideos(around.drop(request.length))
            val alone = rest.all { it in FILLER }
            if (!led && !alone) continue
            return PlaceQuery(if (alone) "" else rest.joinToString(" "), key, name.label, request.videosOnly || videos)
        }
        return null
    }

    /**
     * The names of [known] places in [text] (normalized names, the longest where they overlap),
     * however they are put: "beach hawaii", "paris hilton".
     */
    fun namesIn(text: String, known: Map<String, Known>): List<String> {
        if (known.isEmpty()) return emptyList()
        val words = QueryRequest.words(QueryRequest.normalize(text))
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val len = (minOf(MAX_WORDS, words.size - i) downTo 1).firstOrNull { len ->
                Gazetteer.normalize(words.subList(i, i + len).joinToString(" ")) in known
            }
            if (len == null) {
                i++
            } else {
                out.add(Gazetteer.normalize(words.subList(i, i + len).joinToString(" ")))
                i += len
            }
        }
        return out.distinct()
    }

    /** Words that make a place's name one: "in hawaii", "trip to paris". */
    private val LEAD_IN = setOf("in", "at", "near", "from", "around", "to", "visiting")

    /** Words that say nothing a picture taken there could be searched for by: "my trip to rome". */
    private val FILLER = LEAD_IN + QueryRequest.FILLER

    /** The longest name looked for, in words ("rio de janeiro", "bosnia and herzegovina"). */
    private const val MAX_WORDS = 5
}
