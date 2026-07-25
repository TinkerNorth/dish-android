// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.ui.common

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.navigation.ActivityNavigator
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphNavigator
import androidx.navigation.NavInflater
import androidx.navigation.NavigatorProvider
import com.tinkernorth.dish.R
import com.tinkernorth.dish.ui.main.MainActivity
import com.tinkernorth.dish.ui.setup.SetupFlow

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

    fun toSetupInput() {
        go(R.id.setupInputActivity)
    }

    fun toSetupUsb() {
        go(R.id.setupUsbActivity)
    }

    fun toSetupBluetoothController() {
        go(R.id.setupBluetoothControllerActivity)
    }

    fun toSetupConnection(
        inputType: String,
        slotId: String,
    ) {
        go(
            R.id.setupConnectionActivity,
            Bundle().apply {
                putString(SetupFlow.EXTRA_INPUT_TYPE, inputType)
                putString(SetupFlow.EXTRA_SLOT_ID, slotId)
            },
        )
    }

    fun toSetupBluetoothHost(
        inputType: String,
        slotId: String,
    ) {
        go(
            R.id.setupBluetoothHostActivity,
            Bundle().apply {
                putString(SetupFlow.EXTRA_INPUT_TYPE, inputType)
                putString(SetupFlow.EXTRA_SLOT_ID, slotId)
            },
        )
    }

    fun toSetupConfigure(
        slotId: String,
        connectionId: String,
    ) {
        go(
            R.id.setupConfigureActivity,
            Bundle().apply {
                putString(SetupFlow.EXTRA_SLOT_ID, slotId)
                putString(SetupFlow.EXTRA_CONNECTION_ID, connectionId)
            },
        )
    }

    fun toHelp() {
        go(R.id.helpActivity)
    }

    fun toDonate() {
        go(R.id.donateActivity)
    }

    fun toDiagnostics() {
        go(R.id.diagnosticsActivity)
    }

    fun toLicenses() {
        go(R.id.licensesActivity)
    }

    fun toInputInspector(
        deviceId: Int,
        deviceName: String,
    ) {
        go(
            R.id.inputInspectorActivity,
            Bundle().apply {
                putInt("extra_device_id", deviceId)
                putString("extra_device_name", deviceName)
            },
        )
    }

    // Setup flow handoff to the dashboard: the setup task is over, so the back stack resets.
    fun finishSetupToDashboard() {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        activity.finish()
    }

    fun toTouchpad(
        connectionId: String,
        slotId: String,
    ) {
        go(
            R.id.touchpadOverlayActivity,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putString("extra_slot_id", slotId)
            },
        )
    }

    fun toGamepad(
        connectionId: String,
        skin: GamepadSkin,
    ) {
        go(
            R.id.gamepadOverlayActivity,
            Bundle().apply {
                putString("extra_connection_id", connectionId)
                putString("extra_gamepad_skin", skin.name)
            },
        )
    }

    fun toNativeUnavailable() {
        go(R.id.nativeUnavailableActivity)
    }
}
