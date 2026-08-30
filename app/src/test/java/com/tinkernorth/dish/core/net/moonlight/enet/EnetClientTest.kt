// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight.enet

import com.tinkernorth.dish.core.net.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `unacked reliable send is retransmitted with a fresh sent time`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val original = client.sendReliable("input".toByteArray())!!
        clock += 600
        val retransmits = client.tick()
        val resent = retransmits.single { firstCommand(it).command == EnetProtocol.COMMAND_SEND_RELIABLE }
        // The command is byte-identical...
        assertArrayEquals(firstCommand(original).body, firstCommand(resent).body)
        assertEquals(firstCommand(original).reliableSeq, firstCommand(resent).reliableSeq)
        // ...but the datagram is not, because the header's sent time is what the
        // peer echoes back to measure the round trip. Replaying the original
        // bytes would report a round trip of however long we spent waiting.
        assertNotEquals(sentTimeOf(original), sentTimeOf(resent))
        assertEquals((clock and 0xFFFF).toInt(), sentTimeOf(resent))
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

    // --- acknowledgement generation, byte for byte ---

    @Test
    fun `a host ping is acknowledged with the echoed seq and sent time`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        clock = 0x4321
        val acks = client.onDatagram(pingDatagram(reliableSeq = 7, sentTime = 0x1234))
        assertEquals(1, acks.size)
        //   a042  peerID 0x042, session 2 in bits 12-13, sent-time flag set
        //   4321  our own sent time, the low 16 bits of the clock
        //   01ff  ACKNOWLEDGE, on the system channel
        //   0007  the reliable sequence number of the ping being acknowledged
        //   0007  receivedReliableSequenceNumber, the same
        //   1234  receivedSentTime, echoed back from the ping's own header
        assertArrayEquals(hexToBytes("a042432101ff000700071234"), acks.first())
    }

    @Test
    fun `a command wanting an ack but carrying no sent time is not acknowledged`() {
        // protocol.c abandons the datagram in this case rather than guessing a
        // round trip out of nothing; the port keeps to that.
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val w = EnetProtocol.Writer(EnetProtocol.NO_SENT_TIME_HEADER_LEN + EnetProtocol.PING_LEN)
        w.u16(0) // no sent-time flag
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_PING or EnetProtocol.FLAG_ACKNOWLEDGE, EnetProtocol.SYSTEM_CHANNEL, 3)
        assertTrue(client.onDatagram(w.toByteArray()).isEmpty())
    }

    // --- the fault that ended every live session at about 6.4 seconds ---

    @Test
    fun `a reliable BANDWIDTH_LIMIT is acknowledged`() {
        // A live Sunshine host sends this about a second after the peer connects,
        // off enet_host_bandwidth_throttle's 1000 ms tick. Leaving it
        // unacknowledged freezes the host's sent-reliable queue, which stops its
        // pings and drops the peer ENET_PEER_TIMEOUT_MINIMUM later.
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val acks = client.onDatagram(bandwidthLimitDatagram(reliableSeq = 2))
        assertEquals(1, acks.size)
        val ack = firstCommand(acks.first())
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, ack.command)
        assertEquals(EnetProtocol.SYSTEM_CHANNEL, ack.channelId)
        assertEquals(2, ack.reliableSeq)
        assertEquals(0, client.unknownCommands)
    }

    @Test
    fun `a reliable THROTTLE_CONFIGURE is acknowledged`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        val acks = client.onDatagram(throttleConfigureDatagram(reliableSeq = 3))
        assertEquals(EnetProtocol.COMMAND_ACKNOWLEDGE, firstCommand(acks.single()).command)
        assertEquals(0, client.unknownCommands)
    }

    @Test
    fun `a command we do not act on does not swallow the rest of its datagram`() {
        // The real cost of mismeasuring a command: everything behind it in the
        // same datagram is lost, acknowledgements included.
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        client.sendReliable("input".toByteArray())
        val w = EnetProtocol.Writer(HEADER_AND_TWO_COMMANDS)
        hostHeader(w, sentTime = 80)
        bandwidthLimitCommand(w, reliableSeq = 2)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_ACKNOWLEDGE, EnetClient.DATA_CHANNEL, 1)
        w.u16(1)
        w.u16(0)

        val acks = client.onDatagram(w.toByteArray())

        // The bandwidth limit was acknowledged...
        assertEquals(1, acks.size)
        // ...and the acknowledgement riding behind it still cleared our send.
        clock += 10_000
        assertTrue(client.tick().none { firstCommand(it).command == EnetProtocol.COMMAND_SEND_RELIABLE })
    }

    @Test
    fun `an unreliable send's payload is skipped so a following ack still parses`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        client.sendReliable("input".toByteArray())
        val payload = "discarded".toByteArray()
        val w =
            EnetProtocol.Writer(
                EnetProtocol.FULL_HEADER_LEN + EnetProtocol.SEND_UNRELIABLE_HEADER_LEN + payload.size +
                    EnetProtocol.ACKNOWLEDGE_LEN,
            )
        hostHeader(w, sentTime = 90)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_SEND_UNRELIABLE, EnetClient.DATA_CHANNEL, 5)
        w.u16(1) // unreliableSequenceNumber
        w.u16(payload.size)
        w.bytes(payload)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_ACKNOWLEDGE, EnetClient.DATA_CHANNEL, 1)
        w.u16(1)
        w.u16(0)

        client.onDatagram(w.toByteArray())

        clock += 10_000
        assertTrue(client.tick().none { firstCommand(it).command == EnetProtocol.COMMAND_SEND_RELIABLE })
    }

    // --- giving up on the peer, on the same clock the peer uses ---

    @Test
    fun `a peer that keeps acknowledging is never given up on`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        // Well past the old fixed retransmit budget, which ended the session at
        // about five and a half seconds no matter how healthy the link was.
        repeat(TALKATIVE_ROUNDS) { round ->
            clock += EnetProtocol.PING_INTERVAL_MS
            client.sendReliable("input".toByteArray())
            client.tick()
            client.onDatagram(ackDatagram(EnetClient.DATA_CHANNEL, reliableSeq = round + 1))
        }
        assertEquals(EnetClient.State.CONNECTED, client.state)
        assertNull(client.disconnectReason)
    }

    @Test
    fun `a peer that stops acknowledging is given up on, but not before the ENet rule says so`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        client.sendReliable("input".toByteArray())
        val start = clock

        // Six seconds of silence is NOT yet a dead peer. The rule needs both
        // ENET_PEER_TIMEOUT_MINIMUM of waiting and enough resends for the
        // doubling window to pass ENET_PEER_TIMEOUT_LIMIT, and from a cold
        // round-trip estimate that takes a good deal longer than the minimum.
        // The revision this replaces gave up here, after a flat ten resends.
        while (clock - start < BEFORE_GIVING_UP_MS) {
            clock += TICK_MS
            client.tick()
        }
        assertEquals(EnetClient.State.CONNECTED, client.state)

        // It does give up, well inside ENET_PEER_TIMEOUT_MAXIMUM.
        while (clock - start < EnetProtocol.TIMEOUT_MAXIMUM_MS && client.state == EnetClient.State.CONNECTED) {
            clock += TICK_MS
            client.tick()
        }
        assertEquals(EnetClient.State.DISCONNECTED, client.state)
        assertTrue(client.disconnectReason.orEmpty().contains("stopped acknowledging"))
    }

    @Test
    fun `an acknowledgement clears the give-up clock`() {
        val client = newClient()
        client.connect()
        client.onDatagram(verifyConnectDatagram())
        client.sendReliable("first".toByteArray())
        repeat(BEFORE_MINIMUM_TICKS) {
            clock += TICK_MS
            client.tick()
        }
        client.onDatagram(ackDatagram(EnetClient.DATA_CHANNEL, reliableSeq = 1))
        client.sendReliable("second".toByteArray())
        // The clock restarts from that acknowledgement, so the same wait again
        // is survivable.
        repeat(BEFORE_MINIMUM_TICKS) {
            clock += TICK_MS
            client.tick()
        }
        assertEquals(EnetClient.State.CONNECTED, client.state)
    }

    private fun sentTimeOf(datagram: ByteArray): Int {
        val buf = ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN)
        buf.short
        return buf.short.toInt() and 0xFFFF
    }

    private fun pingDatagram(
        reliableSeq: Int,
        sentTime: Int,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.PING_LEN)
        hostHeader(w, sentTime)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_PING or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            reliableSeq,
        )
        return w.toByteArray()
    }

    private fun bandwidthLimitCommand(
        w: EnetProtocol.Writer,
        reliableSeq: Int,
    ) {
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_BANDWIDTH_LIMIT or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            reliableSeq,
        )
        w.u32(0) // incomingBandwidth
        w.u32(0) // outgoingBandwidth
    }

    private fun bandwidthLimitDatagram(reliableSeq: Int): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.BANDWIDTH_LIMIT_LEN)
        hostHeader(w, sentTime = 80)
        bandwidthLimitCommand(w, reliableSeq)
        return w.toByteArray()
    }

    private fun throttleConfigureDatagram(reliableSeq: Int): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.THROTTLE_CONFIGURE_LEN)
        hostHeader(w, sentTime = 85)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_THROTTLE_CONFIGURE or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            reliableSeq,
        )
        w.u32(EnetProtocol.PACKET_THROTTLE_INTERVAL)
        w.u32(EnetProtocol.PACKET_THROTTLE_ACCELERATION)
        w.u32(EnetProtocol.PACKET_THROTTLE_DECELERATION)
        return w.toByteArray()
    }

    private companion object {
        const val HEADER_AND_TWO_COMMANDS =
            EnetProtocol.FULL_HEADER_LEN + EnetProtocol.BANDWIDTH_LIMIT_LEN + EnetProtocol.ACKNOWLEDGE_LEN

        // Thirty seconds of healthy traffic at the ping interval.
        const val TALKATIVE_ROUNDS = 60

        const val TICK_MS = 100L
        const val BEFORE_MINIMUM_TICKS = 40 // 4.0 s

        // Past the 5.5 s at which the old fixed retransmit budget expired, and
        // past the 6.4 s at which a live host was ending the session.
        const val BEFORE_GIVING_UP_MS = 6_500L
    }
}
