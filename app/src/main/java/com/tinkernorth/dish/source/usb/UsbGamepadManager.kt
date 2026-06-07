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
import com.tinkernorth.dish.composer.ConnectionHub
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.core.model.DishNotification
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.notification.DishNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class UsbGamepadManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PhysicalGamepadRegistry,
        private val connectionHubProvider: Provider<ConnectionHub>,
        private val notifications: DishNotifications,
        private val scope: CoroutineScope,
        private val native: PhysicalInputNative,
    ) {
        private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        private val claimedDevices = ConcurrentHashMap<String, ClaimedDevice>()
        private val promptedDevices = ConcurrentHashMap.newKeySet<String>()

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
            for (device in usbManager.deviceList.values) {
                claimIfPermitted(device)
            }
        }

        private fun claimIfPermitted(device: UsbDevice) {
            if (!isCandidate(device)) return
            if (claimedDevices.containsKey(keyFor(device))) return
            if (usbManager.hasPermission(device)) claimAndReport(device, notify = false)
        }

        fun reconcileForeground() {
            for (device in usbManager.deviceList.values) {
                handleAttached(device)
            }
        }

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
            native.detachUsbDevice(claimed.syntheticDeviceId)
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

        private fun attemptClaim(device: UsbDevice): PathReason? {
            val key = keyFor(device)
            if (claimedDevices.containsKey(key)) return null
            val (intf, epIn, epOut) =
                findInterruptInPair(device) ?: return PathReason.SupportedNoFastPathYet
            val routedSlotId =
                registry.devices.value.values
                    .firstOrNull {
                        !it.isUsbSynthetic &&
                            it.vendorId == device.vendorId &&
                            it.productId == device.productId
                    }?.id
                    ?.toString()
            val conn = openAndClaim(device, intf) ?: return PathReason.Busy
            val synthetic =
                native.attachUsbDevice(
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
            val pollRateHz = computeUsbPollRateHz(epIn.interval, epIn.maxPacketSize)
            registry.addUsbSynthetic(
                deviceId = synthetic,
                name = displayName,
                hasGyro = native.modelHasImu(device.vendorId, device.productId),
                pollRateHz = pollRateHz,
                vendorId = device.vendorId,
                productId = device.productId,
            )
            connectionHubProvider.get().bindClaimedSynthetic(routedSlotId, synthetic.toString())
            Log.i(TAG, "claimed ${device.vendorId.toHex4()}:${device.productId.toHex4()} ($displayName) → dev=$synthetic")
            return null
        }

        private fun openAndClaim(
            device: UsbDevice,
            intf: UsbInterface,
        ): UsbDeviceConnection? {
            val conn = usbManager.openDevice(device) ?: return null
            if (conn.claimInterface(intf, true)) return conn
            conn.close()
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

        private fun isCandidate(device: UsbDevice): Boolean =
            isGamepadShaped(device) &&
                native.isKnownFastLaneModel(device.vendorId, device.productId)

        private fun keyFor(device: UsbDevice): String = device.deviceName

        private fun friendlyName(device: UsbDevice): String {
            val known = native.lookupKnownModelName(device.vendorId, device.productId)
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
        }
    }
