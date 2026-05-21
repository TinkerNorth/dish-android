plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.tinkernorth.dish"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tinkernorth.dish"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
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
