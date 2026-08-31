// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.moonlight.enet.EnetClient

/**
 * Drives the Moonlight control stream: the ENet connect handshake, the reliable
 * CONTROLLER_MULTI / ping / termination sends, and the inbound rumble / trigger
 * / motion / LED events. Composes the pure pieces ([EnetClient],
 * [MoonlightHotSealer], [MoonlightControlPacket], [MoonlightEventDecoder]) over
 * a swappable [Transport] so the whole lifecycle unit-tests with a fake
 * transport and a controllable clock; production plugs in a UDP socket.
 *
 * The hot path ([sendControllerState]) reuses the sealer's buffers and only the
 * ENet framing allocates.
 *
 * ONE LOCK OVER THE WHOLE PROTOCOL STATE, and it has to be. Input arrives on the
 * dispatch thread while [pump] runs the receive/ping loop on an IO thread, and
 * both reach the same [EnetClient] and the same [MoonlightHotSealer]. Neither is
 * thread-safe, and the sealer's counter is the AES-GCM IV: two threads sealing at
 * once can hand the same IV to two packets, which is a real key-recovery bug and
 * not merely a lost input. The blocking receive is deliberately left OUTSIDE the
 * lock, so a quiet link never stalls the input thread behind a socket timeout.
 */
class MoonlightControlSession(
    rikey: ByteArray,
    private val enetConnectData: Int,
    private val transport: Transport,
    private val nowMs: () -> Long,
    private val onEvent: (MoonlightEvent) -> Unit = {},
) {
    /** The datagram plumbing under the session (a UDP socket in production). */
    interface Transport {
        fun send(datagram: ByteArray)

        /** Blocking receive; returns null on timeout. */
        fun receive(timeoutMs: Int): ByteArray?

        fun close()
    }

    enum class State { IDLE, CONNECTING, CONNECTED, CLOSED }

    var state: State = State.IDLE
        private set

    private val enet = EnetClient(enetConnectData, nowMs)
    private val sealer = MoonlightHotSealer(rikey)
    private val opener = MoonlightControlPacket(rikey)

    /** Guards [enet], [sealer], [opener] and [state]; see the class comment. */
    private val lock = Any()

    private var lastPingMs = 0L

    /** Why the ENet layer gave up, once it has. For the session log. */
    val disconnectReason: String? get() = synchronized(lock) { enet.disconnectReason }

    /** A one-line account of what the link did, for the session log. */
    fun linkStats(): String =
        synchronized(lock) {
            "acks ${enet.acksSent}, retransmits ${enet.retransmits}, unknown commands ${enet.unknownCommands}"
        }

    /**
     * Run the ENet handshake. Sends CONNECT, then pumps received datagrams until
     * VERIFY_CONNECT flips the client to CONNECTED or [handshakeTimeoutMs]
     * elapses. Returns true on success.
     */
    fun connect(handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS): Boolean {
        synchronized(lock) {
            state = State.CONNECTING
            transport.send(enet.connect())
        }
        val deadline = nowMs() + handshakeTimeoutMs
        while (nowMs() < deadline && enetState() == EnetClient.State.CONNECTING) {
            val datagram = transport.receive(HANDSHAKE_POLL_MS)
            synchronized(lock) {
                if (datagram == null) {
                    enet.tick().forEach(transport::send)
                } else {
                    enet.onDatagram(datagram).forEach(transport::send)
                }
            }
        }
        return synchronized(lock) {
            if (enet.state == EnetClient.State.CONNECTED) {
                state = State.CONNECTED
                true
            } else {
                state = State.CLOSED
                false
            }
        }
    }

    private fun enetState(): EnetClient.State = synchronized(lock) { enet.state }

    /**
     * HOT PATH: seal and send the controller state on channel 0. No allocation
     * beyond the ENet frame. Silently drops when not connected so a dead session
     * never blocks the input thread.
     */
    @Suppress("LongParameterList")
    fun sendControllerState(
        controllerNumber: Int,
        activeMask: Int,
        buttons: Int,
        leftTrigger: Int,
        rightTrigger: Int,
        leftStickX: Int,
        leftStickY: Int,
        rightStickX: Int,
        rightStickY: Int,
    ) {
        val datagram =
            synchronized(lock) {
                if (state != State.CONNECTED) return
                val sealed =
                    sealer.sealControllerMulti(
                        controllerNumber,
                        activeMask,
                        buttons,
                        leftTrigger,
                        rightTrigger,
                        leftStickX,
                        leftStickY,
                        rightStickX,
                        rightStickY,
                    )
                enet.sendReliable(sealed)
            } ?: return
        transport.send(datagram)
    }

    /** Announce a virtual controller with its emulated type and capabilities. */
    fun sendControllerArrival(
        controllerNumber: Int,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(
                MoonlightInputEncoder.controllerArrival(controllerNumber, emulatedType, capabilities, supportedButtons),
            )
        }
    }

    fun sendMouseMoveRel(
        deltaX: Int,
        deltaY: Int,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(MoonlightInputEncoder.mouseMoveRel(deltaX, deltaY))
        }
    }

    fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(MoonlightInputEncoder.mouseButton(down, button))
        }
    }

    fun sendMouseScroll(amount: Int) {
        synchronized(lock) {
            sendControlPlaintextLocked(MoonlightInputEncoder.mouseScroll(amount))
        }
    }

    @Suppress("LongParameterList")
    fun sendControllerTouch(
        controllerNumber: Int,
        eventType: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(
                MoonlightInputEncoder.controllerTouch(controllerNumber, eventType, pointerId, x, y, pressure),
            )
        }
    }

    fun sendControllerMotion(
        controllerNumber: Int,
        motionType: Int,
        x: Float,
        y: Float,
        z: Float,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(
                MoonlightInputEncoder.controllerMotion(controllerNumber, motionType, x, y, z),
            )
        }
    }

    fun sendControllerBattery(
        controllerNumber: Int,
        batteryState: Int,
        percentage: Int,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(
                MoonlightInputEncoder.controllerBattery(controllerNumber, batteryState, percentage),
            )
        }
    }

    /**
     * Pump the receive side once: read up to [budget] datagrams, feed the ENet
     * layer, decrypt delivered control payloads and dispatch decoded events.
     * Also emits a periodic ping when idle. Call this from the session's read
     * loop.
     */
    fun pump(budget: Int = RECEIVE_BUDGET) {
        var handled = 0
        val events = mutableListOf<MoonlightEvent>()
        while (handled < budget) {
            val datagram = transport.receive(RECEIVE_POLL_MS) ?: break
            synchronized(lock) {
                enet.onDatagram(datagram).forEach(transport::send)
                drainEventsLocked(events)
            }
            handled += 1
        }
        synchronized(lock) {
            enet.tick().forEach(transport::send)
            maybePingLocked()
            if (enet.state == EnetClient.State.DISCONNECTED && state == State.CONNECTED) {
                state = State.CLOSED
            }
        }
        // Dispatched outside the lock: a rumble sink is somebody else's code and
        // must never be able to hold up the input thread.
        events.forEach(onEvent)
    }

    private fun drainEventsLocked(into: MutableList<MoonlightEvent>) {
        while (enet.received.isNotEmpty()) {
            val payload = enet.received.removeFirst()
            val plaintext = runCatching { opener.open(payload) }.getOrNull() ?: continue
            MoonlightEventDecoder.decode(plaintext)?.let(into::add)
        }
    }

    /**
     * The protocol's own keepalive, independent of whether input is changing: a
     * host that hears nothing at this layer ends the session even while the ENet
     * layer underneath is healthy.
     */
    private fun maybePingLocked() {
        val now = nowMs()
        if (state == State.CONNECTED && now - lastPingMs >= PING_INTERVAL_MS) {
            lastPingMs = now
            sendControlPlaintextLocked(MoonlightInputEncoder.periodicPing())
        }
    }

    private fun sendControlPlaintextLocked(plaintext: ByteArray) {
        if (state != State.CONNECTED) return
        // Route every outbound packet through the sealer so the whole control
        // stream shares one monotonic seq (no GCM IV reuse).
        val sealed = sealer.seal(plaintext)
        enet.sendReliable(sealed)?.let(transport::send)
    }

    /** Graceful teardown: TERMINATION then ENet disconnect. */
    fun stop() {
        synchronized(lock) {
            if (state == State.CONNECTED) {
                runCatching { sendControlPlaintextLocked(MoonlightInputEncoder.termination()) }
            }
            runCatching { enet.disconnect()?.let(transport::send) }
            runCatching { transport.close() }
            state = State.CLOSED
        }
    }

    private companion object {
        // Room for two CONNECT retransmits on a busy link. Giving up sooner ends in
        // a /cancel that burns the launch, so a blip reads as a host that refused.
        // The three clients wait the same five seconds.
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 5000
        const val HANDSHAKE_POLL_MS = 100
        const val RECEIVE_POLL_MS = 50
        const val RECEIVE_BUDGET = 16
        const val PING_INTERVAL_MS = 500
    }
}
