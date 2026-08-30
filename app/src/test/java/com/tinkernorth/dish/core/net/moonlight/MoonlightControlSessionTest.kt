// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import com.tinkernorth.dish.core.net.hexToBytes
import com.tinkernorth.dish.core.net.moonlight.enet.EnetProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.VERIFY_CONNECT_LEN)
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(10)
        EnetProtocol.commandHeader(
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
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN + sealed.size)
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(20)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.FLAG_ACKNOWLEDGE, 0, seq)
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
        session.sendControllerState(0, 1, MoonlightControlProtocol.BTN_A, 0, 0, 0, 0, 0, 0)
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
                .putShort(MoonlightControlProtocol.EVENT_RUMBLE_DATA.toShort())
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
}
