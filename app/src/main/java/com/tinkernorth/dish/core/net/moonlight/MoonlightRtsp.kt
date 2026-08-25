// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

/**
 * Plaintext RTSP request formatting and response parsing for the Moonlight
 * stream setup handshake (Wolf protocols/rtsp.adoc, cross-checked against Wolf
 * src/moonlight-protocol/rtsp/parser.hpp). Requests are
 * `<CMD> <target> RTSP/1.0` + option lines (CSeq always) + blank line +
 * optional payload, CRLF-terminated. Responses start with `RTSP/1.0 <code>
 * <msg>`.
 *
 * Pure string work so it unit-tests without a socket. The transport writes the
 * bytes and reads the reply; this class owns the wire text only. Video config
 * is kept minimal on purpose: the dish negotiates the streams then discards
 * their payloads (no decoding).
 */
object MoonlightRtsp {
    const val CRLF = "\r\n"

    data class Request(
        val command: String,
        val target: String,
        val cseq: Int,
        val options: List<Pair<String, String>> = emptyList(),
        val payload: String? = null,
    ) {
        fun encode(): String =
            buildString {
                append(command)
                    .append(' ')
                    .append(target)
                    .append(" RTSP/1.0")
                    .append(CRLF)
                append("CSeq: ").append(cseq).append(CRLF)
                for ((k, v) in options) append(k).append(": ").append(v).append(CRLF)
                append(CRLF)
                if (payload != null) append(payload)
            }
    }

    data class Response(
        val statusCode: Int,
        val statusMessage: String,
        val cseq: Int,
        val options: Map<String, String>,
        val payload: String,
    ) {
        val ok: Boolean get() = statusCode in 200..299

        /**
         * The SETUP reply carries the negotiated port in `server_port=<n>` of
         * the Transport option (rtsp.adoc setup). Ports are dynamic: read them
         * here, never hardcode.
         */
        fun serverPort(): Int? {
            val transport = options["Transport"] ?: return null
            val marker = "server_port="
            val start = transport.indexOf(marker)
            if (start < 0) return null
            val digits = transport.substring(start + marker.length).takeWhile { it.isDigit() }
            return digits.toIntOrNull()
        }
    }

    fun options(
        target: String,
        cseq: Int,
    ): Request = Request("OPTIONS", target, cseq, listOf("X-GS-ClientVersion" to CLIENT_VERSION))

    fun describe(
        target: String,
        cseq: Int,
    ): Request =
        Request(
            "DESCRIBE",
            target,
            cseq,
            listOf(
                "X-GS-ClientVersion" to CLIENT_VERSION,
                "Accept" to "application/sdp",
            ),
        )

    /**
     * streamId is one of audio / video / control (rtsp.adoc SETUP). The target
     * is the streamid form, so no URI is needed here.
     */
    fun setup(
        streamId: String,
        cseq: Int,
    ): Request =
        Request(
            "SETUP",
            "streamid=$streamId",
            cseq,
            listOf("Transport" to "unicast;X-GS-ClientPort=$streamId", "X-GS-ClientVersion" to CLIENT_VERSION),
        )

    fun announce(
        target: String,
        cseq: Int,
        sdpPayload: String,
    ): Request =
        Request(
            "ANNOUNCE",
            target,
            cseq,
            listOf(
                "Content-type" to "application/sdp",
                "Content-length" to sdpPayload.toByteArray(Charsets.UTF_8).size.toString(),
                "Session" to "DEADBEEFCAFE",
            ),
            payload = sdpPayload,
        )

    fun play(
        target: String,
        cseq: Int,
    ): Request = Request("PLAY", target, cseq, listOf("Session" to "DEADBEEFCAFE"))

    /**
     * Parse an RTSP response. Returns null when the first line is not an
     * `RTSP/1.0`-style status line, so a truncated or non-RTSP reply is
     * rejected rather than misparsed.
     */
    fun parseResponse(raw: String): Response? {
        val normalized = raw.replace("\r\n", "\n")
        val headerEnd = normalized.indexOf("\n\n")
        val headerBlock = if (headerEnd >= 0) normalized.substring(0, headerEnd) else normalized
        val payload = if (headerEnd >= 0) normalized.substring(headerEnd + 2) else ""
        val lines = headerBlock.split('\n').filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val statusLine = lines.first().trim().split(' ', limit = 3)
        if (statusLine.size < 2 || !statusLine[0].startsWith("RTSP/")) return null
        val code = statusLine[1].toIntOrNull() ?: return null
        val message = statusLine.getOrElse(2) { "" }
        val options = LinkedHashMap<String, String>()
        var cseq = 0
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.equals("CSeq", ignoreCase = true)) {
                cseq = value.toIntOrNull() ?: cseq
            } else {
                options[key] = value
            }
        }
        return Response(code, message, cseq, options, payload)
    }

    // A minimal SDP-ish config that advertises the lowest video/audio settings.
    // The host negotiates these; the dish never decodes the resulting streams.
    fun minimalAnnounceSdp(
        width: Int,
        height: Int,
        fps: Int,
    ): String =
        buildString {
            append("v=0").append(CRLF)
            append("a=x-nv-video[0].clientViewportWd:").append(width).append(CRLF)
            append("a=x-nv-video[0].clientViewportHt:").append(height).append(CRLF)
            append("a=x-nv-video[0].maxFPS:").append(fps).append(CRLF)
            append("a=x-nv-video[0].packetSize:1024").append(CRLF)
            append("a=x-nv-vqos[0].bitStreamFormat:0").append(CRLF)
            append("a=x-nv-audio.surround.numChannels:2").append(CRLF)
            append("t=0 0").append(CRLF)
        }

    private const val CLIENT_VERSION = "14"
}
