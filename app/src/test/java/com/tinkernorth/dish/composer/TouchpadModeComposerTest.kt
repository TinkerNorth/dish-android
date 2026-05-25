// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertEquals
import org.junit.Test

class TouchpadModeComposerTest {
    @Test
    fun `saved preference is used when still supported by the server`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = TouchpadModeValue.MOUSE,
                serverSupports = setOf("off", "ds4", "mouse"),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.MOUSE, mode)
    }

    @Test
    fun `saved preference is dropped when the server no longer advertises it`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = TouchpadModeValue.MOUSE,
                serverSupports = setOf("off"),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.OFF, mode)
    }

    @Test
    fun `default at pair time is off when the device cannot capture touchpad`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = setOf("off", "ds4", "mouse"),
                hasLocalTouchpadCapture = false,
            )
        assertEquals(TouchpadModeValue.OFF, mode)
    }

    @Test
    fun `default at pair time prefers ds4 when both sides support it`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = setOf("off", "ds4", "mouse"),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.DS4, mode)
    }

    @Test
    fun `default falls back to mouse when only off and mouse are supported`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = setOf("off", "mouse"),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.MOUSE, mode)
    }

    @Test
    fun `default falls back to off when only off is supported`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = setOf("off"),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.OFF, mode)
    }

    @Test
    fun `default falls back to off when the server advertises an empty set`() {
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = emptySet(),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.OFF, mode)
    }
}
