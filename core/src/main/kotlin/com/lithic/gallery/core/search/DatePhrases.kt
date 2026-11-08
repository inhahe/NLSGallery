package com.lithic.gallery.core.search

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Dates in a search: "last summer", "beach 2023", "flowers in may", "christmas", "two weeks
 * ago". [parse] takes the phrase out of the query and turns it into a [DateFilter] on the day each
 * picture was taken; the rest of the query is searched as words. English only, as the models are.
 *
 * A word that is also an ordinary word (a month, a season, a holiday, a weekday) is a date only
 * when something makes it one: "last", "this", "in", a year, or nothing else in the query.
 * "christmas tree" looks for Christmas trees, "christmas" for pictures taken at Christmas, and
 * "may" is never a date on its own.
 */
class DatePhrases(
    /** Today where the phone is. */
    private val today: LocalDate,
    /** Seasons the other way round (Australia, South America, southern Africa). */
    private val southern: Boolean = false,
    /** The day weeks start on ("last week"). */
    private val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    /** Whether 3/5/2024 is March 5 (the United States) rather than 3 May. */
    private val monthFirst: Boolean = true,
    /** Canada's Thanksgiving (the second Monday of October) rather than the United States'. */
    private val canadianThanksgiving: Boolean = false,
    /** How days are written in labels. */
    private val locale: Locale = Locale.US,
) {
    /**
     * A query with its date phrase taken out: [text] is what is left to search for as words
     * (empty when the query was only a date), [videosOnly] when it asked for videos ("videos from
     * last summer").
     */
    data class DateQuery(val text: String, val filter: DateFilter, val videosOnly: Boolean = false)

    /** The days from [from] to [until], [until] excluded. */
    data class Span(val from: LocalDate, val until: LocalDate) {
        init {
            require(from.isBefore(until)) { "empty span $from..$until" }
        }

        operator fun contains(day: LocalDate) = !day.isBefore(from) && day.isBefore(until)
    }

    /** The days a phrase stands for. */
    sealed interface DateFilter {
        fun matches(day: LocalDate): Boolean

        /** What the phrase was taken to mean, for the user to check ("last week: Oct 25 – Oct 31, 2026"). */
        val label: String
    }

    /** The days in [spans]. */
    data class Within(val spans: List<Span>, override val label: String) : DateFilter {
        override fun matches(day: LocalDate) = spans.any { day in it }
    }

    /** The same days every year: [spansOf] gives a year's (a northern winter's reaches into the next). */
    class EveryYear(override val label: String, private val spansOf: (Int) -> List<Span>) : DateFilter {
        /** Each year's spans, made once: a library is matched file by file. */
        private val byYear = java.util.concurrent.ConcurrentHashMap<Int, List<Span>>()

        override fun matches(day: LocalDate) =
            (day.year - 1..day.year + 1).any { y -> byYear.computeIfAbsent(y) { spansOf(it) }.any { day in it } }
    }

    /** [query] with its date phrase taken out, or null when it has none. */
    fun parse(query: String): DateQuery? {
        val q = " " + QueryRequest.normalize(query) + " "
        for (rule in RULES) for (m in rule.regex.findAll(q)) {
            // "show me photos of dogs from last summer": the request the query opens with is not
            // searched for (read before "from" is taken: it ends "photos from"), and the words
            // leading into the phrase go with it.
            val ahead = QueryRequest.words(q.substring(0, m.range.first))
            val behind = QueryRequest.words(q.substring(m.range.last + 1))
            val request = QueryRequest.read(ahead + behind, nounAlone = true)
            // Words after it may open with their own ("last summer at the beach": the beach).
            val after = behind.drop(maxOf(0, request.length - ahead.size)).dropWhile { it in LEAD_IN || it in QueryRequest.DETERMINERS }
            val (rest, videos) = QueryRequest.trailingVideos(ahead.drop(request.length).dropLastWhile { it in LEAD_IN } + after)
            val text = if (rest.all { it in FILLER }) "" else rest.joinToString(" ")
            if (rule.alone && text.isNotEmpty()) continue
            val filter = rule.make(this, m.groupValues) ?: continue
            return DateQuery(text, filter, request.videosOnly || videos)
        }
        return null
    }

    // Rules, tried in order: the first whose phrase is in the query and makes sense wins.

    /**
     * A phrase ([pattern], between spaces) and the filter it stands for on a given today, from its
     * groups (0 the whole); [alone] when it is a date only as the whole query.
     */
    private class Rule(pattern: String, val alone: Boolean = false, val make: DatePhrases.(List<String>) -> DateFilter?) {
        val regex = Regex("(?<= )(?:$pattern)(?= )")
    }

    private val tomorrow: LocalDate = today.plusDays(1)
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)


    // Filters.

    private fun format(d: LocalDate): String = dayFormat.format(d)

    /** "Jun 1, 2026 – Aug 31, 2026", or the one day. */
    private fun range(s: Span): String {
        val last = s.until.minusDays(1)
        return if (last == s.from) format(s.from) else "${format(s.from)} – ${format(last)}"
    }

    /** [span], labelled by a [name] that says which days it is ("2023", "March 2023"). */
    private fun named(name: String, span: Span) = Within(listOf(span), name)

    /** [span], labelled with its days: "last week: Oct 25, 2026 – Oct 31, 2026". */
    private fun explained(name: String, span: Span) = Within(listOf(span), "$name: ${range(span)}")

    private fun jan1(year: Int) = LocalDate.of(year, 1, 1)

    /** A two-digit year as the latest such year not after this one ("24" is 2024, "98" 1998). */
    private fun year(s: String): Int {
        val n = s.toInt()
        if (s.length == 4) return n
        val century = today.year / 100 * 100
        return if (century + n <= today.year) century + n else century - 100 + n
    }

    private fun yearRange(a: Int, b: Int): DateFilter {
        val from = minOf(a, b)
        val to = maxOf(a, b)
        return named(if (from == to) "$from" else "$from–$to", Span(jan1(from), jan1(to + 1)))
    }

    private fun day(year: Int, month: Int, day: Int): DateFilter? = try {
        LocalDate.of(year, month, day).let { named(format(it), Span(it, it.plusDays(1))) }
    } catch (e: DateTimeException) {
        null
    }

    /** The latest [month] [day] not after today: this year's, or an earlier one while it is still to come. */
    private fun latestDay(month: Int, day: Int): DateFilter? {
        if (month !in 1..12 || day !in 1..31) return null
        for (y in today.year downTo today.year - 8) {
            val d = try { LocalDate.of(y, month, day) } catch (e: DateTimeException) { continue }
            if (!d.isAfter(today)) return named(format(d), Span(d, d.plusDays(1)))
        }
        return null
    }

    /** The [n] [unit]s up to today: "the past 3 months". */
    private fun rolling(n: Int, unit: String): DateFilter? {
        if (n !in 1..MAX_COUNT) return null
        val from = today.minus(n.toLong(), unitOf(unit)).plusDays(1)
        return explained("the past ${count(n, unit)}", Span(from, tomorrow))
    }

    /**
     * "[ns] [unit]s ago" ([phrase]): the day, the week or the month that was. A year is looser:
     * "a year ago" is around this time last year (a month either side), "5 years ago" that year.
     */
    private fun ago(phrase: String, ns: IntRange, unit: String): DateFilter? {
        if (ns.first < 1 || ns.last > MAX_COUNT) return null
        val u = unitOf(unit)
        fun around(n: Int): Span {
            val d = today.minus(n.toLong(), u)
            return when (u) {
                ChronoUnit.DAYS -> Span(d, d.plusDays(1))
                ChronoUnit.WEEKS -> weekStart(d).let { Span(it, it.plusWeeks(1)) }
                ChronoUnit.MONTHS -> d.withDayOfMonth(1).let { Span(it, it.plusMonths(1)) }
                else -> if (n == 1) d.withDayOfMonth(1).minusMonths(1).let { Span(it, it.plusMonths(3)) }
                    else Span(jan1(d.year), jan1(d.year + 1))
            }
        }
        return explained(phrase, Span(around(ns.last).from, around(ns.first).until))
    }

    private fun calendar(last: Boolean, unit: String): DateFilter {
        val which = if (last) "last" else "this"
        return when (unit) {
            "week" -> {
                val start = weekStart(today).minusWeeks(if (last) 1 else 0)
                explained("$which week", Span(start, if (last) start.plusWeeks(1) else tomorrow))
            }
            "weekend" -> {
                // This weekend while it is the weekend, else the one just gone; last weekend the one before.
                val saturday = when (today.dayOfWeek) {
                    DayOfWeek.SATURDAY -> today.minusWeeks(if (last) 1 else 0)
                    DayOfWeek.SUNDAY -> today.minusDays(if (last) 8 else 1)
                    else -> today.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY))
                }
                explained("$which weekend", Span(saturday, saturday.plusDays(2)))
            }
            "month" -> {
                val start = today.withDayOfMonth(1).minusMonths(if (last) 1 else 0)
                explained("$which month", Span(start, if (last) start.plusMonths(1) else tomorrow))
            }
            else -> {
                val start = jan1(today.year - if (last) 1 else 0)
                explained("$which year", Span(start, if (last) start.plusYears(1) else tomorrow))
            }
        }
    }

    private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

    /** The latest [name] (a weekday): today's counts unless [strictlyBefore] ("last friday" on a Friday is a week ago). */
    private fun weekday(name: String, strictlyBefore: Boolean): DateFilter {
        val dow = DayOfWeek.valueOf(name.uppercase(Locale.ROOT))
        val d = today.with(if (strictlyBefore) TemporalAdjusters.previous(dow) else TemporalAdjusters.previousOrSame(dow))
        return named(format(d), Span(d, d.plusDays(1)))
    }

    /**
     * Of a yearly thing's occurrences ([of] gives the one in a year, a single span): with [last],
     * the latest that is over; else ("this") the one under way, else the latest this year has
     * seen, else the next.
     */
    private fun occurrence(last: Boolean, of: (Int) -> Within): DateFilter {
        val around = (today.year - 2..today.year + 1).map(of)
        fun Within.span() = spans.single()
        return if (last) around.filter { !it.span().until.isAfter(today) }.maxBy { it.span().from }
        else around.firstOrNull { today in it.span() }
            ?: around.filter { !it.span().from.isAfter(today) && it.span().until.minusDays(1).year == today.year }.maxByOrNull { it.span().from }
            ?: around.filter { it.span().from.isAfter(today) }.minBy { it.span().from }
    }

    // Seasons: whole months (meteorological), turned round in the south.

    /** [season]'s months, from its first: a northern winter is 12, 1, 2. */
    private fun seasonMonths(season: String): List<Int> {
        val north = when (season) {
            "spring" -> 3
            "summer" -> 6
            "autumn", "fall" -> 9
            else -> 12
        }
        val start = if (southern) (north + 5) % 12 + 1 else north
        return (0..2).map { (start - 1 + it) % 12 + 1 }
    }

    private fun seasonName(season: String) = if (season == "fall") "autumn" else season

    /** The [season] that begins in [year]: "winter 2025–26: Dec 1, 2025 – Feb 28, 2026". */
    private fun seasonSpan(season: String, year: Int): Within {
        val from = LocalDate.of(year, seasonMonths(season).first(), 1)
        val span = Span(from, from.plusMonths(3))
        val endYear = span.until.minusDays(1).year
        val name = "${seasonName(season)} $year" + if (endYear != year) "–${endYear % 100}" else ""
        return explained(name, span)
    }

    /** "winter 2023": the season's months in 2023 (January, February and December, in the north). */
    private fun seasonIn(season: String, year: Int): DateFilter {
        val spans = ArrayList<Span>()
        for (m in seasonMonths(season).sorted()) {
            val from = LocalDate.of(year, m, 1)
            val last = spans.lastOrNull()
            if (last != null && last.until == from) spans[spans.size - 1] = Span(last.from, from.plusMonths(1))
            else spans.add(Span(from, from.plusMonths(1)))
        }
        return Within(spans, "${seasonName(season)} $year: ${spans.joinToString(" and ") { range(it) }}")
    }

    private fun everySeason(season: String): DateFilter {
        val months = seasonMonths(season)
        return EveryYear("every ${seasonName(season)} (${monthName(months.first())} – ${monthName(months.last())})") { y ->
            seasonSpan(season, y).spans
        }
    }

    // Months.

    private fun monthName(m: Int): String = Month.of(m).getDisplayName(TextStyle.FULL_STANDALONE, locale)

    private fun monthSpan(month: Int, year: Int): Within =
        LocalDate.of(year, month, 1).let { named("${monthName(month)} $year", Span(it, it.plusMonths(1))) }

    private fun everyMonth(month: Int): DateFilter = EveryYear("every ${monthName(month)}") { y -> monthSpan(month, y).spans }

    // Holidays.

    private enum class Holiday(val title: String) {
        CHRISTMAS("Christmas"), CHRISTMAS_EVE("Christmas Eve"), CHRISTMAS_DAY("Christmas Day"),
        NEW_YEARS_EVE("New Year's Eve"), NEW_YEARS_DAY("New Year's Day"), NEW_YEAR("New Year"),
        HALLOWEEN("Halloween"), VALENTINES("Valentine's Day"), FOURTH_OF_JULY("Fourth of July"),
        EASTER("Easter"), THANKSGIVING("Thanksgiving"),
    }

    private fun holiday(s: String): Holiday = when (s.replace("'", "")) {
        "christmas", "xmas" -> Holiday.CHRISTMAS
        "christmas eve" -> Holiday.CHRISTMAS_EVE
        "christmas day" -> Holiday.CHRISTMAS_DAY
        "new years eve" -> Holiday.NEW_YEARS_EVE
        "new years day" -> Holiday.NEW_YEARS_DAY
        "new years", "new year" -> Holiday.NEW_YEAR
        "halloween" -> Holiday.HALLOWEEN
        "valentines day", "valentines", "valentine" -> Holiday.VALENTINES
        "fourth of july", "july fourth", "independence day" -> Holiday.FOURTH_OF_JULY
        "easter" -> Holiday.EASTER
        "thanksgiving" -> Holiday.THANKSGIVING
        else -> throw IllegalArgumentException("'$s' is in HOLIDAY but has no Holiday")
    }

    /** [name] (a holiday) in [year], with the days around it that belong to it. */
    private fun holidaySpan(name: String, year: Int): Within {
        val h = holiday(name)
        val span = when (h) {
            Holiday.CHRISTMAS -> days(LocalDate.of(year, 12, 24), 3) // Christmas Eve to Boxing Day
            Holiday.CHRISTMAS_EVE -> days(LocalDate.of(year, 12, 24), 1)
            Holiday.CHRISTMAS_DAY -> days(LocalDate.of(year, 12, 25), 1)
            Holiday.NEW_YEARS_EVE -> days(LocalDate.of(year, 12, 31), 1)
            Holiday.NEW_YEARS_DAY -> days(LocalDate.of(year, 1, 1), 1)
            Holiday.NEW_YEAR -> days(LocalDate.of(year - 1, 12, 31), 2) // the night it begins, and its first day
            Holiday.HALLOWEEN -> days(LocalDate.of(year, 10, 31), 1)
            Holiday.VALENTINES -> days(LocalDate.of(year, 2, 14), 1)
            Holiday.FOURTH_OF_JULY -> days(LocalDate.of(year, 7, 4), 1)
            Holiday.EASTER -> days(easterSunday(year).minusDays(2), 4) // Good Friday to Easter Monday
            Holiday.THANKSGIVING ->
                if (canadianThanksgiving) { // the long weekend ending on its Monday
                    days(LocalDate.of(year, 10, 1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.MONDAY)).minusDays(2), 3)
                } else { // Thursday to Sunday
                    days(LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)), 4)
                }
        }
        return explained("${h.title} $year", span)
    }

    private fun everyHoliday(name: String): DateFilter = EveryYear("every ${holiday(name).title}") { y -> holidaySpan(name, y).spans }

    private fun days(from: LocalDate, n: Int) = Span(from, from.plusDays(n.toLong()))

    /** Easter Sunday in [year] (the Gregorian computus: Meeus, Jones, Butcher). */
    private fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /**
     * A decade: of [century] (19 or 20), or with none the latest that has begun ("the 20s" is
     * the 2020s, "the 90s" the 1990s). [tens] is its third digit.
     */
    private fun decade(century: Int?, tens: Int): DateFilter {
        val c = century ?: if (2000 + tens * 10 <= today.year) 20 else 19
        val start = c * 100 + tens * 10
        return named("the ${start}s", Span(jan1(start), jan1(start + 10)))
    }

    // Words.

    private fun unitOf(unit: String): ChronoUnit = when (unit.removeSuffix("s")) {
        "day" -> ChronoUnit.DAYS
        "week" -> ChronoUnit.WEEKS
        "month" -> ChronoUnit.MONTHS
        else -> ChronoUnit.YEARS
    }

    private fun count(n: Int, unit: String): String {
        val one = unit.removeSuffix("s")
        return if (n == 1) one else "$n ${one}s"
    }

    /** How many a number phrase stands for: "a few" is 2 to 4. */
    private fun numbers(s: String): IntRange = when {
        "couple" in s -> 2..2
        "few" in s -> 2..4
        s == "several" -> 3..7
        s == "a" || s == "an" -> 1..1
        else -> (s.toIntOrNull() ?: NUMBER_WORDS.indexOf(s)).let { it..it }
    }

    private fun month(s: String): Int = MONTH_PREFIXES.indexOfFirst { s.startsWith(it) } + 1

    companion object {
        private val RULES: List<Rule> = listOf(
            // Years: ranges and bounds.
            Rule("between $YEAR and $YEAR") { g -> yearRange(g[1].toInt(), g[2].toInt()) },
            Rule("$YEAR ?(?:-|to|until|till|through) ?$YEAR") { g -> yearRange(g[1].toInt(), g[2].toInt()) },
            // Open-ended: a picture dated ahead by a wrong clock still counts, and "after 2026" in
            // 2026 is an empty date, not 2026.
            Rule("since $YEAR") { g -> named(g[0], Span(jan1(g[1].toInt()), LATEST)) },
            Rule("after $YEAR") { g -> named(g[0], Span(jan1(g[1].toInt() + 1), LATEST)) },
            Rule("before $YEAR") { g -> Within(listOf(Span(EARLIEST, jan1(g[1].toInt()))), g[0]) },
            // Days.
            Rule("(\\d{4})-(\\d{1,2})-(\\d{1,2})") { g -> day(g[1].toInt(), g[2].toInt(), g[3].toInt()) },
            Rule("(\\d{1,2})/(\\d{1,2})/(\\d{4}|\\d{2})") { g ->
                val a = g[1].toInt()
                val b = g[2].toInt()
                if (monthFirst) day(year(g[3]), a, b) else day(year(g[3]), b, a)
            },
            Rule("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2})") { g -> day(year(g[3]), g[2].toInt(), g[1].toInt()) },
            Rule("$MONTH $DAY $YEAR") { g -> day(g[3].toInt(), month(g[1]), g[2].toInt()) },
            Rule("$DAY (?:of )?$MONTH $YEAR") { g -> day(g[3].toInt(), month(g[2]), g[1].toInt()) },
            Rule("$MONTH $DAY") { g -> latestDay(month(g[1]), g[2].toInt()) },
            Rule("$DAY (?:of )?$MONTH") { g -> latestDay(month(g[2]), g[1].toInt()) },
            Rule("today|tonight|this morning|this afternoon|this evening") { g -> explained(g[0], Span(today, tomorrow)) },
            Rule("the day before yesterday") { g -> explained(g[0], Span(today.minusDays(2), today.minusDays(1))) },
            Rule("yesterday") { g -> explained(g[0], Span(today.minusDays(1), today)) },
            Rule("last night") { g -> explained(g[0], Span(today.minusDays(1), tomorrow)) },
            // Counting back: "the past 3 months", "two weeks ago".
            Rule("(?:the )?(?:past|last|previous) $NUMBER $UNIT") { g -> rolling(numbers(g[1]).last, g[2]) },
            Rule("(?:the )?past (day|week|month|year)") { g -> rolling(1, g[1]) },
            Rule("$NUMBER $UNIT ago") { g -> ago(g[0], numbers(g[1]), g[2]) },
            // The calendar: "last week", "this month", "last weekend".
            Rule("(last|this|previous) (week|weekend|month|year)") { g -> calendar(g[1] != "this", g[2]) },
            // The latest of something yearly: "last summer", "this march", "last christmas".
            Rule("(last|this|previous) $SEASON") { g -> occurrence(g[1] != "this") { y -> seasonSpan(g[2], y) } },
            Rule("(last|this|previous) $HOLIDAY") { g -> occurrence(g[1] != "this") { y -> holidaySpan(g[2], y) } },
            Rule("(last|this|previous) $MONTH") { g -> occurrence(g[1] != "this") { y -> monthSpan(month(g[2]), y) } },
            Rule("(?:last|previous) $WEEKDAY") { g -> weekday(g[1], strictlyBefore = true) },
            Rule("(?:this|on) $WEEKDAY") { g -> weekday(g[1], strictlyBefore = false) },
            // With a year: "summer 2023", "christmas of 2022", "march 2023".
            Rule("$SEASON (?:of )?$YEAR") { g -> seasonIn(g[1], g[2].toInt()) },
            Rule("$YEAR $SEASON") { g -> seasonIn(g[2], g[1].toInt()) },
            Rule("$HOLIDAY (?:of )?$YEAR") { g -> holidaySpan(g[1], g[2].toInt()) },
            Rule("$YEAR $HOLIDAY") { g -> holidaySpan(g[2], g[1].toInt()) },
            Rule("$MONTH (?:of )?$YEAR") { g -> monthSpan(month(g[1]), g[2].toInt()) },
            Rule("$YEAR $MONTH") { g -> monthSpan(month(g[2]), g[1].toInt()) },
            // Decades: "the 90s", "1990s". A bare "90s" is also a style ("90s fashion").
            Rule("(?:the )?(19|20)(\\d)0'?s") { g -> decade(g[1].toInt(), g[2].toInt()) },
            Rule("the (\\d)0'?s") { g -> decade(null, g[1].toInt()) },
            Rule("(\\d)0'?s", alone = true) { g -> decade(null, g[1].toInt()) },
            // A year, not one still to come ("2048" is more likely the game).
            Rule(YEAR) { g -> g[1].toInt().takeIf { it <= today.year }?.let { named(it.toString(), Span(jan1(it), jan1(it + 1))) } },
            // Every year: "in summer", "in july", "at christmas".
            Rule("(?:in|during) (?:the )?$SEASON") { g -> everySeason(g[1]) },
            Rule("(?:in|during) $MONTH") { g -> everyMonth(month(g[1])) },
            Rule("(?:on|at|over|during) $HOLIDAY") { g -> everyHoliday(g[1]) },
            // Words that are dates only when they are all there is.
            Rule(SEASON, alone = true) { g -> everySeason(g[1]) },
            Rule(HOLIDAY, alone = true) { g -> everyHoliday(g[1]) },
            Rule(MONTH_ALONE, alone = true) { g -> everyMonth(month(g[1])) },
            Rule(WEEKDAY, alone = true) { g -> weekday(g[1], strictlyBefore = false) },
        )

        private const val YEAR = "((?:19|20)\\d\\d)"
        private const val DAY = "(\\d{1,2})(?:st|nd|rd|th)?"
        private const val MONTH = "(january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
        /** Months that may stand alone: not "may", and not the abbreviations. */
        private const val MONTH_ALONE = "(january|february|march|april|june|july|august|september|october|november|december)"
        private const val SEASON = "(spring|summer|autumn|fall|winter)"
        private const val HOLIDAY = "(christmas eve|christmas day|christmas|xmas|new year'?s eve|new year'?s day|new year'?s|new year|" +
            "halloween|valentine'?s day|valentine'?s|valentine|thanksgiving|easter|fourth of july|july fourth|independence day)"
        private const val WEEKDAY = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
        private const val NUMBER = "(\\d{1,3}|a couple of|a couple|couple of|a few|few|several|an?|one|two|three|four|five|six|seven|" +
            "eight|nine|ten|eleven|twelve)"
        private const val UNIT = "(days?|weeks?|months?|years?)"
        private val NUMBER_WORDS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
        private val MONTH_PREFIXES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        /** "the past 500 years" is not a date anyone means. */
        private const val MAX_COUNT = 200
        private val EARLIEST: LocalDate = LocalDate.of(1800, 1, 1)
        private val LATEST: LocalDate = LocalDate.of(9999, 1, 1)

        /** Words that lead into a date ("taken in 2023", "from last summer"): they go with it. */
        private val LEAD_IN = setOf("in", "on", "from", "during", "at", "around", "taken", "shot", "of")

        /** Words that, left on their own, are nothing to search for: "pictures of me from last summer". */
        private val FILLER = LEAD_IN + QueryRequest.PICTURE_WORDS + QueryRequest.VERBS + QueryRequest.DETERMINERS + "me"


        /**
         * Phrases read as they are meant where [locale] is set: which hemisphere's seasons, the
         * first day of the week, which number comes first in 3/5/2024, which Thanksgiving.
         */
        fun forLocale(today: LocalDate, locale: Locale): DatePhrases {
            val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
            return DatePhrases(
                today,
                southern = southernCountry(locale.country),
                firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek,
                monthFirst = pattern.indexOf('M').let { m -> m >= 0 && m < pattern.indexOf('d') },
                canadianThanksgiving = locale.country.equals("CA", ignoreCase = true),
                locale = locale,
            )
        }

        /**
         * Whether [country] (ISO 3166) has the southern hemisphere's seasons: Australia, New
         * Zealand, southern Africa, South America below the equator.
         */
        fun southernCountry(country: String): Boolean = country.uppercase(Locale.ROOT) in setOf(
            "AU", "NZ", "ZA", "NA", "BW", "ZW", "MZ", "LS", "SZ", "MG", "MU", "AR", "CL", "UY", "PY", "BO", "PE", "BR", "FJ", "NC",
        )
    }
}
