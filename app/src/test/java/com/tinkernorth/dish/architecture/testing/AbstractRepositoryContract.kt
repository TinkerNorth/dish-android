// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.testing

import com.tinkernorth.dish.architecture.interfaces.Repository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

abstract class AbstractRepositoryContract<K, V> {
    private lateinit var repo: Repository<K, V>

    protected abstract fun newRepository(): Repository<K, V>

    protected abstract fun newKey(): K

    protected abstract fun newValue(key: K): V

    @Before
    fun setUpRepository() {
        repo = newRepository()
    }

    @Test
    fun get_on_empty_returns_null() {
        assertNull(repo.get(newKey()))
    }

    @Test
    fun all_on_empty_returns_empty() {
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun get_after_put_returns_value() {
        val key = newKey()
        val value = newValue(key)
        repo.put(key, value)
        assertEquals(value, repo.get(key))
    }

    @Test
    fun put_with_same_key_replaces() {
        val key = newKey()
        repo.put(key, newValue(key))
        val replacement = newValue(key)
        repo.put(key, replacement)
        assertEquals(replacement, repo.get(key))
        assertEquals(1, repo.all().size)
    }

    @Test
    fun get_after_remove_returns_null() {
        val key = newKey()
        repo.put(key, newValue(key))
        repo.remove(key)
        assertNull(repo.get(key))
    }

    @Test
    fun all_contains_every_put_value() {
        val keys = List(3) { newKey() }
        keys.forEach { repo.put(it, newValue(it)) }
        assertEquals(3, repo.all().size)
        assertTrue(repo.all().toSet() == keys.map(::newValue).toSet())
    }

    @Test
    fun clear_empties_store() {
        repeat(3) {
            val k = newKey()
            repo.put(k, newValue(k))
        }
        repo.clear()
        assertTrue(repo.all().isEmpty())
    }

    @Test
    fun remove_absent_key_is_noop() {
        repo.remove(newKey())
        assertTrue(repo.all().isEmpty())
    }
}
