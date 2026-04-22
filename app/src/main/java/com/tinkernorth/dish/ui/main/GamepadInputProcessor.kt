package com.tinkernorth.dish.ui.main

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Processes physical gamepad input (joystick axes + button keys) and
 * produces XUSB-compatible report values.
 *
 * Owns all mutable axis/button state, keyed by [android.view.InputDevice]
 * id so two physical controllers can be active side-by-side without their
 * inputs bleeding into each other's reports. The activity delegates
 * [dispatchKeyEvent] and [dispatchGenericMotionEvent] here.
 *
 * Pure logic — no Android UI, no coroutines, no SatelliteNative calls.
 * Call [setSendCallback] to receive report-ready values.
 */
class GamepadInputProcessor {
    /** Callback invoked on the caller's thread whenever a report should be sent. */
    fun interface ReportSender {
        fun send(
            deviceId: Int,
            wButtons: Int,
            bLT: Int,
            bRT: Int,
            sLX: Int,
            sLY: Int,
            sRX: Int,
            sRY: Int,
        )
    }

    var reportSender: ReportSender? = null

    /**
     * Per-device axis/button state. Main-thread only; allocation happens
     * once per new [android.view.InputDevice] id (rare) and every
     * subsequent event is a map lookup on the hot path.
     */
    private class DeviceState {
        var wButtons = 0
        var bLT = 0
        var bRT = 0
        var sLX = 0
        var sLY = 0
        var sRX = 0
        var sRY = 0
        var ltFromKey = false
        var rtFromKey = false
    }

    private val states = HashMap<Int, DeviceState>()

    // ── Last-sent snapshot (read by TelemetryTracker) ─────────────────────
    var wButtons = 0
        internal set
    var bLT = 0
        internal set
    var bRT = 0
        internal set
    var sLX = 0
        internal set
    var sLY = 0
        internal set
    var sRX = 0
        internal set
    var sRY = 0
        internal set

    // ── Telemetry counters (drained by TelemetryTracker) ──────────────────
    var telEventCount = 0
        internal set
    var telSampleCount = 0
        internal set
    var telSendCount = 0
        internal set
    var telTotalSent = 0L
        internal set
    var telLastEventMs = 0L
        internal set

    companion object {
        private const val AXIS_MAX = 32767f
        private const val TRIGGER_MAX = 255f

        @JvmField
        val BUTTON_MAP =
            mapOf(
                KeyEvent.KEYCODE_BUTTON_A to 0x1000,
                KeyEvent.KEYCODE_BUTTON_B to 0x2000,
                KeyEvent.KEYCODE_BUTTON_X to 0x4000,
                KeyEvent.KEYCODE_BUTTON_Y to 0x8000,
                KeyEvent.KEYCODE_BUTTON_L1 to 0x0100,
                KeyEvent.KEYCODE_BUTTON_R1 to 0x0200,
                KeyEvent.KEYCODE_BUTTON_THUMBL to 0x0040,
                KeyEvent.KEYCODE_BUTTON_THUMBR to 0x0080,
                KeyEvent.KEYCODE_BUTTON_START to 0x0010,
                KeyEvent.KEYCODE_BUTTON_SELECT to 0x0020,
                KeyEvent.KEYCODE_DPAD_UP to 0x0001,
                KeyEvent.KEYCODE_DPAD_DOWN to 0x0002,
                KeyEvent.KEYCODE_DPAD_LEFT to 0x0004,
                KeyEvent.KEYCODE_DPAD_RIGHT to 0x0008,
            )
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Device id of the most recently processed event; drives the no-arg [trySend]. */
    private var lastDeviceId = 0

    /**
     * Returns `true` if the event was consumed; `null` if the caller should
     * fall through to `super.dispatchKeyEvent`.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean? {
        if (event.source and InputDevice.SOURCE_GAMEPAD == 0 &&
            event.source and InputDevice.SOURCE_JOYSTICK == 0
        ) {
            return null
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleKeyDown(event.keyCode, event.deviceId)
            KeyEvent.ACTION_UP -> handleKeyUp(event.keyCode, event.deviceId)
            // ACTION_MULTIPLE (deprecated) and any future action stay un-consumed
            // instead of falling through the old if/else as a phantom KEY_UP.
            else -> null
        }
    }

    /**
     * Returns `true` if the event was consumed; `null` if the caller should
     * fall through to `super.dispatchGenericMotionEvent`.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean? {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
        return when (event.action) {
            MotionEvent.ACTION_CANCEL -> {
                val s = states.getOrPut(event.deviceId) { DeviceState() }
                zeroState(s)
                publishFrom(event.deviceId, s)
                trySend()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                telEventCount++
                telLastEventMs = android.os.SystemClock.elapsedRealtime()
                val s = states.getOrPut(event.deviceId) { DeviceState() }
                for (i in 0 until event.historySize) processJoystickInput(event, s, event.deviceId, i)
                processJoystickInput(event, s, event.deviceId, -1)
                true
            }
            else -> null
        }
    }

    /** Clear every per-device state and the last-sent snapshot (no emit). */
    fun zeroAxes() {
        states.clear()
        wButtons = 0
        bLT = 0
        bRT = 0
        sLX = 0
        sLY = 0
        sRX = 0
        sRY = 0
    }

    /**
     * Zero every per-device state and emit a release-all report per device.
     * Used on window-focus-lost so no button stays held server-side on any
     * live connection.
     */
    fun zeroAndSendAll() {
        val ids = states.keys.toList()
        if (ids.isEmpty()) {
            zeroAxes()
            return
        }
        for (id in ids) {
            val s = states.getValue(id)
            zeroState(s)
            publishFrom(id, s)
            trySend()
        }
    }

    fun trySend() {
        reportSender?.send(lastDeviceId, wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
        telSendCount++
        telTotalSent++
    }

    /** Reset per-second telemetry counters and return their values. */
    fun drainTelemetry(): TelemetrySnapshot {
        val snap = TelemetrySnapshot(telEventCount, telSampleCount, telSendCount, telTotalSent)
        telEventCount = 0
        telSampleCount = 0
        telSendCount = 0
        return snap
    }

    data class TelemetrySnapshot(
        val events: Int,
        val samples: Int,
        val sends: Int,
        val totalSent: Long,
    )

    // ── Internal ──────────────────────────────────────────────────────────

    /** Publish [s] as the last-sent snapshot; the no-arg [trySend] reads from here. */
    private fun publishFrom(
        deviceId: Int,
        s: DeviceState,
    ) {
        lastDeviceId = deviceId
        wButtons = s.wButtons
        bLT = s.bLT
        bRT = s.bRT
        sLX = s.sLX
        sLY = s.sLY
        sRX = s.sRX
        sRY = s.sRY
    }

    private fun zeroState(s: DeviceState) {
        s.wButtons = 0
        s.bLT = 0
        s.bRT = 0
        s.sLX = 0
        s.sLY = 0
        s.sRX = 0
        s.sRY = 0
        s.ltFromKey = false
        s.rtFromKey = false
    }

    internal fun handleKeyDown(
        keyCode: Int,
        deviceId: Int = 0,
    ): Boolean {
        val s = states.getOrPut(deviceId) { DeviceState() }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                s.ltFromKey = true
                s.bLT = 255
                publishFrom(deviceId, s)
                trySend()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                s.rtFromKey = true
                s.bRT = 255
                publishFrom(deviceId, s)
                trySend()
                return true
            }
        }
        BUTTON_MAP[keyCode]?.let { bit ->
            s.wButtons = s.wButtons or bit
            publishFrom(deviceId, s)
            trySend()
            return true
        }
        return false
    }

    internal fun handleKeyUp(
        keyCode: Int,
        deviceId: Int = 0,
    ): Boolean {
        val s = states.getOrPut(deviceId) { DeviceState() }
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                s.ltFromKey = false
                s.bLT = 0
                publishFrom(deviceId, s)
                trySend()
                return true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                s.rtFromKey = false
                s.bRT = 0
                publishFrom(deviceId, s)
                trySend()
                return true
            }
        }
        BUTTON_MAP[keyCode]?.let { bit ->
            s.wButtons = s.wButtons and bit.inv()
            publishFrom(deviceId, s)
            trySend()
            return true
        }
        return false
    }

    @Suppress("MagicNumber")
    private fun processJoystickInput(
        event: MotionEvent,
        s: DeviceState,
        deviceId: Int,
        histPos: Int,
    ) {
        fun axis(a: Int) = if (histPos < 0) event.getAxisValue(a) else event.getHistoricalAxisValue(a, histPos)

        fun flat(a: Int) = event.device?.getMotionRange(a, event.source)?.flat ?: 0f

        fun dead(
            v: Float,
            a: Int,
        ) = if (kotlin.math.abs(v) > flat(a)) v else 0f

        s.sLX = scaleAxis(dead(axis(MotionEvent.AXIS_X), MotionEvent.AXIS_X), AXIS_MAX)
        s.sLY = scaleAxis(dead(axis(MotionEvent.AXIS_Y), MotionEvent.AXIS_Y), -AXIS_MAX)
        s.sRX = scaleAxis(dead(axis(MotionEvent.AXIS_Z), MotionEvent.AXIS_Z), AXIS_MAX)
        s.sRY = scaleAxis(dead(axis(MotionEvent.AXIS_RZ), MotionEvent.AXIS_RZ), -AXIS_MAX)

        if (!s.ltFromKey) {
            s.bLT =
                scaleTrigger(
                    maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)),
                    TRIGGER_MAX,
                )
        }
        if (!s.rtFromKey) {
            s.bRT =
                scaleTrigger(
                    maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)),
                    TRIGGER_MAX,
                )
        }

        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        s.wButtons = s.wButtons and 0x000F.inv()
        if (hx < -0.5f) s.wButtons = s.wButtons or 0x0004
        if (hx > 0.5f) s.wButtons = s.wButtons or 0x0008
        if (hy < -0.5f) s.wButtons = s.wButtons or 0x0001
        if (hy > 0.5f) s.wButtons = s.wButtons or 0x0002
        publishFrom(deviceId, s)
        telSampleCount++
        trySend()
    }
}

// ── Pure helpers (top-level, easily testable) ────────────────────────────

/** Scale a -1..1 axis float to a clamped 16-bit signed integer. */
fun scaleAxis(
    value: Float,
    max: Float,
): Int = (value * max).toInt().coerceIn(-32768, 32767)

/** Scale a 0..1 trigger float to a clamped 8-bit unsigned integer. */
fun scaleTrigger(
    value: Float,
    max: Float,
): Int = (value * max).toInt().coerceIn(0, 255)
