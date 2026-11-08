package com.lithic.gallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Descriptions and their embeddings go together: written, forgotten and retried as one. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AiDaoTest {
    private lateinit var db: GalleryDatabase
    private val dao get() = db.ai()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GalleryDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun embedding(key: String) = EmbeddingEntity(key, "m", ByteArray(8), "t$key")

    @Test
    fun retryingFailuresForgetsTheirEmbeddingsToo() = runBlocking {
        // Captioned, then its text pass failed to read it: the caption (and its embedding) kept.
        dao.saveDescribed(DescriptionEntity("failed", "a cat", null, "", 1, null), embedding("failed"))
        dao.upsertDescription(DescriptionEntity("failed", "a cat", null, "", 2, "unreadable"))
        dao.saveDescribed(DescriptionEntity("fine", "a dog", null, "", 3, null), embedding("fine"))

        dao.clearFailures()

        assertThat(dao.description("failed")).isNull()
        assertThat(dao.embedding("failed", "m")).isNull()
        assertThat(dao.description("fine")).isNotNull()
        assertThat(dao.embedding("fine", "m")).isNotNull()
    }

    private fun media(id: Long, key: String) = MediaEntity(
        id, "content://media/$id", false, "IMG_$id.jpg", "DCIM/Camera/", "image/jpeg", id, id, 1, 4, 3, 0, 0, 0, key, 0,
    )

    @Test
    fun aDescriptionSavedWithoutItsEmbeddingIsListedForOne(): Unit = runBlocking {
        db.media().upsert(listOf(media(1, "a"), media(2, "a"), media(3, "b"), media(4, "c"), media(5, "d")))
        // "a": captioned and embedded, then its text read but MiniLM failed: the stale embedding goes.
        dao.saveDescribed(DescriptionEntity("a", "a cat", null, "", 1, null), embedding("a"))
        dao.saveUnembedded(DescriptionEntity("a", "a cat", "SALE", "", 2, null))
        dao.saveDescribed(DescriptionEntity("b", "a dog", null, "", 3, null), embedding("b"))
        dao.saveUnembedded(DescriptionEntity("c", "  ", "", "", 4, null)) // no text
        dao.upsertDescription(DescriptionEntity("d", "a cow", null, "", 5, "unreadable")) // set aside
        dao.saveUnembedded(DescriptionEntity("gone", "a hen", null, "", 6, null)) // no file has it now

        assertThat(dao.embedding("a", "m")).isNull()
        // Once for description "a", though two files share it.
        assertThat(dao.withoutEmbedding("m").map { it.hash }).containsExactly("a")
    }

    @Test
    fun filesTheModelsFailedOnComeLast(): Unit = runBlocking {
        db.media().upsert(listOf(media(1, "old"), media(2, "new"), media(3, "newest")))
        dao.noteModelFailure("newest", "", 10)
        val order = dao.undescribed(videos = true, captions = true, ocr = false).map { it.aiKey }
        assertThat(order).containsExactly("new", "old", "newest").inOrder()
        assertThat(dao.withoutText(videos = true).map { it.aiKey }.last()).isEqualTo("newest")
    }

    @Test
    fun aNotedFailureKeepsTheFilesOwnCaptionSearchable(): Unit = runBlocking {
        dao.noteModelFailure("k", "Grandma's 90th", 10)
        assertThat(dao.description("k")!!.manualCaption).isEqualTo("Grandma's 90th")
        dao.noteModelFailure("k", "Grandma's 90th", 11)
        // Every write moves describedAt on, which the search index watches.
        assertThat(dao.description("k")!!.describedAt).isEqualTo(11)
    }

    @Test
    fun theThirdCountedFailureSetsAFileAside(): Unit = runBlocking {
        dao.noteModelFailure("k", "", 1)
        for (at in 2L..3L) assertThat(dao.countModelFailure("k", "boom", at, max = 3)).isTrue()
        assertThat(dao.description("k")!!.error).isNull()
        assertThat(dao.description("k")!!.modelFailures).isEqualTo(2)
        assertThat(dao.countModelFailure("k", "boom", 4, max = 3)).isTrue()
        assertThat(dao.description("k")!!.error).isEqualTo("the AI failed on it 3 times: boom")
        // Set aside, it is not counted again.
        assertThat(dao.countModelFailure("k", "boom", 5, max = 3)).isFalse()
    }

    @Test
    fun aFailureNoLongerCurrentIsNotCounted(): Unit = runBlocking {
        // Noted, then a part made (by a copy of the same file, say) before it was blamed.
        dao.noteModelFailure("k", "", 1)
        dao.saveDescribed(DescriptionEntity("k", "a cat", null, "", 2, null), embedding("k"))
        assertThat(dao.countModelFailure("k", "boom", 3, max = 3)).isFalse()
        assertThat(dao.description("k")!!.modelFailures).isEqualTo(0)
        // Or reset ("Describe again") before it was blamed: nothing comes back.
        dao.noteModelFailure("j", "", 1)
        dao.forget("j")
        assertThat(dao.countModelFailure("j", "boom", 3, max = 3)).isFalse()
        assertThat(dao.description("j")).isNull()
    }

    @Test
    fun anEmbeddingIsSavedOnlyOverTheTextItWasMadeFrom(): Unit = runBlocking {
        val first = DescriptionEntity("k", "a cat", null, "", 1, null)
        dao.saveUnembedded(first)
        // Its text read meanwhile: the embedding of the caption alone is not saved.
        dao.saveUnembedded(first.copy(ocr = "SALE", describedAt = 2))
        assertThat(dao.saveEmbeddingIfCurrent(embedding("k"), first, 3)).isFalse()
        assertThat(dao.embedding("k", "m")).isNull()
        // Reset meanwhile: nothing comes back.
        dao.forget("k")
        assertThat(dao.saveEmbeddingIfCurrent(embedding("k"), first, 4)).isFalse()
        assertThat(dao.description("k")).isNull()
        // Unchanged: saved, with a new describedAt for the search index.
        dao.saveUnembedded(first)
        assertThat(dao.saveEmbeddingIfCurrent(embedding("k"), first, 5)).isTrue()
        assertThat(dao.embedding("k", "m")).isNotNull()
        assertThat(dao.description("k")!!.describedAt).isEqualTo(5)
    }

    @Test
    fun forgettingTakesTheEmbeddingWithIt() = runBlocking {
        dao.saveDescribed(DescriptionEntity("k", "a cat", null, "", 1, null), embedding("k"))
        dao.forget("k")
        assertThat(dao.description("k")).isNull()
        assertThat(dao.embedding("k", "m")).isNull()
    }
}
