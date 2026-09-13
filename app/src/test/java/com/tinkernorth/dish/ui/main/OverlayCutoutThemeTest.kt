// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverlayCutoutThemeTest {
    private val mainDir: File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main"), File(it, "app/src/main")) }
            .first { File(it, "AndroidManifest.xml").exists() }

    private val manifest = File(mainDir, "AndroidManifest.xml").readText()

    @Test
    fun `every input overlay activity uses the overlay theme`() {
        val wrongTheme =
            manifestActivities()
                .filter { (name, _) -> isInputOverlay(name) }
                .filterNot { (_, theme) -> theme == "@style/Theme.Dish.Overlay" }
                .map { (name, theme) -> "$name -> $theme" }
        assertTrue("input overlays not on Theme.Dish.Overlay: $wrongTheme", wrongTheme.isEmpty())
    }

    @Test
    fun `only input overlay activities use the overlay theme`() {
        val leaked =
            manifestActivities()
                .filter { (_, theme) -> theme == "@style/Theme.Dish.Overlay" }
                .filterNot { (name, _) -> isInputOverlay(name) }
                .map { (name, _) -> name }
        assertTrue("non-overlay screens on Theme.Dish.Overlay: $leaked", leaked.isEmpty())
    }

    @Test
    fun `overlay theme inherits the app theme on every api level`() {
        val parents =
            listOf("values", "values-v28", "values-v30")
                .map { qualifier -> qualifier to overlayStyle(qualifier) }
        parents.forEach { (qualifier, style) ->
            assertTrue("$qualifier is missing Theme.Dish.Overlay", style != null)
            assertTrue("$qualifier overlay theme must extend Theme.Dish", style!!.contains("parent=\"Theme.Dish\""))
        }
    }

    @Test
    fun `overlay theme leaves the cutout mode alone below api 28`() {
        assertEquals(null, cutoutMode("values"))
    }

    @Test
    fun `overlay theme requests short edges on api 28 and 29`() {
        assertEquals("shortEdges", cutoutMode("values-v28"))
    }

    @Test
    fun `overlay theme requests always on api 30 and up`() {
        assertEquals("always", cutoutMode("values-v30"))
    }

    @Test
    fun `overlays no longer set the cutout mode at runtime`() {
        val sources =
            File(mainDir, "java/com/tinkernorth/dish/ui/main")
                .listFiles { f -> f.name.endsWith("OverlayActivity.kt") }
                .orEmpty()
                .filter { it.readText().contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE") }
                .map { it.name }
        assertTrue("runtime cutout mode in: $sources", sources.isEmpty())
    }

    private fun isInputOverlay(name: String): Boolean =
        BaseInputOverlayActivity::class.java.isAssignableFrom(Class.forName(name, false, javaClass.classLoader))

    private fun manifestActivities(): List<Pair<String, String?>> =
        Regex("""<activity((?:(?!</activity>).)*?)/?>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { m ->
                val attrs = m.groupValues[1]
                val name =
                    Regex("""android:name="([^"]+)"""")
                        .find(attrs)!!
                        .groupValues[1]
                        .let { if (it.startsWith(".")) "com.tinkernorth.dish$it" else it }
                name to Regex("""android:theme="([^"]+)"""").find(attrs)?.groupValues?.get(1)
            }.toList()

    private fun overlayStyle(qualifier: String): String? =
        Regex("""<style name="Theme\.Dish\.Overlay"[^>]*(?:/>|>.*?</style>)""", RegexOption.DOT_MATCHES_ALL)
            .find(File(mainDir, "res/$qualifier/themes.xml").readText())
            ?.value

    private fun cutoutMode(qualifier: String): String? =
        Regex("""<item name="android:windowLayoutInDisplayCutoutMode">([^<]+)</item>""")
            .find(overlayStyle(qualifier).orEmpty())
            ?.groupValues
            ?.get(1)
}
