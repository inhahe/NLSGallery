package com.lithic.gallery.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.lithic.gallery.GalleryApp
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.core.ai.MiniLmEmbedder
import com.lithic.gallery.core.ai.embeddingText
import com.lithic.gallery.data.DescriptionEntity
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.R
import com.lithic.gallery.data.MediaScanner
import com.lithic.gallery.data.OnBattery
import com.lithic.gallery.data.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Who asked for a describe run, which says what it waits for ([Work.constraints]) and what it
 * takes ([Work.scope]); kept as the request's tag. In order of precedence: requests merge into
 * the first of theirs ([Work.merge]).
 */
internal enum class Run {
    /** The AI button, redescribing, retrying: everything, at once, whatever the battery. */
    USER,

    /** Automatic, without the charger: waits for the battery not to be low; takes what "On battery" allows. */
    BATTERY,

    /**
     * Automatic, on the charger: everything; Android stops it when the phone is unplugged
     * ([Work.chargerRunStopped]). Every automatic run in [OnBattery.CHARGER_ONLY]; in
     * [OnBattery.NEW_ONLY], the one [ChargerWorker] asks for.
     */
    CHARGER;

    val tag: String get() = "run-$name"
}

/** What a describe run takes ([Work.scope]). */
internal enum class Scope {
    /** Every file waiting. */
    ALL,

    /** The new files ([Work.isNew]), newest first, as many as the day's [BatteryBudget] allows. */
    NEW,

    /** Nothing: it waits for the charger (a run on battery asked for before "Nothing" was chosen). */
    WAIT,
}

/** Scheduling of the app's background work. */
object Work {
    const val DESCRIBE = "describe"
    /** In [OnBattery.NEW_ONLY]: once the phone is plugged in, a run for what runs on battery left ([ChargerWorker]). */
    const val DESCRIBE_ON_CHARGER = "describe-on-charger"
    /** In [OnBattery.NEW_ONLY], the files described on battery: taken in the last [NEW_DAYS] days. */
    const val NEW_DAYS = 7L
    const val MEDIA_TRIGGER = "media-trigger"
    const val DOWNLOAD = "download-models"
    /** Whether the user asked for a run, in a request queued before runs were tagged with their [Run]: read, never written. */
    const val KEY_USER = "user"
    const val KEY_DONE = "done"
    const val KEY_TOTAL = "total"
    /** Which pass a describe run is in: "embeddings", "captions", "text", "embeddings". */
    const val KEY_PHASE = "phase"
    /** Files done, and in all, in the pass under way (KEY_PHASE): passes go at very different speeds. */
    const val KEY_PHASE_DONE = "phaseDone"
    const val KEY_PHASE_TOTAL = "phaseTotal"
    const val KEY_KEYS = "keys"
    const val KEY_ERROR = "error"

    const val CHANNEL_AI = "ai"
    const val CHANNEL_DOWNLOAD = "download"
    private const val NOTIFY_DESCRIBE = 1
    private const val NOTIFY_DOWNLOAD = 2
    private const val TAG = "Work"

    /** One request placed at a time: each reads the runs, then changes them. */
    private val requests = Mutex()

    /**
     * Describes everything not yet described: asked by the user, at once and whatever the
     * battery; automatically, as [Settings.onBattery] says ([request]).
     */
    suspend fun describe(context: Context, userInitiated: Boolean, s: Settings) =
        request(context, if (userInitiated) Run.USER else automatic(s.onBattery))

    /** What an automatic request is under [rule]. */
    internal fun automatic(rule: OnBattery): Run = if (rule == OnBattery.CHARGER_ONLY) Run.CHARGER else Run.BATTERY

    /**
     * Asks for a describe run of [kind]. The chain holds a run going and at most one waiting
     * after it: a run's lists are made when it begins, so the one waiting takes what is added or
     * reset meanwhile, and each request merges into it ([merge]) rather than being dropped or
     * replacing it. With none going, what waits is replaced by the merged request.
     */
    internal suspend fun request(context: Context, kind: Run) {
        val wm = WorkManager.getInstance(context)
        requests.withLock {
            // The one waiting may start between reading it and changing it: then look again.
            repeat(3) {
                val infos = wm.getWorkInfosForUniqueWorkFlow(DESCRIBE).first()
                val waiting = infos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                val merged = merge(waiting.map(::kindOf) + kind)
                if (infos.none { it.state == WorkInfo.State.RUNNING }) {
                    wm.enqueueUniqueWork(DESCRIBE, ExistingWorkPolicy.REPLACE, describeRequest(merged)).await()
                    return
                }
                if (waiting.isEmpty()) {
                    wm.enqueueUniqueWork(DESCRIBE, ExistingWorkPolicy.APPEND_OR_REPLACE, describeRequest(merged)).await()
                    return
                }
                if (waiting.all {
                        kindOf(it) == merged ||
                            wm.updateWork(describeRequest(merged, it.id)).await() == WorkManager.UpdateResult.APPLIED_IMMEDIATELY
                    }) return
            }
            Log.w(TAG, "a describe request ($kind) found the runs changing under it; the next one places it")
        }
    }

    /**
     * The request that serves all of [kinds]: the user's (everything, at once), else one on
     * battery (it starts without the charger, and queues the charger's run for what it leaves).
     */
    internal fun merge(kinds: List<Run>): Run = kinds.minBy { it.ordinal }

    /** [Run] by the tags of its request; null for one queued before runs were tagged. */
    internal fun runOf(tags: Set<String>): Run? = Run.entries.firstOrNull { it.tag in tags }

    /** One queued before runs were tagged counts as the user's, so it is never taken for less. */
    private fun kindOf(info: WorkInfo): Run = runOf(info.tags) ?: Run.USER

    private fun describeRequest(kind: Run, id: UUID? = null): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<DescribeWorker>()
            .apply { if (id != null) setId(id) }
            .setConstraints(constraints(kind))
            .addTag(kind.tag)
            .build()

    /** What a run of [kind] waits for: the user's nothing; an automatic one the battery not low, and the charger's the charger. */
    internal fun constraints(kind: Run): Constraints =
        if (kind == Run.USER) Constraints.NONE
        else Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresCharging(kind == Run.CHARGER).build()

    /** What a run of [kind] takes under [rule], as it starts (the rule may have changed since it was asked for). */
    internal fun scope(kind: Run, rule: OnBattery): Scope = when {
        kind != Run.BATTERY || rule == OnBattery.ANYTIME -> Scope.ALL
        rule == OnBattery.NEW_ONLY -> Scope.NEW
        else -> Scope.WAIT
    }

    /**
     * What a run of [kind] takes, as it starts under [rule]; null when it takes nothing: a run on
     * battery asked for before "Nothing" was chosen, which asks for the charger's instead.
     */
    internal suspend fun begin(context: Context, kind: Run, rule: OnBattery): Scope? {
        val scope = scope(kind, rule)
        if (scope != Scope.WAIT) return scope
        Log.i(TAG, "on battery automatic describing waits for the charger")
        request(context, Run.CHARGER)
        return null
    }

    /** The files a run describes, pass by pass, and whether it left any for the charger. */
    internal data class Plan(val captions: List<MediaEntity>, val text: List<MediaEntity>, val leftForCharger: Boolean)

    /**
     * What a run of [scope] ([Scope.ALL] or [Scope.NEW]) describes of the files waiting for
     * [captions] and for [text], each list in its pass's order: all of them, or the files new at
     * [now] (epoch ms), newest first, at most [budget] of them, a file counted once whichever
     * passes it waits for.
     */
    internal fun plan(scope: Scope, captions: List<MediaEntity>, text: List<MediaEntity>, budget: Int, now: Long): Plan {
        if (scope == Scope.ALL) return Plan(captions, text, leftForCharger = false)
        val chosen = (captions + text).filter { isNew(it.sortDate, now) }
            .sortedByDescending { it.sortDate }
            .map { it.aiKey }.distinct().take(budget).toHashSet()
        val c = captions.filter { it.aiKey in chosen }
        val t = text.filter { it.aiKey in chosen }
        return Plan(c, t, leftForCharger = c.size < captions.size || t.size < text.size)
    }

    /** Whether a file taken at [sortDate] counts as new at [now] (epoch ms both): taken in the last [NEW_DAYS] days. */
    internal fun isNew(sortDate: Long, now: Long): Boolean = sortDate >= now - TimeUnit.DAYS.toMillis(NEW_DAYS)

    /**
     * Queues [ChargerWorker]: once the phone is plugged in, a run for what one on battery left.
     * It replaces one queued, or one starting (whose request this one then makes).
     */
    internal fun describeOnCharger(context: Context) {
        val request = OneTimeWorkRequestBuilder<ChargerWorker>().setConstraints(constraints(Run.CHARGER)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DESCRIBE_ON_CHARGER, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * The charger's run [id] was stopped. Unless it was cancelled (Stop), or the phone still
     * charges (stopped for another reason, Android queues it again as it is), the phone was
     * unplugged: in [OnBattery.NEW_ONLY] ([rule]) a run on battery takes its place, which goes on
     * with the new files and queues the charger's run again for the rest. In
     * [OnBattery.CHARGER_ONLY] it waits for the charger, queued again by Android.
     */
    internal suspend fun chargerRunStopped(context: Context, id: UUID, rule: OnBattery) {
        if (rule != OnBattery.NEW_ONLY || charging(context)) return
        val wm = WorkManager.getInstance(context)
        requests.withLock {
            val infos = wm.getWorkInfosForUniqueWorkFlow(DESCRIBE).first()
            // Still going, or already queued again by Android; not cancelled, nor replaced.
            val state = infos.firstOrNull { it.id == id }?.state
            if (state != WorkInfo.State.RUNNING && state != WorkInfo.State.ENQUEUED) return
            val others = infos.filter { it.id != id && !it.state.isFinished }
            wm.enqueueUniqueWork(DESCRIBE, ExistingWorkPolicy.REPLACE,
                describeRequest(merge(others.map(::kindOf) + Run.BATTERY))).await()
        }
    }

    /**
     * "On battery" became [rule]. A run on battery going (which took what the old rule allowed)
     * starts again; the other automatic runs are made what the rule asks for (one going, for when
     * it starts again); the user's stay. Out of [OnBattery.NEW_ONLY], the charger's run, if
     * queued, becomes an automatic request.
     */
    internal suspend fun replan(context: Context, rule: OnBattery) {
        val wm = WorkManager.getInstance(context)
        if (rule != OnBattery.NEW_ONLY && wm.getWorkInfosForUniqueWorkFlow(DESCRIBE_ON_CHARGER).first().any { !it.state.isFinished }) {
            wm.cancelUniqueWork(DESCRIBE_ON_CHARGER).await()
            request(context, automatic(rule))
        }
        requests.withLock {
            val live = wm.getWorkInfosForUniqueWorkFlow(DESCRIBE).first().filter { !it.state.isFinished }
            fun under(info: WorkInfo) = kindOf(info).let { if (it == Run.USER) it else automatic(rule) }
            if (live.any { it.state == WorkInfo.State.RUNNING && kindOf(it) == Run.BATTERY }) {
                wm.enqueueUniqueWork(DESCRIBE, ExistingWorkPolicy.REPLACE, describeRequest(merge(live.map(::under)))).await()
            } else {
                for (info in live) if (under(info) != kindOf(info)) wm.updateWork(describeRequest(under(info), info.id)).await()
            }
        }
    }

    /** Whether the phone is charging, as Android's job scheduling counts it (a weak charger the phone drains on may not count). */
    fun charging(context: Context): Boolean = context.getSystemService(BatteryManager::class.java)?.isCharging ?: false

    /** Stops describing until something asks again: the run going, the one waiting, and the charger's. */
    fun stopDescribing(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(DESCRIBE)
        wm.cancelUniqueWork(DESCRIBE_ON_CHARGER)
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
 * Describes every file that lacks a description (or a part of one now switched on): captions
 * first, newest first (about 2 s each, so the whole library becomes searchable soon), then the
 * text in pictures (10-60 s each), likeliest to hold text first. Before and after those two
 * passes it makes the embeddings MiniLM failed to make (milliseconds each). Each file is saved as
 * it finishes, so stopping loses at most the one in progress.
 *
 * Each pass tells files the models fail on from models failing ([DescribePass], probing with
 * [AiEngine.modelsStillWork]); a file is counted at most once a run ([AiEngine.recordModelFailure]).
 * Files the models failed on come last in every pass. When the caption or text models are found
 * failing, the run stops and is retried later; when MiniLM is, or a pass could not tell why its
 * models failed, the other passes go on (descriptions are saved without embeddings) and the run
 * is retried later, the files that failed then coming after the others. MiniLM's failures are
 * never counted against a file.
 *
 * What a run takes is decided as it starts, by who asked for it ([Run]) and "On battery" as it
 * then stands ([Work.scope]).
 */
class DescribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = Work.runOf(tags) ?: if (inputData.getBoolean(Work.KEY_USER, false)) Run.USER else Run.BATTERY
        try {
            return describe(kind)
        } finally {
            // Android stops a run on the charger when the phone is unplugged (and on Stop).
            if (isStopped && kind == Run.CHARGER) withContext(NonCancellable) {
                val app = applicationContext as GalleryApp
                Work.chargerRunStopped(applicationContext, id, app.settings.current().onBattery)
            }
        }
    }

    private suspend fun describe(kind: Run): Result {
        val app = applicationContext as GalleryApp
        val s = app.settings.current()
        val started = System.currentTimeMillis()
        val scope = Work.begin(applicationContext, kind, s.onBattery) ?: return Result.success()
        app.models.verifyCopied()
        if (!app.ai.ready(s)) return Result.success()
        app.repository.sync()
        val dao = app.db.ai()
        suspend fun unembedded() = dao.withoutEmbedding(MiniLmEmbedder.MODEL_ID)
            .filter { embeddingText(it.caption ?: "", it.ocr ?: "") != null }
        val embedFirst = unembedded()
        val user = kind == Run.USER
        // On battery in "new photos only": the new files, up to the day's budget; the charger's
        // run takes the rest. Embeddings take milliseconds: always all.
        val budget = BatteryBudget(applicationContext).takeIf { scope == Scope.NEW }
        val plan = Work.plan(scope,
            if (s.captions) dao.undescribed(s.describeVideos, captions = true, ocr = false) else emptyList(),
            if (s.ocr) dao.withoutText(s.describeVideos) else emptyList(),
            budget?.left(LocalDate.now()) ?: Int.MAX_VALUE, started)
        if (plan.leftForCharger && !isStopped) Work.describeOnCharger(applicationContext)
        val captionPass = plan.captions
        val textPass = plan.text
        var total = embedFirst.size + captionPass.size + textPass.size
        Log.i(TAG, "describing ($kind): ${captionPass.size} captions, ${textPass.size} texts, ${embedFirst.size} embeddings" +
            if (plan.leftForCharger) " (on battery: the new ones, up to ${BatteryBudget.PER_DAY} a day; the rest waits for the charger)" else "")
        if (total == 0) return Result.success()
        val firstPhase = when {
            embedFirst.isNotEmpty() -> AiEngine.Part.EMBEDDINGS
            captionPass.isNotEmpty() -> AiEngine.Part.CAPTIONS
            else -> AiEngine.Part.TEXT
        }.phase
        // A notification for any run worth watching. From the background Android may refuse a
        // foreground service; then this runs as an ordinary job (limited to ten minutes, and
        // resumed by the next trigger).
        var foreground = false
        if (user || total > 3) {
            foreground = try {
                setForeground(Work.describeNotification(applicationContext, id, 0, total, firstPhase)); true
            } catch (e: IllegalStateException) {
                Log.i(TAG, "not in the foreground: ${e.message}"); false
            }
        }
        var done = 0
        var phaseDone = 0
        var phaseTotal = 0
        var lastShown = 0L
        suspend fun report(phase: String) = setProgress(workDataOf(Work.KEY_DONE to done, Work.KEY_TOTAL to total,
            Work.KEY_PHASE to phase, Work.KEY_PHASE_DONE to phaseDone, Work.KEY_PHASE_TOTAL to phaseTotal))
        suspend fun progress(phase: String) {
            done++
            phaseDone++
            // At most once a second: an embedding takes milliseconds, and Android drops
            // notifications posted faster.
            val now = SystemClock.elapsedRealtime()
            if (now - lastShown < 1000 && done < total) return
            lastShown = now
            report(phase)
            if (foreground) setForeground(Work.describeNotification(applicationContext, id, done, total, phase))
        }
        /** A pass of [size] files begins: shown at once (a text pass's first file can take a minute). */
        suspend fun begin(part: AiEngine.Part, size: Int) {
            if (size == 0) return
            phaseDone = 0
            phaseTotal = size
            report(part.phase)
        }
        // A file counts once a run, whichever passes the models failed on it in (a blame the
        // database finds stale does not use that up).
        val counted = HashSet<String>()
        suspend fun count(key: String, message: String) {
            if (key !in counted && app.ai.recordModelFailure(key, message)) counted.add(key)
        }
        // Each file a run on battery tries counts against the day's budget, once whatever its passes.
        val charged = HashSet<String>()
        fun describing(part: AiEngine.Part) = DescribePass<MediaEntity>(
            attempt = {
                if (budget != null && charged.add(it.aiKey)) budget.use(LocalDate.now())
                app.ai.describe(it, s, captions = part == AiEngine.Part.CAPTIONS, ocr = part == AiEngine.Part.TEXT)
            },
            probe = { app.ai.modelsStillWork(part, it, s) },
            blame = { files -> for ((item, message) in files) count(item.aiKey, message) },
            // If it was memory, what closing the models gives back is there for the next file.
            afterFailure = { app.ai.release() },
            progress = { progress(part.phase) },
        )
        val embedding = DescribePass<DescriptionEntity>(
            attempt = { app.ai.embedMissing(it, s) },
            probe = { app.ai.modelsStillWork(AiEngine.Part.EMBEDDINGS, null, s) },
            blame = {}, // MiniLM's failures are not counted: an embedding costs milliseconds to retry
            afterFailure = { app.ai.release() },
            progress = { progress(AiEngine.Part.EMBEDDINGS.phase) },
        )

        // A pass that ended without its work done (MiniLM failing, or failures it could not tell
        // apart): the run goes on, and is tried again later.
        var unfinished = false
        try {
            val stop = { isStopped }
            begin(AiEngine.Part.EMBEDDINGS, embedFirst.size)
            unfinished = embedding.run(embedFirst, stop) != DescribePass.End.Done
            for ((part, items) in listOf(AiEngine.Part.CAPTIONS to captionPass, AiEngine.Part.TEXT to textPass)) {
                begin(part, items.size)
                when (describing(part).run(items, stop)) {
                    DescribePass.End.ModelsFailing -> {
                        Log.w(TAG, "the ${part.phase} models fail even on what they handled before; describing again later")
                        return Result.retry()
                    }
                    DescribePass.End.Undecided -> unfinished = true
                    DescribePass.End.Done -> {}
                }
            }
            if (!isStopped) {
                // Those whose embedding MiniLM failed to make in this run: not tried in the first
                // pass, or saved again since it began.
                val tried = embedFirst.mapTo(HashSet()) { it.hash }
                val embedAfter = unembedded().filter { it.hash !in tried || it.describedAt >= started }
                total += embedAfter.size
                begin(AiEngine.Part.EMBEDDINGS, embedAfter.size)
                if (embedding.run(embedAfter, stop) != DescribePass.End.Done) unfinished = true
            }
        } finally {
            app.ai.release()
            app.search.invalidate()
        }
        if (unfinished && !isStopped) {
            Log.w(TAG, "some of this run's work could not be done; it is tried again later")
            return Result.retry()
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Work.describeNotification(applicationContext, id, 0, 0)

    private companion object { const val TAG = "DescribeWorker" }
}

/**
 * Started once the phone is plugged in, after a run on battery left files ([OnBattery.NEW_ONLY]):
 * asks for a run on the charger, which takes them, when describing is automatic at all.
 */
class ChargerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as GalleryApp
        val s = app.settings.current()
        if ((s.describeNewMedia || s.describeOnOpen) && app.ai.ready(s)) Work.request(applicationContext, Run.CHARGER)
        return Result.success()
    }
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
        // Not on a phone that may not run the AI (the screen offers none; this is the backstop).
        if (!app.ai.allowed(app.settings.current())) return Result.success()
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
