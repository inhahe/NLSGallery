package com.lithic.gallery.data

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The app's copy of the media library, kept in step with MediaStore. */
class MediaRepository(
    private val db: GalleryDatabase,
    private val resolver: ContentResolver,
) {
    private val scanner = MediaScanner(resolver)
    private val mutex = Mutex()

    /** Every photo and video, newest first. */
    val media: Flow<List<MediaEntity>> = db.media().observeAll()

    data class SyncResult(val added: Int, val changed: Int, val removed: Int)

    /**
     * Brings the database in line with MediaStore: new files added, changed ones updated, gone
     * ones removed (their descriptions stay, keyed by content, in case they come back).
     */
    suspend fun sync(): SyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val scanned = scanner.scan()
            val existing = db.media().all().associateBy { it.id }
            val upserts = scanned.filter { existing[it.id] != it }
            val seen = scanned.mapTo(HashSet(scanned.size)) { it.id }
            val removed = existing.keys.filter { it !in seen }
            if (upserts.isNotEmpty() || removed.isNotEmpty()) {
                db.withTransaction {
                    upserts.chunked(500).forEach { db.media().upsert(it) }
                    removed.chunked(500).forEach { db.media().delete(it) }
                }
            }
            SyncResult(upserts.count { it.id !in existing }, upserts.count { it.id in existing }, removed.size)
        }
    }

    /** Emits whenever MediaStore's photos or videos change, while collected. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        MediaScanner.watchedUris.forEach { resolver.registerContentObserver(it, true, observer) }
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}
