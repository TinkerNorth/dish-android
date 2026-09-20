// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The session lifecycle: open lazily on the first color, reuse for later colors, coalesce identical
// ones, close on release, and never resurrect a session for a device whose bar has gone. The Android
// I/O is faked so this runs on the JVM.
class FrameworkLightGatewayTest {
    private class FakeLightbar : FrameworkLightGateway.Lightbar {
        val writes = mutableListOf<Int>()
        var closes = 0
        var writeResult = true

        override fun write(argb: Int): Boolean {
            writes += argb
            return writeResult
        }

        override fun close() {
            closes++
        }
    }

    private class FakeLightbars : FrameworkLightGateway.Lightbars {
        val withBar = mutableSetOf<Int>()
        var nextWriteResult = true
        val opens = mutableListOf<Int>()
        val handles = mutableMapOf<Int, FakeLightbar>()

        override fun open(deviceId: Int): FrameworkLightGateway.Lightbar? {
            if (deviceId !in withBar) return null
            opens += deviceId
            return FakeLightbar().also {
                it.writeResult = nextWriteResult
                handles[deviceId] = it
            }
        }
    }

    private val lightbars = FakeLightbars()
    private val gateway = FrameworkLightGateway(lightbars)

    @Test
    fun `the first color opens a session and the next colors reuse it`() {
        lightbars.withBar += 9
        gateway.setColor(9, 1, 2, 3)
        gateway.setColor(9, 4, 5, 6)
        assertEquals(listOf(9), lightbars.opens)
        assertEquals(
            listOf(0xFF010203.toInt(), 0xFF040506.toInt()),
            lightbars.handles.getValue(9).writes,
        )
    }

    @Test
    fun `an identical color is coalesced and never reaches the service`() {
        lightbars.withBar += 9
        gateway.setColor(9, 1, 2, 3)
        gateway.setColor(9, 1, 2, 3)
        assertEquals(
            1,
            lightbars.handles
                .getValue(9)
                .writes.size,
        )
    }

    @Test
    fun `a full-opacity ARGB is what lands, so alpha drives brightness`() {
        lightbars.withBar += 9
        gateway.setColor(9, 0x10, 0x20, 0x30)
        assertEquals(
            0xFF102030.toInt(),
            lightbars.handles
                .getValue(9)
                .writes
                .single(),
        )
    }

    @Test
    fun `a device with no drivable bar never opens a session`() {
        gateway.setColor(9, 1, 2, 3)
        assertTrue(lightbars.opens.isEmpty())
    }

    @Test
    fun `release closes the session and gives the bar back`() {
        lightbars.withBar += 9
        gateway.setColor(9, 1, 2, 3)
        gateway.release(9)
        assertEquals(1, lightbars.handles.getValue(9).closes)
    }

    @Test
    fun `a released device is not reopened once its bar is gone`() {
        lightbars.withBar += 9
        gateway.setColor(9, 1, 2, 3)
        gateway.release(9)
        // The pad left, so its bar is no longer offered; a stale color must not resurrect it.
        lightbars.withBar -= 9
        gateway.setColor(9, 4, 5, 6)
        assertEquals(listOf(9), lightbars.opens)
    }

    @Test
    fun `releaseAll closes every open session`() {
        lightbars.withBar += 9
        lightbars.withBar += 12
        gateway.setColor(9, 1, 2, 3)
        gateway.setColor(12, 4, 5, 6)
        gateway.releaseAll()
        assertEquals(1, lightbars.handles.getValue(9).closes)
        assertEquals(1, lightbars.handles.getValue(12).closes)
    }

    @Test
    fun `a failed first color drops the session without closing an unrequested one`() {
        lightbars.withBar += 9
        lightbars.nextWriteResult = false
        gateway.setColor(9, 1, 2, 3)
        // Nothing landed, so closing would throw in the service: it is dropped, not closed.
        assertEquals(0, lightbars.handles.getValue(9).closes)
        // And the next color reopens against a fresh session.
        lightbars.nextWriteResult = true
        gateway.setColor(9, 4, 5, 6)
        assertEquals(listOf(9, 9), lightbars.opens)
    }

    @Test
    fun `a color that fails after a good one hands the bar back`() {
        lightbars.withBar += 9
        gateway.setColor(9, 1, 2, 3)
        // The bar goes away mid-stream: the next color fails, and the session, having landed a
        // color already, is closed so the light is released.
        lightbars.handles.getValue(9).writeResult = false
        gateway.setColor(9, 4, 5, 6)
        assertEquals(1, lightbars.handles.getValue(9).closes)
    }
}
