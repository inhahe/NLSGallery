package com.lithic.gallery.core.search

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The moon's phases and the calendars that follow it, against published dates. */
class CalendarsTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun moonPhasesWithinMinutes() {
        // 2024's new and full moons (UTC), as the almanacs give them.
        val newMoons = listOf("2024-01-11T11:57Z", "2024-02-09T22:59Z", "2024-03-10T09:00Z", "2024-04-08T18:21Z",
            "2024-05-08T03:22Z", "2024-06-06T12:38Z", "2024-07-05T22:57Z", "2024-08-04T11:13Z", "2024-09-03T01:55Z",
            "2024-10-02T18:49Z", "2024-11-01T12:47Z", "2024-12-01T06:21Z", "2024-12-30T22:27Z")
        val fullMoons = listOf("2024-01-25T17:54Z", "2024-02-24T12:30Z", "2024-03-25T07:00Z", "2024-04-23T23:49Z",
            "2024-05-23T13:53Z", "2024-06-22T01:08Z", "2024-07-21T10:17Z", "2024-08-19T18:26Z", "2024-09-18T02:34Z",
            "2024-10-17T11:26Z", "2024-11-15T21:28Z", "2024-12-15T09:02Z")
        var worst = Duration.ZERO
        for ((list, half) in listOf(newMoons to 0.0, fullMoons to 0.5)) {
            for (s in list) {
                val want = Instant.parse(s.replace("Z", ":00Z"))
                val k = Math.round(Moon.lunation(want) - half) + half
                val off = Duration.between(want, Moon.phase(k)).abs()
                if (off > worst) worst = off
                assertWithMessage(s).that(off).isLessThan(Duration.ofMinutes(10))
            }
        }
        println("moon phases: worst ${worst.toMinutes()} min off")
    }

    @Test
    fun moonHolidays() {
        for ((year, day) in listOf(2017 to "2017-01-28", 2018 to "2018-02-16", 2019 to "2019-02-05", 2020 to "2020-01-25",
            2021 to "2021-02-12", 2022 to "2022-02-01", 2023 to "2023-01-22", 2024 to "2024-02-10", 2025 to "2025-01-29",
            2026 to "2026-02-17")) {
            assertWithMessage("Lunar New Year $year").that(LunarHolidays.lunarNewYear(year)).isEqualTo(d(day))
        }
        for ((year, day) in listOf(2021 to "2021-09-21", 2022 to "2022-09-10", 2023 to "2023-09-29", 2024 to "2024-09-17",
            2025 to "2025-10-06", 2026 to "2026-09-25")) {
            assertWithMessage("Mid-Autumn $year").that(LunarHolidays.midAutumn(year)).isEqualTo(d(day))
        }
        // Ramadan's first day, Eid al-Fitr (1 Shawwal), and Eid al-Adha's month (1 Dhu al-Hijjah: Eid is the 10th).
        for ((year, day) in listOf(2022 to "2022-04-02", 2023 to "2023-03-23", 2024 to "2024-03-11", 2025 to "2025-03-01")) {
            assertWithMessage("Ramadan $year").that(LunarHolidays.hijriMonthStarts(year, 9)).containsExactly(d(day))
        }
        for ((year, day) in listOf(2022 to "2022-05-02", 2023 to "2023-04-21", 2024 to "2024-04-10", 2025 to "2025-03-30")) {
            assertWithMessage("Eid al-Fitr $year").that(LunarHolidays.hijriMonthStarts(year, 10)).containsExactly(d(day))
        }
        for ((year, day) in listOf(2022 to "2022-07-09", 2023 to "2023-06-28", 2024 to "2024-06-16", 2025 to "2025-06-06")) {
            assertWithMessage("Eid al-Adha $year").that(LunarHolidays.hijriMonthStarts(year, 12).map { it.plusDays(9) }).containsExactly(d(day))
        }
        // An Islamic year is 11 days shorter: 2000 had two Eid al-Fitrs.
        assertThat(LunarHolidays.hijriMonthStarts(2000, 10)).hasSize(2)
    }

    @Test
    fun hebrewHolidays() {
        // Years whose new year the postponement rules move a day or two (1957, 1984, 2005, 2028, 2035), and recent ones.
        for ((year, day) in listOf(1957 to "1957-09-26", 1984 to "1984-09-27", 2005 to "2005-10-04", 2028 to "2028-09-21",
            2035 to "2035-10-04", 2022 to "2022-09-26", 2023 to "2023-09-16", 2024 to "2024-10-03", 2025 to "2025-09-23",
            2026 to "2026-09-12")) {
            assertWithMessage("Rosh Hashanah $year").that(HebrewCalendar.roshHashanah(year)).isEqualTo(d(day))
        }
        for ((year, day) in listOf(2023 to "2023-09-25", 2024 to "2024-10-12", 2025 to "2025-10-02")) {
            assertWithMessage("Yom Kippur $year").that(HebrewCalendar.yomKippur(year)).isEqualTo(d(day))
        }
        for ((year, day) in listOf(2023 to "2023-04-06", 2024 to "2024-04-23", 2025 to "2025-04-13", 2026 to "2026-04-02")) {
            assertWithMessage("Passover $year").that(HebrewCalendar.passover(year)).isEqualTo(d(day))
        }
        for ((year, day) in listOf(2022 to "2022-12-19", 2023 to "2023-12-08", 2024 to "2024-12-26", 2025 to "2025-12-15")) {
            assertWithMessage("Hanukkah $year").that(HebrewCalendar.hanukkah(year)).isEqualTo(d(day))
        }
    }
}
