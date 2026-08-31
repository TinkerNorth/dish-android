// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightMotionGate
import com.tinkernorth.dish.core.net.moonlight.MoonlightTelemetry
import com.tinkernorth.dish.core.net.moonlight.MoonlightTouchDiffer
import com.tinkernorth.dish.source.connection.TelemetrySink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One Moonlight host session, the sibling of
 * [com.tinkernorth.dish.source.connection.SatelliteConnection]. Holds the live
 * control session and forwards the on-screen (and, once bound natively, the
 * physical) controller state to it. The manager owns pairing and launch; this
 * class owns the live-session lifecycle and the hot send path.
 *
 * ONE SESSION PER HOST, REFERENCE COUNTED BY THE BINDINGS POINTING AT IT. A
 * Moonlight session carries up to [MAX_PADS] controllers on one stream, so the
 * first binding starts (or joins) it and settles the app, later bindings only
 * announce their own pad, and the last unbind is what tears it down.
 */
class MoonlightConnection(
    val id: String,
    host: MoonlightHost,
    private val scope: CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) : TelemetrySink {
    private val _host = MutableStateFlow(host)
    val host: StateFlow<MoonlightHost> = _host.asStateFlow()

    private val _state = MutableStateFlow(MoonlightSessionState.Idle)
    val state: StateFlow<MoonlightSessionState> = _state.asStateFlow()

    private val _pads = MutableStateFlow<Map<String, MoonlightPad>>(emptyMap())
    val pads: StateFlow<Map<String, MoonlightPad>> = _pads.asStateFlow()

    @Volatile private var session: MoonlightControlSession? = null
    private var pumpJob: Job? = null

    @Volatile private var pinger: UdpMediaPinger? = null
    private var pingJob: Job? = null

    private val padLock = Any()

    // The app the session actually settled on, so a later binding can say what it
    // is joining instead of guessing from the remembered pick.
    @Volatile var sessionAppId: String? = null
        private set

    @Volatile var sessionAppName: String? = null
        private set

    // Inbound feedback (rumble/LED/motion request) surfaced to the same plumbing
    // the satellite path uses; the manager wires the actual sinks.
    @Volatile var onFeedback: (MoonlightEvent) -> Unit = {}

    fun updateHost(host: MoonlightHost) {
        _host.value = host
    }

    fun markLaunching() {
        if (_state.value == MoonlightSessionState.Live) return
        _state.value = MoonlightSessionState.Launching
    }

    /**
     * Take the lowest free controller number in `0..3` for [slotId], or null when
     * the host already carries its four. A slot that already holds one keeps it:
     * the host skips a CONTROLLER_ARRIVAL for a number it has seen, so a live
     * index is never handed out twice.
     */
    fun acquirePad(
        slotId: String,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ): MoonlightPad? {
        val pad =
            synchronized(padLock) {
                _pads.value[slotId]?.let { return@synchronized it }
                val taken = _pads.value.values.mapTo(mutableSetOf()) { it.number }
                val free = (0 until MAX_PADS).firstOrNull { it !in taken } ?: return@synchronized null
                val fresh =
                    MoonlightPad(
                        slotId = slotId,
                        number = free,
                        emulatedType = emulatedType,
                        capabilities = capabilities,
                        supportedButtons = supportedButtons,
                    )
                _pads.value = _pads.value + (slotId to fresh)
                fresh
            } ?: return null
        announce(pad)
        return pad
    }

    /** Drop [slotId] from the session and report how many pads remain. */
    fun releasePad(slotId: String): Int {
        val released: MoonlightPad?
        val remaining =
            synchronized(padLock) {
                released = _pads.value[slotId]
                if (released == null) return@synchronized _pads.value.size
                _pads.value = _pads.value - slotId
                _pads.value.size
            }
        released?.let { pad ->
            motionGate.clear(pad.number)
            touchDiffers.remove(slotId)
            lastPadFrames.remove(pad.number)
            touchClickByNumber.remove(pad.number)
        }
        withdraw()
        return remaining
    }

    fun padFor(slotId: String): MoonlightPad? = _pads.value[slotId]

    val padCount: Int get() = _pads.value.size

    val hasRoom: Boolean get() = _pads.value.size < MAX_PADS

    fun activeMask(): Int = _pads.value.values.fold(0) { mask, pad -> mask or (1 shl pad.number) }

    /**
     * Start pinging the host's media ports. Runs from the moment the stream
     * setup names them, because the host's initial-ping deadline is counted from
     * its own session start and not from when our control channel comes up.
     */
    fun startMediaPings(pinger: UdpMediaPinger) {
        this.pinger?.let { old -> old.close() }
        this.pinger = pinger
        Log.i(TAG, "media pings for $id as ${pinger.mode} from ${pinger.localPorts}")
        pingJob =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    runCatching {
                        pinger.ping()
                        pinger.drain()
                    }
                    delay(MEDIA_PING_INTERVAL_MS)
                }
            }
    }

    /**
     * Adopt a connected control session and start the receive/ping pump. The
     * pump owns liveness: when the ENet layer drops, the session flips to Closed
     * and this connection reports the drop rather than a clean idle.
     */
    fun markLive(
        session: MoonlightControlSession,
        appId: String?,
        appName: String?,
    ) {
        this.session = session
        sessionAppId = appId
        sessionAppName = appName
        _state.value = MoonlightSessionState.Live
        _pads.value.values.forEach(::announce)
        pumpJob =
            scope.launch(ioDispatcher) {
                // A throw in here would strand the session Live with nothing
                // acknowledging the host, so it is caught and reported rather
                // than left to kill the coroutine silently.
                val failure = runCatching { pumpUntilClosed(session) }.exceptionOrNull()
                if (failure != null) Log.w(TAG, "control pump for $id stopped: ${failure.message}", failure)
                Log.i(TAG, "control link for $id ended: ${session.disconnectReason ?: "closed"} (${session.linkStats()})")
                // Deliberately no /cancel here. The host will be left holding the
                // app it started for us, but a control stream that drops after
                // going live is as likely to be a blip as a real end, and closing
                // somebody's game out from under them is worse than the tidying is
                // worth. The binding screen offers the cancel explicitly.
                if (_state.value == MoonlightSessionState.Live) markDropped()
            }
    }

    private fun announce(pad: MoonlightPad) {
        val live = session ?: return
        live.sendControllerArrival(pad.number, pad.emulatedType, pad.capabilities, pad.supportedButtons)
        live.sendControllerState(
            controllerNumber = pad.number,
            activeMask = activeMask(),
            buttons = 0,
            leftTrigger = 0,
            rightTrigger = 0,
            leftStickX = 0,
            leftStickY = 0,
            rightStickX = 0,
            rightStickY = 0,
        )
    }

    // Clearing the pad's bit from the active mask is how the host is told to
    // unplug it; the number is only free once that has gone out.
    private fun withdraw() {
        val live = session ?: return
        val mask = activeMask()
        val survivor =
            _pads.value.values
                .firstOrNull()
                ?.number ?: 0
        live.sendControllerState(
            controllerNumber = survivor,
            activeMask = mask,
            buttons = 0,
            leftTrigger = 0,
            rightTrigger = 0,
            leftStickX = 0,
            leftStickY = 0,
            rightStickX = 0,
            rightStickY = 0,
        )
    }

    private suspend fun pumpUntilClosed(session: MoonlightControlSession) {
        while (currentCoroutineContext().isActive && session.state == MoonlightControlSession.State.CONNECTED) {
            session.pump()
        }
    }

    /** HOT PATH: forward one pad's controller state to the live session. */
    @Suppress("LongParameterList")
    fun sendControllerState(
        controllerNumber: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
    ) {
        val live = session ?: return
        // Cache the frame so a touchpad-click edge (which arrives on the touch
        // stream, not the pad report) can replay it with the click bit merged.
        val frame = PadFrame(buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY)
        lastPadFrames[controllerNumber] = frame
        val clickBit =
            if (touchClickByNumber[controllerNumber] == true) MoonlightControlProtocol.BTN_TOUCHPAD else 0
        live.sendControllerState(
            controllerNumber = controllerNumber,
            activeMask = activeMask(),
            buttons = (buttons or clickBit) and WIRE_BUTTONS_MASK,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = rightX,
            rightStickY = rightY,
        )
    }

    /** Resolve a wire controller number back to the slot bound to it, if any. */
    fun slotIdForNumber(controllerNumber: Int): String? =
        _pads.value.values
            .firstOrNull { it.number == controllerNumber }
            ?.slotId

    fun sendMouseMoveRel(
        deltaX: Int,
        deltaY: Int,
    ) {
        session?.sendMouseMoveRel(deltaX, deltaY)
    }

    fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) {
        session?.sendMouseButton(down, button)
    }

    fun sendMouseScroll(amount: Int) {
        session?.sendMouseScroll(amount)
    }

    // Host-requested motion state + per-slot touch differs (TelemetrySink below).
    private val motionGate = MoonlightMotionGate()
    private val touchDiffers = java.util.concurrent.ConcurrentHashMap<String, MoonlightTouchDiffer>()

    private data class PadFrame(
        val buttons: Int,
        val leftTrigger: Int,
        val rightTrigger: Int,
        val leftX: Int,
        val leftY: Int,
        val rightX: Int,
        val rightY: Int,
    )

    private val lastPadFrames = java.util.concurrent.ConcurrentHashMap<Int, PadFrame>()
    private val touchClickByNumber = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    override fun motionWanted(slotId: String): Boolean {
        val pad = padFor(slotId) ?: return false
        return motionGate.wanted(pad.number)
    }

    /**
     * One satellite-scaled IMU sample, split into the per-type Moonlight
     * packets the host asked for (accel and gyro are independent MOTION_EVENT
     * subscriptions) and paced to each requested rate.
     */
    @Suppress("LongParameterList")
    override fun sendMotion(
        slotId: String,
        gyroX: Short,
        gyroY: Short,
        gyroZ: Short,
        accelX: Short,
        accelY: Short,
        accelZ: Short,
        timestampDeltaUs: Int,
    ) {
        val live = session ?: return
        val pad = padFor(slotId) ?: return
        val nowNs = System.nanoTime()
        if (motionGate.shouldSend(pad.number, MoonlightControlProtocol.MOTION_TYPE_GYRO, nowNs)) {
            live.sendControllerMotion(
                controllerNumber = pad.number,
                motionType = MoonlightControlProtocol.MOTION_TYPE_GYRO,
                x = MoonlightTelemetry.gyroDegS(gyroX),
                y = MoonlightTelemetry.gyroDegS(gyroY),
                z = MoonlightTelemetry.gyroDegS(gyroZ),
            )
        }
        if (motionGate.shouldSend(pad.number, MoonlightControlProtocol.MOTION_TYPE_ACCEL, nowNs)) {
            live.sendControllerMotion(
                controllerNumber = pad.number,
                motionType = MoonlightControlProtocol.MOTION_TYPE_ACCEL,
                x = MoonlightTelemetry.accelMs2(accelX),
                y = MoonlightTelemetry.accelMs2(accelY),
                z = MoonlightTelemetry.accelMs2(accelZ),
            )
        }
    }

    override fun sendBattery(
        slotId: String,
        level: Int,
        status: Int,
    ) {
        val live = session ?: return
        val pad = padFor(slotId) ?: return
        live.sendControllerBattery(
            controllerNumber = pad.number,
            batteryState = MoonlightTelemetry.batteryState(status),
            percentage = MoonlightTelemetry.batteryPercentage(level),
        )
    }

    /**
     * Full-state touch snapshot -> per-pointer events. The click button does
     * not ride here: BTN_TOUCHPAD travels inside CONTROLLER_MULTI's button
     * flags, and the mouse-mode buttons/wheel ride the native mouse packets,
     * so this carries contacts only.
     */
    @Suppress("LongParameterList")
    override fun sendTouchpad(
        slotId: String,
        finger0Active: Boolean,
        finger1Active: Boolean,
        buttonPressed: Boolean,
        rightPressed: Boolean,
        middlePressed: Boolean,
        finger0TrackingId: Int,
        finger0X: Short,
        finger0Y: Short,
        finger1TrackingId: Int,
        finger1X: Short,
        finger1Y: Short,
        eventTimeMs: Long,
        scrollDelta: Short,
    ) {
        val live = session ?: return
        val pad = padFor(slotId) ?: return
        // The pad-surface click has no packet of its own: it is BTN_TOUCHPAD in
        // the pad report. On an edge, replay the last cached frame with the bit
        // merged so a click with no stick/button change still reaches the host.
        if (touchClickByNumber[pad.number] != buttonPressed) {
            touchClickByNumber[pad.number] = buttonPressed
            val f = lastPadFrames[pad.number] ?: PadFrame(0, 0, 0, 0, 0, 0, 0)
            sendControllerState(pad.number, f.buttons, f.leftTrigger, f.rightTrigger, f.leftX, f.leftY, f.rightX, f.rightY)
        }
        val differ = touchDiffers.getOrPut(slotId) { MoonlightTouchDiffer() }
        val events =
            differ.diff(
                finger0Active = finger0Active,
                finger0Id = finger0TrackingId,
                finger0X = MoonlightTelemetry.touchNorm(finger0X),
                finger0Y = MoonlightTelemetry.touchNorm(finger0Y),
                finger1Active = finger1Active,
                finger1Id = finger1TrackingId,
                finger1X = MoonlightTelemetry.touchNorm(finger1X),
                finger1Y = MoonlightTelemetry.touchNorm(finger1Y),
            )
        for (e in events) {
            live.sendControllerTouch(
                controllerNumber = pad.number,
                eventType = e.eventType,
                pointerId = e.pointerId,
                x = e.x,
                y = e.y,
                pressure = e.pressure,
            )
        }
    }

    fun dispatchFeedback(event: MoonlightEvent) {
        if (event is MoonlightEvent.MotionRequest) {
            motionGate.onMotionRequest(event.controllerNumber, event.reportRateHz, event.motionType)
        }
        onFeedback(event)
    }

    fun markDisconnected() {
        teardown()
        _state.value = MoonlightSessionState.Idle
    }

    fun markDropped() {
        teardown()
        _state.value = MoonlightSessionState.Dropped
    }

    fun markEnded() {
        teardown()
        _state.value = MoonlightSessionState.Ended
    }

    private fun teardown() {
        motionGate.clearAll()
        touchDiffers.clear()
        lastPadFrames.clear()
        touchClickByNumber.clear()
        pumpJob?.cancel()
        pumpJob = null
        pingJob?.cancel()
        pingJob = null
        pinger?.close()
        pinger = null
        session?.let { s -> scope.launch(ioDispatcher) { runCatching { s.stop() } } }
        session = null
    }

    companion object {
        private const val TAG = "MoonlightConnection"

        // XUSB's low 16 plus the wire's own touchpad flag (buttonFlags2 on the wire);
        // anything else a caller sets is not a button this client can vouch for.
        private const val WIRE_BUTTONS_MASK = 0xFFFF or MoonlightControlProtocol.BTN_TOUCHPAD

        // Comfortably inside every host deadline we have measured, and cheap.
        private const val MEDIA_PING_INTERVAL_MS = 500L

        const val ID_PREFIX = MoonlightHost.ID_PREFIX

        // The controller number is four bits of a 16-bit active mask, but a
        // Moonlight session carries four pads and no more.
        const val MAX_PADS = 4

        const val SUPPORTED_BUTTONS = 0xFFFF

        val DEFAULT_TYPE = MoonlightEmulatedType.XBOX
    }
}

data class MoonlightPad(
    val slotId: String,
    val number: Int,
    val emulatedType: Int,
    val capabilities: Int,
    val supportedButtons: Int,
)

enum class MoonlightSessionState { Idle, Launching, Live, Dropped, Ended }
