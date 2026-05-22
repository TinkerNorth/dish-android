// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped fact: does the phone itself have a usable gyroscope?
 *
 * The activity-scoped [PhoneMotionSource] already exposes this as its
 * `isAvailable` property, but the satellite registration path and the
 * [com.tinkernorth.dish.composer.MotionCapabilityComposer] need the same fact
 * *without* an overlay being on screen — capability negotiation at controller
 * add time happens long before the overlay activity is constructed.
 *
 * Implemented as an [AbstractStateSource]`<Boolean>` so it composes through the
 * existing `state` flow plumbing. The value is computed once at injection time
 * because Android device hardware does not change at runtime; the source still
 * extends the base class purely so consumers can `.state.collect { … }`
 * uniformly with the other availability flows.
 */
@Singleton
class PhoneMotionAvailability
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AbstractStateSource<Boolean>(initialState = false) {
        init {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val hasGyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            setState(hasGyro)
        }
    }
