// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.diagnostics.WriteRoute
import com.tinkernorth.dish.core.diagnostics.WriteVerdict
import com.tinkernorth.dish.source.controllerdb.ControllerDbStatus
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.feedbackPair
import com.tinkernorth.dish.source.store.feedbackRgb
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The second cut of dish-diagnostics.v1: the report, the device facts, every controller path's
// input and output, and each host's session and snapshot.
class DiagnosticsBundleSecondCutTest {
    private val hostLabel = "Living Room PC"
    private val hostAddress = "192.168.1.40"

    private val verdict = WriteVerdict(WriteRoute.INTERRUPT_OUT, endpoint = 3, reportId = 2, ok = true, err = 0, ageMs = 50)

    private val output =
        BundleOutput(
            target = "directUsb",
            lanes =
                mapOf(
                    FeedbackKind.RUMBLE to BundleLane(count = 12, value = feedbackPair(32768, 0), atMs = 1_000L),
                    FeedbackKind.LIGHTBAR to BundleLane(count = 2, value = feedbackRgb(0, 0, 255), atMs = 2_000L),
                    FeedbackKind.PLAYER_LEDS to BundleLane(count = 1, value = 1L, atMs = 3_000L),
                    FeedbackKind.TRIGGER_RUMBLE to BundleLane(count = 4, value = feedbackPair(10, 20), atMs = 4_000L),
                    FeedbackKind.MIC_LED to BundleLane(count = 1, value = 2L, atMs = 5_000L),
                ),
            lastWrite = verdict,
            speaker = BundleVoice(VoiceState.OPEN, VoiceDevice.PAD, frames = null),
            haptics = BundleVoice(VoiceState.CLOSED, device = null, frames = null),
        )

    private val controller =
        BundleController(
            record = "sony-dualsense",
            verification = "verified",
            transport = "usb",
            path = "direct",
            hardware = null,
            firmware = null,
            expectMismatch = emptyList(),
            rateHz = 250,
            lastFeedback = null,
            lastWrite = verdict,
            audio = null,
            outcomes = emptyList(),
            input = BundleInput(frames = 12_345L, rateHz = 250, last = BundleInputState(0x1000, 1, -2, 3, -4, 5, 6, 0x0001)),
            output = output,
        )

    private val session =
        BundleSession(
            slots =
                listOf(
                    BundleSessionSlot(
                        index = 0,
                        record = "sony-dualsense",
                        requestedType = "dualsense",
                        appliedType = "dualsense",
                        backend = null,
                        result = "ok",
                        touchpadMode = "ds4",
                    ),
                ),
            ack = BundleAck(backendAvailable = true, activeControllers = 1, epoch = 12, activeBitmap = 1),
            rtt = BundleRtt(p50Ms = 2.1, p99Ms = 4.8, samples = 120),
            sent = mapOf("input" to 12_345L, "motion" to 0L),
            received = mapOf("rumble" to 12L, "sessionClose" to 0L),
        )

    private val satelliteSnapshot =
        buildJsonObject {
            put("format", "satellite-connection-diagnostics.v1")
            put("note", "requested by $hostLabel at $hostAddress")
        }

    private val inputs =
        BundleInputs(
            createdAtMs = CREATED_AT_MS,
            app = BundleApp(version = "2.7.0", build = "20700", flavor = "github"),
            device =
                BundleDevice(
                    model = "AYN Thor",
                    os = "Android 15",
                    arch = "arm64-v8a",
                    battery = BundleBattery(level = 81, charging = true),
                    thermal = "fair",
                    powerSave = false,
                    wifi = BundleWifi(band = "5", rssiDbm = -52, linkSpeedMbps = 866),
                    usbHost = true,
                ),
            controllerDb = ControllerDbStatus.UNLOADED,
            controllers = listOf(controller),
            hosts =
                listOf(
                    BundleHost(
                        kind = "satellite",
                        version = "2.3.0",
                        protocol = 4,
                        link = "active",
                        grants = null,
                        session = session,
                        snapshot = HostSnapshot(satelliteSnapshot, error = null),
                    ),
                    BundleHost(
                        kind = "satellite",
                        version = "2.2.0",
                        protocol = 4,
                        link = "active",
                        grants = null,
                        snapshot = HostSnapshot(buildJsonObject { put("format", "satellite-capabilities") }, SnapshotError.OLDER_SATELLITE),
                    ),
                ),
            log = emptyList(),
            logcat = "",
            forbidden = setOf(hostLabel),
            report = BundleReport(ReportCategory.NO_FEEDBACK, "Returnal: the pad works but never rumbles over USB.", "sony-dualsense"),
        )

    private val bundle = diagnosticsBundle(inputs)

    @Test
    fun `the report carries the category, the person's words and the controller`() {
        val report = bundle.obj("report")
        assertEquals("noFeedback", report.text("category"))
        assertEquals("Returnal: the pad works but never rumbles over USB.", report.text("text"))
        assertEquals("sony-dualsense", report.text("controller"))
    }

    @Test
    fun `a bundle made without a report says so with a null`() {
        assertEquals(JsonNull, diagnosticsBundle(inputs.copy(report = null))["report"])
    }

    @Test
    fun `every category has the contract's wire name`() {
        val names = ReportCategory.entries.map { it.wire }
        assertEquals(listOf("notRecognised", "noFeedback", "connection", "audio", "other", "question"), names)
    }

    @Test
    fun `the device carries battery, thermal, power save, wifi without a name and usb host`() {
        val device = bundle.obj("device")
        assertEquals(81, device.obj("battery")["level"]?.jsonPrimitive?.int)
        assertEquals(true, device.obj("battery")["charging"]?.jsonPrimitive?.boolean)
        assertEquals("fair", device.text("thermal"))
        assertEquals(false, device["powerSave"]?.jsonPrimitive?.boolean)
        assertEquals(setOf("band", "rssiDbm", "linkSpeedMbps"), device.obj("wifi").keys)
        assertEquals(-52, device.obj("wifi")["rssiDbm"]?.jsonPrimitive?.int)
        assertEquals(true, device["usbHost"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a device fact the phone could not read is null`() {
        val unknown = inputs.copy(device = BundleDevice(model = "AYN Thor", os = "Android 9", arch = "arm64-v8a"))
        val device = diagnosticsBundle(unknown).obj("device")
        listOf("battery", "thermal", "powerSave", "wifi", "usbHost").forEach { assertEquals(it, JsonNull, device[it]) }
    }

    @Test
    fun `the input is the frame count, the rate and the last state as hex buttons`() {
        val input = pad().obj("input")
        assertEquals(12_345L, input["frames"]?.jsonPrimitive?.long)
        assertEquals(250, input["rateHz"]?.jsonPrimitive?.int)
        val last = input.obj("last")
        assertEquals("1000", last.text("buttons"))
        assertEquals("0001", last.text("ext"))
        assertEquals(-2, last["ly"]?.jsonPrimitive?.int)
        assertEquals(6, last["rt"]?.jsonPrimitive?.int)
    }

    @Test
    fun `rumble output carries its count, target, last value and the last write`() {
        val rumble = pad().obj("output").obj("rumble")
        assertEquals(12L, rumble["count"]?.jsonPrimitive?.long)
        assertEquals("directUsb", rumble.text("target"))
        assertEquals(32768, rumble.obj("last")["strong"]?.jsonPrimitive?.int)
        assertEquals(0, rumble.obj("last")["weak"]?.jsonPrimitive?.int)
        assertEquals(1_000L, rumble.obj("last")["atMs"]?.jsonPrimitive?.long)
        assertEquals("interruptOut", rumble.obj("lastWrite").text("route"))
    }

    @Test
    fun `each other lane carries its own last value`() {
        val out = pad().obj("output")
        assertEquals(
            255,
            out
                .obj("lightbar")
                .obj("last")["b"]
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals("01", out.obj("playerLeds").obj("last").text("mask"))
        assertEquals(
            20,
            out
                .obj("triggerRumble")
                .obj("last")["right"]
                ?.jsonPrimitive
                ?.int,
        )
        assertEquals(
            2,
            out
                .obj("micLed")
                .obj("last")["state"]
                ?.jsonPrimitive
                ?.int,
        )
    }

    @Test
    fun `a lane nothing arrived on is a zero count with no last value`() {
        val effects = pad().obj("output").obj("triggerEffects")
        assertEquals(0L, effects["count"]?.jsonPrimitive?.long)
        assertEquals(JsonNull, effects["last"])
    }

    @Test
    fun `speaker and haptics carry their voice and device`() {
        val out = pad().obj("output")
        assertEquals("open", out.obj("speaker").text("voice"))
        assertEquals("pad", out.obj("speaker").text("device"))
        assertEquals("closed", out.obj("haptics").text("voice"))
        assertEquals(JsonNull, out.obj("haptics")["device"])
    }

    @Test
    fun `a path that knows nothing of input, output, standard or bluetooth says null`() {
        val bare = controller.copy(input = null, output = null)
        val json =
            diagnosticsBundle(inputs.copy(controllers = listOf(bare)))
                .getValue("controllers")
                .jsonArray
                .single()
                .jsonObject
        listOf("input", "output", "standard", "bluetooth").forEach { assertEquals(it, JsonNull, json[it]) }
    }

    @Test
    fun `a framework pad carries its sources, vibrator and lights`() {
        val standard = BundleStandard(sources = listOf("gamepad", "joystick"), vibrator = true, lights = false)
        val json = padOf(controller.copy(path = "standard", standard = standard)).obj("standard")
        assertEquals(listOf("gamepad", "joystick"), json.getValue("sources").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, json["vibrator"]?.jsonPrimitive?.boolean)
        assertEquals(false, json["lights"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a bluetooth pad carries its profile and transport layer and nothing else`() {
        val json = padOf(controller.copy(path = "bluetooth", bluetooth = BundleBluetooth("hid", "classic"))).obj("bluetooth")
        assertEquals(setOf("profile", "transportLayer"), json.keys)
        assertEquals("hid", json.text("profile"))
        assertEquals("classic", json.text("transportLayer"))
    }

    @Test
    fun `a host session carries its slots, ack, rtt and message counts`() {
        val session = host(0).obj("session")
        val slot =
            session
                .getValue("slots")
                .jsonArray
                .single()
                .jsonObject
        assertEquals(0, slot["index"]?.jsonPrimitive?.int)
        assertEquals("dualsense", slot.text("appliedType"))
        assertEquals(JsonNull, slot["backend"])
        assertEquals("ds4", slot.text("touchpadMode"))
        assertEquals("0001", session.obj("ack").text("activeBitmap"))
        assertEquals(12, session.obj("ack")["epoch"]?.jsonPrimitive?.int)
        assertEquals(2.1, session.obj("rtt")["p50Ms"]?.jsonPrimitive?.double ?: 0.0, 1e-9)
        assertEquals(12_345L, session.obj("sent")["input"]?.jsonPrimitive?.long)
        assertEquals(12L, session.obj("received")["rumble"]?.jsonPrimitive?.long)
    }

    @Test
    fun `a host with no session view says null`() {
        assertEquals(JsonNull, host(1)["session"])
    }

    @Test
    fun `the snapshot rides as the host gave it with its error`() {
        assertEquals("satellite-connection-diagnostics.v1", host(0).obj("snapshot").text("format"))
        assertEquals(JsonNull, host(0)["snapshotError"])
        assertEquals("olderSatellite", host(1).text("snapshotError"))
    }

    @Test
    fun `a snapshot is scrubbed of every forbidden value and address`() {
        val text = diagnosticsBundleText(inputs)
        assertFalse(text.contains(hostLabel))
        assertFalse(text.contains(hostAddress))
    }

    @Test
    fun `a driver version shaped like an address survives the snapshot scrub`() {
        val snapshot =
            buildJsonObject {
                put("driver", "1.21.442.0")
                put("serverVersion", "2.3.0.1")
                put("seen", "1.21.442.0")
            }
        val withVersions = inputs.copy(hosts = listOf(inputs.hosts[0].copy(snapshot = HostSnapshot(snapshot, error = null))))
        val json =
            diagnosticsBundle(withVersions)
                .getValue("hosts")
                .jsonArray[0]
                .jsonObject
                .obj("snapshot")
        assertEquals("1.21.442.0", json.text("driver"))
        assertEquals("2.3.0.1", json.text("serverVersion"))
        assertEquals("[address]", json.text("seen"))
    }

    @Test
    fun `a host's own label and address become its alias in its snapshot, keys included`() {
        val snapshot =
            buildJsonObject {
                put("format", "satellite-connection-diagnostics.v1")
                put("Gaming Rig", "the machine")
                put("seenAt", "10.0.0.7:9443")
            }
        val own = inputs.hosts[0].copy(snapshot = HostSnapshot(snapshot, error = null), ownNames = setOf("Gaming Rig", "10.0.0.7"))
        val json =
            diagnosticsBundle(inputs.copy(hosts = listOf(own)))
                .getValue("hosts")
                .jsonArray[0]
                .jsonObject
                .obj("snapshot")
        assertEquals("the machine", json.text("Host 1"))
        assertEquals("Host 1:9443", json.text("seenAt"))
        assertFalse(json.toString().contains("Gaming Rig"))
    }

    private fun pad(): JsonObject =
        bundle
            .getValue("controllers")
            .jsonArray
            .single()
            .jsonObject

    private fun padOf(controller: BundleController): JsonObject =
        diagnosticsBundle(inputs.copy(controllers = listOf(controller)))
            .getValue("controllers")
            .jsonArray
            .single()
            .jsonObject

    private fun host(index: Int): JsonObject = bundle.getValue("hosts").jsonArray[index].jsonObject

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private companion object {
        // 2026-11-19T21:04:11Z
        const val CREATED_AT_MS = 1_795_122_251_000L
    }
}
