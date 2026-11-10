package com.lithic.gallery.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assume.assumeTrue
import java.io.File

/**
 * Where the tests find thumbsup2's reference output and the files it was made from. Paths come
 * from core/build.gradle.kts. A test whose inputs are absent is skipped, naming the script that
 * creates them, rather than failing: the weights are 1.2 GB and the test images are downloaded.
 * A release (release.bat) must not go out on skipped tests: there they fail.
 */
object TestFiles {
    val tools = File(System.getProperty("gallery.tools") ?: error("gallery.tools not set; run through Gradle"))
    val graphs = File(System.getProperty("gallery.graphs") ?: error("gallery.graphs not set"))
    val reference = File(tools, "reference")
    val testdata = File(tools, "testdata")
    val weights = File(tools, "weights")

    fun json(file: File): JsonElement = Json.parseToJsonElement(file.readText(Charsets.UTF_8))

    fun reference(name: String): JsonElement = json(File(reference, name))

    /** Whether a missing file fails the test instead of skipping it (release.bat asks this). */
    private val mustExist = System.getProperty("gallery.requireTestFiles") == "true"

    /** [file], or the test is skipped (failed, if [mustExist]) saying what makes it. */
    fun require(file: File, madeBy: String): File {
        val missing = "$file is missing: run $madeBy"
        if (mustExist) check(file.exists()) { missing } else assumeTrue(missing, file.exists())
        return file
    }

    fun testImage(name: String): File = require(File(testdata, name), "tools/fetch_test_images.py")

    fun op(name: String): File = require(File(testdata, "ops/$name"), "tools/make_image_reference.py")

    /** The weight directory of [model] (blip, minilm, craft, english_g2), or the test is skipped. */
    fun weightsDir(model: String): File {
        val dir = File(weights, model)
        require(dir, "tools/fetch_weights.py")
        return dir
    }

    /** bert-base-uncased's vocabulary, as the app ships it. */
    fun vocab(): List<String> = File(graphs, "vocab.txt").readLines(Charsets.UTF_8)
}
