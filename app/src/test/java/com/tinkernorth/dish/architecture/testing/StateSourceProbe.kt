// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.abstracts.AbstractStateSource
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals

class StateSourceProbe<S> internal constructor(
    private val source: AbstractStateSource<S>,
    scope: TestScope,
) {
    val owner = TestLifecycleOwner()

    private val capturedStates = mutableListOf<S>()

    init {
        source.state.onEach { capturedStates += it }.launchIn(scope.backgroundScope)
    }

    fun start() {
        owner.start()
        source.onStart(owner)
    }

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

fun <S> AbstractStateSource<S>.probe(scope: TestScope): StateSourceProbe<S> = StateSourceProbe(this, scope)

fun stateSourceTest(block: suspend TestScope.() -> Unit) = runTest(testBody = block)
