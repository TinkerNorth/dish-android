// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.composer

import com.tinkernorth.dish.repository.TouchpadModePreference
import com.tinkernorth.dish.repository.TouchpadModeRepository
import com.tinkernorth.dish.repository.TouchpadModeValue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the active touchpad mode for a paired satellite, given:
 *   - the user's saved preference (may be absent for a first-pair satellite),
 *   - whether the local device can capture touchpad input at all
 *     (`hasLocalTouchpadCapture`), and
 *   - the satellite's advertised support
 *     (`serverSupports` — typically `["off", "ds4", "mouse"]` on Windows /
 *     Linux receivers and `["off"]` on the inert macOS backend).
 *
 * The "default at pair time" rule the user asked for: **`off` if the
 * device doesn't support it**. dish-android only ships an on-screen virtual
 * touchpad surface (no physical trackpad capture), so
 * `hasLocalTouchpadCapture` is always true on this app — but the
 * abstraction is here so a future variant (e.g. an Android build without
 * the virtual surface, or a Wear OS spinoff) can opt out cleanly.
 *
 * After the default lands, the user can change the mode any time via the
 * connection card; the result is persisted by [TouchpadModeRepository] and
 * pushed to the server via `SatelliteHttpClient.setTouchpadMode`.
 */
@Singleton
class TouchpadModeComposer
    @Inject
    constructor(
        private val repo: TouchpadModeRepository,
    ) {
        /**
         * Resolve the effective mode for [satelliteId] given the server's
         * advertised capabilities. The lookup order is:
         *   1. The user's saved preference, *if* it is still supported by
         *      the server (server capabilities can change across firmware
         *      updates — fall back rather than push a now-rejected mode).
         *   2. The default at pair time:
         *      - `off` if [hasLocalTouchpadCapture] is false (device can't
         *        capture touchpad input at all), OR if the server only
         *        advertises `off` (e.g. macOS receiver).
         *      - Otherwise `ds4` if supported (preferred default — exposes
         *        a real DS4 touchpad surface to the host game).
         *      - Otherwise `mouse` if supported.
         *      - Otherwise `off` (fallback baseline).
         */
        fun resolve(
            satelliteId: String,
            serverSupports: Set<String>,
            hasLocalTouchpadCapture: Boolean,
        ): String {
            val saved = repo.get(satelliteId)?.mode
            if (saved != null && saved in serverSupports) return saved
            // Default at pair time (or after the saved value is no longer
            // supported by the receiver).
            if (!hasLocalTouchpadCapture) return TouchpadModeValue.OFF
            if (TouchpadModeValue.DS4 in serverSupports) return TouchpadModeValue.DS4
            if (TouchpadModeValue.MOUSE in serverSupports) return TouchpadModeValue.MOUSE
            return TouchpadModeValue.OFF
        }

        /**
         * Persist [mode] for [satelliteId] locally. The caller is also
         * expected to push to the server via `SatelliteHttpClient.setTouchpadMode`
         * — this composer only owns the local mirror, so a server-side
         * failure can still leave the local pick visible until the next
         * connect (the live connection's mode is server-owned).
         */
        fun persist(satelliteId: String, mode: String) {
            require(TouchpadModeValue.isValid(mode)) {
                "Invalid touchpad mode '$mode' — must be one of ${TouchpadModeValue.ALL}"
            }
            repo.put(TouchpadModePreference(satelliteId, mode))
        }
    }
