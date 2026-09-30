// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import com.tinkernorth.dish.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryGlyphTest {
    private fun battery(
        level: Int?,
        charging: Boolean = false,
    ) = BatteryUi(level = level, charging = charging)

    @Test
    fun `charging wins over every level`() {
        assertEquals(R.drawable.ic_battery_charging, batteryIconRes(battery(3, charging = true)))
        assertEquals(R.drawable.ic_battery_charging, batteryIconRes(battery(null, charging = true)))
    }

    @Test
    fun `an unknown level wears the plain outline`() {
        assertEquals(R.drawable.ic_battery, batteryIconRes(battery(null)))
    }

    @Test
    fun `each level band has its own glyph, floors included`() {
        assertEquals(R.drawable.ic_battery_empty, batteryIconRes(battery(0)))
        assertEquals(R.drawable.ic_battery_critical, batteryIconRes(battery(BatteryUi.LOW_THRESHOLD - 1)))
        assertEquals(R.drawable.ic_battery_low, batteryIconRes(battery(BatteryUi.LOW_THRESHOLD)))
        assertEquals(R.drawable.ic_battery_low, batteryIconRes(battery(34)))
        assertEquals(R.drawable.ic_battery_mid, batteryIconRes(battery(35)))
        assertEquals(R.drawable.ic_battery_mid, batteryIconRes(battery(59)))
        assertEquals(R.drawable.ic_battery_high, batteryIconRes(battery(60)))
        assertEquals(R.drawable.ic_battery_high, batteryIconRes(battery(89)))
        assertEquals(R.drawable.ic_battery_full, batteryIconRes(battery(90)))
        assertEquals(R.drawable.ic_battery_full, batteryIconRes(battery(100)))
    }

    @Test
    fun `the spoken state is low, then charging, then discharging`() {
        assertEquals(R.string.battery_state_low, batteryStateRes(battery(5)))
        assertEquals(R.string.battery_state_charging, batteryStateRes(battery(5, charging = true)))
        assertEquals(R.string.battery_state_discharging, batteryStateRes(battery(50)))
        assertEquals(R.string.battery_state_discharging, batteryStateRes(battery(null)))
    }
}
