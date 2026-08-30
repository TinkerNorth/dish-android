// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

// Ranked best-first: satellite links are the fastest and most stable path, a Moonlight
// host's control stream is next, and the Bluetooth HID gamepad is the last resort.
enum class LinkTier { FASTEST, FAST, BASIC }

object LinkTiers {
    fun forKind(kind: ConnectionKind): LinkTier =
        when (kind) {
            ConnectionKind.SATELLITE -> LinkTier.FASTEST
            ConnectionKind.MOONLIGHT -> LinkTier.FAST
            ConnectionKind.BLUETOOTH -> LinkTier.BASIC
        }

    fun <T> byTier(kind: (T) -> ConnectionKind): Comparator<T> = compareBy { forKind(kind(it)) }
}
