// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.freshAppInstanceOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.LongSupplier

// Above the boxed-Integer cache (-128..127), so a lookup that boxed the id would allocate.
private const val UNCACHED_DEVICE_ID = 300
private const val COLORS_PER_CYCLE = 1000

// A session that counts what reached it without keeping it.
private class CountingLightbar : FrameworkLightGateway.Lightbar {
    var writes = 0L

    override fun write(argb: Int): Boolean {
        writes++
        return true
    }

    override fun close() = Unit
}

private class OneLightbar(
    private val lightbar: CountingLightbar,
) : FrameworkLightGateway.Lightbars {
    override fun open(deviceId: Int): FrameworkLightGateway.Lightbar = lightbar
}

// A host asking again and again for the color a pad already shows. Reached through Runnable (one
// cycle) and LongSupplier (the writes that reached the service) because the allocation test makes it
// in a class loader of its own (freshAppInstanceOf).
internal class RepeatedColorCycles :
    Runnable,
    LongSupplier {
    private val lightbar = CountingLightbar()
    private val gateway = FrameworkLightGateway(OneLightbar(lightbar))

    init {
        gateway.setColor(UNCACHED_DEVICE_ID, 1, 2, 3)
    }

    override fun run() {
        repeat(COLORS_PER_CYCLE) { gateway.setColor(UNCACHED_DEVICE_ID, 1, 2, 3) }
    }

    override fun getAsLong(): Long = lightbar.writes
}

private const val FIRST_PAD = 9
private const val SECOND_PAD = 12
private const val THIRD_PAD = 15
private const val UNCOLORED_PAD = 7

// What a closed session leaves on a light: the input service's LightState(0).
private const val OFF_ARGB = 0
private const val RED_ARGB = 0xFFFF0000.toInt()
private const val GREEN_ARGB = 0xFF00FF00.toInt()
private const val BLUE_ARGB = 0xFF0000FF.toInt()

private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

// The session lifecycle: open lazily on the first color, reuse for later colors, coalesce identical
// ones, close on release, and never resurrect a session for a device whose bar has gone. The Android
// I/O is faked so this runs on the JVM, with the input service's light sessions modelled as AOSP's
// InputManagerService keeps them (API 31-37): one list for every app, and a close that turns the
// closing session's lights off, then repaints the first remaining session's request onto the closing
// pad by light id. PeripheralController numbers each pad's lights from 1, so every bar here is light 1.
class FrameworkLightGatewayTest {
    private class FakeLightbar(
        private val service: FakeLightbars,
        val deviceId: Int,
    ) : FrameworkLightGateway.Lightbar {
        val writes = mutableListOf<Int>()
        var closes = 0
        var writeResult = true
        var landedArgb: Int? = null

        override fun write(argb: Int): Boolean {
            writes += argb
            if (!writeResult) return false
            landedArgb = argb
            service.paint(deviceId, argb)
            return true
        }

        override fun close() {
            closes++
            service.closeSession(this)
        }
    }

    private class FakeLightbars : FrameworkLightGateway.Lightbars {
        val withBar = mutableSetOf<Int>()
        var nextWriteResult = true
        val opens = mutableListOf<Int>()
        val handles = mutableMapOf<Int, FakeLightbar>()
        private val openSessions = mutableListOf<FakeLightbar>()
        private val barColors = mutableMapOf<Int, Int>()

        override fun open(deviceId: Int): FrameworkLightGateway.Lightbar? {
            if (deviceId !in withBar) return null
            opens += deviceId
            val session = FakeLightbar(this, deviceId)
            session.writeResult = nextWriteResult
            handles[deviceId] = session
            openSessions += session
            return session
        }

        fun barColorOf(deviceId: Int): Int = barColors[deviceId] ?: OFF_ARGB

        fun paint(
            deviceId: Int,
            argb: Int,
        ) {
            if (deviceId in withBar) barColors[deviceId] = argb
        }

        fun closeSession(session: FakeLightbar) {
            paint(session.deviceId, OFF_ARGB)
            openSessions -= session
            val firstRemainingRequest = openSessions.firstOrNull()?.landedArgb ?: return
            paint(session.deviceId, firstRemainingRequest)
        }
    }

    private val lightbars = FakeLightbars()
    private val gateway = FrameworkLightGateway(lightbars)

    private fun hostSends(
        deviceId: Int,
        argb: Int,
    ) {
        val r = (argb shr RED_SHIFT) and CHANNEL_MASK
        val g = (argb shr GREEN_SHIFT) and CHANNEL_MASK
        val b = argb and CHANNEL_MASK
        gateway.setColor(deviceId, r, g, b)
    }

    private fun lightTwoPads() {
        lightbars.withBar += FIRST_PAD
        lightbars.withBar += SECOND_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        hostSends(SECOND_PAD, BLUE_ARGB)
    }

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

    // ---- a close repaints another pad's request onto the closing pad ----

    @Test
    fun `a bar released while another pad's is lit goes dark instead of taking that pad's color`() {
        lightTwoPads()

        gateway.release(FIRST_PAD)

        assertEquals(OFF_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(BLUE_ARGB, lightbars.barColorOf(SECOND_PAD))
    }

    @Test
    fun `a bar released while another pad's is lit keeps its session for its next color`() {
        lightTwoPads()
        gateway.release(FIRST_PAD)

        hostSends(FIRST_PAD, GREEN_ARGB)

        assertEquals(listOf(FIRST_PAD, SECOND_PAD), lightbars.opens)
        assertEquals(0, lightbars.handles.getValue(FIRST_PAD).closes)
        assertEquals(GREEN_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `releasing a turned-off bar again sends nothing`() {
        lightTwoPads()
        gateway.release(FIRST_PAD)

        gateway.release(FIRST_PAD)

        assertEquals(listOf(RED_ARGB, OFF_ARGB), lightbars.handles.getValue(FIRST_PAD).writes)
        assertEquals(0, lightbars.handles.getValue(FIRST_PAD).closes)
    }

    @Test
    fun `the last lit bar released is closed, and its close paints nothing onto either pad`() {
        lightTwoPads()
        gateway.release(SECOND_PAD)

        gateway.release(FIRST_PAD)

        assertEquals(1, lightbars.handles.getValue(FIRST_PAD).closes)
        assertEquals(OFF_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(OFF_ARGB, lightbars.barColorOf(SECOND_PAD))
    }

    @Test
    fun `a bar that cannot be turned off beside a lit one is closed, since it is gone`() {
        lightTwoPads()
        lightbars.handles.getValue(FIRST_PAD).writeResult = false

        gateway.release(FIRST_PAD)

        assertEquals(1, lightbars.handles.getValue(FIRST_PAD).closes)
    }

    @Test
    fun `releaseAll leaves every pad's bar dark`() {
        lightTwoPads()

        gateway.releaseAll()

        assertEquals(OFF_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(OFF_ARGB, lightbars.barColorOf(SECOND_PAD))
    }

    @Test
    fun `releaseAll darkens and closes every other bar when a gone pad's cannot be turned off`() {
        lightTwoPads()
        lightbars.withBar += THIRD_PAD
        hostSends(THIRD_PAD, GREEN_ARGB)
        lightbars.handles.getValue(FIRST_PAD).writeResult = false
        lightbars.withBar -= FIRST_PAD

        gateway.releaseAll()

        assertEquals(1, lightbars.handles.getValue(FIRST_PAD).closes)
        assertEquals(1, lightbars.handles.getValue(SECOND_PAD).closes)
        assertEquals(1, lightbars.handles.getValue(THIRD_PAD).closes)
        assertEquals(OFF_ARGB, lightbars.barColorOf(SECOND_PAD))
        assertEquals(OFF_ARGB, lightbars.barColorOf(THIRD_PAD))
    }

    @Test
    fun `releaseAll closes a turned-off bar too`() {
        lightTwoPads()
        gateway.release(FIRST_PAD)

        gateway.releaseAll()

        assertEquals(1, lightbars.handles.getValue(FIRST_PAD).closes)
        assertEquals(1, lightbars.handles.getValue(SECOND_PAD).closes)
    }

    // ---- a host sends a color once, so a bar lit again shows the one it sent last ----

    @Test
    fun `a bar released on unbind shows its host's last color again when its slot is bound again`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        gateway.release(FIRST_PAD)

        gateway.restore(FIRST_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `every bar given back when the app left shows its host's last color again on return`() {
        lightTwoPads()
        gateway.releaseAll()

        gateway.restore(FIRST_PAD)
        gateway.restore(SECOND_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(BLUE_ARGB, lightbars.barColorOf(SECOND_PAD))
    }

    @Test
    fun `a bar turned off beside a lit one shows its host's color again on the session it kept`() {
        lightTwoPads()
        gateway.release(FIRST_PAD)

        gateway.restore(FIRST_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(listOf(FIRST_PAD, SECOND_PAD), lightbars.opens)
    }

    @Test
    fun `the host's latest color is the one shown again`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        hostSends(FIRST_PAD, GREEN_ARGB)
        gateway.release(FIRST_PAD)

        gateway.restore(FIRST_PAD)

        assertEquals(GREEN_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `restoring a bar that is still lit sends nothing`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)

        gateway.restore(FIRST_PAD)

        assertEquals(listOf(RED_ARGB), lightbars.handles.getValue(FIRST_PAD).writes)
    }

    @Test
    fun `restoring a pad its host never colored opens no session`() {
        lightbars.withBar += FIRST_PAD

        gateway.restore(FIRST_PAD)

        assertTrue(lightbars.opens.isEmpty())
    }

    @Test
    fun `a pad that has gone does not get its host's color back`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        gateway.release(FIRST_PAD)

        gateway.forget(FIRST_PAD)
        gateway.restore(FIRST_PAD)

        assertEquals(listOf(FIRST_PAD), lightbars.opens)
        assertEquals(OFF_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `a bar kept beside a lit one is given back when its pad departs`() {
        lightTwoPads()
        gateway.release(SECOND_PAD)
        lightbars.withBar -= SECOND_PAD

        gateway.forget(SECOND_PAD)

        assertEquals(1, lightbars.handles.getValue(SECOND_PAD).closes)
        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `forgetting a pad its host never colored keeps every other pad's color`() {
        lightTwoPads()
        gateway.releaseAll()

        gateway.forget(UNCOLORED_PAD)
        gateway.restore(FIRST_PAD)
        gateway.restore(SECOND_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(BLUE_ARGB, lightbars.barColorOf(SECOND_PAD))
    }

    // ---- the inspector bench paints a bar without speaking for its host ----

    @Test
    fun `a bench color is not remembered as the host's`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        gateway.paint(FIRST_PAD, 0x00, 0xFF, 0x00)
        gateway.release(FIRST_PAD)

        gateway.restore(FIRST_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `ending a bench test shows the host's color again`() {
        lightbars.withBar += FIRST_PAD
        hostSends(FIRST_PAD, RED_ARGB)
        gateway.paint(FIRST_PAD, 0x00, 0xFF, 0x00)

        gateway.showHostColor(FIRST_PAD)

        assertEquals(RED_ARGB, lightbars.barColorOf(FIRST_PAD))
    }

    @Test
    fun `ending a bench test on a pad no host colored gives its bar back dark`() {
        lightbars.withBar += FIRST_PAD
        gateway.paint(FIRST_PAD, 0x00, 0xFF, 0x00)

        gateway.showHostColor(FIRST_PAD)

        assertEquals(OFF_ARGB, lightbars.barColorOf(FIRST_PAD))
        assertEquals(1, lightbars.handles.getValue(FIRST_PAD).closes)
    }

    @Test
    fun `a repeated color allocates nothing`() {
        val cycles = freshAppInstanceOf(RepeatedColorCycles::class.java)
        val cycle = cycles as Runnable
        repeat(WARMUP_CYCLES) { cycle.run() }

        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS) { cycle.run() }

        assertEquals("only the first color reached the service", 1L, (cycles as LongSupplier).asLong)
        assertTrue("$allocated bytes over $COLORS_PER_CYCLE colors", allocated < COLORS_PER_CYCLE * BYTES_PER_COLOR_BOUND)
    }

    private companion object {
        const val WARMUP_CYCLES = 20
        const val MEASURED_RUNS = 3

        // Half the smallest object: a boxed id or color costs 16 bytes or more every call.
        const val BYTES_PER_COLOR_BOUND = 8
    }
}
