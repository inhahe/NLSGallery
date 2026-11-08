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
            dao.saveUnembedded(DescriptionEntity(key, "a cat sitting on a couch", "", "", 1L, null))
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNull()
            assertThat(app.ai.embedMissing(key, captionsOnly)).isEqualTo(AiEngine.Outcome.Described)
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNotNull()
            // Made, it is not made again.
            assertThat(app.ai.embedMissing(key, captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
        } finally {
            dao.forget(key)
        }
    }

    @Test
    fun theProbeFindsTheModelsWorking() = runBlocking {
        assertThat(app.ai.modelsWork(captionsOnly, captions = true, ocr = false)).isTrue()
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
