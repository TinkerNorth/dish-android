// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.store

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import com.tinkernorth.dish.source.sensor.BatteryValidator.BatterySample
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BatteryStatusStore
    @Inject
    constructor() : AbstractStateSource<Map<String, BatterySample>>(emptyMap()) {
        val samples get() = state

        fun put(
            slotId: String,
            sample: BatterySample,
        ) {
            setState { it + (slotId to sample) }
        }

        fun clear(slotId: String) {
            setState { if (slotId in it) it - slotId else it }
        }
    }
