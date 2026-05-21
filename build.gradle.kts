// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    // OWASP Dependency-Check: scans the resolved Gradle classpath against
    // the NVD + GitHub advisory database. PR-time CI runs
    // `./gradlew dependencyCheckAnalyze` and fails on CVSS >= 7.0.
    alias(libs.plugins.dependency.check) apply false
    // Firebase plugins — declared at the root so :app can apply them
    // conditionally (only when google-services.json is present). The
    // plugins themselves are NOT applied at the root; :app does that.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Apply dependency-check at the root so `./gradlew dependencyCheckAggregate`
// covers every subproject (currently `app/`, may grow).
apply(plugin = "org.owasp.dependencycheck")

// Tighten the default cutoff: any CVSS >= 7.0 fails the build. The CI
// workflow explicitly invokes the aggregating task so transitive deps in
// :app are caught at the root project's report.
configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "SARIF", "JSON")
    // Allow the suppression file to live alongside the YAML allowlist so
    // there is one source of truth for "we accept this finding".
    suppressionFile = "${rootDir}/.security/dependency-check-suppressions.xml"
    analyzers.run {
        // The Android plugin pulls in many transitive deps; restrict the
        // scan to the JVM-side that actually ships in the APK.
        assemblyEnabled = false
        nuspecEnabled = false
        nugetconfEnabled = false
    }
    nvd.run {
        // The NVD API key is optional but doubles throughput for self-
        // hosted runners. CI passes it via the NVD_API_KEY env var.
        apiKey = System.getenv("NVD_API_KEY") ?: ""
    }
}
