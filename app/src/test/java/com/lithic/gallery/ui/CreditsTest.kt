package com.lithic.gallery.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.lithic.gallery.R
import com.mikepenz.aboutlibraries.Libs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Credits and licences: what the generated list holds, and the notices read as the screen shows them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CreditsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theListHasTheDataAndModelsAndEveryLibraryALicence() {
        val json = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().toString(Charsets.UTF_8) }
        val libs = Libs.Builder().withJson(json).build()
        val byId = libs.libraries.associateBy { it.uniqueId }
        // app/aboutlibraries' entries, each with its licence's text (the copyright lines).
        for ((id, holder) in listOf(
            "org.geonames:places" to "GeoNames",
            "com.github.dralshehri:hijridate" to "Mohammed Alshehri",
            "Salesforce/blip-image-captioning-base" to "Salesforce.com",
            "clovaai/CRAFT-pytorch" to "NAVER Corp.",
        )) {
            val library = byId[id] ?: throw AssertionError("$id is not in the list")
            assertWithMessage(id).that(library.licenses.joinToString { it.licenseContent.orEmpty() }).contains(holder)
        }
        for (id in listOf("JaidedAI/EasyOCR", "sentence-transformers/all-MiniLM-L6-v2", "google-research/bert:vocab")) {
            assertWithMessage(id).that(byId).containsKey(id)
        }
        // The libraries the app is built on are there, and none without a licence.
        assertThat(byId).containsKey("com.microsoft.onnxruntime:onnxruntime-android")
        assertThat(byId).containsKey("com.qualcomm.qti:qnn-runtime")
        assertThat(libs.libraries.filter { it.licenses.isEmpty() }.map { it.uniqueId }).isEmpty()
    }

    @Test
    fun theNativeLibrariesNoticesAreThere() {
        val files = context.assets.list("licences").orEmpty().toList()
        assertThat(files.map { it.substringBefore('-') }).containsExactly("onnxruntime", "qnn")
        for (f in files) {
            val size = context.assets.open("licences/$f").use { it.readBytes().size }
            assertWithMessage(f).that(size).isGreaterThan(10_000)
        }
    }

    @Test
    fun noticesAreReflowedToTheScreen() {
        // A paragraph wrapped at 80 columns runs on; an address and list items keep their lines.
        val wrapped = "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files (the \"Software\"), to deal\n" +
            "in the Software without restriction."
        assertThat(reflow(wrapped)).isEqualTo(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and " +
                "associated documentation files (the \"Software\"), to deal in the Software without restriction."
        )
        assertThat(reflow("Microsoft Corporation\nOne Microsoft Way\nRedmond, WA 98052")).isEqualTo(
            "Microsoft Corporation\nOne Microsoft Way\nRedmond, WA 98052"
        )
        // A rule is not text wrapped: the heading after it keeps its line.
        val rule = "=".repeat(78)
        assertThat(reflow("$rule\n---- Caffe SSD License ----")).isEqualTo("$rule\n---- Caffe SSD License ----")
        val list = "Redistribution and use in source and binary forms, with or without modification, are\n" +
            "permitted provided that:\n" +
            "* Redistributions of source code must retain the above copyright notice, this list\n" +
            "  of conditions and the following disclaimer.\n" +
            "* Neither the name nor the names of its contributors may be used."
        assertThat(reflow(list)).isEqualTo(
            "Redistribution and use in source and binary forms, with or without modification, are permitted provided that:\n" +
                "* Redistributions of source code must retain the above copyright notice, this list of conditions and the " +
                "following disclaimer.\n" +
                "* Neither the name nor the names of its contributors may be used."
        )
    }
}
