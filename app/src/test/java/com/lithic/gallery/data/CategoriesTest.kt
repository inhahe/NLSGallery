package com.lithic.gallery.data

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class CategoriesTest {
    private fun check(path: String, name: String, want: Folder) =
        assertWithMessage("$path$name").that(Categories.folderOf(path, name)).isEqualTo(want)

    @Test
    fun foldersSeenOnAPhone() {
        // MediaStore relative paths from a Samsung S22 and common apps.
        check("DCIM/Camera/", "20260101_120000.jpg", Folder.CAMERA)
        check("DCIM/Screenshots/", "Screenshot_20260101_120000_Chrome.jpg", Folder.SCREENSHOTS)
        check("Pictures/Screenshots/", "Screenshot_20260101-120000.png", Folder.SCREENSHOTS)
        check("DCIM/Screen recordings/", "Screen_Recording_20260101.mp4", Folder.SCREENSHOTS)
        check("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", "IMG-20260101-WA0001.jpg", Folder.MESSAGES)
        check("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Animated Gifs/", "VID-20260101-WA0002.mp4", Folder.MESSAGES)
        check("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "VID-20260101-WA0003.mp4", Folder.MESSAGES)
        check("Pictures/Messages/", "IMG_0001.jpg", Folder.MESSAGES)
        check("Pictures/Messenger/", "received_123.jpeg", Folder.MESSAGES)
        check("Pictures/Telegram/", "IMG_123.jpg", Folder.MESSAGES)
        check("Pictures/Signal/", "signal-2026.jpg", Folder.MESSAGES)
        check("Download/", "flyer.png", Folder.DOWNLOADS)
        check("Download/EasyJoin/", "photo.jpg", Folder.DOWNLOADS)
        check("Download/WhatsApp/", "saved.jpg", Folder.MESSAGES)
        check("DCIM/Facebook/", "FB_IMG_1.jpg", Folder.OTHER)
        check("Pictures/Facebook/", "FB_IMG_2.jpg", Folder.OTHER)
        check("Pictures/Instagram/", "IMG_3.jpg", Folder.OTHER)
        check("DCIM/100CANON/", "IMG_0001.JPG", Folder.CAMERA)
        check("DCIM/", "loose.jpg", Folder.CAMERA)
        check("Pictures/", "Screenshot_2026.png", Folder.SCREENSHOTS)
        check("Movies/", "clip.mp4", Folder.OTHER)
    }

    @Test
    fun tabsShowTheirFoldersAndVideoShowsEveryVideo() {
        val t = Categories::shows
        assertWithMessage("camera photo in Camera").that(t(Category.CAMERA, Folder.CAMERA, false, true)).isTrue()
        assertWithMessage("camera video in Camera").that(t(Category.CAMERA, Folder.CAMERA, true, true)).isTrue()
        assertWithMessage("camera video in Camera, folder tabs without videos").that(t(Category.CAMERA, Folder.CAMERA, true, false)).isFalse()
        assertWithMessage("messages video in Video").that(t(Category.VIDEO, Folder.MESSAGES, true, false)).isTrue()
        assertWithMessage("photo in Video").that(t(Category.VIDEO, Folder.CAMERA, false, true)).isFalse()
        assertWithMessage("download in Camera").that(t(Category.CAMERA, Folder.DOWNLOADS, false, true)).isFalse()
        assertWithMessage("anything in All").that(t(Category.ALL, Folder.OTHER, true, false)).isTrue()
    }
}
