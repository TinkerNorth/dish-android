// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample

data class Routed(
    val display: BatterySample?,
    val wire: BatterySample,
)
