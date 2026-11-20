// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.diagnostics

import android.view.InputDevice
import com.tinkernorth.dish.composer.BundleLane
import com.tinkernorth.dish.composer.BundleWifi
import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.composer.ReportCategory
import com.tinkernorth.dish.composer.SnapshotReply
import com.tinkernorth.dish.composer.VoiceDevice
import com.tinkernorth.dish.composer.VoiceState
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.Transport
import com.tinkernorth.dish.source.audio.NO_AUDIO_DEVICE
import com.tinkernorth.dish.source.audio.PlayoutLane
import com.tinkernorth.dish.source.audio.SpeakerTarget
import com.tinkernorth.dish.source.audio.speakerRouteKey
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.bluetooth.BluetoothLinkType
import com.tinkernorth.dish.source.connection.DiagnosticsAnswer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.moonlight.MoonlightPad
import com.tinkernorth.dish.source.connection.moonlight.MoonlightSessionState
import com.tinkernorth.dish.source.store.FeedbackKind
import com.tinkernorth.dish.source.store.LaneActivity
import com.tinkernorth.dish.source.store.PadAudioRefusal
import com.tinkernorth.dish.source.system.WifiBand
import com.tinkernorth.dish.source.system.WifiLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// How the report's controllers and hosts become the bundle's path, input, output and session.
class BundleMappersTest {
    private fun diag(
        slotId: String = "7",
        virtual: Boolean = false,
        transport: Transport? = Transport.Usb,
        direct: Boolean = false,
        state: ControllerDiagState = ControllerDiagState.CONNECTED,
        facts: PadFacts? = null,
    ) = ControllerDiag(
        slotId = slotId,
        name = "Wireless Controller",
        isVirtual = virtual,
        transport = transport,
        isUsbSynthetic = direct,
        hasGyro = false,
        pollRateHz = 250,
        gyroHz = 0,
        state = state,
        battery = null,
        host = null,
        functions = emptyList(),
        facts = facts,
    )

    private val device =
        PhysicalGamepadRegistry.Device(id = 7, name = "Wireless Controller", hasRumble = true, hasLightbar = false)

    private fun host(
        kind: ConnectionKind,
        satellite: SatelliteSnapshot? = null,
        moonlight: MoonlightSnapshot? = null,
        bluetooth: BtHostSnapshot? = null,
        slots: List<HostSlotDiag> = emptyList(),
    ) = HostDiag(
        id = "h",
        label = "Host",
        detail = "",
        kind = kind,
        live = LinkState.Connected,
        btProfile = null,
        telemetry = null,
        features = null,
        serverVersion = null,
        slots = slots,
        satellite = satellite,
        moonlight = moonlight,
        bluetooth = bluetooth,
    )

    @Test
    fun `the on-screen pad's path is virtual`() {
        assertEquals("virtual", bundlePathOf(diag(slotId = "virtual", virtual = true, transport = null)))
    }

    @Test
    fun `a pad the app reads over usb is direct`() {
        assertEquals("direct", bundlePathOf(diag(slotId = "-5", direct = true)))
    }

    @Test
    fun `a pad the phone reads is standard`() {
        assertEquals("standard", bundlePathOf(diag()))
    }

    @Test
    fun `a pad over bluetooth is bluetooth whoever reads it`() {
        assertEquals("bluetooth", bundlePathOf(diag(transport = Transport.Bluetooth)))
        assertEquals("bluetooth", bundlePathOf(diag(slotId = "-9", transport = Transport.Bluetooth, direct = true)))
    }

    @Test
    fun `a usb pad has no bluetooth facts`() {
        assertNull(bundleBluetoothOf(diag(), BluetoothLinkType.CLASSIC))
    }

    @Test
    fun `the phone's bluetooth pad is hid over classic or le, and a dual-mode link is unknown`() {
        val classic = bundleBluetoothOf(diag(transport = Transport.Bluetooth), BluetoothLinkType.CLASSIC)
        val dual = bundleBluetoothOf(diag(transport = Transport.Bluetooth), BluetoothLinkType.DUAL)
        val unknown = bundleBluetoothOf(diag(transport = Transport.Bluetooth), BluetoothLinkType.UNKNOWN)
        assertEquals("hid", classic?.profile)
        assertEquals("classic", classic?.transportLayer)
        assertNull(dual?.transportLayer)
        assertNull(unknown?.transportLayer)
    }

    @Test
    fun `the app's own bluetooth client is ble over le`() {
        val ble = bundleBluetoothOf(diag(slotId = "-9", transport = Transport.Bluetooth, direct = true), null)
        assertEquals("ble", ble?.profile)
        assertEquals("le", ble?.transportLayer)
    }

    @Test
    fun `a pad the phone reads carries its sources, vibrator and lights`() {
        val sources = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK
        val standard = bundleStandardOf(diag(), sources, device)
        assertEquals(listOf("gamepad", "joystick"), standard?.sources)
        assertEquals(true, standard?.vibrator)
        assertEquals(false, standard?.lights)
    }

    @Test
    fun `a pad the phone does not read has no standard facts`() {
        assertNull(bundleStandardOf(diag(slotId = "-5", direct = true), InputDevice.SOURCE_GAMEPAD, device))
        assertNull(bundleStandardOf(diag(slotId = "virtual", virtual = true, transport = null), null, null))
    }

    @Test
    fun `a pad the phone reads but could not describe has null facts inside`() {
        val standard = bundleStandardOf(diag(), null, null)
        assertEquals(emptyList<String>(), standard?.sources)
        assertNull(standard?.vibrator)
    }

    @Test
    fun `the input is the frame count, the rate and the last state`() {
        val snapshot = InputSnapshot(buttons = 0x1000, lx = 5, rt = 9, extButtons = 3)
        val input = bundleInputOf(diag(), frames = 42L, snapshot)
        assertEquals(42L, input.frames)
        assertEquals(250, input.rateHz)
        assertEquals(0x1000, input.last?.buttons)
        assertEquals(5, input.last?.lx)
        assertEquals(9, input.last?.rt)
        assertEquals(3, input.last?.ext)
    }

    @Test
    fun `a pad with no rate and no state reads null`() {
        val input = bundleInputOf(diag().copy(pollRateHz = 0), frames = null, snapshot = null)
        assertNull(input.rateHz)
        assertNull(input.last)
        assertNull(input.frames)
    }

    @Test
    fun `each lane the store kept rides with its count, value and time`() {
        val lanes = mapOf(FeedbackKind.RUMBLE to LaneActivity(count = 3, value = 9L, atMs = 4L))
        assertEquals(mapOf(FeedbackKind.RUMBLE to BundleLane(3, 9L, 4L)), bundleLanesOf(lanes))
    }

    @Test
    fun `the feedback target names the route the way the contract does`() {
        assertEquals("phone", feedbackTargetWire(FeedbackTargetKind.PHONE))
        assertEquals("directUsb", feedbackTargetWire(FeedbackTargetKind.PAD_DIRECT))
        assertEquals("framework", feedbackTargetWire(FeedbackTargetKind.PAD_FRAMEWORK))
        assertEquals("none", feedbackTargetWire(FeedbackTargetKind.NONE))
    }

    @Test
    fun `a lane the platform refused is refused`() {
        val refusals = mapOf(("-5" to PlayoutLane.HAPTICS) to PadAudioRefusal("-5", PlayoutLane.HAPTICS, 12, 4))
        val voice = bundleVoiceOf("-5", PlayoutLane.HAPTICS, emptyMap(), refusals)
        assertEquals(VoiceState.REFUSED, voice.voice)
    }

    @Test
    fun `a lane with a voice is open on the pad or on the phone's default output`() {
        val pad = SpeakerTarget("-5", sessionHandle = 1, controllerIndex = 0, playbackDeviceId = 12)
        val phone = SpeakerTarget("virtual", sessionHandle = 1, controllerIndex = 1, playbackDeviceId = NO_AUDIO_DEVICE)
        val voices = mapOf(speakerRouteKey(1, 0) to pad, speakerRouteKey(1, 1) to phone)
        val onPad = bundleVoiceOf("-5", PlayoutLane.SPEAKER, voices, emptyMap())
        val onPhone = bundleVoiceOf("virtual", PlayoutLane.SPEAKER, voices, emptyMap())
        assertEquals(VoiceState.OPEN, onPad.voice)
        assertEquals(VoiceDevice.PAD, onPad.device)
        assertEquals(VoiceDevice.DEFAULT, onPhone.device)
    }

    @Test
    fun `a lane with no voice is closed on no device`() {
        val voice = bundleVoiceOf("-5", PlayoutLane.HAPTICS, emptyMap(), emptyMap())
        assertEquals(VoiceState.CLOSED, voice.voice)
        assertNull(voice.device)
    }

    @Test
    fun `a satellite session lists its slots by index with the types it asked for and got`() {
        val bindings =
            mapOf(
                "-5" to
                    SatelliteConnection.SlotBinding(1, controllerType = 2, registered = true, lastApplyResult = "ok", lastAppliedType = 2),
                "virtual" to SatelliteConnection.SlotBinding(0, controllerType = 9, registered = false),
            )
        val snapshot = SatelliteSnapshot(live = true, slots = bindings, telemetry = null)
        val facts =
            PadFacts(
                0,
                0,
                null,
                null,
                0,
                0,
                BluetoothLinkType.UNKNOWN,
                0,
                0,
                null,
                null,
                null,
                record = RecordFacts("sony-dualsense", null, "verified", emptyList()),
            )
        val controllers = listOf(diag(slotId = "-5", direct = true, facts = facts))
        val session = bundleSessionOf(host(ConnectionKind.SATELLITE, satellite = snapshot), controllers, mapOf(2 to "dualsense"))
        val slots = session?.slots.orEmpty()
        assertEquals(listOf(0, 1), slots.map { it.index })
        assertEquals("9", slots[0].requestedType)
        assertNull(slots[0].appliedType)
        assertEquals("dualsense", slots[1].requestedType)
        assertEquals("dualsense", slots[1].appliedType)
        assertEquals("sony-dualsense", slots[1].record)
        assertEquals("ok", slots[1].result)
        assertNull(slots[1].backend)
    }

    @Test
    fun `a satellite session carries the ack, rtt and message counts`() {
        val telemetry = SatelliteTelemetry(vigemAvailable = true, activeControllers = 1, epoch = 12, activeBitmap = 1)
        val stats = SatelliteSessionStats(2.0, 4.0, 10, emptyList(), 0, 0, 0)
        val counts = SessionMessageCounts(sent = mapOf("input" to 5L), received = mapOf("rumble" to 2L))
        val snapshot = SatelliteSnapshot(true, emptyMap(), telemetry, stats = stats, messages = counts)
        val session = bundleSessionOf(host(ConnectionKind.SATELLITE, satellite = snapshot), emptyList(), emptyMap())
        assertEquals(12, session?.ack?.epoch)
        assertEquals(true, session?.ack?.backendAvailable)
        assertEquals(10, session?.rtt?.samples)
        assertEquals(mapOf("input" to 5L), session?.sent)
        assertEquals(mapOf("rumble" to 2L), session?.received)
    }

    @Test
    fun `an ack the satellite never enriched is no ack`() {
        val telemetry = SatelliteTelemetry(vigemAvailable = false, activeControllers = -1, epoch = -1, activeBitmap = -1)
        val snapshot = SatelliteSnapshot(true, emptyMap(), telemetry)
        assertNull(bundleSessionOf(host(ConnectionKind.SATELLITE, satellite = snapshot), emptyList(), emptyMap())?.ack)
    }

    @Test
    fun `a satellite with no session view has no session`() {
        assertNull(bundleSessionOf(host(ConnectionKind.SATELLITE), emptyList(), emptyMap()))
    }

    @Test
    fun `a moonlight session lists its pads by number and the reports it sent`() {
        val pads = mapOf("-5" to MoonlightPad("-5", number = 1, emulatedType = 0, capabilities = 0, supportedButtons = 0))
        val moonlight =
            MoonlightSnapshot(MoonlightSessionState.Live, rttMs = 3L, pads = pads, reportsByNumber = mapOf(1 to 40L), facts = null)
        val session = bundleSessionOf(host(ConnectionKind.MOONLIGHT, moonlight = moonlight), emptyList(), emptyMap())
        assertEquals(listOf(1), session?.slots?.map { it.index })
        assertEquals(mapOf("input" to 40L), session?.sent)
        assertNull(session?.ack)
    }

    @Test
    fun `a bluetooth host session counts the reports it sent`() {
        val slots = listOf(HostSlotDiag("-5", "pad", typeId = null, slotIndex = 0))
        val bt = BtHostSnapshot(BluetoothGamepadRegistry.SlotState(), reportsSent = 77L)
        val session = bundleSessionOf(host(ConnectionKind.BLUETOOTH, bluetooth = bt, slots = slots), emptyList(), emptyMap())
        assertEquals(mapOf("input" to 77L), session?.sent)
        assertEquals(listOf(0), session?.slots?.map { it.index })
    }

    @Test
    fun `the report names a controller by its record, else by what it calls itself`() {
        val facts =
            PadFacts(
                0,
                0,
                null,
                null,
                0,
                0,
                BluetoothLinkType.UNKNOWN,
                0,
                0,
                null,
                null,
                null,
                record = RecordFacts("sony-dualsense", null, "verified", emptyList()),
            )
        assertEquals("sony-dualsense", reportControllerOf(diag(facts = facts)))
        assertEquals("Wireless Controller", reportControllerOf(diag()))
    }

    @Test
    fun `the report picks the first attached pad, never the on-screen one`() {
        val controllers =
            listOf(
                diag(slotId = "virtual", virtual = true, transport = null),
                diag(slotId = "3", state = ControllerDiagState.DISCONNECTING),
                diag(slotId = "4"),
            )
        assertEquals("4", defaultReportSlotOf(controllers))
    }

    @Test
    fun `with only the on-screen pad the report picks none`() {
        assertNull(defaultReportSlotOf(listOf(diag(slotId = "virtual", virtual = true, transport = null))))
    }

    @Test
    fun `the report trims the person's words and names the chosen controller`() {
        val draft = ReportDraft(ReportCategory.AUDIO, "  no sound \n", slotId = "7")
        val report = reportOf(draft, listOf(diag()))
        assertEquals("no sound", report.text)
        assertEquals("Wireless Controller", report.controller)
        assertEquals(ReportCategory.AUDIO, report.category)
    }

    @Test
    fun `a report about no controller names none`() {
        assertNull(reportOf(ReportDraft(ReportCategory.QUESTION, "how?", slotId = null), listOf(diag())).controller)
    }

    @Test
    fun `a satellite never asked is unreachable and one without a key cannot be proven to`() {
        assertEquals(SnapshotReply.Unreachable, snapshotReplyOf(DiagnosticsAnswer.NotLive))
        assertEquals(SnapshotReply.NoCredentials, snapshotReplyOf(DiagnosticsAnswer.NoKey))
        assertEquals(SnapshotReply.Answered(200, "{}"), snapshotReplyOf(DiagnosticsAnswer.Asked(HttpReply(200, "{}", null))))
    }

    @Test
    fun `the phone's wifi rides by band, signal and speed alone`() {
        val wifi = bundleWifiOf(WifiLink(rssiDbm = -60, linkSpeedMbps = 433, frequencyMhz = 5180, ipv4 = "192.168.1.9"))
        assertEquals(BundleWifi(band = "5", rssiDbm = -60, linkSpeedMbps = 433), wifi)
    }

    @Test
    fun `a host's own names are its label and the address it is reached at`() {
        assertEquals(setOf("Host", "10.0.0.7"), ownNamesOf(host(ConnectionKind.SATELLITE), "10.0.0.7"))
        assertEquals(setOf("Host"), ownNamesOf(host(ConnectionKind.MOONLIGHT), null))
    }

    @Test
    fun `only the chosen controller's capture rides`() {
        val capture =
            com.tinkernorth.dish.composer
                .BundleCapture(null, null, emptyMap(), emptyList(), emptyList())
        val captures = mapOf("-5" to capture, "-6" to capture)
        assertEquals(setOf("-5"), capturesRiding(captures, "-5").keys)
        assertEquals(emptySet<String>(), capturesRiding(captures, null).keys)
    }

    @Test
    fun `the wifi band is its gigahertz as the fleet writes it and unknown is null`() {
        assertEquals("2.4", wifiBandWire(WifiBand.GHZ_2_4))
        assertEquals("5", wifiBandWire(WifiBand.GHZ_5))
        assertEquals("6", wifiBandWire(WifiBand.GHZ_6))
        assertNull(wifiBandWire(WifiBand.UNKNOWN))
    }
}
