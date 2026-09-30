// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteHttpBodiesTest {
    @Test
    fun `jsonEscape escapes quotes backslashes and control characters`() {
        assertEquals("""say \"hi\"""", jsonEscape("""say "hi""""))
        assertEquals("""a\\b""", jsonEscape("""a\b"""))
        assertEquals("""line\nfeed""", jsonEscape("line\nfeed"))
        assertEquals("""carriage\rreturn""", jsonEscape("carriage\rreturn"))
        assertEquals("""tab\tstop""", jsonEscape("tab\tstop"))
        assertEquals("""bell\u0007""", jsonEscape("bell\u0007"))
    }

    @Test
    fun `jsonEscape escapes both ends of the C0 range as a unicode escape`() {
        assertEquals("""\u0000""", jsonEscape("\u0000"))
        assertEquals("""\u001f""", jsonEscape("\u001f"))
    }

    @Test
    fun `jsonEscape leaves the first printable character and DEL alone`() {
        assertEquals(" ", jsonEscape(" "))
        assertEquals("\u007f", jsonEscape("\u007f"))
    }

    @Test
    fun `the headers go out spelled as the satellite reads them`() {
        assertEquals("Accept-Language", HEADER_ACCEPT_LANGUAGE)
        assertEquals("If-None-Match", HEADER_IF_NONE_MATCH)
        assertEquals("Content-Type", HEADER_CONTENT_TYPE)
        assertEquals("X-Device-Id", HEADER_DEVICE_ID)
        assertEquals("X-Hmac-Proof", HEADER_HMAC_PROOF)
        assertEquals("ETag", HEADER_ETAG)
    }

    @Test
    fun `the bodies are sent as json`() {
        assertEquals("application/json", MIME_JSON)
    }

    @Test
    fun `jsonEscape leaves plain and non-ascii text alone`() {
        assertEquals("Pixel 9 Pro", jsonEscape("Pixel 9 Pro"))
        assertEquals("Téléphone d'Élodie", jsonEscape("Téléphone d'Élodie"))
        assertEquals("", jsonEscape(""))
    }

    @Test
    fun `sessionPutBody carries the whole declarative session`() {
        val body =
            sessionPutBody(
                deviceId = "dev-1",
                deviceName = "Phone",
                protocolVersion = 3,
                descriptorsJson = """[{"ctrlIdx":0}]""",
                requestMouseControl = true,
            )
        assertEquals(
            """{"deviceId":"dev-1","deviceName":"Phone","protocolVersion":3,""" +
                """"controllers":[{"ctrlIdx":0}],"hostFeatures":{"mouseControl":true}}""",
            body,
        )
    }

    @Test
    fun `sessionPutBody escapes the device name`() {
        val body = sessionPutBody("dev-1", """Ada's "Phone"""", 3, "[]", false)
        assertTrue(body.contains(""""deviceName":"Ada's \"Phone\"""""))
        assertEquals("""Ada's "Phone"""", jsonGet(body, "deviceName"))
    }

    @Test
    fun `pairBody carries pin and clientPin side by side`() {
        val body = pairBody(deviceId = "dev-1", deviceName = "Phone", protocolVersion = 3, pin = "1234", clientPin = "5678")
        assertEquals(
            """{"deviceId":"dev-1","deviceName":"Phone","protocolVersion":3,"pin":"1234","clientPin":"5678"}""",
            body,
        )
    }

    @Test
    fun `pairBody sends an empty clientPin on a path A pairing`() {
        val body = pairBody("dev-1", "Phone", 3, "1234", "")
        assertEquals("", jsonGet(body, "clientPin"))
        assertEquals("1234", jsonGet(body, "pin"))
    }

    @Test
    fun `disconnectBody names the device`() {
        assertEquals("""{"deviceId":"dev-1"}""", disconnectBody("dev-1"))
    }

    @Test
    fun `requestFailedBody reads back as an error the decode path understands`() {
        assertEquals("request failed: timeout", jsonGet(requestFailedBody("timeout"), "error"))
        assertEquals("request failed: null", jsonGet(requestFailedBody(null), "error"))
    }

    @Test
    fun `requestFailedBody escapes a quoted cause`() {
        assertEquals("""request failed: bad "cert"""", jsonGet(requestFailedBody("""bad "cert""""), "error"))
    }

    @Test
    fun `catalogHeaders omits If-None-Match for a blank etag`() {
        assertNull(catalogHeaders("en-US", null)[HEADER_IF_NONE_MATCH])
        assertNull(catalogHeaders("en-US", "")[HEADER_IF_NONE_MATCH])
        assertFalse(catalogHeaders("en-US", "  ").containsKey(HEADER_IF_NONE_MATCH))
    }

    @Test
    fun `catalogHeaders revalidates with the etag it was given`() {
        val headers = catalogHeaders("en-US,en", "\"v7\"")
        assertEquals(mapOf(HEADER_ACCEPT_LANGUAGE to "en-US,en", HEADER_IF_NONE_MATCH to "\"v7\""), headers)
    }

    @Test
    fun `pairStatusPath url-encodes the device id`() {
        assertEquals("/api/pair/status?deviceId=a+b%26c", pairStatusPath("a b&c"))
        assertEquals("/api/pair/status?deviceId=dev-1", pairStatusPath("dev-1"))
    }
}
