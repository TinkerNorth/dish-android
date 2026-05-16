// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import com.tinkernorth.dish.data.network.BatteryCoalescer.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BatteryCoalescer] — the validation gate in front of
 * [SatelliteNative.sendBattery]. MSG_BATTERY is a fixed 30 s heartbeat, so
 * `publish` forwards every well-formed sample (no dedup) and rejects only
 * malformed ones.
 */
class BatteryCoalescerTest {
    private val emitted = mutableListOf<BatterySample>()
    private val emit = BatteryCoalescer.Emit { s -> emitted += s }

    @Test
    fun `first sample is emitted`() {
        val c = BatteryCoalescer()
        assertTrue(c.publish(BatterySample(75, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(1, emitted.size)
        assertEquals(75, emitted[0].level)
    }

    @Test
    fun `every sample is forwarded, including unchanged ones`() {
        // MSG_BATTERY is a fixed 30 s heartbeat: an unchanged value must still
        // reach the wire each tick so a dropped UDP packet self-heals — publish
        // must NOT coalesce identical samples.
        val c = BatteryCoalescer()
        val s = BatterySample(100, BatteryCoalescer.STATUS_WIRED)
        assertTrue(c.publish(s, emit))
        assertTrue(c.publish(s, emit))
        assertTrue(c.publish(s, emit))
        assertEquals(3, emitted.size)
    }

    @Test
    fun `level change is forwarded`() {
        val c = BatteryCoalescer()
        c.publish(BatterySample(75, BatteryCoalescer.STATUS_DISCHARGING), emit)
        assertTrue(c.publish(BatterySample(74, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertTrue(c.publish(BatterySample(73, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(3, emitted.size)
    }

    @Test
    fun `status change at the same level is forwarded`() {
        val c = BatteryCoalescer()
        c.publish(BatterySample(80, BatteryCoalescer.STATUS_DISCHARGING), emit)
        assertTrue(c.publish(BatterySample(80, BatteryCoalescer.STATUS_CHARGING), emit))
        assertEquals(2, emitted.size)
        assertEquals(BatteryCoalescer.STATUS_CHARGING, emitted[1].status)
    }

    @Test
    fun `0xFF level is accepted as the unknown sentinel`() {
        val c = BatteryCoalescer()
        // Status-only Bluetooth pads (some 8BitDo) report level=0xFF + a
        // known status. publish must NOT reject 0xFF as malformed.
        assertTrue(
            c.publish(
                BatterySample(BatteryCoalescer.LEVEL_UNKNOWN, BatteryCoalescer.STATUS_CHARGING),
                emit,
            ),
        )
        assertEquals(1, emitted.size)
    }

    @Test
    fun `bogus level above 100 is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(BatterySample(101, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertFalse(c.publish(BatterySample(254, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `negative level is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(BatterySample(-1, BatteryCoalescer.STATUS_DISCHARGING), emit))
        assertEquals(0, emitted.size)
    }

    @Test
    fun `status outside the documented set is rejected`() {
        val c = BatteryCoalescer()
        assertFalse(c.publish(BatterySample(50, BatteryCoalescer.STATUS_MAX + 1), emit))
        assertFalse(c.publish(BatterySample(50, -1), emit))
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
}
