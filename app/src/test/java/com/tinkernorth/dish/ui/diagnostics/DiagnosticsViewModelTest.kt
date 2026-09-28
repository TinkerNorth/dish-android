// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.diagnostics

import com.tinkernorth.dish.composer.ConnectionKind
import com.tinkernorth.dish.composer.ConnectionSummary
import com.tinkernorth.dish.composer.LinkState
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.system.WifiLinkSource
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val profilingEnabled = MutableStateFlow(false)
    private val store =
        mockk<LatencyProfilingStore> { every { state } returns profilingEnabled }
    private val native =
        mockk<PhysicalInputNative> {
            justRun { setLatencyProbe(any()) }
            every { hotPathBenchJson(false) } returns """{"rtt_us":{"n":4,"p50":6000.0}}"""
        }
    private val wifi = mockk<WifiLinkSource>(relaxed = true)
    private val sources = mockk<DiagnosticsSources>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { sources.world } returns flowOf(worldWithOneSatellite())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        DiagnosticsViewModel(
            store,
            native,
            wifi,
            Json { ignoreUnknownKeys = true },
            sources,
            mockk(relaxed = true),
        )

    private fun worldWithOneSatellite(): DiagnosticsWorld {
        val satellite =
            ConnectionSummary(
                id = SATELLITE_ID,
                kind = ConnectionKind.SATELLITE,
                label = SATELLITE_LABEL,
                detail = "",
                live = LinkState.Connected,
                boundSlotIds = emptyList(),
            )
        return DiagnosticsWorld(
            devices = emptyMap(),
            virtualName = "Virtual Controller",
            bindings = emptyMap(),
            summaries = listOf(satellite),
            satellites = emptyMap(),
            rates = emptyMap(),
            batteries = emptyMap(),
            caps = emptyMap(),
            hostFeatures = emptyMap(),
            serverVersions = emptyMap(),
        )
    }

    private fun satelliteRows(): LatencyRows {
        val unmeasured =
            HostLatencyRow(
                label = SATELLITE_LABEL,
                kind = ConnectionKind.SATELLITE,
                oneWayMs = null,
                samples = 0,
                controlRttMs = null,
            )
        return LatencyRows(hosts = listOf(unmeasured), pads = emptyList())
    }

    @Test
    fun `latency is off while profiling is disabled and no probe runs`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            val job = launch { vm.latency.collect {} }
            dispatcher.scheduler.runCurrent()

            assertEquals(DiagnosticsViewModel.LatencyUi.Off, vm.latency.value)
            verify(exactly = 0) { native.setLatencyProbe(true) }
            job.cancel()
        }

    @Test
    fun `a collector with profiling on arms the probe and parses stats`() =
        runTest(dispatcher.scheduler) {
            profilingEnabled.value = true
            val vm = viewModel()
            val job = launch { vm.latency.collect {} }
            dispatcher.scheduler.runCurrent()

            verify { native.setLatencyProbe(true) }
            val stats = vm.latency.value as DiagnosticsViewModel.LatencyUi.Stats
            assertEquals(3.0, stats.panel.networkOneWayP50Ms!!, 1e-6)

            job.cancel()
            dispatcher.scheduler.runCurrent()
            verify { native.setLatencyProbe(false) }
        }

    @Test
    fun `unparseable native output reads as waiting, not a crash`() =
        runTest(dispatcher.scheduler) {
            every { native.hotPathBenchJson(false) } returns "garbage"
            profilingEnabled.value = true
            val vm = viewModel()
            val job = launch { vm.latency.collect {} }
            dispatcher.scheduler.runCurrent()

            assertTrue(vm.latency.value is DiagnosticsViewModel.LatencyUi.Waiting)
            job.cancel()
        }

    @Test
    fun `the latency panel carries the overview rows beside the probe state`() =
        runTest(dispatcher.scheduler) {
            val vm = viewModel()
            val job = launch { vm.latencyPanel.collect {} }
            dispatcher.scheduler.runCurrent()

            val off = vm.latencyPanel.value
            assertEquals(DiagnosticsViewModel.LatencyUi.Off, off.ui)
            assertEquals(satelliteRows(), off.rows)

            profilingEnabled.value = true
            dispatcher.scheduler.runCurrent()

            val measuring = vm.latencyPanel.value
            assertTrue(measuring.ui is DiagnosticsViewModel.LatencyUi.Stats)
            assertEquals(satelliteRows(), measuring.rows)
            job.cancel()
        }

    @Test
    fun `turning profiling off mid-run disarms the probe`() =
        runTest(dispatcher.scheduler) {
            profilingEnabled.value = true
            val vm = viewModel()
            val job = launch { vm.latency.collect {} }
            dispatcher.scheduler.runCurrent()

            profilingEnabled.value = false
            dispatcher.scheduler.runCurrent()

            verify { native.setLatencyProbe(false) }
            assertEquals(DiagnosticsViewModel.LatencyUi.Off, vm.latency.value)
            job.cancel()
        }

    private companion object {
        const val SATELLITE_ID = "sat"
        const val SATELLITE_LABEL = "Living Room"
    }
}
