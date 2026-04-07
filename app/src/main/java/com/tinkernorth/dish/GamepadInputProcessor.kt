package com.tinkernorth.dish

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Processes physical gamepad input (joystick axes + button keys) and
 * produces XUSB-compatible report values.
 *
 * Owns all mutable axis/button state. The activity delegates
 * [dispatchKeyEvent] and [dispatchGenericMotionEvent] here.
 *
 * Pure logic — no Android UI, no coroutines, no SatelliteNative calls.
 * Call [setSendCallback] to receive report-ready values.
 */
class GamepadInputProcessor {

    /** Callback invoked on the caller's thread whenever a report should be sent. */
    fun interface ReportSender {
        fun send(wButtons: Int, bLT: Int, bRT: Int, sLX: Int, sLY: Int, sRX: Int, sRY: Int)
    }

    /** Callback invoked when a telemetry-relevant input event occurs. */
    fun interface TelemetryListener {
        fun onInputEvent(eventCount: Int, sampleCount: Int, sendCount: Int)
    }

    var reportSender: ReportSender? = null
    var telemetryListener: TelemetryListener? = null

    // ── Axis / button state (main-thread only) ────────────────────────────
    internal var wButtons = 0; private set
    internal var bLT = 0; private set
    internal var bRT = 0; private set
    internal var sLX = 0; private set
    internal var sLY = 0; private set
    internal var sRX = 0; private set
    internal var sRY = 0; private set
    private var ltFromKey = false
    private var rtFromKey = false

    // ── Telemetry counters (drained by TelemetryTracker) ──────────────────
    var telEventCount = 0; internal set
    var telSampleCount = 0; internal set
    var telSendCount = 0; internal set
    var telTotalSent = 0L; internal set
    var telLastEventMs = 0L; internal set

    companion object {
        private const val AXIS_MAX = 32767f
        private const val TRIGGER_MAX = 255f

        @JvmField
        val BUTTON_MAP = mapOf(
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

    /**
     * Returns `true` if the event was consumed; `null` if the caller should
     * fall through to `super.dispatchKeyEvent`.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean? {
        if (event.source and InputDevice.SOURCE_GAMEPAD == 0 &&
            event.source and InputDevice.SOURCE_JOYSTICK == 0
        ) return null
        return if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event.keyCode)
        else handleKeyUp(event.keyCode)
    }

    /**
     * Returns `true` if the event was consumed; `null` if the caller should
     * fall through to `super.dispatchGenericMotionEvent`.
     */
    fun handleMotionEvent(event: MotionEvent): Boolean? {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return null
        return when (event.action) {
            MotionEvent.ACTION_CANCEL -> { zeroAxes(); trySend(); true }
            MotionEvent.ACTION_MOVE -> {
                telEventCount++
                telLastEventMs = android.os.SystemClock.elapsedRealtime()
                for (i in 0 until event.historySize) processJoystickInput(event, i)
                processJoystickInput(event, -1)
                true
            }
            else -> null
        }
    }

    fun zeroAxes() {
        sLX = 0; sLY = 0; sRX = 0; sRY = 0
        bLT = 0; bRT = 0; wButtons = 0
        ltFromKey = false; rtFromKey = false
    }

    fun trySend() {
        reportSender?.send(wButtons, bLT, bRT, sLX, sLY, sRX, sRY)
        telSendCount++
        telTotalSent++
    }

    /** Reset per-second telemetry counters and return their values. */
    fun drainTelemetry(): TelemetrySnapshot {
        val snap = TelemetrySnapshot(telEventCount, telSampleCount, telSendCount, telTotalSent)
        telEventCount = 0; telSampleCount = 0; telSendCount = 0
        return snap
    }

    data class TelemetrySnapshot(
        val events: Int, val samples: Int, val sends: Int, val totalSent: Long,
    )

    // ── Internal ──────────────────────────────────────────────────────────

    internal fun handleKeyDown(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { ltFromKey = true; bLT = 255; trySend(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rtFromKey = true; bRT = 255; trySend(); return true }
        }
        BUTTON_MAP[keyCode]?.let { bit -> wButtons = wButtons or bit; trySend(); return true }
        return false
    }

    internal fun handleKeyUp(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { ltFromKey = false; bLT = 0; trySend(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rtFromKey = false; bRT = 0; trySend(); return true }
        }
        BUTTON_MAP[keyCode]?.let { bit -> wButtons = wButtons and bit.inv(); trySend(); return true }
        return false
    }

    @Suppress("MagicNumber")
    internal fun processJoystickInput(event: MotionEvent, histPos: Int) {
        fun axis(a: Int) =
            if (histPos < 0) event.getAxisValue(a) else event.getHistoricalAxisValue(a, histPos)

        fun flat(a: Int) = event.device?.getMotionRange(a, event.source)?.flat ?: 0f
        fun dead(v: Float, a: Int) = if (kotlin.math.abs(v) > flat(a)) v else 0f

        sLX = scaleAxis(dead(axis(MotionEvent.AXIS_X), MotionEvent.AXIS_X), AXIS_MAX)
        sLY = scaleAxis(dead(axis(MotionEvent.AXIS_Y), MotionEvent.AXIS_Y), -AXIS_MAX)
        sRX = scaleAxis(dead(axis(MotionEvent.AXIS_Z), MotionEvent.AXIS_Z), AXIS_MAX)
        sRY = scaleAxis(dead(axis(MotionEvent.AXIS_RZ), MotionEvent.AXIS_RZ), -AXIS_MAX)

        if (!ltFromKey) {
            bLT = scaleTrigger(
                maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE)),
                TRIGGER_MAX,
            )
        }
        if (!rtFromKey) {
            bRT = scaleTrigger(
                maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS)),
                TRIGGER_MAX,
            )
        }

        val hx = axis(MotionEvent.AXIS_HAT_X)
        val hy = axis(MotionEvent.AXIS_HAT_Y)
        wButtons = wButtons and 0x000F.inv()
        if (hx < -0.5f) wButtons = wButtons or 0x0004
        if (hx > 0.5f) wButtons = wButtons or 0x0008
        if (hy < -0.5f) wButtons = wButtons or 0x0001
        if (hy > 0.5f) wButtons = wButtons or 0x0002
        telSampleCount++
        trySend()
    }
}

// ── Pure helpers (top-level, easily testable) ────────────────────────────

/** Scale a -1..1 axis float to a clamped 16-bit signed integer. */
fun scaleAxis(value: Float, max: Float): Int =
    (value * max).toInt().coerceIn(-32768, 32767)

/** Scale a 0..1 trigger float to a clamped 8-bit unsigned integer. */
fun scaleTrigger(value: Float, max: Float): Int =
    (value * max).toInt().coerceIn(0, 255)

