// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import com.tinkernorth.dish.core.jni.SatelliteNative
import kotlin.math.roundToInt

object MotionScaling {
    // m/s²
    const val GRAVITY_MSS = 9.80665

    // deg/s
    const val GYRO_FULL_SCALE_DEG = 2000.0

    // g
    const val ACCEL_FULL_SCALE_G = 4.0

    private const val RAD_TO_DEG = 180.0 / Math.PI
    private const val INT16_MAX = 32767
    private const val INT16_MIN = -32768

    fun gyroRadToWire(radPerSec: Float): Short {
        val degPerSec = radPerSec * RAD_TO_DEG
        val scaled = (degPerSec / GYRO_FULL_SCALE_DEG * INT16_MAX).roundToInt()
        return scaled.coerceIn(INT16_MIN, INT16_MAX).toShort()
    }

    fun accelMssToWire(mss: Float): Short {
        val g = mss / GRAVITY_MSS
        val scaled = (g / ACCEL_FULL_SCALE_G * INT16_MAX).roundToInt()
        return scaled.coerceIn(INT16_MIN, INT16_MAX).toShort()
    }

    sealed interface RemapResult {
        data object Mapped : RemapResult

        data class Fallback(
            val unknownRotation: Int,
        ) : RemapResult
    }

    fun remapLandscape(
        deviceX: Float,
        deviceY: Float,
        deviceZ: Float,
        rotation: Int,
        out: FloatArray,
    ): RemapResult {
        require(out.size >= 3) { "out must have at least 3 elements; got ${out.size}" }
        return when (rotation) {
            ROTATION_0 -> {
                out[0] = deviceX
                out[1] = deviceY
                out[2] = deviceZ
                RemapResult.Mapped
            }
            ROTATION_90 -> {
                out[0] = deviceY
                out[1] = -deviceX
                out[2] = deviceZ
                RemapResult.Mapped
            }
            ROTATION_180 -> {
                out[0] = -deviceX
                out[1] = -deviceY
                out[2] = deviceZ
                RemapResult.Mapped
            }
            ROTATION_270 -> {
                out[0] = -deviceY
                out[1] = deviceX
                out[2] = deviceZ
                RemapResult.Mapped
            }
            else -> {
                out[0] = deviceY
                out[1] = -deviceX
                out[2] = deviceZ
                RemapResult.Fallback(rotation)
            }
        }
    }

    fun remapLandscape(
        deviceX: Float,
        deviceY: Float,
        deviceZ: Float,
        rotation: Int,
    ): FloatArray {
        val out = FloatArray(3)
        remapLandscape(deviceX, deviceY, deviceZ, rotation, out)
        return out
    }

    // Mirror of android.view.Surface.ROTATION_* — duplicated to keep this pure-JVM testable.
    private const val ROTATION_0 = 0
    private const val ROTATION_90 = 1
    private const val ROTATION_180 = 2
    private const val ROTATION_270 = 3
}
