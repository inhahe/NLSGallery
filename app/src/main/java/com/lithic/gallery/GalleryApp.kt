package com.lithic.gallery

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import com.lithic.gallery.ai.AiEngine
import com.lithic.gallery.ai.ModelManager
import com.lithic.gallery.data.GalleryDatabase
import com.lithic.gallery.data.MediaRepository
import com.lithic.gallery.data.SettingsStore
import com.lithic.gallery.search.SearchService
import com.lithic.gallery.ui.MediaThumbFetcher
import com.lithic.gallery.ui.MediaThumbKeyer
import com.lithic.gallery.work.Work
import kotlinx.coroutines.launch

/** The app's single instances, shared by the UI and the background workers in one process. */
class GalleryApp : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(MediaThumbFetcher.Factory(contentResolver))
            add(MediaThumbKeyer())
        }
        // Thumbnails come from MediaStore's own disk cache; keep plenty in memory for scrolling.
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
        .build()

    val db: GalleryDatabase by lazy { GalleryDatabase.open(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val repository: MediaRepository by lazy { MediaRepository(db, contentResolver) }
    val models: ModelManager by lazy { ModelManager(this) }
    val ai: AiEngine by lazy { AiEngine(this, models, db.ai()) }
    val search: SearchService by lazy { SearchService(db.ai(), models) { ai.tokenizer } }

    override fun onCreate() {
        super.onCreate()
        Work.createChannels(this)
        // Out of sight (not merely rotated), the query embedder's memory goes back; the next
        // search opens it again.
        val process = ProcessLifecycleOwner.get()
        process.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                process.lifecycleScope.launch { search.releaseEmbedder() }
            }
        })
    }
}
