// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UsbIdentityTest {
    @Test
    fun `vidPidKey packs the vendor above the product`() {
        assertEquals(0x054C_09CC, vidPidKey(SONY_VENDOR, DUALSHOCK_4_PRODUCT))
    }

    @Test
    fun `vidPidKey masks a product id to sixteen bits`() {
        val overflowingProduct = 0x1_09CC
        assertEquals(vidPidKey(SONY_VENDOR, DUALSHOCK_4_PRODUCT), vidPidKey(SONY_VENDOR, overflowingProduct))
    }

    @Test
    fun `the same product under two vendors never collides`() {
        assertNotEquals(vidPidKey(SONY_VENDOR, DUALSHOCK_4_PRODUCT), vidPidKey(MICROSOFT_VENDOR, DUALSHOCK_4_PRODUCT))
    }

    @Test
    fun `the zero pair is the zero key`() {
        assertEquals(0, vidPidKey(0, 0))
    }

    private companion object {
        const val SONY_VENDOR = 0x054C
        const val DUALSHOCK_4_PRODUCT = 0x09CC
        const val MICROSOFT_VENDOR = 0x045E
    }
}
