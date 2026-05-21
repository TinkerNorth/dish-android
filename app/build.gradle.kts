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
data class ResolvedVersion(val code: Int, val name: String)

fun resolveVersion(): ResolvedVersion {
    System.getenv("DISH_VERSION_CODE")?.toIntOrNull()?.let { code ->
        System.getenv("DISH_VERSION_NAME")?.takeIf { it.isNotBlank() }?.let { name ->
            return ResolvedVersion(code, name)
        }
    }
    runCatching {
        val proc = ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--abbrev=0")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || proc.exitValue() != 0 || out.isEmpty()) {
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tinkernorth.dish"
        minSdk = 24
        targetSdk = 36
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
    if (firebaseEnabled) {
        implementation(platform(libs.firebase.bom))
        implementation(libs.firebase.crashlytics)
        // Analytics is optional but Crashlytics gains free trend data from it.
        // If you want pure crash reporting with zero analytics, remove this
        // line and add a Data Safety note to PRIVACY.md.
        implementation(libs.firebase.analytics)
    }
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
