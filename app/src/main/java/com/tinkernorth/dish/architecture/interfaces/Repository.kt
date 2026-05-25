// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.architecture.interfaces

interface Repository<K, V> {
    fun get(key: K): V?

    fun all(): List<V>

    fun put(
        key: K,
        value: V,
    )

    fun remove(key: K)

    fun clear()
}

interface KeyedRepository<K, V> : Repository<K, V> {
    fun keyOf(value: V): K

    fun put(value: V) = put(keyOf(value), value)

    // Distinct name avoids JVM-erasure clash with Repository.remove(K).
    fun removeValue(value: V) = remove(keyOf(value))
}
