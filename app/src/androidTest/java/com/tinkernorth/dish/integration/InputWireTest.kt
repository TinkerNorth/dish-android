// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.composer.CONTROLLER_TYPE_XBOX
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the input data plane end to end: bind the virtual slot, reach a
 * live session, then stream a gamepad report and a motion sample the way the
 * on-screen overlay does. The encrypted UDP packets must decrypt at the fake
 * with the right opcodes, proving the native encode + ChaCha20-Poly1305 send
 * path works against a session key derived from the real handshake.
 */
@RunWith(AndroidJUnit4::class)
class InputWireTest {
    private val manager get() = AppSingletons.satellite
    private var fake: FakeSatellite? = null

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
    }

    @After
    fun tearDown() {
        AppSingletons.resetConnections()
        fake?.close()
        fake = null
    }

    private fun bindVirtualAndGoLive(): DiscoveredServer {
        val satellite = FakeSatellite().also { fake = it }
        val server = satellite.server()
        val id = SatelliteConnection.idFor(server)
        manager.pairWithPin(server, "1234")
        assertTrue(
            "session should reach Live",
            AppSingletons.await { manager.get(id)?.state?.value == SatelliteSessionState.Live },
        )
        // Declare the virtual slot straight on the live connection. Production
        // routes this through the lifecycle-scoped SlotTopologyController, which
        // does not run on a headless CI emulator (no foreground window); calling
        // applyDesired directly exercises the same declare -> syncSlot ->
        // controller PUT -> registered path without the lifecycle dependency.
        manager.get(id)!!.applyDesired(mapOf(VIRTUAL_SLOT_ID to CONTROLLER_TYPE_XBOX))
        assertTrue(
            "the virtual slot must register on the satellite before streams flow",
            AppSingletons.await {
                manager
                    .get(id)
                    ?.slots
                    ?.value
                    ?.get(VIRTUAL_SLOT_ID)
                    ?.registered == true
            },
        )
        return server
    }

    @Test
    fun gamepadReport_reachesTheSatelliteAsEncryptedInput() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!

        // UDP is lossy by design; send a burst so the assertion isn't hostage to one packet.
        assertTrue(
            "an encrypted INPUT (0x0001) packet must decrypt at the satellite",
            pollSend(satellite, 0x0001) {
                conn.sendReport(VIRTUAL_SLOT_ID, buttons = 0x1000, lt = 0, rt = 0, lx = 12000, ly = -8000, rx = 0, ry = 0)
            },
        )
    }

    @Test
    fun batteryAndTouchpad_reachTheSatelliteAsEncryptedTelemetry() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!

        assertTrue(
            "an encrypted BATTERY (0x000B) packet must decrypt at the satellite",
            pollSend(satellite, 0x000B) { conn.sendBattery(VIRTUAL_SLOT_ID, level = 77, status = 1) },
        )
        assertTrue(
            "an encrypted TOUCHPAD (0x000C) packet must decrypt at the satellite",
            pollSend(satellite, 0x000C) {
                conn.sendTouchpad(
                    VIRTUAL_SLOT_ID,
                    finger0Active = true,
                    finger1Active = false,
                    buttonPressed = false,
                    finger0TrackingId = 1,
                    finger0X = 500,
                    finger0Y = 300,
                    finger1TrackingId = 0,
                    finger1X = 0,
                    finger1Y = 0,
                    eventTimeMs = 1_000,
                )
            },
        )
    }

    @Test
    fun motionSample_reachesTheSatelliteAsEncryptedMotion() {
        val server = bindVirtualAndGoLive()
        val conn = manager.get(SatelliteConnection.idFor(server))!!
        val satellite = fake!!

        assertTrue(
            "an encrypted MOTION (0x000A) packet must decrypt at the satellite",
            pollSend(satellite, 0x000A) {
                conn.sendMotion(
                    VIRTUAL_SLOT_ID,
                    gyroX = 100,
                    gyroY = -50,
                    gyroZ = 25,
                    accelX = 0,
                    accelY = 4096,
                    accelZ = 0,
                    timestampDeltaUs = 16_000,
                )
            },
        )
    }

    private fun pollSend(
        satellite: FakeSatellite,
        opcode: Int,
        send: () -> Unit,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            send()
            if (satellite.udpOpcodes.contains(opcode)) return true
            Thread.sleep(100)
        }
        return satellite.udpOpcodes.contains(opcode)
    }
}
