package com.lithic.gallery.core.search

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * New and full moons: Meeus, Astronomical Algorithms (2nd edition), chapter 49. Within a few
 * minutes of the true moments for centuries either side of 2000; the holidays that follow the
 * moon go by the day.
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
        // Dynamical time is about a minute ahead of UTC these decades: no matter by the day.
        return Instant.ofEpochMilli(((jde - JD_UNIX_EPOCH) * 86_400_000.0).roundToLong())
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
    private val CHINA = ZoneOffset.ofHours(8)
    private val MECCA = ZoneOffset.ofHours(3)

    private fun dateIn(instant: Instant, zone: ZoneOffset): LocalDate = instant.atOffset(zone).toLocalDate()

    /**
     * Diwali in [year]: the new moon ending the lunar month in which the sun enters sidereal
     * Libra (about 17 October). Lakshmi Puja is that night or the one before.
     */
    fun diwali(year: Int): LocalDate {
        val ingress = LocalDateTime.of(year, 10, 17, 6, 0).toInstant(INDIA)
        val (k, _) = Moon.newMoonBefore(ingress)
        return dateIn(Moon.phase((k + 1).toDouble()), INDIA)
    }

    /**
     * The full moon of Holi in [year]: that of the lunar month in which the sun enters sidereal
     * Pisces (about 14 March). Holika Dahan is that night, Holi the next day.
     */
    fun holiFullMoon(year: Int): LocalDate {
        val ingress = LocalDateTime.of(year, 3, 14, 6, 0).toInstant(INDIA)
        val (k, _) = Moon.newMoonBefore(ingress)
        return dateIn(Moon.phase(k + 0.5), INDIA)
    }

    /** The Lunar (Chinese) New Year in [year]: the first new moon, by China's date, from 21 January. */
    fun lunarNewYear(year: Int): LocalDate {
        var (k, _) = Moon.newMoonBefore(LocalDateTime.of(year, 1, 21, 0, 0).toInstant(CHINA))
        while (true) {
            val d = dateIn(Moon.phase(k.toDouble()), CHINA)
            if (!d.isBefore(LocalDate.of(year, 1, 21))) return d
            k++
        }
    }

    /** The Mid-Autumn Festival in [year]: the 15th day of the lunar month (by China's date) holding 23 September. */
    fun midAutumn(year: Int): LocalDate {
        val (_, newMoon) = Moon.newMoonBefore(LocalDateTime.of(year, 9, 23, 23, 59).toInstant(CHINA))
        return dateIn(newMoon, CHINA).plusDays(14)
    }

    /**
     * The first days of Hijri month [month] (1-12; Ramadan is 9) falling in Gregorian [year] (an
     * Islamic year is 11 days shorter, so some years have two). Umm al-Qura's rule: the month
     * begins the day after the new moon if that comes before sunset in Mecca, else the day after.
     */
    fun hijriMonthStarts(year: Int, month: Int): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        val first = floor(Moon.lunation(LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))).toLong() - 1
        for (k in first..first + 14) {
            if (Math.floorMod(k + HIJRI_MONTH_OF_LUNATION_0, 12L).toInt() != month - 1) continue
            val conjunction = Moon.phase(k.toDouble()).atOffset(MECCA)
            val day1 = conjunction.toLocalDate().plusDays(if (conjunction.hour * 60 + conjunction.minute < SUNSET_MINUTES) 1 else 2)
            if (day1.year == year) out.add(day1)
        }
        return out
    }

    /** Hijri months since 1 Muharram 1 AH, less one, of lunation 0: Ramadan 1445 began after lunation 299's new moon (10 March 2024). */
    private const val HIJRI_MONTH_OF_LUNATION_0 = (1445L - 1) * 12 + (9 - 1) - 299

    /** About when the sun sets in Mecca (18:30 local, within the hour all year). */
    private const val SUNSET_MINUTES = 18 * 60 + 30
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
