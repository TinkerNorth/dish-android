// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.core.model.DiscoveredServer
import com.tinkernorth.dish.repository.SatelliteCatalogRepository
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.connection.SatelliteSessionState
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Test

// Prewarms a satellite's catalog once its link reaches Live, exactly once per id.
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPrewarmerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val server = DiscoveredServer(name = "PC", ip = "1.1.1.1", httpPort = 9877)
    private val connsFlow = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private val satellite =
        mockk<SatelliteConnectionManager>(relaxed = true) { every { connections } returns connsFlow }
    private val catalogRepo = mockk<SatelliteCatalogRepository>(relaxed = true)

    private fun conn(
        connId: String,
        session: MutableStateFlow<SatelliteSessionState>,
    ): SatelliteConnection =
        mockk(relaxed = true) {
            every { id } returns connId
            every { state } returns session
            every { server } returns MutableStateFlow(this@CatalogPrewarmerTest.server)
        }

    private fun startPrewarmer() = CatalogPrewarmer(satellite, catalogRepo, scope).start()

    @Test
    fun `a live satellite is warmed once`() {
        connsFlow.value = mapOf(HOST to conn(HOST, MutableStateFlow(SatelliteSessionState.Live)))
        startPrewarmer()
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { catalogRepo.catalogFor(server, HOST) }
    }

    @Test
    fun `a satellite is warmed only after it reaches Live`() {
        val session = MutableStateFlow(SatelliteSessionState.Linking)
        connsFlow.value = mapOf(HOST to conn(HOST, session))
        startPrewarmer()
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 0) { catalogRepo.catalogFor(any(), any()) }

        session.value = SatelliteSessionState.Live
        scope.testScheduler.advanceUntilIdle()
        coVerify(exactly = 1) { catalogRepo.catalogFor(server, HOST) }
    }

    @Test
    fun `each satellite warms once across emissions`() {
        val live = conn(HOST, MutableStateFlow(SatelliteSessionState.Live))
        connsFlow.value = mapOf(HOST to live)
        startPrewarmer()
        scope.testScheduler.advanceUntilIdle()

        connsFlow.value = mapOf(HOST to live, OTHER to conn(OTHER, MutableStateFlow(SatelliteSessionState.Live)))
        scope.testScheduler.advanceUntilIdle()

        coVerify(exactly = 1) { catalogRepo.catalogFor(server, HOST) }
        coVerify(exactly = 1) { catalogRepo.catalogFor(server, OTHER) }
    }

    private companion object {
        const val HOST = "satellite:1.1.1.1:9876"
        const val OTHER = "satellite:2.2.2.2:9876"
    }
}
