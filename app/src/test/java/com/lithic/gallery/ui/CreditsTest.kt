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

    private val libs: Libs by lazy {
        val json = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().toString(Charsets.UTF_8) }
        Libs.Builder().withJson(json).build()
    }

    @Test
    fun everyLicenceShownHasItsCopyrightLine() {
        val shown = creditsList(libs).libraries
        val byId = shown.associateBy { it.uniqueId }
        // app/aboutlibraries' entries and the MIT and BSD libraries, with their real notices.
        for ((id, holder) in listOf(
            "org.geonames:places" to "GeoNames",
            "com.github.dralshehri:hijridate" to "Mohammed Alshehri",
            "Salesforce/blip-image-captioning-base" to "Salesforce.com",
            "clovaai/CRAFT-pytorch" to "NAVER Corp.",
            "lucaong/minisearch" to "Luca Ongaro",
            "python-pillow/Pillow" to "Secret Labs AB",
            "pytorch/pytorch" to "Facebook, Inc",
            "com.microsoft.onnxruntime:onnxruntime-android" to "Microsoft Corporation",
            "com.qualcomm.qti:onnxruntime-android-qnn" to "Qualcomm Technologies",
            "androidx.datastore:datastore-preferences-external-protobuf" to "Google Inc.",
        )) {
            val library = byId[id] ?: throw AssertionError("$id is not in the list")
            assertWithMessage(id).that(library.licenses.joinToString { it.licenseContent.orEmpty() }).contains(holder)
        }
        // No licence shown is a template waiting for its holder (or has no text): a new MIT or BSD
        // library needs its own licence in app/aboutlibraries/licenses.
        for (library in shown) {
            assertWithMessage("${library.uniqueId}'s licence").that(library.licenses).isNotEmpty()
            for (licence in library.licenses) {
                assertWithMessage("${library.uniqueId}: ${licence.name}").that(isRealText(licence.licenseContent)).isTrue()
            }
        }
        // ONNX Runtime shows its licence alone, not beside the MIT template its POM names.
        assertThat(byId.getValue("com.microsoft.onnxruntime:onnxruntime-android").licenses.map { it.hash }).containsExactly("onnxruntime-MIT")
        // The licence of Qualcomm's runtime says what it allows, in the app.
        assertThat(byId.getValue("com.qualcomm.qti:qnn-runtime").licenses.single().licenseContent).contains("object code")
    }

    @Test
    fun theDataModelsAndPortsComeFirstAndNoBoms() {
        val ordered = creditsList(libs).libraries
        val ours = ordered.takeWhile { it.tag != null }
        // The data and models together (by name), then the ported code.
        val group = ours.map { if (it.tag == "port") 1 else 0 }
        assertThat(group).isInOrder()
        assertThat(ours.map { it.tag }.toSet()).containsExactly("data", "model", "port")
        assertThat(ours.map { it.uniqueId }).containsAtLeast("org.geonames:places", "Salesforce/blip-image-captioning-base",
            "opencv/opencv", "huggingface/tokenizers")
        assertThat(ordered.drop(ours.size).none { it.tag != null }).isTrue()
        assertThat(ordered.map { it.uniqueId }.filter { it.endsWith("-bom") }).isEmpty()
        // The libraries the app is built on are there.
        assertThat(ordered.map { it.uniqueId }).containsAtLeast("com.microsoft.onnxruntime:onnxruntime-android", "com.qualcomm.qti:qnn-runtime")
    }

    @Test
    fun theNativeLibrariesNoticesAreThere() {
        val files = context.assets.list("licences").orEmpty().toList()
        // Each file is one of the three, by name (its title without the version).
        assertThat(files.mapNotNull { noticeTitle(it)?.substringBefore(':')?.substringBeforeLast(' ') }).containsExactly(
            "ONNX Runtime", "ONNX Runtime's QNN execution provider", "Qualcomm QNN runtime",
        )
        for (f in files) {
            val size = context.assets.open("licences/$f").use { it.readBytes().size }
            assertWithMessage(f).that(size).isGreaterThan(10_000)
        }
        assertThat(noticeTitle("onnxruntime-qnn-2.7.0-ThirdPartyNotices.txt")).isEqualTo("ONNX Runtime's QNN execution provider 2.7.0: notices")
        assertThat(noticeTitle("something-else.txt")).isNull()
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
        // Paragraphs written one to a line (Qualcomm's notice opens so) keep their lines.
        val unwrapped = "This Notice.txt file contains certain notices Qualcomm Technologies, Inc. (\"QTI\") is required to " +
            "provide with certain software components.\n" +
            "Copyright (c) 2015-2019 Qualcomm Technologies, Inc. All rights reserved.\n" +
            "Qualcomm is a trademark of Qualcomm Incorporated, registered in the United States and other countries."
        assertThat(reflow(unwrapped)).isEqualTo(unwrapped)
    }
}
