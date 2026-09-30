// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.store

// The keyed-map reducers the stores hand to setState. A write that changes nothing hands back the
// same map, so the store publishes nothing.

internal fun <K, V> withEntryIfAbsent(
    entries: Map<K, V>,
    key: K,
    value: V,
): Map<K, V> {
    val isPresent = key in entries
    if (isPresent) return entries
    return entries + (key to value)
}

internal fun <K, V> withoutEntry(
    entries: Map<K, V>,
    key: K,
): Map<K, V> {
    val isAbsent = key !in entries
    if (isAbsent) return entries
    return entries - key
}
