// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.abstracts

import com.tinkernorth.dish.architecture.testing.ControllerProbe
import com.tinkernorth.dish.architecture.testing.probe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AbstractControllerTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val upstream = MutableStateFlow(0)
    private val controller = RecordingController(upstream, scope)
    private val probe: ControllerProbe<Int> = controller.probe()

    // Records starts and applies in one list, so a test pins their order as well as their count.
    private class RecordingController(
        private val values: Flow<Int>,
        scope: CoroutineScope,
    ) : AbstractController<Int>(scope) {
        val events = mutableListOf<String>()

        override fun upstream(): Flow<Int> = values

        override fun onStarting() {
            events += STARTING
        }

        override fun apply(value: Int) {
            events += "$APPLIED $value"
        }
    }

    private fun settle() = scope.testScheduler.runCurrent()

    @Test
    fun `onStart twice keeps one collector`() =
        runTest(scope.testScheduler) {
            probe.start()
            probe.start()
            settle()
            upstream.value = 1
            settle()
            assertEquals(listOf(STARTING, "$APPLIED 0", "$APPLIED 1"), controller.events)
        }

    @Test
    fun `onStarting runs before the first apply on each real start`() =
        runTest(scope.testScheduler) {
            probe.start()
            settle()
            probe.stop()
            probe.start()
            settle()
            assertEquals(listOf(STARTING, "$APPLIED 0", STARTING, "$APPLIED 0"), controller.events)
        }

    @Test
    fun `an emission after onStop is not applied`() =
        runTest(scope.testScheduler) {
            probe.start()
            settle()
            probe.stop()
            upstream.value = 5
            settle()
            assertEquals(listOf(STARTING, "$APPLIED 0"), controller.events)
        }

    @Test
    fun `onStop twice is harmless`() =
        runTest(scope.testScheduler) {
            probe.start()
            settle()
            probe.stop()
            probe.stop()
            probe.start()
            settle()
            assertEquals(listOf(STARTING, "$APPLIED 0", STARTING, "$APPLIED 0"), controller.events)
        }

    @Test
    fun `a restart applies the value the upstream moved to while stopped`() =
        runTest(scope.testScheduler) {
            probe.start()
            settle()
            probe.stop()
            upstream.value = 7
            probe.start()
            settle()
            assertEquals(listOf(STARTING, "$APPLIED 0", STARTING, "$APPLIED 7"), controller.events)
        }

    private companion object {
        const val STARTING = "starting"
        const val APPLIED = "applied"
    }
}
