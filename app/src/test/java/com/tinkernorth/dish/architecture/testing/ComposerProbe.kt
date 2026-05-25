// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.abstracts.AbstractComposer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

class ComposerProbe<S> internal constructor(
    private val composer: AbstractComposer<S>,
    scope: TestScope,
) {
    private val captured = mutableListOf<S>()

    init {
        composer.state.onEach { captured += it }.launchIn(scope.backgroundScope)
    }

    val latest: S get() = composer.state.value
    val sequence: List<S> get() = captured.toList()

    fun assertLatest(expected: S) {
        assertEquals("Latest derived state", expected, latest)
    }

    fun assertSequence(vararg expected: S) {
        assertEquals("Derived state sequence", expected.toList(), sequence)
    }

    fun assertContains(predicate: (S) -> Boolean) {
        if (captured.none(predicate)) fail("No captured state matched. Sequence = $captured")
    }

    fun clearCaptured() {
        captured.clear()
    }
}

fun <S> AbstractComposer<S>.probe(scope: TestScope): ComposerProbe<S> = ComposerProbe(this, scope)

fun composerTest(block: suspend TestScope.() -> Unit) = runTest(testBody = block)

fun <T> fakeUpstream(initial: T): MutableStateFlow<T> = MutableStateFlow(initial)

fun <T> StateFlow<T>.asUpstream(): StateFlow<T> = this
