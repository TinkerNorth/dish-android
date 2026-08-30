// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import com.tinkernorth.dish.core.net.moonlight.MoonlightControlSession
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * A UDP-socket [MoonlightControlSession.Transport] connected to the host's
 * negotiated control port. The session is single-threaded per the ENet client's
 * contract, so one socket bound to the host endpoint is enough.
 */
class UdpControlTransport(
    address: String,
    port: Int,
) : MoonlightControlSession.Transport {
    private val socket = DatagramSocket()
    private val host = InetAddress.getByName(address)
    private val hostPort = port
    private val recvBuffer = ByteArray(MAX_DATAGRAM)

    init {
        socket.connect(host, port)
    }

    override fun send(datagram: ByteArray) {
        socket.send(DatagramPacket(datagram, datagram.size, host, hostPort))
    }

    // A read timeout is the normal "no datagram this tick" signal, not an error to propagate.
    @Suppress("SwallowedException")
    override fun receive(timeoutMs: Int): ByteArray? {
        socket.soTimeout = timeoutMs.coerceAtLeast(1)
        val packet = DatagramPacket(recvBuffer, recvBuffer.size)
        return try {
            socket.receive(packet)
            recvBuffer.copyOf(packet.length)
        } catch (timeout: SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_DATAGRAM = 2048
    }
}
