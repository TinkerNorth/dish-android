// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

private const val RES = 0x7f120001

class ContextStringLookupTest {
    @Test
    fun `a lookup hands the resource and every argument, in order, to the context`() {
        val context = mockk<Context>()
        every { context.getString(RES, "Living room PC", "#0: backendUnavailable") } returns "formatted"

        val text = ContextStringLookup(context).format(RES, "Living room PC", "#0: backendUnavailable")

        assertEquals("formatted", text)
    }
}
