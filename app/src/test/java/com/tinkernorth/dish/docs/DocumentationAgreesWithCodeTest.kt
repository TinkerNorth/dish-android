// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.docs

import com.tinkernorth.dish.core.net.DISH_PROTOCOL_CURRENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// A document that repeats a fact the code owns goes stale the day the code moves. Each fact a
// document repeats is read back here and compared with its owner: the protocol number, the
// third-party versions the CMake files pin, the navigation destinations, and the README's
// release, links and the blocks it shares word for word with the other five READMEs.
class DocumentationAgreesWithCodeTest {
    private val repo: File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .first { File(it, "THIRD_PARTY.md").exists() }

    private fun text(path: String): String = File(repo, path).readText()

    private val mainCmake = text("app/src/main/cpp/CMakeLists.txt")
    private val testCmake = text("app/src/test/cpp/CMakeLists.txt")

    @Test
    fun `the contract notes name the protocol the client speaks`() {
        val claimed = protocolClaimedBy(text("docs/contract.md"))

        assertEquals(DISH_PROTOCOL_CURRENT, claimed)
    }

    @Test
    fun `the protocol reader finds the number after implements protocol`() {
        assertEquals(4, protocolClaimedBy("This client implements protocol 4 against it."))
        assertEquals(3, protocolClaimedBy("This client\nimplements protocol 3 against it."))
        assertEquals(null, protocolClaimedBy("This client implements a protocol."))
    }

    @Test
    fun `THIRD_PARTY names the libsodium version both CMake files fetch`() {
        val named = Regex("""libsodium\*\* \(`([0-9.]+)`""").find(text("THIRD_PARTY.md"))?.groupValues?.get(1)

        assertEquals(named, pinnedVersion(mainCmake, "libsodium-"))
        assertEquals(named, pinnedVersion(testCmake, "libsodium-"))
    }

    @Test
    fun `THIRD_PARTY names the libsodium version the hand written header declares`() {
        val named = Regex("""libsodium\*\* \(`([0-9.]+)`""").find(text("THIRD_PARTY.md"))?.groupValues?.get(1)

        assertEquals(named, versionStringOf(mainCmake))
        assertEquals(named, versionStringOf(testCmake))
    }

    @Test
    fun `THIRD_PARTY names the libopus version both CMake files fetch`() {
        val named = Regex("""libopus\*\* \(`opus-([0-9.]+)`""").find(text("THIRD_PARTY.md"))?.groupValues?.get(1)

        assertEquals(named, pinnedVersion(mainCmake, "opus-"))
        assertEquals(named, pinnedVersion(testCmake, "opus-"))
    }

    @Test
    fun `THIRD_PARTY names the googletest version the host CMake fetches`() {
        val named = Regex("""googletest\*\* \(`([0-9.]+)`""").find(text("THIRD_PARTY.md"))?.groupValues?.get(1)
        val fetched = Regex("""refs/tags/v([0-9.]+)\.tar\.gz""").find(testCmake)?.groupValues?.get(1)

        assertEquals(named, fetched)
    }

    @Test
    fun `the two CMake files fetch libsodium and libopus by the same hash`() {
        assertEquals(hashOf(mainCmake, "libsodium-"), hashOf(testCmake, "libsodium-"))
        assertEquals(hashOf(mainCmake, "opus-"), hashOf(testCmake, "opus-"))
    }

    @Test
    fun `the design document lists exactly the destinations the navigation graph declares`() {
        val declared = destinationsOf(text("app/src/main/res/navigation/nav_graph.xml"))
        val documented = destinationsDocumentedIn(text("DESIGN.md"))

        assertTrue("the graph was read", declared.size >= FEWEST_DESTINATIONS)
        assertEquals(declared, documented)
    }

    @Test
    fun `the destination reader takes activity ids from the graph and table rows from the document`() {
        val graph =
            """
            <navigation android:id="@+id/nav_graph">
                <activity android:id="@+id/mainActivity" android:name="a.B" />
                <activity
                    android:id="@+id/helpActivity"
                    android:name="a.C" />
            </navigation>
            """.trimIndent()
        val table =
            """
            | Destination | Activity | Args |
            |---|---|---|
            | `mainActivity` (start) | `MainActivity` | none |
            | `helpActivity` | `HelpActivity` | none |
            """.trimIndent()

        assertEquals(setOf("mainActivity", "helpActivity"), destinationsOf(graph))
        assertEquals(setOf("mainActivity", "helpActivity"), destinationsDocumentedIn(table))
    }

    @Test
    fun `the README names the newest released version in the CHANGELOG`() {
        val released = newestReleasedIn(text("CHANGELOG.md"))

        assertTrue("the CHANGELOG has a released heading", released != null)
        assertEquals(released, currentReleaseNamedBy(text("README.md")))
    }

    @Test
    fun `every relative link in the README points at a file in the repository`() {
        val links = relativeLinksIn(text("README.md"))
        val missing = links.filterNot { File(repo, it).exists() }

        assertTrue("the README links to files", links.isNotEmpty())
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the README carries every block the family shares word for word`() {
        val blocks = sharedBlocksIn(text(SHARED_BLOCKS))
        val readme = text("README.md")

        assertTrue("the shared blocks were read", blocks.size >= FEWEST_SHARED_BLOCKS)
        assertEquals(emptyList<String>(), blocks.filterNot { it in readme })
    }

    @Test
    fun `the README readers find the version, the relative links and the shared blocks`() {
        val changelog = "## [Unreleased]\n\n## [2.7.0] - 2026-11-23\n\n## [2.6.1] - 2026-11-19\n"
        val readme =
            """
            Dish for Android 2.7.0 is the current release.
            [SECURITY.md](SECURITY.md), [site](https://dish.tinkernorth.com), [mail](mailto:a@b.c),
            [top](#download), [arch](docs/ARCHITECTURE.md#pipeline)
            <img src="play/a.png" width="260"> <img src="https://example.com/b.png">
            """.trimIndent()
        val fixture = "first\nblock\n\n<!-- shared block -->\n\nsecond block\n"

        assertEquals("2.7.0", newestReleasedIn(changelog))
        assertEquals("2.7.0", currentReleaseNamedBy(readme))
        assertEquals(listOf("SECURITY.md", "docs/ARCHITECTURE.md", "play/a.png"), relativeLinksIn(readme))
        assertEquals(listOf("first\nblock", "second block"), sharedBlocksIn(fixture))
    }

    private companion object {
        const val FEWEST_DESTINATIONS = 15
        const val FEWEST_SHARED_BLOCKS = 7
        const val SHARED_BLOCKS = "app/src/test/resources/readme/shared-blocks.md"

        fun newestReleasedIn(changelog: String): String? =
            Regex("""^## \[(\d+\.\d+\.\d+)]""", RegexOption.MULTILINE).find(changelog)?.groupValues?.get(1)

        fun currentReleaseNamedBy(readme: String): String? =
            Regex("""Dish for Android (\d+\.\d+\.\d+) is the current release""").find(readme)?.groupValues?.get(1)

        fun relativeLinksIn(readme: String): List<String> =
            Regex("""]\(([^)\s]+)\)|<img\s[^>]*?src="([^"]+)"""")
                .findAll(readme)
                .map { match -> match.groupValues.drop(1).first { it.isNotEmpty() } }
                .filterNot { it.startsWith("#") || Regex("""^[a-z]+:""").containsMatchIn(it) }
                .map { it.substringBefore('#') }
                .toList()

        fun sharedBlocksIn(fixture: String): List<String> =
            fixture
                .split("<!-- shared block -->")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

        fun protocolClaimedBy(document: String): Int? =
            Regex("""implements\s+protocol\s+(\d+)""")
                .find(document)
                ?.groupValues
                ?.get(1)
                ?.toInt()

        fun pinnedVersion(
            cmake: String,
            artifact: String,
        ): String? =
            Regex("""$artifact([0-9]+\.[0-9]+(?:\.[0-9]+)?)(?:-stable)?\.tar\.gz""")
                .find(cmake)
                ?.groupValues
                ?.get(1)

        fun versionStringOf(cmake: String): String? = Regex("""SODIUM_VERSION_STRING \\"([0-9.]+)\\"""").find(cmake)?.groupValues?.get(1)

        fun hashOf(
            cmake: String,
            artifact: String,
        ): String? =
            Regex("""$artifact[0-9.]+(?:-stable)?\.tar\.gz[\s\S]*?URL_HASH SHA256=([0-9a-f]{64})""")
                .find(cmake)
                ?.groupValues
                ?.get(1)

        fun destinationsOf(graph: String): Set<String> =
            Regex("""<activity\s[^>]*?android:id="@\+id/(\w+)"""")
                .findAll(graph)
                .map { it.groupValues[1] }
                .toSet()

        fun destinationsDocumentedIn(document: String): Set<String> {
            val table = document.substringAfter("| Destination | Activity | Args |")
            return table
                .lineSequence()
                .drop(2)
                .takeWhile { it.startsWith("|") }
                .mapNotNull { Regex("""^\|\s*`(\w+)`""").find(it)?.groupValues?.get(1) }
                .toSet()
        }
    }
}
