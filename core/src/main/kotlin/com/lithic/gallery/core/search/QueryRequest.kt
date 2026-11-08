package com.lithic.gallery.core.search

import java.util.Locale

/**
 * What a query asks for besides what it describes: "show me photos of dogs" is a search for
 * dogs, "videos of dogs" or "dog videos" one for dogs among videos. The request is the words it
 * opens with: a verb ("show me", "find"), words like "all", "my", "the", "a", then a word for
 * pictures followed by "of", "from", "on"..., or "videos" followed by "of", "from", "taken"...;
 * or "videos" ending it. A word that names something stays ("photo booth", "video game"), and so
 * does a query that is nothing but a request's words ("find my": Find My screenshots).
 *
 * Asking for videos narrows a search, so it is read only from words that cannot mean anything
 * else: "video on youtube" and "clip on earrings" stay as typed.
 */
object QueryRequest {
    /** A query without its request: [text] to search for, and whether only videos are asked for. */
    data class Stripped(val text: String, val videosOnly: Boolean)

    /** [query] (as typed) without its request; the query itself, untouched, when it makes none. */
    fun strip(query: String): Stripped {
        val words = words(normalize(query))
        val r = read(words, nounAlone = false)
        val (rest, videos) = trailingVideos(words.drop(r.length))
        return if (r.length == 0 && !videos) Stripped(query.trim(), false)
        else Stripped(rest.joinToString(" "), r.videosOnly || videos)
    }

    /**
     * The request [words] (normalized) open with: how many words it takes, and whether it asks
     * for videos only. With [nounAlone], a word for pictures or videos with nothing after it is a
     * request on its own ("videos" before a date: videos taken then).
     */
    internal fun read(words: List<String>, nounAlone: Boolean): Request {
        var i = 0
        val verb = words.getOrNull(0) in VERBS
        if (verb) {
            i++
            if (words.getOrNull(i) == "me") i++
        }
        var determiner = false
        while (words.getOrNull(i) in DETERMINERS) {
            i++
            determiner = true
        }
        val noun = words.getOrNull(i)
        val next = words.getOrNull(i + 1)
        val kind = noun in PICTURE_WORDS || noun in VIDEO_WORDS
        val asks = when {
            !kind -> false
            next == null -> verb || determiner || nounAlone
            noun in VIDEO_WORDS -> next in VIDEO_PREPOSITIONS
            else -> next in PREPOSITIONS
        }
        if (asks) {
            i += if (next == null) 1 else 2
            while (words.getOrNull(i) in DETERMINERS) i++
            return Request(i, videosOnly = noun in VIDEO_WORDS)
        }
        // A verb and the words after it ("find the photo booth": the booth's words stay); a query
        // that is nothing but those is searched as typed ("find my", "show").
        return Request(if (verb && i < words.size) i else 0, videosOnly = false)
    }

    /** [words] without a closing "videos" ("dog videos"), and whether there was one. */
    internal fun trailingVideos(words: List<String>): Pair<List<String>, Boolean> =
        if (words.size >= 2 && words.last() in VIDEO_WORDS) words.dropLast(1) to true else words to false

    /** The first [length] words of a query ask for something; [videosOnly] when that is videos. */
    internal data class Request(val length: Int, val videosOnly: Boolean)

    internal val VERBS = setOf("show", "find")
    internal val DETERMINERS = setOf("all", "my", "the", "a", "an", "any", "some")
    internal val PICTURE_WORDS = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "shots")
    internal val VIDEO_WORDS = setOf("video", "videos")
    internal val PREPOSITIONS = setOf("of", "from", "with", "showing", "taken", "shot", "in", "on", "at", "during", "around")

    /**
     * Words that, left on their own beside a date or a place, are nothing to search for:
     * "pictures of me from last summer", "my trip to rome" ([DatePhrases] and [PlacePhrases] add
     * the words that lead into their phrases).
     */
    internal val FILLER = PICTURE_WORDS + VERBS + DETERMINERS +
        setOf("me", "of", "taken", "shot", "trip", "trips", "vacation", "vacations", "holiday", "holidays", "visit", "travel", "travels")
    /** After "videos", only what says when or what they show: "video on youtube" is a screenshot's words. */
    internal val VIDEO_PREPOSITIONS = setOf("of", "from", "showing", "taken", "shot", "during")

    private val PUNCTUATION = Regex("[,;!?()\"]")
    private val FULL_STOP = Regex("\\.(?= |$)")
    private val SPACES = Regex("\\s+")

    /** Lowercase, curly quotes and dashes made plain, punctuation that is not part of a date out. */
    internal fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('‘', '\'').replace('`', '\'').replace('–', '-').replace('—', '-')
        .replace(PUNCTUATION, " ")
        .replace(FULL_STOP, " ")
        .replace(SPACES, " ").trim()

    internal fun words(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }
}
