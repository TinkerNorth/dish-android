import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

// Firebase plugins only apply when google-services.json is present so local builds without Firebase still work.
val googleServicesJson = file("google-services.json")
val firebaseEnabled = googleServicesJson.exists()
if (firebaseEnabled) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

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
        // SettingsActivity reads BuildConfig.VERSION_* directly; AGP 8 makes this opt-in.
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

    lint {
        error += "MissingTranslation"
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.window)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.runtime.ktx)
    implementation(libs.androidx.games.activity)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // Firebase SDKs always on classpath; controller no-ops when google-services.json is absent.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    // Firebase Analytics is deliberately omitted: it would auto-inject AD_ID permission and break the zero-analytics privacy posture.
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

// Host-built native unit tests bypass AGP NDK because AGP only cross-compiles for Android targets.
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

// 512m default OOMs SatelliteConnectionTest reflection cache; matches daemon heap.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}
