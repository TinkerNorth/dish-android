import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.androidx.baselineprofile)
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

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.viewpager2)
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
    "baselineProfile"(project(":baselineprofile"))
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

tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
}

val licensesOutputFile =
    layout.projectDirectory.file("src/main/assets/licenses/licenses.json")

val knownLicenseOverrides: Map<String, List<Map<String, String?>>> =
    mapOf(
        "com.google.guava:listenablefuture" to
            listOf(
                mapOf(
                    "name" to "Apache License 2.0",
                    "url" to "http://www.apache.org/licenses/LICENSE-2.0.txt",
                ),
            ),
    )

tasks.register("generateLicenseManifest") {
    group = "build"
    description =
        "Generate src/main/assets/licenses/licenses.json from the release runtime classpath POMs."

    outputs.file(licensesOutputFile)

    doLast {
        val moduleIds =
            configurations
                .getByName("releaseRuntimeClasspath")
                .incoming
                .resolutionResult
                .allComponents
                .mapNotNull { it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
                .distinctBy { "${it.group}:${it.module}:${it.version}" }
                .sortedBy { "${it.group}:${it.module}:${it.version}" }

        val pomDeps =
            moduleIds.map { id ->
                dependencies.create("${id.group}:${id.module}:${id.version}@pom")
            }
        val pomConfig =
            configurations.detachedConfiguration(*pomDeps.toTypedArray()).apply {
                isTransitive = false
            }

        val entries =
            pomConfig
                .resolve()
                .mapNotNull { parsePomFile(it) }
                .map { entry ->
                    val licenses = entry["licenses"] as? List<*>
                    if (licenses.isNullOrEmpty()) {
                        val key = "${entry["group"]}:${entry["artifact"]}"
                        knownLicenseOverrides[key]?.let { entry + ("licenses" to it) } ?: entry
                    } else {
                        entry
                    }
                }.sortedBy { "${it["group"]}:${it["artifact"]}" }

        val payload =
            mapOf(
                "generatedBy" to "generateLicenseManifest",
                "libraries" to entries,
            )

        val outFile = licensesOutputFile.asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(renderJson(payload))
    }
}

tasks.named("preBuild") { dependsOn("generateLicenseManifest") }

fun parsePomFile(pomFile: java.io.File): Map<String, Any?>? {
    return try {
        val factory =
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val doc = factory.newDocumentBuilder().parse(pomFile)
        val root: org.w3c.dom.Element = doc.documentElement

        fun directChild(
            parent: org.w3c.dom.Element,
            tag: String,
        ): org.w3c.dom.Element? {
            val children = parent.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is org.w3c.dom.Element && node.tagName == tag) return node
            }
            return null
        }

        val parent = directChild(root, "parent")
        val group =
            directChild(root, "groupId")?.textContent
                ?: parent?.let { directChild(it, "groupId") }?.textContent
        val artifact = directChild(root, "artifactId")?.textContent
        val version =
            directChild(root, "version")?.textContent
                ?: parent?.let { directChild(it, "version") }?.textContent
        val displayName =
            directChild(root, "name")?.textContent?.takeIf { it.isNotBlank() }
                ?: "$group:$artifact"
        val url = directChild(root, "url")?.textContent

        val licensesEl = directChild(root, "licenses")
        val licenses: List<Map<String, String?>> =
            if (licensesEl != null) {
                val list = mutableListOf<Map<String, String?>>()
                val kids = licensesEl.childNodes
                for (i in 0 until kids.length) {
                    val node = kids.item(i)
                    if (node is org.w3c.dom.Element && node.tagName == "license") {
                        list.add(
                            mapOf(
                                "name" to directChild(node, "name")?.textContent,
                                "url" to directChild(node, "url")?.textContent,
                            ),
                        )
                    }
                }
                list
            } else {
                emptyList()
            }

        mapOf(
            "group" to group,
            "artifact" to artifact,
            "version" to version,
            "name" to displayName,
            "url" to url,
            "licenses" to licenses,
        )
    } catch (e: Exception) {
        logger.warn("generateLicenseManifest: failed to parse ${pomFile.name}: ${e.message}")
        null
    }
}

fun renderJson(value: Any?): String = StringBuilder().also { renderJsonTo(it, value, 0) }.append('\n').toString()

fun renderJsonTo(
    sb: StringBuilder,
    value: Any?,
    indent: Int,
) {
    when (value) {
        null -> sb.append("null")
        is Boolean -> sb.append(value.toString())
        is Number -> sb.append(value.toString())
        is String -> appendJsonString(sb, value)
        is Map<*, *> -> {
            if (value.isEmpty()) {
                sb.append("{}")
                return
            }
            sb.append("{\n")
            val entries = value.entries.toList()
            for ((i, e) in entries.withIndex()) {
                repeat(indent + 1) { sb.append("  ") }
                appendJsonString(sb, e.key.toString())
                sb.append(": ")
                renderJsonTo(sb, e.value, indent + 1)
                if (i < entries.size - 1) sb.append(',')
                sb.append('\n')
            }
            repeat(indent) { sb.append("  ") }
            sb.append('}')
        }
        is Iterable<*> -> {
            val list = value.toList()
            if (list.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append("[\n")
            for ((i, item) in list.withIndex()) {
                repeat(indent + 1) { sb.append("  ") }
                renderJsonTo(sb, item, indent + 1)
                if (i < list.size - 1) sb.append(',')
                sb.append('\n')
            }
            repeat(indent) { sb.append("  ") }
            sb.append(']')
        }
        else -> appendJsonString(sb, value.toString())
    }
}

fun appendJsonString(
    sb: StringBuilder,
    s: String,
) {
    sb.append('"')
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    sb.append('"')
}
