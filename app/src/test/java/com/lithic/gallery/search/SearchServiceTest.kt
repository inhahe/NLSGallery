package com.lithic.gallery.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.embeddingText
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.data.DescriptionEntity
import com.lithic.gallery.data.EmbeddingEntity
import com.lithic.gallery.data.GalleryDatabase
import com.lithic.gallery.data.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The search index kept up to date change by change gives what one built afresh from the same
 * database gives, through descriptions made and reset, files added, moved, renamed, redated and
 * deleted, failures recorded. Embeddings are a function of the text (as MiniLM's are), so equal
 * captions tie in meaning; dates tie too (bursts, copies); and the query's embedding is given, so
 * meaning is ranked too.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SearchServiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: GalleryDatabase
    private val rng = java.util.Random(3)
    private val words = ("cat dog beach sunset receipt invoice total birthday cake mountain snow car street sign " +
        "menu coffee table window phone screen chat message meeting train station garden flower bridge river night").split(" ")
    private val folders = listOf("DCIM/Camera/", "Pictures/Screenshots/", "Download/", "Pictures/Messages/")
    private val topics = Array(5) { unit(FloatArray(DIM) { rng.nextGaussian().toFloat() }) }
    private val media = HashMap<Long, MediaEntity>()
    private var nextId = 1L
    private var nextDate = 1_700_000_000_000L
    /** describedAt as the app writes it: later at every write. */
    private var clock = 1L

    private inner class Service : SearchService(db.ai(), ModelManager(context), { error("no tokenizer in this test") }) {
        override suspend fun embedQuery(text: String): FloatArray = topics[abs(text.hashCode()) % topics.size]
    }

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, GalleryDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** A stand-in for MiniLM: the same text always gives the same vector, near one of the topics. */
    private fun vectorOf(text: String): FloatArray {
        val r = java.util.Random(text.hashCode().toLong())
        val topic = topics[r.nextInt(topics.size)]
        val w = r.nextFloat()
        return unit(FloatArray(DIM) { topic[it] * w * 4 + r.nextGaussian().toFloat() * (1 - w) / 8 })
    }

    private fun phrase(n: Int) = (1..n).joinToString(" ") { words[rng.nextInt(words.size)] }

    private fun newMedia(key: String? = null): MediaEntity {
        val id = nextId++
        // Some taken in the same second as the one before (a burst), so dates tie too.
        if (rng.nextInt(4) != 0) nextDate += 1000 + rng.nextInt(100_000)
        val name = "IMG_$id.jpg"
        return MediaEntity(
            id = id, uri = "content://media/external/images/media/$id", isVideo = false, displayName = name,
            relativePath = folders[rng.nextInt(folders.size)], mimeType = "image/jpeg", sortDate = nextDate,
            dateModified = nextDate / 1000, size = 1000L + id, width = 4, height = 3, orientation = 0,
            durationMs = 0, folder = 0, aiKey = key ?: "key$id", generation = 0,
        )
    }

    private suspend fun describe(key: String, caption: String?, ocr: String?) {
        val text = embeddingText(caption ?: "", ocr ?: "")
        val embedding = text?.let {
            EmbeddingEntity(key, MiniLmEmbedder.MODEL_ID, AiEngine.toBytes(vectorOf(it)), AiEngine.sha1(it))
        }
        db.ai().saveDescribed(DescriptionEntity(key, caption, ocr, if (rng.nextInt(10) == 0) phrase(2) else "", clock++, null), embedding)
    }

    private suspend fun add(m: MediaEntity) {
        db.media().upsert(listOf(m))
        media[m.id] = m
    }

    private suspend fun someChange() {
        val all = media.values.toList()
        val m = all[rng.nextInt(all.size)]
        val d = db.ai().description(m.aiKey)
        when (rng.nextInt(10)) {
            // Captioned (often the same words as another file, so meanings tie).
            0, 1 -> if (d == null) describe(m.aiKey, if (rng.nextInt(4) == 0) "a cat on a table" else phrase(3 + rng.nextInt(4)), null)
            // Its text read.
            2 -> if (d != null && d.error == null && d.ocr == null) describe(m.aiKey, d.caption, if (rng.nextBoolean()) phrase(5) else "")
            // The failed files retried: their descriptions gone.
            3 -> if (d?.error != null) db.ai().clearFailures()
            4 -> { db.media().delete(listOf(m.id)); media.remove(m.id) }
            // A new file, sometimes a copy of an existing one (same key and date, the same description).
            5 -> add(if (rng.nextBoolean()) newMedia(m.aiKey).copy(sortDate = m.sortDate) else newMedia())
            6 -> add(m.copy(relativePath = folders[rng.nextInt(folders.size)]))
            7 -> add(m.copy(displayName = "renamed_${m.id}.jpg", aiKey = "renamed${m.id}"))
            8 -> { nextDate += 1000; add(m.copy(sortDate = nextDate)) }
            9 -> if (d == null) db.ai().upsertDescription(DescriptionEntity(m.aiKey, null, null, "", clock++, "unreadable"))
        }
    }

    private val queries = listOf("cat", "cat table", "beach sunset", "receipt total", "img", "camera", "scre", "mesage", "river night")
    private val fieldSets = listOf(
        HybridSearch.FIELDS,
        listOf(HybridSearch.AI_CAPTION, HybridSearch.AI_OCR),
        listOf(HybridSearch.FILENAME, HybridSearch.PATH),
    )

    /** Same files in the same order; scores equal but for the last bits of floating-point sums. */
    private fun assertSame(what: String, got: List<SearchService.Result>, want: List<SearchService.Result>) {
        assertWithMessage("$what: ids").that(got.map { it.mediaId }).isEqualTo(want.map { it.mediaId })
        for ((g, w) in got.zip(want)) {
            assertWithMessage("$what: score of ${g.mediaId}").that(abs(g.score - w.score)).isAtMost(1e-12)
            assertWithMessage("$what: how ${g.mediaId} was found").that(g.semanticOnly).isEqualTo(w.semanticOnly)
            assertWithMessage("$what: similarity of ${g.mediaId}").that(g.similarity).isEqualTo(w.similarity)
        }
    }

    @Test
    fun keptUpToDateItAnswersAsIfBuiltAfresh() = runBlocking {
        repeat(300) { add(newMedia()) }
        repeat(200) { someChange() }
        val kept = Service()
        var matched = 0
        for (round in 1..25) {
            for (q in queries) kept.search(q, HybridSearch.FIELDS)
            repeat(1 + rng.nextInt(40)) { someChange() }
            kept.invalidate()
            val fresh = Service()
            for (q in queries) for (fields in fieldSets) {
                val want = fresh.search(q, fields)
                assertSame("round $round, '$q' in $fields", kept.search(q, fields), want)
                matched += want.size
            }
        }
        // The comparison had something to compare, by words and by meaning.
        assertThat(matched).isGreaterThan(5000)
        assertThat(Service().search("cat", HybridSearch.FIELDS).count { it.semanticOnly }).isGreaterThan(0)
    }

    private companion object {
        const val DIM = 384
    }
}
