// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.architecture.testing.allocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.fewestAllocatedBytesDuring
import com.tinkernorth.dish.architecture.testing.freshAppInstanceOf
import com.tinkernorth.dish.core.net.bytesToHex
import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.core.net.moonlight.enet.EnetClient
import com.tinkernorth.dish.core.net.moonlight.enet.EnetProtocol
import com.tinkernorth.dish.core.net.moonlight.enet.EnetWriter
import com.tinkernorth.dish.core.net.moonlight.enet.commandHeader
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The key an idle session is made with; it never seals with it.
private const val IDLE_TOUCH_KEY_BYTES = 16

// A transport that never carries anything, so a session over it never connects.
private class NeverConnectedTransport : MoonlightControlSession.Transport {
    override fun send(datagram: ByteArray) = Unit

    override fun receive(timeoutMs: Int): ByteArray? = null

    override fun close() = Unit
}

// How many touch sends one IdleSessionTouches run makes.
internal const val IDLE_TOUCHES_PER_RUN = 500

// Touch sends to a session that never connected, which drops each one. Reached through Runnable
// because the allocation test makes it in a class loader of its own (freshAppInstanceOf).
internal class IdleSessionTouches : Runnable {
    private val session = MoonlightControlSession(ByteArray(IDLE_TOUCH_KEY_BYTES), 0, NeverConnectedTransport(), { IDLE_NOW_MS })

    override fun run() {
        repeat(IDLE_TOUCHES_PER_RUN) { session.sendControllerTouch(1, TOUCH_EVENT_MOVE, 2, 0.25f, 0.5f, 1f) }
    }
}

// The idle session never reads its clock past construction.
private const val IDLE_NOW_MS = 0L

/**
 * Lifecycle tests for [MoonlightControlSession] driven by a scripted fake
 * transport: IDLE -> CONNECTING -> CONNECTED, controller sends, inbound event
 * decode, and graceful teardown.
 */
class MoonlightControlSessionTest {
    private val key = hexToBytes("edf04a215c4fbea20934120c8480d855")
    private var clock = 0L

    /** A fake transport that captures sends and replays a queued receive script. */
    private class FakeTransport : MoonlightControlSession.Transport {
        val sent = mutableListOf<ByteArray>()
        val inbound = ArrayDeque<ByteArray>()
        var closed = false

        override fun send(datagram: ByteArray) {
            sent += datagram
        }

        override fun receive(timeoutMs: Int): ByteArray? = inbound.removeFirstOrNull()

        override fun close() {
            closed = true
        }
    }

    private fun verifyConnectDatagram(): ByteArray {
        val w = EnetWriter(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.VERIFY_CONNECT_LEN)
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(10)
        commandHeader(
            w,
            EnetProtocol.COMMAND_VERIFY_CONNECT or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            1,
        )
        w.u16(0x0042) // outgoingPeerID
        w.u8(1) // incomingSessionID
        w.u8(2) // outgoingSessionID
        w.u32(1024) // mtu
        w.u32(EnetProtocol.MINIMUM_WINDOW_SIZE) // windowSize
        w.u32(1) // channelCount
        w.u32(0) // incomingBandwidth
        w.u32(0) // outgoingBandwidth
        w.u32(EnetProtocol.PACKET_THROTTLE_INTERVAL)
        w.u32(EnetProtocol.PACKET_THROTTLE_ACCELERATION)
        w.u32(EnetProtocol.PACKET_THROTTLE_DECELERATION)
        w.u32(0) // connectID
        return w.toByteArray()
    }

    /** Wrap a host-sent, sealed control payload as an ENet SEND_RELIABLE datagram. */
    private fun hostReliable(
        seq: Int,
        sealed: ByteArray,
    ): ByteArray {
        val w = EnetWriter(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN + sealed.size)
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(20)
        commandHeader(w, EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.FLAG_ACKNOWLEDGE, 0, seq)
        w.u16(sealed.size)
        w.bytes(sealed)
        return w.toByteArray()
    }

    @Test
    fun `connect handshake reaches CONNECTED`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        assertEquals(MoonlightControlSession.State.IDLE, session.state)
        assertTrue(session.connect())
        assertEquals(MoonlightControlSession.State.CONNECTED, session.state)
        // The CONNECT datagram went out first.
        assertTrue(transport.sent.isNotEmpty())
    }

    @Test
    fun `connect times out without a verify`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock.also { clock += 500 } })
        assertTrue(!session.connect(handshakeTimeoutMs = 300))
        assertEquals(MoonlightControlSession.State.CLOSED, session.state)
    }

    @Test
    fun `controller state is sealed and sent only when connected`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.connect()
        val before = transport.sent.size
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertEquals(before + 1, transport.sent.size)
    }

    @Test
    fun `inbound rumble event is decoded and dispatched`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val events = mutableListOf<MoonlightEvent>()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock }, onEvent = { events += it })
        session.connect()

        // The host seals a RUMBLE_DATA event with its own seq 0.
        val body = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(0)
        body.putShort(0)
        body.putShort(0x0FA0)
        body.putShort(0x0BB8)
        val plaintext =
            ByteBuffer
                .allocate(4 + 10)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(EVENT_RUMBLE_DATA.toShort())
                .putShort(10)
                .put(body.array())
                .array()
        val hostPacket = MoonlightControlPacket(key)
        transport.inbound.addLast(hostReliable(seq = 1, sealed = hostPacket.sealWithSeq(0, plaintext)))

        session.pump()
        assertEquals(1, events.size)
        assertEquals(MoonlightEvent.Rumble(0, 0x0FA0, 0x0BB8), events.first())
    }

    @Test
    fun `stop sends termination and closes the transport`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.connect()
        session.stop()
        assertEquals(MoonlightControlSession.State.CLOSED, session.state)
        assertTrue(transport.closed)
    }

    private fun connectedSession(
        transport: FakeTransport,
        onEvent: (MoonlightEvent) -> Unit = {},
    ): MoonlightControlSession {
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock }, onEvent)
        assertTrue(session.connect())
        transport.sent.clear()
        return session
    }

    private fun hostDisconnectDatagram(): ByteArray {
        val w = EnetWriter(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.DISCONNECT_LEN)
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(30)
        commandHeader(
            w,
            EnetProtocol.COMMAND_DISCONNECT or EnetProtocol.FLAG_UNSEQUENCED,
            EnetProtocol.SYSTEM_CHANNEL,
            0,
        )
        w.u32(0)
        return w.toByteArray()
    }

    private fun sealedRumble(seq: Int): ByteArray {
        val body = ByteBuffer.allocate(RUMBLE_BODY_LEN).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(0)
        body.putShort(0)
        body.putShort(0x0FA0)
        body.putShort(0x0BB8)
        val plaintext =
            ByteBuffer
                .allocate(CONTROL_HEADER_LEN + RUMBLE_BODY_LEN)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(EVENT_RUMBLE_DATA.toShort())
                .putShort(RUMBLE_BODY_LEN.toShort())
                .put(body.array())
                .array()
        return MoonlightControlPacket(key).sealWithSeq(seq, plaintext)
    }

    // Every datagram the client sends carries the sent-time header, so the command byte is the fifth.
    private fun commandOf(datagram: ByteArray): Int = datagram[EnetProtocol.FULL_HEADER_LEN].toInt() and EnetProtocol.COMMAND_MASK

    private fun channelOf(datagram: ByteArray): Int = datagram[EnetProtocol.FULL_HEADER_LEN + 1].toInt() and 0xFF

    private fun reliableSendsIn(sent: List<ByteArray>): List<ByteArray> =
        sent.filter { commandOf(it) == EnetProtocol.COMMAND_SEND_RELIABLE }

    private fun controlTypeOf(sealedReliableDatagram: ByteArray): Int {
        val payloadStart = EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN
        val sealed = sealedReliableDatagram.copyOfRange(payloadStart, sealedReliableDatagram.size)
        val plaintext = MoonlightControlPacket(key).open(sealed)!!
        return ByteBuffer
            .wrap(plaintext)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xFFFF
    }

    @Test
    fun `controller state is dropped before the handshake settles`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `controller state is dropped once the session has closed`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.stop()
        transport.sent.clear()
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `cold-path sends are dropped while not connected`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.sendMouseMoveRel(1, 1)
        session.sendMouseButton(true, MOUSE_BUTTON_LEFT)
        session.sendMouseScroll(1)
        session.sendControllerArrival(0, 0, 0, 0)
        session.sendControllerTouch(0, TOUCH_EVENT_DOWN, 0, 0f, 0f, 0f)
        session.sendControllerMotion(0, 1, 0f, 0f, 0f)
        session.sendControllerBattery(0, BATTERY_FULL, 100)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `an arrival goes out as one sealed reliable send on the data channel`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.sendControllerArrival(0, 0, 0, 0)
        val datagram = transport.sent.single()
        assertEquals(EnetProtocol.COMMAND_SEND_RELIABLE, commandOf(datagram))
        assertEquals(EnetClient.DATA_CHANNEL, channelOf(datagram))
        assertEquals(CTRL_INPUT_DATA, controlTypeOf(datagram))
    }

    @Test
    fun `the hot path and the cold path share one control seq`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        session.sendMouseMoveRel(1, 1)
        session.sendControllerState(0, 1, 0, 0, 0, 0, 0, 0, 0)
        val seqs = transport.sent.map { controlSeqOf(it) }
        assertEquals(listOf(0, 1, 2), seqs)
    }

    @Test
    fun `a silent host gets the CONNECT retransmitted before the deadline`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock.also { clock += HANDSHAKE_STEP_MS } })
        assertFalse(session.connect(handshakeTimeoutMs = HANDSHAKE_BUDGET_MS))
        val connects = transport.sent.count { commandOf(it) == EnetProtocol.COMMAND_CONNECT }
        assertTrue("expected a retransmit, saw $connects CONNECT sends", connects >= 2)
    }

    @Test
    fun `a peer disconnect during pump closes the session`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        transport.inbound.addLast(hostDisconnectDatagram())
        session.pump()
        assertEquals(MoonlightControlSession.State.CLOSED, session.state)
        assertEquals("peer sent DISCONNECT", session.disconnectReason)
    }

    @Test
    fun `a forged control packet is dropped and the next one still dispatches`() {
        val transport = FakeTransport()
        val events = mutableListOf<MoonlightEvent>()
        val session = connectedSession(transport) { events += it }
        val forged = sealedRumble(seq = 0).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        transport.inbound.addLast(hostReliable(seq = 1, sealed = forged))
        transport.inbound.addLast(hostReliable(seq = 2, sealed = sealedRumble(seq = 1)))
        session.pump()
        assertEquals(listOf<MoonlightEvent>(MoonlightEvent.Rumble(0, 0x0FA0, 0x0BB8)), events)
    }

    @Test
    fun `a payload too short to be a control packet is dropped without an event`() {
        val transport = FakeTransport()
        val events = mutableListOf<MoonlightEvent>()
        val session = connectedSession(transport) { events += it }
        transport.inbound.addLast(hostReliable(seq = 1, sealed = byteArrayOf(0x01, 0x00)))
        session.pump()
        assertTrue(events.isEmpty())
        assertEquals(MoonlightControlSession.State.CONNECTED, session.state)
    }

    @Test
    fun `pump pings every 500 ms while connected and idle`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        clock = PING_INTERVAL_MS - 1
        session.pump()
        assertTrue(reliableSendsIn(transport.sent).isEmpty())
        clock = PING_INTERVAL_MS
        session.pump()
        val ping = reliableSendsIn(transport.sent).single()
        assertEquals(CTRL_PERIODIC_PING, controlTypeOf(ping))
        session.pump()
        assertEquals(1, reliableSendsIn(transport.sent).size)
    }

    @Test
    fun `pump does not ping a session that never connected`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        clock = PING_INTERVAL_MS * 2L
        session.pump()
        assertTrue(reliableSendsIn(transport.sent).isEmpty())
    }

    @Test
    fun `stop sends termination then disconnect`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.stop()
        assertEquals(listOf(EnetProtocol.COMMAND_SEND_RELIABLE, EnetProtocol.COMMAND_DISCONNECT), transport.sent.map(::commandOf))
        assertEquals(CTRL_TERMINATION, controlTypeOf(transport.sent.first()))
    }

    @Test
    fun `stop after a failed handshake sends no termination`() {
        val transport = FakeTransport()
        val session = MoonlightControlSession(key, 0x1234, transport, { clock.also { clock += HANDSHAKE_STEP_MS } })
        assertFalse(session.connect(handshakeTimeoutMs = HANDSHAKE_STEP_MS.toInt()))
        transport.sent.clear()
        session.stop()
        assertTrue(reliableSendsIn(transport.sent).isEmpty())
        assertTrue(transport.closed)
        assertEquals(MoonlightControlSession.State.CLOSED, session.state)
    }

    @Test
    fun `linkStats and roundTripMs read the ENet layer's counters`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        assertEquals("acks 1, retransmits 0, unknown commands 0", session.linkStats())
        assertNull(session.roundTripMs())
        assertNull(session.disconnectReason)
    }

    private fun plaintextOf(sealedReliableDatagram: ByteArray): ByteArray {
        val payloadStart = EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN
        val sealed = sealedReliableDatagram.copyOfRange(payloadStart, sealedReliableDatagram.size)
        return MoonlightControlPacket(key).open(sealed)!!
    }

    // A controller packet goes first so a mouse packet sealed after it, in a shorter message,
    // shows any byte or length the longer one left behind.
    @Test
    fun `each mouse send goes out sealed with exactly the encoder's plaintext`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        session.sendMouseButton(true, MOUSE_BUTTON_RIGHT)
        session.sendMouseButton(false, MOUSE_BUTTON_LEFT)
        session.sendMouseScroll(-WHEEL_NOTCH)
        session.sendMouseMoveRel(MOVE_DX, MOVE_DY)
        val expected =
            listOf(
                mouseButton(true, MOUSE_BUTTON_RIGHT),
                mouseButton(false, MOUSE_BUTTON_LEFT),
                mouseScroll(-WHEEL_NOTCH),
                mouseMoveRel(MOVE_DX, MOVE_DY),
            )
        val sent = transport.sent.drop(1)
        assertEquals(expected.map(::bytesToHex), sent.map { bytesToHex(plaintextOf(it)) })
        assertEquals(listOf(1, 2, 3, 4), sent.map { controlSeqOf(it) })
    }

    // Wolf skips a CONTROLLER_ARRIVAL for a number it still holds, and drops a pad on the
    // CONTROLLER_MULTI that names its number with its bit cleared, so a replug is that unplug
    // and then the arrival, in this order on the one reliable channel.
    @Test
    fun `a replug sends the pad's unplug and then its arrival as the new type`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.sendControllerReplug(REPLUG_NUMBER, OTHER_PADS_MASK, PLAYSTATION, REPLUG_CAPS, REPLUG_BUTTONS)
        val expected =
            listOf(
                controllerMulti(REPLUG_NUMBER, OTHER_PADS_MASK, 0, 0, 0, 0, 0, 0, 0),
                controllerArrival(REPLUG_NUMBER, PLAYSTATION, REPLUG_CAPS, REPLUG_BUTTONS),
            )
        assertEquals(expected.map(::bytesToHex), transport.sent.map { bytesToHex(plaintextOf(it)) })
        assertEquals(listOf(0, 1), transport.sent.map { controlSeqOf(it) })
    }

    @Test
    fun `a replug is dropped while not connected and spends no seq`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.sendControllerReplug(REPLUG_NUMBER, OTHER_PADS_MASK, PLAYSTATION, REPLUG_CAPS, REPLUG_BUTTONS)
        assertTrue(transport.sent.isEmpty())
        assertTrue(session.connect())
        transport.sent.clear()
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertEquals(0, controlSeqOf(transport.sent.single()))
    }

    // The host derives each packet's IV from the seq, so one burnt by a send nobody saw would
    // leave the host a packet behind from the first real one.
    @Test
    fun `a mouse send dropped before the session connects spends no seq`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.sendMouseMoveRel(MOVE_DX, MOVE_DY)
        session.sendMouseButton(true, MOUSE_BUTTON_LEFT)
        session.sendMouseScroll(WHEEL_NOTCH)
        assertTrue(session.connect())
        transport.sent.clear()
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertEquals(0, controlSeqOf(transport.sent.single()))
    }

    // Counts datagrams without keeping them, so an allocation count sees only the session.
    private class CountingTransport : MoonlightControlSession.Transport {
        var sent = 0
            private set
        val inbound = ArrayDeque<ByteArray>()

        override fun send(datagram: ByteArray) {
            sent++
        }

        override fun receive(timeoutMs: Int): ByteArray? = inbound.removeFirstOrNull()

        override fun close() = Unit
    }

    // What one send allocates on average, on a fresh connected session so every kind of send
    // starts from the same ENet state and grows the same unacked-send table.
    private fun bytesPerSend(send: (MoonlightControlSession) -> Unit): Long {
        val transport = CountingTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        assertTrue(session.connect())
        repeat(WARMUP_SENDS) { send(session) }
        val sentBefore = transport.sent
        val allocated = allocatedBytesDuring { repeat(MEASURED_SENDS) { send(session) } }
        assertEquals(MEASURED_SENDS, transport.sent - sentBefore)
        return allocated / MEASURED_SENDS
    }

    // A reliable send cannot be allocation-free: ENet keeps each command until the host acks it
    // and the cipher re-inits for every IV. The controller path builds nothing else, and a mouse
    // packet, shorter than a controller one, must not cost more than it.
    @Test
    fun `a mouse send allocates no more than a controller state send`() {
        val controller = bytesPerSend { it.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0) }
        val move = bytesPerSend { it.sendMouseMoveRel(MOVE_DX, MOVE_DY) }
        val button = bytesPerSend { it.sendMouseButton(true, MOUSE_BUTTON_LEFT) }
        val scroll = bytesPerSend { it.sendMouseScroll(WHEEL_NOTCH) }
        val perSend = "controller $controller, move $move, button $button, scroll $scroll bytes per send"
        assertTrue(perSend, move <= controller)
        assertTrue(perSend, button <= controller)
        assertTrue(perSend, scroll <= controller)
    }

    // A controller packet goes first so a touch packet sealed after it, in a shorter message,
    // shows any byte or length the longer one left behind; a mouse button, shorter still, goes
    // between them so the touch must take the whole scratch back.
    @Test
    fun `each touch send goes out sealed with exactly the encoder's plaintext`() {
        val transport = FakeTransport()
        val session = connectedSession(transport)
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        session.sendMouseButton(true, MOUSE_BUTTON_LEFT)
        session.sendControllerTouch(TOUCH_NUMBER, TOUCH_EVENT_DOWN, TOUCH_POINTER, TOUCH_X, TOUCH_Y, 1f)
        session.sendControllerTouch(TOUCH_NUMBER, TOUCH_EVENT_UP, TOUCH_POINTER, TOUCH_Y, TOUCH_X, 0f)
        val expected =
            listOf(
                controllerTouch(TOUCH_NUMBER, TOUCH_EVENT_DOWN, TOUCH_POINTER, TOUCH_X, TOUCH_Y, 1f),
                controllerTouch(TOUCH_NUMBER, TOUCH_EVENT_UP, TOUCH_POINTER, TOUCH_Y, TOUCH_X, 0f),
            )
        val sent = transport.sent.drop(2)
        assertEquals(expected.map(::bytesToHex), sent.map { bytesToHex(plaintextOf(it)) })
        assertEquals(listOf(2, 3), sent.map { controlSeqOf(it) })
    }

    // The encoder's bytes as the host reads them (Wolf control.hpp CONTROLLER_TOUCH_PACKET), so a
    // change to the encoder cannot move the wire with the test that compares against it.
    @Test
    fun `a touch packet's plaintext is the wire layout`() {
        val touch = controllerTouch(TOUCH_NUMBER, TOUCH_EVENT_DOWN, TOUCH_POINTER, TOUCH_X, TOUCH_Y, 1f)
        assertEquals(TOUCH_WIRE_HEX, bytesToHex(touch))
    }

    @Test
    fun `a touch send dropped before the session connects spends no seq`() {
        val transport = FakeTransport()
        transport.inbound.addLast(verifyConnectDatagram())
        val session = MoonlightControlSession(key, 0x1234, transport, { clock })
        session.sendControllerTouch(TOUCH_NUMBER, TOUCH_EVENT_DOWN, TOUCH_POINTER, TOUCH_X, TOUCH_Y, 1f)
        assertTrue(transport.sent.isEmpty())
        assertTrue(session.connect())
        transport.sent.clear()
        session.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0)
        assertEquals(0, controlSeqOf(transport.sent.single()))
    }

    @Test
    fun `a touch send allocates no more than a controller state send`() {
        val controller = bytesPerSend { it.sendControllerState(0, 1, BTN_A, 0, 0, 0, 0, 0, 0) }
        val touch = bytesPerSend { it.sendControllerTouch(TOUCH_NUMBER, TOUCH_EVENT_MOVE, TOUCH_POINTER, TOUCH_X, TOUCH_Y, 1f) }
        assertTrue("controller $controller, touch $touch bytes per send", touch <= controller)
    }

    @Test
    fun `a touch send dropped while not connected allocates nothing, even once MockK has rewritten the session class`() {
        mockk<MoonlightControlSession>(relaxed = true).sendControllerTouch(0, 0, 0, 0f, 0f, 0f)
        val touches = freshAppInstanceOf(IdleSessionTouches::class.java) as Runnable
        touches.run()
        val allocated = fewestAllocatedBytesDuring(MEASURED_RUNS, touches::run)
        assertTrue("$allocated bytes over $IDLE_TOUCHES_PER_RUN sends", allocated < IDLE_TOUCHES_PER_RUN * BYTES_PER_SEND_BOUND)
    }

    private fun controlSeqOf(sealedReliableDatagram: ByteArray): Int {
        val seqStart = EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN + CONTROL_HEADER_LEN
        return ByteBuffer.wrap(sealedReliableDatagram, seqStart, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private companion object {
        const val CONTROL_HEADER_LEN = 4
        const val RUMBLE_BODY_LEN = 10
        const val PING_INTERVAL_MS = 500L
        const val HANDSHAKE_STEP_MS = 300L
        const val HANDSHAKE_BUDGET_MS = 3000
        const val WHEEL_NOTCH = 120
        const val MOVE_DX = 3
        const val MOVE_DY = -4
        const val REPLUG_NUMBER = 2
        const val OTHER_PADS_MASK = 0b0001
        const val REPLUG_CAPS = 0x3F
        const val REPLUG_BUTTONS = 0x10FFFF
        const val WARMUP_SENDS = 50
        const val MEASURED_SENDS = 500
        const val MEASURED_RUNS = 3
        const val TOUCH_NUMBER = 2
        const val TOUCH_POINTER = 0x01020304
        const val TOUCH_X = 0.25f
        const val TOUCH_Y = 0.75f

        // Half the smallest object: one allocation a send costs 16 bytes or more.
        const val BYTES_PER_SEND_BOUND = 8

        // type 0x0206 LE, length 28 LE, wrapper size 24 BE, INPUT_CONTROLLER_TOUCH LE, number,
        // event type, two reserved bytes, pointer id LE, x, y and pressure as LE floats.
        const val TOUCH_WIRE_HEX = "06021c00000000180500005502010000040302010000803e0000403f0000803f"
    }
}
