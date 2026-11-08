package com.lithic.gallery.core.places

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Where a point on Earth is, in words: the nearest populated place of a thousand people or more
 * (GeoNames), its region and country, and the biggest city near it, since a suburb's pictures
 * are the city's too. Read from places.bin (tools/make_places.py).
 */
class Gazetteer(bytes: ByteArray) {
    /**
     * Where a picture was taken: [name] the nearest place, its [region] (a state, a province) and
     * [country], and [city] the biggest city within [CITY_KM], of [BIG_CITY] people or more (none
     * when that is [name] itself, or none is near).
     */
    data class Place(val name: String, val region: String?, val country: String, val city: String?) {
        /** "Honolulu, Hawaii, United States". */
        val label: String get() = listOfNotNull(name, city, region?.takeIf { it != name && it != city }, country).joinToString(", ")

        /** The names it is found by, from the narrowest. */
        val names: List<String> get() = listOfNotNull(name, city, region, country).distinct()
    }

    private val strings: Array<String>
    private val countryName: IntArray
    private val regionName: IntArray
    private val aliasOf: Map<String, String>
    private val lat: FloatArray
    private val lon: FloatArray
    private val name: IntArray
    private val region: IntArray
    private val country: IntArray
    private val population: IntArray
    /** Places by 1° cell ([cell]), so a look-up reads a few hundred, not all. */
    private val cells: Map<Int, IntArray>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4).also { b.get(it) }
        require(String(magic, Charsets.US_ASCII) == "NLSP" && b.short.toInt() == 1) { "not a places file" }
        strings = Array(b.int) {
            val bytesOf = ByteArray(b.short.toInt() and 0xFFFF)
            b.get(bytesOf)
            String(bytesOf, Charsets.UTF_8)
        }
        val nCountries = b.short.toInt() and 0xFFFF
        countryName = IntArray(nCountries)
        for (i in 0 until nCountries) {
            b.get(ByteArray(2))
            countryName[i] = b.int
        }
        val nRegions = b.short.toInt() and 0xFFFF
        regionName = IntArray(nRegions)
        for (i in 0 until nRegions) {
            b.short // its country, which the places carry themselves
            regionName[i] = b.int
        }
        aliasOf = HashMap<String, String>().also { m ->
            repeat(b.int) {
                val alias = strings[b.int]
                m[normalize(alias)] = strings[countryName[b.short.toInt() and 0xFFFF]]
            }
        }
        val n = b.int
        lat = FloatArray(n)
        lon = FloatArray(n)
        name = IntArray(n)
        region = IntArray(n)
        country = IntArray(n)
        population = IntArray(n)
        for (i in 0 until n) {
            lat[i] = b.float
            lon[i] = b.float
            name[i] = b.int
            region[i] = b.short.toInt() and 0xFFFF
            country[i] = b.short.toInt() and 0xFFFF
            population[i] = b.int
        }
        val byCell = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byCell.getOrPut(cell(floor(lat[i].toDouble()).toInt(), floor(lon[i].toDouble()).toInt())) { ArrayList() }.add(i)
        cells = byCell.mapValues { it.value.toIntArray() }
    }

    /** Where ([latitude], [longitude]) is, or null when no place is within [MAX_KM] (at sea, in a wilderness). */
    fun place(latitude: Double, longitude: Double): Place? {
        val nearest = within(latitude, longitude, MAX_KM).minByOrNull { it.second }?.first ?: return null
        val city = within(latitude, longitude, CITY_KM).maxByOrNull { population[it.first] }?.first
            ?.takeIf { it != nearest && population[it] >= BIG_CITY }
        val r = region[nearest].takeIf { it != 0xFFFF }
        return Place(strings[name[nearest]], r?.let { strings[regionName[it]] }, strings[countryName[country[nearest]]],
            city?.let { strings[name[it]] })
    }

    /** Names of countries GeoNames does not use ("usa", "holland"), normalized, to the name it does. */
    val countryAliases: Map<String, String> get() = aliasOf

    private val aliasesOf: Map<String, List<String>> = aliasOf.entries.groupBy({ it.value }, { it.key })

    /** The words [p] is found by in a keyword search: its names and its country's other names. */
    fun words(p: Place): String = (p.names + aliasesOf[p.country].orEmpty()).joinToString(" ")

    /** The places within [km] of a point (at most a degree away: [MAX_KM] is less), with how far each is. */
    private fun within(latitude: Double, longitude: Double, km: Double): List<Pair<Int, Double>> {
        val cosLat = cos(Math.toRadians(latitude))
        val out = ArrayList<Pair<Int, Double>>()
        val la = floor(latitude).toInt()
        val lo = floor(longitude).toInt()
        for (dla in -1..1) for (dlo in -1..1) {
            val places = cells[cell(la + dla, lo + dlo)] ?: continue
            for (i in places) {
                var dLon = lon[i] - longitude
                if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
                val dx = dLon * cosLat
                val dy = lat[i] - latitude
                val d = sqrt(dx * dx + dy * dy) * KM_PER_DEGREE
                if (d < km) out.add(i to d)
            }
        }
        return out
    }

    private fun cell(latDegree: Int, lonDegree: Int): Int {
        val lo = Math.floorMod(lonDegree + 180, 360)
        return (latDegree + 90) * 360 + lo
    }

    companion object {
        /** How far the nearest place may be: a park's pictures are its nearest town's. */
        const val MAX_KM = 60.0

        /** How far a big city may be, for its suburbs' pictures to be found by its name. */
        const val CITY_KM = 30.0

        /** The people a city needs to lend its name to the places around it. */
        const val BIG_CITY = 100_000

        private const val KM_PER_DEGREE = 111.195

        /** [s] as names are matched: lower case, accents and punctuation gone ("Saint-Étienne" is "saint etienne"). */
        fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).replace(Regex("[-'’.,()]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
