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
import com.tinkernorth.dish.ui.connections.ConnectionsActivity
import com.tinkernorth.dish.ui.diagnostics.BindingInspectorViewModel
import com.tinkernorth.dish.ui.diagnostics.HostInspectorViewModel
import com.tinkernorth.dish.ui.diagnostics.InputInspectorActivity
import com.tinkernorth.dish.ui.diagnostics.InputInspectorViewModel
import com.tinkernorth.dish.ui.main.ConfigureBindingsActivity
import com.tinkernorth.dish.ui.main.GamepadOverlayActivity
import com.tinkernorth.dish.ui.main.MainActivity
import com.tinkernorth.dish.ui.main.MouseOverlayActivity
import com.tinkernorth.dish.ui.main.TouchpadOverlayActivity
import com.tinkernorth.dish.ui.setup.EXTRA_CONNECTION_ID
import com.tinkernorth.dish.ui.setup.EXTRA_INPUT_TYPE
import com.tinkernorth.dish.ui.setup.EXTRA_SLOT_ID
import com.tinkernorth.dish.ui.setup.SetupInputActivity

// Activity destinations can't carry <action> children, so navigate by destination id, not action id.
// Drives ActivityNavigator directly instead of NavController: setGraph() auto-navigates to the start
// destination whenever its back queue is empty, which stacked a spurious dashboard instance under
// every screen opened from a non-dashboard activity.
class DishNavigator(
    private val activity: Activity,
) {
    private val navigator by lazy { ActivityNavigator(activity) }

    private val graph: NavGraph by lazy(::inflateGraph)

    private fun inflateGraph(): NavGraph {
        val provider =
            NavigatorProvider().apply {
                addNavigator(NavGraphNavigator(this))
                addNavigator(navigator)
            }
        return NavInflater(activity, provider).inflate(R.navigation.nav_graph)
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
            Bundle().apply { putString(ConnectionsActivity.EXTRA_PAIR_PROMPT_FOR_ID, connectionId) },
        )
    }

    fun toSettings() {
        go(R.id.settingsActivity)
    }

    fun toDonate() {
        go(R.id.donateActivity)
    }

    fun toConfigureBindings(slotId: String) {
        go(
            R.id.configureBindingsActivity,
            Bundle().apply { putString(ConfigureBindingsActivity.EXTRA_SLOT_ID, slotId) },
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
                putString(EXTRA_INPUT_TYPE, inputType)
                putString(EXTRA_SLOT_ID, slotId)
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
                putString(EXTRA_INPUT_TYPE, inputType)
                putString(EXTRA_SLOT_ID, slotId)
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
                putString(EXTRA_SLOT_ID, slotId)
                putString(EXTRA_CONNECTION_ID, connectionId)
            },
        )
    }

    fun toHelp() {
        go(R.id.helpActivity)
    }

    fun toDiagnostics() {
        go(R.id.diagnosticsActivity)
    }

    fun toLicenses() {
        go(R.id.licensesActivity)
    }

    fun toInputInspector(
        slotId: String,
        deviceName: String,
    ) {
        go(
            R.id.inputInspectorActivity,
            Bundle().apply {
                putString(InputInspectorViewModel.EXTRA_SLOT_ID, slotId)
                putString(InputInspectorActivity.EXTRA_DEVICE_NAME, deviceName)
            },
        )
    }

    fun toHostInspector(
        connectionId: String,
        label: String,
    ) {
        go(
            R.id.hostInspectorActivity,
            Bundle().apply {
                putString(HostInspectorViewModel.EXTRA_CONNECTION_ID, connectionId)
                putString(HostInspectorViewModel.EXTRA_LABEL, label)
            },
        )
    }

    fun toBindingInspector(
        slotId: String,
        label: String,
    ) {
        go(
            R.id.bindingInspectorActivity,
            Bundle().apply {
                putString(BindingInspectorViewModel.EXTRA_SLOT_ID, slotId)
                putString(BindingInspectorViewModel.EXTRA_LABEL, label)
            },
        )
    }

    fun finishSetupToDashboard() {
        reset(StackReset.SETUP_TO_DASHBOARD)
        activity.finish()
    }

    fun rewindSetupToStart() {
        reset(StackReset.SETUP_TO_START)
    }

    private fun reset(reset: StackReset) {
        activity.startActivity(Intent(activity, reset.target).addFlags(reset.flags))
    }

    fun toTouchpad(
        connectionId: String,
        slotId: String,
    ) {
        go(
            R.id.touchpadOverlayActivity,
            Bundle().apply {
                putString(TouchpadOverlayActivity.EXTRA_CONNECTION_ID, connectionId)
                putString(TouchpadOverlayActivity.EXTRA_SLOT_ID, slotId)
            },
        )
    }

    fun toMouse(
        connectionId: String,
        slotId: String,
    ) {
        go(
            R.id.mouseOverlayActivity,
            Bundle().apply {
                putString(MouseOverlayActivity.EXTRA_CONNECTION_ID, connectionId)
                putString(MouseOverlayActivity.EXTRA_SLOT_ID, slotId)
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
                putString(GamepadOverlayActivity.EXTRA_CONNECTION_ID, connectionId)
                putString(GamepadOverlayActivity.EXTRA_GAMEPAD_SKIN, skin.name)
            },
        )
    }

    fun toNativeUnavailable() {
        go(R.id.nativeUnavailableActivity)
    }
}

// A launch that resets the back stack, which a nav-graph destination cannot express.
internal enum class StackReset(
    val target: Class<out Activity>,
    val flags: Int,
) {
    // The setup task is over, so the dashboard starts a fresh task.
    SETUP_TO_DASHBOARD(MainActivity::class.java, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),

    // SetupInputActivity sits at the root of the setup task, so CLEAR_TOP rewinds to it and
    // drops every screen stacked above.
    SETUP_TO_START(SetupInputActivity::class.java, Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
}
