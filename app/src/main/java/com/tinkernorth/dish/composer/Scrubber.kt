// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

private const val REMOVED = "[removed]"
private const val ADDRESS = "[address]"
private const val SHORTEST_SCRUBBED = 3
private val MAC = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")
private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
private val IPV6 = Regex("\\b(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}\\b")
private const val IPV6_LEAST_COLONS = 3

// Removes every forbidden value, then anything shaped like a MAC or an IP address.
internal class Scrubber(
    forbidden: Set<String>,
) {
    private val values = forbidden.filter { it.length >= SHORTEST_SCRUBBED }.sortedByDescending { it.length }

    operator fun invoke(text: String): String {
        var out = forbiddenOnly(text)
        out = MAC.replace(out, ADDRESS)
        out = IPV4.replace(out, ADDRESS)
        return IPV6.replace(out, ::keepIfNotAnAddress)
    }

    fun forbiddenOnly(text: String): String {
        var out = text
        for (value in values) out = out.replace(value, REMOVED, ignoreCase = true)
        return out
    }

    // A vid:pid pair has one colon and a clock time two; an IPv6 address has three or more, or a
    // "::" run of zeros.
    private fun keepIfNotAnAddress(match: MatchResult): String {
        val colons = match.value.count { it == ':' }
        val isAnAddress = colons >= IPV6_LEAST_COLONS || match.value.contains("::")
        return if (isAnAddress) ADDRESS else match.value
    }
}
