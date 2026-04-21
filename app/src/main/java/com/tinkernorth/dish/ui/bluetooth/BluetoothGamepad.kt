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
 * Manages a Bluetooth HID gamepad device.
 *
 * Registers the app as a Bluetooth HID device with either Xbox or PlayStation
 * report descriptors, handles connection/disconnection with host devices,
 * and sends gamepad reports.
 *
 * Requires API 28+ (Android 9).
 */
@RequiresApi(Build.VERSION_CODES.P)
class BluetoothGamepad(
    private val context: Context,
    private val listener: Listener,
) {
    companion object

    interface Listener {
        fun onRegistered()

        fun onUnregistered()

        fun onConnected(device: BluetoothDevice)

        fun onDisconnected(device: BluetoothDevice)

        fun onError(message: String)
    }

    enum class GamepadProfile(
        val profileName: String,
        val sdpName: String,
        val sdpDescription: String,
        val sdpProvider: String,
    ) {
        XBOX(
            profileName = "Xbox",
            sdpName = "Dish Xbox Controller",
            sdpDescription = "Wireless Xbox Controller",
            sdpProvider = "TinkerNorth",
        ),
        PLAYSTATION(
            profileName = "PlayStation",
            sdpName = "Dish PS Controller",
            sdpDescription = "Wireless PlayStation Controller",
            sdpProvider = "TinkerNorth",
        ),
    }

    private var hidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var registered = false
    var currentProfile = GamepadProfile.XBOX
        private set

    /**
     * MAC address of a previously-connected host. When non-null, the callback
     * attempts to reconnect automatically as soon as the HID app is registered.
     * One-shot: cleared after the attempt so we never re-trigger on reconnect.
     */
    private var autoConnectMac: String? = null

    val isRegistered get() = registered
    val isConnected get() = connectedDevice != null

    // ── HID Report Descriptor ────────────────────────────────────────────────
    // Standard HID gamepad: 2 sticks (16-bit X/Y), 2 triggers (8-bit),
    // 14 buttons, 1 hat switch (d-pad). Works as Xbox-compatible on all hosts.

    @Suppress("MagicNumber", "LongMethod")
    private fun buildDescriptor(): ByteArray =
        byteArrayOf(
            0x05,
            0x01, // Usage Page (Generic Desktop)
            0x09,
            0x05, // Usage (Gamepad)
            0xA1.toByte(),
            0x01, // Collection (Application)
            0x85.toByte(),
            0x01, //   Report ID (1)
            // ── 14 Buttons ──
            0x05,
            0x09, //   Usage Page (Buttons)
            0x19,
            0x01, //   Usage Minimum (1)
            0x29,
            0x0E, //   Usage Maximum (14)
            0x15,
            0x00, //   Logical Minimum (0)
            0x25,
            0x01, //   Logical Maximum (1)
            0x75,
            0x01, //   Report Size (1)
            0x95.toByte(),
            0x0E, //   Report Count (14)
            0x81.toByte(),
            0x02, //   Input (Variable)
            // 2-bit padding to align to byte
            0x75,
            0x01, //   Report Size (1)
            0x95.toByte(),
            0x02, //   Report Count (2)
            0x81.toByte(),
            0x03, //   Input (Constant)
            // ── Hat Switch (D-Pad) ──
            0x05,
            0x01, //   Usage Page (Generic Desktop)
            0x09,
            0x39, //   Usage (Hat Switch)
            0x15,
            0x01, //   Logical Minimum (1)
            0x25,
            0x08, //   Logical Maximum (8)
            0x35,
            0x00, //   Physical Minimum (0)
            0x46,
            0x3B,
            0x01, //   Physical Maximum (315)
            0x65,
            0x14, //   Unit (Degrees)
            0x75,
            0x04, //   Report Size (4)
            0x95.toByte(),
            0x01, //   Report Count (1)
            0x81.toByte(),
            0x42, //   Input (Variable, Null State)
            // 4-bit padding
            0x75,
            0x04, //   Report Size (4)
            0x95.toByte(),
            0x01, //   Report Count (1)
            0x81.toByte(),
            0x03, //   Input (Constant)
            // ── Reset globals leaked by Hat Switch ──
            0x35,
            0x00, //   Physical Minimum (0)   — reset
            0x45,
            0x00, //   Physical Maximum (0)   — reset (0,0 = use logical)
            0x65,
            0x00, //   Unit (None)            — reset
            // ── Left Stick X/Y (16-bit signed) ──
            0x05,
            0x01, //   Usage Page (Generic Desktop)
            0x09,
            0x30, //   Usage (X)
            0x09,
            0x31, //   Usage (Y)
            0x16,
            0x00,
            0x80.toByte(), //   Logical Minimum (-32768)
            0x26,
            0xFF.toByte(),
            0x7F, //   Logical Maximum (32767)
            0x75,
            0x10, //   Report Size (16)
            0x95.toByte(),
            0x02, //   Report Count (2)
            0x81.toByte(),
            0x02, //   Input (Variable)
            // ── Right Stick X/Y (16-bit signed) ──
            0x09,
            0x33, //   Usage (Rx)
            0x09,
            0x34, //   Usage (Ry)
            0x16,
            0x00,
            0x80.toByte(), //   Logical Minimum (-32768)
            0x26,
            0xFF.toByte(),
            0x7F, //   Logical Maximum (32767)
            0x75,
            0x10, //   Report Size (16)
            0x95.toByte(),
            0x02, //   Report Count (2)
            0x81.toByte(),
            0x02, //   Input (Variable)
            // ── Triggers (8-bit unsigned) ──
            0x05,
            0x02, //   Usage Page (Simulation)
            0x09,
            0xC5.toByte(), //   Usage (Brake / Left Trigger)
            0x09,
            0xC4.toByte(), //   Usage (Accelerator / Right Trigger)
            0x15,
            0x00, //   Logical Minimum (0)
            0x26,
            0xFF.toByte(),
            0x00, //   Logical Maximum (255)
            0x75,
            0x08, //   Report Size (8)
            0x95.toByte(),
            0x02, //   Report Count (2)
            0x81.toByte(),
            0x02, //   Input (Variable)
            0xC0.toByte(), // End Collection
        )

    // Report is: [reportId=1][buttons 2B][hat 1B][LX 2B][LY 2B][RX 2B][RY 2B][LT 1B][RT 1B]
    // Total: 13 bytes (without report ID prefix, which BluetoothHidDevice adds)

    @Suppress("MagicNumber")
    fun buildReport(
        buttons: Int,
        hatSwitch: Int,
        leftX: Short,
        leftY: Short,
        rightX: Short,
        rightY: Short,
        leftTrigger: Int,
        rightTrigger: Int,
    ): ByteArray {
        val report = ByteArray(REPORT_SIZE)
        report[0] = REPORT_ID.toByte()
        report[1] = (buttons and 0xFF).toByte()
        report[2] = ((buttons shr 8) and 0xFF).toByte()
        report[3] = (hatSwitch and 0xFF).toByte()
        report[4] = (leftX.toInt() and 0xFF).toByte()
        report[5] = ((leftX.toInt() shr 8) and 0xFF).toByte()
        report[6] = (leftY.toInt() and 0xFF).toByte()
        report[7] = ((leftY.toInt() shr 8) and 0xFF).toByte()
        report[8] = (rightX.toInt() and 0xFF).toByte()
        report[9] = ((rightX.toInt() shr 8) and 0xFF).toByte()
        report[10] = (rightY.toInt() and 0xFF).toByte()
        report[11] = ((rightY.toInt() shr 8) and 0xFF).toByte()
        report[12] = (leftTrigger and 0xFF).toByte()
        report[13] = (rightTrigger and 0xFF).toByte()
        return report
    }

    // ── Bluetooth HID lifecycle ──────────────────────────────────────────────

    private val profileListener =
        object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(
                profile: Int,
                proxy: BluetoothProfile,
            ) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile != BluetoothProfile.HID_DEVICE) return
                hidDevice = null
                registered = false
                connectedDevice = null
                listener.onUnregistered()
            }
        }

    @SuppressLint("MissingPermission")
    private val hidCallback =
        object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(
                pluggedDevice: BluetoothDevice?,
                registered: Boolean,
            ) {
                this@BluetoothGamepad.registered = registered
                if (registered) {
                    listener.onRegistered()
                    tryAutoReconnect()
                } else {
                    connectedDevice = null
                    listener.onUnregistered()
                }
            }

            override fun onConnectionStateChanged(
                device: BluetoothDevice,
                state: Int,
            ) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        autoConnectMac = null
                        listener.onConnected(device)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice?.address == device.address) {
                            connectedDevice = null
                        }
                        listener.onDisconnected(device)
                    }
                }
            }
        }

    /**
     * If a saved host MAC is set, try to restore the connection. First checks
     * the HID profile's already-connected devices (host may have auto-reopened
     * the ACL link), then falls back to an active [BluetoothHidDevice.connect].
     */
    @SuppressLint("MissingPermission")
    private fun tryAutoReconnect() {
        val mac = autoConnectMac ?: return
        val hid = hidDevice ?: return
        try {
            val already = hid
                .getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                .firstOrNull { it.address.equals(mac, ignoreCase = true) }
            if (already != null) {
                connectedDevice = already
                autoConnectMac = null
                listener.onConnected(already)
                return
            }
        } catch (_: SecurityException) {
            // Fall through to active connect below.
        }
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return
        try {
            val device = adapter.getRemoteDevice(mac)
            hid.connect(device)
        } catch (_: IllegalArgumentException) {
            autoConnectMac = null
            listener.onError("Invalid saved host address")
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerApp() {
        val hid = hidDevice ?: return
        val sdp =
            BluetoothHidDeviceAppSdpSettings(
                currentProfile.sdpName,
                currentProfile.sdpDescription,
                currentProfile.sdpProvider,
                BluetoothHidDevice.SUBCLASS2_GAMEPAD,
                buildDescriptor(),
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
        hid.registerApp(sdp, null, qos, { it.run() }, hidCallback)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start(profile: GamepadProfile, autoConnectMac: String? = null) {
        currentProfile = profile
        this.autoConnectMac = autoConnectMac
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("Bluetooth is not available or not enabled")
            return
        }
        adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val hid = hidDevice ?: return
        connectedDevice?.let { hid.disconnect(it) }
        hid.unregisterApp()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        hidDevice = null
        registered = false
        connectedDevice = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        val hid = hidDevice
        if (hid == null || !registered) {
            listener.onError("HID device not registered")
            return
        }
        hid.connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectedDevice?.let { hidDevice?.disconnect(it) }
    }

    @SuppressLint("MissingPermission")
    fun sendReport(report: ByteArray): Boolean {
        val hid = hidDevice ?: return false
        val device = connectedDevice ?: return false
        val ok = hid.sendReport(device, REPORT_ID, report.sliceArray(1 until report.size))
        if (!ok) android.util.Log.w("BtGamepad", "sendReport FAILED")
        return ok
    }
}

private const val REPORT_ID = 1
private const val REPORT_SIZE = 14
private const val TOKEN_RATE = 3200 // 250 reports/sec × 13 bytes
private const val BT_SLOT_US = 625 // Bluetooth Classic slot duration
private const val JITTER_US = 1250
