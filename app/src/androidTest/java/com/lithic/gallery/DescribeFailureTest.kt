package com.lithic.gallery

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.data.DescriptionEntity
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What describing records when a file fails: nothing when the file cannot be opened now (so a
 * later run tries again), the error when it opens but cannot be decoded (so it is not retried
 * until it changes), and a file on record as failed is left alone. Needs the caption model in
 * the app's folder (push-models.bat debug). The test files are pending MediaStore entries in
 * Pictures/, which every device has, so nothing is left behind.
 */
@RunWith(AndroidJUnit4::class)
class DescribeFailureTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as GalleryApp
    private val dao = app.db.ai()
    private val captionsOnly = Settings(captions = true, ocr = false)
    private val textOnly = Settings(captions = false, ocr = true)

    @Before
    fun needsModels() {
        assumeTrue("the caption model is not on this device: run push-models.bat debug", app.ai.ready(captionsOnly))
    }

    private fun item(uri: Uri, key: String) = MediaEntity(
        id = -1, uri = uri.toString(), isVideo = false, displayName = "x.jpg", relativePath = "Pictures/",
        mimeType = "image/jpeg", sortDate = 0, dateModified = 0, size = 0, width = 0, height = 0,
        orientation = 0, durationMs = 0, folder = 0, aiKey = key, generation = 0,
    )

    @Test
    fun aFileThatCannotBeOpenedIsLeftForTheNextRun() = runBlocking {
        val key = "describe-failure-test-missing-${System.nanoTime()}"
        // An id MediaStore has never given out: what a file deleted while it waits in a run looks like.
        val gone = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            .buildUpon().appendPath("2147483000").build()
        try {
            assertThat(app.ai.describe(item(gone, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
            assertThat(dao.description(key)).isNull()
        } finally {
            dao.deleteDescription(key)
        }
    }

    @Test
    fun aFileThatFailedIsNotTriedAgain() = runBlocking {
        val key = "describe-failure-test-failed-${System.nanoTime()}"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "describe-failure-test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            // A readable picture whose earlier attempt is on record as failed.
            resolver.openOutputStream(uri)!!.use { out ->
                android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            val failed = DescriptionEntity(key, null, null, "", 1L, "out of memory")
            dao.upsertDescription(failed)
            assertThat(app.ai.describe(item(uri, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
            assertThat(dao.description(key)).isEqualTo(failed)
        } finally {
            resolver.delete(uri, null, null)
            dao.deleteDescription(key)
        }
    }

    @Test
    fun anEmbeddingMiniLmFailedToMakeIsMadeLater() = runBlocking {
        val key = "describe-failure-test-unembedded-${System.nanoTime()}"
        try {
            val d = DescriptionEntity(key, "a cat sitting on a couch", "", "", 1L, null)
            dao.saveUnembedded(d)
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNull()
            assertThat(app.ai.embedMissing(d, captionsOnly)).isEqualTo(AiEngine.Outcome.Described)
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNotNull()
            // Made, it is not made again.
            assertThat(app.ai.embedMissing(d, captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
        } finally {
            dao.forget(key)
        }
    }

    @Test
    fun anEmbeddingIsNotSavedOverADescriptionResetMeanwhile() = runBlocking {
        val key = "describe-failure-test-reset-${System.nanoTime()}"
        try {
            val d = DescriptionEntity(key, "a dog on a beach", "", "", 1L, null)
            dao.saveUnembedded(d)
            dao.forget(key) // "Describe again", between the listing and the embedding
            assertThat(app.ai.embedMissing(d, captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
            assertThat(dao.description(key)).isNull()
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNull()
        } finally {
            dao.forget(key)
        }
    }

    @Test
    fun aPartMadeResetsTheCount() = runBlocking {
        val key = "describe-failure-test-reset-count-${System.nanoTime()}"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "describe-failure-test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                android.graphics.Bitmap.createBitmap(64, 48, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            // The models failed on it in an earlier run, and it was counted.
            dao.noteModelFailure(key, "", 1L)
            assertThat(app.ai.recordModelFailure(key, "boom")).isTrue()
            assertThat(dao.description(key)!!.modelFailures).isEqualTo(1)
            assertThat(app.ai.describe(item(uri, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Described)
            val d = dao.description(key)!!
            assertThat(d.caption).isNotNull()
            assertThat(d.modelFailures).isEqualTo(0)
            assertThat(d.lastModelFailure).isNull()
        } finally {
            resolver.delete(uri, null, null)
            dao.forget(key)
        }
    }

    @Test
    fun theProbesFindTheModelsWorking() = runBlocking {
        assumeTrue("the text models are not on this device", app.ai.ready(textOnly))
        // The emulator's photos have captions and text read, so there are pictures to try them on.
        assertThat(app.ai.modelsStillWork(AiEngine.Part.CAPTIONS, null, captionsOnly)).isTrue()
        assertThat(app.ai.modelsStillWork(AiEngine.Part.TEXT, null, textOnly)).isTrue()
        assertThat(app.ai.modelsStillWork(AiEngine.Part.EMBEDDINGS, null, captionsOnly)).isTrue()
    }

    @Test
    fun aProbePassesOverAReferenceThatCannotBeReadNow() = runBlocking {
        // The newest captioned file is one that cannot be opened: the probe tries the next.
        val key = "describe-failure-test-reference-${System.nanoTime()}"
        val gone = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            .buildUpon().appendPath("2147483001").build()
        val media = item(gone, key).copy(id = 2147483001)
        try {
            app.db.media().upsert(listOf(media))
            dao.saveDescribed(DescriptionEntity(key, "a bird", null, "", Long.MAX_VALUE / 2, null), null)
            assertThat(dao.captionedReferences(1).single().aiKey).isEqualTo(key)
            assertThat(app.ai.modelsStillWork(AiEngine.Part.CAPTIONS, null, captionsOnly)).isTrue()
        } finally {
            app.db.media().delete(listOf(media.id))
            dao.forget(key)
        }
    }

    @Test
    fun aFileThatCannotBeDecodedIsRecordedAsFailed() = runBlocking {
        val key = "describe-failure-test-corrupt-${System.nanoTime()}"
        val resolver = context.contentResolver
        // Pending, so the gallery and other apps never list it.
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "describe-failure-test.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            resolver.openOutputStream(uri)!!.use { out -> out.write(ByteArray(4096) { (it * 31).toByte() }) }
            assertThat(app.ai.describe(item(uri, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Failed)
            assertThat(dao.description(key)?.error).isNotNull()
        } finally {
            resolver.delete(uri, null, null)
            dao.deleteDescription(key)
        }
    }
}
