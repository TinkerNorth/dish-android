// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.TouchpadModeValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behaviour tests for [TouchpadModeComposer.resolve] — the "what mode is
 * active right now?" decision tree. The resolver's job is to be:
 *   - honest about what the receiver can do (never pick a mode the server
 *     can't honour, even if the user previously saved it),
 *   - safe at pair time (default `off` when capture is absent),
 *   - quietly helpful when the device + server can both do better
 *     (default `ds4` if available, then `mouse`, then `off`).
 *
 * Every branch has its own test so a regression in any single rule is
 * caught at the assertion that names it.
 *
 * **Pure-function tests:** the resolver is an `object` with a single pure
 * `resolve` function. No mocks, no repo, no flows — the saved preference
 * is passed in directly. Persistence is the caller's responsibility
 * (`TouchpadModeStore.setMode`) and is tested separately in
 * `TouchpadModeStoreTest`.
 */
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
        // A satellite OS update (or pairing migrating to a Mac receiver)
        // can shrink the supported-modes set. The resolver must NOT push
        // a now-rejected mode — the next API call would 409 and the user
        // would see the server fall back anyway. Pre-empt by re-defaulting.
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
        // The user's explicit rule: "should be off if the device doesn't
        // support it" — i.e. the LOCAL device. Even a fully-capable
        // receiver gets `off` if the dish can't capture.
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
        // `ds4` is preferred over `mouse` because it surfaces a real DS4
        // touchpad to the host game — which most users opening the touchpad
        // overlay will want by default. Mouse mode is a useful secondary
        // for a "phone as trackpad" workflow but it's not the headline.
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
        // Hypothetical future receiver that ships mouse synthesis but not
        // virtual DS4 (e.g. a Linux distro without uinput touchpad). The
        // resolver must reach for the next-best option, not silently OFF.
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
        // Inert backend (macOS receiver) — the resolver must NOT propose a
        // mode the server can't honour, even if the dish itself can capture.
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
        // Defensive: a malformed capabilities response should still resolve
        // to a defined mode — `off`, the universally-supported baseline.
        val mode =
            TouchpadModeComposer.resolve(
                savedMode = null,
                serverSupports = emptySet(),
                hasLocalTouchpadCapture = true,
            )
        assertEquals(TouchpadModeValue.OFF, mode)
    }
}
