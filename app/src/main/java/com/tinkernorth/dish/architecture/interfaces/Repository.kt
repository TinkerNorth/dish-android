// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.architecture.interfaces

/**
 * Pattern: **synchronous CRUD over a single durable backing store**. Lives in the
 * `interfaces/` package because it is an interface, not an abstract class — no
 * inheritable state, only a contract.
 *
 * Use this for any class whose job is to read and write durable records — typically
 * backed by SharedPreferences, a file, a DataStore, or a database — and whose API
 * is shaped like `get / all / put / remove / clear`. The contract intentionally has
 * no flows, no lifecycle, no events, and no scope. Repositories are dumb storage.
 *
 * If you want reactive reads, wrap the repository in an
 * [com.tinkernorth.dish.architecture.abstracts.AbstractStateSource] that
 * observes the underlying store and republishes; do **not** fold reactivity into
 * the repository.
 *
 * **Type parameters:** [K] is the key (typically `String`); [V] is the stored value
 * (typically a `@Serializable` data class).
 *
 * **Threading:** implementations must be safe for concurrent calls from any thread.
 * The canonical implementation pattern is a private `writeLock: Any` synchronizing
 * read-modify-write of the list under a single SharedPreferences key.
 *
 * **Testing:** every concrete `Repository` should extend `AbstractRepositoryContract`
 * (in `com.tinkernorth.dish.architecture.testing`) to inherit the standard
 * property checks.
 */
interface Repository<K, V> {
    /** Returns the value for [key], or `null` if absent. */
    fun get(key: K): V?

    /** Returns every stored value. Order is implementation-defined. */
    fun all(): List<V>

    /** Inserts or replaces the value at [key]. */
    fun put(
        key: K,
        value: V,
    )

    /** Removes [key]. No-op if absent. */
    fun remove(key: K)

    /** Removes every stored value. */
    fun clear()
}

/**
 * Variant for repositories whose values carry their own key (id), so the caller does
 * not need to supply it separately. Most real repositories in this codebase look
 * like this: the satellite id is computed from `ip:udpPort`, the BT id is derived
 * from the MAC.
 */
interface KeyedRepository<K, V> : Repository<K, V> {
    /** Extracts the key from [value]. Must be stable for the lifetime of the value. */
    fun keyOf(value: V): K

    /** Inserts or replaces, computing the key from the value. */
    fun put(value: V) = put(keyOf(value), value)

    /**
     * Removes by value. Named distinctly from [remove] to avoid a JVM-erasure
     * clash with the [Repository.remove] overload.
     */
    fun removeValue(value: V) = remove(keyOf(value))
}
