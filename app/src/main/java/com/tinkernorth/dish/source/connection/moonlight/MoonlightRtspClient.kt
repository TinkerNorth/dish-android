// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightRtsp
import java.io.BufferedReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the plaintext RTSP handshake (OPTIONS -> DESCRIBE -> SETUP x3 -> ANNOUNCE
 * -> PLAY) over TCP and returns the negotiated control port plus the ENet
 * connect-data token the host handed back in the control SETUP (Wolf
 * rtsp/commands.hpp setup(): X-SS-Connect-Data). Video/audio are negotiated at
 * the lowest settings and their payloads are never decoded.
 *
 * Message framing is delegated to the pure [MoonlightRtsp] codec; this class
 * owns only the socket and the CSeq counter.
 */
class MoonlightRtspClient(
    private val address: String,
    private val rtspPort: Int,
) {
    data class StreamPorts(
        val controlPort: Int,
        val videoPort: Int,
        val audioPort: Int,
        val enetConnectData: Int,
    )

    private var cseq = 0

    @Suppress("ReturnCount") // each early return is a distinct RTSP step failing
    fun handshake(
        width: Int,
        height: Int,
        fps: Int,
    ): StreamPorts? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, rtspPort), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val target = "rtsp://$address:$rtspPort"
            val out = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()

            if (!exchange(out, reader, MoonlightRtsp.options(target, nextCseq()))) return null
            if (!exchange(out, reader, MoonlightRtsp.describe(target, nextCseq()))) return null

            val audio = setupPort(out, reader, "audio") ?: return null
            val video = setupPort(out, reader, "video") ?: return null
            val controlResp = setupResponse(out, reader, "control") ?: return null
            val controlPort = controlResp.serverPort() ?: return null
            val connectData = controlResp.options["X-SS-Connect-Data"]?.trim()?.toIntOrNull() ?: 0

            val sdp = MoonlightRtsp.minimalAnnounceSdp(width, height, fps)
            if (!exchange(out, reader, MoonlightRtsp.announce(target, nextCseq(), sdp))) return null
            if (!exchange(out, reader, MoonlightRtsp.play(target, nextCseq()))) return null

            return StreamPorts(controlPort, video, audio, connectData)
        }
    }

    private fun setupPort(
        out: java.io.OutputStream,
        reader: BufferedReader,
        streamId: String,
    ): Int? = setupResponse(out, reader, streamId)?.serverPort()

    private fun setupResponse(
        out: java.io.OutputStream,
        reader: BufferedReader,
        streamId: String,
    ): MoonlightRtsp.Response? {
        out.write(MoonlightRtsp.setup(streamId, nextCseq()).encode().toByteArray())
        out.flush()
        return readResponse(reader)?.takeIf { it.ok }
    }

    private fun exchange(
        out: java.io.OutputStream,
        reader: BufferedReader,
        request: MoonlightRtsp.Request,
    ): Boolean {
        out.write(request.encode().toByteArray())
        out.flush()
        return readResponse(reader)?.ok == true
    }

    // Read one RTSP response: status + headers until a blank line, then the
    // body if Content-length says there is one.
    private fun readResponse(reader: BufferedReader): MoonlightRtsp.Response? {
        val header = StringBuilder()
        var line = reader.readLine() ?: return null
        while (line.isNotEmpty()) {
            header.append(line).append(MoonlightRtsp.CRLF)
            line = reader.readLine() ?: break
        }
        header.append(MoonlightRtsp.CRLF)
        val contentLength =
            Regex("(?i)content-length:\\s*(\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) CharArray(contentLength).also { reader.read(it) }.concatToString() else ""
        return MoonlightRtsp.parseResponse(header.toString() + body).also {
            if (it == null) Log.w(TAG, "unparsable RTSP response")
        }
    }

    private fun nextCseq(): Int = ++cseq

    private companion object {
        const val TAG = "MoonlightRtspClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
    }
}
