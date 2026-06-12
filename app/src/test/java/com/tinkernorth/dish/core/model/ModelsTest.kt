// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelsTest {
    @Test
    fun `ControllerEntry has correct defaults`() {
        val entry = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        assertEquals(0, entry.controllerIndex)
        assertFalse(entry.isDisconnected)
        assertEquals(0, entry.disconnectTimeLeft)
    }

    @Test
    fun `ControllerEntry equality`() {
        val a = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        val b = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        assertEquals(a, b)
    }

    @Test
    fun `ControllerEntry copy with disconnected state`() {
        val entry = ControllerEntry(id = 1, name = "Test", controllerIndex = 0)
        val disconnected = entry.copy(isDisconnected = true, disconnectTimeLeft = 30)
        assertEquals(true, disconnected.isDisconnected)
        assertEquals(30, disconnected.disconnectTimeLeft)
    }

    @Test
    fun `DiscoveredServer data class equality`() {
        val a = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        val b = DiscoveredServer("PC", "10.0.0.1", 9876, 9878, 9877)
        assertEquals(a, b)
    }

    @Test
    fun `PairResponse defaults`() {
        val response = PairResponse()
        assertFalse(response.ok)
        assertEquals(null, response.error)
        assertEquals(null, response.sharedKey)
    }

    @Test
    fun `SessionResponse defaults`() {
        val response = SessionResponse()
        assertEquals(null, response.connectionId)
        assertEquals(null, response.token)
        assertEquals(null, response.sessionSalt)
        assertEquals(null, response.error)
        assertFalse(response.unauthorized)
    }

    @Test
    fun `SessionResponse parses the full contract body`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val body =
            """{"connectionId":"conn_ab12cd34","token":"0007a1b2","sessionSalt":"a1b2c3d4e5f60718",""" +
                """"epoch":3,"maxControllers":16,"protocolVersion":1,"controllers":[""" +
                """{"ctrlIdx":0,"result":"ok","appliedType":1,""" +
                """"motion":{"sinkSupportedForType":true,"backendOk":true}},""" +
                """{"ctrlIdx":1,"result":"replugFailed","appliedType":0,""" +
                """"motion":{"sinkSupportedForType":false,"backendOk":true}}],""" +
                """"hostFeatures":{"mouseControl":{"granted":false,"reason":"notSupported"}}}"""
        val resp = json.decodeFromString(SessionResponse.serializer(), body)
        assertEquals("conn_ab12cd34", resp.connectionId)
        assertEquals(3, resp.epoch)
        assertEquals(2, resp.controllers.size)
        assertEquals(true, resp.controllers[0].ok)
        assertFalse(resp.controllers[1].ok)
        assertEquals(0, resp.controllers[1].appliedType) // failed replug kept the old type
        assertFalse(resp.hostFeatures.mouseControl.granted)
        assertEquals("notSupported", resp.hostFeatures.mouseControl.reason)
    }

    @Test
    fun `SessionResponse maps the terminal 401 codes`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val notPaired =
            json.decodeFromString(
                SessionResponse.serializer(),
                """{"error":"unauthorized","code":"NOT_PAIRED"}""",
            )
        val badProof =
            json.decodeFromString(
                SessionResponse.serializer(),
                """{"error":"unauthorized","code":"BAD_PROOF"}""",
            )
        assertEquals(true, notPaired.unauthorized)
        assertEquals(true, badProof.unauthorized)
    }

    @Test
    fun `CatalogDto parses controller types with unknown-slug tolerance`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val body =
            """{"locale":"de","protocolVersion":1,"serverVersion":"1.6.0","controllerTypes":[""" +
                """{"id":0,"slug":"xbox360","name":"Xbox 360 Controller","shortName":"Xbox",""" +
                """"description":"d","image":{"href":"/api/catalog/images/xbox360","etag":"\"1.6.0\""},""" +
                """"features":{"rumble":{"supported":true},"motion":{"supported":false}}},""" +
                """{"id":7,"slug":"hyperpad","name":"HyperPad 9000","shortName":"Hyper",""" +
                """"description":"future","image":{"href":"/api/catalog/images/hyperpad","etag":"x"},""" +
                """"features":{"warp":{"supported":true,"requires":"fluxcap>=2"}}}],""" +
                """"hostFeatures":{"mouseControl":{"supported":true,"modes":["off","ds4","mouse"]}}}"""
        val catalog = json.decodeFromString(CatalogDto.serializer(), body)
        assertEquals("de", catalog.locale)
        assertEquals(2, catalog.controllerTypes.size)
        // A type newer than this app still parses fully — name and unknown
        // feature slugs included (the UI offers the type, skips the feature).
        assertEquals(7, catalog.controllerTypes[1].id)
        assertEquals("HyperPad 9000", catalog.controllerTypes[1].name)
        assertEquals("fluxcap>=2", catalog.controllerTypes[1].features["warp"]?.requires)
        assertEquals(listOf("off", "ds4", "mouse"), catalog.hostFeatures["mouseControl"]?.modes)
    }
}
