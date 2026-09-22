// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import androidx.annotation.StringRes

/**
 * Resolves a string resource with its format arguments. The view binds it to a Context; a
 * test binds it to a recorder, which is what keeps the pure state projections free of any
 * View or Context.
 */
fun interface StringLookup {
    fun format(
        @StringRes res: Int,
        vararg args: Any,
    ): String
}
