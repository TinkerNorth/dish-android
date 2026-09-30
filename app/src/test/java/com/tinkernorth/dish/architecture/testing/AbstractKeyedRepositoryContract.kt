// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.interfaces.KeyedRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The value-keyed half of the contract: put(value) and removeValue(value) route through keyOf.
abstract class AbstractKeyedRepositoryContract<K, V> : AbstractRepositoryContract<K, V>() {
    private lateinit var keyed: KeyedRepository<K, V>

    protected abstract fun newKeyedRepository(): KeyedRepository<K, V>

    final override fun newRepository(): KeyedRepository<K, V> = newKeyedRepository().also { keyed = it }

    @Test
    fun keyOf_names_the_key_a_value_is_stored_under() {
        val key = newKey()
        assertEquals(key, keyed.keyOf(newValue(key)))
    }

    @Test
    fun put_by_value_stores_it_under_its_own_key() {
        val key = newKey()
        val value = newValue(key)
        keyed.put(value)
        assertEquals(value, keyed.get(key))
    }

    @Test
    fun removeValue_removes_by_the_values_own_key() {
        val key = newKey()
        val value = newValue(key)
        keyed.put(key, value)
        keyed.removeValue(value)
        assertNull(keyed.get(key))
    }

    @Test
    fun removeValue_leaves_other_values_in_place() {
        val kept = newKey()
        val removed = newKey()
        keyed.put(kept, newValue(kept))
        keyed.put(removed, newValue(removed))
        keyed.removeValue(newValue(removed))
        assertEquals(listOf(newValue(kept)), keyed.all())
    }
}
