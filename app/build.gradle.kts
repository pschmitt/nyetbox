plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.androidx.baselineprofile)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

val configuredVersionCode =
    providers
        .gradleProperty("versionCode")
        .orElse("22")
        .map { value ->
            value.toIntOrNull()?.takeIf { it > 0 }
                ?: error("versionCode must be a positive integer")
        }
        .get()
val configuredVersionName =
    providers.gradleProperty("versionName").orElse("1.5.2").get().also { name ->
        require(name.isNotBlank()) { "versionName must not be blank" }
    }

android {
    namespace = "dev.pschmitt.nyetbox"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    lint {
        // Keep the known legacy findings visible without blocking CI; new findings still fail
        // the gate. Remove entries as the underlying permission/API/accessibility issues are fixed.
        baseline = file("lint-baseline.xml")
    }

    defaultConfig {
        applicationId = "dev.pschmitt.nyetbox"
        minSdk = 26
        targetSdk = 36

        versionCode = configuredVersionCode
        versionName = configuredVersionName
        val gitRevision = System.getenv("GIT_REVISION") ?: "unknown"
        buildConfigField("String", "GIT_REVISION", "\"$gitRevision\"")
        val buildDate = System.getenv("BUILD_DATE") ?: "unknown"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only overridden in CI (see .github/workflows/release.yaml), which decodes a persistent
        // keystore from a secret and exports CI_KEYSTORE_PATH - local debug builds keep using the
        // regular auto-generated ~/.android/debug.keystore. Without this, every CI run signs with
        // a different ephemeral debug key, which breaks update checks for anyone installing
        // builds via Obtainium (signature mismatch on every release).
        named("debug") {
            System.getenv("CI_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CI_KEY_ALIAS")
                keyPassword = System.getenv("CI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        named("debug") { applicationIdSuffix = ".debug" }
        named("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Reuses the CI keystore override above so CI can also produce a signed, installable
            // release build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            // Detect app bundle and conditionally disable split abis - avoids the "Multiple
            // shrunk-resources files found in directory" error present since AGP 8.9.0.
            val isBuildingBundle =
                gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle

            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs/bundles.
        includeInApk = false
        includeInBundle = false
    }
}

// NBC-426: the androidx.baselineprofile plugin auto-creates a "benchmark" build type (release-
// derived, non-debuggable, profileable, signed with the same debug-keystore-or-CI-secret fallback
// as release above) that :baselineprofile's BaselineProfileGenerator drives. Manual generation
// only - a baseline profile is committed source, not something every release build should try to
// regenerate on a machine that may have no connected device at all.
baselineProfile { automaticGenerationDuringBuild = false }

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)

    // Installs the committed app/src/main/baselineProfiles/baseline-prof.txt on real devices at
    // install/first-launch time (NBC-426) - without this dependency, the committed profile is
    // inert on any install that doesn't go through Play's Cloud Profile delivery (i.e. every
    // Obtainium/GitHub Releases/F-Droid sideload this app ships to).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // Home-screen widget
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)

    // Compose / Material 3
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Camera / QR + barcode scanning
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    // NetBox API client
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization.converter)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)

    // Markdown rendering (NetBox "comments" fields support Markdown)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    // Image loading (device-type stock photos + image attachments)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Coil delegates AVIF decoding to the Android platform, which can ignore AVIF's auxiliary
    // alpha plane on some devices. libavif provides a consistent alpha-aware decoder instead.
    implementation(libs.libavif.android)

    // Offline cache
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted settings (base URL + API token)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.biometric)

    // Background sync
    implementation(libs.androidx.work)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.fastlane.screengrab)
}
