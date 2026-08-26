// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.MoonlightRtsp
import java.io.BufferedReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the plaintext RTSP handshake (OPTIONS -> DESCRIBE -> SETUP x3 -> ANNOUNCE
 * -> PLAY) over TCP and returns the negotiated control port plus the ENet
 * connect-data token the host handed back in the control SETUP (Wolf
 * rtsp/commands.hpp setup(): X-SS-Connect-Data). Video/audio are negotiated at
 * the lowest settings and their payloads are never decoded.
 *
 * ONE CONNECTION PER MESSAGE, and it has to be. A Moonlight host answers exactly
 * one RTSP message per TCP connection and then hangs up on its own. Measured
 * against a live Sunshine host: an idle read taken straight after the OPTIONS
 * reply, with nothing further written, returns end-of-stream, and so does the
 * same read after a DESCRIBE reply, so it is having answered that ends the
 * connection and not which command was asked. A second message written into that
 * socket is never seen at all: the host's own debug log recorded our OPTIONS and
 * nothing after it, and pipelining OPTIONS and DESCRIBE into a single write got
 * one answer and one hang-up. Reusing the socket cost us the whole stream setup,
 * which failed at DESCRIBE with the host already gone. So each request opens its
 * own socket and closes it, the same shape [MoonlightHttp11Client] gives the
 * HTTP half.
 *
 * The reply body is framed by that hang-up as much as by Content-length: the
 * host sends the DESCRIBE SDP with no length header at all and simply closes.
 *
 * Message framing is delegated to the pure [MoonlightRtsp] codec; this class
 * owns only the sockets and the CSeq counter.
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
        /**
         * Hex, per session; see [MoonlightRtsp.Response.pingPayload]. Carried,
         * not yet acted on: a live host still ends the session on "Initial Ping
         * Timeout" roughly ten seconds after PLAY, and echoing these bytes to
         * the media ports has not been shown to prevent it. It is per session,
         * so whatever does fix it has to read it from this handshake.
         */
        val pingPayload: String,
    )

    private var cseq = 0

    /**
     * The step in flight, as it would be named in a log line. A host that hangs
     * up mid-handshake reaches us as a bare write or read failure with no reply
     * attached, so the step it died on is the only thing that identifies it.
     */
    private var stage = "connect"

    @Suppress("ReturnCount") // each early return is a distinct RTSP step failing
    fun handshake(
        width: Int,
        height: Int,
        fps: Int,
    ): StreamPorts? {
        val target = "rtsp://$address:$rtspPort"
        if (send(MoonlightRtsp.options(target, nextCseq())) == null) return null
        if (send(MoonlightRtsp.describe(target, nextCseq())) == null) return null

        val audioResp = setup("audio") ?: return null
        val audio = audioResp.serverPort() ?: return null
        val videoResp = setup("video") ?: return null
        val video = videoResp.serverPort() ?: return null
        val controlResp = setup("control") ?: return null
        val controlPort = controlResp.serverPort() ?: return null
        val connectData = controlResp.enetConnectData() ?: 0
        val ping = audioResp.pingPayload() ?: videoResp.pingPayload().orEmpty()

        val sdp = MoonlightRtsp.announceSdp(width, height, fps)
        if (send(MoonlightRtsp.announce(target, nextCseq(), sdp)) == null) return null
        if (send(MoonlightRtsp.play(target, nextCseq())) == null) return null

        Log.i(TAG, "negotiated: control $controlPort, video $video, audio $audio, connect-data $connectData")
        if (ping.isEmpty()) Log.w(TAG, "host named no ping payload; its media streams will time the session out")
        return StreamPorts(controlPort, video, audio, connectData, ping)
    }

    private fun setup(streamId: String): MoonlightRtsp.Response? {
        val response = send(MoonlightRtsp.setup(streamId, nextCseq())) ?: return null
        if (response.serverPort() == null) {
            Log.w(TAG, "SETUP $streamId carried no server_port, options ${response.options}")
        }
        return response
    }

    /**
     * One request over one socket: connect, ask, read the answer, close. Says on
     * the way out how it went, so a handshake that dies somewhere in the middle
     * names the step it died on.
     */
    private fun send(request: MoonlightRtsp.Request): MoonlightRtsp.Response? {
        stage = "${request.command} (CSeq ${request.cseq})"
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, rtspPort), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                Log.d(TAG, "-> $stage")
                socket.getOutputStream().apply {
                    write(request.encode().toByteArray())
                    flush()
                }
                accept(readResponse(socket.getInputStream().bufferedReader()))
            }
        } catch (e: IOException) {
            Log.w(TAG, "$stage failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun accept(response: MoonlightRtsp.Response?): MoonlightRtsp.Response? {
        if (response == null) return null
        if (!response.ok) {
            Log.w(TAG, "<- $stage refused: ${response.statusCode} ${response.statusMessage}")
            return null
        }
        Log.d(TAG, "<- $stage ${response.statusCode}, options ${response.options.keys}")
        return response
    }

    /**
     * Read one RTSP response: status + headers until a blank line, then the
     * body. Content-length frames it when the host sends one; the host does not
     * on DESCRIBE, and since it closes the connection once it has answered, the
     * rest of the stream is the body.
     */
    private fun readResponse(reader: BufferedReader): MoonlightRtsp.Response? {
        val header = StringBuilder()
        var line = reader.readLine()
        if (line == null) {
            Log.w(TAG, "host closed the connection during $stage, before answering")
            return null
        }
        while (line != null && line.isNotEmpty()) {
            header.append(line).append(MoonlightRtsp.CRLF)
            line = reader.readLine()
        }
        header.append(MoonlightRtsp.CRLF)
        val declared =
            Regex("(?i)content-length:\\s*(\\d+)")
                .find(header)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        val raw = header.toString() + if (declared != null) readExactly(reader, declared) else reader.readText()
        return MoonlightRtsp.parseResponse(raw).also {
            if (it == null) Log.w(TAG, "unparsable reply to $stage: ${escape(raw)}")
        }
    }

    /** Hands back what arrived even when the host stops short of its own count. */
    private fun readExactly(
        reader: BufferedReader,
        count: Int,
    ): String {
        val out = CharArray(count.coerceIn(0, MAX_BODY_CHARS))
        var filled = 0
        while (filled < out.size) {
            val n = reader.read(out, filled, out.size - filled)
            if (n < 0) break
            filled += n
        }
        return out.concatToString(0, filled)
    }

    /** Line ends spelled out, so a framing bug is readable in a log line. */
    private fun escape(raw: String): String =
        raw
            .take(RAW_LOG_CHARS)
            .replace("\r", "\\r")
            .replace("\n", "\\n")

    private fun nextCseq(): Int = ++cseq

    private companion object {
        const val TAG = "MoonlightRtspClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
        const val RAW_LOG_CHARS = 512
        const val MAX_BODY_CHARS = 256 * 1024
    }
}
