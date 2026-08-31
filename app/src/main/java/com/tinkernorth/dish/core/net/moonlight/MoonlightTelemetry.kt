// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net.moonlight

import java.util.concurrent.ConcurrentHashMap

/**
 * Pure translation from the satellite wire conventions the app's sources
 * already speak (docs/contract.md scales) onto the Moonlight control-stream
 * ones (Wolf control.hpp), so the two transports share every source.
 */
object MoonlightTelemetry {
    // Satellite wire: gyro int16 at ±2000 deg/s full scale, accel int16 at
    // ±4 g. Moonlight wants floats: gyro in deg/s, accel in m/s^2.
    private const val GYRO_SCALE_DEG_S = 2000.0f / 32767.0f
    private const val ACCEL_SCALE_G = 4.0f / 32767.0f
    private const val STANDARD_GRAVITY = 9.80665f

    fun gyroDegS(wire: Short): Float = wire * GYRO_SCALE_DEG_S

    fun accelMs2(wire: Short): Float = wire * ACCEL_SCALE_G * STANDARD_GRAVITY

    // Satellite touch coordinates are full-range int16; Moonlight's are 0..1.
    fun touchNorm(wire: Short): Float = (wire.toInt() + 32768) / 65535.0f

    /**
     * Satellite battery status byte -> Moonlight BATTERY_STATE. Wired (no
     * battery, AC powered) maps to NOT_PRESENT, which hosts treat as "nothing
     * to show", the same thing the satellite does with it.
     */
    fun batteryState(satelliteStatus: Int): Int =
        when (satelliteStatus) {
            1 -> MoonlightControlProtocol.BATTERY_DISCHARGING
            2 -> MoonlightControlProtocol.BATTERY_CHARGING
            3 -> MoonlightControlProtocol.BATTERY_FULL
            4 -> MoonlightControlProtocol.BATTERY_NOT_PRESENT
            else -> MoonlightControlProtocol.BATTERY_STATE_UNKNOWN
        }

    fun batteryPercentage(level: Int): Int = if (level in 0..100) level else MoonlightControlProtocol.BATTERY_PERCENTAGE_UNKNOWN
}

/**
 * Host-requested motion streaming state for one Moonlight session
 * (MOTION_EVENT 0x5501): per (controller number, motion type) the requested
 * report rate, 0 = stop. Senders keep their own cadence; [shouldSend] applies
 * the host's ceiling so a 100 Hz request never receives the phone's 200 Hz.
 * Thread-safe: the pump thread writes, the sensor threads read.
 */
class MoonlightMotionGate {
    private data class Key(
        val controllerNumber: Int,
        val motionType: Int,
    )

    private val rates = ConcurrentHashMap<Key, Int>()
    private val lastSentNs = ConcurrentHashMap<Key, Long>()

    fun onMotionRequest(
        controllerNumber: Int,
        reportRateHz: Int,
        motionType: Int,
    ) {
        val key = Key(controllerNumber, motionType)
        if (reportRateHz <= 0) {
            rates.remove(key)
            lastSentNs.remove(key)
        } else {
            rates[key] = reportRateHz
        }
    }

    fun clear(controllerNumber: Int) {
        rates.keys.removeAll { it.controllerNumber == controllerNumber }
        lastSentNs.keys.removeAll { it.controllerNumber == controllerNumber }
    }

    fun clearAll() {
        rates.clear()
        lastSentNs.clear()
    }

    fun wanted(controllerNumber: Int): Boolean = rates.keys.any { it.controllerNumber == controllerNumber }

    fun wanted(
        controllerNumber: Int,
        motionType: Int,
    ): Boolean = rates.containsKey(Key(controllerNumber, motionType))

    /** True (and marks the send) when a sample of this type is due under the requested rate. */
    fun shouldSend(
        controllerNumber: Int,
        motionType: Int,
        nowNs: Long,
    ): Boolean {
        val key = Key(controllerNumber, motionType)
        val rate = rates[key] ?: return false
        val intervalNs = 1_000_000_000L / rate
        val last = lastSentNs[key]
        if (last != null && nowNs - last < intervalNs) return false
        lastSentNs[key] = nowNs
        return true
    }
}

/**
 * Turns the app's full-state two-finger touch snapshots into the per-pointer
 * DOWN / MOVE / UP events the Moonlight wire wants. Pure and per-pad: feed
 * every snapshot in order, get the events out. A changed tracking id on an
 * active finger is a lift plus a fresh contact, matching how the satellite
 * receiver treats it.
 */
class MoonlightTouchDiffer {
    data class TouchEvent(
        val eventType: Int,
        val pointerId: Int,
        val x: Float,
        val y: Float,
        val pressure: Float,
    )

    private data class FingerState(
        val active: Boolean,
        val id: Int,
        val x: Float,
        val y: Float,
    )

    private var last0 = FingerState(false, 0, 0f, 0f)
    private var last1 = FingerState(false, 0, 0f, 0f)

    fun reset() {
        last0 = FingerState(false, 0, 0f, 0f)
        last1 = FingerState(false, 0, 0f, 0f)
    }

    @Suppress("LongParameterList")
    fun diff(
        finger0Active: Boolean,
        finger0Id: Int,
        finger0X: Float,
        finger0Y: Float,
        finger1Active: Boolean,
        finger1Id: Int,
        finger1X: Float,
        finger1Y: Float,
    ): List<TouchEvent> {
        val out = ArrayList<TouchEvent>(2)
        last0 = diffFinger(last0, FingerState(finger0Active, finger0Id, finger0X, finger0Y), out)
        last1 = diffFinger(last1, FingerState(finger1Active, finger1Id, finger1X, finger1Y), out)
        return out
    }

    private fun diffFinger(
        prev: FingerState,
        cur: FingerState,
        out: MutableList<TouchEvent>,
    ): FingerState {
        when {
            !prev.active && cur.active ->
                out += TouchEvent(MoonlightControlProtocol.TOUCH_EVENT_DOWN, cur.id, cur.x, cur.y, 1.0f)
            prev.active && !cur.active ->
                out += TouchEvent(MoonlightControlProtocol.TOUCH_EVENT_UP, prev.id, prev.x, prev.y, 0.0f)
            prev.active && cur.active && prev.id != cur.id -> {
                out += TouchEvent(MoonlightControlProtocol.TOUCH_EVENT_UP, prev.id, prev.x, prev.y, 0.0f)
                out += TouchEvent(MoonlightControlProtocol.TOUCH_EVENT_DOWN, cur.id, cur.x, cur.y, 1.0f)
            }
            prev.active && cur.active && (prev.x != cur.x || prev.y != cur.y) ->
                out += TouchEvent(MoonlightControlProtocol.TOUCH_EVENT_MOVE, cur.id, cur.x, cur.y, 1.0f)
        }
        return cur
    }
}
