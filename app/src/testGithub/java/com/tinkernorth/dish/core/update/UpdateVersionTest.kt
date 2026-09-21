// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    private fun v(text: String): UpdateVersion = requireNotNull(UpdateVersion.parse(text)) { "expected $text to parse" }

    @Test
    fun `a strict triple parses`() {
        assertEquals(UpdateVersion(2, 0, 10), UpdateVersion.parse("2.0.10"))
        assertEquals(UpdateVersion(0, 0, 0), UpdateVersion.parse("0.0.0"))
        assertEquals("2.0.10", v("2.0.10").toString())
    }

    @Test
    fun `anything looser is rejected`() {
        val bad =
            listOf(
                "",
                "v2.0.0",
                "2.0",
                "2.0.0.1",
                "2.0.0-rc1",
                " 2.0.0",
                "2.0.0 ",
                "+2.0.0",
                "2.-1.0",
                "2.0.0-3-gabcdef",
                "99999999999.0.0",
                "2.0.a",
            )
        for (text in bad) assertNull(text, UpdateVersion.parse(text))
    }

    @Test
    fun `ordering is numeric per component`() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("2.0.1") > v("2.0.0"))
        assertEquals(0, v("1.2.3").compareTo(v("1.2.3")))
    }

    @Test
    fun `a build past its tag reports the tag`() {
        assertEquals(UpdateVersion(2, 0, 0), UpdateVersion.ofBuild("2.0.0"))
        assertEquals(UpdateVersion(2, 0, 0), UpdateVersion.ofBuild("2.0.0-3-g1a2b3c4"))
        assertEquals(UpdateVersion(2, 0, 0), UpdateVersion.ofBuild("2.0.0-3-g1a2b3c4-dirty"))
        assertEquals(UpdateVersion(2, 0, 0), UpdateVersion.ofBuild("2.0.0-dirty"))
        assertNull(UpdateVersion.ofBuild("dev"))
        assertNull(UpdateVersion.ofBuild("2.0.0-rc1"))
        assertNull(UpdateVersion.ofBuild("v2.0.0"))
    }

    @Test
    fun `isStrictlyNewer needs both sides to parse`() {
        assertTrue(isStrictlyNewer("2.0.1", "2.0.0"))
        assertFalse(isStrictlyNewer("2.0.0", "2.0.0"))
        assertFalse(isStrictlyNewer("1.9.9", "2.0.0"))
        assertFalse(isStrictlyNewer("2.0.0-rc1", "1.0.0"))
        assertFalse(isStrictlyNewer("3.0.0", "garbage"))
        assertTrue(isValidVersion("1.0.0"))
        assertFalse(isValidVersion("1.0"))
    }
}
