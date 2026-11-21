// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.core.controllerdb

// Section 4.2: record.expect checked once at attach, each mismatch a Diagnostics row and a bundle
// entry, in the form "endpoints.out: expected true, found false". A null expectation is unknown
// and not checked; a pad with no record has nothing to compare.
fun expectMismatches(
    record: ControllerRecord?,
    found: FoundEndpoints,
): List<String> {
    record ?: return emptyList()
    val endpoints = record.expect.endpoints
    return listOfNotNull(
        mismatch("endpoints.in", endpoints.hasIn, found.hasIn),
        mismatch("endpoints.out", endpoints.hasOut, found.hasOut),
    )
}

private fun mismatch(
    field: String,
    expected: Boolean?,
    found: Boolean,
): String? {
    expected ?: return null
    if (expected == found) return null
    return "$field: expected $expected, found $found"
}
