// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

// The phone facts that explain a latency or feedback complaint: its battery, how hot it runs,
// whether it saves power, and whether it can be a USB host. None needs a permission.
data class PhoneFacts(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val thermal: String?,
    val powerSave: Boolean?,
    val usbHost: Boolean,
)

private const val NOT_REPORTED = -1
private const val PERCENT = 100

// PowerManager.THERMAL_STATUS_* by value (API 29), named the way the fleet's bundle names them.
private const val THERMAL_NONE = 0
private const val THERMAL_LIGHT = 1
private const val THERMAL_MODERATE = 2
private const val THERMAL_SEVERE = 3
private const val THERMAL_CRITICAL = 4
private const val THERMAL_EMERGENCY = 5
private const val THERMAL_SHUTDOWN = 6

fun thermalWire(status: Int): String? =
    when (status) {
        THERMAL_NONE -> "nominal"
        THERMAL_LIGHT, THERMAL_MODERATE -> "fair"
        THERMAL_SEVERE -> "serious"
        THERMAL_CRITICAL, THERMAL_EMERGENCY, THERMAL_SHUTDOWN -> "critical"
        else -> null
    }

fun batteryPercentOf(
    level: Int,
    scale: Int,
): Int? {
    val reported = level >= 0 && scale > 0
    if (!reported) return null
    return level * PERCENT / scale
}

// BatteryManager.BATTERY_STATUS_* by value.
private const val STATUS_CHARGING = 2
private const val STATUS_DISCHARGING = 3
private const val STATUS_NOT_CHARGING = 4
private const val STATUS_FULL = 5

fun chargingOf(status: Int): Boolean? =
    when (status) {
        STATUS_CHARGING, STATUS_FULL -> true
        STATUS_DISCHARGING, STATUS_NOT_CHARGING -> false
        else -> null
    }

class PhoneFactsReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun read(): PhoneFacts {
            val battery: Intent? =
                ContextCompat.registerReceiver(
                    context,
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            val power = context.getSystemService(PowerManager::class.java)
            return PhoneFacts(
                batteryPercent =
                    battery?.let {
                        batteryPercentOf(
                            it.getIntExtra(BatteryManager.EXTRA_LEVEL, NOT_REPORTED),
                            it.getIntExtra(BatteryManager.EXTRA_SCALE, NOT_REPORTED),
                        )
                    },
                charging = battery?.let { chargingOf(it.getIntExtra(BatteryManager.EXTRA_STATUS, NOT_REPORTED)) },
                thermal = power?.let(::thermalOf),
                powerSave = power?.isPowerSaveMode,
                usbHost = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            )
        }

        // The thermal status arrived in API 29; an older phone cannot say.
        private fun thermalOf(power: PowerManager): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            return thermalWire(power.currentThermalStatus)
        }
    }
