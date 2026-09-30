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
    private var keysIssued = 0

    protected abstract fun newRepository(): Repository<K, V>

    // The nth key a test asks for is keyFor(n): distinct by construction, and the same on every
    // run, so a failure replays exactly. JUnit makes a fresh instance per test, so n restarts at 0.
    protected abstract fun keyFor(index: Int): K

    protected fun newKey(): K {
        val index = keysIssued
        keysIssued += 1
        return keyFor(index)
    }

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

    // Every test above that holds two keys needs them to differ; this is the sample that says so.
    @Test
    fun keys_issued_within_one_test_are_distinct() {
        val keys = List(DISTINCT_KEY_SAMPLE) { newKey() }
        assertEquals(keys.size, keys.toSet().size)
    }

    private companion object {
        const val DISTINCT_KEY_SAMPLE = 2_000
    }
}
