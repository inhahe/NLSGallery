package com.lithic.gallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
    const val KEY_KEYS = "keys"
    const val KEY_ERROR = "error"

    const val CHANNEL_AI = "ai"
    const val CHANNEL_DOWNLOAD = "download"
    private const val NOTIFY_DESCRIBE = 1
    private const val NOTIFY_DOWNLOAD = 2

    /**
     * Describes everything not yet described. Started by the user it runs now, whatever the
     * battery; started automatically it waits for the battery (and charging, if so set).
     */
    suspend fun describe(context: Context, userInitiated: Boolean, s: Settings) {
        val wm = WorkManager.getInstance(context)
        val running = wm.getWorkInfosForUniqueWorkFlow(DESCRIBE).first().any { it.state == WorkInfo.State.RUNNING }
        if (running) return
        val constraints = if (userInitiated) Constraints.NONE else Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(s.onlyWhileCharging)
            .build()
        val request = OneTimeWorkRequestBuilder<DescribeWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(KEY_USER to userInitiated))
            .build()
        wm.enqueueUniqueWork(DESCRIBE, ExistingWorkPolicy.REPLACE, request)
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

    internal fun describeNotification(context: Context, id: java.util.UUID, done: Int, total: Int): ForegroundInfo {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(id)
        val n = NotificationCompat.Builder(context, CHANNEL_AI)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Describing photos for search")
            .setContentText("$done of $total")
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
 * Describes, newest first, every file that lacks a description (or a part of one now switched
 * on), saving each as it finishes, so stopping loses at most the file in progress.
 */
class DescribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val s = app.settings.current()
        if (!app.ai.ready(s)) return Result.success()
        app.repository.sync()
        val todo = app.db.ai().undescribed(s.describeVideos, s.captions, s.ocr)
        if (todo.isEmpty()) return Result.success()
        val user = inputData.getBoolean(Work.KEY_USER, false)
        // A notification for any run worth watching. From the background Android may refuse a
        // foreground service; then this runs as an ordinary job (limited to ten minutes, and
        // resumed by the next trigger).
        var foreground = false
        if (user || todo.size > 3) {
            foreground = try {
                setForeground(Work.describeNotification(applicationContext, id, 0, todo.size)); true
            } catch (e: IllegalStateException) {
                Log.i(TAG, "not in the foreground: ${e.message}"); false
            }
        }
        try {
            for ((i, item) in todo.withIndex()) {
                if (isStopped) break
                app.ai.describe(item, s)
                setProgress(workDataOf(Work.KEY_DONE to i + 1, Work.KEY_TOTAL to todo.size))
                if (foreground) setForeground(Work.describeNotification(applicationContext, id, i + 1, todo.size))
                if ((i + 1) % 25 == 0) app.search.refresh()
            }
        } finally {
            app.ai.release()
            app.search.refresh()
        }
        return Result.success()
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
        val keys = inputData.getStringArray(Work.KEY_KEYS)?.toList() ?: return Result.success()
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
        } catch (e: IOException) {
            Log.w("ModelDownload", "download failed", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(Work.KEY_ERROR to (e.message ?: "download failed")))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Work.downloadNotification(applicationContext, id, 0, 0)
}
