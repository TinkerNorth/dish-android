// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.controllerdb.ControllerDbCheckResult
import com.tinkernorth.dish.core.controllerdb.ControllerDbSource
import com.tinkernorth.dish.core.diagnostics.WriteRoute
import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.DiagnosticsLogEntry
import com.tinkernorth.dish.source.store.TestOutcome
import com.tinkernorth.dish.source.usb.UsbHardwareFacts
import com.tinkernorth.dish.source.usb.UsbInterfaceSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBundleTest {
    private val hostLabel = "Emir's Gaming PC"
    private val ipv4 = "192.168.1.23"
    private val ipv6 = "fe80::1c2d:3e4f:5a6b:7c8d"
    private val wifiName = "HomeNet-5G"
    private val pairingKey = "9f86d081884c7d659a2feaa0c55ad015"
    private val fingerprint = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"
    private val btMac = "AA:BB:CC:DD:EE:FF"
    private val machineId = "satellite:mid:5f3c2a19e0b7"

    private val forbiddenValues = listOf(hostLabel, ipv4, ipv6, wifiName, pairingKey, fingerprint, btMac, machineId)

    private val verdict = WriteVerdict(WriteRoute.INTERRUPT_OUT, endpoint = 3, reportId = 2, ok = false, err = 32, ageMs = 100)

    private val hardware =
        UsbHardwareFacts(
            vendorId = 0x054C,
            productId = 0x0CE6,
            bcdDevice = "1.07",
            product = "Wireless Controller",
            manufacturer = "Sony Interactive Entertainment",
            interfaces = listOf(UsbInterfaceSummary(3, 3, 0, 0)),
            endpointIn = 0x84,
            endpointOut = 0x03,
            descriptorSha256 = "ab".repeat(32),
        )

    private val controller =
        BundleController(
            record = "sony-dualsense",
            verification = "verified",
            transport = "usb",
            path = "direct",
            hardware = hardware,
            firmware = "2.21",
            expectMismatch = listOf("endpoints.out: expected true, found false"),
            rateHz = 250,
            lastFeedback = BundleFeedback("rumble", 1_763_586_000_000L, 12),
            lastWrite = verdict,
            audio = BundleAudio(4, refused = false),
            outcomes = listOf(TestOutcome("rumble.strong", "worked", 1_763_585_990_000L)),
        )

    private val inputs =
        BundleInputs(
            createdAtMs = CREATED_AT_MS,
            app = BundleApp(version = "2.7.0", build = "20700", flavor = "github"),
            device = BundleDevice(model = "AYN Thor", os = "Android 15", arch = "arm64-v8a"),
            controllerDb =
                ControllerDbStatus(
                    bundledVersion = 1,
                    activeVersion = 3,
                    source = ControllerDbSource.CACHE,
                    checksEnabled = true,
                    lastCheckAtMs = CREATED_AT_MS - 3_600_000L,
                    lastCheckResult = ControllerDbCheckResult.UP_TO_DATE,
                    checking = false,
                ),
            controllers = listOf(controller),
            hosts =
                listOf(
                    BundleHost("satellite", "2.3.0", 4, "active", BundleGrants(mic = false, speaker = true, hapticAudio = false)),
                    BundleHost("bluetooth", null, null, "saved", null),
                ),
            log =
                listOf(
                    DiagnosticsLogEntry(1, "link", "Connected to $hostLabel at $ipv4 ($machineId)"),
                    DiagnosticsLogEntry(2, "bt", "Bonded $btMac over $wifiName, pin $fingerprint"),
                    DiagnosticsLogEntry(3, "slot", "DualSense 054c:0ce6 attached on USB"),
                ),
            logcat = "11-19 21:04:11.123 I Dish: route via $ipv6 key=$pairingKey\n11-19 21:04:12.000 I Dish: ok",
            forbidden = setOf(hostLabel, wifiName, pairingKey, machineId),
        )

    private val bundle = diagnosticsBundle(inputs)
    private val text = diagnosticsBundleText(inputs)

    @Test
    fun `the serialized bundle holds none of the values it must never hold`() {
        forbiddenValues.forEach { assertFalse(it, text.contains(it, ignoreCase = true)) }
    }

    @Test
    fun `scrubbing keeps a vid pid pair and a logcat clock time`() {
        assertTrue(text.contains("054c:0ce6"))
        assertTrue(text.contains("21:04:11.123"))
    }

    @Test
    fun `the envelope is bundle 1 with the app, the device and its creation time`() {
        assertEquals(1, bundle["bundle"]?.jsonPrimitive?.int)
        assertEquals("2026-11-19T21:04:11Z", bundle["createdAt"]?.jsonPrimitive?.content)
        assertEquals("dish-android", bundle.obj("app")["name"]?.jsonPrimitive?.content)
        assertEquals("AYN Thor", bundle.obj("device")["model"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the controller database carries both versions, the source and the last check`() {
        val db = bundle.obj("controllerDb")
        assertEquals(1, db["bundledVersion"]?.jsonPrimitive?.int)
        assertEquals(3, db["activeVersion"]?.jsonPrimitive?.int)
        assertEquals("cache", db["source"]?.jsonPrimitive?.content)
        assertEquals("upToDate", db["lastCheckResult"]?.jsonPrimitive?.content)
        assertEquals("2026-11-19T20:04:11Z", db["lastCheckAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `never having checked is a null time and result`() {
        val unchecked = inputs.copy(controllerDb = inputs.controllerDb.copy(lastCheckAtMs = 0, lastCheckResult = null))
        val db = diagnosticsBundle(unchecked).obj("controllerDb")
        assertEquals(JsonNull, db["lastCheckAt"])
        assertEquals(JsonNull, db["lastCheckResult"])
    }

    @Test
    fun `a controller carries its record, hardware as hex, mismatches and outcomes`() {
        val pad = bundle["controllers"]!!.jsonArray.single().jsonObject
        assertEquals("sony-dualsense", pad["record"]?.jsonPrimitive?.content)
        val hw = pad.obj("hardware")
        assertEquals("054c", hw["vid"]?.jsonPrimitive?.content)
        assertEquals("84", hw.obj("endpoints")["in"]?.jsonPrimitive?.content)
        assertEquals(
            "03",
            hw["interfaces"]!!
                .jsonArray
                .single()
                .jsonObject["class"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("2.21", hw["firmware"]?.jsonPrimitive?.content)
        assertEquals(1, (pad["expectMismatch"] as JsonArray).size)
        assertEquals(
            "worked",
            pad["outcomes"]!!
                .jsonArray
                .single()
                .jsonObject["result"]
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(JsonNull, pad["capture"])
    }

    @Test
    fun `the last write carries its route, report id, errno name and the time it was written`() {
        val write =
            bundle["controllers"]!!
                .jsonArray
                .single()
                .jsonObject
                .obj("lastWrite")
        assertEquals("interruptOut", write["route"]?.jsonPrimitive?.content)
        assertEquals("02", write["reportId"]?.jsonPrimitive?.content)
        assertEquals("EPIPE", write["err"]?.jsonPrimitive?.content)
        assertEquals(CREATED_AT_MS - 100, write["atMs"]?.jsonPrimitive?.long)
    }

    @Test
    fun `hosts are aliases in order with their kind, version, protocol, link and grants`() {
        val hosts = bundle["hosts"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Host 1", "Host 2"), hosts.map { it["alias"]?.jsonPrimitive?.content })
        assertEquals(4, hosts[0]["protocol"]?.jsonPrimitive?.int)
        assertEquals("true", hosts[0].obj("grants")["speaker"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, hosts[1]["grants"])
    }

    @Test
    fun `logcat rides in extra`() {
        assertTrue(
            bundle
                .obj("extra")["logcat"]
                ?.jsonPrimitive
                ?.content
                .orEmpty()
                .contains("I Dish: ok"),
        )
    }

    @Test
    fun `a capture is usb-descriptors-v1 with its descriptors and rest reports`() {
        val capture =
            BundleCapture(
                device = "12010002",
                configuration = "09022900",
                strings = mapOf("2" to "0403"),
                hidReportDescriptors = listOf(BundleHidDescriptor(3, "05010905")),
                restReports = listOf(BundleCapturedReport(5, "0180")),
            )
        val captured = diagnosticsBundle(inputs.copy(controllers = listOf(controller.copy(capture = capture))))
        val json =
            captured
                .getValue("controllers")
                .jsonArray
                .single()
                .jsonObject
                .obj("capture")
        assertEquals("usb-descriptors-v1", json["format"]?.jsonPrimitive?.content)
        assertEquals("12010002", json["device"]?.jsonPrimitive?.content)
        assertEquals("09022900", json["configuration"]?.jsonPrimitive?.content)
        val hid =
            json
                .getValue("hidReportDescriptors")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(3, hid["interface"]?.jsonPrimitive?.int)
        assertEquals("05010905", hid["hex"]?.jsonPrimitive?.content)
        val report =
            json
                .getValue("restReports")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("0180", report["hex"]?.jsonPrimitive?.content)
        assertEquals(5L, report["atMs"]?.jsonPrimitive?.long)
    }

    @Test
    fun `the attachment is named for the app and the moment in UTC`() {
        assertEquals("dish-diagnostics-dish-android-20261119-210411.json", bundleFileName(CREATED_AT_MS))
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private companion object {
        // 2026-11-19T21:04:11Z
        const val CREATED_AT_MS = 1_795_122_251_000L
    }
}
