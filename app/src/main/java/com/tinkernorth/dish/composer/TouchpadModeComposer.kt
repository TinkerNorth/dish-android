// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.TouchpadModeValue

/**
 * Stateless resolver that picks the active touchpad mode for a paired
 * satellite, given:
 *   - the user's saved preference for that satellite (may be null if the
 *     pair-time default still applies — caller looks this up via
 *     [com.tinkernorth.dish.source.store.TouchpadModeStore.modeFor]),
 *   - whether the local device can capture touchpad input at all
 *     ([hasLocalTouchpadCapture]), and
 *   - the satellite's advertised support ([serverSupports] — typically
 *     `["off", "ds4", "mouse"]` on Windows / Linux receivers and `["off"]`
 *     on the inert macOS backend).
 *
 * **Not a [com.tinkernorth.dish.architecture.abstracts.AbstractComposer]:**
 * AbstractComposer is the right shape for *reactive derivations from
 * upstream flows*. This resolver is a tiny pure function on its arguments —
 * the caller is the one doing the reactive combine (see
 * `MainViewModel.init`, where `hub.connections × touchpadModeStore.state`
 * is mapped through this resolver). Wrapping a one-line `when` in a full
 * AbstractComposer would just be ceremony.
 *
 * **Lives in `composer/` anyway** because it's semantically the touchpad-mode
 * *policy* — adjacent to [MotionCapabilityComposer]'s motion-policy role —
 * and the dashboard combine that calls it is the only call site. An `object`
 * (no constructor, no DI) keeps the "I'm pure" signal loud.
 *
 * Persistence is **not** this object's job: the user's choice is written
 * through [com.tinkernorth.dish.source.store.TouchpadModeStore.setMode],
 * which owns the durable repo + reactive state. Folding persistence into
 * the resolver would create a hidden side effect on what looks like a
 * lookup function — the bug class that put the previous version of this
 * file on the wrong side of the architecture audit.
 */
object TouchpadModeComposer {
    /**
     * Resolve the effective mode for a satellite given the user's saved
     * pick (or null if never picked) and the satellite's advertised
     * capabilities. Lookup order:
     *   1. The user's saved preference, *if* it is still supported by the
     *      server (server capabilities can change across firmware updates
     *      — fall back rather than push a now-rejected mode).
     *   2. The default at pair time:
     *      - `off` if [hasLocalTouchpadCapture] is false (device can't
     *        capture touchpad input at all), OR if the server only
     *        advertises `off` (e.g. macOS receiver).
     *      - Otherwise `ds4` if supported (preferred default — exposes a
     *        real DS4 touchpad surface to the host game).
     *      - Otherwise `mouse` if supported.
     *      - Otherwise `off` (fallback baseline).
     */
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
