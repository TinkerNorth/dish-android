// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.repository

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.core.net.DiscoveryGateway
import com.tinkernorth.dish.core.net.HttpReply
import com.tinkernorth.dish.source.store.SatelliteHostFeaturesStore
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SatelliteCatalogRepositoryTest {
    private val gateway = mockk<DiscoveryGateway>()
    private val hostFeaturesStore = SatelliteHostFeaturesStore()
    private val repo = SatelliteCatalogRepository(gateway, Json { ignoreUnknownKeys = true }, hostFeaturesStore)
    private val server = DiscoveredServer(name = "Pc", ip = "10.0.0.5", udpPort = 9876, machineId = "m1")

    private val catalogBody =
        """{"locale":"en","protocolVersion":1,"serverVersion":"1.6.0","controllerTypes":""" +
            """[{"id":0,"slug":"xbox360","name":"Xbox 360 Controller"}],"hostFeatures":{}}"""

    private val receivedEtags = mutableListOf<String?>()
    private val replies = ArrayDeque<HttpReply>()

    private fun stubGateway() {
        coEvery { gateway.catalog(any(), any(), any(), anyNullable(), any()) } coAnswers {
            receivedEtags += arg<String?>(3)
            replies.removeFirst()
        }
    }

    @Test
    fun `a 200 fills the cache and the next fetch revalidates with the stored ETag`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, catalogBody, "\"1.6.0\"")
            replies += HttpReply(304, "", null)

            val first = repo.catalogFor(server, "sat-1")
            assertEquals("xbox360", first?.controllerTypes?.single()?.slug)
            assertNull(receivedEtags[0]) // nothing cached yet, unconditional GET

            val second = repo.catalogFor(server, "sat-1")
            assertEquals("\"1.6.0\"", receivedEtags[1]) // If-None-Match from the cache
            assertEquals("xbox360", second?.controllerTypes?.single()?.slug) // 304 → cache served
            val cached = repo.cached("sat-1")
            assertEquals("xbox360", cached?.controllerTypes?.single()?.slug)
        }

    @Test
    fun `a transport failure serves the last good copy instead of degrading the picker`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, catalogBody, null)
            repo.catalogFor(server, "sat-1")
            coEvery { gateway.catalog(any(), any(), any(), anyNullable(), any()) } throws
                RuntimeException("ECONNREFUSED")

            val stale = repo.catalogFor(server, "sat-1")

            assertEquals("xbox360", stale?.controllerTypes?.single()?.slug)
        }

    @Test
    fun `a server error keeps the cache, a malformed body too`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, catalogBody, null)
            replies += HttpReply(500, """{"error":"boom"}""", null)
            replies += HttpReply(200, "not json", null)

            repo.catalogFor(server, "sat-1")
            val afterServerError = repo.catalogFor(server, "sat-1")
            assertEquals("xbox360", afterServerError?.controllerTypes?.single()?.slug)
            val afterMalformedBody = repo.catalogFor(server, "sat-1")
            assertEquals("xbox360", afterMalformedBody?.controllerTypes?.single()?.slug)
        }

    @Test
    fun `a never-reachable satellite yields null, no cache to serve`() =
        runTest {
            coEvery { gateway.catalog(any(), any(), any(), anyNullable(), any()) } throws
                RuntimeException("ECONNREFUSED")

            assertNull(repo.catalogFor(server, "sat-1"))
            assertNull(repo.cached("sat-1"))
        }

    private val catalogWithMouseHost =
        """{"locale":"en","protocolVersion":1,"serverVersion":"1.6.0","controllerTypes":""" +
            """[{"id":0,"slug":"xbox360","name":"Xbox 360 Controller"}],""" +
            """"hostFeatures":{"mouseControl":{"supported":true}}}"""

    @Test
    fun `a 200 publishes parsed host features`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, catalogWithMouseHost, null)

            repo.catalogFor(server, "sat-1")

            assertEquals(true, hostFeaturesStore.featuresFor("sat-1")?.mouseControl)
            assertEquals(true, hostFeaturesStore.featuresFor("sat-1")?.hasCatalog)
        }

    @Test
    fun `a 304 re-publishes from cache`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, catalogWithMouseHost, "\"v\"")
            replies += HttpReply(304, "", null)

            repo.catalogFor(server, "sat-1")
            // Drop the published features so the 304 path has to re-publish from the cached catalog.
            hostFeaturesStore.clearConnection("sat-1")

            repo.catalogFor(server, "sat-1")

            assertEquals(true, hostFeaturesStore.featuresFor("sat-1")?.mouseControl)
            assertEquals("\"v\"", receivedEtags[1]) // conditional GET carried the cached ETag
        }

    @Test
    fun `a transport failure does not publish`() =
        runTest {
            coEvery { gateway.catalog(any(), any(), any(), anyNullable(), any()) } throws
                RuntimeException("ECONNREFUSED")

            repo.catalogFor(server, "sat-1")

            assertNull(hostFeaturesStore.featuresFor("sat-1"))
        }

    @Test
    fun `a malformed 200 body does not publish`() =
        runTest {
            stubGateway()
            replies += HttpReply(200, "not json", null)

            repo.catalogFor(server, "sat-1")

            assertNull(hostFeaturesStore.featuresFor("sat-1"))
        }
}
