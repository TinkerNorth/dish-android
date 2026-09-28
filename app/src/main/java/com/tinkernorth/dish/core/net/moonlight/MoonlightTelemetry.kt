// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.net.moonlight

// Pure translation from the satellite wire conventions the app's sources
// already speak (docs/contract.md scales) onto the Moonlight control-stream
// ones (Wolf control.hpp), so the two transports share every source.

// Satellite wire: gyro int16 at ±2000 deg/s full scale, accel int16 at
// ±4 g. Moonlight wants floats: gyro in deg/s, accel in m/s^2.
private const val GYRO_SCALE_DEG_S = 2000.0f / 32767.0f
private const val ACCEL_SCALE_G = 4.0f / 32767.0f
private const val STANDARD_GRAVITY = 9.80665f
private const val INT16_OFFSET = 32768
private const val INT16_SPAN = 65535.0f

// The satellite's MSG_BATTERY status byte (docs/contract.md).
private const val WIRE_BATTERY_DISCHARGING = 1
private const val WIRE_BATTERY_CHARGING = 2
private const val WIRE_BATTERY_FULL = 3
private const val WIRE_BATTERY_WIRED = 4
private const val PERCENT_MAX = 100

private const val NS_PER_SECOND = 1_000_000_000L
private const val PRESSURE_TOUCHING = 1.0f
private const val PRESSURE_LIFTED = 0.0f

fun gyroDegS(wire: Short): Float = wire * GYRO_SCALE_DEG_S

fun accelMs2(wire: Short): Float = wire * ACCEL_SCALE_G * STANDARD_GRAVITY

// Satellite touch coordinates are full-range int16; Moonlight's are 0..1.
fun touchNorm(wire: Short): Float = (wire.toInt() + INT16_OFFSET) / INT16_SPAN

/**
 * Satellite battery status byte -> Moonlight BATTERY_STATE. Wired (no
 * battery, AC powered) maps to NOT_PRESENT, which hosts treat as "nothing
 * to show", the same thing the satellite does with it.
 */
fun batteryState(satelliteStatus: Int): Int =
    when (satelliteStatus) {
        WIRE_BATTERY_DISCHARGING -> BATTERY_DISCHARGING
        WIRE_BATTERY_CHARGING -> BATTERY_CHARGING
        WIRE_BATTERY_FULL -> BATTERY_FULL
        WIRE_BATTERY_WIRED -> BATTERY_NOT_PRESENT
        else -> BATTERY_STATE_UNKNOWN
    }

fun batteryPercentage(level: Int): Int = if (level in 0..PERCENT_MAX) level else BATTERY_PERCENTAGE_UNKNOWN

/**
 * Host-requested motion streaming state for one Moonlight session
 * (MOTION_EVENT 0x5501): per (controller number, motion type) the requested
 * report rate, 0 = stop. Senders keep their own cadence; [shouldSend] applies
 * the host's ceiling so a 100 Hz request never receives the phone's 200 Hz.
 * Thread-safe: the pump thread and the pad table write, the sensor threads read.
 */
class MoonlightMotionGate {
    // Copy on write: a host request or a released pad swaps the array under [writeLock]; a
    // sample's checks scan the array they read, with no lock and nothing built.
    @Volatile private var streams = arrayOf<MotionStream>()
    private val writeLock = Any()

    fun onMotionRequest(
        controllerNumber: Int,
        reportRateHz: Int,
        motionType: Int,
    ) {
        synchronized(writeLock) {
            val held = streamFor(controllerNumber, motionType)
            when {
                reportRateHz <= 0 -> stop(held)
                held != null -> held.rateHz = reportRateHz
                else -> streams += MotionStream(controllerNumber, motionType, reportRateHz)
            }
        }
    }

    // Under [writeLock]. A stream started again later paces from scratch.
    private fun stop(held: MotionStream?) {
        if (held == null) return
        streams = streams.filter { it !== held }.toTypedArray()
    }

    fun clear(controllerNumber: Int) {
        synchronized(writeLock) {
            streams = streams.filter { it.controllerNumber != controllerNumber }.toTypedArray()
        }
    }

    fun clearAll() {
        synchronized(writeLock) {
            streams = arrayOf()
        }
    }

    fun wanted(controllerNumber: Int): Boolean = streams.any { it.controllerNumber == controllerNumber }

    fun wanted(
        controllerNumber: Int,
        motionType: Int,
    ): Boolean = streamFor(controllerNumber, motionType) != null

    /** True (and marks the send) when a sample of this type is due under the requested rate. */
    fun shouldSend(
        controllerNumber: Int,
        motionType: Int,
        nowNs: Long,
    ): Boolean {
        val stream = streamFor(controllerNumber, motionType) ?: return false
        return stream.admit(nowNs)
    }

    private fun streamFor(
        controllerNumber: Int,
        motionType: Int,
    ): MotionStream? =
        streams.firstOrNull { it.controllerNumber == controllerNumber && it.motionType == motionType }
}

// One (controller number, motion type) the host asked for, and when a sample of it last went out.
private class MotionStream(
    val controllerNumber: Int,
    val motionType: Int,
    @Volatile var rateHz: Int,
) {
    @Volatile private var hasSent = false

    @Volatile private var lastSentNs = 0L

    fun admit(nowNs: Long): Boolean {
        val intervalNs = NS_PER_SECOND / rateHz
        val isTooSoon = hasSent && nowNs - lastSentNs < intervalNs
        if (isTooSoon) return false
        lastSentNs = nowNs
        hasSent = true
        return true
    }
}

// The two fingers a touch frame carries, as [MoonlightTouchDiffer.diff] names them.
const val FIRST_FINGER = 0
const val SECOND_FINGER = 1
private const val FINGERS = 2

/** Where a [MoonlightTouchDiffer] hands each event it finds, in the order the wire carries them. */
fun interface MoonlightTouchSink {
    fun sendControllerTouch(
        controllerNumber: Int,
        eventType: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    )
}

/**
 * Turns the app's full-state two-finger touch snapshots into the per-pointer
 * DOWN / MOVE / UP events the Moonlight wire wants. Per-pad: feed each
 * snapshot's fingers in order and the events go to the sink as they are found;
 * the differ keeps each finger's last contact in place and builds nothing. A
 * changed tracking id on an active finger is a lift plus a fresh contact,
 * matching how the satellite receiver treats it.
 */
class MoonlightTouchDiffer {
    private val contacts = Array(FINGERS) { HeldContact() }

    fun reset() {
        for (contact in contacts) contact.forget()
    }

    /** [finger]'s contact in this snapshot against its last, its events to [sink] for [controllerNumber]. */
    fun diff(
        finger: Int,
        active: Boolean,
        id: Int,
        x: Float,
        y: Float,
        controllerNumber: Int,
        sink: MoonlightTouchSink,
    ) {
        val held = contacts[finger]
        val landed = !held.active && active
        val lifted = held.active && !active
        val bothActive = held.active && active
        val retracked = bothActive && held.id != id
        val moved = bothActive && (held.x != x || held.y != y)
        when {
            landed -> sink.sendControllerTouch(controllerNumber, TOUCH_EVENT_DOWN, id, x, y, PRESSURE_TOUCHING)
            lifted -> held.sendUp(controllerNumber, sink)
            retracked -> {
                held.sendUp(controllerNumber, sink)
                sink.sendControllerTouch(controllerNumber, TOUCH_EVENT_DOWN, id, x, y, PRESSURE_TOUCHING)
            }
            moved -> sink.sendControllerTouch(controllerNumber, TOUCH_EVENT_MOVE, id, x, y, PRESSURE_TOUCHING)
        }
        held.hold(active, id, x, y)
    }
}

// One finger's last contact, overwritten by each snapshot once its events are out.
private class HeldContact {
    var active = false
        private set
    var id = 0
        private set
    var x = 0f
        private set
    var y = 0f
        private set

    fun hold(
        active: Boolean,
        id: Int,
        x: Float,
        y: Float,
    ) {
        this.active = active
        this.id = id
        this.x = x
        this.y = y
    }

    fun forget() = hold(active = false, id = 0, x = 0f, y = 0f)

    // The lift names the contact as it was last held, not as the snapshot that ended it.
    fun sendUp(
        controllerNumber: Int,
        sink: MoonlightTouchSink,
    ) {
        sink.sendControllerTouch(controllerNumber, TOUCH_EVENT_UP, id, x, y, PRESSURE_LIFTED)
    }
}
