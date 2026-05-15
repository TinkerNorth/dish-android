// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.BatteryCoalescer.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BatteryCoalescer] — per-controller dedup wrapping
 * [SatelliteNative.sendBattery]. Battery is reported on a 30 s cadence plus
 * state-change triggers, so the coalescer drops identical follow-ups.
 */
class BatteryCoalescerTest {
    private val emitted = mutableListOf<BatterySample>()
    private val emit = BatteryCoalescer.Emit { s -> emitted += s }

    @Test
    fun `first sample is emitted`() {
        val c = BatteryCoalescer()
        assertTrue(c.publish(0, BatterySample(75, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(1, emitted.size)
        assertEquals(75, emitted[0].level)
    }

    @Test
    fun `identical follow-up sample is dropped`() {
        val c = BatteryCoalescer()
        val s = BatterySample(100, BatteryCoalescer.STATUS_WIRED)
        c.publish(0, s, emit)
        assertFalse(c.publish(0, s, emit))
        assertFalse(c.publish(0, s, emit))
        assertEquals(1, emitted.size)
    }

    @Test
    fun `level change emits`() {
        val c = BatteryCoalescer()
        c.publish(0, BatterySample(75, BatteryCoalescer.STATUS_DISCHARGING), emit)
        assertTrue(c.publish(0, BatterySample(74, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertTrue(c.publish(0, BatterySample(73, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(3, emitted.size)
    }

    @Test
    fun `status change at the same level emits`() {
        val c = BatteryCoalescer()
        c.publish(0, BatterySample(80, BatteryCoalescer.STATUS_DISCHARGING), emit)
        assertTrue(c.publish(0, BatterySample(80, BatteryCoalescer.STATUS_CHARGING), emit))
        assertEquals(2, emitted.size)
        assertEquals(BatteryCoalescer.STATUS_CHARGING, emitted[1].status)
    }

    @Test
    fun `coalescing is independent per controller`() {
        val c = BatteryCoalescer()
        val s = BatterySample(50, BatteryCoalescer.STATUS_DISCHARGING)
        assertTrue(c.publish(0, s, emit))
        assertTrue(c.publish(1, s, emit))
        assertFalse(c.publish(0, s, emit))
        assertFalse(c.publish(1, s, emit))
        assertEquals(2, emitted.size)
    }

    @Test
    fun `clear lets the same sample emit again`() {
        val c = BatteryCoalescer()
        val s = BatterySample(50, BatteryCoalescer.STATUS_DISCHARGING)
        c.publish(0, s, emit)
        c.clear(0)
        assertTrue(c.publish(0, s, emit))
        assertEquals(2, emitted.size)
    }

    @Test
    fun `0xFF level is accepted as the unknown sentinel`() {
        val c = BatteryCoalescer()
        // Status-only Bluetooth pads (some 8BitDo) report level=0xFF + a
        // known status. The coalescer must NOT reject 0xFF as malformed.
        assertTrue(
            c.publish(
                0,
                BatterySample(BatteryCoalescer.LEVEL_UNKNOWN, BatteryCoalescer.STATUS_CHARGING),
                emit,
            ),
        )
        assertEquals(1, emitted.size)
    }

    @Test
    fun `bogus level above 100 is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(0, BatterySample(101, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertFalse(c.publish(0, BatterySample(254, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `negative level is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(0, BatterySample(-1, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `status outside the documented set is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(0, BatterySample(50, BatteryCoalescer.STATUS_MAX + 1), emit))
        assertFalse(c.publish(0, BatterySample(50, -1), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `wire constants match the protocol spec`() {
        // These literal values are the contract with satellite/src/core/types.h.
        // Bumping them silently would break every paired receiver.
        assertEquals(0xFF, BatteryCoalescer.LEVEL_UNKNOWN)
        assertEquals(0, BatteryCoalescer.STATUS_UNKNOWN)
        assertEquals(1, BatteryCoalescer.STATUS_DISCHARGING)
        assertEquals(2, BatteryCoalescer.STATUS_CHARGING)
        assertEquals(3, BatteryCoalescer.STATUS_FULL)
        assertEquals(4, BatteryCoalescer.STATUS_WIRED)
        assertEquals(30, BatteryCoalescer.REPORT_INTERVAL_SECONDS)
    }

    @Test
    fun `clearAll wipes every controller`() {
        val c = BatteryCoalescer()
        val s = BatterySample(50, BatteryCoalescer.STATUS_DISCHARGING)
        c.publish(0, s, emit)
        c.publish(1, s, emit)
        c.clearAll()
        assertTrue(c.publish(0, s, emit))
        assertTrue(c.publish(1, s, emit))
        assertEquals(4, emitted.size)
    }
}
