// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.BTN_TOUCHPAD
import com.tinkernorth.dish.core.net.moonlight.MOTION_TYPE_ACCEL
import com.tinkernorth.dish.core.net.moonlight.MOTION_TYPE_GYRO
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
import com.tinkernorth.dish.core.net.moonlight.MoonlightMotionGate
import com.tinkernorth.dish.core.net.moonlight.MoonlightTouchDiffer
import com.tinkernorth.dish.core.net.moonlight.XBOX
import com.tinkernorth.dish.core.net.moonlight.accelMs2
import com.tinkernorth.dish.core.net.moonlight.batteryPercentage
import com.tinkernorth.dish.core.net.moonlight.batteryState
import com.tinkernorth.dish.core.net.moonlight.gyroDegS
import com.tinkernorth.dish.core.net.moonlight.touchNorm
import com.tinkernorth.dish.source.connection.TelemetrySink
import com.tinkernorth.dish.source.connection.TouchpadReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

// One pad frame as the connection remembers it, FRAME_FIELDS ints per controller number.
private const val FRAME_BUTTONS = 0
private const val FRAME_LEFT_TRIGGER = 1
private const val FRAME_RIGHT_TRIGGER = 2
private const val FRAME_LEFT_X = 3
private const val FRAME_LEFT_Y = 4
private const val FRAME_RIGHT_X = 5
private const val FRAME_RIGHT_Y = 6
private const val FRAME_FIELDS = 7

// A pad's touchpad click as its last touch report said it; unknown until one has.
private const val CLICK_UNKNOWN = 0
private const val CLICK_UP = 1
private const val CLICK_DOWN = 2

private fun padMaskOf(pads: Map<String, MoonlightPad>): Int = pads.values.fold(0) { mask, pad -> mask or (1 shl pad.number) }

// Each pad's slot at its number, null where no pad holds the number.
private fun slotsByNumberOf(pads: Map<String, MoonlightPad>): Array<String?> {
    val slots = arrayOfNulls<String>(MoonlightConnection.MAX_PADS)
    for (pad in pads.values) slots[pad.number] = pad.slotId
    return slots
}

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

    // The active mask of [_pads], kept beside it so a frame reads an Int instead of walking the map.
    @Volatile private var padMask = 0

    // [_pads] by number, kept beside it so a bridge upcall finds its slot without walking the map.
    @Volatile private var slotsByNumber = arrayOfNulls<String>(MAX_PADS)

    @Volatile private var session: MoonlightControlSession? = null
    private var pumpJob: Job? = null
    private val sentByNumber = AtomicLongArray(MAX_PADS)

    fun controlRoundTripMs(): Long? = session?.roundTripMs()

    fun reportsSentFor(controllerNumber: Int): Long = if (controllerNumber in 0 until MAX_PADS) sentByNumber.get(controllerNumber) else 0L

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
                publishPads(_pads.value + (slotId to fresh))
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
                publishPads(_pads.value - slotId)
                _pads.value.size
            }
        released?.let { pad ->
            forgetHostPadState(slotId, pad.number)
            unplug(pad.number)
        }
        return remaining
    }

    /**
     * Re-announce the pad [slotId] holds as [emulatedType], under the same number, and return it
     * as it now stands; null when [slotId] holds no pad. On a live session the host unplugs the
     * number and plugs the new type in (see [MoonlightControlSession.sendControllerReplug]); on
     * one not yet live only the table changes, and markLive announces the new type.
     */
    fun reannouncePad(
        slotId: String,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ): MoonlightPad? {
        val pad =
            synchronized(padLock) {
                val held = _pads.value[slotId] ?: return@synchronized null
                val next = held.copy(emulatedType = emulatedType, capabilities = capabilities, supportedButtons = supportedButtons)
                publishPads(_pads.value + (slotId to next))
                next
            } ?: return null
        forgetHostPadState(slotId, pad.number)
        replug(pad)
        return pad
    }

    // Under [padLock].
    private fun publishPads(next: Map<String, MoonlightPad>) {
        _pads.value = next
        padMask = padMaskOf(next)
        slotsByNumber = slotsByNumberOf(next)
    }

    // What the host asked of, or was told about, the pad that held [number]: a pad it plugs in
    // under that number next starts from nothing.
    private fun forgetHostPadState(
        slotId: String,
        number: Int,
    ) {
        motionGate.clear(number)
        touchDiffers.remove(slotId)
        forgetFrame(number)
        touchClickByNumber.set(number, CLICK_UNKNOWN)
    }

    private fun forgetFrame(number: Int) {
        val first = number * FRAME_FIELDS
        for (field in first until first + FRAME_FIELDS) lastPadFrames.set(field, 0)
    }

    fun padFor(slotId: String): MoonlightPad? = _pads.value[slotId]

    val padCount: Int get() = _pads.value.size

    val hasRoom: Boolean get() = _pads.value.size < MAX_PADS

    fun activeMask(): Int = padMask

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
        sendNeutral(live, pad.number)
    }

    private fun replug(pad: MoonlightPad) {
        val live = session ?: return
        val otherPads = activeMask() and (1 shl pad.number).inv()
        live.sendControllerReplug(pad.number, otherPads, pad.emulatedType, pad.capabilities, pad.supportedButtons)
        sendNeutral(live, pad.number)
    }

    // A just-plugged pad at rest, with the active mask it now belongs to.
    private fun sendNeutral(
        live: MoonlightControlSession,
        number: Int,
    ) {
        live.sendControllerState(
            controllerNumber = number,
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

    // The CONTROLLER_MULTI that names [number] with its bit cleared from the active mask: Wolf
    // unplugs that pad on it and on nothing else (a packet naming another pad leaves this one
    // plugged in), and the number is only free once it has gone out. Called once [number] has
    // left the table, so the active mask no longer carries it.
    private fun unplug(number: Int) {
        val live = session ?: return
        live.sendControllerState(
            controllerNumber = number,
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

    private suspend fun pumpUntilClosed(session: MoonlightControlSession) {
        while (currentCoroutineContext().isActive && session.state == MoonlightControlSession.State.CONNECTED) {
            session.pump()
        }
    }

    /** HOT PATH: forward one pad's controller state to the live session. */
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
        val isAPad = controllerNumber in 0 until MAX_PADS
        if (isAPad) {
            rememberFrame(controllerNumber, buttons, leftTrigger, rightTrigger, leftX, leftY, rightX, rightY)
            sentByNumber.incrementAndGet(controllerNumber)
        }
        val isClickHeld = isAPad && touchClickByNumber.get(controllerNumber) == CLICK_DOWN
        val clickBit = if (isClickHeld) BTN_TOUCHPAD else 0
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

    private fun rememberFrame(
        number: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
    ) {
        val first = number * FRAME_FIELDS
        lastPadFrames.set(first + FRAME_BUTTONS, buttons)
        lastPadFrames.set(first + FRAME_LEFT_TRIGGER, leftTrigger)
        lastPadFrames.set(first + FRAME_RIGHT_TRIGGER, rightTrigger)
        lastPadFrames.set(first + FRAME_LEFT_X, leftX)
        lastPadFrames.set(first + FRAME_LEFT_Y, leftY)
        lastPadFrames.set(first + FRAME_RIGHT_X, rightX)
        lastPadFrames.set(first + FRAME_RIGHT_Y, rightY)
    }

    // The pad's last frame again, at rest before it has sent one, so a click edge with no stick or
    // button change still reaches the host.
    private fun replayLastFrame(number: Int) {
        val first = number * FRAME_FIELDS
        sendControllerState(
            controllerNumber = number,
            buttons = lastPadFrames.get(first + FRAME_BUTTONS),
            leftTrigger = lastPadFrames.get(first + FRAME_LEFT_TRIGGER),
            rightTrigger = lastPadFrames.get(first + FRAME_RIGHT_TRIGGER),
            leftX = lastPadFrames.get(first + FRAME_LEFT_X),
            leftY = lastPadFrames.get(first + FRAME_LEFT_Y),
            rightX = lastPadFrames.get(first + FRAME_RIGHT_X),
            rightY = lastPadFrames.get(first + FRAME_RIGHT_Y),
        )
    }

    /** Resolve a wire controller number back to the slot bound to it, if any. */
    fun slotIdForNumber(controllerNumber: Int): String? {
        val slots = slotsByNumber
        val isAPadNumber = controllerNumber in slots.indices
        return if (isAPadNumber) slots[controllerNumber] else null
    }

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

    // Each pad's last frame and click, indexed by controller number. Each field is set and read
    // on its own, so a replay racing another thread's frame for that pad can mix the two frames:
    // the same one-frame staleness that replay already had, and the next frame supersedes it.
    private val lastPadFrames = AtomicIntegerArray(MAX_PADS * FRAME_FIELDS)
    private val touchClickByNumber = AtomicIntegerArray(MAX_PADS)

    override fun motionWanted(slotId: String): Boolean {
        val pad = padFor(slotId) ?: return false
        return motionGate.wanted(pad.number)
    }

    /**
     * One satellite-scaled IMU sample, split into the per-type Moonlight
     * packets the host asked for (accel and gyro are independent MOTION_EVENT
     * subscriptions) and paced to each requested rate.
     */
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
        if (motionGate.shouldSend(pad.number, MOTION_TYPE_GYRO, nowNs)) {
            live.sendControllerMotion(
                controllerNumber = pad.number,
                motionType = MOTION_TYPE_GYRO,
                x = gyroDegS(gyroX),
                y = gyroDegS(gyroY),
                z = gyroDegS(gyroZ),
            )
        }
        if (motionGate.shouldSend(pad.number, MOTION_TYPE_ACCEL, nowNs)) {
            live.sendControllerMotion(
                controllerNumber = pad.number,
                motionType = MOTION_TYPE_ACCEL,
                x = accelMs2(accelX),
                y = accelMs2(accelY),
                z = accelMs2(accelZ),
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
            batteryState = batteryState(status),
            percentage = batteryPercentage(level),
        )
    }

    /**
     * Full-state touch snapshot -> per-pointer events. The click button does
     * not ride here: BTN_TOUCHPAD travels inside CONTROLLER_MULTI's button
     * flags, and the mouse-mode buttons/wheel ride the native mouse packets,
     * so this carries contacts only.
     */
    override fun sendTouchpad(
        slotId: String,
        report: TouchpadReport,
    ) {
        val live = session ?: return
        val pad = padFor(slotId) ?: return
        // The pad-surface click has no packet of its own: it is BTN_TOUCHPAD in
        // the pad report, so an edge replays the pad's last frame with the bit merged.
        val click = if (report.buttonPressed) CLICK_DOWN else CLICK_UP
        val isAClickEdge = touchClickByNumber.getAndSet(pad.number, click) != click
        if (isAClickEdge) replayLastFrame(pad.number)
        val differ = touchDiffers.getOrPut(slotId) { MoonlightTouchDiffer() }
        val events =
            differ.diff(
                finger0Active = report.finger0Active,
                finger0Id = report.finger0TrackingId,
                finger0X = touchNorm(report.finger0X),
                finger0Y = touchNorm(report.finger0Y),
                finger1Active = report.finger1Active,
                finger1Id = report.finger1TrackingId,
                finger1X = touchNorm(report.finger1X),
                finger1Y = touchNorm(report.finger1Y),
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
        for (number in 0 until MAX_PADS) forgetFrame(number)
        for (number in 0 until MAX_PADS) touchClickByNumber.set(number, CLICK_UNKNOWN)
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
        private const val WIRE_BUTTONS_MASK = 0xFFFF or BTN_TOUCHPAD

        // Comfortably inside every host deadline we have measured, and cheap.
        private const val MEDIA_PING_INTERVAL_MS = 500L

        const val ID_PREFIX = MoonlightHost.ID_PREFIX

        // The controller number is four bits of a 16-bit active mask, but a
        // Moonlight session carries four pads and no more.
        const val MAX_PADS = 4

        const val SUPPORTED_BUTTONS = 0xFFFF

        val DEFAULT_TYPE = XBOX
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
