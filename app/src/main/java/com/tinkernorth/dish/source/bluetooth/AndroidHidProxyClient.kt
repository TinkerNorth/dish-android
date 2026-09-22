// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.core.input.BluetoothGamepad
import com.tinkernorth.dish.core.input.REPORT_ID
import com.tinkernorth.dish.core.input.REPORT_SIZE
import com.tinkernorth.dish.core.input.buildHidDescriptor

@RequiresApi(Build.VERSION_CODES.P)
class AndroidHidProxyClient(
    private val context: Context,
) : HidProxyClient {
    private var events: HidProxyClient.Events? = null

    // Read on the JNI report thread (sendReport) but mutated on the binder callback thread
    // (onConnectionStateChanged / onServiceDisconnected); volatile publishes those writes.
    @Volatile private var hidDevice: BluetoothHidDevice? = null

    @Volatile private var connectedDevice: BluetoothDevice? = null
    private var currentProfile: BluetoothGamepad.GamepadProfile? = null

    // Per-thread (sendReport is reached from the BT dispatch and on-screen-pad threads); avoids a
    // payload allocation per report.
    private val payloadScratch = ThreadLocal.withInitial { ByteArray(REPORT_SIZE - 1) }

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
        try {
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            events.onError("Bluetooth permission denied: ${e.message ?: "BLUETOOTH_CONNECT not granted"}")
        }
    }

    override fun registerApp(profile: BluetoothGamepad.GamepadProfile) {
        val hid = hidDevice ?: return
        currentProfile = profile
        val sdp =
            BluetoothHidDeviceAppSdpSettings(
                profile.sdpName,
                profile.sdpDescription,
                profile.sdpProvider,
                BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                buildHidDescriptor(),
            )
        val qos =
            BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_GUARANTEED,
                TOKEN_RATE,
                BluetoothHidDeviceAppQosSettings.MAX,
                BluetoothHidDeviceAppQosSettings.MAX,
                BT_SLOT_US,
                JITTER_US,
            )
        try {
            hid.registerApp(sdp, null, qos, { it.run() }, hidCallback)
        } catch (e: SecurityException) {
            events?.onError("Bluetooth permission denied: ${e.message ?: "BLUETOOTH_CONNECT not granted"}")
        }
    }

    override fun connectToHost(mac: String) {
        val hid = hidDevice ?: return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return
        try {
            hid.connect(adapter.getRemoteDevice(mac))
        } catch (e: SecurityException) {
            events?.onError("Bluetooth permission denied: ${e.message ?: "BLUETOOTH_CONNECT not granted"}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "getRemoteDevice rejected $mac: ${e.message}")
            events?.onError("Invalid host address: $mac")
        }
    }

    override fun disconnectCurrentHost() {
        val hid = hidDevice ?: return
        val device = connectedDevice ?: return
        try {
            hid.disconnect(device)
        } catch (e: SecurityException) {
            // Without the grant the link is the OS's to keep; the session state still drops it.
            Log.w(TAG, "disconnect without BLUETOOTH_CONNECT: ${e.message}")
        }
    }

    override fun findOsConnectedHost(mac: String): String? {
        val hid = hidDevice ?: return null
        return try {
            hid
                .getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                .firstOrNull { it.address.equals(mac, ignoreCase = true) }
                ?.let { it.name ?: it.address }
        } catch (e: SecurityException) {
            Log.w(TAG, "connected hosts unavailable without BLUETOOTH_CONNECT: ${e.message}")
            null
        }
    }

    override fun sendReport(report: ByteArray): Boolean {
        // A concurrent teardown can null the stack out from under us, so hid.sendReport may throw
        // IllegalStateException after the proxy closed, and a revoked grant throws SecurityException.
        // This runs on the JNI report thread at report rate, which must never crash and must not
        // log per report: the false return is the whole answer, and the session state machine
        // reports the teardown or the revocation once, on its own thread.
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        // Strip report-id byte into per-thread scratch: sendReport takes it separately from the payload.
        val payload = payloadScratch.get() ?: return false
        System.arraycopy(report, 1, payload, 0, REPORT_SIZE - 1)
        return try {
            hid.sendReport(device, REPORT_ID, payload)
        } catch (ignored: SecurityException) {
            false
        } catch (ignored: IllegalStateException) {
            false
        }
    }

    // Release runs from BluetoothHidSession.teardownLocked, which holds the session lock and is
    // reached from the profile callbacks on a binder thread, so it must not throw and must not
    // stop half way: the three steps are independent, and a stack that has already gone away
    // must not keep the ones after it from running.
    override fun unregisterAndRelease() {
        val hid = hidDevice
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (hid != null) {
            val device = connectedDevice
            if (device != null) {
                try {
                    hid.disconnect(device)
                } catch (e: SecurityException) {
                    // Without the grant the link is the OS's to drop; the rest of the release stands.
                    Log.w(TAG, "disconnect without BLUETOOTH_CONNECT: ${e.message}")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "disconnect after the stack went away: ${e.message}")
                }
            }
            try {
                hid.unregisterApp()
            } catch (e: SecurityException) {
                // The proxy is still closed below; the OS tears the registration down with it.
                Log.w(TAG, "unregisterApp without BLUETOOTH_CONNECT: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "unregisterApp after the stack went away: ${e.message}")
            }
            try {
                manager?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (e: IllegalArgumentException) {
                // Closing a proxy whose service binding is already gone.
                Log.w(TAG, "the HID proxy was already released: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "closeProfileProxy after the stack went away: ${e.message}")
            }
        }
        hidDevice = null
        connectedDevice = null
        currentProfile = null
        events = null
    }

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
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

    private val hidCallback =
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(
                pluggedDevice: BluetoothDevice?,
                registered: Boolean,
            ) {
                if (registered) events?.onAppRegistered() else events?.onAppUnregistered()
            }

            override fun onConnectionStateChanged(
                device: BluetoothDevice,
                state: Int,
            ) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        events?.onHostConnected(device.address, hostName(device))
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice?.address == device.address) connectedDevice = null
                        events?.onHostDisconnected(device.address)
                    }
                }
            }
        }

    // The connected callback runs whatever the grant is; a host whose name we may not read is
    // still connected, just unnamed.
    private fun hostName(device: BluetoothDevice): String? =
        try {
            device.name
        } catch (e: SecurityException) {
            Log.w(TAG, "host name unavailable without BLUETOOTH_CONNECT: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "AndroidHidProxyClient"
        const val TOKEN_RATE = 3200
        const val BT_SLOT_US = 625
        const val JITTER_US = 1250
    }
}
