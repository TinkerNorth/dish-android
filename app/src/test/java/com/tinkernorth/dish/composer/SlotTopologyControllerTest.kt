// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.architecture.testing.TestLifecycleOwner
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.source.store.ControllerTypeStore
import com.tinkernorth.dish.source.store.SlotBindingStore
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlotTopologyControllerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val bindingStore = SlotBindingStore()
    private val typeStore = ControllerTypeStore()
    private val connsFlow = MutableStateFlow<Map<String, SatelliteConnection>>(emptyMap())
    private lateinit var satellite: SatelliteConnectionManager
    private lateinit var owner: TestLifecycleOwner

    @Before
    fun setUp() {
        satellite = mockk { every { connections } returns connsFlow }
        owner = TestLifecycleOwner()
    }

    private fun startController() {
        val controller =
            SlotTopologyController(
                composer = SlotTopologyComposer(bindingStore, typeStore, scope),
                satellite = satellite,
                scope = scope,
            )
        owner.lifecycle.addObserver(controller)
        owner.start()
        scope.testScheduler.runCurrent()
    }

    private fun mockConn(): SatelliteConnection = mockk { justRun { applyDesired(any()) } }

    @Test
    fun `a bind converges the target connection to the desired slot set`() =
        runTest(scope.testScheduler) {
            val conn = mockConn()
            connsFlow.value = mapOf("satellite:m1" to conn)
            startController()

            typeStore.setType("satellite:m1", "5", CONTROLLER_TYPE_PLAYSTATION)
            bindingStore.bind("5", "satellite:m1")
            scope.testScheduler.runCurrent()

            verify { conn.applyDesired(mapOf("5" to CONTROLLER_TYPE_PLAYSTATION)) }
        }

    @Test
    fun `an unbind converges the connection to an empty slot set`() =
        runTest(scope.testScheduler) {
            val conn = mockConn()
            connsFlow.value = mapOf("satellite:m1" to conn)
            startController()

            bindingStore.bind("5", "satellite:m1")
            scope.testScheduler.runCurrent()
            bindingStore.unbind("5")
            scope.testScheduler.runCurrent()

            verify { conn.applyDesired(emptyMap()) }
        }

    @Test
    fun `a binding recorded before the session object exists converges once it appears`() =
        runTest(scope.testScheduler) {
            startController()
            bindingStore.bind("5", "satellite:m1")
            scope.testScheduler.runCurrent()

            val conn = mockConn()
            connsFlow.value = mapOf("satellite:m1" to conn)
            scope.testScheduler.runCurrent()

            verify { conn.applyDesired(mapOf("5" to CONTROLLER_TYPE_XBOX)) }
        }

    @Test
    fun `every live connection is converged, including to empty`() =
        runTest(scope.testScheduler) {
            val bound = mockConn()
            val other = mockConn()
            connsFlow.value = mapOf("satellite:m1" to bound, "satellite:m2" to other)
            startController()

            bindingStore.bind("5", "satellite:m1")
            scope.testScheduler.runCurrent()

            verify { bound.applyDesired(mapOf("5" to CONTROLLER_TYPE_XBOX)) }
            verify { other.applyDesired(emptyMap()) }
        }

    @Test
    fun `a restart reconciles from current stores, not remembered state`() =
        runTest(scope.testScheduler) {
            val conn = mockConn()
            connsFlow.value = mapOf("satellite:m1" to conn)
            startController()
            owner.stop()
            scope.testScheduler.runCurrent()

            // Stores moved while the actuator was stopped.
            bindingStore.bind("5", "satellite:m1")

            owner.start()
            scope.testScheduler.runCurrent()

            verify { conn.applyDesired(mapOf("5" to CONTROLLER_TYPE_XBOX)) }
        }
}
