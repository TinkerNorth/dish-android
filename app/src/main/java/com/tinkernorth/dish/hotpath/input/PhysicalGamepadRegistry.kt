// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.tinkernorth.dish.core.jni.SatelliteNative
import com.tinkernorth.dish.source.sensor.PhysicalMotionProbe
import com.tinkernorth.dish.source.usb.PathMode
import com.tinkernorth.dish.source.usb.PathReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalGamepadRegistry
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val scope: CoroutineScope,
    ) : InputManager.InputDeviceListener {
        data class Device(
            val id: Int,
            val name: String,
            val disconnectingTimeLeftSec: Int? = null,
            val hasGyro: Boolean = false,
            val pathMode: PathMode = PathMode.Routed,
            val pathReason: PathReason = PathReason.None,
            val isUsbSynthetic: Boolean = false,
            val pollRateHz: Int = 0,
            val vendorId: Int = 0,
            val productId: Int = 0,
        ) {
            val isDisconnecting: Boolean get() = disconnectingTimeLeftSec != null
        }

        private val inputManager =
            context.getSystemService(Context.INPUT_SERVICE) as InputManager

        private val usbManager =
            context.getSystemService(Context.USB_SERVICE) as UsbManager

        private val _devices = MutableStateFlow<Map<Int, Device>>(emptyMap())
        val devices: StateFlow<Map<Int, Device>> = _devices.asStateFlow()

        @Volatile private var installed = false

        private val disconnectJobs = HashMap<Int, Job>()

        fun install() {
            if (installed) return
            installed = true
            inputManager.registerInputDeviceListener(this, null)
            syncAll()
        }

        private fun syncAll() {
            val next = mutableMapOf<Int, Device>()
            for (id in InputDevice.getDeviceIds()) {
                val dev = InputDevice.getDevice(id) ?: continue
                if (!isGamepad(dev)) continue
                pushDeadzones(dev)
                next[id] = makeRoutedDevice(id, dev)
            }
            _devices.value = next
        }

        override fun onInputDeviceAdded(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId) ?: return
            if (!isGamepad(dev)) return
            pushDeadzones(dev)
            cancelDisconnect(deviceId)
            _devices.value = _devices.value + (deviceId to makeRoutedDevice(deviceId, dev))
        }

        // Classifies a framework-routed controller. A known fast-lane model on the USB bus is an
        // Eligible Direct-mode candidate (the card offers a "Try Direct mode" action) unless a prior
        // attempt failed, in which case it shows that locked reason. Off the USB bus (Bluetooth)
        // Direct mode can't apply; unknown models stay UnknownModel.
        private fun makeRoutedDevice(
            deviceId: Int,
            dev: InputDevice,
        ): Device {
            val vid = runCatching { dev.vendorId }.getOrDefault(0)
            val pid = runCatching { dev.productId }.getOrDefault(0)
            val reason =
                when {
                    vid == 0 || pid == 0 -> PathReason.UnknownModel
                    !SatelliteNative.isKnownFastLaneModel(vid, pid) -> PathReason.UnknownModel
                    !isUsbDevicePresent(vid, pid) -> PathReason.Bluetooth
                    else -> directFailed[vpKey(vid, pid)] ?: PathReason.Eligible
                }
            return Device(
                id = deviceId,
                name = dev.name,
                hasGyro = PhysicalMotionProbe.hasGyro(deviceId),
                pathMode = PathMode.Routed,
                pathReason = reason,
                vendorId = vid,
                productId = pid,
            )
        }

        private fun isUsbDevicePresent(
            vendorId: Int,
            productId: Int,
        ): Boolean =
            usbManager.deviceList.values.any {
                it.vendorId == vendorId && it.productId == productId
            }

        private val directFailed = HashMap<Int, PathReason>()

        private fun vpKey(
            vendorId: Int,
            productId: Int,
        ): Int = (vendorId shl 16) or (productId and 0xFFFF)

        // Called by UsbGamepadManager when a Direct-mode claim fails. Locks the matching framework
        // card to the failure reason (no retry affordance) until the controller is re-plugged.
        fun markDirectFailed(
            vendorId: Int,
            productId: Int,
            reason: PathReason,
        ) {
            directFailed[vpKey(vendorId, productId)] = reason
            _devices.value =
                _devices.value.mapValues { (_, d) ->
                    if (!d.isUsbSynthetic && d.vendorId == vendorId && d.productId == productId) {
                        d.copy(pathReason = reason)
                    } else {
                        d
                    }
                }
        }

        fun clearDirectFailed(
            vendorId: Int,
            productId: Int,
        ) {
            directFailed.remove(vpKey(vendorId, productId))
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            val current = _devices.value[deviceId] ?: return
            scheduleDisconnect(current)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId)
            if (dev == null || !isGamepad(dev)) {
                _devices.value[deviceId]?.let { scheduleDisconnect(it) }
                return
            }
            cancelDisconnect(deviceId)
            // Bluetooth Switch Pro Controllers enumerate sensors after onInputDeviceAdded; re-probe to catch the late gyro.
            val nextHasGyro = PhysicalMotionProbe.hasGyro(deviceId)
            val current = _devices.value[deviceId]
            val needsUpdate =
                current == null ||
                    current.name != dev.name ||
                    current.isDisconnecting ||
                    current.hasGyro != nextHasGyro
            if (needsUpdate) {
                if (current?.hasGyro != nextHasGyro) {
                    Log.i(
                        TAG,
                        "pad $deviceId (${dev.name}) hasGyro re-probed: " +
                            "${current?.hasGyro} -> $nextHasGyro",
                    )
                }
                _devices.value =
                    _devices.value + (deviceId to makeRoutedDevice(deviceId, dev))
            }
        }

        private fun scheduleDisconnect(device: Device) {
            disconnectJobs.remove(device.id)?.cancel()
            disconnectJobs[device.id] =
                scope.launch {
                    var remaining = DISCONNECT_GRACE_SEC
                    while (isActive && remaining > 0) {
                        val current = _devices.value[device.id] ?: return@launch
                        _devices.value =
                            _devices.value + (device.id to current.copy(disconnectingTimeLeftSec = remaining))
                        delay(1000L)
                        remaining -= 1
                    }
                    _devices.value = _devices.value - device.id
                    disconnectJobs.remove(device.id)
                }
        }

        private fun cancelDisconnect(deviceId: Int) {
            disconnectJobs.remove(deviceId)?.cancel()
            val cur = _devices.value[deviceId] ?: return
            if (cur.isDisconnecting) {
                _devices.value = _devices.value + (deviceId to cur.copy(disconnectingTimeLeftSec = null))
            }
        }

        // Called by UsbGamepadManager after a successful claim. The synthetic deviceId is the
        // negative integer returned by SatelliteNative.attachUsbDevice; the slot binding observer
        // treats it like any other physical device.
        fun addUsbSynthetic(
            deviceId: Int,
            name: String,
            hasGyro: Boolean,
            pollRateHz: Int,
            vendorId: Int,
            productId: Int,
        ) {
            _devices.value =
                _devices.value +
                (
                    deviceId to
                        Device(
                            id = deviceId,
                            name = name,
                            hasGyro = hasGyro,
                            pathMode = PathMode.Direct,
                            pathReason = PathReason.None,
                            isUsbSynthetic = true,
                            pollRateHz = pollRateHz,
                            vendorId = vendorId,
                            productId = productId,
                        )
                )
        }

        fun removeUsbSynthetic(deviceId: Int) {
            _devices.value = _devices.value - deviceId
        }

        // Called by PollRateSampler with the measured Hz computed from native URB-count deltas.
        // Replaces the advertised rate set at attach with the actual live rate so the UI shows
        // what the controller is doing right now rather than what its descriptor claims.
        fun updateMeasuredPollRate(
            deviceId: Int,
            rateHz: Int,
        ) {
            val cur = _devices.value[deviceId] ?: return
            if (cur.pollRateHz == rateHz) return
            _devices.value = _devices.value + (deviceId to cur.copy(pollRateHz = rateHz))
        }

        // Pushed at device-add time so the native input thread never crosses back into Java per event.
        private fun pushDeadzones(dev: InputDevice) {
            val src = InputDevice.SOURCE_JOYSTICK
            SatelliteNative.setDeviceDeadzones(
                dev.id,
                dev.getMotionRange(MotionEvent.AXIS_X, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Y, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Z, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_RZ, src)?.flat ?: 0f,
            )
            logDeviceCapabilities(dev)
        }

        private fun logDeviceCapabilities(dev: InputDevice) {
            val axes =
                intArrayOf(
                    MotionEvent.AXIS_X,
                    MotionEvent.AXIS_Y,
                    MotionEvent.AXIS_Z,
                    MotionEvent.AXIS_RZ,
                    MotionEvent.AXIS_RX,
                    MotionEvent.AXIS_RY,
                    MotionEvent.AXIS_HAT_X,
                    MotionEvent.AXIS_HAT_Y,
                    MotionEvent.AXIS_LTRIGGER,
                    MotionEvent.AXIS_RTRIGGER,
                    MotionEvent.AXIS_BRAKE,
                    MotionEvent.AXIS_GAS,
                )
            val names =
                arrayOf("X", "Y", "Z", "RZ", "RX", "RY", "HX", "HY", "LT", "RT", "BR", "GS")
            val sb = StringBuilder()
            sb
                .append("DEVCAPS id=")
                .append(dev.id)
                .append(" name=\"")
                .append(dev.name)
                .append('"')
                .append(" sources=0x")
                .append(Integer.toHexString(dev.sources))
                .append(" ranges=[")
            var first = true
            for (i in axes.indices) {
                val r = dev.getMotionRange(axes[i], InputDevice.SOURCE_JOYSTICK) ?: continue
                if (!first) sb.append(',')
                first = false
                sb
                    .append(names[i])
                    .append('(')
                    .append(r.min)
                    .append("..")
                    .append(r.max)
                    .append(",flat=")
                    .append(r.flat)
                    .append(')')
            }
            sb.append(']')
            Log.i("SatelliteJNI", sb.toString())
        }

        // Generic HID joysticks expose buttons as KEYCODE_BUTTON_1..16; gating on hasKeys would hide them.
        private fun isGamepad(d: InputDevice): Boolean = isGamepadDeviceFromCapabilities(d.sources, d.keyboardType)

        private companion object {
            const val TAG = "PhysicalGamepadRegistry"

            // Covers a USB cable jiggle / re-enumeration without holding state long enough that a real removal feels stuck.
            const val DISCONNECT_GRACE_SEC = 5
        }
    }

// Lifted out so the classifier is JVM-testable without mocking InputDevice (final class with many native methods).
internal fun isGamepadDeviceFromCapabilities(
    sources: Int,
    keyboardType: Int,
): Boolean {
    if (keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
    return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
}
