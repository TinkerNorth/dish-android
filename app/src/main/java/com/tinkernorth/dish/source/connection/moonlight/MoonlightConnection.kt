// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlProtocol
import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import com.tinkernorth.dish.core.net.moonlight.MoonlightEmulatedType
import com.tinkernorth.dish.core.net.moonlight.MoonlightEvent
import com.tinkernorth.dish.core.net.moonlight.MoonlightHost
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
 */
class MoonlightConnection(
    val id: String,
    host: MoonlightHost,
    private val scope: CoroutineScope,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
) {
    private val _host = MutableStateFlow(host)
    val host: StateFlow<MoonlightHost> = _host.asStateFlow()

    private val _state = MutableStateFlow(MoonlightSessionState.Idle)
    val state: StateFlow<MoonlightSessionState> = _state.asStateFlow()

    @Volatile private var session: MoonlightControlSession? = null
    private var pumpJob: Job? = null

    @Volatile private var pinger: UdpMediaPinger? = null
    private var pingJob: Job? = null

    /**
     * Set when this connection gave up on its own (the control stream died, or
     * the stream setup never completed) rather than being closed by the user.
     * The manager reads it to decide whether to tell the host to drop the app it
     * launched for us.
     */
    @Volatile var gaveUp: Boolean = false
        private set

    // The emulated type chosen for this session and whether the arrival was sent.
    @Volatile private var emulatedType: Int = MoonlightEmulatedType.AUTO

    @Volatile private var arrivalSent = false

    // Inbound feedback (rumble/LED/motion request) surfaced to the same plumbing
    // the satellite path uses; the manager wires the actual sinks.
    @Volatile var onFeedback: (MoonlightEvent) -> Unit = {}

    fun updateHost(host: MoonlightHost) {
        _host.value = host
    }

    fun markLaunching() {
        gaveUp = false
        if (_state.value == MoonlightSessionState.Live) return
        _state.value = MoonlightSessionState.Launching
    }

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
     * and this connection returns to Idle.
     */
    fun markLive(
        session: MoonlightControlSession,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ) {
        this.session = session
        this.emulatedType = MoonlightEmulatedType.resolve(emulatedType)
        arrivalSent = false
        session.sendControllerArrival(0, this.emulatedType, capabilities, supportedButtons)
        arrivalSent = true
        _state.value = MoonlightSessionState.Live
        pumpJob =
            scope.launch(ioDispatcher) {
                // A throw in here would strand the session Live with nothing
                // acknowledging the host, so it is caught and reported rather
                // than left to kill the coroutine silently.
                val failure = runCatching { pumpUntilClosed(session) }.exceptionOrNull()
                if (failure != null) Log.w(TAG, "control pump for $id stopped: ${failure.message}", failure)
                Log.i(TAG, "control link for $id ended: ${session.disconnectReason ?: "closed"} (${session.linkStats()})")
                if (_state.value == MoonlightSessionState.Live) {
                    gaveUp = true
                    markDisconnected()
                }
            }
    }

    private suspend fun pumpUntilClosed(session: MoonlightControlSession) {
        while (currentCoroutineContext().isActive && session.state == MoonlightControlSession.State.CONNECTED) {
            session.pump()
        }
    }

    /** HOT PATH: forward the current controller state to the live session. */
    @Suppress("LongParameterList")
    fun sendControllerState(
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftX: Int,
        leftY: Int,
        rightX: Int,
        rightY: Int,
    ) {
        val live = session ?: return
        live.sendControllerState(
            controllerNumber = 0,
            activeMask = 0x0001,
            buttons = buttons and 0xFFFF,
            leftTrigger = leftTrigger,
            rightTrigger = rightTrigger,
            leftStickX = leftX,
            leftStickY = leftY,
            rightStickX = rightX,
            rightStickY = rightY,
        )
    }

    fun dispatchFeedback(event: MoonlightEvent) {
        onFeedback(event)
    }

    /** Give up on this session ourselves (as opposed to the user closing it). */
    fun markFailed() {
        gaveUp = true
        markDisconnected()
    }

    fun markDisconnected() {
        pumpJob?.cancel()
        pumpJob = null
        pingJob?.cancel()
        pingJob = null
        pinger?.close()
        pinger = null
        session?.let { s -> scope.launch(ioDispatcher) { runCatching { s.stop() } } }
        session = null
        arrivalSent = false
        _state.value = MoonlightSessionState.Idle
    }

    companion object {
        private const val TAG = "MoonlightConnection"

        // Comfortably inside every host deadline we have measured, and cheap.
        private const val MEDIA_PING_INTERVAL_MS = 500L

        const val ID_PREFIX = MoonlightHost.ID_PREFIX

        // Capabilities the dish's virtual/physical pad advertises to the host:
        // analog triggers + rumble (Android has no LED/gyro sink for this path yet).
        const val BASE_CAPABILITIES = MoonlightControlProtocol.CAP_ANALOG_TRIGGERS or MoonlightControlProtocol.CAP_RUMBLE

        // XInput-style buttons the pad supports (low 16 bits, shared layout).
        const val SUPPORTED_BUTTONS = 0xFFFF
    }
}

enum class MoonlightSessionState { Idle, Launching, Live }
