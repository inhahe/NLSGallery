package com.lithic.gallery.core.search

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Astronomical time: Julian Ephemeris Days (dynamical time, which the sun's and moon's motions
 * are reckoned in) to the clock's (UT), less ΔT, the Earth's lag (Espenak and Meeus' fits to
 * the record: about 24 s in 1925, 64 s in 2000, 70 s now). A solar term or new moon within a
 * minute of midnight moves its day by it.
 */
internal object Time {
    private const val JD_UNIX_EPOCH = 2440587.5

    /** The moment of [jde]. */
    fun instant(jde: Double): Instant {
        val jd = jde - deltaT(2000.0 + (jde - 2451545.0) / 365.25) / 86_400.0
        return Instant.ofEpochMilli(((jd - JD_UNIX_EPOCH) * 86_400_000.0).roundToLong())
    }

    /** [instant] as a Julian Ephemeris Day. */
    fun jde(instant: Instant): Double {
        val jd = instant.toEpochMilli() / 86_400_000.0 + JD_UNIX_EPOCH
        return jd + deltaT(2000.0 + (jd - 2451545.0) / 365.25) / 86_400.0
    }

    /** ΔT in seconds in decimal [year] (Espenak and Meeus 2006, from 1900; their long-term fit outside). */
    private fun deltaT(year: Double): Double {
        fun u(y: Double) = (y - 1820) / 100
        return when {
            year < 1900 || year >= 2150 -> -20 + 32 * u(year) * u(year)
            year < 1920 -> (year - 1900).let { t -> -2.79 + 1.494119 * t - 0.0598939 * t * t + 0.0061966 * t * t * t - 0.000197 * t * t * t * t }
            year < 1941 -> (year - 1920).let { t -> 21.20 + 0.84493 * t - 0.076100 * t * t + 0.0020936 * t * t * t }
            year < 1961 -> (year - 1950).let { t -> 29.07 + 0.407 * t - t * t / 233 + t * t * t / 2547 }
            year < 1986 -> (year - 1975).let { t -> 45.45 + 1.067 * t - t * t / 260 - t * t * t / 718 }
            year < 2005 -> (year - 2000).let { t ->
                63.86 + 0.3345 * t - 0.060374 * t * t + 0.0017275 * t * t * t + 0.000651814 * t * t * t * t + 0.00002373599 * t * t * t * t * t
            }
            year < 2050 -> (year - 2000).let { t -> 62.92 + 0.32217 * t + 0.005589 * t * t }
            else -> -20 + 32 * u(year) * u(year) - 0.5628 * (2150 - year)
        }
    }
}

/**
 * The sun's apparent longitude: Meeus, Astronomical Algorithms (2nd edition), chapter 25 at its
 * lower accuracy (0.01°, a quarter of an hour of the sun's motion), and the moments it reaches a
 * longitude (chapter 27's iteration).
 */
internal object Sun {
    /** The sun's apparent geocentric longitude at [jde], in degrees from 0 to 360. */
    fun longitude(jde: Double): Double {
        val t = (jde - 2451545.0) / 36525
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) + (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        return mod360(l0 + c - 0.00569 - 0.00478 * sin(omega))
    }

    /** The first moment (JDE) from [after] at which the sun's longitude is [degrees]. */
    fun reaches(degrees: Double, after: Double): Double {
        var jde = after + mod360(degrees - longitude(after)) / 360.0 * 365.2422
        repeat(6) { jde += 58.13 * sin(Math.toRadians(degrees - longitude(jde))) }
        return jde
    }

    /** [x] degrees as 0 to 360. */
    private fun mod360(x: Double): Double = x - 360.0 * floor(x / 360.0)
}

/**
 * New and full moons: Meeus, Astronomical Algorithms (2nd edition), chapter 49. Within a few
 * minutes of the true moments for centuries either side of 2000.
 */
internal object Moon {
    private const val SYNODIC_MONTH = 29.530588861
    private const val JDE_FIRST_NEW_MOON = 2451550.09766 // 6 January 2000, 18:14
    private const val JD_UNIX_EPOCH = 2440587.5

    /** The moment of lunation [k]: whole, a new moon (0 that of 6 January 2000); k + 0.5, the full moon after it. */
    fun phase(k: Double): Instant {
        val t = k / 1236.85
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        var jde = JDE_FIRST_NEW_MOON + SYNODIC_MONTH * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4
        val e = 1 - 0.002516 * t - 0.0000074 * t2
        val m = rad(2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3)
        val mp = rad(201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4)
        val f = rad(160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4)
        val om = rad(124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3)
        val full = k - floor(k) > 0.25
        jde += (if (full) -0.40614 else -0.40720) * sin(mp) +
            (if (full) 0.17302 else 0.17241) * e * sin(m) +
            (if (full) 0.01614 else 0.01608) * sin(2 * mp) +
            (if (full) 0.01043 else 0.01039) * sin(2 * f) +
            (if (full) 0.00734 else 0.00739) * e * sin(mp - m) -
            (if (full) 0.00515 else 0.00514) * e * sin(mp + m) +
            (if (full) 0.00209 else 0.00208) * e * e * sin(2 * m) -
            0.00111 * sin(mp - 2 * f) -
            0.00057 * sin(mp + 2 * f) +
            0.00056 * e * sin(2 * mp + m) -
            0.00042 * sin(3 * mp) +
            0.00042 * e * sin(m + 2 * f) +
            0.00038 * e * sin(m - 2 * f) -
            0.00024 * e * sin(2 * mp - m) -
            0.00017 * sin(om) -
            0.00007 * sin(mp + 2 * m) +
            0.00004 * sin(2 * mp - 2 * f) +
            0.00004 * sin(3 * m) +
            0.00003 * sin(mp + m - 2 * f) +
            0.00003 * sin(2 * mp + 2 * f) -
            0.00003 * sin(mp + m + 2 * f) +
            0.00003 * sin(mp - m + 2 * f) -
            0.00002 * sin(mp - m - 2 * f) -
            0.00002 * sin(3 * mp + m) +
            0.00002 * sin(4 * mp)
        // The planets' pull.
        val a = doubleArrayOf(
            299.77 + 0.107408 * k - 0.009173 * t2, 251.88 + 0.016321 * k, 251.83 + 26.651886 * k,
            349.42 + 36.412478 * k, 84.66 + 18.206239 * k, 141.74 + 53.303771 * k, 207.14 + 2.453732 * k,
            154.84 + 7.306860 * k, 34.52 + 27.261239 * k, 207.19 + 0.121824 * k, 291.34 + 1.844379 * k,
            161.72 + 24.198154 * k, 239.56 + 25.513099 * k, 331.55 + 3.592518 * k,
        )
        val w = doubleArrayOf(0.000325, 0.000165, 0.000164, 0.000126, 0.000110, 0.000062, 0.000060,
            0.000056, 0.000047, 0.000042, 0.000040, 0.000037, 0.000035, 0.000023)
        for (i in a.indices) jde += w[i] * sin(rad(a[i]))
        return Time.instant(jde)
    }

    /** The lunation number of [instant], as a fraction (the new moon of 6 January 2000 is 0). */
    fun lunation(instant: Instant): Double =
        (instant.toEpochMilli() / 86_400_000.0 + JD_UNIX_EPOCH - JDE_FIRST_NEW_MOON) / SYNODIC_MONTH

    /** The last new moon at or before [instant], with its lunation number. */
    fun newMoonBefore(instant: Instant): Pair<Long, Instant> {
        var k = floor(lunation(instant)).toLong() + 1
        while (phase(k.toDouble()).isAfter(instant)) k--
        return k to phase(k.toDouble())
    }

    private fun rad(degrees: Double) = Math.toRadians(degrees % 360.0)
}

/**
 * Holidays that follow the moon, in the calendars they belong to. Where countries see them a day
 * apart (Islamic months begin by sighting in many), the rule of the reference country is taken
 * and the span around the day is wide enough for the rest.
 */
internal object LunarHolidays {
    private val INDIA = ZoneOffset.ofHoursMinutes(5, 30)
    private val MECCA = ZoneOffset.ofHours(3)

    private fun dateIn(instant: Instant, zone: ZoneOffset): LocalDate = instant.atOffset(zone).toLocalDate()

    /**
     * When the sun enters the sidereal sign beginning at [degrees] (180 Libra, 330 Pisces) on or
     * after [from]: the tropical longitude less the Lahiri ayanamsa, India's official one (23.857°
     * in 2000, growing 50.3" a year with the precession of the equinoxes).
     */
    private fun siderealIngress(degrees: Double, from: LocalDate): Instant {
        val start = Time.jde(from.atStartOfDay().toInstant(INDIA))
        // The ayanamsa moves a second of arc a week: taken once near the ingress, it is exact enough.
        val ayanamsa = 23.857 + 1.3972 * ((start - 2451545.0) / 36525)
        return Time.instant(Sun.reaches((degrees + ayanamsa) % 360.0, start))
    }

    /**
     * Diwali in [year]: the new moon ending Ashvin, the lunar month in which the sun enters
     * sidereal Libra (17 or 18 October). Lakshmi Puja is that night or the one before.
     */
    fun diwali(year: Int): LocalDate {
        val (k, _) = Moon.newMoonBefore(siderealIngress(180.0, LocalDate.of(year, 9, 1)))
        return dateIn(Moon.phase((k + 1).toDouble()), INDIA)
    }

    /**
     * The full moon of Holi in [year]: that of Phalguna, the lunar month in which the sun enters
     * sidereal Pisces (14 or 15 March). Holika Dahan is that night, Holi the next day.
     */
    fun holiFullMoon(year: Int): LocalDate {
        val (k, _) = Moon.newMoonBefore(siderealIngress(330.0, LocalDate.of(year, 2, 1)))
        return dateIn(Moon.phase(k + 0.5), INDIA)
    }

    /** The Lunar (Chinese) New Year in [year]: the first day of its first month ([ChineseCalendar]). */
    fun lunarNewYear(year: Int): LocalDate = ChineseCalendar.monthStart(year, 1)

    /** The Mid-Autumn Festival in [year]: the 15th day of the 8th month ([ChineseCalendar]). */
    fun midAutumn(year: Int): LocalDate = ChineseCalendar.monthStart(year, 8).plusDays(14)

    /**
     * The first days of Hijri month [month] (1-12; Ramadan is 9) falling in Gregorian [year] (an
     * Islamic year is 11 days shorter, so some years have two): Saudi Arabia's Umm al-Qura
     * calendar, its official table from 1945 to 2077 ([UMM_AL_QURA]); outside it, its rule as
     * near as a day: the month begins the day after the new moon if that comes before sunset
     * in Mecca, else the day after.
     */
    fun hijriMonthStarts(year: Int, month: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        val first = floor(Moon.lunation(LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))).toLong() - 1
        for (k in first..first + 14) {
            val index = k + HIJRI_MONTH_OF_LUNATION_0
            if (Math.floorMod(index, 12L).toInt() != month - 1) continue
            val day1 = tableMonthStart(index) ?: Moon.phase(k.toDouble()).atOffset(MECCA).let { conjunction ->
                conjunction.toLocalDate().plusDays(if (conjunction.hour * 60 + conjunction.minute < SUNSET_MINUTES) 1 else 2)
            }
            if (day1.year == year) out.add(day1)
        }
        return out
    }

    /** Hijri months since 1 Muharram 1 AH of lunation 0: Ramadan 1445 began after lunation 299's new moon (10 March 2024). */
    private const val HIJRI_MONTH_OF_LUNATION_0 = (1445L - 1) * 12 + (9 - 1) - 299

    /** About when the sun sets in Mecca (18:30 local, within the hour all year). */
    private const val SUNSET_MINUTES = 18 * 60 + 30

    /** The first month of [UMM_AL_QURA], in months since 1 Muharram 1 AH, and its first day. */
    private const val TABLE_FIRST_MONTH = (1365L - 1) * 12
    private val TABLE_FIRST_DAY = LocalDate.of(1945, 12, 5)

    /**
     * The Umm al-Qura calendar's month lengths, 1 Muharram 1365 to the end of 1500
     * (tools/make_umm_al_qura.py, from hijridate's table): a month's bit, most significant first
     * in each hex digit, is 1 for 30 days, 0 for 29.
     */
    private const val UMM_AL_QURA =
            "aabaaaaaaaabab6aaaa5754b555ab3aa64ea95baaa555aaab4ab655aaaaab2ecab2ab6aaaabab4aab2aba2baa6b4aba5" +
            "5aaab5556d2b54aea57526e936aaad555aa5b52ba95b49ba4db25d52daa5ad4aea56d4bd23d91da95b4ab5a56d2b693b" +
            "49b6556a9754b6a56caad555b29b92ba95d4ada55aaab595749764baa5b52b6a56d2ae9572a75535a95d49ba4dd26d53" +
            "5aaaad4b6a57527a95b4ab5536c9ae4b6a96b4ada55d25d92dc96d4ad6556d2b693749b64d72ab54b6a5752b6956b2ad" +
            "94dc95d4aea56caad5556c97"

    /** Each table month's first day, by month since [TABLE_FIRST_MONTH]. */
    private val tableStarts: LongArray by lazy {
        val months = (1500 - 1365 + 1) * 12
        val starts = LongArray(months + 1)
        starts[0] = TABLE_FIRST_DAY.toEpochDay()
        for (i in 0 until months) {
            val bit = (Character.digit(UMM_AL_QURA[i / 4], 16) shr (3 - i % 4)) and 1
            starts[i + 1] = starts[i] + 29 + bit
        }
        starts
    }

    /** The first day of Hijri month [index] (months since 1 Muharram 1 AH) by the table, or null outside it. */
    private fun tableMonthStart(index: Long): LocalDate? {
        val i = index - TABLE_FIRST_MONTH
        return if (i < 0 || i >= tableStarts.size - 1) null else LocalDate.ofEpochDay(tableStarts[i.toInt()])
    }
}

/**
 * The Chinese calendar's months (as China has reckoned them since 1645, and Calendrical
 * Calculations describes): each begins on the day, in China, of a new moon; the month holding
 * the winter solstice is the 11th; and in a year of 13 months between two 11th months, the first
 * month holding none of the twelve major solar terms (the sun at a multiple of 30°) is a leap
 * month, numbered as the one before it. China's day is UTC+8 from 1929, Beijing's mean solar
 * time before.
 */
internal object ChineseCalendar {
    private fun zone(day: LocalDate): ZoneOffset =
        if (day.year >= 1929) ZoneOffset.ofHours(8) else ZoneOffset.ofHoursMinutesSeconds(7, 45, 40)

    /** The day in China of [instant]. */
    private fun dayOf(instant: Instant): LocalDate {
        val utc = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        return instant.atOffset(zone(utc)).toLocalDate()
    }

    /** The day in China of the winter solstice of [year]. */
    private fun solstice(year: Int): LocalDate =
        dayOf(Time.instant(Sun.reaches(270.0, Time.jde(LocalDate.of(year, 12, 1).atStartOfDay().toInstant(ZoneOffset.UTC)))))

    /** The lunation and the day of the last new moon on or before [day] (by China's day). */
    private fun newMoonOnOrBefore(day: LocalDate): Pair<Long, LocalDate> {
        var (k, _) = Moon.newMoonBefore(day.plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC))
        while (dayOf(Moon.phase(k.toDouble())).isAfter(day)) k--
        return k to dayOf(Moon.phase(k.toDouble()))
    }

    /** Whether the sun reaches a multiple of 30° on a day from [start] to [end] (excluded), in China. */
    private fun holdsMajorTerm(start: LocalDate, end: LocalDate): Boolean {
        val from = Time.jde(start.atStartOfDay().toInstant(zone(start)))
        val next = (floor(Sun.longitude(from) / 30.0) + 1) * 30.0
        return dayOf(Time.instant(Sun.reaches(next % 360.0, from))).isBefore(end)
    }

    /** The first day of month [number] (1-12, not a leap month) of Chinese year [year] (the one beginning in Gregorian [year]). */
    fun monthStart(year: Int, number: Int): LocalDate {
        val (k0, _) = newMoonOnOrBefore(solstice(year - 1))
        val (k1, _) = newMoonOnOrBefore(solstice(year))
        val starts = (k0..k1).map { dayOf(Moon.phase(it.toDouble())) }
        val leapYear = k1 - k0 == 13L
        var month = 11
        var leapSeen = false
        for (i in 1 until starts.size - 1) {
            if (leapYear && !leapSeen && !holdsMajorTerm(starts[i], starts[i + 1])) {
                leapSeen = true
                continue
            }
            month = month % 12 + 1
            if (month == number) return starts[i]
        }
        error("no month $number in Chinese year $year")
    }
}

/**
 * The Hebrew calendar's new years (Dershowitz and Reingold, Calendrical Calculations): Rosh
 * Hashanah, and the holidays counted from it.
 */
internal object HebrewCalendar {
    /** The fixed day (day 1 = 1 January of year 1) of 1 Tishri, year 1. */
    private const val EPOCH = -1373427L
    /** The fixed day of 1 January 1970. */
    private const val UNIX_EPOCH = 719163L

    private fun elapsedDays(year: Int): Long {
        val months = Math.floorDiv(235L * year - 234, 19L)
        val parts = 12084L + 13753L * months
        val days = 29L * months + Math.floorDiv(parts, 25920L)
        return if (Math.floorMod(3 * (days + 1), 7L) < 3) days + 1 else days
    }

    private fun yearLengthCorrection(year: Int): Long {
        val ny0 = elapsedDays(year - 1)
        val ny1 = elapsedDays(year)
        val ny2 = elapsedDays(year + 1)
        return when {
            ny2 - ny1 == 356L -> 2
            ny1 - ny0 == 382L -> 1
            else -> 0
        }
    }

    /** 1 Tishri (Rosh Hashanah) of Hebrew [year]. */
    private fun newYear(year: Int): LocalDate =
        LocalDate.ofEpochDay(EPOCH + elapsedDays(year) + yearLengthCorrection(year) - UNIX_EPOCH)

    /** Rosh Hashanah in Gregorian [year] (autumn: Hebrew year [year] + 3761 begins). */
    fun roshHashanah(year: Int): LocalDate = newYear(year + 3761)

    /** Yom Kippur, 10 Tishri. */
    fun yomKippur(year: Int): LocalDate = roshHashanah(year).plusDays(9)

    /** The first day of Passover, 15 Nisan, in Gregorian [year]: 163 days before the next Rosh Hashanah. */
    fun passover(year: Int): LocalDate = roshHashanah(year).minusDays(163)

    /** The first day of Hanukkah, 25 Kislev, of the Hebrew year beginning in Gregorian [year]. */
    fun hanukkah(year: Int): LocalDate {
        val rh = newYear(year + 3761)
        val length = newYear(year + 3762).toEpochDay() - rh.toEpochDay()
        // Tishri has 30 days; Cheshvan 30 in a complete year (355 or 385 days), else 29.
        val cheshvan = if (length % 10 == 5L) 30 else 29
        return rh.plusDays((30 + cheshvan + 24).toLong())
    }
}
