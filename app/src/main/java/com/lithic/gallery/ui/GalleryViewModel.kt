package com.lithic.gallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lithic.gallery.GalleryApp
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.ai.ModelState
import com.lithic.gallery.data.Categories
import com.lithic.gallery.data.Category
import com.lithic.gallery.data.MediaEntity
import com.lithic.gallery.data.Settings
import com.lithic.gallery.search.SearchService
import com.lithic.gallery.work.Work
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A search result shown in the grid. */
data class SearchHit(val item: MediaEntity, val result: SearchService.Result)

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
    /** The pass a run is in: "captions" (all files first), then "text". */
    val phase: String = "captions",
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

    /** Bumps when files or descriptions change, so open results are recomputed. */
    private val indexVersion = MutableStateFlow(0)

    /** Results for [query] (null when not searching), best first. */
    val searchResults: StateFlow<List<SearchHit>?> =
        combine(query.debounce(250), settings, category, allMedia, indexVersion) { q, s, c, all, _ ->
            SearchInput(q, s, c, all)
        }.mapLatest { input ->
            if (input.query.isBlank()) return@mapLatest null
            val byId = input.all.associateBy { it.id }
            val fields = com.lithic.gallery.core.search.HybridSearch.FIELDS.filter { it in input.settings.searchFields }
            app.search.search(input.query, fields).mapNotNull { r ->
                val item = byId[r.mediaId] ?: return@mapNotNull null
                if (input.settings.searchCurrentTabOnly &&
                    !Categories.shows(input.category, item.folderCategory, item.isVideo, input.settings.videosInFolderTabs)
                ) return@mapNotNull null
                SearchHit(item, r)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class SearchInput(val query: String, val settings: Settings, val category: Category, val all: List<MediaEntity>)

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
            downloading = dl != null,
            downloadDone = dl?.progress?.getLong(Work.KEY_DONE, 0) ?: 0,
            downloadTotal = dl?.progress?.getLong(Work.KEY_TOTAL, 0) ?: 0,
            downloadError = if (dl == null) dlFailed?.outputData?.getString(Work.KEY_ERROR) else null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiStatus())

    val modelStates: StateFlow<Map<String, ModelState>> = app.models.state

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
            app.models.verifyCopied()
            app.repository.sync()
            val s = app.settings.current()
            Work.watchNewMedia(app, s.describeNewMedia)
            if (s.describeOnOpen && app.ai.ready(s)) Work.describe(app, userInitiated = false, s)
        }
        // MediaStore changes while the app is open: resync shortly after they settle.
        viewModelScope.launch {
            app.repository.changes().debounce(1000).collect { app.repository.sync() }
        }
        // Files or descriptions changed: the next search updates the index first, and an open one
        // is rerun, at most every 5 s while photos are being described.
        viewModelScope.launch {
            merge(allMedia.map { it.size.toLong() }, app.db.ai().observeVersion())
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
        app.models.delete(key)
        // Search stops using it at once (it checks the files); this frees what it had open.
        if (key == ModelManager.MINILM) viewModelScope.launch { app.search.releaseEmbedder() }
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

}
