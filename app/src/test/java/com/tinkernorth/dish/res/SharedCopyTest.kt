// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private val STORE_NAMES = Regex("""Play Store|Google Play|Appstore|App Store""", RegexOption.IGNORE_CASE)

/** src/main's copy ships in the GitHub, Play and Amazon builds alike, so it may name no store. */
class SharedCopyTest {
    private val stringFiles: List<File> =
        File("src/main/res")
            .listFiles { dir -> dir.name.startsWith("values") }
            .orEmpty()
            .flatMap { dir -> dir.listFiles { file -> file.name.startsWith("strings") }.orEmpty().toList() }

    @Test
    fun `every locale's shared strings are read, so an empty sweep cannot pass`() {
        assertTrue("string files read: ${stringFiles.size}", stringFiles.size >= SHIPPED_LOCALES)
    }

    @Test
    fun `no shared string names an app store`() {
        val offenders =
            stringFiles.flatMap { file ->
                file.readLines().filter { STORE_NAMES.containsMatchIn(it) }.map { "${file.parentFile?.name}: ${it.trim()}" }
            }
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        const val SHIPPED_LOCALES = 6
    }
}
