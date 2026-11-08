package com.lithic.gallery.ai

import android.app.ActivityManager
import android.content.Context

/**
 * Whether this phone has what the AI needs. Describing holds about 2 GB at once (BLIP's two
 * graphs, or the text detector's working memory and the reader), beside whatever else the phone
 * runs; with less than 6 GB, Android kills the app (or the apps around it) while it describes.
 * The app ships for 64-bit processors only, so that need is met wherever it installs.
 */
object Hardware {
    /** The memory the AI needs, as phones are sold. */
    const val NEEDED_GB = 6

    /**
     * This phone's memory as Android reports it ([bytes]: less than it was sold with, the kernel
     * and the GPU keeping some; 6 GB phones report about 5.3-5.7 GiB, 4 GB ones about 3.6), and
     * whether it is enough.
     */
    class Memory(val bytes: Long) {
        /** It was sold with [NEEDED_GB] or more: what the notice says, and what the AI is allowed on. */
        val enough: Boolean get() = soldAsGb >= NEEDED_GB

        /** The memory as phones are sold: the smallest usual size at least what is reported ("4 GB"). */
        val soldAsGb: Int get() = SOLD_SIZES.firstOrNull { (it.toLong() shl 30) >= bytes } ?: ((bytes shr 30) + 1).toInt()
    }

    private val SOLD_SIZES = listOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32)

    @Volatile private var memory: Memory? = null

    fun memory(context: Context): Memory = memory ?: run {
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        Memory(mi.totalMem)
    }.also { memory = it }
}
