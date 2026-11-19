// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpectCheckTest {
    private val found = FoundEndpoints(hasIn = true, hasOut = false)

    @Test
    fun `an expected endpoint that is missing is a mismatch in the contract's form`() {
        val expected = record("pad").expecting(true, true)
        assertEquals(listOf("endpoints.out: expected true, found false"), expectMismatches(expected, found))
    }

    @Test
    fun `an endpoint found where none was expected is a mismatch`() {
        val expected = record("pad").expecting(true, false)
        val withOut = FoundEndpoints(hasIn = true, hasOut = true)
        assertEquals(listOf("endpoints.out: expected false, found true"), expectMismatches(expected, withOut))
    }

    @Test
    fun `an endpoint the record does not know is not checked`() {
        val expected = record("pad").expecting(null, null)
        assertTrue(expectMismatches(expected, found).isEmpty())
    }

    @Test
    fun `both endpoints are checked, in before out`() {
        val expected = record("pad").expecting(true, true)
        val neither = FoundEndpoints(hasIn = false, hasOut = false)
        assertEquals(
            listOf("endpoints.in: expected true, found false", "endpoints.out: expected true, found false"),
            expectMismatches(expected, neither),
        )
    }

    @Test
    fun `hardware that matches has no mismatch`() {
        val expected = record("pad").expecting(true, false)
        assertTrue(expectMismatches(expected, found).isEmpty())
    }

    @Test
    fun `a pad with no record has nothing to compare`() {
        assertTrue(expectMismatches(null, found).isEmpty())
    }
}
