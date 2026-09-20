// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The light-bar selection rule per API level. hasRgbControl only tells the truth on API 34+; on
// 31..33 the driver's light name is the signal, because the RGB capability flag is 0 there and
// reads true for every light. All pure: no InputDevice, no framework calls.
class FrameworkLightProbeTest {
    private fun input(
        id: Int,
        name: String,
        rgb: Boolean,
    ) = FrameworkLight(id = id, name = name, input = true, rgb = rgb)

    // A DualSense on API 31..33: the light bar is one multicolor "indicator" plus five player LEDs
    // that come through as mono INPUT lights (the framework's player grouping rejects `player-N`).
    private val dualsenseBelow34 =
        listOf(
            input(1, "indicator", rgb = true),
            input(2, "player-1", rgb = true),
            input(3, "player-2", rgb = true),
            input(4, "player-3", rgb = true),
            input(5, "player-4", rgb = true),
            input(6, "player-5", rgb = true),
        )

    @Test
    fun `below S there is never a light bar`() {
        assertNull(lightbarLight(listOf(input(1, "RGB", rgb = true)), Build.VERSION_CODES.R))
        assertNull(lightbarLight(dualsenseBelow34, Build.VERSION_CODES.R))
    }

    @Test
    fun `on API 34 plus the RGB-capable input light is the bar`() {
        val lights =
            listOf(
                input(1, "player-1", rgb = false),
                input(2, "indicator", rgb = true),
                FrameworkLight(id = 3, name = "mic_mute", input = false, rgb = true),
            )
        assertEquals(2, lightbarLight(lights, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)?.id)
    }

    @Test
    fun `on API 34 plus a pad with no RGB input light has no bar`() {
        val lights = listOf(input(1, "player-1", rgb = false), input(2, "status", rgb = false))
        assertNull(lightbarLight(lights, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }

    @Test
    fun `on 31 to 33 the composed DualShock 4 RGB light is the bar by name`() {
        // hid-sony composes the DS4 red/green/blue LEDs into one light named "RGB".
        val lights = listOf(input(7, "RGB", rgb = true))
        assertEquals(7, lightbarLight(lights, Build.VERSION_CODES.S)?.id)
        assertEquals(7, lightbarLight(lights, Build.VERSION_CODES.TIRAMISU)?.id)
    }

    @Test
    fun `on 31 to 33 the DualSense indicator is picked over its player LEDs by name`() {
        // The sole-input fallback would fail here (six INPUT lights), so the name match must win.
        assertEquals(1, lightbarLight(dualsenseBelow34, Build.VERSION_CODES.S)?.id)
        assertEquals(1, lightbarLight(dualsenseBelow34, Build.VERSION_CODES.TIRAMISU)?.id)
    }

    @Test
    fun `on 31 to 33 a pad with exactly one input light uses it as the bar`() {
        val lights = listOf(input(4, "some_vendor_led", rgb = false))
        assertEquals(4, lightbarLight(lights, Build.VERSION_CODES.S)?.id)
    }

    @Test
    fun `on 31 to 33 several unnamed input lights are ambiguous and yield no bar`() {
        val lights = listOf(input(4, "led_a", rgb = true), input(5, "led_b", rgb = true))
        assertNull(lightbarLight(lights, Build.VERSION_CODES.S))
    }

    @Test
    fun `a non-input light is never the bar, whatever its RGB flag`() {
        val lights = listOf(FrameworkLight(id = 9, name = "keyboard_backlight", input = false, rgb = true))
        assertNull(lightbarLight(lights, Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
        assertNull(lightbarLight(lights, Build.VERSION_CODES.S))
    }

    @Test
    fun `no lights at all is no bar`() {
        assertNull(lightbarLight(emptyList(), Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
