// Plain Kotlin (no Android): the AI pipeline (tokenizers, image operations, BLIP, MiniLM, EasyOCR),
// the search engine and the thumbnail layout. Kept free of Android so its tests run on the desktop
// JVM against the same ONNX graphs and weights as the phone, and against thumbsup2's own output.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 17 bytecode, like :app's, from whatever JDK runs Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The ONNX Runtime Java API. The app supplies the Android build of the same release at run
    // time; tests run on the desktop build.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(libs.onnxruntime.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.serialization.json)
}

val toolsDir = rootProject.layout.projectDirectory.dir("tools")
val modelGraphs = rootProject.layout.projectDirectory.dir("app/src/main/assets/models")

tasks.test {
    // Reference outputs (tools/reference), test images (tools/testdata, from
    // tools/fetch_test_images.py) and the official weight files (tools/weights, from
    // tools/fetch_weights.py). Tests needing files that are absent are skipped, saying which.
    systemProperty("gallery.tools", toolsDir.asFile.absolutePath)
    systemProperty("gallery.graphs", modelGraphs.asFile.absolutePath)
    // Read by path, so declared: a changed weight, image, reference, shipped graph or gazetteer
    // runs the tests again instead of taking their last result (up to date, or from the build cache).
    inputs.files(
        toolsDir.dir("reference"), toolsDir.dir("testdata"), toolsDir.dir("weights"), modelGraphs,
        rootProject.layout.projectDirectory.file("app/src/main/assets/places.bin"),
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("testFiles")
    maxHeapSize = "4g"
    testLogging {
        events("failed", "skipped")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
