// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.donate

import android.content.Context
import androidx.annotation.StringRes

/** Whether this device already supports the project, which keeps the donate pill away. */
fun interface SupporterCheck {
    fun isSupporter(context: Context): Boolean
}

/**
 * What a build asks of its users for tips. Each flavor declares its own `TIP_OFFER`, and every
 * donation surface in src/main renders it: an [Ask] in the flavor's own words, or nothing at all
 * for [None].
 */
sealed interface TipOffer {
    /** A channel that takes no tips. */
    data object None : TipOffer

    class Ask(
        @StringRes val pillAsk: Int,
        @StringRes val settingsBody: Int,
        val supporters: SupporterCheck,
    ) : TipOffer
}
