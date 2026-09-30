// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tinkernorth.dish.R

private const val BATTERY_FULL_FLOOR = 90
private const val BATTERY_HIGH_FLOOR = 60
private const val BATTERY_MID_FLOOR = 35

// Charging wins over every level; an unknown level wears the plain outline.
@DrawableRes
internal fun batteryIconRes(battery: BatteryUi): Int {
    if (battery.charging) return R.drawable.ic_battery_charging
    val level = battery.level ?: return R.drawable.ic_battery
    return when {
        level <= 0 -> R.drawable.ic_battery_empty
        level >= BATTERY_FULL_FLOOR -> R.drawable.ic_battery_full
        level >= BATTERY_HIGH_FLOOR -> R.drawable.ic_battery_high
        level >= BATTERY_MID_FLOOR -> R.drawable.ic_battery_mid
        level >= BatteryUi.LOW_THRESHOLD -> R.drawable.ic_battery_low
        else -> R.drawable.ic_battery_critical
    }
}

// The spoken state: low outranks charging, since isLow is already false while charging.
@StringRes
internal fun batteryStateRes(battery: BatteryUi): Int =
    when {
        battery.isLow -> R.string.battery_state_low
        battery.charging -> R.string.battery_state_charging
        else -> R.string.battery_state_discharging
    }
