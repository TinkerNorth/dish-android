// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.main

import android.content.Context
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

/** The Context end of [StringLookup], shared by every screen that fills strings this way. */
internal class ContextStringLookup(
    private val context: Context,
) : StringLookup {
    override fun format(
        @StringRes res: Int,
        vararg args: Any,
    ): String = context.getString(res, *args)
}
