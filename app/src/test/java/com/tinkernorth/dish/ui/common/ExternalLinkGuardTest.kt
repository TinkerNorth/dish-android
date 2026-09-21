// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExternalLinkGuardTest {
    private val mainDir: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
            .first { File(it, "AndroidManifest.xml").exists() }

    private val sourceRoot = File(mainDir, "java/com/tinkernorth/dish")

    private val helperFile = "ui/common/ExternalLinks.kt"

    private fun kotlinSources(): List<File> = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun relative(file: File): String = file.relativeTo(sourceRoot).path.replace(File.separatorChar, '/')

    @Test
    fun `only the shared helper builds a browser intent`() {
        val offenders =
            kotlinSources()
                .filter { it.readText().contains("ACTION_VIEW") }
                .map(::relative)
                .filterNot { it == helperFile }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `the shared helper survives a device without a browser`() {
        val helper = File(sourceRoot, helperFile).readText()
        assertTrue(helper.contains("runCatching { startActivity(intent) }"))
        assertTrue(helper.contains(".onFailure"))
    }

    @Test
    fun `the setup screen's GitHub link goes through the helper`() {
        val setup = File(sourceRoot, "ui/setup/SetupConnectionActivity.kt").readText()
        assertTrue(setup.contains("openExternalUrl(getString(R.string.url_github))"))
    }

    @Test
    fun `the base activity's opener is the helper`() {
        val base = File(sourceRoot, "ui/common/BaseGamepadHostActivity.kt").readText()
        assertTrue(base.contains("protected fun openExternalUrl(url: String) = openExternalLink(url, notifications)"))
    }

    @Test
    fun `the main screen's update notice opens through the helper`() {
        val main = File(sourceRoot, "ui/main/MainActivity.kt").readText()
        assertTrue(main.contains("attachUpdatePill(updateNotices) { openExternalLink(it, notifications) }"))
    }
}
