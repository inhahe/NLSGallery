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

    @Test
    fun forgettingTakesTheEmbeddingWithIt() = runBlocking {
        dao.saveDescribed(DescriptionEntity("k", "a cat", null, "", 1, null), embedding("k"))
        dao.forget("k")
        assertThat(dao.description("k")).isNull()
        assertThat(dao.embedding("k", "m")).isNull()
    }
}
