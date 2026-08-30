// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The datagram a client sends to a host's video and audio RTP ports so the host
 * stops waiting and starts the stream. Pure byte work; the socket half lives in
 * [com.tinkernorth.dish.source.connection.moonlight.UdpMediaPinger].
 *
 * LENGTH IS THE PROTOCOL HERE, not content. Wolf's listener
 * (src/moonlight-server/rtp/udp-ping.cpp, handle_receive) dispatches purely on
 * how many bytes arrived:
 *
 *  - exactly 4 bytes: the legacy ping. Its contents are never looked at. The
 *    host matches the session by source IP and port instead, which is why this
 *    form only works from a socket the host can already place.
 *  - at least 20 bytes: an `SS_PING` (data-structures.hpp), which is a 16-byte
 *    payload followed by a u32 sequence number. The host matches the session by
 *    comparing those 16 bytes against the per-session secret it handed out in
 *    the SETUP reply's `X-SS-Ping-Payload`, so neither address nor port has to
 *    match anything.
 *  - anything from 5 to 19 bytes: DROPPED, silently, with no host-side log line
 *    beyond a trace of the byte count.
 *
 * That dead zone is what cost us the session for days. `X-SS-Ping-Payload` looks
 * like hex (a live Sunshine host sent `68A75BBEEEA86826`) but it is not: the
 * host mints 16 random printable ASCII characters and expects those same 16
 * bytes back. Hex-decoding it produced an 8-byte datagram and sending the text
 * alone produced a 16-byte one, both inside the dead zone, both discarded
 * without a word. The host then reported `Initial Ping Timeout` and ended the
 * session ten seconds after it began.
 */
object MoonlightMediaPing {
    /** The `X-SS-Ping-Payload` secret is exactly this many bytes, verbatim. */
    const val PAYLOAD_LEN = 16

    /** 16-byte payload + u32 sequence number. */
    const val SS_PING_LEN = 20

    const val LEGACY_LEN = 4

    /**
     * The modern ping: [payload] as raw bytes (padded or truncated to
     * [PAYLOAD_LEN]) followed by [sequence]. The host ignores the sequence
     * number; it is there because the struct has the field.
     */
    fun ssPing(
        payload: String,
        sequence: Int,
    ): ByteArray {
        val buf = ByteBuffer.allocate(SS_PING_LEN).order(ByteOrder.LITTLE_ENDIAN)
        val raw = payload.toByteArray(Charsets.US_ASCII)
        buf.put(raw, 0, minOf(raw.size, PAYLOAD_LEN))
        buf.position(PAYLOAD_LEN)
        buf.putInt(sequence)
        return buf.array()
    }

    /**
     * The legacy 4-byte ping, for a host that named no payload. The bytes are
     * not inspected by the host, but "PING" is what the wire has always carried.
     */
    fun legacy(): ByteArray = LEGACY.copyOf()

    /** Whether [payload] can key the modern ping at all. */
    fun usable(payload: String): Boolean = payload.isNotEmpty()

    private val LEGACY = "PING".toByteArray(Charsets.US_ASCII)
}
