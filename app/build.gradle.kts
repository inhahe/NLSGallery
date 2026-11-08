import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }

    // The exported Room schemas, which MigrationTest builds old databases from.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
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
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.room.testing)
}
