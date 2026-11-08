package com.lithic.gallery.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lithic.gallery.GalleryApp
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.ai.ModelState
import com.lithic.gallery.core.search.DatePhrases
import com.lithic.gallery.core.search.HybridSearch
import com.lithic.gallery.core.search.QueryRequest
import com.lithic.gallery.data.Categories
import com.lithic.gallery.data.Category
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import com.lithic.gallery.search.SearchService
import com.lithic.gallery.work.Work
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A search result shown in the grid: how it was found, or no [result] when it was listed for its date alone. */
data class SearchHit(val item: MediaEntity, val result: SearchService.Result?)

/**
 * A search query as read: [text] as typed, the [date] read in it, the [words] to search for (the
 * query itself unless a date or a request was taken out; empty when nothing is left, and every
 * file allowed is listed), [videosOnly] when it asks for videos.
 */
data class SearchReading(val text: String, val date: DatePhrases.DateQuery?, val words: String, val videosOnly: Boolean)

/** What the AI button shows. */
data class AiStatus(
    /** Describing can run: captions or text reading is on, and the models for them are here. */
    val ready: Boolean = false,
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
)

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

    /** The query once it has settled for a moment: what is read and searched. */
    private val settledQuery: StateFlow<String> = query.debounce(250).stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Today, moved on at midnight and when the app comes back (a sleeping phone's timers run
     * late), so relative dates in an open search are read again. A query is read on the clock's
     * day itself; this only says when to read it again.
     */
    private val today = MutableStateFlow(LocalDate.now())

    /** The settled query as read: its date unless dismissed, and its request ("show me videos of"). */
    val reading: StateFlow<SearchReading> =
        combine(settledQuery, dateDismissed, today) { q, dismissed, day -> Triple(q, q.isNotBlank() && q == dismissed, day) }
            .distinctUntilChanged()
            .map { (q, dismissed, _) -> read(q, dismissed) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SearchReading("", null, "", false))

    /** What [q] asks for: the date read in it (none when [dismissed]), the words to search for, the kind. */
    private fun read(q: String, dismissed: Boolean): SearchReading {
        if (q.isBlank()) return SearchReading(q, null, "", false)
        val date = if (dismissed) null else DatePhrases.forLocale(LocalDate.now(), Locale.getDefault()).parse(q)
        if (date != null) return SearchReading(q, date, date.text, date.videosOnly)
        val stripped = QueryRequest.strip(q)
        return SearchReading(q, null, stripped.text, stripped.videosOnly)
    }

    /**
     * Searches for the words of the date phrase shown instead (the × on its chip); not when the
     * chip shown is of a query typed over since (its own will show).
     */
    fun dismissDate() {
        val shown = reading.value.text
        if (shown == settledQuery.value) dateDismissed.value = shown
    }

    /** The tab a search is kept to: the one shown, with "Only in this tab" on, unless it is All. */
    val searchScope: StateFlow<Category?> = combine(settings, category) { s, c ->
        c.takeIf { s.searchCurrentTabOnly && it != Category.ALL }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // A dismissal lasts until the settled query changes.
        viewModelScope.launch { settledQuery.collect { q -> if (q != dateDismissed.value) dateDismissed.value = null } }
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
     * removed, renamed, moved) invalidates the index here, before the search it reruns.
     *
     * A date in the query narrows the search to the files taken then, "videos" to videos, "Only
     * in this tab" to the tab. With no words left to search for ("last summer", "show me
     * videos"), every file allowed is listed, newest first, which needs no descriptions.
     */
    val searchResults: StateFlow<List<SearchHit>?> =
        combine(reading, settings, searchScope, allMedia.onEach { app.search.invalidate() }, indexVersion) { q, s, tab, all, _ ->
            SearchInput(q, s, tab, all)
        }.mapLatest { input ->
            val q = input.query
            if (q.text.isBlank()) return@mapLatest null
            val s = input.settings
            val zone = ZoneId.systemDefault()
            val dates = q.date?.filter
            val tab = input.tab
            // The files the search may find, worked out once: the search asks per candidate,
            // holding its index's lock. It also keeps out files the index has and this list has
            // not yet (or no longer).
            val allowed = input.all.filter { item ->
                (tab == null || Categories.shows(tab, item.folderCategory, item.isVideo, s.videosInFolderTabs)) &&
                    (!q.videosOnly || item.isVideo) &&
                    (dates == null || dates.matches(Instant.ofEpochMilli(item.sortDate).atZone(zone).toLocalDate()))
            }
            if (q.words.isBlank()) return@mapLatest allowed.map { SearchHit(it, null) }
            val byId = allowed.associateBy { it.id }
            val fields = HybridSearch.FIELDS.filter { it in s.searchFields }
            app.search.search(q.words, fields) { id -> id in byId }
                .mapNotNull { r -> byId[r.mediaId]?.let { SearchHit(it, r) } }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class SearchInput(val query: SearchReading, val settings: Settings, val tab: Category?, val all: List<MediaEntity>)

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

    /** Called once access to the media has been granted. */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            // The library first: checking copied models can take a while.
            app.repository.sync()
            app.models.verifyCopied()
            val s = app.settings.current()
            Work.watchNewMedia(app, s.describeNewMedia)
            if (s.describeOnOpen && app.ai.ready(s)) Work.describe(app, userInitiated = false, s)
        }
        // MediaStore changes while the app is open: resync shortly after they settle.
        viewModelScope.launch {
            app.repository.changes().debounce(1000).collect { app.repository.sync() }
        }
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

    /** Forgets [item]'s description so it is described again on the next run, and starts one. */
    fun redescribe(item: MediaEntity) {
        viewModelScope.launch {
            app.db.ai().forget(item.aiKey)
            Work.describe(app, userInitiated = true, settings.value)
        }
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
        /** How far back the pass's speed is taken from: describing slows as the phone warms. */
        const val ETA_WINDOW_MS = 5 * 60_000L
    }
}
