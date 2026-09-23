// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Resource invariants the CONTRIBUTING states as rules. They are tests rather than review notes
 * because a layout is the one place a magic number can land without a compiler or a linter
 * objecting.
 */
class LayoutResourceRulesTest {
    private val resRoots = listOf(File("src/main/res"), File("src/github/res"), File("src/play/res"))

    private fun layouts(): List<File> =
        resRoots
            .filter { it.isDirectory }
            .flatMap { root -> root.listFiles().orEmpty().filter { it.name.startsWith("layout") } }
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.extension == "xml" }

    // Every values folder, not just dimens.xml: elevations.xml declares dimens too.
    private fun declaredDimens(): Set<String> =
        resRoots
            .filter { it.isDirectory }
            .flatMap { root -> root.listFiles().orEmpty().filter { it.name.startsWith("values") } }
            .flatMap { it.listFiles().orEmpty().filter { f -> f.extension == "xml" } }
            .flatMap { DIMEN_DECL.findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()

    @Test
    fun `the layouts are found, so an empty sweep cannot pass by accident`() {
        assertTrue("no layout files were read", layouts().size > 20)
        assertTrue("no dimens were read", declaredDimens().size > 20)
    }

    @Test
    fun `no layout carries a raw size, because every dimension is a named dimen`() {
        val offenders =
            layouts().flatMap { file ->
                RAW_SIZE.findAll(file.readText()).map { "${file.name}: ${it.value}" }
            }
        assertEquals("a size in a layout must be a @dimen, not a literal", emptyList<String>(), offenders)
    }

    @Test
    fun `every dimen a layout references is declared`() {
        val declared = declaredDimens()
        val missing =
            layouts().flatMap { file ->
                DIMEN_REF
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .filterNot { it in declared }
                    .map { "${file.name}: @dimen/$it" }
            }
        assertEquals(emptyList<String>(), missing.distinct())
    }

    private companion object {
        // 0dp is how a constrained or weighted child asks its parent for the size, not a magic
        // number, so it is the one literal allowed.
        val RAW_SIZE = Regex("""="(?:[1-9]\d*(?:\.\d+)?)(?:dp|sp)"""")
        val DIMEN_REF = Regex("""@dimen/([a-zA-Z0-9_]+)""")
        val DIMEN_DECL = Regex("""<dimen name="([a-zA-Z0-9_]+)"""")
    }
}
