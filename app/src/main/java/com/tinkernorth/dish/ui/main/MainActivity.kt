package com.tinkernorth.dish.ui.main

import android.content.Context
import android.content.DialogInterface
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tinkernorth.dish.R
import com.tinkernorth.dish.data.model.DiscoveredServer
import com.tinkernorth.dish.data.repository.ControllerRepository
import com.tinkernorth.dish.data.repository.DiscoveryRepository
import com.tinkernorth.dish.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), InputManager.InputDeviceListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val controllerAdapter = ControllerAdapter()
    private lateinit var inputManager: InputManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.rvControllers.adapter = controllerAdapter

        // Trigger a scan when the app opens if not connected.
        viewModel.onScanClicked()

        binding.btnDisconnectAll.setOnClickListener {
            viewModel.onDisconnectClicked()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    handleEvent(event)
                }
            }
        }
    }

    private fun updateUI(state: MainUiState) {
        binding.tvServerStatus.text = if (state.isConnected) {
            "CONNECTED TO ${state.connectedServerName?.uppercase()}"
        } else if (state.isScanning) {
            "SCANNING..."
        } else {
            "NOT CONNECTED"
        }

        binding.dotServer.setBackgroundColor(if (state.isConnected) 0xFF00FF00.toInt() else 0xFFFF0000.toInt())

        binding.llEmptyState.visibility =
            if (state.controllers.isEmpty()) View.VISIBLE else View.GONE
        binding.btnDisconnectAll.visibility = if (state.isConnected) View.VISIBLE else View.GONE

        controllerAdapter.submitList(state.controllers)
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowToast -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is MainEvent.ShowPairingDialog -> {
                showPairingDialog(event.server)
            }
        }
    }

    private fun showPairingDialog(server: DiscoveredServer) {
        val view = layoutInflater.inflate(R.layout.dialog_pairing, null)
        val etPin = view.findViewById<EditText>(R.id.et_pin)

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Connect") { _: DialogInterface, _: Int ->
                val pin = etPin.text.toString().ifEmpty { "0000" }
                viewModel.onPairingPinEntered(server, pin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Input Device Handling
    override fun onResume() {
        super.onResume()
        inputManager.registerInputDeviceListener(this, null)
        syncControllers()
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(this)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        val device = InputDevice.getDevice(deviceId) ?: return
        if (isGamepad(device)) {
            viewModel.onControllerConnected(deviceId, device.name)
        }
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        viewModel.onControllerDisconnected(deviceId)
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        // Potentially handle changes if necessary
    }

    private fun syncControllers() {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (isGamepad(device)) {
                viewModel.onControllerConnected(id, device.name)
            }
        }
    }

    private fun isGamepad(device: InputDevice): Boolean {
        val sources = device.sources
        return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
}
