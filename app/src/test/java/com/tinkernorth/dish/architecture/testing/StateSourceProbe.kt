// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals

/**
 * Test kit for any [AbstractStateSource] subclass. Drives lifecycle and records
 * state transitions on a [TestScope]'s `backgroundScope` so the collector is
 * auto-cancelled at test end (no "uncompleted coroutines" noise).
 *
 * Typical test (copy this shape):
 *
 * ```
 * @Test fun connectedFlipsToOn() = stateSourceTest {
 *     val source = MyStateSource(...)
 *     val probe = source.probe(this)
 *     probe.start()
 *     source.simulateUpstream(...)
 *     testScheduler.runCurrent()
 *     probe.assertState(MyState.On)
 *     probe.stop()
 * }
 * ```
 *
 * Event-emitting classes (`DishNotifications`,
 * `SatelliteConnectionManager`) are not [AbstractStateSource]s and do not use
 * this probe — they own their own SharedFlows and tests should collect those
 * directly.
 */
class StateSourceProbe<S> internal constructor(
    private val source: AbstractStateSource<S>,
    scope: TestScope,
) {
    val owner = TestLifecycleOwner()

    private val capturedStates = mutableListOf<S>()

    init {
        // backgroundScope is auto-cancelled when runTest's body returns, so the
        // collector never outlives the test.
        source.state.onEach { capturedStates += it }.launchIn(scope.backgroundScope)
    }

    /** Drive the source through `onStart(owner)`. */
    fun start() {
        owner.start()
        source.onStart(owner)
    }

    /** Drive the source through `onStop(owner)`. */
    fun stop() {
        source.onStop(owner)
        owner.stop()
    }

    val state: S get() = source.state.value
    val states: List<S> get() = capturedStates.toList()

    fun assertState(expected: S) {
        assertEquals("Latest state mismatch", expected, source.state.value)
    }

    fun clearCaptured() {
        capturedStates.clear()
    }
}

/** Convenience: spawn a probe attached to a [TestScope]'s `backgroundScope`. */
fun <S> AbstractStateSource<S>.probe(scope: TestScope): StateSourceProbe<S> = StateSourceProbe(this, scope)

/** Convenience for the common case where a test runs inside [runTest]. */
fun stateSourceTest(block: suspend TestScope.() -> Unit) = runTest(testBody = block)
