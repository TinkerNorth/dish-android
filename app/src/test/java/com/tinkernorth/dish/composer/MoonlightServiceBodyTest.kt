// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonlightServiceBodyTest {
    @Test
    fun `no host at all reads idle`() {
        assertEquals(MoonlightServiceBody.Idle, moonlightServiceBodyFor(hostLabel = null, padCount = 0))
    }

    @Test
    fun `a pad count without a host is still idle`() {
        assertEquals(MoonlightServiceBody.Idle, moonlightServiceBodyFor(hostLabel = null, padCount = 2))
    }

    @Test
    fun `a host with no pads yet is starting`() {
        assertEquals(MoonlightServiceBody.Starting(HOST), moonlightServiceBodyFor(hostLabel = HOST, padCount = 0))
    }

    @Test
    fun `the first pad turns starting into a count`() {
        assertEquals(MoonlightServiceBody.Pads(HOST, 1), moonlightServiceBodyFor(hostLabel = HOST, padCount = 1))
    }

    @Test
    fun `several pads are counted for the host`() {
        assertEquals(MoonlightServiceBody.Pads(HOST, 3), moonlightServiceBodyFor(hostLabel = HOST, padCount = 3))
    }

    private companion object {
        const val HOST = "Desk PC"
    }
}
