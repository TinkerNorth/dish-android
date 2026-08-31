// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton

class HoldButtonView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : MaterialButton(context, attrs) {
        var onHeldChanged: ((Boolean) -> Unit)? = null

        private var held = false

        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            if (held != pressed) {
                held = pressed
                onHeldChanged?.invoke(pressed)
            }
        }
    }
