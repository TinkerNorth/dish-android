// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight.enet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal ENet client: the connect handshake, reliable send/receive on
 * channel 0, acknowledgements, ping and disconnect. Ported to pure Kotlin from
 * the MIT-licensed cgutman/enet C source (host.c, peer.c, protocol.c; the fork
 * Wolf pins). Fragmentation, unsequenced delivery, throttling and bandwidth
 * commands are intentionally omitted: the Moonlight control payloads are all
 * small single-fragment reliable messages.
 *
 * The class is a PURE state machine so it unit-tests against handcrafted
 * protocol bytes with no socket. [connect], [sendReliable], [onDatagram] and
 * [tick] return the datagrams the transport must send; delivered host payloads
 * queue in [received]. A monotonic clock is injected so retransmit and ping
 * timing are deterministic in tests.
 */
class EnetClient(
    private val connectData: Int,
    private val nowMs: () -> Long,
    private val random: () -> Int = { (Math.random() * Int.MAX_VALUE).toInt() },
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    var state: State = State.DISCONNECTED
        private set

    /** Reliable payloads delivered by the host (the encrypted control events). */
    val received: ArrayDeque<ByteArray> = ArrayDeque()

    // Our peer identity. incomingPeerId is our slot (0); outgoingPeerId is the
    // host's id for us, learned from VERIFY_CONNECT.
    private val incomingPeerId = 0
    private var outgoingPeerId = EnetProtocol.MAXIMUM_PEER_ID
    private var incomingSessionId = 0xFF
    private var outgoingSessionId = 0xFF
    private var connectId = 0
    private var mtu = EnetProtocol.DEFAULT_MTU
    private var windowSize = EnetProtocol.MAXIMUM_WINDOW_SIZE

    // Channel-0 outgoing reliable sequence (the connect uses the system channel 0xFF).
    private var channelReliableSeq = 0
    private var systemReliableSeq = 0

    // The highest channel-0 reliable seq we have delivered, so a retransmitted
    // host command is acked again but not delivered twice.
    private var incomingReliableSeq = 0

    private var lastReceiveMs = 0L
    private var lastPingMs = 0L

    private data class Outgoing(
        val channelId: Int,
        val reliableSeq: Int,
        val datagram: ByteArray,
        var sentAtMs: Long,
        var attempts: Int,
    )

    // Reliable commands awaiting acknowledgement, keyed for O(1) ack removal.
    private val sentReliable = LinkedHashMap<Long, Outgoing>()

    /** Begin the handshake: returns the CONNECT datagram to send. */
    fun connect(): ByteArray {
        state = State.CONNECTING
        connectId = random()
        systemReliableSeq = 1
        val now = nowMs()
        lastReceiveMs = now
        lastPingMs = now
        val command = buildConnect()
        val datagram = wrapRaw(command, now)
        track(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq, datagram, now)
        return datagram
    }

    /**
     * Queue a reliable payload on channel 0 and return the datagram to send.
     * Returns null when not connected, so the caller drops input for a dead
     * session rather than framing into the void.
     */
    fun sendReliable(payload: ByteArray): ByteArray? {
        if (state != State.CONNECTED) return null
        channelReliableSeq += 1
        val seq = channelReliableSeq
        val now = nowMs()
        val command = buildSendReliable(seq, payload)
        val datagram = wrapRaw(command, now)
        track(DATA_CHANNEL, seq, datagram, now)
        return datagram
    }

    /** Graceful DISCONNECT (unsequenced, matches enet_peer_disconnect_now). */
    fun disconnect(): ByteArray? {
        if (state == State.DISCONNECTED) return null
        state = State.DISCONNECTED
        val now = nowMs()
        val command =
            EnetProtocol
                .Writer(EnetProtocol.DISCONNECT_LEN)
                .also {
                    EnetProtocol.commandHeader(
                        it,
                        EnetProtocol.COMMAND_DISCONNECT or EnetProtocol.FLAG_UNSEQUENCED,
                        EnetProtocol.SYSTEM_CHANNEL,
                        0,
                    )
                    it.u32(0) // disconnect data
                }.toByteArray()
        return wrapRaw(command, now)
    }

    /**
     * Feed a received datagram. Returns any datagrams to send in response
     * (acknowledgements). Delivered host payloads are appended to [received].
     */
    fun onDatagram(datagram: ByteArray): List<ByteArray> {
        if (datagram.size < EnetProtocol.NO_SENT_TIME_HEADER_LEN) return emptyList()
        val buf = ByteBuffer.wrap(datagram).order(ByteOrder.BIG_ENDIAN)
        val peerField = buf.short.toInt() and 0xFFFF
        val hasSentTime = peerField and EnetProtocol.HEADER_FLAG_SENT_TIME != 0
        val compressed = peerField and EnetProtocol.HEADER_FLAG_COMPRESSED != 0
        if (compressed) return emptyList() // this client never negotiates compression
        var sentTime = 0
        if (hasSentTime) {
            if (buf.remaining() < 2) return emptyList()
            sentTime = buf.short.toInt() and 0xFFFF
        }
        lastReceiveMs = nowMs()
        val acks = mutableListOf<ByteArray>()
        while (buf.remaining() >= EnetProtocol.COMMAND_HEADER_LEN) {
            val command = buf.get().toInt() and 0xFF
            val channelId = buf.get().toInt() and 0xFF
            val reliableSeq = buf.short.toInt() and 0xFFFF
            val header = EnetProtocol.CommandHeader(command, channelId, reliableSeq)
            if (!handleCommand(header, buf, sentTime, hasSentTime, acks)) break
        }
        return acks
    }

    /** Advance time: retransmit unacked commands and ping when idle. */
    fun tick(): List<ByteArray> {
        val now = nowMs()
        val out = mutableListOf<ByteArray>()
        for (cmd in sentReliable.values) {
            if (now - cmd.sentAtMs < RETRANSMIT_TIMEOUT_MS) continue
            cmd.attempts += 1
            if (cmd.attempts > MAX_RETRANSMITS) {
                state = State.DISCONNECTED
                return out
            }
            cmd.sentAtMs = now
            out += cmd.datagram
        }
        if (state == State.CONNECTED &&
            now - lastReceiveMs >= EnetProtocol.PING_INTERVAL_MS &&
            now - lastPingMs >= EnetProtocol.PING_INTERVAL_MS
        ) {
            lastPingMs = now
            out += buildPing(now)
        }
        return out
    }

    // Each early return is a distinct malformed/short-command bail; splitting them would
    // obscure the one-command-per-branch parse.
    @Suppress("ReturnCount")
    private fun handleCommand(
        header: EnetProtocol.CommandHeader,
        buf: ByteBuffer,
        sentTime: Int,
        hasSentTime: Boolean,
        acks: MutableList<ByteArray>,
    ): Boolean {
        when (header.commandNumber) {
            EnetProtocol.COMMAND_VERIFY_CONNECT -> {
                if (buf.remaining() < EnetProtocol.VERIFY_CONNECT_LEN - EnetProtocol.COMMAND_HEADER_LEN) return false
                consumeVerifyConnect(buf)
            }
            EnetProtocol.COMMAND_ACKNOWLEDGE -> {
                if (buf.remaining() < EnetProtocol.ACKNOWLEDGE_LEN - EnetProtocol.COMMAND_HEADER_LEN) return false
                val recvReliableSeq = buf.short.toInt() and 0xFFFF
                buf.short // receivedSentTime
                acknowledge(header.channelId, recvReliableSeq)
            }
            EnetProtocol.COMMAND_SEND_RELIABLE -> {
                if (buf.remaining() < EnetProtocol.SEND_RELIABLE_HEADER_LEN - EnetProtocol.COMMAND_HEADER_LEN) return false
                val dataLength = buf.short.toInt() and 0xFFFF
                if (buf.remaining() < dataLength) return false
                val payload = ByteArray(dataLength)
                buf.get(payload)
                deliverReliable(header.reliableSequenceNumber, payload)
            }
            EnetProtocol.COMMAND_PING -> Unit
            EnetProtocol.COMMAND_DISCONNECT -> {
                if (buf.remaining() < EnetProtocol.DISCONNECT_LEN - EnetProtocol.COMMAND_HEADER_LEN) return false
                buf.int
                state = State.DISCONNECTED
            }
            else -> return false // an unsupported command aborts the rest of the packet
        }
        if (header.wantsAck && hasSentTime) {
            acks += buildAcknowledge(header, sentTime)
        }
        return true
    }

    private fun consumeVerifyConnect(buf: ByteBuffer) {
        outgoingPeerId = buf.short.toInt() and 0xFFFF
        incomingSessionId = buf.get().toInt() and 0xFF
        outgoingSessionId = buf.get().toInt() and 0xFF
        val theirMtu = buf.int
        val theirWindow = buf.int
        buf.int // channelCount
        buf.int // incomingBandwidth
        buf.int // outgoingBandwidth
        buf.int // packetThrottleInterval
        buf.int // packetThrottleAcceleration
        buf.int // packetThrottleDeceleration
        buf.int // connectID (already validated implicitly by the handshake)
        if (theirMtu in EnetProtocol.PROTOCOL_MINIMUM_MTU..EnetProtocol.PROTOCOL_MAXIMUM_MTU && theirMtu < mtu) {
            mtu = theirMtu
        }
        if (theirWindow in EnetProtocol.MINIMUM_WINDOW_SIZE..EnetProtocol.MAXIMUM_WINDOW_SIZE && theirWindow < windowSize) {
            windowSize = theirWindow
        }
        // The host acked our CONNECT by sending VERIFY_CONNECT; clear it and go live.
        sentReliable.remove(key(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq))
        state = State.CONNECTED
    }

    private fun acknowledge(
        channelId: Int,
        reliableSeq: Int,
    ) {
        sentReliable.remove(key(channelId, reliableSeq))
    }

    private fun deliverReliable(
        reliableSeq: Int,
        payload: ByteArray,
    ) {
        // In-order gate: a retransmitted command (seq already delivered) is acked
        // again by the caller but not re-delivered.
        if (reliableSeq <= incomingReliableSeq) return
        incomingReliableSeq = reliableSeq
        received.addLast(payload)
    }

    private fun buildConnect(): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.CONNECT_LEN)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_CONNECT or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            systemReliableSeq,
        )
        w.u16(incomingPeerId)
        w.u8(0xFF) // incomingSessionId (unset)
        w.u8(0xFF) // outgoingSessionId (unset)
        w.u32(mtu)
        w.u32(windowSize)
        w.u32(CHANNEL_COUNT)
        w.u32(0) // incomingBandwidth
        w.u32(0) // outgoingBandwidth
        w.u32(EnetProtocol.PACKET_THROTTLE_INTERVAL)
        w.u32(EnetProtocol.PACKET_THROTTLE_ACCELERATION)
        w.u32(EnetProtocol.PACKET_THROTTLE_DECELERATION)
        w.u32(connectId)
        w.u32(connectData)
        return w.toByteArray()
    }

    private fun buildSendReliable(
        seq: Int,
        payload: ByteArray,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.SEND_RELIABLE_HEADER_LEN + payload.size)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_SEND_RELIABLE or EnetProtocol.FLAG_ACKNOWLEDGE, DATA_CHANNEL, seq)
        w.u16(payload.size)
        w.bytes(payload)
        return w.toByteArray()
    }

    private fun buildAcknowledge(
        header: EnetProtocol.CommandHeader,
        sentTime: Int,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.ACKNOWLEDGE_LEN)
        EnetProtocol.commandHeader(w, EnetProtocol.COMMAND_ACKNOWLEDGE, header.channelId, header.reliableSequenceNumber)
        w.u16(header.reliableSequenceNumber)
        w.u16(sentTime)
        return wrapRaw(w.toByteArray(), nowMs())
    }

    private fun buildPing(now: Long): ByteArray {
        systemReliableSeq += 1
        val w = EnetProtocol.Writer(EnetProtocol.PING_LEN)
        EnetProtocol.commandHeader(
            w,
            EnetProtocol.COMMAND_PING or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            systemReliableSeq,
        )
        val datagram = wrapRaw(w.toByteArray(), now)
        track(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq, datagram, now)
        return datagram
    }

    // Wrap one command in a datagram with the sent-time header.
    private fun wrapRaw(
        command: ByteArray,
        now: Long,
    ): ByteArray {
        val w = EnetProtocol.Writer(EnetProtocol.FULL_HEADER_LEN + command.size)
        EnetProtocol.writeHeader(w, outgoingPeerId, outgoingSessionId, sentTime = (now and 0xFFFF).toInt())
        w.bytes(command)
        return w.toByteArray()
    }

    private fun track(
        channelId: Int,
        reliableSeq: Int,
        datagram: ByteArray,
        now: Long,
    ) {
        sentReliable[key(channelId, reliableSeq)] = Outgoing(channelId, reliableSeq, datagram, now, attempts = 0)
    }

    private fun key(
        channelId: Int,
        reliableSeq: Int,
    ): Long = (channelId.toLong() shl 32) or (reliableSeq.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DATA_CHANNEL = 0
        private const val CHANNEL_COUNT = 1
        private const val RETRANSMIT_TIMEOUT_MS = 500L
        private const val MAX_RETRANSMITS = 10
    }
}
