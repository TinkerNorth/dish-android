// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [PhysicalBatteryMapping] — the pure
 * `android.os.BatteryState` → wire `(level, status)` mapping behind
 * [PhysicalBatterySource] (roadmap Task 1.2, physical-controller half).
 *
 * The contract that matters: a wireless pad's own battery is reported as-is,
 * while a USB-wired / batteryless pad surfaces as `isPresent == false` and
 * MUST map to null so the caller falls back to the phone (host) battery.
 */
class PhysicalBatteryMappingTest {
    // ── controllerSample: present vs not-present ────────────────────────────

    @Test
    fun `pad with no battery present maps to null for phone fallback`() {
        // A USB-wired pad: it has no battery of its own, so getBatteryState()
        // reports isPresent=false. The caller must fall back to the phone.
        assertNull(
            PhysicalBatteryMapping.controllerSample(
                isPresent = false,
                capacity = Float.NaN,
                status = PhysicalBatteryMapping.ANDROID_STATUS_UNKNOWN,
            ),
        )
    }

    @Test
    fun `not-present wins even when a capacity is somehow set`() {
        // Defensive: isPresent=false is the authoritative signal — a stray
        // capacity value must not resurrect a non-existent battery.
        assertNull(
            PhysicalBatteryMapping.controllerSample(
                isPresent = false,
                capacity = 0.5f,
                status = PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING,
            ),
        )
    }

    @Test
    fun `wireless pad capacity maps to a percentage in the 0 to 100 range`() {
        val sample =
            PhysicalBatteryMapping.controllerSample(
                isPresent = true,
                capacity = 0.84f,
                status = PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING,
            )
        assertEquals(84, sample?.level)
        assertEquals(BatteryCoalescer.STATUS_DISCHARGING, sample?.status)
    }

    @Test
    fun `capacity endpoints map to 0 and 100`() {
        assertEquals(
            0,
            PhysicalBatteryMapping
                .controllerSample(true, 0f, PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING)
                ?.level,
        )
        assertEquals(
            100,
            PhysicalBatteryMapping
                .controllerSample(true, 1f, PhysicalBatteryMapping.ANDROID_STATUS_FULL)
                ?.level,
        )
    }

    @Test
    fun `capacity above 1 is clamped to 100`() {
        // Some HID descriptors report a slightly-over-1.0 capacity; clamp it
        // rather than letting the coalescer reject a >100 level.
        assertEquals(
            100,
            PhysicalBatteryMapping
                .controllerSample(true, 1.02f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING)
                ?.level,
        )
    }

    @Test
    fun `present pad with NaN capacity reports the unknown-level sentinel`() {
        // A pad that exposes a charging status but no percentage (some 8BitDo
        // pads): keep the status, send level=0xFF rather than discarding it.
        val sample =
            PhysicalBatteryMapping.controllerSample(
                isPresent = true,
                capacity = Float.NaN,
                status = PhysicalBatteryMapping.ANDROID_STATUS_CHARGING,
            )
        assertEquals(BatteryCoalescer.LEVEL_UNKNOWN, sample?.level)
        assertEquals(BatteryCoalescer.STATUS_CHARGING, sample?.status)
    }

    @Test
    fun `present pad with negative capacity reports the unknown-level sentinel`() {
        val sample =
            PhysicalBatteryMapping.controllerSample(
                isPresent = true,
                capacity = -1f,
                status = PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING,
            )
        assertEquals(BatteryCoalescer.LEVEL_UNKNOWN, sample?.level)
    }

    // ── statusToWire ────────────────────────────────────────────────────────

    @Test
    fun `charging status maps to the wire charging status`() {
        assertEquals(
            BatteryCoalescer.STATUS_CHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
        )
    }

    @Test
    fun `full status maps to the wire full status`() {
        assertEquals(
            BatteryCoalescer.STATUS_FULL,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_FULL),
        )
    }

    @Test
    fun `discharging status maps to the wire discharging status`() {
        assertEquals(
            BatteryCoalescer.STATUS_DISCHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING),
        )
    }

    @Test
    fun `not-charging status reads as discharging`() {
        // Plugged in but held (charge limiter) — closest to discharging from
        // the player's point of view, matching the phone-battery choice.
        assertEquals(
            BatteryCoalescer.STATUS_DISCHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_NOT_CHARGING),
        )
    }

    @Test
    fun `unknown status maps to the wire unknown status`() {
        assertEquals(
            BatteryCoalescer.STATUS_UNKNOWN,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_UNKNOWN),
        )
    }

    @Test
    fun `an out-of-range status falls back to unknown`() {
        assertEquals(BatteryCoalescer.STATUS_UNKNOWN, PhysicalBatteryMapping.statusToWire(99))
        assertEquals(BatteryCoalescer.STATUS_UNKNOWN, PhysicalBatteryMapping.statusToWire(-1))
    }

    @Test
    fun `every mapped sample is accepted by the coalescer`() {
        // The mapping must never produce a (level, status) the coalescer would
        // reject as malformed — that would silently drop a real battery report.
        val coalescer = BatteryCoalescer()
        val cases =
            listOf(
                Triple(true, 0f, PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING),
                Triple(true, 1f, PhysicalBatteryMapping.ANDROID_STATUS_FULL),
                Triple(true, 0.5f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
                Triple(true, 1.5f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
                Triple(true, Float.NaN, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
            )
        var index = 0
        for ((present, cap, status) in cases) {
            val sample = PhysicalBatteryMapping.controllerSample(present, cap, status)!!
            // Distinct controller index per case so coalescing never hides one.
            var emitted = false
            coalescer.publish(index++, sample) { emitted = true }
            org.junit.Assert.assertTrue(
                "coalescer rejected mapped sample $sample",
                emitted,
            )
        }
    }
}
