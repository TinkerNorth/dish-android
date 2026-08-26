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
        val sdp = MoonlightRtsp.announceSdp(1280, 720, 30)
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

    @Test
    fun `reads an ENet connect token that does not fit in a signed int`() {
        // A live Sunshine host handed back exactly this. Read straight into an
        // Int it is out of range, and the control stream then connected with a
        // token of 0.
        val response =
            MoonlightRtsp.parseResponse(
                "RTSP/1.0 200 OK\r\nCSeq: 5\r\nX-SS-Connect-Data: 4270471497\r\n\r\n",
            )!!
        assertEquals(4270471497L.toInt(), response.enetConnectData())
        assertEquals(-24495799, response.enetConnectData())
    }

    @Test
    fun `reads a connect token that does fit, and reports an absent one`() {
        val small = MoonlightRtsp.parseResponse("RTSP/1.0 200 OK\r\nCSeq: 5\r\nX-SS-Connect-Data: 12345\r\n\r\n")!!
        assertEquals(12345, small.enetConnectData())
        assertNull(MoonlightRtsp.parseResponse("RTSP/1.0 200 OK\r\nCSeq: 5\r\n\r\n")!!.enetConnectData())
    }

    @Test
    fun `reads the media ping payload the host wants echoed`() {
        val response =
            MoonlightRtsp.parseResponse(
                "RTSP/1.0 200 OK\r\nCSeq: 3\r\nX-SS-Ping-Payload: 9A615601970AEC19\r\n\r\n",
            )!!
        assertEquals("9A615601970AEC19", response.pingPayload())
        assertNull(MoonlightRtsp.parseResponse("RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n")!!.pingPayload())
    }

    @Test
    fun `the ANNOUNCE description carries every attribute a host looks up`() {
        val sdp = MoonlightRtsp.announceSdp(1280, 720, 30)

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
}
