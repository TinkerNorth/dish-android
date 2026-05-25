// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.TouchpadModeValue

object TouchpadModeComposer {
    fun resolve(
        savedMode: String?,
        serverSupports: Set<String>,
        hasLocalTouchpadCapture: Boolean,
    ): String {
        if (savedMode != null && savedMode in serverSupports) return savedMode
        if (!hasLocalTouchpadCapture) return TouchpadModeValue.OFF
        if (TouchpadModeValue.DS4 in serverSupports) return TouchpadModeValue.DS4
        if (TouchpadModeValue.MOUSE in serverSupports) return TouchpadModeValue.MOUSE
        return TouchpadModeValue.OFF
    }
}
