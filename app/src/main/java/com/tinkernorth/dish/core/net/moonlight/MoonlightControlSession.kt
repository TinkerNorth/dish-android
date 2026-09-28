// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.moonlight.enet.EnetClient

/**
 * Drives the Moonlight control stream: the ENet connect handshake, the reliable
 * CONTROLLER_MULTI / ping / termination sends, and the inbound rumble / trigger
 * / motion / LED events. Composes the pure pieces ([EnetClient],
 * [MoonlightHotSealer], [MoonlightControlPacket], [decodeMoonlightEvent]) over
 * a swappable [Transport] so the whole lifecycle unit-tests with a fake
 * transport and a controllable clock; production plugs in a UDP socket.
 *
 * The hot paths ([sendControllerState] and the mouse sends) encode and seal in
 * the sealer's reused buffers. They cannot be allocation-free: the cipher
 * re-inits for every packet's IV, and ENet keeps each reliable command, as sent,
 * until the host acks it, so the pump thread's retransmit has an immutable copy
 * whatever the input thread sends next. Each packet's own frame is that copy.
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
) : MoonlightTouchSink {
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
            val stats = enet.stats
            "acks ${stats.acksSent}, retransmits ${stats.retransmits}, unknown commands ${stats.unknownCommands}"
        }

    fun roundTripMs(): Long? = synchronized(lock) { enet.roundTripMs }

    /**
     * Run the ENet handshake. Sends CONNECT, then pumps received datagrams until
     * VERIFY_CONNECT flips the client to CONNECTED or [handshakeTimeoutMs]
     * elapses. Returns true on success.
     */
    fun connect(handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS): Boolean {
        beginHandshake()
        pumpUntilHandshakeSettles(nowMs() + handshakeTimeoutMs)
        return finishHandshake()
    }

    private fun beginHandshake() {
        synchronized(lock) {
            state = State.CONNECTING
            transport.send(enet.connect())
        }
    }

    // A quiet poll still ticks, so a dropped connect request is retransmitted rather than waited
    // out until the deadline.
    private fun pumpUntilHandshakeSettles(deadline: Long) {
        while (nowMs() < deadline && enetState() == EnetClient.State.CONNECTING) {
            val datagram = transport.receive(HANDSHAKE_POLL_MS)
            synchronized(lock) {
                val outgoing = if (datagram == null) enet.tick() else enet.onDatagram(datagram)
                outgoing.forEach(transport::send)
            }
        }
    }

    private fun finishHandshake(): Boolean =
        synchronized(lock) {
            val connected = enet.state == EnetClient.State.CONNECTED
            state = if (connected) State.CONNECTED else State.CLOSED
            connected
        }

    private fun enetState(): EnetClient.State = synchronized(lock) { enet.state }

    /**
     * HOT PATH: seal and send the controller state on channel 0. Nothing is built
     * beyond the sealed frame and what ENet keeps for a retransmit (see the class
     * comment). Silently drops when not connected so a dead session never blocks
     * the input thread.
     */
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
                controllerArrival(controllerNumber, emulatedType, capabilities, supportedButtons),
            )
        }
    }

    /**
     * Plug [controllerNumber] back in as [emulatedType]. Wolf skips a CONTROLLER_ARRIVAL for a
     * number it still holds, and drops the pad on the CONTROLLER_MULTI that names its number
     * with its bit cleared from the active mask, so this sends that unplug ([otherPadsMask] is
     * every other pad the session holds) and then the arrival. Both go under one hold of the
     * lock: an input frame for the number landing between them would make Wolf plug a default
     * Xbox pad for it, and the arrival would then be skipped.
     */
    fun sendControllerReplug(
        controllerNumber: Int,
        otherPadsMask: Int,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ) {
        synchronized(lock) {
            if (state != State.CONNECTED) return
            sendSealedLocked(sealer.sealControllerMulti(controllerNumber, otherPadsMask, 0, 0, 0, 0, 0, 0, 0))
            sendControlPlaintextLocked(controllerArrival(controllerNumber, emulatedType, capabilities, supportedButtons))
        }
    }

    // The mouse sends run per touch frame, so they seal in the sealer's reused buffers like
    // the controller state rather than building a plaintext first. The state check comes
    // before the seal, as on the cold path, so a dropped send never spends a seq.
    fun sendMouseMoveRel(
        deltaX: Int,
        deltaY: Int,
    ) {
        synchronized(lock) {
            if (state != State.CONNECTED) return
            sendSealedLocked(sealer.sealMouseMoveRel(deltaX, deltaY))
        }
    }

    fun sendMouseButton(
        down: Boolean,
        button: Int,
    ) {
        synchronized(lock) {
            if (state != State.CONNECTED) return
            sendSealedLocked(sealer.sealMouseButton(down, button))
        }
    }

    fun sendMouseScroll(amount: Int) {
        synchronized(lock) {
            if (state != State.CONNECTED) return
            sendSealedLocked(sealer.sealMouseScroll(amount))
        }
    }

    override fun sendControllerTouch(
        controllerNumber: Int,
        eventType: Int,
        pointerId: Int,
        x: Float,
        y: Float,
        pressure: Float,
    ) {
        synchronized(lock) {
            sendControlPlaintextLocked(
                controllerTouch(controllerNumber, eventType, pointerId, x, y, pressure),
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
                controllerMotion(controllerNumber, motionType, x, y, z),
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
                controllerBattery(controllerNumber, batteryState, percentage),
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
        val events = mutableListOf<MoonlightEvent>()
        receiveUpTo(budget, events)
        tickAndObserveClose()
        // Dispatched outside the lock: a rumble sink is somebody else's code and must never be
        // able to hold up the input thread.
        events.forEach(onEvent)
    }

    private fun receiveUpTo(
        budget: Int,
        events: MutableList<MoonlightEvent>,
    ) {
        var handled = 0
        while (handled < budget) {
            val datagram = transport.receive(RECEIVE_POLL_MS) ?: break
            synchronized(lock) {
                enet.onDatagram(datagram).forEach(transport::send)
                drainEventsLocked(events)
            }
            handled += 1
        }
    }

    private fun tickAndObserveClose() {
        synchronized(lock) {
            enet.tick().forEach(transport::send)
            maybePingLocked()
            val peerGaveUp = enet.state == EnetClient.State.DISCONNECTED && state == State.CONNECTED
            if (peerGaveUp) state = State.CLOSED
        }
    }

    private fun drainEventsLocked(into: MutableList<MoonlightEvent>) {
        while (enet.received.isNotEmpty()) {
            val payload = enet.received.removeFirst()
            val plaintext = runCatching { opener.open(payload) }.getOrNull() ?: continue
            decodeMoonlightEvent(plaintext)?.let(into::add)
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
            sendControlPlaintextLocked(periodicPing())
        }
    }

    private fun sendControlPlaintextLocked(plaintext: ByteArray) {
        if (state != State.CONNECTED) return
        // Route every outbound packet through the sealer so the whole control
        // stream shares one seq, the one the host derives each packet's IV from.
        sendSealedLocked(sealer.seal(plaintext))
    }

    private fun sendSealedLocked(sealed: ByteArray) {
        val datagram = enet.sendReliable(sealed) ?: return
        transport.send(datagram)
    }

    /** Graceful teardown: TERMINATION then ENet disconnect. */
    fun stop() {
        synchronized(lock) {
            if (state == State.CONNECTED) {
                runCatching { sendControlPlaintextLocked(termination()) }
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
