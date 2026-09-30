// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.abstracts

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class AbstractStateSourceTest {
    private class CounterSource : AbstractStateSource<Int>(0) {
        fun add(amount: Int) {
            setState { current -> current + amount }
        }

        fun reset(value: Int) {
            setState(value)
        }

        fun addReturningPrior(amount: Int): Int = getAndSetState { current -> current + amount }
    }

    @Test
    fun `the reducer form folds into the current state`() {
        val source = CounterSource()
        source.add(2)
        source.add(3)
        assertEquals(5, source.state.value)
    }

    @Test
    fun `the value form replaces the state`() {
        val source = CounterSource()
        source.add(2)
        source.reset(7)
        assertEquals(7, source.state.value)
    }

    @Test
    fun `the get-and-set form applies the reducer and returns the state it replaced`() {
        val source = CounterSource()
        source.add(2)
        val prior = source.addReturningPrior(3)
        assertEquals(2, prior)
        assertEquals(5, source.state.value)
    }

    // setState and getAndSetState are the only ways in: nothing outside the class can reach the
    // writable flow, so no code in the module can write a source's state behind its back.
    @Test
    fun `the writable state flow is reachable only from inside the source`() {
        val type = AbstractStateSource::class.java
        val exposingMethods =
            type.declaredMethods.filter { method ->
                MutableStateFlow::class.java.isAssignableFrom(method.returnType) && !Modifier.isPrivate(method.modifiers)
            }
        val exposingFields =
            type.declaredFields.filter { field ->
                MutableStateFlow::class.java.isAssignableFrom(field.type) && !Modifier.isPrivate(field.modifiers)
            }
        assertTrue("non-private accessors: $exposingMethods", exposingMethods.isEmpty())
        assertTrue("non-private fields: $exposingFields", exposingFields.isEmpty())
    }
}
