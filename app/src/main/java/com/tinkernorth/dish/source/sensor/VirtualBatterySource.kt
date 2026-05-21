// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.source.store.BatteryStatusStore
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the dashboard's *virtual controller* battery indicator live.
 *
 * The virtual controller is the on-screen touch pad, and its "battery" is the
 * phone's own battery. [PhoneBatterySource] knows how to poll that, but the
 * feed the touch overlay runs is scoped to GamepadOverlayActivity's
 * resume/pause — it exists to push `MSG_BATTERY` to the wire while the virtual
 * pad is the active input device. That left the dashboard's per-slot indicator
 * with no producer of its own: on MainActivity nothing refreshed
 * [BatteryStatusStore]'s `VIRTUAL_SLOT_ID` entry, so it showed whatever the
 * overlay last left behind — frozen until the overlay was reopened.
 *
 * This source closes that gap. Like [PhysicalBatterySource] it is a
 * process-scoped `@Singleton` that observes
 * [androidx.lifecycle.ProcessLifecycleOwner] (registered in DishApplication):
 * while the app is foreground — on every screen, across the dashboard ↔
 * overlay hand-off — it runs a [PhoneBatterySource] poll loop that mirrors the
 * phone battery into [BatteryStatusStore] for
 * [com.tinkernorth.dish.ui.main.MainViewModel] to render.
 *
 * It is UI-only: the `emit` callback is a deliberate no-op. The virtual
 * controller's `MSG_BATTERY` wire send stays owned by the overlay, since a
 * virtual pad only produces input while the overlay is open.
 */
@Singleton
class VirtualBatterySource
    @Inject
    constructor(
        @ApplicationContext context: Context,
        statusStore: BatteryStatusStore,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        /**
         * The phone-battery poll loop, wired to write [BatteryStatusStore]
         * under [VIRTUAL_SLOT_ID]. No wire emit — see the class doc.
         */
        private val phoneBattery =
            PhoneBatterySource(
                context = context,
                slotId = VIRTUAL_SLOT_ID,
                statusStore = statusStore,
            )

        /** App entered the foreground — begin polling the phone battery. */
        override fun onStart(owner: LifecycleOwner) {
            // start() re-arms (it stop()s first), so a redundant onStart is safe.
            phoneBattery.start(scope) { _, _ -> }
        }

        /**
         * App went to the background — stop polling and release the
         * charging-state receiver. The last sample is intentionally left in
         * [BatteryStatusStore]: the phone always has a battery, so there is
         * nothing to invalidate, and keeping it means the dashboard shows the
         * last known level immediately on return — refreshed within the first
         * poll tick of the next [onStart].
         */
        override fun onStop(owner: LifecycleOwner) {
            phoneBattery.stop()
        }
    }
