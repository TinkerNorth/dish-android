// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight.enet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A minimal ENet client: the connect handshake, reliable send/receive on
 * channel 0, acknowledgements, ping and disconnect. Ported to pure Kotlin from
 * the MIT-licensed cgutman/enet C source (host.c, peer.c, protocol.c; the fork
 * Wolf pins). Fragmentation and unsequenced delivery are not *produced* by this
 * client, because the Moonlight control payloads are all small single-fragment
 * reliable messages. They are still parsed on receive, along with every other
 * command number, for the reason spelled out in [onDatagram].
 *
 * The class is a PURE state machine so it unit-tests against handcrafted
 * protocol bytes with no socket. [connect], [sendReliable], [onDatagram] and
 * [tick] return the datagrams the transport must send; delivered host payloads
 * queue in [received]. A monotonic clock is injected so retransmit and ping
 * timing are deterministic in tests.
 *
 * NOT THREAD SAFE. One session's calls must be serialized by its owner; see
 * [com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession].
 */
class EnetClient(
    private val connectData: Int,
    private val nowMs: () -> Long,
    private val random: () -> Int = { (Math.random() * Int.MAX_VALUE).toInt() },
) {
    enum class State { CONNECTING, CONNECTED, DISCONNECTED }

    var state: State = State.DISCONNECTED
        private set

    /** Why the client gave up, for the session log. Null while healthy. */
    var disconnectReason: String? = null
        private set

    /** Reliable payloads delivered by the host (the encrypted control events). */
    val received: ArrayDeque<ByteArray> = ArrayDeque()

    /** Counters the session logs, so a live run says what the link actually did. */
    var acksSent: Int = 0
        private set

    var retransmits: Int = 0
        private set

    var unknownCommands: Int = 0
        private set

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

    // Round-trip bookkeeping, mirroring enet_protocol_handle_acknowledge. The
    // retransmission timeout is derived from these, not from a fixed constant.
    private var roundTripTimeMs = EnetProtocol.DEFAULT_ROUND_TRIP_TIME_MS.toLong()
    private var roundTripTimeVarianceMs = 0L
    private var sampledRtt = false

    /**
     * The send time of the oldest reliable command still waiting for its
     * acknowledgement, or 0 when nothing is overdue. Reset by ANY acknowledgement,
     * exactly as protocol.c does: it is the clock the give-up rule runs on.
     */
    private var earliestTimeoutMs = 0L

    private class Outgoing(
        val channelId: Int,
        val reliableSeq: Int,
        val command: ByteArray,
        var sentAtMs: Long,
        var sendAttempts: Int,
        var roundTripTimeout: Long,
    )

    // Reliable commands awaiting acknowledgement, keyed for O(1) ack removal.
    private val sentReliable = LinkedHashMap<Long, Outgoing>()

    /** Begin the handshake: returns the CONNECT datagram to send. */
    fun connect(): ByteArray {
        state = State.CONNECTING
        disconnectReason = null
        connectId = random()
        systemReliableSeq = 1
        val now = nowMs()
        lastReceiveMs = now
        lastPingMs = now
        return trackAndWrap(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq, buildConnect(), now)
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
        return trackAndWrap(DATA_CHANNEL, seq, buildSendReliable(seq, payload), nowMs())
    }

    /** Graceful DISCONNECT (unsequenced, matches enet_peer_disconnect_now). */
    fun disconnect(): ByteArray? {
        if (state == State.DISCONNECTED) return null
        state = State.DISCONNECTED
        disconnectReason = disconnectReason ?: "local teardown"
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
        return wrapRaw(command, nowMs())
    }

    /**
     * Feed a received datagram. Returns any datagrams to send in response
     * (acknowledgements). Delivered host payloads are appended to [received].
     *
     * EVERY COMMAND IN THE DATAGRAM GETS WALKED, not just the ones this client
     * knows what to do with. A peer packs acknowledgements and control commands
     * into one datagram, so a command we cannot measure is not one command
     * skipped, it is every command behind it in that datagram dropped. This
     * cost us the whole session once already: a live Sunshine host sends a
     * reliable BANDWIDTH_LIMIT (command 10) about a second after the peer
     * connects, off enet_host_bandwidth_throttle's 1000 ms tick. An earlier
     * revision of this parser bailed on it as unsupported and so never
     * acknowledged it. The host's sent-reliable queue then never empties, which
     * both stops its pings and freezes its lastReceiveTime, and
     * enet_protocol_check_timeouts drops the peer ENET_PEER_TIMEOUT_MINIMUM
     * (5000 ms) later. It read as "CLIENT DISCONNECTED about 6.4 seconds in"
     * with controller input flowing right up to the cut.
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
        val now = nowMs()
        lastReceiveMs = now
        val acks = mutableListOf<ByteArray>()
        while (buf.remaining() >= EnetProtocol.COMMAND_HEADER_LEN) {
            val command = buf.get().toInt() and 0xFF
            val channelId = buf.get().toInt() and 0xFF
            val reliableSeq = buf.short.toInt() and 0xFFFF
            val header = EnetProtocol.CommandHeader(command, channelId, reliableSeq)
            if (!handleCommand(header, buf, sentTime, hasSentTime, acks, now)) break
        }
        return acks
    }

    /**
     * Advance time: retransmit overdue reliable commands, give up on a peer that
     * has stopped acknowledging, and ping when the link is idle.
     */
    fun tick(): List<ByteArray> {
        val now = nowMs()
        val out = mutableListOf<ByteArray>()
        for (cmd in sentReliable.values) {
            if (now - cmd.sentAtMs < cmd.roundTripTimeout) continue
            if (earliestTimeoutMs == 0L || cmd.sentAtMs < earliestTimeoutMs) earliestTimeoutMs = cmd.sentAtMs
            if (hasTimedOut(cmd, now)) {
                state = State.DISCONNECTED
                disconnectReason =
                    "peer stopped acknowledging: channel ${cmd.channelId} seq ${cmd.reliableSeq} " +
                    "unacked for ${now - earliestTimeoutMs} ms over ${cmd.sendAttempts} sends"
                return out
            }
            cmd.sendAttempts += 1
            cmd.roundTripTimeout = retryTimeoutFor(cmd.sendAttempts)
            cmd.sentAtMs = now
            retransmits += 1
            // Re-wrap rather than replay: the header's sent time is what the peer
            // echoes back to measure the round trip, so a stale one poisons its RTT.
            out += wrapRaw(cmd.command, now)
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

    /**
     * protocol.c enet_protocol_check_timeouts: give up either after
     * timeoutMaximum outright, or after timeoutMinimum once the command has been
     * resent enough times that the doubling window has passed timeoutLimit.
     */
    private fun hasTimedOut(
        cmd: Outgoing,
        now: Long,
    ): Boolean {
        val waited = now - earliestTimeoutMs
        if (waited >= EnetProtocol.TIMEOUT_MAXIMUM_MS) return true
        val attemptWindow = 1L shl (cmd.sendAttempts - 1).coerceIn(0, MAX_ATTEMPT_SHIFT)
        return attemptWindow >= EnetProtocol.TIMEOUT_LIMIT && waited >= EnetProtocol.TIMEOUT_MINIMUM_MS
    }

    /** The peer-level retransmission timeout, scaled by how often we have resent. */
    private fun retryTimeoutFor(sendAttempts: Int): Long {
        val base = peerRoundTripTimeout()
        val scale = if (sendAttempts < EnetProtocol.TIMEOUT_LIMIT) sendAttempts else EnetProtocol.TIMEOUT_LIMIT
        return base * scale.coerceAtLeast(1)
    }

    private fun peerRoundTripTimeout(): Long {
        val variance = 4 * maxOf(1L, roundTripTimeVarianceMs)
        val timeout = roundTripTimeMs + minOf(roundTripTimeMs, variance)
        return timeout.coerceIn(1L, (EnetProtocol.TIMEOUT_MAXIMUM_MS / RTO_CAP_DIVISOR).toLong())
    }

    // Each early return is a distinct malformed/short-command bail; splitting them would
    // obscure the one-command-per-branch parse.
    @Suppress("ReturnCount", "LongParameterList", "CyclomaticComplexMethod")
    private fun handleCommand(
        header: EnetProtocol.CommandHeader,
        buf: ByteBuffer,
        sentTime: Int,
        hasSentTime: Boolean,
        acks: MutableList<ByteArray>,
        now: Long,
    ): Boolean {
        val number = header.commandNumber
        val fixed = EnetProtocol.sizeForCommand(number)
        if (fixed == 0) {
            unknownCommands += 1
            return false
        }
        val bodyLen = fixed - EnetProtocol.COMMAND_HEADER_LEN
        if (buf.remaining() < bodyLen) return false
        val body = ByteArray(bodyLen).also { if (bodyLen > 0) buf.get(it) }
        val payload = readPayload(number, body, buf) ?: return false

        when (number) {
            EnetProtocol.COMMAND_VERIFY_CONNECT -> consumeVerifyConnect(body)
            EnetProtocol.COMMAND_ACKNOWLEDGE -> consumeAcknowledge(header, body, now)
            EnetProtocol.COMMAND_SEND_RELIABLE -> deliverReliable(header.reliableSequenceNumber, payload)
            EnetProtocol.COMMAND_DISCONNECT -> {
                state = State.DISCONNECTED
                disconnectReason = "peer sent DISCONNECT"
            }
            // PING, the throttle/bandwidth advisories and the delivery modes this
            // client does not produce all need nothing beyond the acknowledgement
            // below, which is the whole point of measuring them correctly.
            else -> Unit
        }

        if (header.wantsAck) {
            // protocol.c refuses to acknowledge a command that arrived without a
            // sent time and abandons the rest of the datagram; keep to that so
            // both ends agree on what was and was not acknowledged.
            if (!hasSentTime) return false
            acks += buildAcknowledge(header, sentTime)
            acksSent += 1
        }
        return true
    }

    /**
     * Consume the `dataLength`-counted payload the SEND_* commands carry after
     * their fixed header. Returns an empty array for the commands that carry
     * none, or null when the datagram is truncated.
     */
    private fun readPayload(
        commandNumber: Int,
        body: ByteArray,
        buf: ByteBuffer,
    ): ByteArray? {
        val offset = EnetProtocol.dataLengthOffset(commandNumber)
        if (offset < 0) return EMPTY
        val at = offset - EnetProtocol.COMMAND_HEADER_LEN
        if (at + 2 > body.size) return null
        val dataLength = ((body[at].toInt() and 0xFF) shl 8) or (body[at + 1].toInt() and 0xFF)
        if (buf.remaining() < dataLength) return null
        return ByteArray(dataLength).also { if (dataLength > 0) buf.get(it) }
    }

    private fun consumeVerifyConnect(body: ByteArray) {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        outgoingPeerId = buf.short.toInt() and 0xFFFF
        incomingSessionId = buf.get().toInt() and 0xFF
        outgoingSessionId = buf.get().toInt() and 0xFF
        val theirMtu = buf.int
        val theirWindow = buf.int
        if (theirMtu in EnetProtocol.PROTOCOL_MINIMUM_MTU..EnetProtocol.PROTOCOL_MAXIMUM_MTU && theirMtu < mtu) {
            mtu = theirMtu
        }
        if (theirWindow in EnetProtocol.MINIMUM_WINDOW_SIZE..EnetProtocol.MAXIMUM_WINDOW_SIZE && theirWindow < windowSize) {
            windowSize = theirWindow
        }
        // The host acked our CONNECT by sending VERIFY_CONNECT; clear it and go live.
        sentReliable.remove(key(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq))
        earliestTimeoutMs = 0
        state = State.CONNECTED
    }

    private fun consumeAcknowledge(
        header: EnetProtocol.CommandHeader,
        body: ByteArray,
        now: Long,
    ) {
        val buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val recvReliableSeq = buf.short.toInt() and 0xFFFF
        val recvSentTime = buf.short.toInt() and 0xFFFF
        val acked = sentReliable.remove(key(header.channelId, recvReliableSeq))
        // An acknowledgement is the only thing that proves the peer is still
        // there, so it clears the give-up clock outright (protocol.c does the same).
        earliestTimeoutMs = 0
        sampleRoundTrip(acked?.let { now - it.sentAtMs } ?: ((now.toInt() - recvSentTime) and 0xFFFF).toLong())
    }

    /** enet_protocol_handle_acknowledge's smoothed round-trip estimate. */
    private fun sampleRoundTrip(rawSample: Long) {
        val sample = rawSample.coerceIn(1L, EnetProtocol.TIMEOUT_MAXIMUM_MS.toLong())
        if (!sampledRtt) {
            roundTripTimeMs = sample
            roundTripTimeVarianceMs = (sample + 1) / 2
            sampledRtt = true
            return
        }
        roundTripTimeVarianceMs -= (roundTripTimeVarianceMs + 3) / 4
        if (sample >= roundTripTimeMs) {
            val diff = sample - roundTripTimeMs
            roundTripTimeVarianceMs += (diff + 3) / 4
            roundTripTimeMs += (diff + 7) / 8
        } else {
            val diff = roundTripTimeMs - sample
            roundTripTimeVarianceMs += (diff + 3) / 4
            roundTripTimeMs -= (diff + 7) / 8
        }
    }

    private fun deliverReliable(
        reliableSeq: Int,
        payload: ByteArray,
    ) {
        // In-order gate on the 16-bit sequence space: a retransmitted command
        // (seq already delivered) is acked again by the caller but not
        // re-delivered. The signed difference keeps that true across the wrap.
        val ahead = ((reliableSeq - incomingReliableSeq) and 0xFFFF).let { if (it > 0x7FFF) it - 0x10000 else it }
        if (ahead <= 0) return
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
        return trackAndWrap(EnetProtocol.SYSTEM_CHANNEL, systemReliableSeq, w.toByteArray(), now)
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

    private fun trackAndWrap(
        channelId: Int,
        reliableSeq: Int,
        command: ByteArray,
        now: Long,
    ): ByteArray {
        sentReliable[key(channelId, reliableSeq)] =
            Outgoing(channelId, reliableSeq, command, now, sendAttempts = 1, roundTripTimeout = peerRoundTripTimeout())
        return wrapRaw(command, now)
    }

    private fun key(
        channelId: Int,
        reliableSeq: Int,
    ): Long = (channelId.toLong() shl 32) or (reliableSeq.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DATA_CHANNEL = 0
        private const val CHANNEL_COUNT = 1
        private val EMPTY = ByteArray(0)

        // protocol.c caps a command's retransmission timeout at a fifth of the
        // peer's maximum timeout.
        private const val RTO_CAP_DIVISOR = 5

        // Keeps the attempt-window shift inside a Long once a command has been
        // resent absurdly often; by then it has long since passed timeoutLimit.
        private const val MAX_ATTEMPT_SHIFT = 30
    }
}
