package com.lithic.gallery.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lithic.gallery.GalleryApp
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.ai.Hardware
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.ai.ModelState
import com.lithic.gallery.core.search.DatePhrases
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.core.search.PlacePhrases
import com.lithic.gallery.core.search.SearchReading
import com.lithic.gallery.data.Categories
import com.lithic.gallery.data.Category
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.OnBattery
import com.lithic.gallery.data.Settings
import com.lithic.gallery.data.MediaLocations
import com.lithic.gallery.search.Places
import com.lithic.gallery.search.SearchService
import com.lithic.gallery.work.Work
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A search result shown in the grid: how it was found, or no [result] when it was listed for its date alone. */
data class SearchHit(val item: MediaEntity, val result: SearchService.Result?)

/** What the AI button shows. */
data class AiStatus(
    /** Describing can run: captions or text reading is on, and the models for them are here. */
    val ready: Boolean = false,
    /** This phone has too little memory for the AI, and the user has not asked for it anyway. */
    val notAllowed: Boolean = false,
    /** Captions and text reading are both switched off: nothing is described. */
    val featuresOff: Boolean = false,
    /** A model the current settings need is not downloaded (nor being checked). */
    val modelsMissing: Boolean = false,
    /** Files still to describe. */
    val undescribed: Int = 0,
    val failures: Int = 0,
    /** A describe run is going: files done of total (0 of 0 while it starts). */
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** The pass a run is in: "captions" (all files first), then "text"; "embeddings" before and after. */
    val phase: String = "captions",
    /** Files done, and in all, in that pass (passes go at very different speeds). */
    val phaseDone: Int = 0,
    val phaseTotal: Int = 0,
    /** A model download is going: bytes done of total. */
    val downloading: Boolean = false,
    val downloadDone: Long = 0,
    val downloadTotal: Long = 0,
    val downloadError: String? = null,
    /** Whether anything describes by itself ("Describe new photos as they arrive", or "the rest when the app opens"). */
    val automatic: Boolean = true,
    /** What automatic describing does on battery, for the dialog to say why files wait. */
    val onBattery: OnBattery = OnBattery.NEW_ONLY,
)

/**
 * Describing one file afresh: [going] while it waits or goes; [problem], why the last time left it
 * as it was, which holds while its description is still the one written at [describedAt] (0: none).
 */
data class Redescribe(val going: Boolean = false, val problem: String? = null, val describedAt: Long = 0) {
    /** [problem], unless the file was described since: [now] is its description's describedAt (0: none). */
    fun problemWith(now: Long): String? = problem?.takeIf { now == describedAt }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GalleryApp
    private val workManager = WorkManager.getInstance(application)

    val settings: StateFlow<Settings> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val allMedia: StateFlow<List<MediaEntity>> =
        app.repository.media.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The tabs shown, in order. */
    val tabs: StateFlow<List<Category>> = settings.map { s ->
        Category.entries.filter {
            when (it) {
                Category.OTHER -> s.showOtherTab
                Category.ALL -> s.showAllTab
                else -> true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Category.entries.filter { it != Category.ALL })

    private val _category = MutableStateFlow<Category?>(null)
    /** The tab shown: the one chosen, or the first if that one has been hidden. */
    val category: StateFlow<Category> = combine(_category, settings, tabs) { c, s, t ->
        (c ?: s.lastCategory).takeIf { it in t } ?: t.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Category.CAMERA)

    /** The current tab's files, newest first. */
    val visibleMedia: StateFlow<List<MediaEntity>> = combine(allMedia, category, settings) { all, c, s ->
        all.filter { Categories.shows(c, it.folderCategory, it.isVideo, s.videosInFolderTabs) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val query = MutableStateFlow("")

    /**
     * The query whose date phrase is searched for as words (the user dismissed the date read in
     * it), until the settled query changes.
     */
    private val dateDismissed = MutableStateFlow<String?>(null)

    /** The same for the place read in it. */
    private val placeDismissed = MutableStateFlow<String?>(null)

    /** The query once it has settled for a moment: what is read and searched. */
    private val settledQuery: StateFlow<String> = query.debounce(250).stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Today, moved on at midnight and when the app comes back (a sleeping phone's timers run
     * late), so relative dates in an open search are read again. A query is read on the clock's
     * day itself; this only says when to read it again.
     */
    private val today = MutableStateFlow(LocalDate.now())

    /** What a query is read with: whether its date and its place were dismissed, the day, the places known. */
    private data class ReadingInput(
        val q: String,
        val dateOff: Boolean,
        val placeOff: Boolean,
        val day: LocalDate,
        val known: Map<String, PlacePhrases.Known>,
    )

    /** The settled query as read: its date and place unless dismissed, and its request ("show me videos of"). */
    val reading: StateFlow<SearchReading> =
        combine(settledQuery, dateDismissed, placeDismissed, today, app.places.snapshot.map { it.names }) { q, dd, pd, day, known ->
            ReadingInput(q, q.isNotBlank() && q == dd, q.isNotBlank() && q == pd, day, known)
        }
            .distinctUntilChanged()
            .map { r ->
                val dates = if (r.dateOff) null else DatePhrases.forLocale(LocalDate.now(), Locale.getDefault())
                SearchReading.read(r.q, dates, r.known, r.placeOff)
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SearchReading.EMPTY)

    /**
     * Searches for the words of the date phrase shown instead (the × on its chip); not when the
     * chip shown is of a query typed over since (its own will show).
     */
    fun dismissDate() {
        val shown = reading.value.text
        if (shown == settledQuery.value) dateDismissed.value = shown
    }

    /** The same for the place shown. */
    fun dismissPlace() {
        val shown = reading.value.text
        if (shown == settledQuery.value) placeDismissed.value = shown
    }

    /** The tab a search is kept to: the one shown, with "Only in this tab" on, unless it is All. */
    val searchScope: StateFlow<Category?> = combine(settings, category) { s, c ->
        c.takeIf { s.searchCurrentTabOnly && it != Category.ALL }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // A dismissal lasts until the settled query changes.
        viewModelScope.launch {
            settledQuery.collect { q ->
                if (q != dateDismissed.value) dateDismissed.value = null
                if (q != placeDismissed.value) placeDismissed.value = null
            }
        }
        viewModelScope.launch {
            while (true) {
                val midnight = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                delay(maxOf(1_000L, midnight - System.currentTimeMillis()))
                today.value = LocalDate.now()
            }
        }
    }

    /** The date read in the query, for the user to check and dismiss; null when none is. */
    val searchDate: StateFlow<DatePhrases.DateQuery?> = reading.map { it.date }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Bumps when files or descriptions change, so open results are recomputed. */
    private val indexVersion = MutableStateFlow(0)

    /**
     * Results for [query] (null when not searching), best first. A change to the files (added,
     * removed, renamed, moved) or to where they were taken invalidates the index here, before
     * the search it reruns.
     *
     * A date in the query narrows the search to the files taken then, a place to those taken
     * there, "videos" to videos, "Only in this tab" to the tab; a place named in passing ("beach
     * hawaii") ranks those taken there higher. With no words left to search for ("last summer",
     * "in paris", "show me videos"), every file allowed is listed, newest first, which needs no
     * descriptions.
     */
    val searchResults: StateFlow<List<SearchHit>?> =
        combine(
            reading, settings, searchScope, allMedia.onEach { app.search.invalidate() },
            combine(indexVersion, app.places.snapshot) { _, places -> places },
        ) { q, s, tab, all, places ->
            SearchInput(q, s, tab, all, places)
        }.mapLatest { input ->
            val q = input.query
            if (q.text.isBlank()) return@mapLatest null
            val s = input.settings
            val zone = ZoneId.systemDefault()
            val dates = q.date?.filter
            val there = q.place?.let { input.places.keys[it.key].orEmpty() }
            val tab = input.tab
            // The files the search may find, worked out once: the search asks per candidate,
            // holding its index's lock. It also keeps out files the index has and this list has
            // not yet (or no longer).
            val allowed = input.all.filter { item ->
                (tab == null || Categories.shows(tab, item.folderCategory, item.isVideo, s.videosInFolderTabs)) &&
                    (!q.videosOnly || item.isVideo) &&
                    (there == null || item.aiKey in there) &&
                    (dates == null || dates.matches(Instant.ofEpochMilli(item.sortDate).atZone(zone).toLocalDate()))
            }
            if (q.words.isBlank()) return@mapLatest allowed.map { SearchHit(it, null) }
            val byId = allowed.associateBy { it.id }
            val fields = HybridSearch.FIELDS.filter { it in s.searchFields }
            val named = q.named.flatMapTo(HashSet()) { input.places.keys[it].orEmpty() }
            app.search.search(q.words, fields, accept = { id -> id in byId }, favoured = { id: Long -> byId[id]?.aiKey in named }.takeIf { named.isNotEmpty() })
                .mapNotNull { r -> byId[r.mediaId]?.let { SearchHit(it, r) } }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class SearchInput(
        val query: SearchReading,
        val settings: Settings,
        val tab: Category?,
        val all: List<MediaEntity>,
        val places: Places.Snapshot,
    )

    val aiStatus: StateFlow<AiStatus> = combine(
        settings.flatMapLatest { s -> app.db.ai().observeUndescribedCount(s.describeVideos, s.captions, s.ocr) },
        app.db.ai().observeFailureCount(),
        workManager.getWorkInfosForUniqueWorkFlow(Work.DESCRIBE),
        workManager.getWorkInfosForUniqueWorkFlow(Work.DOWNLOAD),
        combine(app.models.state, settings) { states, s -> s to states },
    ) { undescribed, failures, describe, download, (s, states) ->
        val run = describe.firstOrNull { it.state == WorkInfo.State.RUNNING }
        val dl = download.firstOrNull { !it.state.isFinished }
        val dlFailed = download.firstOrNull { it.state == WorkInfo.State.FAILED }
        AiStatus(
            ready = app.ai.ready(s),
            notAllowed = !app.ai.allowed(s),
            featuresOff = !s.captions && !s.ocr,
            modelsMissing = ModelManager.needed(s.captions, s.ocr).any {
                states[it] != ModelState.Ready && states[it] != ModelState.Verifying
            },
            undescribed = undescribed,
            failures = failures,
            running = run != null,
            done = run?.progress?.getInt(Work.KEY_DONE, 0) ?: 0,
            total = run?.progress?.getInt(Work.KEY_TOTAL, 0) ?: 0,
            phase = run?.progress?.getString(Work.KEY_PHASE) ?: "captions",
            phaseDone = run?.progress?.getInt(Work.KEY_PHASE_DONE, 0) ?: 0,
            phaseTotal = run?.progress?.getInt(Work.KEY_PHASE_TOTAL, 0) ?: 0,
            downloading = dl != null,
            downloadDone = dl?.progress?.getLong(Work.KEY_DONE, 0) ?: 0,
            downloadTotal = dl?.progress?.getLong(Work.KEY_TOTAL, 0) ?: 0,
            downloadError = if (dl == null) dlFailed?.outputData?.getString(Work.KEY_ERROR) else null,
            automatic = s.describesByItself,
            onBattery = s.onBattery,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiStatus())

    val modelStates: StateFlow<Map<String, ModelState>> = app.models.state

    private val _passSecondsLeft = MutableStateFlow<Long?>(null)

    /**
     * Seconds left in the describing pass under way, from how fast its files went in the last
     * few minutes; null while none is running, or until a few are done.
     */
    val passSecondsLeft: StateFlow<Long?> = _passSecondsLeft

    init {
        viewModelScope.launch {
            // (time, files done) in the pass under way, the last few minutes of them.
            val samples = ArrayDeque<Pair<Long, Int>>()
            var pass: String? = null
            aiStatus.collect { a ->
                val restarted = samples.isNotEmpty() && a.phaseDone < samples.last().second
                if (!a.running || a.phase != pass || restarted) {
                    samples.clear()
                    pass = a.phase.takeIf { a.running }
                }
                if (!a.running || a.phaseTotal == 0) {
                    _passSecondsLeft.value = null
                    return@collect
                }
                val now = SystemClock.elapsedRealtime()
                if (samples.isEmpty() || samples.last().second != a.phaseDone) samples.addLast(now to a.phaseDone)
                while (samples.size > 2 && now - samples.first().first > ETA_WINDOW_MS) samples.removeFirst()
                val (since, doneThen) = samples.first()
                val doneSince = a.phaseDone - doneThen
                _passSecondsLeft.value = if (doneSince < 3) null
                else (a.phaseTotal - a.phaseDone).toLong() * (now - since) / doneSince / 1000
            }
        }
    }

    /** This phone's memory, and whether the AI has what it needs. */
    val memory: Hardware.Memory = Hardware.memory(app)

    /**
     * Whether to tell the user, once, that this phone has too little memory for the AI: from the
     * settings as stored, so not before they are read (their defaults would say it was not told).
     */
    val weakPhoneNotice: StateFlow<Boolean> = app.settings.settings
        .map { s -> !memory.enough && !s.aiOnWeakPhone && !s.weakPhoneNoticeShown }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Where the GPU stands for reading text (Settings shows it). */
    val gpu: StateFlow<AiEngine.Gpu> = app.ai.gpu

    /** Looks for the GPU, off the main thread: it loads the GPU's libraries, so only Settings asks. */
    fun lookForGpu() {
        viewModelScope.launch(Dispatchers.Default) { app.ai.lookForGpu() }
    }

    /** Displayed aspect ratios learned from thumbnails, by media id (see [recordAspect]). */
    val aspectFixes: StateFlow<Map<Long, Pair<Long, Float>>> = app.db.media().observeAspects()
        .map { list -> list.associate { it.id to (it.dateModified to it.aspect) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * A thumbnail decoded at [aspect] (width / height) for [item]: when that clearly differs from
     * the shape MediaStore's size gives (a video with non-square pixels), remember it so the
     * layout gives the item the right shape.
     */
    fun recordAspect(item: MediaEntity, aspect: Float) {
        val current = aspectFixes.value[item.id]?.takeIf { it.first == item.dateModified }?.second ?: item.aspect
        if (kotlin.math.abs(aspect - current) / current < 0.04f) return
        viewModelScope.launch {
            app.db.media().upsertAspect(com.lithic.gallery.data.DisplayAspectEntity(item.id, item.dateModified, aspect))
        }
    }

    /** The list the viewer pages through, set when a thumbnail is opened. */
    var viewerList: List<MediaEntity> = emptyList()
        private set
    var viewerHits: Map<Long, SearchService.Result> = emptyMap()
        private set

    private var started = false

    /** Asks for the files' locations to be read (one reading at a time, at most one more waiting). */
    private val scans = Channel<Unit>(Channel.CONFLATED)

    /** Where the library's files were taken, as far as their locations are read. */
    val places: StateFlow<Places.Snapshot> = app.places.snapshot

    /** Whether the app may read where photos were taken. */
    fun locationsAllowed(): Boolean = MediaLocations.allowed(app)

    /** The settings as stored ([settings] starts out with the defaults until they are read). */
    suspend fun storedSettings(): Settings = app.settings.current()

    /** The permission to read locations was answered: read them, if it was given. */
    fun onLocationAnswer() {
        viewModelScope.launch { app.settings.update { it.copy(locationAsked = true) } }
        scans.trySend(Unit)
    }

    /** Called once access to the media has been granted. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            // The library first: checking copied models can take a while.
            app.repository.sync()
            scans.trySend(Unit)
            app.models.verifyCopied()
            val s = app.settings.current()
            Work.watchNewMedia(app, s.describeNewMedia)
            if (s.describeOnOpen && app.ai.ready(s)) Work.describe(app, userInitiated = false, s)
        }
        // MediaStore changes while the app is open: resync shortly after they settle.
        viewModelScope.launch {
            app.repository.changes().debounce(1000).collect {
                // One failure must not end the collecting: the library would stop following
                // MediaStore until the app is opened again.
                try {
                    val r = app.repository.sync()
                    scans.trySend(Unit)
                    // What arrived describes as it would with the app closed: this sync took it
                    // first, so the job MediaStore wakes (NewMediaWorker) finds nothing new.
                    val s = app.settings.current()
                    if ((r.added > 0 || r.changed > 0) && s.describeNewMedia && app.ai.ready(s)) {
                        Work.describe(app, userInitiated = false, s)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "following the library's changes", e)
                }
            }
        }
        // Where new files were taken, read beside the rest (a large library's first reading
        // takes a while).
        viewModelScope.launch(Dispatchers.IO) { for (unit in scans) app.places.scan() }
        // Descriptions changed: the next search updates the index first, and an open one is
        // rerun, at most every 5 s while photos are being described. (File changes rerun it
        // through searchResults.)
        viewModelScope.launch {
            app.db.ai().observeVersion()
                .onEach { app.search.invalidate() }
                .sample(5000)
                .collect { if (query.value.isNotBlank()) indexVersion.value++ }
        }
        // Keep the media trigger in step with its setting.
        viewModelScope.launch {
            settings.map { it.describeNewMedia }.distinctUntilChanged().collect { Work.watchNewMedia(app, it) }
        }
    }

    /**
     * The app came to the foreground: notice models copied in meanwhile, and describe what is
     * waiting, as on opening.
     */
    fun onResume() {
        today.value = LocalDate.now()
        if (!started) return
        // The permission may have been given in the system's settings meanwhile.
        scans.trySend(Unit)
        viewModelScope.launch {
            app.models.verifyCopied()
            val s = app.settings.current()
            if (s.describeOnOpen && app.ai.ready(s)) Work.describe(app, userInitiated = false, s)
        }
    }

    fun selectCategory(c: Category) {
        _category.value = c
        viewModelScope.launch { app.settings.update { it.copy(lastCategory = c) } }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        viewModelScope.launch { app.settings.update(change) }
    }

    /**
     * Sets "On battery"; what is queued or going is planned again by it. In the process's scope,
     * not the screen's: a change cut short, or one waiting behind another dropped, would leave the
     * runs planned for a rule not chosen last.
     */
    fun setOnBattery(rule: OnBattery) {
        ProcessLifecycleOwner.get().lifecycleScope.launch {
            onBatteryChanges.withLock {
                if (app.settings.current().onBattery == rule) return@withLock
                app.settings.update { it.copy(onBattery = rule) }
                val s = app.settings.current()
                Work.replan(app, rule, automatic = s.describesByItself && app.ai.ready(s))
            }
        }
    }

    fun openViewer(list: List<MediaEntity>, hits: Map<Long, SearchService.Result> = emptyMap()) {
        viewerList = list
        viewerHits = hits
    }

    fun describeNow() {
        viewModelScope.launch { Work.describe(app, userInitiated = true, settings.value) }
    }

    fun stopDescribing() = Work.stopDescribing(app)

    fun downloadModels(wifiOnly: Boolean) {
        val s = settings.value
        Work.downloadModels(app, ModelManager.needed(s.captions, s.ocr), wifiOnly)
    }

    fun cancelDownload() = Work.cancelDownload(app)

    fun deleteModel(key: String) {
        viewModelScope.launch {
            app.models.delete(key)
            // Search stops using it at once (it checks the model's state); this frees what it had open.
            if (key == ModelManager.MINILM) app.search.releaseEmbedder()
        }
    }

    fun modelSizes(): Map<String, Long> = app.models.sources.mapValues { it.value.size }

    fun neededModels(): List<String> = settings.value.let { ModelManager.needed(it.captions, it.ocr) }

    /** Describes [item] afresh, at once, beside any run going; its description stays until the new one is saved. */
    fun redescribe(item: MediaEntity) = Work.describeFile(app, item)

    /**
     * Where describing [item] afresh ([redescribe]) stands: whether it waits or goes, and why the
     * last time left it as it was.
     */
    fun redescribing(item: MediaEntity): Flow<Redescribe> =
        workManager.getWorkInfosForUniqueWorkFlow(Work.fileWork(item.id)).map { infos ->
            val going = infos.any { !it.state.isFinished }
            val done = infos.firstOrNull { it.state.isFinished }?.outputData
            Redescribe(going, if (going) null else done?.getString(Work.KEY_ERROR), done?.getLong(Work.KEY_DESCRIBED_AT, 0L) ?: 0L)
        }

    /** Retries the files describing failed on. */
    fun retryFailures() {
        viewModelScope.launch {
            app.db.ai().clearFailures()
            Work.describe(app, userInitiated = true, settings.value)
        }
    }

    fun description(item: MediaEntity) = app.db.ai().observeDescription(item.aiKey)

    suspend fun resync() = app.repository.sync()

    fun storageUsedByModels(): Long = app.models.bytesOnDisk()

    private companion object {
        const val TAG = "GalleryViewModel"

        /** One change of "On battery" at a time in the process, so the runs are planned by the last one chosen. */
        val onBatteryChanges = Mutex()
        /** How far back the pass's speed is taken from: describing slows as the phone warms. */
        const val ETA_WINDOW_MS = 5 * 60_000L
    }
}
