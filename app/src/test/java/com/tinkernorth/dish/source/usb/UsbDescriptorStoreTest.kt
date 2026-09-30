// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbDescriptorStoreTest {
    private val facts =
        UsbEndpointFacts(
            intervalRaw = 4,
            maxPacketSize = 64,
            pollRateHz = 250,
            highSpeed = false,
            interfaceClass = 3,
            hasOutEndpoint = true,
        )

    @Test
    fun `factsFor is null for a model never noted`() {
        assertNull(UsbDescriptorStore().factsFor(0x045E, 0x028E))
    }

    @Test
    fun `factsFor returns what note recorded for that model only`() {
        val store = UsbDescriptorStore()

        store.note(0x045E, 0x028E, facts)

        assertEquals(facts, store.factsFor(0x045E, 0x028E))
        assertNull(store.factsFor(0x045E, 0x028F))
    }

    @Test
    fun `a second note for the same model replaces the first`() {
        val store = UsbDescriptorStore()
        store.note(0x045E, 0x028E, facts)

        store.note(0x045E, 0x028E, facts.copy(pollRateHz = 1000))

        assertEquals(1000, store.factsFor(0x045E, 0x028E)?.pollRateHz)
        assertEquals(1, store.state.value.size)
    }
}
