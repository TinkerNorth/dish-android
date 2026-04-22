package com.tinkernorth.dish.ui.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Android implementation of [HidProxyClient] backed by
 * [android.bluetooth.BluetoothHidDevice]. Each instance owns exactly one
 * profile-proxy binding; [unregisterAndRelease] tears it down idempotently
 * and clears every framework handle so the session can allocate a fresh
 * [AndroidHidProxyClient] for the next connect attempt.
 *
 * Requires API 28+. Callers must hold BLUETOOTH_CONNECT on API 31+.
 */
@RequiresApi(Build.VERSION_CODES.P)
@SuppressLint("MissingPermission")
class AndroidHidProxyClient(
    private val context: Context,
) : HidProxyClient {

    private var events: HidProxyClient.Events? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var currentProfile: BluetoothGamepad.GamepadProfile? = null

    override fun isAdapterEnabled(): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    override fun acquire(events: HidProxyClient.Events) {
        this.events = events
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            events.onError("Bluetooth is not available or not enabled")
            return
        }
        adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun registerApp(profile: BluetoothGamepad.GamepadProfile) {
        val hid = hidDevice ?: return
        currentProfile = profile
        val sdp = BluetoothHidDeviceAppSdpSettings(
            profile.sdpName,
            profile.sdpDescription,
            profile.sdpProvider,
            BluetoothHidDevice.SUBCLASS2_GAMEPAD,
            buildHidDescriptor(),
        )
        val qos = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
            TOKEN_RATE, BluetoothHidDeviceAppQosSettings.MAX, BluetoothHidDeviceAppQosSettings.MAX,
            BT_SLOT_US, JITTER_US,
        )
        hid.registerApp(sdp, null, qos, { it.run() }, hidCallback)
    }

    override fun connectToHost(mac: String) {
        val hid = hidDevice ?: return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return
        runCatching { hid.connect(adapter.getRemoteDevice(mac)) }
            .onFailure { events?.onError("Invalid host address: $mac") }
    }

    override fun disconnectCurrentHost() {
        val hid = hidDevice ?: return
        connectedDevice?.let { runCatching { hid.disconnect(it) } }
    }

    override fun findOsConnectedHost(mac: String): String? {
        val hid = hidDevice ?: return null
        return runCatching {
            hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                .firstOrNull { it.address.equals(mac, ignoreCase = true) }
                ?.let { it.name ?: it.address }
        }.getOrNull()
    }

    override fun sendReport(report: ByteArray): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        // Strip the report-id byte: BluetoothHidDevice.sendReport takes it
        // separately from the payload (see buildHidReport wire layout).
        return hid.sendReport(device, REPORT_ID, report.sliceArray(1 until report.size))
    }

    override fun unregisterAndRelease() {
        val hid = hidDevice
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (hid != null) {
            connectedDevice?.let { runCatching { hid.disconnect(it) } }
            runCatching { hid.unregisterApp() }
            runCatching { manager?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        }
        hidDevice = null
        connectedDevice = null
        currentProfile = null
        events = null
    }

    // ── Framework callbacks ──────────────────────────────────────────────

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            events?.onAcquired()
        }
        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            connectedDevice = null
            events?.onReleased()
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            if (registered) events?.onAppRegistered() else events?.onAppUnregistered()
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    events?.onHostConnected(device.address, device.name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedDevice?.address == device.address) connectedDevice = null
                    events?.onHostDisconnected(device.address)
                }
            }
        }
    }

    private companion object {
        const val TOKEN_RATE = 3200
        const val BT_SLOT_US = 625
        const val JITTER_US = 1250
    }
}
