// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.R
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.notification.DishNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// Hilt singleton that manages the USB-host fast lane. Direct mode is opt-in per connection: when a
// recognised controller is attached we post a themed prompt asking whether to move it to the
// C-level fast lane. Accepting (prompt action or the per-card button) requests USB permission,
// claims the interface, and hands the fd to SatelliteNative.attachUsbDevice; the per-device hot
// path then runs entirely in C. This class is only touched on plug-in, prompt/button action,
// permission-grant, and unplug.
@Singleton
class UsbGamepadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val notifications: DishNotifications,
        private val scope: CoroutineScope,
    ) {
        private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        private val claimedDevices = HashMap<String, ClaimedDevice>()
        private val promptedDevices = HashSet<String>()

        @Volatile private var installed = false

        private data class ClaimedDevice(
            val device: UsbDevice,
            val connection: UsbDeviceConnection,
            val intf: UsbInterface,
            val syntheticDeviceId: Int,
        )

        fun install() {
            if (installed) return
            installed = true
            val filter =
                IntentFilter().apply {
                    addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                    addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                    addAction(ACTION_USB_PERMISSION)
                }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            scanExistingDevices()
        }

        private fun scanExistingDevices() {
            // App start: the dashboard isn't up yet, so a prompt would be dropped. Silently restore
            // devices we already hold permission for; reconcileForeground() prompts the rest once
            // the UI is showing.
            for (device in usbManager.deviceList.values) {
                claimIfPermitted(device)
            }
        }

        private fun claimIfPermitted(device: UsbDevice) {
            if (!isCandidate(device)) return
            if (claimedDevices.containsKey(keyFor(device))) return
            if (usbManager.hasPermission(device)) claimAndReport(device, notify = false)
        }

        // Called when the dashboard comes to the foreground: silently restores already-permitted
        // candidates and prompts the rest (once per connection), covering controllers plugged in
        // while the app was closed.
        fun reconcileForeground() {
            for (device in usbManager.deviceList.values) {
                handleAttached(device)
            }
        }

        // Entry point for the prompt's "Try" action and the per-card "Try Direct mode" button.
        // Claims the device (permission already held) or pops the system permission dialog; the
        // outcome surfaces as a themed toast.
        fun tryDirectMode(
            vendorId: Int,
            productId: Int,
        ) {
            val device =
                usbManager.deviceList.values.firstOrNull {
                    it.vendorId == vendorId && it.productId == productId
                } ?: return
            if (claimedDevices.containsKey(keyFor(device))) return
            if (usbManager.hasPermission(device)) {
                claimAndReport(device, notify = true)
            } else {
                requestPermission(device)
            }
        }

        private val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ctx: Context,
                    intent: Intent,
                ) {
                    when (intent.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            val device = deviceFromIntent(intent) ?: return
                            handleAttached(device)
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            val device = deviceFromIntent(intent) ?: return
                            handleDetached(device)
                        }
                        ACTION_USB_PERMISSION -> {
                            val device = deviceFromIntent(intent) ?: return
                            val granted =
                                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            // Denial leaves the controller eligible (the card keeps its "Try Direct
                            // mode" button); only a real claim failure locks it.
                            if (granted) claimAndReport(device, notify = true)
                        }
                    }
                }
            }

        private fun handleAttached(device: UsbDevice) {
            if (!isCandidate(device)) return
            if (claimedDevices.containsKey(keyFor(device))) return
            if (usbManager.hasPermission(device)) {
                claimAndReport(device, notify = false)
            } else {
                promptTryDirect(device)
            }
        }

        private fun handleDetached(device: UsbDevice) {
            val key = keyFor(device)
            promptedDevices.remove(key)
            registry.clearDirectFailed(device.vendorId, device.productId)
            val claimed = claimedDevices.remove(key) ?: return
            SatelliteNative.detachUsbDevice(claimed.syntheticDeviceId)
            runCatching {
                claimed.connection.releaseInterface(claimed.intf)
                claimed.connection.close()
            }
            registry.removeUsbSynthetic(claimed.syntheticDeviceId)
        }

        private fun claimAndReport(
            device: UsbDevice,
            notify: Boolean,
        ) {
            val failure = attemptClaim(device)
            if (failure == null) {
                registry.clearDirectFailed(device.vendorId, device.productId)
                if (notify) notifyDirect(device, R.string.direct_enabled, success = true)
            } else {
                registry.markDirectFailed(device.vendorId, device.productId, failure)
                Log.i(TAG, "direct failed ${device.vendorId.toHex4()}:${device.productId.toHex4()} reason=$failure")
                if (notify) notifyDirect(device, R.string.direct_failed, success = false)
            }
        }

        // Returns null on success (the device is now on the fast lane), or the PathReason explaining
        // why it couldn't be claimed. Surfaces nothing to the user; the caller decides whether to
        // toast (user-initiated) or stay silent (background restore).
        private fun attemptClaim(device: UsbDevice): PathReason? {
            val key = keyFor(device)
            if (claimedDevices.containsKey(key)) return null
            val (intf, epIn, epOut) =
                findInterruptInPair(device) ?: return PathReason.SupportedNoFastPathYet
            val conn = usbManager.openDevice(device) ?: return PathReason.Busy
            if (!conn.claimInterface(intf, true)) {
                conn.close()
                return PathReason.Busy
            }
            val synthetic =
                SatelliteNative.attachUsbDevice(
                    fd = conn.fileDescriptor,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    interfaceNumber = intf.id,
                    endpointIn = epIn.address,
                    endpointInMaxPacket = epIn.maxPacketSize,
                    endpointOut = epOut?.address ?: 0,
                )
            if (synthetic == 0) {
                runCatching {
                    conn.releaseInterface(intf)
                    conn.close()
                }
                return PathReason.InitFailed
            }
            claimedDevices[key] = ClaimedDevice(device, conn, intf, synthetic)
            val displayName = friendlyName(device)
            val pollRateHz = computePollRateHz(epIn.interval, epIn.maxPacketSize)
            registry.addUsbSynthetic(
                deviceId = synthetic,
                name = displayName,
                hasGyro = SatelliteNative.modelHasImu(device.vendorId, device.productId),
                pollRateHz = pollRateHz,
                vendorId = device.vendorId,
                productId = device.productId,
            )
            Log.i(TAG, "claimed ${device.vendorId.toHex4()}:${device.productId.toHex4()} ($displayName) → dev=$synthetic")
            return null
        }

        private fun promptTryDirect(device: UsbDevice) {
            if (!promptedDevices.add(keyFor(device))) return
            val name = friendlyName(device)
            val vendorId = device.vendorId
            val productId = device.productId
            notifications.info(
                glyph = R.drawable.ic_gamepad,
                title = context.getString(R.string.direct_prompt_title, name),
                action =
                    DishNotification.Action(
                        label = context.getString(R.string.direct_prompt_action),
                    ) { tryDirectMode(vendorId, productId) },
                key = "direct-prompt:${keyFor(device)}",
                durationMs = DishNotification.DURATION_LONG,
            )
        }

        private fun notifyDirect(
            device: UsbDevice,
            titleRes: Int,
            success: Boolean,
        ) {
            val title = context.getString(titleRes, friendlyName(device))
            val key = "direct-result:${keyFor(device)}"
            if (success) {
                notifications.success(glyph = R.drawable.ic_gamepad, title = title, key = key)
            } else {
                notifications.warn(glyph = R.drawable.ic_gamepad, title = title, key = key)
            }
        }

        // The PendingIntent must be MUTABLE on API 31+: UsbManager fills EXTRA_DEVICE and
        // EXTRA_PERMISSION_GRANTED into the result when it fires, which it can't do on an immutable
        // one (the grant then never reaches our receiver). Safe here: the intent is package-scoped
        // and the receiver is not exported.
        private fun requestPermission(device: UsbDevice) {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending = PendingIntent.getBroadcast(context, device.deviceId, intent, flags)
            scope.launch(Dispatchers.Main) {
                usbManager.requestPermission(device, pending)
            }
        }

        // A controller is a Direct-mode candidate if it's gamepad-shaped and its VID/PID is in our
        // parser table. Both are readable without USB permission, so we decide before prompting.
        private fun isCandidate(device: UsbDevice): Boolean =
            isGamepadShaped(device) &&
                SatelliteNative.isKnownFastLaneModel(device.vendorId, device.productId)

        // Stable per-connection key: deviceName is the USB bus path, identical at attach and detach,
        // unique per attached device, and readable without USB permission.
        private fun keyFor(device: UsbDevice): String = device.deviceName

        private fun friendlyName(device: UsbDevice): String {
            val known = SatelliteNative.lookupKnownModelName(device.vendorId, device.productId)
            if (known.isNotEmpty()) return known
            val product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) device.productName else null
            return product?.takeIf { it.isNotBlank() } ?: device.deviceName
        }

        private fun isGamepadShaped(device: UsbDevice): Boolean {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val isHid = intf.interfaceClass == UsbConstants.USB_CLASS_HID
                val isVendor = intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
                if (!isHid && !isVendor) continue
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.direction == UsbConstants.USB_DIR_IN
                    ) {
                        return true
                    }
                }
            }
            return false
        }

        private fun findInterruptInPair(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val isHid = intf.interfaceClass == UsbConstants.USB_CLASS_HID
                val isVendor = intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC
                if (!isHid && !isVendor) continue
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
                }
                if (epIn != null) return Triple(intf, epIn, epOut)
            }
            return null
        }

        // Derives the controller's advertised poll rate from the endpoint descriptor. USB-HID
        // interrupt endpoints encode this differently per device speed: low/full-speed devices
        // report bInterval in 1ms frames (rate = 1000 / bInterval), high-speed devices encode it
        // as a power-of-2 microframe exponent (rate = 8000 / 2^(bInterval-1)).
        //
        // Android's UsbEndpoint.getInterval returns the raw bInterval byte and does not expose
        // the negotiated USB speed. We infer high-speed from the IN endpoint's max packet size:
        // anything over 64 bytes can only come from a high-speed (or faster) endpoint.
        private fun computePollRateHz(
            epInterval: Int,
            epMaxPacketSize: Int,
        ): Int {
            if (epInterval <= 0) return 0
            val isHighSpeed = epMaxPacketSize > MAX_FS_INTERRUPT_PACKET
            val periodMicros =
                if (isHighSpeed) {
                    val exp = (epInterval - 1).coerceIn(0, 15)
                    (1L shl exp) * 125L
                } else {
                    epInterval.toLong() * 1000L
                }
            if (periodMicros <= 0L) return 0
            return (1_000_000L / periodMicros).toInt()
        }

        private fun deviceFromIntent(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

        private fun Int.toHex4(): String = "%04x".format(this and 0xFFFF)

        private companion object {
            const val TAG = "UsbGamepadManager"
            const val ACTION_USB_PERMISSION = "com.tinkernorth.dish.USB_PERMISSION"
            const val MAX_FS_INTERRUPT_PACKET = 64
        }
    }
