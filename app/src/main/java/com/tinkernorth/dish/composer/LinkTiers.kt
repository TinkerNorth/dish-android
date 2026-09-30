// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

fun linkTierFor(kind: ConnectionKind): LinkTier =
    when (kind) {
        ConnectionKind.SATELLITE -> LinkTier.FASTEST
        ConnectionKind.MOONLIGHT -> LinkTier.FAST
        ConnectionKind.BLUETOOTH -> LinkTier.BASIC
    }

fun <T> comparatorByLinkTier(kind: (T) -> ConnectionKind): Comparator<T> = compareBy { linkTierFor(kind(it)) }
