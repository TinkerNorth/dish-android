// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.model.HostFeatureSet
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import com.tinkernorth.dish.source.store.SatelliteHostRuntime
import com.tinkernorth.dish.source.store.SatelliteHostRuntimeStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SatelliteCapabilitiesRepositoryTest {
    private val gateway = mockk<DiscoveryGateway>()
    private val hostFeaturesStore = SatelliteHostFeaturesStore()
    private val runtimeStore = SatelliteHostRuntimeStore()
    private val repo =
        SatelliteCapabilitiesRepository(gateway, Json { ignoreUnknownKeys = true }, hostFeaturesStore, runtimeStore)
    private val server = DiscoveredServer(name = "Pc", ip = "10.0.0.5", udpPort = 9876, machineId = "m1")

    private fun body(
        motionAvailable: Boolean,
        rumbleSupported: Boolean,
    ): String =
        """{"protocolVersion":1,"serverVersion":"1.6.0","maxControllers":16,""" +
            """"backend":{"id":"vigem","supported":true,"available":true,"errorCode":null},""" +
            """"motion":{"available":$motionAvailable},""" +
            """"host":{"catalog":{"supported":true},""" +
            """"mouseControl":{"supported":true,"available":true},""" +
            """"keyboardControl":{"supported":false},""" +
            """"rumble":{"supported":$rumbleSupported,"available":$rumbleSupported}}}"""

    @Test
    fun `a host block seeds the host layer and the runtime store`() =
        runTest {
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns
                HttpReply(200, body(motionAvailable = false, rumbleSupported = false), null)

            val caps = repo.refresh(server, "sat-1")

            assertEquals("1.6.0", caps?.serverVersion)
            val host = hostFeaturesStore.featuresFor("sat-1")
            assertTrue(host?.mouseControl == true)
            assertFalse(host?.keyboardControl == true)
            assertFalse(host?.rumbleReturn == true) // host explicitly reports no rumble
            // Motion backend down → the pre-bind runtime store reflects it.
            assertFalse(runtimeStore.runtimeFor("sat-1")?.motionBackendOk == true)
        }

    @Test
    fun `a richer catalog read is not clobbered by the capabilities probe`() =
        runTest {
            // The catalog already populated the host layer (with touchpad modes); setIfAbsent
            // must leave it intact.
            val fromCatalog =
                HostFeatureSet(
                    hasCatalog = true,
                    mouseControl = true,
                    keyboardControl = false,
                    rumbleReturn = true,
                    touchpadModes = setOf("off", "ds4", "mouse"),
                )
            hostFeaturesStore.setFeatures("sat-1", fromCatalog)
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns
                HttpReply(200, body(motionAvailable = true, rumbleSupported = true), null)

            repo.refresh(server, "sat-1")

            assertEquals(fromCatalog, hostFeaturesStore.featuresFor("sat-1"))
            // Runtime is still published regardless.
            assertTrue(runtimeStore.runtimeFor("sat-1")?.motionBackendOk == true)
        }

    @Test
    fun `the host block seeds features even while the motion backend is down`() =
        runTest {
            // Decouples the static seed (present) from the runtime read (down right now):
            // the headline item-5 scenario.
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns
                HttpReply(200, body(motionAvailable = false, rumbleSupported = true), null)

            repo.refresh(server, "sat-1")

            val host = hostFeaturesStore.featuresFor("sat-1")
            assertTrue(host?.mouseControl == true)
            assertTrue(host?.rumbleReturn == true)
            assertFalse(runtimeStore.runtimeFor("sat-1")?.motionBackendOk == true)
        }

    @Test
    fun `a non-200 reply yields null and writes nothing`() =
        runTest {
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns HttpReply(404, "", null)

            assertNull(repo.refresh(server, "sat-1"))
            assertNull(hostFeaturesStore.featuresFor("sat-1"))
            assertNull(runtimeStore.runtimeFor("sat-1"))
        }

    @Test
    fun `an older satellite without a host block leaves the host layer untouched`() =
        runTest {
            val legacyBody =
                """{"protocolVersion":1,"serverVersion":"1.5.0","maxControllers":16,""" +
                    """"backend":{"id":"vigem","supported":true,"available":true,"errorCode":null},""" +
                    """"motion":{"available":true}}"""
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns HttpReply(200, legacyBody, null)

            repo.refresh(server, "sat-1")

            assertNull(hostFeaturesStore.featuresFor("sat-1")) // no host block → no optimistic clobber
            assertTrue(runtimeStore.runtimeFor("sat-1")?.motionBackendOk == true)
        }

    @Test
    fun `a transport failure yields null and writes nothing`() =
        runTest {
            coEvery { gateway.serverCapabilities(any(), any(), any()) } throws RuntimeException("ECONNREFUSED")

            assertNull(repo.refresh(server, "sat-1"))
            assertNull(hostFeaturesStore.featuresFor("sat-1"))
            assertNull(runtimeStore.runtimeFor("sat-1"))
        }

    @Test
    fun `a malformed 200 body yields null and writes neither store`() =
        runTest {
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns HttpReply(200, "not json", null)

            assertNull(repo.refresh(server, "sat-1"))
            assertNull(hostFeaturesStore.featuresFor("sat-1"))
            assertNull(runtimeStore.runtimeFor("sat-1"))
        }

    @Test
    fun `an empty 200 body yields null and writes nothing`() =
        runTest {
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns HttpReply(200, "", null)

            assertNull(repo.refresh(server, "sat-1"))
            assertNull(hostFeaturesStore.featuresFor("sat-1"))
            assertNull(runtimeStore.runtimeFor("sat-1"))
        }

    @Test
    fun `an absent motion block records the backend as up`() =
        runTest {
            // motion is nullable: a host block with no motion field means backend-up, not down.
            val noMotionBody =
                """{"protocolVersion":1,"serverVersion":"1.6.0","maxControllers":16,""" +
                    """"backend":{"id":"vigem","supported":true,"available":true,"errorCode":null},""" +
                    """"host":{"catalog":{"supported":true},""" +
                    """"mouseControl":{"supported":true,"available":true},""" +
                    """"keyboardControl":{"supported":false},""" +
                    """"rumble":{"supported":false,"available":false}}}"""
            coEvery { gateway.serverCapabilities(any(), any(), any()) } returns HttpReply(200, noMotionBody, null)

            repo.refresh(server, "sat-1")

            assertEquals(SatelliteHostRuntime(motionBackendOk = true), runtimeStore.runtimeFor("sat-1"))
        }
}
