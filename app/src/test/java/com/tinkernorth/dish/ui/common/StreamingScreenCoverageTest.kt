// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import com.tinkernorth.dish.ui.main.BaseInputOverlayActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// NativeUnavailableActivity is exempt (no native library to stream through);
// MainActivity wires GamepadActivityHost itself on top of GameActivity.
class StreamingScreenCoverageTest {
    private val mainDir: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
            .first { File(it, "AndroidManifest.xml").exists() }

    private val exemptActivities =
        setOf(
            "com.tinkernorth.dish.ui.main.NativeUnavailableActivity",
            "com.tinkernorth.dish.ui.main.MainActivity",
        )

    private val exemptLayouts = setOf("activity_native_unavailable.xml")

    @Test
    fun `every manifest activity hosts gamepad streaming`() {
        val missing =
            manifestActivities()
                .filterNot { it in exemptActivities }
                .filterNot { hostsGamepad(Class.forName(it, false, javaClass.classLoader)) }
        assertTrue("screens without a gamepad host: $missing", missing.isEmpty())
    }

    @Test
    fun `main activity forwards gamepad events itself`() {
        val main = Class.forName("com.tinkernorth.dish.ui.main.MainActivity", false, javaClass.classLoader)
        val declared = main.declaredMethods.map { it.name }
        assertTrue("dispatchKeyEvent" in declared)
        assertTrue("dispatchGenericMotionEvent" in declared)
        assertTrue("onWindowFocusChanged" in declared)
    }

    // Scaffolded screens inherit the overlays from screen_scaffold; a bespoke screen keeps
    // its own CoordinatorLayout root and must carry the includes itself.
    @Test
    fun `every full-screen activity layout includes the low power overlays`() {
        val missing =
            File(mainDir, "res")
                .listFiles { dir -> dir.name.startsWith("layout") }
                .orEmpty()
                .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("activity_") }.orEmpty().toList() }
                .filterNot { it.name in exemptLayouts }
                .filter { it.readText().contains("CoordinatorLayout") }
                .filterNot { hasOverlayIncludes(it) }
                .map { "${it.parentFile.name}/${it.name}" }
        assertTrue("full-screen activity layouts without the low-power overlays: $missing", missing.isEmpty())
    }

    @Test
    fun `the screen scaffold carries the low power overlays`() {
        assertTrue(hasOverlayIncludes(File(mainDir, "res/layout/screen_scaffold.xml")))
    }

    private fun hasOverlayIncludes(file: File): Boolean {
        val xml = file.readText()
        return xml.contains("@layout/overlay_low_power") && xml.contains("@layout/overlay_low_power_chip")
    }

    private fun manifestActivities(): List<String> =
        Regex("""<activity[^>]*android:name="([^"]+)"""")
            .findAll(File(mainDir, "AndroidManifest.xml").readText())
            .map { m -> m.groupValues[1].let { if (it.startsWith(".")) "com.tinkernorth.dish$it" else it } }
            .toList()

    private fun hostsGamepad(cls: Class<*>): Boolean =
        BaseGamepadHostActivity::class.java.isAssignableFrom(cls) ||
            BaseInputOverlayActivity::class.java.isAssignableFrom(cls)
}
