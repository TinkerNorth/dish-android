// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.os.Bundle
import androidx.navigation.ActivityNavigator
import androidx.navigation.NavController
import com.tinkernorth.dish.R

/**
 * Typed navigation surface over the [androidx.navigation] graph at
 * `res/navigation/nav_graph.xml`. Constructed per-Activity (cheap — the
 * graph itself is static), then used to invoke navigation actions by name:
 *
 * ```
 * private val nav = DishNavigator(this)
 * nav.toConnections()
 * nav.toGamepad(connectionId = cid, usePsLayout = true)
 * ```
 *
 * Why a wrapper instead of calling [NavController.navigate] directly: the
 * action ids in the graph use raw strings for extras (e.g. `extra_slot_id`)
 * to match the receiving activities' `Intent.getStringExtra` calls. The
 * typed methods here give the call site Kotlin-level type-safety —
 * mistyping `connectionId` becomes a compiler error instead of a silent
 * "extra missing" at runtime.
 *
 * Activity destinations only: `ActivityNavigator` translates each
 * `navigate(actionId, args)` call into `startActivity(Intent)` with the
 * args applied as intent extras (argument names map 1:1 to extras). The
 * navigation library does NOT manage the back stack for Activity
 * destinations — the OS does. So calling [Activity.finish] from within an
 * activity destination still pops back to whatever started it.
 *
 * Intentional non-coverage: `startActivity(Intent.ACTION_VIEW, uri)` calls
 * (privacy-policy URL, Bluetooth settings, etc.) stay as direct
 * `startActivity` calls — they target external apps, not Dish destinations,
 * and don't belong in the in-app navigation graph.
 */
class DishNavigator(
    private val activity: Activity,
) {
    private val controller: NavController by lazy {
        NavController(activity).apply {
            navigatorProvider.addNavigator(ActivityNavigator(activity))
            setGraph(R.navigation.nav_graph)
        }
    }

    /** Navigate to the Connections screen ("Manage" button). */
    fun toConnections() {
        controller.navigate(R.id.action_main_to_connections)
    }

    /**
     * Navigate to the Connections screen with the PIN dialog auto-presented
     * for [connectionId]. Triggered by the "Pairing needed" notification
     * action from the dashboard.
     */
    fun toConnectionsForPairing(connectionId: String) {
        controller.navigate(
            R.id.action_main_to_connections_for_pairing,
            Bundle().apply { putString("extra_pair_prompt_for_id", connectionId) },
        )
    }

    /** Navigate to the Settings screen. */
    fun toSettings() {
        controller.navigate(R.id.action_main_to_settings)
    }

    /**
     * Open the touchpad overlay bound to [connectionId] in [touchpadMode]
     * ("ds4" or "mouse"), routing under [slotId]'s controllerIndex.
     */
    fun toTouchpad(
        connectionId: String,
        touchpadMode: String,
        slotId: String,
    ) {
        controller.navigate(
            R.id.action_main_to_touchpad,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putString("extra_touchpad_mode", touchpadMode)
                putString("extra_slot_id", slotId)
            },
        )
    }

    /**
     * Open the gamepad overlay bound to [connectionId]. [usePsLayout] picks
     * the DualSense face glyphs (true) vs. the Xbox layout (false).
     */
    fun toGamepad(
        connectionId: String,
        usePsLayout: Boolean,
    ) {
        controller.navigate(
            R.id.action_main_to_gamepad,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putBoolean("extra_use_ps_layout", usePsLayout)
            },
        )
    }

    /** Route to the themed fatal-fallback screen when the native library fails. */
    fun toNativeUnavailable() {
        controller.navigate(R.id.action_main_to_native_unavailable)
    }
}
