// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight.enet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives [EnetClient] as a pure state machine with handcrafted host datagrams,
 * matching the cgutman/enet wire format the Kotlin port reproduces.
 */
class EnetClientTest {
    private var clock = 1000L

    private fun now() = clock

    private fun newClient(connectData: Int = 0x11223344) = EnetClient(connectData, ::now, random = { 0x0BADF00D })

    // --- host-side datagram builders (the bytes a Sunshine/Wolf host would send) ---

    private fun hostHeader(
        w: EnetProtocol.Writer,
        sentTime: Int,
    ) {
        // Host addresses our peer 0, with the sent-time flag set.
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(sentTime)
    }

    private fun verifyConnectDatagram(
        outgoingPeerId: Int = 0x0042,
        reliableSeq: Int = 1,
        mtu: Int = 1024,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.VERIFY_CONNECT_LEN)
        hostHeader(w, sentTime = 50)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_VERIFY_CONNECT or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            reliableSeq,
        )
        w.u16(outgoingPeerId)
        w.u8(0x01) // incomingSessionId
        w.u8(0x02) // outgoingSessionId
        w.u32(mtu)
        w.u32(EnetProtocol.MINIMUM_WINDOW_SIZE)
        w.u32(1) // channelCount
        w.u32(0) // incomingBandwidth
        w.u32(0) // outgoingBandwidth
        w.u32(EnetProtocol.PACKET_THROTTLE_INTERVAL)
        w.u32(EnetProtocol.PACKET_THROTTLE_ACCELERATION)
        w.u32(EnetProtocol.PACKET_THROTTLE_DECELERATION)
        w.u32(0x0BADF00D) // connectID echo
        return w.toByteArray()
    }

    private fun ackDatagram(
        channelId: Int,
        reliableSeq: Int,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.ACKNOWLEDGE_LEN)
        hostHeader(w, sentTime = 60)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_ACKNOWLEDGE, channelId, reliableSeq)
        w.u16(reliableSeq)
        w.u16(0)
        return w.toByteArray()
    }

    private fun sendReliableDatagram(
        reliableSeq: Int,
        payload: ByteArray,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_RELIABLE_HEADER_LEN + payload.size)
        hostHeader(w, sentTime = 70)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetClient.DATA_CHANNEL,
            reliableSeq,
        )
        w.u16(payload.size)
        w.bytes(payload)
        return w.toByteArray()
    }

    // --- helpers to read back what the client emitted ---

    private data class ParsedCommand(
        val command: Int,
        val channelId: Int,
        val reliableSeq: Int,
        val body: ByteArray,
    )

    private fun firstCommand(datagram: ByteArray): ParsedCommand {
        val buf = ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN)
        val peerField = buf.short.toInt() and 0xFFFF
        if (peerField and EnetProtocol.HEADER_FLAG_SENT_TIME != 0) buf.short
        val command = buf.get().toInt() and 0xFF
        val channelId = buf.get().toInt() and 0xFF
        val reliableSeq = buf.short.toInt() and 0xFFFF
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        return ParsedCommand(command and EnetProtocol.COMMAND_MASK, channelId, reliableSeq, body)
    }

    @Test
    fun `connect emits a CONNECT command carrying the connect data`() {
        val client = newClient(connectData = 0x11223344)
        val cmd = firstCommand(client.connect())
        assertEquals(EnetProtocol.COMMAND_CONNECT, cmd.command)
        assertEquals(EnetProtocol.SYSTEM_CHANNEL, cmd.channelId)
        assertEquals(1, cmd.reliableSeq)
        // The connect data is the last u32 of the CONNECT body (X-SS-Connect-Data).
        val body = ByteBuffer.wrap(cmd.body).order(ByteOrder.BIG_ENDIAN)
        body.position(cmd.body.size - 4)
        assertEquals(0x11223344, body.int)
        assertEquals(EnetClient.State.CONNECTING, client.state)
    }

    @Test
    fun `VERIFY_CONNECT transitions to CONNECTED and acks`() {
        val client = newClient()
        client.connect()
        val acks = client.onDatagram(verifyConnectDatagram())
        assertEquals(EnetClient.State.CONNECTED, client.state)
        // The verify wanted an ack (it had FLAG_ACKNOWLEDGE and a sent time).
        assertEquals(1, acks.size)
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, firstCommand(acks.first()).command)
        // The CONNECT is now acknowledged: a tick must not retransmit it.
        clock += 10_000
        assertTrue(client.tick().none { firstCommand(it).command == EnetProtocol.COMMAND_CONNECT })
    }

    @Test
    fun `reliable send is acknowledged and not retransmitted`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val datagram = client.sendReliable("hello".toByteArray())!!
        val cmd = firstCommand(datagram)
        assertEquals(EnetProtocol.COMMAND_SEND_RELIABLE, cmd.command)
        assertEquals(EnetClient.DATA_CHANNEL, cmd.channelId)
        assertEquals(1, cmd.reliableSeq)
        // Host acks channel 0 seq 1.
        client.onDatagram(ackDatagram(EnetClient.DATA_CHANNEL, reliableSeq = 1))
        clock += 10_000
        assertTrue(client.tick().none { firstCommand(it).command == EnetProtocol.COMMAND_SEND_RELIABLE })
    }

    @Test
    fun `unacked reliable send is retransmitted after the timeout`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val original = client.sendReliable("input".toByteArray())!!
        clock += 600
        val retransmits = client.tick()
        assertTrue(retransmits.any { it.contentEquals(original) })
    }

    @Test
    fun `host reliable send is delivered once and acked`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val acks = client.onDatagram(sendReliableDatagram(reliableSeq = 1, payload = "rumble".toByteArray()))
        assertEquals(1, client.received.size)
        assertEquals("rumble", String(client.received.removeFirst()))
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, firstCommand(acks.first()).command)
        // A retransmit of the same seq is acked again but not re-delivered.
        client.onDatagram(sendReliableDatagram(reliableSeq = 1, payload = "rumble".toByteArray()))
        assertTrue(client.received.isEmpty())
    }

    @Test
    fun `disconnect emits a DISCONNECT and clears state`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val cmd = firstCommand(client.disconnect()!!)
        assertEquals(EnetProtocol.COMMAND_DISCONNECT, cmd.command)
        assertEquals(EnetClient.State.DISCONNECTED, client.state)
        assertNull(client.sendReliable("late".toByteArray()))
    }

    @Test
    fun `ping is emitted when the link is idle`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        clock += EnetProtocol.PING_INTERVAL_MS + 1
        assertTrue(client.tick().any { firstCommand(it).command == EnetProtocol.COMMAND_PING })
    }

    @Test
    fun `a truncated datagram is ignored`() {
        val client = newClient()
        client.connect()
        assertTrue(client.onDatagram(byteArrayOf(0x00)).isEmpty())
    }
}
