// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import org.junit.Test

/**
 * Canonical example of the [AbstractStateSource] test pattern using
 * [StateSourceProbe]. Use this file as the copy-paste source when adding tests
 * for a new `AbstractStateSource` subclass.
 *
 * The fixture is a tiny `AbstractStateSource` with a deterministic, fully
 * in-process state machine — no real Android types — so the test reads as
 * documentation of the pattern rather than a test of any production code.
 *
 * **The recurring pattern in every test:**
 *
 *   1. Build the source.
 *   2. Build a probe attached to the `TestScope`.
 *   3. Call `probe.start()` (drives `onStart`).
 *   4. Drive the source.
 *   5. `testScheduler.runCurrent()` to drain the backgroundScope collector.
 *   6. Assert on `probe.state` or `probe.states`.
 */
class StateSourceProbeSampleTest {
    private enum class GateState { CLOSED, OPENING, OPEN }

    /** Sample subject: a 3-state gate that exposes only `state`. */
    private class GateSource : AbstractStateSource<GateState>(GateState.CLOSED) {
        fun beginOpening() {
            setState(GateState.OPENING)
        }

        fun completeOpening() {
            setState(GateState.OPEN)
        }

        override fun onStart(owner: LifecycleOwner) {
            // Real sources would register receivers / start collectors here.
        }

        override fun onStop(owner: LifecycleOwner) {
            // Real sources would unregister and clean up here.
            setState(GateState.CLOSED)
        }
    }

    @Test
    fun `state captures every transition when ticks separate them`() =
        stateSourceTest {
            val source = GateSource()
            val probe = source.probe(this)
            probe.start()
            testScheduler.runCurrent()

            source.beginOpening()
            testScheduler.runCurrent() // tick — collector sees OPENING

            source.completeOpening()
            testScheduler.runCurrent() // tick — collector sees OPEN

            probe.assertState(GateState.OPEN)
            assert(probe.states.containsAll(listOf(GateState.CLOSED, GateState.OPENING, GateState.OPEN))) {
                "Expected CLOSED, OPENING, OPEN — got ${probe.states}"
            }
        }

    @Test
    fun `state coalesces transitions inside a single tick`() =
        stateSourceTest {
            val source = GateSource()
            val probe = source.probe(this)
            probe.start()
            testScheduler.runCurrent()

            // Two setState() calls between ticks. StateFlow conflates: the
            // collector sees the latest only. This is intentional StateFlow
            // behaviour and the pattern documents it — assert the FINAL state,
            // not the intermediate.
            source.beginOpening()
            source.completeOpening()
            testScheduler.runCurrent()

            probe.assertState(GateState.OPEN)
        }

    @Test
    fun `onStop resets state`() =
        stateSourceTest {
            val source = GateSource()
            val probe = source.probe(this)
            probe.start()
            source.completeOpening()
            testScheduler.runCurrent()
            probe.assertState(GateState.OPEN)

            probe.stop()
            testScheduler.runCurrent()
            probe.assertState(GateState.CLOSED)
        }
}
