plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependency.check) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

apply(plugin = "org.owasp.dependencycheck")

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "SARIF", "JSON")
    suppressionFile = "${rootDir}/.security/dependency-check-suppressions.xml"
    analyzers.run {
        assemblyEnabled = false
        nuspecEnabled = false
        nugetconfEnabled = false
    }
    nvd.run {
        apiKey = System.getenv("NVD_API_KEY") ?: ""
    }
}
