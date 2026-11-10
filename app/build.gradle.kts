import java.util.Properties
import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries.android)
}

// Release signing: a key kept outside the repository, named in keystore.properties (also outside
// version control). Without it, release builds are unsigned.
val keystoreProperties = rootProject.file("keystore.properties")

// The version: appVersion in gradle.properties, major.minor.patch; versionCode is derived from it.
val appVersion = providers.gradleProperty("appVersion").get()
val appVersionCode = appVersion.split('.').map { it.toIntOrNull() }.let { parts ->
    val (major, minor, patch) = parts.filterNotNull().takeIf { it.size == 3 && parts.size == 3 }
        ?: error("appVersion '$appVersion' in gradle.properties must be major.minor.patch")
    require(minor < 100 && patch < 100) { "appVersion '$appVersion': minor and patch must stay below 100" }
    major * 10000 + minor * 100 + patch
}
providers.gradleProperty("appVersionCode").orNull.let { written ->
    require(written?.toIntOrNull() == appVersionCode) {
        "appVersionCode in gradle.properties must be $appVersionCode for appVersion $appVersion, " +
            "and is ${written ?: "missing"}"
    }
}

android {
    namespace = "com.lithic.gallery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lithic.gallery"
        // 29: MediaStore's RELATIVE_PATH and ContentResolver.loadThumbnail.
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per processor type: ONNX Runtime's native library is ~35 MB for each. Phones need
    // arm64, the emulator x86_64; the 32-bit ones are left out (the models need 64-bit memory anyway).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    signingConfigs {
        if (keystoreProperties.exists()) {
            val p = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            vcsInfo.include = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The ONNX graphs are read with AssetManager; keep them uncompressed in the APK.
        noCompress += listOf("onnx")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
            // ModelManagerTest reads official weights by path: declared, so fetching or changing
            // them runs the tests again instead of taking their last result. Without them its
            // tests are skipped, or with -PrequireTestFiles (any value but false; release.bat) fail.
            it.inputs.files(rootProject.layout.projectDirectory.dir("tools/weights"))
                .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("officialWeights")
            it.systemProperty("gallery.requireTestFiles",
                providers.gradleProperty("requireTestFiles").map { p -> p != "false" }.getOrElse(false).toString())
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        // QNN's Hexagon NPU and DSP backends (about 200 MB): the GPU backend is the one used.
        jniLibs.excludes += setOf("**/libQnnHtp*.so", "**/libQnnDsp*.so")
    }

    // The exported Room schemas, which MigrationTest builds old databases from.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// MigrationTest's assets are the schemas KSP writes: merge them only once it has written them
// (else the first test APK after a schema change lacks the new one).
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn("kspDebugKotlin") }

// Credits and licences (Settings, About): every dependency's licence, gathered at build time, with
// the data and models the app uses (app/aboutlibraries). A dependency under a licence not allowed
// here fails the build, so it is looked at before it ships.
aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    license {
        strictMode = com.mikepenz.aboutlibraries.plugin.StrictMode.FAIL
        allowedLicenses.addAll(
            "Apache-2.0", "MIT", "BSD-3-Clause",
            // app/aboutlibraries/licenses: the data's, the models', the ported code's, and the MIT
            // and BSD libraries' with their copyright lines (CreditsTest checks none is a template).
            "GeoNames-CC-BY-4.0", "hijridate-MIT", "BLIP-BSD-3-Clause", "CRAFT-MIT", "MiniSearch-MIT",
            "Pillow-MIT-CMU", "PyTorch-BSD-3-Clause", "onnxruntime-MIT", "onnxruntime-qnn-MIT", "protobuf-BSD-3-Clause",
        )
        // Qualcomm's own licence (its AI Hub Model License: in object code inside an app only),
        // for its QNN runtime alone.
        allowedLicensesMap = mapOf(
            "9f2e721d858d18d2ad0a4af38ab2283e" to listOf("com.qualcomm.qti:qnn-runtime"),
            "Qualcomm-AI-Hub-Model-License" to listOf("com.qualcomm.qti:qnn-runtime"),
        )
    }
    library {
        // A library without a licence is not shipped unread.
        requireLicense = true
        // BOMs align versions and ship no code.
        exclusionPatterns.add(Pattern.compile(".*-bom"))
    }
}

// The native libraries' third-party notices, for the versions the app is built with
// (tools/fetch_notices.py writes them): checked before every build, since a version bump that
// forgot them would ship another version's notices.
val checkNotices = tasks.register("checkNotices") {
    val wanted = listOf(
        "onnxruntime-${libs.versions.onnxruntime.get()}-ThirdPartyNotices.txt",
        "onnxruntime-qnn-${libs.versions.qnn.ep.get()}-ThirdPartyNotices.txt",
        "qnn-runtime-${libs.versions.qnn.runtime.get()}-NOTICE.txt",
    )
    val dir = file("src/main/assets/licences")
    doLast {
        val missing = wanted.filter { !File(dir, it).isFile }
        val stale = dir.list().orEmpty().filter { it !in wanted }
        check(missing.isEmpty() && stale.isEmpty()) {
            "Third-party notices out of date (missing $missing, stale $stale): run python tools/fetch_notices.py"
        }
    }
}
tasks.named("preBuild") { dependsOn(checkNotices) }

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime.android)
    implementation(libs.qnn.ep)
    implementation(libs.qnn.runtime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.aboutlibraries.compose.m3)

    implementation(libs.coil.compose)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
}
