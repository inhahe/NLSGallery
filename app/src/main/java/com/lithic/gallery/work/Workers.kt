package com.lithic.gallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lithic.gallery.GalleryApp
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.embeddingText
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.R
import com.lithic.gallery.data.MediaScanner
import com.lithic.gallery.data.Settings
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Scheduling of the app's background work. */
object Work {
    const val DESCRIBE = "describe"
    const val MEDIA_TRIGGER = "media-trigger"
    const val DOWNLOAD = "download-models"
    const val KEY_USER = "user"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    /** Which pass a describe run is in: "embeddings", "captions", "text", "embeddings". */
    const val KEY_PHASE = "phase"
    const val KEY_KEYS = "keys"
    const val KEY_ERROR = "error"

    const val CHANNEL_AI = "ai"
    const val CHANNEL_DOWNLOAD = "download"
    private const val NOTIFY_DESCRIBE = 1
    private const val NOTIFY_DOWNLOAD = 2

    /**
     * Describes everything not yet described. Started by the user it runs now, whatever the
     * battery; started automatically it waits for the battery (and charging, if so set).
     * During a run (whose lists were made when it began) one more run is queued after it, so
     * files added or reset meanwhile are not left out; a queued one is not doubled.
     */
    suspend fun describe(context: Context, userInitiated: Boolean, s: Settings) {
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWorkFlow(DESCRIBE).first()
        val running = infos.any { it.state == WorkInfo.State.RUNNING }
        val queuedAfter = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
        if (running && queuedAfter) return
        val constraints = if (userInitiated) Constraints.NONE else Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(s.onlyWhileCharging)
            .build()
        val request = OneTimeWorkRequestBuilder<DescribeWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_USER to userInitiated))
            .build()
        wm.enqueueUniqueWork(DESCRIBE, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
    }

    fun stopDescribing(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DESCRIBE)
    }

    /**
     * Arms (or disarms) the job MediaStore wakes when photos or videos are added, which brings
     * the library up to date and describes what is new, even when the app is not running.
     * The job runs once per arming: it re-arms itself ([fromTrigger]), queued after itself
     * rather than replacing itself while it runs.
     */
    fun watchNewMedia(context: Context, enabled: Boolean, fromTrigger: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork(MEDIA_TRIGGER); return }
        val constraints = Constraints.Builder().apply {
            MediaScanner.watchedUris.forEach { addContentUriTrigger(it, true) }
            setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
            setTriggerContentMaxDelay(1, TimeUnit.MINUTES)
        }.build()
        val request = OneTimeWorkRequestBuilder<NewMediaWorker>().setConstraints(constraints).build()
        wm.enqueueUniqueWork(MEDIA_TRIGGER,
            if (fromTrigger) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun downloadModels(context: Context, keys: Collection<String>, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build())
            .setInputData(workDataOf(KEY_KEYS to keys.toTypedArray()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD)
    }

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_AI,
            context.getString(R.string.notification_channel_ai), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.notification_channel_ai_description)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DOWNLOAD, "Downloading AI models",
            NotificationManager.IMPORTANCE_LOW))
    }

    /** @param phase "embeddings", "captions" or "text": which pass the run is in. */
    internal fun describeNotification(context: Context, id: java.util.UUID, done: Int, total: Int, phase: String = "captions"): ForegroundInfo {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(context, CHANNEL_AI)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Describing photos for search")
            .setContentText("$done of $total" + when (phase) {
                "text" -> " (reading text in pictures)"
                "embeddings" -> " (search embeddings)"
                else -> " (captions)"
            })
            .setProgress(total, done, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", cancel)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        return ForegroundInfo(NOTIFY_DESCRIBE, n, type)
    }

    internal fun downloadNotification(context: Context, id: java.util.UUID, done: Long, total: Long): ForegroundInfo {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val mb = 1 shl 20
        val n = NotificationCompat.Builder(context, CHANNEL_DOWNLOAD)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading AI models")
            .setContentText("${done / mb} of ${total / mb} MB")
            .setProgress(1000, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .build()
        return ForegroundInfo(NOTIFY_DOWNLOAD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}

/**
 * Describes every file that lacks a description (or a part of one now switched on), in two
 * passes: captions first, newest first (about 2 s each, so the whole library becomes searchable
 * soon), then the text in pictures (10-60 s each), likeliest to hold text first. Before and after
 * them it makes the embeddings MiniLM failed to make (milliseconds each). Each file is saved as it
 * finishes, so stopping loses at most the one in progress.
 *
 * Model failures are told apart per pass ([ModelFailureTracker]): a file the models fail on while
 * they describe others of the same pass is counted (once a run, see
 * [AiEngine.recordModelFailure]); a pass whose models fail on file after file gives up, blaming
 * none, and the run is retried later. Files the models failed on come last in every pass, so they
 * cannot hold up the rest. MiniLM failing is never a file's.
 */
class DescribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val s = app.settings.current()
        app.models.verifyCopied()
        if (!app.ai.ready(s)) return Result.success()
        app.repository.sync()
        val dao = app.db.ai()
        suspend fun unembedded() = dao.withoutEmbedding(MiniLmEmbedder.MODEL_ID)
            .filter { embeddingText(it.caption ?: "", it.ocr ?: "") != null }
        val embedFirst = unembedded()
        val captionPass = if (s.captions) dao.undescribed(s.describeVideos, captions = true, ocr = false) else emptyList()
        val textPass = if (s.ocr) dao.withoutText(s.describeVideos) else emptyList()
        var total = embedFirst.size + captionPass.size + textPass.size
        if (total == 0) return Result.success()
        val user = inputData.getBoolean(Work.KEY_USER, false)
        // A notification for any run worth watching. From the background Android may refuse a
        // foreground service; then this runs as an ordinary job (limited to ten minutes, and
        // resumed by the next trigger).
        var foreground = false
        if (user || total > 3) {
            foreground = try {
                setForeground(Work.describeNotification(applicationContext, id, 0, total)); true
            } catch (e: IllegalStateException) {
                Log.i(TAG, "not in the foreground: ${e.message}"); false
            }
        }
        var done = 0
        var lastShown = 0L
        suspend fun progress(phase: String) {
            done++
            // At most once a second: an embedding takes milliseconds, and Android drops
            // notifications posted faster.
            val now = SystemClock.elapsedRealtime()
            if (now - lastShown < 1000 && done < total) return
            lastShown = now
            setProgress(workDataOf(Work.KEY_DONE to done, Work.KEY_TOTAL to total, Work.KEY_PHASE to phase))
            if (foreground) setForeground(Work.describeNotification(applicationContext, id, done, total, phase))
        }
        // A file counts once a run, whichever passes the models failed on it in.
        val counted = HashSet<String>()
        suspend fun blame(files: List<Pair<MediaEntity, String>>) {
            for ((item, message) in files) if (counted.add(item.aiKey)) app.ai.recordModelFailure(item, message)
        }

        /** One pass over [items]; false if it gave up, its models failing on everything. */
        suspend fun <T> pass(items: List<T>, phase: String, blameFiles: (suspend (List<Pair<T, String>>) -> Unit)?,
                             attempt: suspend (T) -> AiEngine.Outcome): Boolean {
            val failures = ModelFailureTracker<T>()
            for (item in items) {
                if (isStopped) return true
                when (val outcome = attempt(item)) {
                    AiEngine.Outcome.Described -> failures.modelsWorked().let { blameFiles?.invoke(it) }
                    is AiEngine.Outcome.ModelsFailed -> {
                        failures.modelsFailed(item, outcome.message)
                        // If it was memory, the next file gets what closing the models gives back.
                        app.ai.release()
                    }
                    else -> {}
                }
                progress(phase)
                if (failures.shouldGiveUp) {
                    Log.w(TAG, "the models fail on every file of the $phase pass just now; it is tried again later")
                    failures.gaveUp()
                    return false
                }
            }
            failures.finished().let { blameFiles?.invoke(it) }
            return true
        }

        var gaveUp = false
        try {
            gaveUp = !pass(embedFirst, "embeddings", null) { app.ai.embedMissing(it, s) }
            gaveUp = !pass(captionPass, "captions", ::blame) { app.ai.describe(it, s, captions = true, ocr = false) } || gaveUp
            gaveUp = !pass(textPass, "text", ::blame) { app.ai.describe(it, s, captions = false, ocr = true) } || gaveUp
            // Those whose embedding MiniLM failed to make in this run (not those just tried).
            val tried = embedFirst.mapTo(HashSet()) { it.hash }
            val embedAfter = unembedded().filter { it.hash !in tried }
            total += embedAfter.size
            gaveUp = !pass(embedAfter, "embeddings", null) { app.ai.embedMissing(it, s) } || gaveUp
        } finally {
            app.ai.release()
            app.search.invalidate()
        }
        if (isStopped) return Result.success()
        return if (gaveUp) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Work.describeNotification(applicationContext, id, 0, 0)

    private companion object { const val TAG = "DescribeWorker" }
}

/** Woken by MediaStore when photos or videos change: updates the library, describes what is new, re-arms. */
class NewMediaWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val s = app.settings.current()
        val r = app.repository.sync()
        app.models.verifyCopied()
        if (s.describeNewMedia && (r.added > 0 || r.changed > 0) && app.ai.ready(s)) {
            Work.describe(applicationContext, userInitiated = false, s)
        }
        Work.watchNewMedia(applicationContext, s.describeNewMedia, fromTrigger = true)
        return Result.success()
    }
}

/** Downloads the AI models' official weight files, resuming after interruptions. */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val keys = inputData.getNullableStringArray(Work.KEY_KEYS)?.filterNotNull() ?: return Result.success()
        val total = app.models.totalSize(keys.filter { !app.models.isReady(it) })
        runCatching { setForeground(Work.downloadNotification(applicationContext, id, 0, total)) }
        var lastShown = 0L
        return try {
            app.models.download(keys) { done, all ->
                if (done - lastShown >= 8L shl 20 || done == all) {
                    lastShown = done
                    setProgressAsync(workDataOf(Work.KEY_DONE to done, Work.KEY_TOTAL to all))
                    runCatching { setForegroundAsync(Work.downloadNotification(applicationContext, id, done, all)) }
                }
            }
            val s = app.settings.current()
            if (s.describeOnOpen && app.ai.ready(s)) Work.describe(applicationContext, userInitiated = false, s)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ModelDownload", "download failed", e)
            // The network may come back; any other failure would only repeat.
            if (e is IOException && runAttemptCount < 5) Result.retry()
            else Result.failure(workDataOf(Work.KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Work.downloadNotification(applicationContext, id, 0, 0)
}
