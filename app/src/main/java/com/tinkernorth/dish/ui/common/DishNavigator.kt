// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.os.Bundle
import androidx.navigation.ActivityNavigator
import androidx.navigation.NavController
import com.tinkernorth.dish.R

// Activity destinations can't carry <action> children, so navigate by destination id, not action id.
class DishNavigator(
    private val activity: Activity,
) {
    private val controller: NavController by lazy {
        NavController(activity).apply {
            navigatorProvider.addNavigator(ActivityNavigator(activity))
            setGraph(R.navigation.nav_graph)
        }
    }

    fun toConnections() {
        controller.navigate(R.id.connectionsActivity)
    }

    fun toConnectionsForPairing(connectionId: String) {
        controller.navigate(
            R.id.connectionsActivity,
            Bundle().apply { putString("extra_pair_prompt_for_id", connectionId) },
        )
    }

    fun toSettings() {
        controller.navigate(R.id.settingsActivity)
    }

    fun toWelcome() {
        controller.navigate(R.id.welcomeActivity)
    }

    fun toSetupWizard() {
        controller.navigate(R.id.setupWizardActivity)
    }

    fun toHelp() {
        controller.navigate(R.id.helpActivity)
    }

    fun toTouchpad(
        connectionId: String,
        touchpadMode: String,
        slotId: String,
    ) {
        controller.navigate(
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
        controller.navigate(
            R.id.gamepadOverlayActivity,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putBoolean("extra_use_ps_layout", usePsLayout)
            },
        )
    }

    fun toNativeUnavailable() {
        controller.navigate(R.id.nativeUnavailableActivity)
    }
}
