plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.tinkernorth.dish.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // :app carries a `distribution` flavor dimension (github/play) that this
        // module has no opinion about; without a strategy the dependency on :app
        // can't resolve. The profile is generated against the Play build, and the
        // two flavors differ only in the donation UI, so it applies to both.
        missingDimensionStrategy("distribution", "play")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Same bar as :app: a Kotlin warning here fails the build too.
            allWarningsAsErrors.set(true)
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

// The same style and static-analysis gates :app runs, so this module cannot drift.
ktlint {
    android.set(true)
    verbose.set(true)
}

detekt {
    config.setFrom(files("../app/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
}

baselineProfile {
    managedDevices += "pixel6Api34"
    useConnectedDevices = false
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.junit)
}
