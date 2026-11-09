package com.lithic.gallery

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.data.DescriptionEntity
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import com.lithic.gallery.work.DescribeFileWorker
import com.lithic.gallery.work.Work
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What describing records when a file fails: nothing when the file cannot be opened now (so a
 * later run tries again), the error when it opens but cannot be decoded (so it is not retried
 * until it changes), and a file on record as failed is left alone, unless described afresh; and
 * the models' sessions. Needs the caption model in
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

    /** Description [key] and its embeddings gone: what the test made, cleared away. */
    private fun erase(key: String) {
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM ai_descriptions WHERE hash = ?", arrayOf(key))
        app.db.openHelper.writableDatabase.execSQL("DELETE FROM ai_embeddings WHERE hash = ?", arrayOf(key))
    }

    /** Runs [block] with a picture (a drawn scene) in a pending MediaStore entry, deleted after. */
    private suspend fun withPicture(block: suspend (Uri) -> Unit) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "describe-failure-test-picture.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        try {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.rgb(120, 170, 230))
                drawRect(0f, 330f, 640f, 480f, Paint().apply { color = Color.rgb(60, 140, 60) })
                drawCircle(500f, 110f, 60f, Paint().apply { color = Color.YELLOW })
                drawText("OPEN", 60f, 250f, Paint().apply { color = Color.BLACK; textSize = 120f })
            }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            block(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun describingAgainRemakesThePartsAskedFor() = runBlocking {
        val key = "describe-failure-test-again-${System.nanoTime()}"
        try {
            withPicture { uri ->
                // Set aside as failed: left alone, but tried again when asked for afresh.
                dao.upsertDescription(DescriptionEntity(key, "older words", null, "", 1L, "it failed before", 2, 1L))
                assertThat(app.ai.describe(item(uri, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
                assertThat(app.ai.describe(item(uri, key), captionsOnly, again = true)).isEqualTo(AiEngine.Outcome.Described)
                val made = dao.description(key)!!
                assertThat(made.error).isNull()
                assertThat(made.modelFailures).isEqualTo(0)
                assertThat(made.caption).isNotEqualTo("older words")
                // A caption there already: nothing to make, unless asked for afresh; the text, not
                // asked for, kept as it was.
                dao.upsertDescription(made.copy(caption = "older words", ocr = "OLD"))
                assertThat(app.ai.describe(item(uri, key), captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
                assertThat(app.ai.describe(item(uri, key), captionsOnly, again = true)).isEqualTo(AiEngine.Outcome.Described)
                assertThat(dao.description(key)!!.caption).isEqualTo(made.caption)
                assertThat(dao.description(key)!!.ocr).isEqualTo("OLD")
            }
        } finally {
            app.ai.release()
            erase(key)
        }
    }

    @Test
    fun aFileDescribedAgainSaysWhyWhenItIsNot() = runBlocking {
        suspend fun describeAgain(media: MediaEntity): ListenableWorker.Result {
            app.db.media().upsert(listOf(media))
            try {
                return TestListenableWorkerBuilder<DescribeFileWorker>(app)
                    .setInputData(workDataOf(Work.KEY_MEDIA to media.id)).build().doWork()
            } finally {
                app.db.media().delete(listOf(media.id))
            }
        }
        // The worker describes by the app's own settings.
        val s = app.settings.current()
        assumeTrue("the app's settings ask for no captions, or their models are missing", s.captions && app.ai.ready(s))
        val key = "describe-failure-test-file-${System.nanoTime()}"
        try {
            withPicture { uri ->
                val result = describeAgain(item(uri, key).copy(id = ContentUris.parseId(uri)))
                assertThat(result).isEqualTo(ListenableWorker.Result.success())
                assertWithMessage("described").that(dao.description(key)?.caption).isNotEmpty()
            }
            // One that cannot be opened: said, and its description left as it was.
            val gone = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                .buildUpon().appendPath("2147483000").build()
            val before = dao.description(key)
            val result = describeAgain(item(gone, key).copy(id = 2147483000L))
            val output = (result as ListenableWorker.Result.Success).outputData
            assertThat(output.getString(Work.KEY_ERROR)).isEqualTo("it could not be opened just now")
            // About the description it left: so said only until the file is described again.
            assertThat(output.getLong(Work.KEY_DESCRIBED_AT, -1)).isEqualTo(before!!.describedAt)
            assertThat(dao.description(key)).isEqualTo(before)
            assertThat(app.ai.modelsOpen()).isFalse()
        } finally {
            erase(key)
        }
    }

    @Test
    fun theModelsCloseWhenTheLastSessionEnds() = runBlocking {
        val key = "describe-failure-test-session-${System.nanoTime()}"
        try {
            withPicture { uri ->
                app.ai.release()
                app.ai.session {
                    // A file described beside a run: when it ends, the run still has the models.
                    app.ai.session { app.ai.describe(item(uri, key), captionsOnly, again = true) }
                    assertThat(app.ai.modelsOpen()).isTrue()
                }
                assertThat(app.ai.modelsOpen()).isFalse()
                // However the last ends.
                runCatching { app.ai.session { app.ai.describe(item(uri, key), captionsOnly, again = true); error("stopped") } }
                assertThat(app.ai.modelsOpen()).isFalse()
            }
        } finally {
            app.ai.release()
            erase(key)
        }
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
            erase(key)
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
            erase(key)
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
            erase(key)
        }
    }

    @Test
    fun anEmbeddingIsNotSavedOverADescriptionResetMeanwhile() = runBlocking {
        val key = "describe-failure-test-reset-${System.nanoTime()}"
        try {
            val d = DescriptionEntity(key, "a dog on a beach", "", "", 1L, null)
            dao.saveUnembedded(d)
            // Described again with other words ("Describe again"), between the listing and the embedding.
            dao.saveUnembedded(d.copy(caption = "a dog running on a beach", describedAt = 2L))
            assertThat(app.ai.embedMissing(d, captionsOnly)).isEqualTo(AiEngine.Outcome.Skipped)
            assertThat(dao.description(key)!!.caption).isEqualTo("a dog running on a beach")
            assertThat(dao.embedding(key, MiniLmEmbedder.MODEL_ID)).isNull()
        } finally {
            erase(key)
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
            erase(key)
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
            erase(key)
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
            erase(key)
        }
    }
}
