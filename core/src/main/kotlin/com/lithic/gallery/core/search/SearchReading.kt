package com.lithic.gallery.core.search

/**
 * A search query as read: [text] as typed, the [date] and [place] read in it, the [words] to
 * search for (the query itself unless a date, a place or a request was taken out; empty when
 * nothing is left, and every file allowed is listed), [videosOnly] when it asks for videos, and
 * [named] the places named in passing ("beach hawaii": normalized names), whose pictures rank
 * higher among what the words find.
 */
data class SearchReading(
    val text: String,
    val date: DatePhrases.DateQuery?,
    val place: PlacePhrases.PlaceQuery?,
    val words: String,
    val videosOnly: Boolean,
    val named: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = SearchReading("", null, null, "", false)

        /**
         * Reads [text]: its date by [dates] (null when the user set the date aside), then its
         * place among [known] (by normalized name; none when [placeOff], the user having set it
         * aside: its words are then searched as words, nothing ranked by place).
         */
        fun read(text: String, dates: DatePhrases?, known: Map<String, PlacePhrases.Known>, placeOff: Boolean): SearchReading {
            if (text.isBlank()) return SearchReading(text, null, null, "", false)
            val date = dates?.parse(text)
            val (rest, videos) = date?.let { it.text to it.videosOnly } ?: QueryRequest.strip(text).let { it.text to it.videosOnly }
            if (placeOff) return SearchReading(text, date, null, rest, videos)
            val place = PlacePhrases.parse(rest, known)
            if (place != null && date == null && dates != null) {
                // A date that is one only on its own, the place taken out ("christmas in paris").
                val dateAfter = dates.parse(place.text)
                if (dateAfter != null) {
                    return SearchReading(text, dateAfter, place, dateAfter.text,
                        videos || place.videosOnly || dateAfter.videosOnly, PlacePhrases.namesIn(dateAfter.text, known))
                }
            }
            if (place == null && date != null) {
                // A place after a date phrase has lost its "in" to it ("beach last summer in
                // hawaii" leaves "beach hawaii"): read the place first, if the date then reads
                // in what is left.
                val placeFirst = PlacePhrases.parse(QueryRequest.strip(text).text, known)
                val dateAfter = placeFirst?.let { dates.parse(it.text) }
                if (placeFirst != null && dateAfter != null) {
                    return SearchReading(text, dateAfter, placeFirst, dateAfter.text,
                        videos || placeFirst.videosOnly || dateAfter.videosOnly, PlacePhrases.namesIn(dateAfter.text, known))
                }
            }
            val words = place?.text ?: rest
            return SearchReading(text, date, place, words, videos || place?.videosOnly == true, PlacePhrases.namesIn(words, known))
        }
    }
}
