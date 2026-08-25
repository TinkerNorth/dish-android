// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net.moonlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoonlightRtspTest {
    @Test
    fun `OPTIONS request is CRLF framed with CSeq`() {
        val encoded = MoonlightRtsp.options("rtsp://192.168.1.100:48010", cseq = 1).encode()
        assertEquals(
            "OPTIONS rtsp://192.168.1.100:48010 RTSP/1.0\r\n" +
                "CSeq: 1\r\n" +
                "X-GS-ClientVersion: 14\r\n" +
                "\r\n",
            encoded,
        )
    }

    @Test
    fun `SETUP targets the stream id`() {
        val encoded = MoonlightRtsp.setup("control", cseq = 4).encode()
        assertTrue(encoded.startsWith("SETUP streamid=control RTSP/1.0\r\n"))
        assertTrue(encoded.contains("CSeq: 4\r\n"))
    }

    @Test
    fun `ANNOUNCE carries the SDP payload and a content-length`() {
        val sdp = MoonlightRtsp.minimalAnnounceSdp(1280, 720, 30)
        val encoded = MoonlightRtsp.announce("rtsp://host:48010", cseq = 5, sdpPayload = sdp).encode()
        assertTrue(encoded.contains("Content-length: ${sdp.toByteArray().size}\r\n"))
        assertTrue(encoded.endsWith(sdp))
        assertTrue(sdp.contains("clientViewportWd:1280"))
    }

    @Test
    fun `parses a 200 response and reads the negotiated control port`() {
        val raw =
            "RTSP/1.0 200 OK\r\n" +
                "CSeq: 4\r\n" +
                "Session: DEADBEEFCAFE;timeout = 90\r\n" +
                "Transport: server_port=47999\r\n" +
                "\r\n"
        val response = MoonlightRtsp.parseResponse(raw)!!
        assertTrue(response.ok)
        assertEquals(200, response.statusCode)
        assertEquals(4, response.cseq)
        assertEquals(47999, response.serverPort())
    }

    @Test
    fun `parses an error response`() {
        val response = MoonlightRtsp.parseResponse("RTSP/1.0 404 NOT FOUND\r\nCSeq: 2\r\n\r\n")!!
        assertEquals(404, response.statusCode)
        assertEquals("NOT FOUND", response.statusMessage)
        assertTrue(!response.ok)
    }

    @Test
    fun `rejects a non-RTSP reply`() {
        assertNull(MoonlightRtsp.parseResponse("HTTP/1.1 200 OK\r\n\r\n"))
        assertNull(MoonlightRtsp.parseResponse(""))
    }

    @Test
    fun `serverPort is null when the transport option is absent`() {
        val response = MoonlightRtsp.parseResponse("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n")!!
        assertNull(response.serverPort())
    }
}
