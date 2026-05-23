import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Crashlytics is gated on the presence of app/google-services.json so that
// local development without a Firebase project still produces a runnable
// build. CI populates the file from the GOOGLE_SERVICES_JSON_BASE64 secret
// before invoking ./gradlew assembleRelease bundleRelease.
val googleServicesJson = file("google-services.json")
val firebaseEnabled = googleServicesJson.exists()
if (firebaseEnabled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

// versionCode / versionName resolution:
//   1. DISH_VERSION_CODE + DISH_VERSION_NAME env vars (set by release.yml
//      from the git tag).
//   2. `git describe --tags --match v*` if a tag exists locally.
//   3. Fallback to 1 / "1.0" for fresh clones with no tags.
//
// Keeps local debug builds usable while producing meaningful values for
// tagged releases. See HANDOFF.md item 4 for the versioning scheme.
data class ResolvedVersion(
    val code: Int,
    val name: String,
)

fun resolveVersion(): ResolvedVersion {
    System.getenv("DISH_VERSION_CODE")?.toIntOrNull()?.let { code ->
        System.getenv("DISH_VERSION_NAME")?.takeIf { it.isNotBlank() }?.let { name ->
            return ResolvedVersion(code, name)
        }
    }
    runCatching {
        val proc =
            ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
                .redirectErrorStream(true)
                .start()
        val out =
            proc.inputStream
                .bufferedReader()
                .readText()
                .trim()
        if (!proc.waitFor(2, TimeUnit.SECONDS) || proc.exitValue() != 0 || out.isEmpty()) {
            return@runCatching null
        }
        val match = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)").find(out) ?: return@runCatching null
        val (major, minor, patch) = match.destructured
        ResolvedVersion(
            code = major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt(),
            name = out.removePrefix("v"),
        )
    }.getOrNull()?.let { return it }
    return ResolvedVersion(1, "1.0")
}

val resolvedVersion = resolveVersion()

android {
    namespace = "com.tinkernorth.dish"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tinkernorth.dish"
        minSdk = 24
        targetSdk = 37
        versionCode = resolvedVersion.code
        versionName = resolvedVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Reads from environment variables set by the release CI workflow. When
    // any var is missing, the release build is unsigned (debuggable=false) so
    // local `./gradlew assembleRelease` still works without provisioning a
    // keystore — the resulting APK is just not installable on a device until
    // it's signed manually.
    signingConfigs {
        val keystoreFile = System.getenv("DISH_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("DISH_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("DISH_KEY_ALIAS")
                keyPassword = System.getenv("DISH_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Apply the release signing config only when it was registered above
            // (i.e. DISH_KEYSTORE_FILE was set in the environment).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        prefab = true
        // BuildConfig — needed by SettingsActivity to render the version row
        // ("1.0 · build 1" style) without going through PackageManager.
        // AGP 8 made this opt-in to shave a few generated classes off apps
        // that don't reference BuildConfig at all.
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // MissingTranslation is promoted from warning → error so CI's existing
    // ./gradlew lint step fails when a new string in values/ isn't translated
    // into every locale folder under locales_config (bs, de, es, fr, pt-rBR).
    // ExtraTranslation stays at warning — useful for cleanup but it doesn't
    // break the user-facing app if a stale translation lingers.
    // Pre-commit hook (scripts/check-translations.py) runs the same check
    // faster (no Gradle) when any values*/strings.xml is staged.
    lint {
        error += "MissingTranslation"
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.games.activity)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Firebase SDKs are unconditional: the CrashReportingController references
    // them directly. The classes are on the classpath even when
    // google-services.json is absent — `FirebaseApp.getApps(context)` then
    // returns empty and the controller no-ops. Only the `google-services` /
    // `firebase-crashlytics` Gradle PLUGINS are conditional (above), because
    // they need the JSON to generate resources and upload mapping files.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // Firebase Analytics is deliberately NOT pulled in. Adding it would (a)
    // auto-collect events like session_start / screen_view / first_open / app_remove,
    // (b) cause the manifest merger to inject
    // com.google.android.gms.permission.AD_ID into the production APK, and
    // (c) require a Firebase Analytics section in PRIVACY.md plus a Data
    // Safety form entry. The published policy promises a zero-analytics
    // posture; keep this dep out unless that posture changes.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

ktlint {
    android.set(true)
    verbose.set(true)
}

detekt {
    config.setFrom(files("detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

// ── Host-build native unit tests ────────────────────────────────────────────
// The pure gamepad-input layer (app/src/main/cpp/gamepad_input.{h,cpp}) is
// tested via googletest from app/src/test/cpp. We drive cmake/make/ctest
// directly rather than going through the AGP NDK build because the latter
// only knows how to cross-compile for Android targets.
val nativeTestBuildDir = layout.buildDirectory.dir("native-test")
val nativeTestSrcDir = layout.projectDirectory.dir("src/test/cpp")

val nativeTestConfigure =
    tasks.register<Exec>("nativeTestConfigure") {
        group = "verification"
        description = "Configure the host CMake build for the native unit tests."
        inputs.file(nativeTestSrcDir.file("CMakeLists.txt"))
        inputs.file(nativeTestSrcDir.file("gamepad_input_test.cpp"))
        inputs.file(nativeTestSrcDir.file("wire_encoders_test.cpp"))
        inputs.file(layout.projectDirectory.file("src/main/cpp/gamepad_input.h"))
        inputs.file(layout.projectDirectory.file("src/main/cpp/gamepad_input.cpp"))
        inputs.file(layout.projectDirectory.file("src/main/cpp/wire_encoders.h"))
        outputs.dir(nativeTestBuildDir)
        commandLine(
            "cmake",
            "-S",
            nativeTestSrcDir.asFile.absolutePath,
            "-B",
            nativeTestBuildDir.get().asFile.absolutePath,
            "-G",
            "Unix Makefiles",
        )
    }

val nativeTestBuild =
    tasks.register<Exec>("nativeTestBuild") {
        group = "verification"
        description = "Compile the native unit-test executable."
        dependsOn(nativeTestConfigure)
        commandLine(
            "cmake",
            "--build",
            nativeTestBuildDir.get().asFile.absolutePath,
            "--parallel",
        )
    }

tasks.register<Exec>("nativeTest") {
    group = "verification"
    description = "Run host unit tests for the pure gamepad-input layer."
    dependsOn(nativeTestBuild)
    workingDir = nativeTestBuildDir.get().asFile
    commandLine("ctest", "--output-on-failure")
}

tasks.named("check") {
    dependsOn("nativeTest")
}

// ── JVM heap for unit-test workers ─────────────────────────────────────────
// The default Gradle test worker runs at -Xmx512m, which is too tight for
// the heavier MockK-based suites (SatelliteConnectionTest in particular
// allocates enough reflection-cached metadata to OOM the worker partway
// through the full suite, even though every test passes individually).
// Bumping to 2g covers comfortably; matches the daemon heap from
// gradle.properties so we don't accidentally starve the daemon when both
// run in the same machine session.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
