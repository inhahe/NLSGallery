package com.lithic.gallery.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Weight files copied in over USB (push-models.bat): checked, checked again when a new copy
 * replaces one that failed, and checked rather than downloaded again. The network is a dead
 * proxy here, so nothing is ever fetched.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModelManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ModelManager.RECOGNIZER // the smallest model: 15 MB
    private lateinit var models: ModelManager
    private val proxies = listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")
    private val saved = HashMap<String, String?>()

    @Before
    fun setUp() {
        for (p in proxies) saved[p] = System.getProperty(p)
        System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "9")
        System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "9")
        models = ModelManager(context)
        runBlocking { models.delete(key) }
    }

    @After
    fun tearDown() {
        runBlocking { models.delete(key) }
        for (p in proxies) {
            val v = saved[p]
            if (v != null) System.setProperty(p, v) else System.clearProperty(p)
        }
    }

    private fun weights() = File(models.store.weightsDir(key), models.sources.getValue(key).file)

    /** Copies [bytes] in as push-models.bat would, with a modification time of its own. */
    private fun copyIn(bytes: ByteArray, time: Long) {
        weights().apply { parentFile!!.mkdirs(); writeBytes(bytes); setLastModified(time) }
    }

    private fun failure() = (models.state.value[key] as? ModelState.Failed)?.message

    @Test
    fun aNewCopyOverOneThatFailedIsCheckedAgain() = runBlocking {
        val size = models.sources.getValue(key).size.toInt()
        copyIn(ByteArray(size), 1_000_000_000_000)
        models.verifyCopied()
        val first = failure()
        assertThat(first).isNotNull()
        // Checked once, a failed copy is not hashed again at every resume...
        models.verifyCopied()
        assertThat(failure()).isEqualTo(first)
        // ...but a new one is.
        copyIn(ByteArray(size) { 1 }, 1_000_000_100_000)
        models.verifyCopied()
        assertThat(failure()).isNotNull()
        assertThat(failure()).isNotEqualTo(first)
    }

    @Test
    fun aFileCopiedOverAVerifiedOneIsCheckedAgain() = runBlocking {
        val official = File("../tools/weights/$key/${models.sources.getValue(key).file}")
        assumeTrue("$official is missing: run tools/fetch_weights.py", official.exists())
        copyIn(official.readBytes(), 1_000_000_000_000)
        models.verifyCopied()
        assertThat(models.state.value[key]).isEqualTo(ModelState.Ready)
        // An interrupted or wrong copy over it, the same size.
        copyIn(ByteArray(official.length().toInt()), 1_000_000_100_000)
        models.verifyCopied()
        assertThat(failure()).isNotNull()
    }

    @Test
    fun aCompleteCopyIsCheckedNotDownloaded() = runBlocking {
        val official = File("../tools/weights/$key/${models.sources.getValue(key).file}")
        assumeTrue("$official is missing: run tools/fetch_weights.py", official.exists())
        copyIn(official.readBytes(), 1_000_000_000_000)
        assertThat(models.state.value[key]).isNotEqualTo(ModelState.Ready)
        models.download(listOf(key))
        assertThat(models.state.value[key]).isEqualTo(ModelState.Ready)
        assertThat(weights().length()).isEqualTo(models.sources.getValue(key).size)
    }
}
