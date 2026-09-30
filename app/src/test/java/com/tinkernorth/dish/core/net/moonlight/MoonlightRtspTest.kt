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
        val encoded = options("rtsp://192.168.1.100:48010", cseq = 1).encode()
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
        val encoded = setup("control", cseq = 4).encode()
        assertTrue(encoded.startsWith("SETUP streamid=control RTSP/1.0\r\n"))
        assertTrue(encoded.contains("CSeq: 4\r\n"))
    }

    @Test
    fun `ANNOUNCE carries the SDP payload and a content-length`() {
        val sdp = announceSdp(1280, 720, 30)
        val encoded = announce("rtsp://host:48010", cseq = 5, sdpPayload = sdp).encode()
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
        val response = parseResponse(raw)!!
        assertTrue(response.ok)
        assertEquals(200, response.statusCode)
        assertEquals(4, response.cseq)
        assertEquals(47999, response.serverPort())
    }

    @Test
    fun `parses an error response`() {
        val response = parseResponse("RTSP/1.0 404 NOT FOUND\r\nCSeq: 2\r\n\r\n")!!
        assertEquals(404, response.statusCode)
        assertEquals("NOT FOUND", response.statusMessage)
        assertTrue(!response.ok)
    }

    @Test
    fun `rejects a non-RTSP reply`() {
        assertNull(parseResponse("HTTP/1.1 200 OK\r\n\r\n"))
        assertNull(parseResponse(""))
    }

    @Test
    fun `serverPort is null when the transport option is absent`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n")!!
        assertNull(response.serverPort())
    }

    @Test
    fun `reads an ENet connect token that does not fit in a signed int`() {
        // A live Sunshine host handed back exactly this. Read straight into an
        // Int it is out of range, and the control stream then connected with a
        // token of 0.
        val response =
            parseResponse(
                "RTSP/1.0 200 OK\r\nCSeq: 5\r\nX-SS-Connect-Data: 4270471497\r\n\r\n",
            )!!
        assertEquals(4270471497L.toInt(), response.enetConnectData())
        assertEquals(-24495799, response.enetConnectData())
    }

    @Test
    fun `reads a connect token that does fit, and reports an absent one`() {
        val small = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 5\r\nX-SS-Connect-Data: 12345\r\n\r\n")!!
        assertEquals(12345, small.enetConnectData())
        assertNull(parseResponse("RTSP/1.0 200 OK\r\nCSeq: 5\r\n\r\n")!!.enetConnectData())
    }

    @Test
    fun `reads the media ping payload the host wants echoed`() {
        val response =
            parseResponse(
                "RTSP/1.0 200 OK\r\nCSeq: 3\r\nX-SS-Ping-Payload: 9A615601970AEC19\r\n\r\n",
            )!!
        assertEquals("9A615601970AEC19", response.pingPayload())
        assertNull(parseResponse("RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n")!!.pingPayload())
    }

    @Test
    fun `the ANNOUNCE description carries every attribute a host looks up`() {
        val sdp = announceSdp(1280, 720, 30)

        // Carrying only the handful the dish itself cares about is answered
        // 400 BAD REQUEST by a real host: it looks each of these up by name and
        // a miss is fatal. Dropping one because nothing here reads it is how the
        // stream setup breaks again.
        listOf(
            "x-nv-video[0].clientViewportWd:1280",
            "x-nv-video[0].clientViewportHt:720",
            "x-nv-video[0].maxFPS:30",
            "x-nv-video[0].packetSize:",
            "x-nv-video[0].rateControlMode:",
            "x-nv-video[0].timeoutLengthMs:",
            "x-nv-video[0].framesWithInvalidRefThreshold:",
            "x-nv-video[0].refPicInvalidation:",
            "x-nv-video[0].encoderCscMode:",
            "x-nv-video[0].dynamicRangeMode:",
            "x-nv-video[0].maxNumReferenceFrames:",
            "x-nv-video[0].videoEncoderSlicesPerFrame:",
            "x-nv-video[0].clientRefreshRateX100:3000",
            "x-nv-vqos[0].bitStreamFormat:",
            "x-nv-vqos[0].bw.minimumBitrateKbps:",
            "x-nv-vqos[0].bw.maximumBitrateKbps:",
            "x-nv-vqos[0].fec.enable:",
            "x-nv-vqos[0].fec.minRequiredFecPackets:",
            "x-nv-vqos[0].fec.repairPercent:",
            "x-nv-vqos[0].drc.enable:",
            "x-nv-vqos[0].videoQualityScoreUpdateTime:",
            "x-nv-vqos[0].qosTrafficType:",
            "x-nv-aqos.qosTrafficType:",
            "x-nv-aqos.packetDuration:",
            "x-nv-audio.surround.numChannels:",
            "x-nv-audio.surround.channelMask:",
            "x-nv-audio.surround.enable:",
            "x-nv-audio.surround.AudioQuality:",
            "x-nv-general.useReliableUdp:",
            "x-nv-general.featureFlags:",
            "x-ml-general.featureFlags:",
            "x-ss-general.encryptionEnabled:",
        ).forEach { attribute ->
            assertTrue("SDP is missing a=$attribute", sdp.contains("a=$attribute"))
        }
        assertTrue(sdp.startsWith("v=0\r\n"))
        assertTrue(sdp.endsWith("t=0 0\r\n"))
    }

    @Test
    fun `parses a reply framed with bare LF`() {
        val response = parseResponse("RTSP/1.0 200 OK\nCSeq: 4\nTransport: server_port=47999\n\n")!!
        assertEquals(200, response.statusCode)
        assertEquals(4, response.cseq)
        assertEquals(47999, response.serverPort())
    }

    @Test
    fun `parses the payload after the blank line, with its line endings normalized to LF`() {
        val sdp = "v=0\r\na=fmtp:97 surround-params=21101\r\n"
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\n\r\n$sdp")!!
        assertEquals(sdp.replace("\r\n", "\n"), response.payload)
        assertEquals("application/sdp", response.options["Content-Type"])
    }

    @Test
    fun `a reply without a blank line has an empty payload`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 2\r\n")!!
        assertEquals("", response.payload)
        assertEquals(2, response.cseq)
    }

    @Test
    fun `rejects a status line without a numeric code`() {
        assertNull(parseResponse("RTSP/1.0 OK\r\nCSeq: 1\r\n\r\n"))
        assertNull(parseResponse("RTSP/1.0\r\nCSeq: 1\r\n\r\n"))
    }

    @Test
    fun `a status line without a message reads as an empty message`() {
        val response = parseResponse("RTSP/1.0 200\r\nCSeq: 1\r\n\r\n")!!
        assertEquals(200, response.statusCode)
        assertEquals("", response.statusMessage)
    }

    @Test
    fun `skips a header line with no colon`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nthis line has no separator\r\nCSeq: 6\r\n\r\n")!!
        assertEquals(6, response.cseq)
        assertTrue(response.options.isEmpty())
    }

    @Test
    fun `a non-numeric CSeq keeps the previous value`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 6\r\ncseq: later\r\n\r\n")!!
        assertEquals(6, response.cseq)
    }

    @Test
    fun `serverPort is null when the transport names no port`() {
        val noPort = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: unicast\r\n\r\n")!!
        assertNull(noPort.serverPort())
        val notDigits = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: server_port=x\r\n\r\n")!!
        assertNull(notDigits.serverPort())
    }

    @Test
    fun `serverPort stops at the first non-digit`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: server_port=48000-48001\r\n\r\n")!!
        assertEquals(48000, response.serverPort())
    }

    @Test
    fun `a non-numeric connect token reads as absent`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 5\r\nX-SS-Connect-Data: nope\r\n\r\n")!!
        assertNull(response.enetConnectData())
    }

    @Test
    fun `a blank ping payload reads as absent`() {
        val response = parseResponse("RTSP/1.0 200 OK\r\nCSeq: 3\r\nX-SS-Ping-Payload:   \r\n\r\n")!!
        assertNull(response.pingPayload())
    }

    @Test
    fun `ok covers the whole 2xx range and nothing else`() {
        assertTrue(parseResponse("RTSP/1.0 299 Whatever\r\n\r\n")!!.ok)
        assertTrue(!parseResponse("RTSP/1.0 300 Moved\r\n\r\n")!!.ok)
        assertTrue(!parseResponse("RTSP/1.0 199 Early\r\n\r\n")!!.ok)
    }

    @Test
    fun `DESCRIBE asks for sdp`() {
        val encoded = describe("rtsp://host:48010", cseq = 2).encode()
        assertEquals(
            "DESCRIBE rtsp://host:48010 RTSP/1.0\r\n" +
                "CSeq: 2\r\n" +
                "X-GS-ClientVersion: 14\r\n" +
                "Accept: application/sdp\r\n" +
                "\r\n",
            encoded,
        )
    }

    @Test
    fun `PLAY names the session`() {
        val encoded = play("rtsp://host:48010", cseq = 6).encode()
        assertEquals("PLAY rtsp://host:48010 RTSP/1.0\r\nCSeq: 6\r\nSession: DEADBEEFCAFE\r\n\r\n", encoded)
    }
}
