// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightMediaPing
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * Keeps the host's video and audio RTP streams alive by pinging the ports its
 * SETUP replies named. See [MoonlightMediaPing] for what the datagram has to
 * look like and why.
 *
 * ONE LONG-LIVED SOCKET PER STREAM, and it has to be. The host learns where to
 * send RTP from the source address of this datagram and then streams back to
 * that exact socket, so a throwaway socket per ping tells it a new port every
 * time and closes the one it just learned. Keeping the socket open also means
 * the legacy 4-byte form has somewhere to work: that path is matched by source
 * address rather than by payload, so the socket is bound to the same port number
 * it sends to whenever the OS will allow it.
 *
 * Whatever arrives on these sockets is read and thrown away. This path
 * negotiates the media streams and decodes nothing, but an unread socket fills
 * its receive buffer and starts dropping, which on some stacks is visible to the
 * far end as a dead peer.
 */
class UdpMediaPinger(
    address: String,
    private val videoPort: Int,
    private val audioPort: Int,
    private val payload: String,
) {
    private val host = InetAddress.getByName(address)
    private val video = bind(videoPort)
    private val audio = bind(audioPort)
    private val drainBuffer = ByteArray(MAX_DATAGRAM)

    private var sequence = 0

    /** The local ports the host will stream to, for the session log. */
    val localPorts: String get() = "video ${video.localPort}, audio ${audio.localPort}"

    val mode: String get() = if (MoonlightMediaPing.usable(payload)) "SS_PING" else "legacy PING"

    /** Send one ping to each media port. Safe to call after [close]. */
    fun ping() {
        val datagram =
            if (MoonlightMediaPing.usable(payload)) {
                MoonlightMediaPing.ssPing(payload, sequence)
            } else {
                MoonlightMediaPing.legacy()
            }
        sequence += 1
        send(video, videoPort, datagram)
        send(audio, audioPort, datagram)
    }

    /**
     * Read and discard what the host has sent us, up to a bounded number of
     * datagrams per stream. The bound is the point: once the host is streaming,
     * an unbounded drain would keep finding more and the next ping would never
     * go out.
     */
    fun drain() {
        drain(video)
        drain(audio)
    }

    fun close() {
        runCatching { video.close() }
        runCatching { audio.close() }
    }

    // A closed or unreachable media socket must not take the control stream with
    // it: the session is still usable without the streams we discard anyway.
    @Suppress("SwallowedException")
    private fun send(
        socket: DatagramSocket,
        port: Int,
        datagram: ByteArray,
    ) {
        try {
            socket.send(DatagramPacket(datagram, datagram.size, host, port))
        } catch (e: SocketException) {
            Log.w(TAG, "media ping to $host:$port failed: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.w(TAG, "media ping to $host:$port failed: ${e.message}")
        }
    }

    @Suppress("SwallowedException")
    private fun drain(socket: DatagramSocket) {
        try {
            socket.soTimeout = 1
            repeat(DRAIN_BUDGET) {
                socket.receive(DatagramPacket(drainBuffer, drainBuffer.size))
            }
        } catch (timeout: SocketTimeoutException) {
            // Nothing left this round; that is the normal exit.
        } catch (e: java.io.IOException) {
            // Closed underneath us, or nothing listening. Either way we discard.
        }
    }

    /**
     * Bind to [port] so the legacy match by source port can work, falling back to
     * an ephemeral port when it is taken (the payload path does not care).
     */
    private fun bind(port: Int): DatagramSocket =
        runCatching { DatagramSocket(port) }.getOrElse {
            Log.i(TAG, "local port $port unavailable, using an ephemeral one")
            DatagramSocket()
        }

    private companion object {
        const val TAG = "MoonlightMediaPing"
        const val MAX_DATAGRAM = 2048

        // Enough to keep the receive buffer from filling between pings, few
        // enough that a host mid-stream cannot hold the ping loop here.
        const val DRAIN_BUDGET = 64
    }
}
