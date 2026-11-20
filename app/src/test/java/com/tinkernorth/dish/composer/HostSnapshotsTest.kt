// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.CatalogDto
import com.tinkernorth.dish.core.model.ServerBackendDto
import com.tinkernorth.dish.core.model.ServerCapabilitiesDto
import com.tinkernorth.dish.core.model.ServerControllerAudioDto
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.source.store.MoonlightHostFacts
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Which snapshot a host entry carries and which error says why, decided from the answer alone.
class HostSnapshotsTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val fallback = buildJsonObject { put("format", SATELLITE_CAPABILITIES_FORMAT) }

    private val document = """{"format":"satellite-connection-diagnostics.v1","satellite":{"version":"2.3.0"}}"""

    @Test
    fun `a satellite that answers gives its own document and no error`() {
        val snapshot = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_OK, document), fallback, json)
        assertEquals(
            "satellite-connection-diagnostics.v1",
            snapshot.snapshot
                ?.get("format")
                ?.jsonPrimitive
                ?.content,
        )
        assertNull(snapshot.error)
    }

    @Test
    fun `a 404 from a satellite that has the route is an unknown connection`() {
        val snapshot = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_NOT_FOUND, ""), fallback, json)
        assertEquals(fallback, snapshot.snapshot)
        assertEquals(SnapshotError.UNKNOWN_CONNECTION, snapshot.error)
    }

    @Test
    fun `a satellite older than the route is told by its version and gets the cached capabilities`() {
        val snapshot = olderSatelliteSnapshot(fallback)
        assertEquals(fallback, snapshot.snapshot)
        assertEquals(SnapshotError.OLDER_SATELLITE, snapshot.error)
    }

    @Test
    fun `a version below 2_3_0 is older than the route`() {
        assertTrue(isOlderSatellite("2.2.9"))
        assertTrue(isOlderSatellite("1.9.0"))
        assertTrue(isOlderSatellite("2.2"))
    }

    @Test
    fun `2_3_0 and later have the route`() {
        assertFalse(isOlderSatellite("2.3.0"))
        assertFalse(isOlderSatellite("2.3.0-beta.1"))
        assertFalse(isOlderSatellite("2.10.0"))
        assertFalse(isOlderSatellite("3.0"))
    }

    @Test
    fun `a version the app never read is not taken as older, so the route is asked`() {
        assertFalse(isOlderSatellite(null))
        assertFalse(isOlderSatellite(""))
        assertFalse(isOlderSatellite("dev"))
    }

    @Test
    fun `a satellite that refuses our proof is unauthorized with the fallback still filled`() {
        val refused = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_UNAUTHORIZED, "{}"), fallback, json)
        val forbidden = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_FORBIDDEN, "{}"), fallback, json)
        assertEquals(SnapshotError.UNAUTHORIZED, refused.error)
        assertEquals(SnapshotError.UNAUTHORIZED, forbidden.error)
        assertEquals(fallback, refused.snapshot)
    }

    @Test
    fun `no pairing key to prove with is unauthorized`() {
        assertEquals(SnapshotError.UNAUTHORIZED, satelliteHostSnapshot(SnapshotReply.NoCredentials, fallback, json).error)
    }

    @Test
    fun `an answer that took longer than the deadline is a timeout`() {
        val snapshot = satelliteHostSnapshot(SnapshotReply.TimedOut, fallback, json)
        assertEquals(SnapshotError.TIMEOUT, snapshot.error)
        assertEquals(fallback, snapshot.snapshot)
    }

    @Test
    fun `a satellite that could not be reached is offline`() {
        assertEquals(SnapshotError.OFFLINE, satelliteHostSnapshot(SnapshotReply.Unreachable, fallback, json).error)
    }

    @Test
    fun `a server error is a bad reply`() {
        assertEquals(SnapshotError.BAD_REPLY, satelliteHostSnapshot(SnapshotReply.Answered(HTTP_SERVER_ERROR, ""), fallback, json).error)
    }

    @Test
    fun `an answer that is not the contract's document is a bad reply`() {
        val notAnObject = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_OK, "[1,2]"), fallback, json)
        val notJson = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_OK, "<html>"), fallback, json)
        val anotherShape = satelliteHostSnapshot(SnapshotReply.Answered(HTTP_OK, """{"format":"something-else"}"""), fallback, json)
        assertEquals(SnapshotError.BAD_REPLY, notAnObject.error)
        assertEquals(SnapshotError.BAD_REPLY, anotherShape.error)
        assertEquals(SnapshotError.BAD_REPLY, notJson.error)
        assertEquals(fallback, notJson.snapshot)
    }

    @Test
    fun `a satellite that is not live is offline without being asked`() {
        val snapshot = offlineSatelliteSnapshot(fallback)
        assertEquals(SnapshotError.OFFLINE, snapshot.error)
        assertEquals(fallback, snapshot.snapshot)
    }

    @Test
    fun `the capabilities fallback holds what the app fetched`() {
        val caps =
            ServerCapabilitiesDto(
                protocolVersion = 4,
                serverVersion = "2.2.0",
                backends = listOf(ServerBackendDto(id = "vigem", supported = true, available = true, audio = false)),
                controllerAudio = ServerControllerAudioDto(enabled = true, mic = false, speaker = true, hapticAudio = false),
            )
        val doc = capabilitiesSnapshotOf(caps, CatalogDto(catalogVersion = 2)) ?: error("no fallback")
        assertEquals(SATELLITE_CAPABILITIES_FORMAT, doc.text("format"))
        assertEquals("2.2.0", doc.text("serverVersion"))
        assertEquals("[1,4]", doc.getValue("protocol").toString())
        assertEquals(
            "vigem",
            doc
                .getValue("backends")
                .jsonArray
                .single()
                .jsonObject
                .text("id"),
        )
        assertEquals(
            true,
            doc
                .getValue("controllerAudio")
                .jsonObject["speaker"]
                ?.jsonPrimitive
                ?.boolean,
        )
        assertEquals(2, doc["catalogVersion"]?.jsonPrimitive?.int)
    }

    @Test
    fun `the fallback from a catalog alone names its version and protocol`() {
        val doc =
            capabilitiesSnapshotOf(null, CatalogDto(protocolVersion = 3, serverVersion = "2.1.0", catalogVersion = 2)) ?: error("none")
        assertEquals("2.1.0", doc.text("serverVersion"))
        assertEquals(JsonNull, doc["controllerAudio"])
        assertEquals(0, doc.getValue("backends").jsonArray.size)
    }

    @Test
    fun `with nothing fetched there is no fallback`() {
        assertNull(capabilitiesSnapshotOf(null, null))
    }

    @Test
    fun `a moonlight host gives its version and never its hostname`() {
        val facts = MoonlightHostFacts("Living Room PC", "SUNSHINE_SERVER_BUSY", "0.23.1", null, 0, 5L)
        val doc = moonlightSnapshotOf(facts) ?: error("no snapshot")
        assertEquals(MOONLIGHT_SERVERINFO_FORMAT, doc.text("format"))
        assertEquals("0.23.1", doc.text("appVersion"))
        assertFalse(doc.toString().contains("Living Room PC"))
    }

    // The plaintext serverinfo reports every host free and every caller unpaired.
    @Test
    fun `a moonlight host's state and pairing are null, never the plaintext answer`() {
        val facts = MoonlightHostFacts("Living Room PC", "SUNSHINE_SERVER_FREE", "0.23.1", null, 0, 5L)
        val doc = moonlightSnapshotOf(facts) ?: error("no snapshot")
        assertEquals(JsonNull, doc["state"])
        assertEquals(JsonNull, doc["pairStatus"])
    }

    @Test
    fun `a moonlight host never probed has no snapshot`() {
        assertNull(moonlightSnapshotOf(null))
    }

    @Test
    fun `the gateway's reply is a timeout, no answer, or the answer as given`() {
        assertEquals(SnapshotReply.TimedOut, snapshotReplyOf(HttpReply(0, "", null, timedOut = true)))
        assertEquals(SnapshotReply.Unreachable, snapshotReplyOf(HttpReply(0, "{}", null)))
        assertEquals(SnapshotReply.Answered(HTTP_NOT_FOUND, "nope"), snapshotReplyOf(HttpReply(HTTP_NOT_FOUND, "nope", null)))
    }

    @Test
    fun `every error has the contract's wire name`() {
        assertEquals(
            listOf("offline", "unauthorized", "olderSatellite", "timeout", "unknownConnection", "badReply"),
            SnapshotError.entries.map { it.wire },
        )
    }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SERVER_ERROR = 500
    }
}
