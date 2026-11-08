package com.lithic.gallery.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lithic.gallery.core.places.Gazetteer
import com.lithic.gallery.data.LocationEntity
import com.lithic.gallery.data.MediaDao
import com.lithic.gallery.data.MediaLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/**
 * Where the library's pictures were taken, in words. Each file's location is read once ([scan]),
 * turned into a place by the [gazetteer], and the places indexed by the names they go by
 * ([snapshot]), for place search to find and to filter on.
 */
class Places(private val context: Context, private val dao: MediaDao, private val gazetteer: () -> Gazetteer) {
    /**
     * The library's places: [placeOf] each located file's place (by AI key), [labels] each
     * normalized name to how it reads ("hawaii" to "Hawaii, United States"), [keys] each name to
     * the files taken there.
     */
    class Snapshot(
        val placeOf: Map<String, Gazetteer.Place>,
        val labels: Map<String, String>,
        val keys: Map<String, Set<String>>,
    )

    private val _snapshot = MutableStateFlow(Snapshot(emptyMap(), emptyMap(), emptyMap()))
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val mutex = Mutex()
    private var built = false

    /**
     * Reads the locations of the files not read yet (newest first), and keeps [snapshot] up to
     * date with them: every few seconds while a large library is read, so its places come in as
     * they are found. Without the permission to read them, the locations read before are
     * forgotten: the user has withheld where the pictures were taken.
     */
    suspend fun scan() = mutex.withLock {
        if (!MediaLocations.allowed(context)) {
            if (dao.forgetLocations() > 0) rebuild()
        } else withContext(Dispatchers.IO) {
            val todo = dao.withoutLocation()
            val started = SystemClock.elapsedRealtime()
            var shownAt = started
            var unshown = false
            var located = 0
            for (chunk in todo.chunked(SCAN_CHUNK)) {
                val rows = chunk.map { item -> MediaLocations.read(context, item).let { LocationEntity(item.aiKey, it?.first, it?.second) } }
                dao.upsertLocations(rows)
                val n = rows.count { it.lat != null }
                located += n
                if (n > 0) unshown = true
                if (unshown && SystemClock.elapsedRealtime() - shownAt > SHOW_EVERY_MS) {
                    rebuild()
                    unshown = false
                    shownAt = SystemClock.elapsedRealtime()
                }
            }
            if (unshown) rebuild()
            if (todo.isNotEmpty()) {
                Log.i("Places", "read ${todo.size} files' locations in ${SystemClock.elapsedRealtime() - started} ms: $located say where")
            }
        }
        if (!built) rebuild()
    }

    private suspend fun rebuild() = withContext(Dispatchers.Default) {
        val located = dao.locations()
        // The gazetteer is only read once a file is found to say where it was taken.
        val g by lazy { gazetteer() }
        val placeOf = HashMap<String, Gazetteer.Place>()
        // Pictures come in bunches from one spot: a place per 100 m or so is looked up once.
        val near = HashMap<Pair<Long, Long>, Gazetteer.Place?>()
        for (l in located) {
            val lat = l.lat ?: continue
            val lon = l.lon ?: continue
            val p = near.getOrPut((lat * 1000).roundToLong() to (lon * 1000).roundToLong()) { g.place(lat, lon) } ?: continue
            placeOf[l.hash] = p
        }
        val labels = HashMap<String, String>()
        val keys = HashMap<String, MutableSet<String>>()
        fun add(name: String, label: String, key: String) {
            val n = Gazetteer.normalize(name)
            keys.getOrPut(n) { HashSet() }.add(key)
            // Two places of one name (Paris, France and Paris, Texas) read as the name alone.
            labels.merge(n, label) { a, b -> if (a == b) a else name }
        }
        for ((key, p) in placeOf) {
            add(p.name, listOfNotNull(p.name, p.region?.takeIf { it != p.name }, p.country).joinToString(", "), key)
            p.city?.let { add(it, listOfNotNull(it, p.region?.takeIf { r -> r != it }, p.country).joinToString(", "), key) }
            p.region?.let { add(it, "$it, ${p.country}", key) }
            add(p.country, p.country, key)
        }
        // Countries by their other names ("usa"), where the library has pictures from them.
        if (placeOf.isNotEmpty()) for ((alias, country) in g.countryAliases) {
            val n = Gazetteer.normalize(country)
            val files = keys[n] ?: continue
            keys.getOrPut(alias) { HashSet() }.addAll(files)
            labels.merge(alias, labels.getValue(n)) { a, b -> if (a == b) a else alias }
        }
        _snapshot.value = Snapshot(placeOf, labels, keys)
        built = true
    }

    private companion object {
        /** Files read between writes: few enough that stopping loses little. */
        const val SCAN_CHUNK = 100

        /** How often, while a library is read, the places found so far are shown. */
        const val SHOW_EVERY_MS = 10_000L
    }
}
