// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [PadAudioRoutes] in step with what the OS actually routes, by walking the two lists only
 * the platform has: the attached USB devices and the enumerated audio endpoints.
 *
 * It listens on the AUDIO side rather than the USB side on purpose. The table is a statement about
 * endpoints, and a pad whose audio function the OS never enumerated has no route however long its
 * cable has been in. So the trigger is [AudioDeviceCallback]: by the time an endpoint is announced
 * its USB device is certainly in the device list, and when the endpoint goes the route goes with
 * it. Registration itself delivers the current set, which is the initial resolve.
 *
 * Publishing re-runs the capability composition, which re-declares the affected slot's descriptor,
 * so a pad plugged mid-session gains its mic/speaker caps without anything else being asked to
 * notice.
 */
@Singleton
class PadAudioRouteResolver
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val routes: PadAudioRoutes,
        private val facts: PadAudioFactsStore,
        private val native: PhysicalInputNative,
    ) {
        private val audioManager: AudioManager? =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        private val usbManager: UsbManager? =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager

        @Volatile private var installed = false

        private inner class AudioDeviceWatcher : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = resolve()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = resolve()
        }

        private val callback = AudioDeviceWatcher()

        /**
         * Process-scoped, like the USB manager's own install: the capability model is composed
         * whether or not a screen is up, and a route that went stale while the app was backgrounded
         * would advertise a cap for an endpoint that is no longer there.
         */
        fun install() {
            if (installed) return
            installed = true
            val manager = audioManager
            if (manager == null) {
                Log.w(TAG, "no AudioManager, physical pads advertise no audio endpoints")
                return
            }
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }

        /** Re-read both lists and republish. Cheap, and the only writer of the table. */
        fun resolve() {
            val explained = explainPadAudio(attachedPads(), usbEndpoints())
            routes.publishRoutes(routesOf(explained))
            facts.publish(explained)
        }

        private fun attachedPads(): List<UsbAudioPad> {
            val devices = usbManager?.deviceList?.values ?: return emptyList()
            return devices.map { device ->
                UsbAudioPad(
                    vendorId = device.vendorId,
                    productId = device.productId,
                    productName = device.productName,
                    hasAudioFunction = hasAudioInterface(device),
                    hasHapticLanes = native.modelHasHapticLanes(device.vendorId, device.productId),
                )
            }
        }

        // Interface descriptors are readable without USB permission (only opening the device needs
        // it), so this holds for a pad the user has not granted us and for one we never claimed.
        private fun hasAudioInterface(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any {
                device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO
            }

        // Asked in two queries rather than one GET_DEVICES_ALL: a device's input and output roles
        // are separate ports with separate ids anyway, so this is the same list, and the platform
        // annotates the parameter as one direction or the other.
        private fun usbEndpoints(): List<UsbAudioEndpoint> {
            val manager = audioManager ?: return emptyList()
            val ports =
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() +
                    manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            return ports.filter { isPluggedUsbType(it.type, Build.VERSION.SDK_INT) }.map {
                UsbAudioEndpoint(
                    deviceId = it.id,
                    productName = it.productName?.toString(),
                    sink = it.isSink,
                    source = it.isSource,
                    channelCounts = it.channelCounts.toList(),
                )
            }
        }

        private companion object {
            const val TAG = "PadAudioRoutes"
        }
    }

// TYPE_USB_ACCESSORY is this phone in accessory mode (a host driving US), not a pad we can route
// to, so it is deliberately absent. TYPE_USB_HEADSET is a 26+ classification.
internal fun isPluggedUsbType(
    type: Int,
    sdkInt: Int,
): Boolean {
    if (type == AudioDeviceInfo.TYPE_USB_DEVICE) return true
    if (atLeast(Build.VERSION_CODES.O, sdkInt)) return type == AudioDeviceInfo.TYPE_USB_HEADSET
    return false
}

// The annotation is what lets lint read a caller-supplied API level as the gate the 26+ constant
// above needs; a bare `sdkInt >= api` comparison it cannot see through.
@ChecksSdkIntAtLeast(parameter = 0)
private fun atLeast(
    api: Int,
    sdkInt: Int,
): Boolean = sdkInt >= api
