// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight.enet

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The minimal ENet wire protocol needed for the Moonlight control stream:
 * the connect handshake, reliable send/receive on one channel, acknowledgements,
 * ping and disconnect. Ported to pure Kotlin from the MIT-licensed
 * cgutman/enet C source (the fork Wolf pins, commit 44c85e16); see THIRD_PARTY.md.
 * Only the subset the client needs is reproduced; fragmentation, unsequenced,
 * bandwidth and compression commands are not sent by this client and are
 * ignored on receive.
 *
 * All multi-byte fields are network byte order (big-endian), matching ENet's
 * ENET_HOST_TO_NET_* macros.
 */
internal object EnetProtocol {
    // Command numbers (protocol.h ENetProtocolCommand).
    const val COMMAND_NONE = 0
    const val COMMAND_ACKNOWLEDGE = 1
    const val COMMAND_CONNECT = 2
    const val COMMAND_VERIFY_CONNECT = 3
    const val COMMAND_DISCONNECT = 4
    const val COMMAND_PING = 5
    const val COMMAND_SEND_RELIABLE = 6
    const val COMMAND_SEND_UNRELIABLE = 7
    const val COMMAND_SEND_FRAGMENT = 8
    const val COMMAND_SEND_UNSEQUENCED = 9
    const val COMMAND_BANDWIDTH_LIMIT = 10
    const val COMMAND_THROTTLE_CONFIGURE = 11
    const val COMMAND_SEND_UNRELIABLE_FRAGMENT = 12
    const val COMMAND_COUNT = 13
    const val COMMAND_MASK = 0x0F

    // Command flags (protocol.h ENetProtocolFlag).
    const val FLAG_ACKNOWLEDGE = 1 shl 7
    const val FLAG_UNSEQUENCED = 1 shl 6

    // Header flags packed into the 16-bit peerID field.
    const val HEADER_FLAG_COMPRESSED = 1 shl 14
    const val HEADER_FLAG_SENT_TIME = 1 shl 15
    const val HEADER_SESSION_MASK = 3 shl 12
    const val HEADER_SESSION_SHIFT = 12

    const val MAXIMUM_PEER_ID = 0xFFF

    // A session id neither side has assigned yet (peer.c enet_peer_reset).
    const val SESSION_ID_UNSET = 0xFF

    // The system channel 0xFF carries connect/ping/disconnect; data rides channel 0.
    const val SYSTEM_CHANNEL = 0xFF

    // Unsigned field widths, and the 16-bit reliable sequence space they bound.
    const val U8_MASK = 0xFF
    const val U16_MASK = 0xFFFF
    const val SEQ_RANGE = 0x10000
    const val SEQ_HALF_RANGE = 0x7FFF

    const val PROTOCOL_MINIMUM_MTU = 576
    const val PROTOCOL_MAXIMUM_MTU = 4096
    const val MINIMUM_WINDOW_SIZE = 4096
    const val MAXIMUM_WINDOW_SIZE = 65536

    const val NO_SENT_TIME_HEADER_LEN = 2
    const val FULL_HEADER_LEN = 4
    const val COMMAND_HEADER_LEN = 4

    const val ACKNOWLEDGE_LEN = 8
    const val CONNECT_LEN = 48
    const val VERIFY_CONNECT_LEN = 44
    const val DISCONNECT_LEN = 8
    const val PING_LEN = 4
    const val SEND_RELIABLE_HEADER_LEN = 6
    const val SEND_UNRELIABLE_HEADER_LEN = 8
    const val SEND_UNSEQUENCED_HEADER_LEN = 8
    const val SEND_FRAGMENT_HEADER_LEN = 24
    const val BANDWIDTH_LIMIT_LEN = 12
    const val THROTTLE_CONFIGURE_LEN = 16

    // Default peer parameters (enet.h ENET_PEER_* / peer.c enet_peer_reset).
    const val DEFAULT_MTU = 1400
    const val PACKET_THROTTLE_INTERVAL = 5000
    const val PACKET_THROTTLE_ACCELERATION = 2
    const val PACKET_THROTTLE_DECELERATION = 2
    const val PING_INTERVAL_MS = 500

    // Peer liveness (enet.h ENET_PEER_*). These are the numbers the host on the
    // other end is using, so the client has to keep to the same clock or one
    // side gives up while the other still thinks the session is healthy.
    const val DEFAULT_ROUND_TRIP_TIME_MS = 500
    const val TIMEOUT_LIMIT = 32
    const val TIMEOUT_MINIMUM_MS = 5000
    const val TIMEOUT_MAXIMUM_MS = 30000

    data class CommandHeader(
        val command: Int,
        val channelId: Int,
        val reliableSequenceNumber: Int,
    ) {
        val commandNumber: Int get() = command and COMMAND_MASK
        val wantsAck: Boolean get() = command and FLAG_ACKNOWLEDGE != 0
    }
}

/** A big-endian scratch buffer for one datagram or one command. */
internal class EnetWriter(
    capacity: Int,
) {
    val buffer: ByteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.BIG_ENDIAN)

    fun u8(value: Int): EnetWriter = apply { buffer.put(value.toByte()) }

    fun u16(value: Int): EnetWriter = apply { buffer.putShort(value.toShort()) }

    fun u32(value: Int): EnetWriter = apply { buffer.putInt(value) }

    fun bytes(value: ByteArray): EnetWriter = apply { buffer.put(value) }

    fun toByteArray(): ByteArray {
        buffer.flip()
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }
}

/**
 * The outer datagram header. peerID is the low 12 bits; session id and the
 * sent-time/compressed flags share the remaining bits (protocol.c
 * enet_protocol_handle_incoming_commands).
 */
internal fun writeHeader(
    w: EnetWriter,
    outgoingPeerId: Int,
    sessionId: Int,
    sentTime: Int?,
) {
    val peerBits = outgoingPeerId and EnetProtocol.MAXIMUM_PEER_ID
    val peerIsAssigned = outgoingPeerId < EnetProtocol.MAXIMUM_PEER_ID
    val sessionBits = if (peerIsAssigned) (sessionId shl EnetProtocol.HEADER_SESSION_SHIFT) and EnetProtocol.HEADER_SESSION_MASK else 0
    val sentTimeBit = if (sentTime != null) EnetProtocol.HEADER_FLAG_SENT_TIME else 0
    val field = peerBits or sessionBits or sentTimeBit
    w.u16(field)
    if (sentTime != null) w.u16(sentTime and EnetProtocol.U16_MASK)
}

internal fun commandHeader(
    w: EnetWriter,
    command: Int,
    channelId: Int,
    reliableSequenceNumber: Int,
) {
    w.u8(command)
    w.u8(channelId)
    w.u16(reliableSequenceNumber and EnetProtocol.U16_MASK)
}

/**
 * The fixed on-wire size of one command, mirroring protocol.c's
 * `commandSizes` table exactly. For the SEND_* commands this is the header
 * only: a `dataLength` payload follows it.
 *
 * EVERY COMMAND NUMBER HAS TO BE IN HERE, including the ones this client
 * never sends. A peer bundles several commands into one datagram, so a
 * command whose size we do not know is not one command lost, it is the rest
 * of that datagram lost, acknowledgements included. See [EnetClient.onDatagram].
 */
internal fun sizeForCommand(commandNumber: Int): Int =
    when (commandNumber) {
        EnetProtocol.COMMAND_ACKNOWLEDGE -> EnetProtocol.ACKNOWLEDGE_LEN
        EnetProtocol.COMMAND_CONNECT -> EnetProtocol.CONNECT_LEN
        EnetProtocol.COMMAND_VERIFY_CONNECT -> EnetProtocol.VERIFY_CONNECT_LEN
        EnetProtocol.COMMAND_DISCONNECT -> EnetProtocol.DISCONNECT_LEN
        EnetProtocol.COMMAND_PING -> EnetProtocol.PING_LEN
        EnetProtocol.COMMAND_SEND_RELIABLE -> EnetProtocol.SEND_RELIABLE_HEADER_LEN
        EnetProtocol.COMMAND_SEND_UNRELIABLE -> EnetProtocol.SEND_UNRELIABLE_HEADER_LEN
        EnetProtocol.COMMAND_SEND_FRAGMENT -> EnetProtocol.SEND_FRAGMENT_HEADER_LEN
        EnetProtocol.COMMAND_SEND_UNSEQUENCED -> EnetProtocol.SEND_UNSEQUENCED_HEADER_LEN
        EnetProtocol.COMMAND_BANDWIDTH_LIMIT -> EnetProtocol.BANDWIDTH_LIMIT_LEN
        EnetProtocol.COMMAND_THROTTLE_CONFIGURE -> EnetProtocol.THROTTLE_CONFIGURE_LEN
        EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT -> EnetProtocol.SEND_FRAGMENT_HEADER_LEN
        else -> 0
    }

/**
 * Whether a command carries a `dataLength`-counted payload after its fixed
 * header, and at what offset within that header the count sits.
 */
internal fun dataLengthOffset(commandNumber: Int): Int =
    when (commandNumber) {
        EnetProtocol.COMMAND_SEND_RELIABLE -> EnetProtocol.COMMAND_HEADER_LEN
        EnetProtocol.COMMAND_SEND_UNRELIABLE, EnetProtocol.COMMAND_SEND_UNSEQUENCED -> EnetProtocol.COMMAND_HEADER_LEN + Short.SIZE_BYTES
        EnetProtocol.COMMAND_SEND_FRAGMENT, EnetProtocol.COMMAND_SEND_UNRELIABLE_FRAGMENT ->
            EnetProtocol.COMMAND_HEADER_LEN + Short.SIZE_BYTES
        else -> -1
    }
