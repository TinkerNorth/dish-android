// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.connection.moonlight

import android.util.Log
import com.tinkernorth.dish.core.net.moonlight.CRLF
import com.tinkernorth.dish.core.net.moonlight.Request
import com.tinkernorth.dish.core.net.moonlight.Response
import com.tinkernorth.dish.core.net.moonlight.announce
import com.tinkernorth.dish.core.net.moonlight.announceSdp
import com.tinkernorth.dish.core.net.moonlight.describe
import com.tinkernorth.dish.core.net.moonlight.options
import com.tinkernorth.dish.core.net.moonlight.parseResponse
import com.tinkernorth.dish.core.net.moonlight.play
import com.tinkernorth.dish.core.net.moonlight.setup
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
         * The host's per-session media-ping secret, 16 raw characters and NOT
         * hex however much it looks like it. It goes back to the host verbatim
         * inside a 20-byte datagram; see
         * [com.tinkernorth.dish.core.net.moonlight.MoonlightMediaPing].
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

    fun handshake(
        width: Int,
        height: Int,
        fps: Int,
    ): StreamPorts? {
        val target = "rtsp://$address:$rtspPort"
        if (!openSession(target)) return null
        val streams = setupStreams() ?: return null
        if (!startStreams(target, announceSdp(width, height, fps))) return null

        val connectData = streams.control.response.enetConnectData() ?: 0
        val ping =
            streams.audio.response.pingPayload() ?: streams.video.response
                .pingPayload()
                .orEmpty()
        Log.i(
            TAG,
            "negotiated ports on $address: control ${streams.control.port}, video ${streams.video.port}, " +
                "audio ${streams.audio.port}; connect-data $connectData, ping payload ${ping.length} chars",
        )
        if (ping.isEmpty()) Log.w(TAG, "host named no ping payload; falling back to the legacy 4-byte media ping")
        return StreamPorts(streams.control.port, streams.video.port, streams.audio.port, connectData, ping)
    }

    // One SETUP answer the host bound a port for.
    private class StreamSetup(
        val port: Int,
        val response: Response,
    )

    private class NegotiatedStreams(
        val audio: StreamSetup,
        val video: StreamSetup,
        val control: StreamSetup,
    )

    // OPTIONS then DESCRIBE: the host is speaking RTSP to us at all.
    private fun openSession(target: String): Boolean =
        send(options(target, nextCseq())) != null &&
            send(describe(target, nextCseq())) != null

    // The three SETUPs in the order Moonlight hosts expect them; each must name a port.
    private fun setupStreams(): NegotiatedStreams? {
        val audio = setupStream("audio") ?: return null
        val video = setupStream("video") ?: return null
        val control = setupStream("control") ?: return null
        return NegotiatedStreams(audio, video, control)
    }

    // ANNOUNCE the stream we want, then PLAY it.
    private fun startStreams(
        target: String,
        sdp: String,
    ): Boolean =
        send(announce(target, nextCseq(), sdp)) != null &&
            send(play(target, nextCseq())) != null

    private fun setupStream(streamId: String): StreamSetup? {
        val response = send(setup(streamId, nextCseq())) ?: return null
        val port = response.serverPort()
        if (port == null) {
            Log.w(TAG, "SETUP $streamId carried no server_port, options ${response.options}")
            return null
        }
        return StreamSetup(port, response)
    }

    /**
     * One request over one socket: connect, ask, read the answer, close. Says on
     * the way out how it went, so a handshake that dies somewhere in the middle
     * names the step it died on.
     */
    private fun send(request: Request): Response? {
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

    private fun accept(response: Response?): Response? {
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
    private fun readResponse(reader: BufferedReader): Response? {
        val header = readHeaderBlock(reader) ?: return null
        val declared = declaredContentLength(header)
        val body = if (declared != null) readExactly(reader, declared) else reader.readText()
        val raw = header + body
        return parseResponse(raw).also {
            if (it == null) Log.w(TAG, "unparsable reply to $stage: ${escape(raw)}")
        }
    }

    // Null means the host hung up before answering at all, which is a different failure from an
    // answer this client could not parse.
    private fun readHeaderBlock(reader: BufferedReader): String? {
        var line = reader.readLine()
        if (line == null) {
            Log.w(TAG, "host closed the connection during $stage, before answering")
            return null
        }
        val header = StringBuilder()
        while (line != null && line.isNotEmpty()) {
            header.append(line).append(CRLF)
            line = reader.readLine()
        }
        header.append(CRLF)
        return header.toString()
    }

    // Absent means read to end of stream instead: some hosts answer without a length at all.
    private fun declaredContentLength(header: String): Int? =
        CONTENT_LENGTH
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

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

        // Compiled once: readResponse runs on every RTSP exchange of a session setup.
        val CONTENT_LENGTH = Regex("""(?i)content-length:\s*(\d+)""")
    }
}
