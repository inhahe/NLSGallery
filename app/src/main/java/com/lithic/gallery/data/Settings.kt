package com.lithic.gallery.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lithic.gallery.core.ai.easyocr.EasyOcr
import com.lithic.gallery.core.search.HybridSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class LayoutMode { JUSTIFIED, GRID }

/**
 * How large an image EasyOCR's detector reads text at. thumbsup2 (EasyOCR's default) works at up
 * to 2560 pixels on the longer side; on a phone that takes about a minute for a 12-megapixel
 * photo, so camera photos default to 1280.
 */
enum class TextDetail {
    /** 2560 for everything, as thumbsup2. */
    FULL,
    /** 2560 for screenshots, downloads, messages and other pictures; 1280 for camera photos and videos. */
    BALANCED,
    /** 1280 for everything. */
    FAST;

    /** The detector's canvas for a file in [folder]. */
    fun canvasFor(folder: Folder, isVideo: Boolean): Int = when (this) {
        FULL -> EasyOcr.DEFAULT_CANVAS
        FAST -> REDUCED
        BALANCED -> if (isVideo || folder == Folder.CAMERA) REDUCED else EasyOcr.DEFAULT_CANVAS
    }

    companion object { const val REDUCED = 1280 }
}

/** Every user preference, with its default. */
data class Settings(
    // Thumbnails
    val layoutMode: LayoutMode = LayoutMode.JUSTIFIED,
    /** Justified rows aim for this height. */
    val rowHeightDp: Int = 130,
    /** Grid: cells per row. */
    val gridColumns: Int = 4,
    val spacingDp: Int = 2,

    // Tabs
    /** Camera, Messages, … also show the videos in their folders (Video shows all videos regardless). */
    val videosInFolderTabs: Boolean = true,
    val showOtherTab: Boolean = true,
    val showAllTab: Boolean = false,
    val lastCategory: Category = Category.CAMERA,

    // AI
    /** Describe photos as they are added to the phone, even when the app is closed. */
    val describeNewMedia: Boolean = true,
    /** Describe everything not yet described, in the background, whenever the app opens. */
    val describeOnOpen: Boolean = true,
    /** Run only while charging (applies to all automatic describing). */
    val onlyWhileCharging: Boolean = false,
    val captions: Boolean = true,
    val ocr: Boolean = true,
    val describeVideos: Boolean = true,
    val textDetail: TextDetail = TextDetail.BALANCED,
    /** ONNX Runtime threads, 0 for its own choice. */
    val aiThreads: Int = 0,

    // Search
    val searchFields: Set<String> = HybridSearch.FIELDS.toSet(),
    val searchCurrentTabOnly: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private val store: DataStore<Preferences> get() = context.dataStore

    val settings: Flow<Settings> = store.data.map { p -> read(p) }

    suspend fun current(): Settings = settings.first()

    suspend fun update(change: (Settings) -> Settings) {
        store.edit { p ->
            val s = change(read(p))
            p[LAYOUT] = s.layoutMode.name
            p[ROW_HEIGHT] = s.rowHeightDp
            p[COLUMNS] = s.gridColumns
            p[SPACING] = s.spacingDp
            p[VIDEOS_IN_FOLDERS] = s.videosInFolderTabs
            p[SHOW_OTHER] = s.showOtherTab
            p[SHOW_ALL] = s.showAllTab
            p[LAST_CATEGORY] = s.lastCategory.name
            p[DESCRIBE_NEW] = s.describeNewMedia
            p[DESCRIBE_ON_OPEN] = s.describeOnOpen
            p[ONLY_CHARGING] = s.onlyWhileCharging
            p[CAPTIONS] = s.captions
            p[OCR] = s.ocr
            p[DESCRIBE_VIDEOS] = s.describeVideos
            p[TEXT_DETAIL] = s.textDetail.name
            p[THREADS] = s.aiThreads
            p[SEARCH_FIELDS] = s.searchFields.joinToString(",")
            p[SEARCH_TAB_ONLY] = s.searchCurrentTabOnly
        }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            layoutMode = p[LAYOUT]?.let { runCatching { LayoutMode.valueOf(it) }.getOrNull() } ?: d.layoutMode,
            rowHeightDp = p[ROW_HEIGHT] ?: d.rowHeightDp,
            gridColumns = p[COLUMNS] ?: d.gridColumns,
            spacingDp = p[SPACING] ?: d.spacingDp,
            videosInFolderTabs = p[VIDEOS_IN_FOLDERS] ?: d.videosInFolderTabs,
            showOtherTab = p[SHOW_OTHER] ?: d.showOtherTab,
            showAllTab = p[SHOW_ALL] ?: d.showAllTab,
            lastCategory = p[LAST_CATEGORY]?.let { runCatching { Category.valueOf(it) }.getOrNull() } ?: d.lastCategory,
            describeNewMedia = p[DESCRIBE_NEW] ?: d.describeNewMedia,
            describeOnOpen = p[DESCRIBE_ON_OPEN] ?: d.describeOnOpen,
            onlyWhileCharging = p[ONLY_CHARGING] ?: d.onlyWhileCharging,
            captions = p[CAPTIONS] ?: d.captions,
            ocr = p[OCR] ?: d.ocr,
            describeVideos = p[DESCRIBE_VIDEOS] ?: d.describeVideos,
            textDetail = p[TEXT_DETAIL]?.let { runCatching { TextDetail.valueOf(it) }.getOrNull() } ?: d.textDetail,
            aiThreads = p[THREADS] ?: d.aiThreads,
            searchFields = p[SEARCH_FIELDS]?.split(',')?.filter { it in HybridSearch.FIELDS }?.toSet() ?: d.searchFields,
            searchCurrentTabOnly = p[SEARCH_TAB_ONLY] ?: d.searchCurrentTabOnly,
        )
    }

    private companion object {
        val Context.dataStore by preferencesDataStore("settings")
        val LAYOUT = stringPreferencesKey("layout")
        val ROW_HEIGHT = intPreferencesKey("row_height_dp")
        val COLUMNS = intPreferencesKey("grid_columns")
        val SPACING = intPreferencesKey("spacing_dp")
        val VIDEOS_IN_FOLDERS = booleanPreferencesKey("videos_in_folder_tabs")
        val SHOW_OTHER = booleanPreferencesKey("show_other_tab")
        val SHOW_ALL = booleanPreferencesKey("show_all_tab")
        val LAST_CATEGORY = stringPreferencesKey("last_category")
        val DESCRIBE_NEW = booleanPreferencesKey("describe_new_media")
        val DESCRIBE_ON_OPEN = booleanPreferencesKey("describe_on_open")
        val ONLY_CHARGING = booleanPreferencesKey("only_while_charging")
        val CAPTIONS = booleanPreferencesKey("captions")
        val OCR = booleanPreferencesKey("ocr")
        val DESCRIBE_VIDEOS = booleanPreferencesKey("describe_videos")
        val TEXT_DETAIL = stringPreferencesKey("text_detail")
        val THREADS = intPreferencesKey("ai_threads")
        val SEARCH_FIELDS = stringPreferencesKey("search_fields")
        val SEARCH_TAB_ONLY = booleanPreferencesKey("search_current_tab_only")
    }
}
