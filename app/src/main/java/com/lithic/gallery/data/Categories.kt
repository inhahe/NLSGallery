package com.lithic.gallery.data

/**
 * Where a file lives, which decides its tab. VIDEO is not a folder: the Video tab is every video,
 * wherever it is.
 */
enum class Folder { CAMERA, SCREENSHOTS, MESSAGES, DOWNLOADS, OTHER }

/** The tabs across the top. */
enum class Category { CAMERA, VIDEO, SCREENSHOTS, MESSAGES, DOWNLOADS, OTHER, ALL }

object Categories {

    /**
     * The folder a file belongs to, from its MediaStore relative path ("DCIM/Camera/") and name.
     * First match wins: screenshots, then messaging apps, then downloads, then the camera.
     */
    fun folderOf(relativePath: String, displayName: String): Folder {
        val path = relativePath.lowercase().trimEnd('/')
        val segments = path.split('/').filter { it.isNotEmpty() }
        val name = displayName.lowercase()
        if (segments.any { it in SCREENSHOT_FOLDERS } || SCREENSHOT_NAME.containsMatchIn(name)) {
            return Folder.SCREENSHOTS
        }
        if (segments.any { seg -> MESSAGING.any { seg == it || seg.startsWith("$it ") } } ||
            MESSAGING_PACKAGES.any { "/$it" in "/$path" }
        ) {
            return Folder.MESSAGES
        }
        if (segments.firstOrNull() in DOWNLOAD_FOLDERS) return Folder.DOWNLOADS
        if (segments.firstOrNull() == "dcim") {
            val sub = segments.getOrNull(1)
            if (sub == null || sub in CAMERA_FOLDERS || sub.matches(DCF_FOLDER)) return Folder.CAMERA
        }
        if (segments.firstOrNull() == "pictures" && segments.getOrNull(1) in CAMERA_FOLDERS) return Folder.CAMERA
        return Folder.OTHER
    }

    /** Whether an item shows in [category]. */
    fun shows(category: Category, folder: Folder, isVideo: Boolean, videosInFolderTabs: Boolean): Boolean =
        when (category) {
            Category.ALL -> true
            Category.VIDEO -> isVideo
            else -> (videosInFolderTabs || !isVideo) && folder.name == category.name
        }

    private val SCREENSHOT_FOLDERS = setOf("screenshots", "screenshot", "screen recordings", "screenrecorder", "screen recorder")
    private val SCREENSHOT_NAME = Regex("^(screenshot|screen_recording|screenrecord)[_ -]")

    /** Folder names messaging apps save into ("WhatsApp Images" starts with "whatsapp "). */
    private val MESSAGING = setOf(
        "whatsapp", "telegram", "messages", "messenger", "signal", "viber", "wechat", "weixin",
        "line", "kakaotalk", "discord", "skype", "slack", "teams", "threema", "wire", "element",
        "imo", "snapchat", "mms",
    )
    /** App packages whose Android/media folders hold received media. */
    private val MESSAGING_PACKAGES = listOf(
        "android/media/com.whatsapp", "android/media/org.telegram", "android/media/org.thoughtcrime.securesms",
        "android/media/com.google.android.apps.messaging", "android/media/com.samsung.android.messaging",
        "android/media/com.facebook.orca", "android/media/com.viber.voip", "android/media/jp.naver.line.android",
        "android/media/com.discord", "android/media/com.tencent.mm",
    )
    private val DOWNLOAD_FOLDERS = setOf("download", "downloads")
    private val CAMERA_FOLDERS = setOf("camera", "opencamera", "open camera", "100andro", "100media")
    /** DCF directory names cameras use: DCIM/100CANON, DCIM/101MSDCF, … */
    private val DCF_FOLDER = Regex("^[1-9][0-9]{2}[a-z0-9_]{5}$")
}
