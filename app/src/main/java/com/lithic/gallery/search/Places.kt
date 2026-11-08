package com.lithic.gallery.search

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.lithic.gallery.core.places.Gazetteer
import com.lithic.gallery.core.search.PlacePhrases
import com.lithic.gallery.data.MediaDao
import com.lithic.gallery.data.MediaLocations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where the library's pictures were taken, in words. Each file's location is read once ([scan]),
 * turned into a place by the [gazetteer], and the places indexed by the names they go by
 * ([snapshot]), for place search to find and to filter on.
 */
class Places(private val context: Context, private val dao: MediaDao, private val gazetteer: () -> Gazetteer) {
    /**
     * The library's places: [placeOf] each located file's place (by AI key), [names] each
     * normalized name they go by ("hawaii": "Hawaii, United States"), [keys] each name to the
     * files taken there.
     */
    class Snapshot(
        val placeOf: Map<String, Gazetteer.Place>,
        val names: Map<String, PlacePhrases.Known>,
        val keys: Map<String, Set<String>>,
    )

    private val _snapshot = MutableStateFlow(Snapshot(emptyMap(), emptyMap(), emptyMap()))
    val snapshot: StateFlow<Snapshot> = _snapshot
    private val mutex = Mutex()
    private var built = false

    /**
     * Each file's place once looked up (by AI key; null when nowhere near one): a key's location
     * never changes, so a rebuild only looks up the files located since. Under [mutex].
     */
    private val placeByKey = HashMap<String, Gazetteer.Place?>()
    /** Names as matched ([Gazetteer.normalize]), each worked out once. Under [mutex]. */
    private val normalized = HashMap<String, String>()

    /**
     * Reads the locations of the files not read yet (newest first), and keeps [snapshot] up to
     * date with the library: every few seconds while a large one is read, so its places come in
     * as they are found, and after the files changed. A file that could not be read is read
     * again next time. Without the permission to read them, the locations read before are
     * forgotten: the user has withheld where the pictures were taken.
     */
    suspend fun scan() = mutex.withLock {
        if (!MediaLocations.allowed(context)) {
            if (dao.forgetLocations() > 0) placeByKey.clear()
        } else withContext(Dispatchers.IO) {
            val todo = dao.withoutLocation()
            val started = SystemClock.elapsedRealtime()
            var shownAt = started
            var located = 0
            for (chunk in todo.chunked(SCAN_CHUNK)) {
                val rows = chunk.mapNotNull { MediaLocations.read(context, it) }
                dao.upsertLocations(rows)
                located += rows.count { it.lat != null }
                if (SystemClock.elapsedRealtime() - shownAt > SHOW_EVERY_MS) {
                    rebuild()
                    shownAt = SystemClock.elapsedRealtime()
                }
            }
            if (todo.isNotEmpty()) {
                Log.i("Places", "read ${todo.size} files' locations in ${SystemClock.elapsedRealtime() - started} ms: $located say where")
            }
        }
        rebuild()
    }

    /** Brings [snapshot] up to date with the locations of the library's files; it changes only when they did. */
    private suspend fun rebuild() = withContext(Dispatchers.Default) {
        val located = dao.locations()
        // The gazetteer is only read once a file is found to say where it was taken.
        val g by lazy { gazetteer() }
        val placeOf = HashMap<String, Gazetteer.Place>()
        for (l in located) {
            val lat = l.lat ?: continue
            val lon = l.lon ?: continue
            val p = if (l.hash in placeByKey) placeByKey[l.hash] else g.place(lat, lon).also { placeByKey[l.hash] = it }
            if (p != null) placeOf[l.hash] = p
        }
        if (built && placeOf == _snapshot.value.placeOf) return@withContext
        val names = HashMap<String, PlacePhrases.Known>()
        val keys = HashMap<String, MutableSet<String>>()
        fun add(name: String, label: String, key: String, country: Boolean = false) {
            val n = normalized.getOrPut(name) { Gazetteer.normalize(name) }
            keys.getOrPut(n) { HashSet() }.add(key)
            // Two places of one name (Paris, France and Paris, Texas) read as the name alone.
            names.merge(n, PlacePhrases.Known(label, country)) { a, b ->
                PlacePhrases.Known(if (a.label == b.label) a.label else name, a.country || b.country)
            }
        }
        for ((key, p) in placeOf) {
            add(p.name, p.label, key)
            p.city?.let { add(it, listOfNotNull(it, p.cityRegion?.takeIf { r -> r != it }, p.country).joinToString(", "), key) }
            p.region?.let { add(it, "$it, ${p.country}", key) }
            add(p.country, p.country, key, country = true)
        }
        // Countries by their other names ("usa"), where the library has pictures from them.
        if (placeOf.isNotEmpty()) for ((alias, country) in g.countryAliases) {
            val files = keys[normalized.getOrPut(country) { Gazetteer.normalize(country) }] ?: continue
            keys.getOrPut(alias) { HashSet() }.addAll(files)
            names.merge(alias, PlacePhrases.Known(country, country = true)) { a, b ->
                PlacePhrases.Known(if (a.label == b.label) a.label else alias, true)
            }
        }
        _snapshot.value = Snapshot(placeOf, names, keys)
        built = true
    }

    private companion object {
        /** Files read between writes: few enough that stopping loses little. */
        const val SCAN_CHUNK = 100

        /** How often, while a library is read, the places found so far are shown. */
        const val SHOW_EVERY_MS = 10_000L
    }
}
