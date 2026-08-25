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

    // The last controller state sent, so a periodic re-send / active-mask change
    // reuses it. Single-controller for now (index 0); the wire supports more.
    private var lastPingMs = 0L

    /**
     * Run the ENet handshake. Sends CONNECT, then pumps received datagrams until
     * VERIFY_CONNECT flips the client to CONNECTED or [handshakeTimeoutMs]
     * elapses. Returns true on success.
     */
    fun connect(handshakeTimeoutMs: Int = DEFAULT_HANDSHAKE_TIMEOUT_MS): Boolean {
        state = State.CONNECTING
        transport.send(enet.connect())
        val deadline = nowMs() + handshakeTimeoutMs
        while (nowMs() < deadline && enet.state == EnetClient.State.CONNECTING) {
            val datagram =
                transport.receive(HANDSHAKE_POLL_MS) ?: run {
                    enet.tick().forEach(transport::send)
                    null
                }
            if (datagram != null) enet.onDatagram(datagram).forEach(transport::send)
        }
        return if (enet.state == EnetClient.State.CONNECTED) {
            state = State.CONNECTED
            true
        } else {
            state = State.CLOSED
            false
        }
    }

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
        enet.sendReliable(sealed)?.let(transport::send)
    }

    /** Announce a virtual controller with its emulated type and capabilities. */
    fun sendControllerArrival(
        controllerNumber: Int,
        emulatedType: Int,
        capabilities: Int,
        supportedButtons: Int,
    ) {
        sendControlPlaintext(
            MoonlightInputEncoder.controllerArrival(controllerNumber, emulatedType, capabilities, supportedButtons),
        )
    }

    /**
     * Pump the receive side once: read up to [budget] datagrams, feed the ENet
     * layer, decrypt delivered control payloads and dispatch decoded events.
     * Also emits a periodic ping when idle. Call this from the session's read
     * loop.
     */
    fun pump(budget: Int = RECEIVE_BUDGET) {
        var handled = 0
        while (handled < budget) {
            val datagram = transport.receive(RECEIVE_POLL_MS) ?: break
            enet.onDatagram(datagram).forEach(transport::send)
            drainEvents()
            handled += 1
        }
        enet.tick().forEach(transport::send)
        maybePing()
        if (enet.state == EnetClient.State.DISCONNECTED && state == State.CONNECTED) {
            state = State.CLOSED
        }
    }

    private fun drainEvents() {
        while (enet.received.isNotEmpty()) {
            val payload = enet.received.removeFirst()
            val plaintext = runCatching { opener.open(payload) }.getOrNull() ?: continue
            MoonlightEventDecoder.decode(plaintext)?.let(onEvent)
        }
    }

    private fun maybePing() {
        val now = nowMs()
        if (state == State.CONNECTED && now - lastPingMs >= PING_INTERVAL_MS) {
            lastPingMs = now
            sendControlPlaintext(MoonlightInputEncoder.periodicPing())
        }
    }

    private fun sendControlPlaintext(plaintext: ByteArray) {
        if (state != State.CONNECTED) return
        // Route every outbound packet through the sealer so the whole control
        // stream shares one monotonic seq (no GCM IV reuse).
        val sealed = sealer.seal(plaintext)
        enet.sendReliable(sealed)?.let(transport::send)
    }

    /** Graceful teardown: TERMINATION then ENet disconnect. */
    fun stop() {
        if (state == State.CONNECTED) {
            runCatching { sendControlPlaintext(MoonlightInputEncoder.termination()) }
        }
        runCatching { enet.disconnect()?.let(transport::send) }
        runCatching { transport.close() }
        state = State.CLOSED
    }

    private companion object {
        const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 3000
        const val HANDSHAKE_POLL_MS = 100
        const val RECEIVE_POLL_MS = 50
        const val RECEIVE_BUDGET = 16
        const val PING_INTERVAL_MS = 500
    }
}
