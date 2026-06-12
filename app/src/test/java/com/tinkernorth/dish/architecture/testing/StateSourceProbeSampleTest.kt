// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import org.junit.Test

class StateSourceProbeSampleTest {
    private enum class GateState { CLOSED, OPENING, OPEN }

    private class GateSource : AbstractStateSource<GateState>(GateState.CLOSED) {
        fun beginOpening() {
            setState(GateState.OPENING)
        }

        fun completeOpening() {
            setState(GateState.OPEN)
        }

        override fun onStop(owner: LifecycleOwner) {
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
            testScheduler.runCurrent()

            source.completeOpening()
            testScheduler.runCurrent()

            probe.assertState(GateState.OPEN)
            assert(probe.states.containsAll(listOf(GateState.CLOSED, GateState.OPENING, GateState.OPEN))) {
                "Expected CLOSED, OPENING, OPEN, got ${probe.states}"
            }
        }

    @Test
    fun `state coalesces transitions inside a single tick`() =
        stateSourceTest {
            val source = GateSource()
            val probe = source.probe(this)
            probe.start()
            testScheduler.runCurrent()

            // StateFlow conflates intermediate values between ticks.
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
