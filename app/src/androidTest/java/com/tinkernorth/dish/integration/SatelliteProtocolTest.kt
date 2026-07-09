// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.integration.AppSingletons.await
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the production connection stack (manager, HTTP client with TOFU
 * pinning, session crypto, native UDP path) against an in-process
 * [FakeSatellite] speaking the documented protocol-1 contract.
 */
@RunWith(AndroidJUnit4::class)
class SatelliteProtocolTest {
    private val manager get() = AppSingletons.satellite
    private var fake: FakeSatellite? = null
    private var extraFake: FakeSatellite? = null

    @Before
    fun setUp() {
        AppSingletons.resetConnections()
    }

    @After
    fun tearDown() {
        AppSingletons.resetConnections()
        fake?.close()
        extraFake?.close()
        fake = null
        extraFake = null
    }

    private fun startFake(): FakeSatellite = FakeSatellite().also { fake = it }

    private fun stateOf(server: DiscoveredServer): SatelliteSessionState? = manager.get(SatelliteConnection.idFor(server))?.state?.value

    private fun pairAndGoLive(satellite: FakeSatellite): DiscoveredServer {
        val server = satellite.server()
        manager.pairWithPin(server, "1234")
        assertTrue(
            "session should reach Live, was ${stateOf(server)}",
            await { stateOf(server) == SatelliteSessionState.Live },
        )
        return server
    }

    @Test
    fun pairWithPin_reachesLive_withValidatedProofAndFullDescriptorPut() {
        val satellite = startFake()
        val server = pairAndGoLive(satellite)
        val id = SatelliteConnection.idFor(server)

        assertNotNull("pairing key must be stored", AppSingletons.store.satelliteSharedKey(id))
        assertEquals("satellite mints one pairing key", satellite.pairingKeyHex != null, true)
        assertTrue("exactly the paired satellite is remembered", manager.remembered().any { it.id == id })
        val put = satellite.sessionPuts.last()
        assertEquals(1, put.getInt("protocolVersion"))
        assertTrue("session PUT carries deviceId", put.getString("deviceId").isNotEmpty())
        assertTrue("declarative controllers array is present", put.has("controllers"))
    }

    @Test
    fun wrongPin_neverStoresAKey() {
        val satellite = startFake()
        val server = satellite.server()
        manager.pairWithPin(server, "9999")
        assertTrue(
            "connection should settle back to Idle",
            await { stateOf(server) == SatelliteSessionState.Idle },
        )
        assertNull(AppSingletons.store.satelliteSharedKey(SatelliteConnection.idFor(server)))
        assertNull("no key minted for a bad PIN", satellite.pairingKeyHex)
    }

    @Test
    fun heartbeats_reachTheFake_decryptable_andAcksKeepTheSessionAlive() {
        val satellite = startFake()
        val server = pairAndGoLive(satellite)

        assertTrue(
            "an encrypted heartbeat must decrypt with the contract-derived session key",
            satellite.awaitUdpOpcode(0x0002),
        )
        assertTrue(
            "the client must keep sending heartbeats on the 2s cadence",
            await(timeoutMs = 12_000) { satellite.heartbeatCount >= 2 },
        )
        assertTrue(
            "an acked session must never be reaped to Idle",
            stateOf(server) != SatelliteSessionState.Idle,
        )
    }

    @Test
    fun tofu_rejectsADifferentCertOnTheSameIdentity() {
        val sharedMachineId = "tofu-shared"
        val satellite = startFake()
        val server = satellite.server(machineId = sharedMachineId)
        manager.pairWithPin(server, "1234")
        assertTrue(await { stateOf(server) == SatelliteSessionState.Live })
        val id = SatelliteConnection.idFor(server)
        manager.disconnect(id)
        satellite.close()

        // Same identity, different cert: an on-path attacker after the pin was
        // set. A second FakeSatellite mints a distinct cert, so its fingerprint
        // cannot match the pin.
        val imposter = FakeSatellite().also { extraFake = it }
        imposter.pairingKeyHex = null
        val imposterServer = imposter.server(machineId = sharedMachineId)
        manager.connect(imposterServer)
        assertTrue(
            "pin mismatch must refuse the session",
            await { stateOf(imposterServer) != SatelliteSessionState.Live },
        )
        assertTrue("imposter's cert must fail the TLS handshake before any PUT", imposter.sessionPuts.isEmpty())
        assertFalse("imposter is never asked to pair", imposter.pairingKeyHex != null)
    }

    @Test
    fun terminal401_dropsTheStoredKeyAndStopsRetrying() {
        val satellite = startFake()
        val server = pairAndGoLive(satellite)
        val id = SatelliteConnection.idFor(server)
        manager.disconnect(id)
        assertTrue(await { stateOf(server) == SatelliteSessionState.Idle })

        satellite.force401Code = "NOT_PAIRED"
        val putsBefore = satellite.sessionPuts.size
        manager.connect(server)
        assertTrue(
            "terminal 401 must drop the pairing key",
            await { AppSingletons.store.satelliteSharedKey(id) == null },
        )
        Thread.sleep(3_000)
        assertEquals(
            "terminal 401 must not be retried",
            putsBefore,
            satellite.sessionPuts.size,
        )
        assertTrue(stateOf(server) != SatelliteSessionState.Live)
    }

    @Test
    fun epochMismatchInHeartbeatAck_triggersTheReconcileGet() {
        val satellite = startFake()
        pairAndGoLive(satellite)
        assertTrue(satellite.awaitUdpOpcode(0x0002))

        satellite.ackEpochOverride = 7
        assertTrue(
            "epoch drift in the ack must trigger GET /api/connections/{id}",
            await(timeoutMs = 15_000) { satellite.reconcileGets.isNotEmpty() },
        )
    }

    @Test
    fun forget_selfUnpairsOnTheSatelliteAndForgetsLocally() {
        val satellite = startFake()
        val server = pairAndGoLive(satellite)
        val id = SatelliteConnection.idFor(server)

        manager.forget(id)
        assertTrue(
            "forget must send DELETE /api/pair with a valid proof",
            await { satellite.unpairCalls.isNotEmpty() },
        )
        assertNull(AppSingletons.store.satelliteSharedKey(id))
        assertTrue(manager.remembered().none { it.id == id })
    }

    @Test
    fun sessionCloseNotify_kicked_takesTheSessionDown() {
        val satellite = startFake()
        val server = pairAndGoLive(satellite)
        assertTrue(satellite.awaitUdpOpcode(0x0002))

        satellite.sendSessionClose(reason = 1)
        assertTrue(
            "an authenticated kick must leave Live",
            await(timeoutMs = 15_000) { stateOf(server) != SatelliteSessionState.Live },
        )
    }
}
