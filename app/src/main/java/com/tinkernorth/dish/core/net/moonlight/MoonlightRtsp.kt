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

        /**
         * The ENet connect token from the control SETUP reply (rtsp.adoc setup:
         * X-SS-Connect-Data), as the 32 bits [enet.EnetClient] puts on the wire.
         *
         * READ WIDE, THEN NARROW. The token is unsigned 32-bit and a real host's
         * routinely sits above Int.MAX_VALUE: 4270471497 came off a live Sunshine
         * host. Parsing it straight into an Int fails for exactly those values
         * and, defaulted, handed the control stream a token of 0.
         */
        fun enetConnectData(): Int? =
            options[CONNECT_DATA]
                ?.trim()
                ?.toLongOrNull()
                ?.toInt()

        /**
         * The media-stream ping payload from an audio or video SETUP reply
         * (rtsp.adoc setup: X-SS-Ping-Payload), hex.
         *
         * The host will not wait for media it has not heard from: unless these
         * bytes reach the ports those replies named, it logs "Initial Ping
         * Timeout" and ends the session seconds after PLAY, taking the control
         * channel with it. It is minted per session, so it cannot be carried
         * over from an earlier launch.
         */
        fun pingPayload(): String? =
            options[PING_PAYLOAD]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
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

    /**
     * The ANNOUNCE session description: the lowest video/audio settings the
     * protocol will express, since the dish negotiates the streams and then
     * discards their payloads without decoding anything.
     *
     * IT HAS TO BE THE WHOLE SET. A host builds its stream configuration by
     * looking each of these attributes up by name, and a lookup that misses is
     * a fatal one: measured against a live Sunshine host, an ANNOUNCE carrying
     * only the seven attributes the dish itself cares about is answered
     * `400 BAD REQUEST`, while the same handshake carrying this set is answered
     * `200 OK`, with either line ending. Nothing here is decoration, and an
     * attribute dropped as unused is a host that stops talking to us.
     */
    @Suppress("LongMethod") // one attribute per line; the list is the point
    fun announceSdp(
        width: Int,
        height: Int,
        fps: Int,
    ): String =
        listOf(
            "v=0",
            "o=android 0 14 IN IPv4 0.0.0.0",
            "s=NVIDIA Streaming Client",
            "a=x-nv-video[0].clientViewportWd:$width",
            "a=x-nv-video[0].clientViewportHt:$height",
            "a=x-nv-video[0].maxFPS:$fps",
            "a=x-nv-video[0].packetSize:1024",
            "a=x-nv-video[0].rateControlMode:4",
            "a=x-nv-video[0].timeoutLengthMs:7000",
            "a=x-nv-video[0].framesWithInvalidRefThreshold:0",
            "a=x-nv-video[0].refPicInvalidation:0",
            "a=x-nv-video[0].encoderCscMode:0",
            "a=x-nv-video[0].dynamicRangeMode:0",
            "a=x-nv-video[0].maxNumReferenceFrames:1",
            "a=x-nv-video[0].videoEncoderSlicesPerFrame:1",
            "a=x-nv-video[0].clientRefreshRateX100:${fps * FPS_HUNDREDTHS}",
            "a=x-nv-vqos[0].bitStreamFormat:0",
            "a=x-nv-vqos[0].bw.minimumBitrateKbps:$MIN_BITRATE_KBPS",
            "a=x-nv-vqos[0].bw.maximumBitrateKbps:$MIN_BITRATE_KBPS",
            "a=x-nv-vqos[0].fec.enable:1",
            "a=x-nv-vqos[0].fec.minRequiredFecPackets:2",
            "a=x-nv-vqos[0].fec.repairPercent:20",
            "a=x-nv-vqos[0].drc.enable:0",
            "a=x-nv-vqos[0].videoQualityScoreUpdateTime:5000",
            "a=x-nv-vqos[0].qosTrafficType:5",
            "a=x-nv-aqos.qosTrafficType:4",
            "a=x-nv-aqos.packetDuration:5",
            "a=x-nv-audio.surround.numChannels:2",
            "a=x-nv-audio.surround.channelMask:3",
            "a=x-nv-audio.surround.enable:0",
            "a=x-nv-audio.surround.AudioQuality:0",
            "a=x-nv-general.useReliableUdp:13",
            "a=x-nv-general.featureFlags:167",
            "a=x-ml-general.featureFlags:3",
            "a=x-ss-general.encryptionEnabled:0",
            "t=0 0",
        ).joinToString("") { it + CRLF }

    const val CONNECT_DATA = "X-SS-Connect-Data"
    const val PING_PAYLOAD = "X-SS-Ping-Payload"

    private const val CLIENT_VERSION = "14"

    // clientRefreshRateX100 is hundredths of a frame per second.
    private const val FPS_HUNDREDTHS = 100

    // The floor the protocol lets us ask for: no payload is ever decoded, so
    // the only thing bitrate buys here is host-side encoder work we throw away.
    private const val MIN_BITRATE_KBPS = 500
}
