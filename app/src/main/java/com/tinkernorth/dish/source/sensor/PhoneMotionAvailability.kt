// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// Gyro presence is a fixed hardware fact, resolved once at construction. Not reactive, so it is a
// plain holder rather than a state source.
@Singleton
class PhoneMotionAvailability
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        val hasGyro: Boolean =
            (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
    }
