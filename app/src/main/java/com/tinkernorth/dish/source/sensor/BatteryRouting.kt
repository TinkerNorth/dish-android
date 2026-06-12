// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample

object BatteryRouting {
    data class Routed(
        val display: BatterySample?,
        val wire: BatterySample,
    )

    val UNKNOWN_SAMPLE =
        BatterySample(BatteryValidator.LEVEL_UNKNOWN, BatteryValidator.STATUS_UNKNOWN)

    fun route(
        transport: Transport,
        device: BatterySample?,
        phone: BatterySample?,
    ): Routed {
        val wire =
            if (device == null || transport == Transport.Usb) {
                phone ?: UNKNOWN_SAMPLE
            } else {
                lowest(device, phone)
            }
        return Routed(display = device, wire = wire)
    }

    fun lowest(
        device: BatterySample,
        phone: BatterySample?,
    ): BatterySample {
        if (phone == null) return device
        return if (comparableLevel(phone) < comparableLevel(device)) phone else device
    }

    private fun comparableLevel(sample: BatterySample): Int =
        if (sample.level == BatteryValidator.LEVEL_UNKNOWN) Int.MAX_VALUE else sample.level
}
