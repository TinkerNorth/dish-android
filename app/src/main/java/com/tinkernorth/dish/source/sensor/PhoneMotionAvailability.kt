// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

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
