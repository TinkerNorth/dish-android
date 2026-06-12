// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalBatteryMappingTest {
    @Test
    fun `pad with no battery present maps to null for phone fallback`() {
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
        assertEquals(BatteryValidator.STATUS_DISCHARGING, sample?.status)
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
        // Some HID descriptors report slightly-over-1.0 capacity. Clamp rather than letting validator reject >100.
        assertEquals(
            100,
            PhysicalBatteryMapping
                .controllerSample(true, 1.02f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING)
                ?.level,
        )
    }

    @Test
    fun `present pad with NaN capacity reports the unknown-level sentinel`() {
        val sample =
            PhysicalBatteryMapping.controllerSample(
                isPresent = true,
                capacity = Float.NaN,
                status = PhysicalBatteryMapping.ANDROID_STATUS_CHARGING,
            )
        assertEquals(BatteryValidator.LEVEL_UNKNOWN, sample?.level)
        assertEquals(BatteryValidator.STATUS_CHARGING, sample?.status)
    }

    @Test
    fun `present pad with negative capacity reports the unknown-level sentinel`() {
        val sample =
            PhysicalBatteryMapping.controllerSample(
                isPresent = true,
                capacity = -1f,
                status = PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING,
            )
        assertEquals(BatteryValidator.LEVEL_UNKNOWN, sample?.level)
    }

    @Test
    fun `charging status maps to the wire charging status`() {
        assertEquals(
            BatteryValidator.STATUS_CHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
        )
    }

    @Test
    fun `full status maps to the wire full status`() {
        assertEquals(
            BatteryValidator.STATUS_FULL,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_FULL),
        )
    }

    @Test
    fun `discharging status maps to the wire discharging status`() {
        assertEquals(
            BatteryValidator.STATUS_DISCHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING),
        )
    }

    @Test
    fun `not-charging status reads as discharging`() {
        // Plugged-but-held (charge limiter): matches the phone-battery choice from the player's view.
        assertEquals(
            BatteryValidator.STATUS_DISCHARGING,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_NOT_CHARGING),
        )
    }

    @Test
    fun `unknown status maps to the wire unknown status`() {
        assertEquals(
            BatteryValidator.STATUS_UNKNOWN,
            PhysicalBatteryMapping.statusToWire(PhysicalBatteryMapping.ANDROID_STATUS_UNKNOWN),
        )
    }

    @Test
    fun `an out-of-range status falls back to unknown`() {
        assertEquals(BatteryValidator.STATUS_UNKNOWN, PhysicalBatteryMapping.statusToWire(99))
        assertEquals(BatteryValidator.STATUS_UNKNOWN, PhysicalBatteryMapping.statusToWire(-1))
    }

    @Test
    fun `every mapped sample is accepted by the validator`() {
        val validator = BatteryValidator()
        val cases =
            listOf(
                Triple(true, 0f, PhysicalBatteryMapping.ANDROID_STATUS_DISCHARGING),
                Triple(true, 1f, PhysicalBatteryMapping.ANDROID_STATUS_FULL),
                Triple(true, 0.5f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
                Triple(true, 1.5f, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
                Triple(true, Float.NaN, PhysicalBatteryMapping.ANDROID_STATUS_CHARGING),
            )
        for ((present, cap, status) in cases) {
            val sample = PhysicalBatteryMapping.controllerSample(present, cap, status)!!
            var emitted = false
            validator.publish(sample) { emitted = true }
            org.junit.Assert.assertTrue(emitted)
        }
    }
}
