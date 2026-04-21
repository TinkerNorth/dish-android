package com.tinkernorth.dish.ui.main

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.network.ServerConnectionManager
import com.tinkernorth.dish.databinding.ActivityGamepadOverlayBinding
import com.tinkernorth.dish.ui.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.ui.common.GamepadTouchView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen landscape activity that hosts the on-screen touch gamepad.
 *
 * State (dest type, PS-vs-Xbox layout) is passed via Intent extras so this
 * activity doesn't depend on an activity-scoped [MainViewModel]. Reports are
 * forwarded through the singleton [BluetoothGamepadRegistry] (BT destination)
 * or [ServerConnectionManager] (WiFi destination), both of which survive the
 * MainActivity pause.
 */
@AndroidEntryPoint
class GamepadOverlayActivity : AppCompatActivity(), GamepadTouchView.Listener {

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry
    @Inject lateinit var serverManager: ServerConnectionManager

    private lateinit var binding: ActivityGamepadOverlayBinding

    private var destType: SlotDestType = SlotDestType.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGamepadOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

        destType = intent.getStringExtra(EXTRA_DEST_TYPE)
            ?.let { runCatching { SlotDestType.valueOf(it) }.getOrNull() }
            ?: SlotDestType.NONE

        binding.gamepadTouchView.listener = this
        binding.gamepadTouchView.usePlayStation = intent.getBooleanExtra(EXTRA_USE_PS_LAYOUT, false)
        binding.dotOverlay.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.colorMuted))
        }
        binding.btnExitGamepad.setOnClickListener { finish() }

        observeConnectionState()
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                btRegistry.states.collect { refreshStatus() }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverManager.isConnected.collect { refreshStatus() }
            }
        }
    }

    private fun refreshStatus() {
        val bt = btRegistry.state(VIRTUAL_SLOT_ID)
        val connected = when (destType) {
            SlotDestType.BLUETOOTH -> bt.connected
            SlotDestType.WIFI -> serverManager.isConnected.value
            SlotDestType.NONE -> false
        }
        val label = when {
            connected && destType == SlotDestType.BLUETOOTH -> bt.connectedName ?: "Streaming"
            connected && destType == SlotDestType.WIFI -> serverManager.connectedServer.value?.name ?: "Streaming"
            destType == SlotDestType.NONE -> "No destination selected"
            else -> "Not connected"
        }
        binding.tvOverlayStatus.text = label
        (binding.dotOverlay.background as? GradientDrawable)?.setColor(
            getColor(if (connected) R.color.colorSuccess else R.color.colorMuted),
        )
    }

    override fun onGamepadStateChanged(state: GamepadTouchView.GamepadState) {
        when (destType) {
            SlotDestType.BLUETOOTH -> {
                if (!btRegistry.isConnected(VIRTUAL_SLOT_ID)) return
                val report = btRegistry.buildReport(
                    VIRTUAL_SLOT_ID,
                    state.buttons, state.hatSwitch,
                    state.leftX, state.leftY, state.rightX, state.rightY,
                    state.leftTrigger, state.rightTrigger,
                ) ?: return
                btRegistry.sendReport(VIRTUAL_SLOT_ID, report)
            }
            SlotDestType.WIFI -> {
                if (!serverManager.isConnected.value) return
                serverManager.controllerRepo.sendReport(
                    0, state.buttons,
                    state.leftTrigger, state.rightTrigger,
                    state.leftX.toInt(), state.leftY.toInt(),
                    state.rightX.toInt(), state.rightY.toInt(),
                )
            }
            SlotDestType.NONE -> {}
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    companion object {
        const val EXTRA_DEST_TYPE = "extra_dest_type"
        const val EXTRA_USE_PS_LAYOUT = "extra_use_ps_layout"
    }
}
