// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.settings

import android.view.View
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Which license rows open a link, and how a row that opens none is bound, a recycled link row
// included.
class LicensesAdapterTest {
    // ---- the link a row opens ----

    @Test
    fun `the first licence's link wins over the library's`() {
        val entry = LicenseEntry(url = LIBRARY_URL, licenses = listOf(LicenseInfo(url = LICENSE_URL)))
        assertEquals(LICENSE_URL, licenseLinkOf(entry))
    }

    @Test
    fun `a licence with no link falls back to the library's`() {
        val entry = LicenseEntry(url = LIBRARY_URL, licenses = listOf(LicenseInfo(name = "MIT")))
        assertEquals(LIBRARY_URL, licenseLinkOf(entry))
    }

    @Test
    fun `an entry with no licence uses the library's link`() {
        assertEquals(LIBRARY_URL, licenseLinkOf(LicenseEntry(url = LIBRARY_URL)))
    }

    @Test
    fun `an entry with no link at all opens nothing`() {
        assertNull(licenseLinkOf(LicenseEntry()))
    }

    @Test
    fun `a blank link opens nothing`() {
        val entry = LicenseEntry(url = LIBRARY_URL, licenses = listOf(LicenseInfo(url = " ")))
        assertNull(licenseLinkOf(entry))
    }

    // ---- a row that opens nothing ----

    // View.setOnClickListener turns clickable back on, so the listener has to go first.
    @Test
    fun `an inert row drops its listener before it stops being clickable`() {
        val row = mockk<View>(relaxed = true)

        bindInertRow(row)

        verifyOrder {
            row.setOnClickListener(null)
            row.isClickable = false
        }
    }

    @Test
    fun `an inert row is not focusable`() {
        val row = mockk<View>(relaxed = true)

        bindInertRow(row)

        verify { row.isFocusable = false }
    }

    @Test
    fun `an inert row drops the description a recycled link row announced`() {
        val row = mockk<View>(relaxed = true)

        bindInertRow(row)

        verify { row.contentDescription = null }
    }

    private companion object {
        const val LIBRARY_URL = "https://example.org/library"
        const val LICENSE_URL = "https://example.org/license"
    }
}
