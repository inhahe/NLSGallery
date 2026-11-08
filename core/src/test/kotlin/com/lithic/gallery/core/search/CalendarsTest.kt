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
    fun islamicHolidays() {
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

    /**
     * Every Lunar New Year and Mid-Autumn Festival from 1900 to 2099, as the Hong Kong
     * Observatory's tables have them (through the lunardate package): 2034's comes after a leap
     * 11th month, and 1985's, 2033's and 2052's are a month off a fixed date's reckoning. 1978's
     * Mid-Autumn Festival is the one exception: the 8th month's new moon came 9 minutes after
     * midnight in China, on 3 September, while the calendars of the day began it on the 2nd, so
     * the festival is the 17th here and was kept on the 16th (which its span still holds).
     */
    @Test
    fun chineseCalendarAsPublished() {
        for (y in 1900..2099) {
            val i = (y - 1900) * 8
            val lny = LocalDate.of(y, CHINESE.substring(i, i + 2).toInt(), CHINESE.substring(i + 2, i + 4).toInt())
            val midAutumn = LocalDate.of(y, CHINESE.substring(i + 4, i + 6).toInt(), CHINESE.substring(i + 6, i + 8).toInt())
            assertWithMessage("Lunar New Year $y").that(LunarHolidays.lunarNewYear(y)).isEqualTo(lny)
            if (y == 1978) {
                assertThat(LunarHolidays.midAutumn(y)).isEqualTo(midAutumn.plusDays(1))
                continue
            }
            assertWithMessage("Mid-Autumn $y").that(LunarHolidays.midAutumn(y)).isEqualTo(midAutumn)
        }
    }

    /** Ramadan's first day every year of Umm al-Qura's official table, 1365 to 1500 AH (through hijridate). */
    @Test
    fun ramadanAsUmmAlQuraHasIt() {
        val days = RAMADAN.split(' ').map { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }
        assertThat(days).hasSize(136)
        for (day in days) {
            assertWithMessage("Ramadan of ${day.year}").that(LunarHolidays.hijriMonthStarts(day.year, 9)).contains(day)
        }
        // The table's first and last months.
        assertThat(LunarHolidays.hijriMonthStarts(1945, 1)).containsExactly(d("1945-12-05"))
        assertThat(LunarHolidays.hijriMonthStarts(2077, 12)).containsExactly(d("2077-10-18"))
    }

    @Test
    fun indianFestivalsAsPublished() {
        // Years in which a new or full moon falls between 17 October (14 March) and the sun's true
        // entry into sidereal Libra (Pisces), where a fixed date's reckoning is a month early:
        // Lakshmi Puja 15 November 2039, Holika Dahan 28 March 2002 and 1983 (a full moon after
        // midnight in India: the evening before).
        assertThat(LunarHolidays.lakshmiPuja(2039)).isEqualTo(d("2039-11-15"))
        assertThat(LunarHolidays.holikaDahan(2002)).isEqualTo(d("2002-03-28"))
        assertThat(LunarHolidays.holikaDahan(1983)).isEqualTo(d("1983-03-28"))
        // Lakshmi Puja in New Delhi as Drik Panchang publishes it, 2024's and 2025's too, whose
        // new moons came 40 and 8 minutes after sunset (the later evening, then the earlier).
        for ((year, puja) in listOf(2024 to "2024-11-01", 2025 to "2025-10-20", 2026 to "2026-11-08", 2027 to "2027-10-29",
            2028 to "2028-10-17", 2029 to "2029-11-05", 2030 to "2030-10-26", 2031 to "2031-11-14", 2032 to "2032-11-02",
            2033 to "2033-10-22", 2034 to "2034-11-10", 2035 to "2035-10-30", 2036 to "2036-10-18")) {
            assertWithMessage("Lakshmi Puja $year").that(LunarHolidays.lakshmiPuja(year)).isEqualTo(d(puja))
        }
        // 2037's new moon comes a minute after sunset: Drik Panchang keeps that evening, 7
        // November, by rules about the tithi's start this does not reckon; the ghati's rule keeps
        // the 6th. The festival's span holds both.
        assertThat(LunarHolidays.lakshmiPuja(2037)).isEqualTo(d("2037-11-06"))
        // Holika Dahan, the evening before Holi: 2022's on the night Bhadra ended in, 2023's and
        // 2026's the evening after (Bhadra lasting until dawn, the full moon before sunset).
        for ((year, holi) in listOf(2019 to "2019-03-21", 2020 to "2020-03-10", 2021 to "2021-03-29", 2022 to "2022-03-18",
            2023 to "2023-03-08", 2024 to "2024-03-25", 2025 to "2025-03-14", 2026 to "2026-03-04")) {
            assertWithMessage("Holika Dahan $year").that(LunarHolidays.holikaDahan(year)).isEqualTo(d(holi).minusDays(1))
        }
    }

    @Test
    fun tetByVietnamsCalendar() {
        // Vietnam's calendar is reckoned for UTC+7: in 1985 a new moon an hour from midnight put
        // its leap month elsewhere and Tết a month before China's New Year; in 2007 and 2030 a day.
        for ((year, tet, china) in listOf(Triple(1985, "1985-01-21", "1985-02-20"), Triple(2007, "2007-02-17", "2007-02-18"),
            Triple(2030, "2030-02-02", "2030-02-03"), Triple(2026, "2026-02-17", "2026-02-17"))) {
            assertWithMessage("Tết $year").that(LunarHolidays.tet(year)).isEqualTo(d(tet))
            assertWithMessage("Lunar New Year $year").that(LunarHolidays.lunarNewYear(year)).isEqualTo(d(china))
        }
    }

    @Test
    fun aYearsLastMonthsAreTheNextOnes() {
        // Months 11 and 12 belong to the reckoning of the year after: asked of a year, they are refused.
        for (n in listOf(0, 11, 12)) {
            val e = runCatching { ChineseCalendar.monthStart(2026, n) }.exceptionOrNull()
            assertWithMessage("month $n").that(e).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(ChineseCalendar.monthStart(2026, 10)).isEqualTo(d("2026-11-09"))
    }

    private companion object {
        /** Lunar New Year and Mid-Autumn Festival, 1900-2099: MMDDMMDD a year. */
        const val CHINESE =
            "013109080219092702080916012910050216092402040913012510020213092202020910012209280210091801301006" +
            "021809250206091501261004021409230203091201230930021109190201100802200926020809160128100502160925" +
            "020509130124100202130921020209100123092802100917013010060217092602060915012610040214092302040912" +
            "012409300211091901311008021909270208091601271005021509240205091401251001021309200202091001220929" +
            "021009170129100602170926020609150127100302140922020309110124093002120919013109080218092702080917" +
            "012810050215092402050913012510020213092002020910012109290209091801301006021709260206091501271003" +
            "021509220203091101230930021109200131090802180927020709160128100502160923020509120125100102130921" +
            "020209100220092902090918012910070217092502060914012710030215092202040911012309300210092001310909" +
            "021909270207091601281005021609240205091201241001021209210201091101220928020909180129100602180925" +
            "020709140126100302140922020309120123093002100919013109080219092702080915012810040216092402050913" +
            "012510010212092102010910012209290210091701291006021709250206091501261003021309220203091201231001" +
            "021109190131090802190927020809160128100402150924020409130124100202120920020109100122092802100917" +
            "013010050217092502060915012610040214092202020911012309300211091902010907021909260208091601281005" +
            "021509240204091301241002021209210202090901210928020909170129100602170925020509150126100302140923" +
            "020309110123092902110919013109080219092602070916012710050215092402050912012410010212092002020910" +
            "012209280209091701291006021709260206091401261003021409220203091101240929021009180130090802180927" +
            "0207091601271005021509240205091301250930021209200201090901210929"

        /** 1 Ramadan, 1365-1500 AH, as yyyymmdd. */
        const val RAMADAN =
            "19460729 19470719 19480707 19490626 19500617 19510606 19520525 19530514 19540504 19550424 " +
            "19560412 19570401 19580321 19590311 19600228 19610216 19620205 19630126 19640115 19650103 " +
            "19651223 19661213 19671202 19681121 19691110 19701101 19711020 19721008 19730927 19740917 " +
            "19750906 19760826 19770815 19780805 19790725 19800713 19810702 19820622 19830612 19840531 " +
            "19850520 19860509 19870429 19880417 19890407 19900327 19910317 19920305 19930222 19940211 " +
            "19950131 19960121 19970110 19971230 19981219 19991209 20001127 20011116 20021106 20031026 " +
            "20041015 20051004 20060924 20070913 20080901 20090822 20100811 20110801 20120720 20130709 " +
            "20140628 20150618 20160606 20170527 20180516 20190506 20200424 20210413 20220402 20230323 " +
            "20240311 20250301 20260218 20270208 20280128 20290116 20300105 20301226 20311215 20321204 " +
            "20331123 20341112 20351101 20361020 20371010 20380930 20390919 20400907 20410828 20420817 " +
            "20430806 20440726 20450715 20460705 20470624 20480612 20490602 20500522 20510511 20520430 " +
            "20530420 20540409 20550329 20560317 20570306 20580224 20590214 20600203 20610123 20620112 " +
            "20630101 20631221 20641209 20651129 20661119 20671108 20681027 20691016 20701005 20710925 " +
            "20720913 20730903 20740823 20750813 20760801 20770721"
    }
}
