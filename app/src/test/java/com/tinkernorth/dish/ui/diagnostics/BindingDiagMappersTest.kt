// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.model.Feature
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.MicCapturePlan
import com.tinkernorth.dish.source.audio.MicCaptureTarget
import com.tinkernorth.dish.source.audio.SpeakerTarget
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.inputrate.SlotInputRates
import com.tinkernorth.dish.source.store.FeedbackActivity
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.LinkHistoryState
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingDiagMappersTest {
    private val touchpadMode: (String) -> String = { "ds4" }

    private fun world(): DiagnosticsWorld {
        val device =
            PhysicalGamepadRegistry.Device(
                id = -5,
                name = "DualSense",
                isUsbSynthetic = true,
                vendorId = 0x054C,
                productId = 0x0CE6,
                pollRateHz = 250,
            )
        val binding =
            SatelliteConnection.SlotBinding(
                controllerIndex = 1,
                controllerType = 2,
                registered = true,
                lastAdvertisedCaps = 0x0007,
                lastApplyResult = "ok",
            )
        val stats =
            SatelliteSessionStats(
                rttP50Ms = 8.0,
                rttP99Ms = 12.0,
                rttSamples = 10,
                rttRecentMs = emptyList(),
                pings = 10,
                acks = 10,
                missed = 0,
            )
        val snapshot =
            SatelliteSnapshot(
                live = true,
                slots = mapOf("-5" to binding),
                telemetry = SatelliteTelemetry(vigemAvailable = true, activeControllers = 1, epoch = 1, activeBitmap = 0b10),
                stats = stats,
                slotSends = mapOf(1 to 4200L),
                slotMotion = mapOf(1 to 300L),
            )
        val summary =
            ConnectionSummary(
                id = "sat",
                kind = ConnectionKind.SATELLITE,
                label = "PC",
                detail = "192.168.1.5:9876",
                live = LinkState.Connected,
                boundSlotIds = listOf("-5"),
                satelliteControllerTypes = mapOf("-5" to 2),
            )
        return DiagnosticsWorld(
            devices = mapOf(-5 to device),
            virtualName = "Virtual Controller",
            bindings = mapOf("-5" to "sat"),
            summaries = listOf(summary),
            satellites = mapOf("sat" to snapshot),
            rates = mapOf("-5" to SlotInputRates(controllerHz = 250)),
            batteries = emptyMap(),
            caps = emptyMap(),
            hostFeatures = emptyMap(),
            serverVersions = emptyMap(),
            pads =
                PadWorld(
                    deviceLatency =
                        mapOf(
                            -5 to DeviceLatency(samples = 5, stage1P50Ms = 0.2, stage1P99Ms = 0.5, gapP50Ms = 4.0, gapP99Ms = 4.2),
                        ),
                ),
            links =
                LinkWorld(
                    history = LinkHistoryState(boundSinceMs = mapOf("-5" to 1000L)),
                    feedback = mapOf("-5" to FeedbackActivity(FeedbackKind.LIGHTBAR, atMs = 5000L, count = 3)),
                ),
            audio =
                AudioWorld(
                    micPlan = MicCapturePlan(armed = setOf(MicCaptureTarget("-5", "sat")), delivering = emptySet()),
                    speakerVoices = mapOf(1L to SpeakerTarget("-5", sessionHandle = 3, controllerIndex = 1, playbackDeviceId = 0)),
                    speakerDrops = mapOf("-5" to 96L),
                ),
            nowMs = 9000L,
        )
    }

    @Test
    fun `a satellite binding gathers slot truth, streams, audio, feedback and an estimate`() {
        val b = bindingDiag("-5", world(), touchpadMode) ?: error("unbound")
        assertEquals("DualSense", b.controllerName)
        assertEquals(1, b.host.slotIndex)
        assertEquals(1000L, b.boundSinceMs)
        assertEquals(listOf(Feature.ANALOG_TRIGGERS, Feature.RUMBLE, Feature.MOTION), declaredFeatures(b.declaredCaps ?: 0))
        assertEquals("ok", b.applyResult)
        assertEquals(4200L, b.packetsSent)
        assertEquals(300L, b.motionSent)
        assertEquals(BatterySource.PHONE, b.batterySource)
        assertEquals(FeedbackTargetKind.PAD_DIRECT, b.rumbleTarget)
        assertEquals(FeedbackKind.LIGHTBAR, b.feedback?.lastKind)
        assertEquals(MicSlotState.ARMED_MUTED, b.micState)
        assertTrue(b.speakerPlaying)
        assertEquals(96L, b.speakerDropped)
        assertEquals(2.0, b.latency.pollHalfMs ?: 0.0, 1e-9)
        assertEquals(0.2, b.latency.phonePathMs ?: 0.0, 1e-9)
        assertEquals(4.0, b.latency.networkOneWayMs ?: 0.0, 1e-9)
        assertEquals(6.2, b.latency.totalMs ?: 0.0, 1e-9)
    }

    @Test
    fun `an unbound slot has no binding and the estimate stays unknown without every part`() {
        assertNull(bindingDiag(VIRTUAL_SLOT_ID, world(), touchpadMode))
        val estimate = LatencyEstimatePolicy.estimate(pollRateHz = 0, phonePathMs = 0.3, rttMs = null)
        assertNull(estimate.pollHalfMs)
        assertNull(estimate.totalMs)
    }

    @Test
    fun `battery source and feedback target follow the slot kind`() {
        assertEquals(BatterySource.LOWEST_OF_BOTH, batterySource(Transport.Bluetooth, isVirtual = false))
        assertEquals(BatterySource.PHONE, batterySource(Transport.Usb, isVirtual = false))
        assertEquals(BatterySource.PHONE, batterySource(null, isVirtual = true))
        assertEquals(FeedbackTargetKind.PHONE, feedbackTarget(VIRTUAL_SLOT_ID))
        assertEquals(FeedbackTargetKind.PAD_FRAMEWORK, feedbackTarget("9"))
        assertEquals(FeedbackTargetKind.NONE, feedbackTarget("nope"))
    }
}
