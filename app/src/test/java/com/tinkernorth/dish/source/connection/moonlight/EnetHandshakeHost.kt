// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.enet.EnetProtocol
import com.tinkernorth.dish.core.net.moonlight.enet.EnetWriter
import com.tinkernorth.dish.core.net.moonlight.enet.commandHeader
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * The control channel of a Moonlight host on loopback, as far as a session needs it to come up: a
 * CONNECT is answered with the VERIFY_CONNECT a real host sends, echoing the client's connect id,
 * and nothing after it is answered at all.
 */
internal class EnetHandshakeHost : Closeable {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    init {
        Thread {
            runCatching {
                while (true) answer(receive())
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun receive(): DatagramPacket {
        val packet = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        socket.receive(packet)
        return packet
    }

    private fun answer(packet: DatagramPacket) {
        val datagram = ByteBuffer.wrap(packet.data, 0, packet.length)
        val isAConnect = packet.length == CONNECT_DATAGRAM_LEN && commandIn(datagram) == EnetProtocol.COMMAND_CONNECT
        if (!isAConnect) return
        val reply = verifyConnect(datagram.getInt(CONNECT_ID_AT))
        socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
    }

    private fun commandIn(datagram: ByteBuffer): Int = datagram.get(EnetProtocol.FULL_HEADER_LEN).toInt() and EnetProtocol.COMMAND_MASK

    private fun verifyConnect(connectId: Int): ByteArray {
        val w = EnetWriter(EnetProtocol.FULL_HEADER_LEN + EnetProtocol.VERIFY_CONNECT_LEN)
        // To the client's peer 0, with the time it was sent.
        w.u16(EnetProtocol.HEADER_FLAG_SENT_TIME)
        w.u16(SENT_TIME)
        commandHeader(
            w,
            EnetProtocol.COMMAND_VERIFY_CONNECT or EnetProtocol.FLAG_ACKNOWLEDGE,
            EnetProtocol.SYSTEM_CHANNEL,
            HOST_RELIABLE_SEQ,
        )
        w.u16(HOST_PEER_ID)
        w.u8(SESSION_ID)
        w.u8(SESSION_ID)
        w.u32(EnetProtocol.DEFAULT_MTU)
        w.u32(EnetProtocol.MINIMUM_WINDOW_SIZE)
        w.u32(CHANNEL_COUNT)
        w.u32(UNLIMITED_BANDWIDTH)
        w.u32(UNLIMITED_BANDWIDTH)
        w.u32(EnetProtocol.PACKET_THROTTLE_INTERVAL)
        w.u32(EnetProtocol.PACKET_THROTTLE_ACCELERATION)
        w.u32(EnetProtocol.PACKET_THROTTLE_DECELERATION)
        w.u32(connectId)
        return w.toByteArray()
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 2048
        const val CONNECT_DATAGRAM_LEN = EnetProtocol.FULL_HEADER_LEN + EnetProtocol.CONNECT_LEN

        // The CONNECT command's connectID: behind the datagram header, the command header and 36 bytes of fields.
        const val CONNECT_ID_AT = 44
        const val SENT_TIME = 50
        const val HOST_RELIABLE_SEQ = 1
        const val HOST_PEER_ID = 0x42
        const val SESSION_ID = 0
        const val CHANNEL_COUNT = 1
        const val UNLIMITED_BANDWIDTH = 0
    }
}
