// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.os.Bundle
import androidx.navigation.ActivityNavigator
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavInflater
import androidx.navigation.NavigatorProvider
import com.tinkernorth.dish.R

// Activity destinations can't carry <action> children, so navigate by destination id, not action id.
// Drives ActivityNavigator directly instead of NavController: setGraph() auto-navigates to the start
// destination whenever its back queue is empty, which stacked a spurious dashboard instance under
// every screen opened from a non-dashboard activity.
class DishNavigator(
    private val activity: Activity,
) {
    private val navigator by lazy { ActivityNavigator(activity) }

    private val graph: NavGraph by lazy {
        val provider =
            NavigatorProvider().apply {
                addNavigator(NavGraphNavigator(this))
                addNavigator(navigator)
            }
        NavInflater(activity, provider).inflate(R.navigation.nav_graph)
    }

    private fun go(
        destinationId: Int,
        args: Bundle? = null,
    ) {
        navigator.navigate(graph.findNode(destinationId) as ActivityNavigator.Destination, args, null, null)
    }

    fun toConnections() {
        go(R.id.connectionsActivity)
    }

    fun toConnectionsForPairing(connectionId: String) {
        go(
            R.id.connectionsActivity,
            Bundle().apply { putString("extra_pair_prompt_for_id", connectionId) },
        )
    }

    fun toSettings() {
        go(R.id.settingsActivity)
    }

    fun toConfigureBindings(slotId: String) {
        go(
            R.id.configureBindingsActivity,
            Bundle().apply { putString("extra_slot_id", slotId) },
        )
    }

    fun toWelcome() {
        go(R.id.welcomeActivity)
    }

    fun toSetupWizard() {
        go(R.id.setupWizardActivity)
    }

    fun toHelp() {
        go(R.id.helpActivity)
    }

    fun toDonate() {
        go(R.id.donateActivity)
    }

    fun toTouchpad(
        connectionId: String,
        touchpadMode: String,
        slotId: String,
    ) {
        go(
            R.id.touchpadOverlayActivity,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putString("extra_touchpad_mode", touchpadMode)
                putString("extra_slot_id", slotId)
            },
        )
    }

    fun toGamepad(
        connectionId: String,
        usePsLayout: Boolean,
    ) {
        go(
            R.id.gamepadOverlayActivity,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putBoolean("extra_use_ps_layout", usePsLayout)
            },
        )
    }

    fun toNativeUnavailable() {
        go(R.id.nativeUnavailableActivity)
    }
}
