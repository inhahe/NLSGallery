package com.lithic.gallery.core.search

import java.util.Locale

/**
 * What a query asks for besides what it describes: "show me photos of dogs" is a search for
 * dogs, "videos of dogs" one for dogs among videos. The request is the words it opens with: a
 * verb ("show me", "find"), words like "all", "my", "the", "a", then a word for pictures or
 * videos followed by "of", "from", "with", "on"... A word for pictures that names something stays
 * ("photo booth", "video game"), and so does a query that makes no request.
 */
object QueryRequest {
    /** A query without its request: [text] to search for, and whether only videos are asked for. */
    data class Stripped(val text: String, val videosOnly: Boolean)

    /** [query] (as typed) without its request; the query itself, untouched, when it makes none. */
    fun strip(query: String): Stripped {
        val words = words(normalize(query))
        val r = read(words, nounAlone = false)
        return if (r.length == 0) Stripped(query.trim(), false) else Stripped(words.drop(r.length).joinToString(" "), r.videosOnly)
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
        val kind = noun in PICTURE_WORDS || noun in VIDEO_WORDS
        val next = words.getOrNull(i + 1)
        if (kind && (next in PREPOSITIONS || (next == null && (verb || determiner || nounAlone)))) {
            i += if (next == null) 1 else 2
            while (words.getOrNull(i) in DETERMINERS) i++
            return Request(i, videosOnly = noun in VIDEO_WORDS)
        }
        // A verb and its words alone ("find the photo booth": the booth's words stay).
        return Request(if (verb) i else 0, videosOnly = false)
    }

    /** The first [length] words of a query ask for something; [videosOnly] when that is videos. */
    internal data class Request(val length: Int, val videosOnly: Boolean)

    internal val VERBS = setOf("show", "find")
    internal val DETERMINERS = setOf("all", "my", "the", "a", "an", "any", "some")
    internal val PICTURE_WORDS = setOf("photo", "photos", "picture", "pictures", "pic", "pics", "image", "images", "shots")
    internal val VIDEO_WORDS = setOf("video", "videos", "clip", "clips")
    internal val PREPOSITIONS = setOf("of", "from", "with", "showing", "taken", "shot", "in", "on", "at", "during", "around")

    /** Lowercase, curly quotes and dashes made plain, punctuation that is not part of a date out. */
    internal fun normalize(s: String): String = s.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('‘', '\'').replace('`', '\'').replace('–', '-').replace('—', '-')
        .replace(Regex("[,;!?()\"]"), " ")
        .replace(Regex("\\.(?= |$)"), " ")
        .replace(Regex("\\s+"), " ").trim()

    internal fun words(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }
}
