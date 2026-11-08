package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.core.search.DatePhrases.EveryYear
import com.lithic.gallery.core.search.DatePhrases.Span
import com.lithic.gallery.core.search.DatePhrases.Within
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** Date phrases read at a fixed today: Saturday 7 November 2026, in the United States unless said. */
class DatePhrasesTest {
    private val today = LocalDate.of(2026, 11, 7)
    private val us = DatePhrases(today, firstDayOfWeek = DayOfWeek.SUNDAY)
    private val south = DatePhrases(today, southern = true)

    private fun d(y: Int, m: Int, day: Int): LocalDate = LocalDate.of(y, m, day)

    /** The days [first] to [last], both included. */
    private fun days(first: LocalDate, last: LocalDate) = Span(first, last.plusDays(1))

    private fun day(y: Int, m: Int, dd: Int) = d(y, m, dd).let { days(it, it) }

    /** What [query] reads as: its spans, and the words left to search for. */
    private fun read(query: String, p: DatePhrases = us): Pair<List<Span>, String> {
        val r = p.parse(query) ?: throw AssertionError("no date read in '$query'")
        val w = r.filter as? Within ?: throw AssertionError("'$query' read as ${r.filter.label}, not as days")
        return w.spans to r.text
    }

    private fun assertSpan(query: String, want: Span, text: String = "", p: DatePhrases = us) {
        val (spans, rest) = read(query, p)
        assertWithMessage("'$query'").that(spans).containsExactly(want)
        assertWithMessage("'$query' left").that(rest).isEqualTo(text)
    }

    private fun assertNoDate(query: String, p: DatePhrases = us) {
        assertWithMessage("'$query'").that(p.parse(query)?.filter?.label).isNull()
    }

    /** [query] means the same days every year: [yes] among them, [no] not. */
    private fun assertEveryYear(query: String, yes: List<LocalDate>, no: List<LocalDate>, p: DatePhrases = us) {
        val r = p.parse(query) ?: throw AssertionError("no date read in '$query'")
        assertWithMessage("'$query' is every year").that(r.filter).isInstanceOf(EveryYear::class.java)
        for (day in yes) assertWithMessage("'$query' has $day").that(r.filter.matches(day)).isTrue()
        for (day in no) assertWithMessage("'$query' has $day").that(r.filter.matches(day)).isFalse()
    }

    @Test
    fun seasons() {
        assertSpan("last summer", days(d(2026, 6, 1), d(2026, 8, 31)))
        assertSpan("beach last summer", days(d(2026, 6, 1), d(2026, 8, 31)), "beach")
        assertSpan("last winter", days(d(2025, 12, 1), d(2026, 2, 28)))
        assertSpan("this summer", days(d(2026, 6, 1), d(2026, 8, 31)))
        assertSpan("this fall", days(d(2026, 9, 1), d(2026, 11, 30)))
        assertSpan("last autumn", days(d(2025, 9, 1), d(2025, 11, 30)))
        assertSpan("last fall", days(d(2025, 9, 1), d(2025, 11, 30)))
        assertSpan("this winter", days(d(2025, 12, 1), d(2026, 2, 28)))
        assertSpan("this spring", days(d(2026, 3, 1), d(2026, 5, 31)))
        assertSpan("summer of 2023", days(d(2023, 6, 1), d(2023, 8, 31)))
        assertSpan("2023 summer", days(d(2023, 6, 1), d(2023, 8, 31)))
        // A winter in a year is that year's winter months.
        assertThat(read("winter 2023").first).containsExactly(days(d(2023, 1, 1), d(2023, 2, 28)), days(d(2023, 12, 1), d(2023, 12, 31))).inOrder()
        assertEveryYear("summer", listOf(d(2019, 7, 1), d(2026, 6, 1)), listOf(d(2019, 9, 1), d(2020, 5, 31)))
        assertEveryYear("winter", listOf(d(2020, 1, 15), d(2019, 12, 1), d(2024, 2, 29)), listOf(d(2020, 3, 1), d(2019, 11, 30)))
        assertEveryYear("beach in summer", listOf(d(2021, 8, 1)), listOf(d(2021, 9, 1)))
        assertThat(us.parse("beach in summer")!!.text).isEqualTo("beach")
        assertEveryYear("fall", listOf(d(2021, 10, 1)), listOf(d(2021, 12, 1)))
        assertNoDate("beach summer")
        assertNoDate("spring water")
        assertNoDate("fall leaves")
    }

    @Test
    fun southernSeasons() {
        assertSpan("last summer", days(d(2025, 12, 1), d(2026, 2, 28)), p = south)
        assertSpan("last winter", days(d(2026, 6, 1), d(2026, 8, 31)), p = south)
        assertSpan("this spring", days(d(2026, 9, 1), d(2026, 11, 30)), p = south)
        assertThat(read("summer 2023", south).first).containsExactly(days(d(2023, 1, 1), d(2023, 2, 28)), days(d(2023, 12, 1), d(2023, 12, 31))).inOrder()
        assertEveryYear("summer", listOf(d(2020, 1, 15), d(2020, 12, 1)), listOf(d(2020, 7, 15)), p = south)
    }

    @Test
    fun months() {
        assertSpan("last march", days(d(2026, 3, 1), d(2026, 3, 31)))
        assertSpan("this march", days(d(2026, 3, 1), d(2026, 3, 31)))
        assertSpan("last march", days(d(2025, 3, 1), d(2025, 3, 31)), p = DatePhrases(d(2026, 3, 15)))
        assertSpan("last march", days(d(2026, 3, 1), d(2026, 3, 31)), p = DatePhrases(d(2026, 4, 1)))
        assertSpan("this march", days(d(2026, 3, 1), d(2026, 3, 31)), p = DatePhrases(d(2026, 3, 15)))
        assertSpan("last may", days(d(2026, 5, 1), d(2026, 5, 31)))
        assertSpan("march 2023", days(d(2023, 3, 1), d(2023, 3, 31)))
        assertSpan("dogs in dec 2022", days(d(2022, 12, 1), d(2022, 12, 31)), "dogs")
        assertSpan("2023 march", days(d(2023, 3, 1), d(2023, 3, 31)))
        assertEveryYear("in may", listOf(d(2019, 5, 10)), listOf(d(2019, 6, 1)))
        assertEveryYear("march", listOf(d(2019, 3, 3)), listOf(d(2019, 4, 1)))
        assertEveryYear("flowers in april", listOf(d(2024, 4, 30)), listOf(d(2024, 5, 1)))
        assertNoDate("may")
        assertNoDate("may i see")
        assertNoDate("march protest")
        assertNoDate("march of the penguins")
    }

    @Test
    fun holidays() {
        assertEveryYear("christmas", listOf(d(2019, 12, 24), d(2019, 12, 25), d(2019, 12, 26)), listOf(d(2019, 12, 23), d(2019, 12, 27)))
        assertNoDate("christmas tree")
        assertSpan("christmas tree last year", days(d(2025, 1, 1), d(2025, 12, 31)), "christmas tree")
        assertSpan("last christmas", days(d(2025, 12, 24), d(2025, 12, 26)))
        assertSpan("this christmas", days(d(2026, 12, 24), d(2026, 12, 26)))
        assertSpan("christmas 2022", days(d(2022, 12, 24), d(2022, 12, 26)))
        assertSpan("photos from christmas 2022", days(d(2022, 12, 24), d(2022, 12, 26)))
        assertSpan("2022 christmas", days(d(2022, 12, 24), d(2022, 12, 26)))
        assertSpan("christmas eve 2024", day(2024, 12, 24))
        assertEveryYear("family at christmas", listOf(d(2020, 12, 25)), listOf(d(2020, 12, 20)))
        assertThat(us.parse("family at christmas")!!.text).isEqualTo("family")
        assertSpan("new year's eve 2025", day(2025, 12, 31))
        assertSpan("new year’s eve 2025", day(2025, 12, 31))
        assertSpan("new years 2026", days(d(2025, 12, 31), d(2026, 1, 1)))
        assertSpan("new year's day 2026", day(2026, 1, 1))
        assertEveryYear("new year", listOf(d(2024, 12, 31), d(2025, 1, 1)), listOf(d(2025, 1, 2), d(2024, 12, 30)))
        assertSpan("last halloween", day(2026, 10, 31))
        assertSpan("valentine's day 2024", day(2024, 2, 14))
        assertEveryYear("valentines", listOf(d(2023, 2, 14)), listOf(d(2023, 2, 15)))
        assertEveryYear("valentine", listOf(d(2023, 2, 14)), listOf(d(2023, 2, 15)))
        assertSpan("fourth of july 2023", day(2023, 7, 4))
        assertSpan("july 4th", day(2026, 7, 4))
        assertSpan("the 4th of july", day(2026, 7, 4))
        assertEveryYear("fourth of july", listOf(d(2015, 7, 4)), listOf(d(2015, 7, 5)))
    }

    @Test
    fun easter() {
        // Easter Sundays from published tables.
        val sundays = mapOf(1999 to d(1999, 4, 4), 2000 to d(2000, 4, 23), 2008 to d(2008, 3, 23), 2011 to d(2011, 4, 24),
            2019 to d(2019, 4, 21), 2024 to d(2024, 3, 31), 2025 to d(2025, 4, 20), 2026 to d(2026, 4, 5), 2038 to d(2038, 4, 25))
        for ((year, sunday) in sundays) assertSpan("easter $year", days(sunday.minusDays(2), sunday.plusDays(1)))
        assertSpan("last easter", days(d(2026, 4, 3), d(2026, 4, 6)))
    }

    @Test
    fun thanksgiving() {
        assertSpan("thanksgiving 2025", days(d(2025, 11, 27), d(2025, 11, 30)))
        assertSpan("thanksgiving 2024", days(d(2024, 11, 28), d(2024, 12, 1)))
        assertSpan("thanksgiving 2023", days(d(2023, 11, 23), d(2023, 11, 26)))
        assertSpan("last thanksgiving", days(d(2025, 11, 27), d(2025, 11, 30)))
        assertSpan("this thanksgiving", days(d(2026, 11, 26), d(2026, 11, 29)))
        val canada = DatePhrases(today, canadianThanksgiving = true)
        assertSpan("thanksgiving 2025", days(d(2025, 10, 11), d(2025, 10, 13)), p = canada)
        assertSpan("thanksgiving 2024", days(d(2024, 10, 12), d(2024, 10, 14)), p = canada)
        assertSpan("last thanksgiving", days(d(2026, 10, 10), d(2026, 10, 12)), p = canada)
    }

    @Test
    fun recentDays() {
        assertSpan("today", day(2026, 11, 7))
        assertSpan("yesterday", day(2026, 11, 6))
        assertSpan("cat yesterday", day(2026, 11, 6), "cat")
        assertSpan("the day before yesterday", day(2026, 11, 5))
        assertSpan("last night", days(d(2026, 11, 6), d(2026, 11, 7)))
        assertSpan("last friday", day(2026, 11, 6))
        assertSpan("friday", day(2026, 11, 6))
        assertSpan("saturday", day(2026, 11, 7))
        assertSpan("last saturday", day(2026, 10, 31))
        assertSpan("on monday", day(2026, 11, 2))
        assertNoDate("sunday dress")
    }

    @Test
    fun calendar() {
        assertSpan("this week", days(d(2026, 11, 1), d(2026, 11, 7)))
        assertSpan("last week", days(d(2026, 10, 25), d(2026, 10, 31)))
        val monday = DatePhrases(today, firstDayOfWeek = DayOfWeek.MONDAY)
        assertSpan("this week", days(d(2026, 11, 2), d(2026, 11, 7)), p = monday)
        assertSpan("last week", days(d(2026, 10, 26), d(2026, 11, 1)), p = monday)
        assertSpan("this weekend", days(d(2026, 11, 7), d(2026, 11, 8)))
        assertSpan("last weekend", days(d(2026, 10, 31), d(2026, 11, 1)))
        val wednesday = DatePhrases(d(2026, 11, 4))
        assertSpan("this weekend", days(d(2026, 10, 31), d(2026, 11, 1)), p = wednesday)
        assertSpan("last weekend", days(d(2026, 10, 31), d(2026, 11, 1)), p = wednesday)
        val sunday = DatePhrases(d(2026, 11, 8))
        assertSpan("this weekend", days(d(2026, 11, 7), d(2026, 11, 8)), p = sunday)
        assertSpan("last weekend", days(d(2026, 10, 31), d(2026, 11, 1)), p = sunday)
        assertSpan("last month", days(d(2026, 10, 1), d(2026, 10, 31)))
        assertSpan("this month", days(d(2026, 11, 1), d(2026, 11, 7)))
        assertSpan("last year", days(d(2025, 1, 1), d(2025, 12, 31)))
        assertSpan("this year", days(d(2026, 1, 1), d(2026, 11, 7)))
    }

    @Test
    fun countingBack() {
        assertSpan("past 3 months", days(d(2026, 8, 8), d(2026, 11, 7)))
        assertSpan("the past week", days(d(2026, 11, 1), d(2026, 11, 7)))
        assertSpan("last 10 days", days(d(2026, 10, 29), d(2026, 11, 7)))
        assertSpan("the last two weeks", days(d(2026, 10, 25), d(2026, 11, 7)))
        assertSpan("past few months", days(d(2026, 7, 8), d(2026, 11, 7)))
        assertSpan("two weeks ago", days(d(2026, 10, 18), d(2026, 10, 24)))
        assertSpan("3 days ago", day(2026, 11, 4))
        assertSpan("a month ago", days(d(2026, 10, 1), d(2026, 10, 31)))
        assertSpan("a year ago", days(d(2025, 10, 1), d(2025, 12, 31)))
        assertSpan("5 years ago", days(d(2021, 1, 1), d(2021, 12, 31)))
        assertSpan("a few days ago", days(d(2026, 11, 3), d(2026, 11, 5)))
        assertSpan("a couple of weeks ago", days(d(2026, 10, 18), d(2026, 10, 24)))
        assertSpan("a few years ago", days(d(2022, 1, 1), d(2024, 12, 31)))
        assertNoDate("0 days ago")
        assertNoDate("999 years ago")
    }

    @Test
    fun days() {
        assertSpan("2023-03-05", day(2023, 3, 5))
        assertSpan("3/5/2023", day(2023, 3, 5))
        assertSpan("3/5/23", day(2023, 3, 5))
        assertSpan("3/5/2023", day(2023, 5, 3), p = DatePhrases(today, monthFirst = false))
        assertSpan("5.3.2023", day(2023, 3, 5))
        assertSpan("march 5th 2023", day(2023, 3, 5))
        assertSpan("March 5, 2023", day(2023, 3, 5))
        assertSpan("5 march 2023", day(2023, 3, 5))
        assertSpan("5th of march 2023", day(2023, 3, 5))
        assertSpan("mar 5 2023", day(2023, 3, 5))
        assertSpan("march 5", day(2026, 3, 5))
        assertSpan("december 25", day(2025, 12, 25))
        assertSpan("february 29", day(2024, 2, 29))
        assertNoDate("feb 30")
        assertNoDate("2023-02-30")
    }

    @Test
    fun years() {
        assertSpan("2023", days(d(2023, 1, 1), d(2023, 12, 31)))
        assertSpan("beach 2023", days(d(2023, 1, 1), d(2023, 12, 31)), "beach")
        assertSpan("pictures taken in 2023", days(d(2023, 1, 1), d(2023, 12, 31)))
        assertNoDate("2048")
        assertSpan("2048 beach 2023", days(d(2023, 1, 1), d(2023, 12, 31)), "2048 beach")
        for (q in listOf("between 2019 and 2021", "2019-2021", "2019 to 2021", "from 2019 to 2021", "2019 – 2021", "2021-2019")) {
            assertSpan(q, days(d(2019, 1, 1), d(2021, 12, 31)))
        }
        // Open-ended, so a year still to come is a date too, not the bare year.
        assertSpan("since 2020", Span(d(2020, 1, 1), d(9999, 1, 1)))
        assertSpan("after 2020", Span(d(2021, 1, 1), d(9999, 1, 1)))
        assertSpan("after 2026", Span(d(2027, 1, 1), d(9999, 1, 1)))
        assertSpan("beach after 2026", Span(d(2027, 1, 1), d(9999, 1, 1)), "beach")
        assertSpan("since 2030", Span(d(2030, 1, 1), d(9999, 1, 1)))
        assertSpan("before 2010", days(d(1800, 1, 1), d(2009, 12, 31)))
    }

    @Test
    fun decades() {
        for (q in listOf("the 90s", "1990s", "the 1990s", "the 90's", "90s")) assertSpan(q, days(d(1990, 1, 1), d(1999, 12, 31)))
        assertSpan("the 2010s", days(d(2010, 1, 1), d(2019, 12, 31)))
        assertSpan("the 20s", days(d(2020, 1, 1), d(2029, 12, 31)))
        assertSpan("the 30s", days(d(1930, 1, 1), d(1939, 12, 31)))
        assertSpan("1990s fashion", days(d(1990, 1, 1), d(1999, 12, 31)), "fashion")
        assertNoDate("90s fashion")
    }

    @Test
    fun wording() {
        assertSpan("Last Summer!", days(d(2026, 6, 1), d(2026, 8, 31)))
        assertSpan("summer, 2023", days(d(2023, 6, 1), d(2023, 8, 31)))
        assertSpan("  sunset   last   summer ", days(d(2026, 6, 1), d(2026, 8, 31)), "sunset")
        assertSpan("show me photos from last summer", days(d(2026, 6, 1), d(2026, 8, 31)))
        assertSpan("dogs on the beach last summer", days(d(2026, 6, 1), d(2026, 8, 31)), "dogs on the beach")
        // A request the query opens with is not searched for; a word for pictures alone is.
        val summer = days(d(2026, 6, 1), d(2026, 8, 31))
        assertSpan("show me photos of dogs from last summer", summer, "dogs")
        assertSpan("last summer photos of dogs", summer, "dogs")
        assertSpan("photos on the beach last summer", summer, "beach")
        assertSpan("find cats last summer", summer, "cats")
        assertSpan("my pictures from last summer", summer)
        assertSpan("photo booth last summer", summer, "photo booth")
        assertSpan("get together last summer", summer, "get together")
        for (q in listOf("dog on a beach", "2 dogs", "iphone 12", "top 10", "", "receipt")) assertNoDate(q)
    }

    @Test
    fun labels() {
        fun label(q: String) = us.parse(q)!!.filter.label
        assertThat(label("last summer")).isEqualTo("summer 2026: Jun 1, 2026 – Aug 31, 2026")
        assertThat(label("last winter")).isEqualTo("winter 2025–26: Dec 1, 2025 – Feb 28, 2026")
        assertThat(label("winter 2023")).isEqualTo("winter 2023: Jan 1, 2023 – Feb 28, 2023 and Dec 1, 2023 – Dec 31, 2023")
        assertThat(label("2023")).isEqualTo("2023")
        assertThat(label("march 2023")).isEqualTo("March 2023")
        assertThat(label("march 5")).isEqualTo("Mar 5, 2026")
        assertThat(label("yesterday")).isEqualTo("yesterday: Nov 6, 2026")
        assertThat(label("christmas")).isEqualTo("every Christmas")
        assertThat(label("summer")).isEqualTo("every summer (June – August)")
        assertThat(label("last christmas")).isEqualTo("Christmas 2025: Dec 24, 2025 – Dec 26, 2025")
        assertThat(label("two weeks ago")).isEqualTo("two weeks ago: Oct 18, 2026 – Oct 24, 2026")
        assertThat(label("past 3 months")).isEqualTo("the past 3 months: Aug 8, 2026 – Nov 7, 2026")
        assertThat(label("the 90s")).isEqualTo("the 1990s")
    }

    @Test
    fun locales() {
        val gb = DatePhrases.forLocale(today, Locale.UK)
        assertSpan("3/5/2023", day(2023, 5, 3), p = gb)
        assertSpan("last week", days(d(2026, 10, 26), d(2026, 11, 1)), p = gb)
        assertThat(gb.parse("march 5")!!.filter.label).isEqualTo("5 Mar 2026")
        val usa = DatePhrases.forLocale(today, Locale.US)
        assertSpan("3/5/2023", day(2023, 3, 5), p = usa)
        assertSpan("last week", days(d(2026, 10, 25), d(2026, 10, 31)), p = usa)
        assertSpan("last summer", days(d(2025, 12, 1), d(2026, 2, 28)), p = DatePhrases.forLocale(today, Locale.of("en", "AU")))
        assertSpan("last summer", days(d(2025, 12, 1), d(2026, 2, 28)), p = DatePhrases.forLocale(today, Locale.of("pt", "BR")))
        assertSpan("last thanksgiving", days(d(2026, 10, 10), d(2026, 10, 12)), p = DatePhrases.forLocale(today, Locale.CANADA))
    }
}
