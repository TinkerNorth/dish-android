// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import com.tinkernorth.dish.core.input.resolveGamepadQuirk
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.bluetooth.BluetoothConnections
import com.tinkernorth.dish.source.sensor.PhysicalMotionProbe
import com.tinkernorth.dish.source.usb.DirectClaimFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalGamepadRegistry
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val scope: CoroutineScope,
        private val native: PhysicalInputNative,
        private val btConnections: BluetoothConnections,
    ) : InputManager.InputDeviceListener {
        data class Device(
            val id: Int,
            val name: String,
            val disconnectingTimeLeftSec: Int? = null,
            val hasGyro: Boolean = false,
            val hasRumble: Boolean = false,
            val isUsbSynthetic: Boolean = false,
            // A loader placeholder held visible while the manager switches this controller's path. Its
            // backing device (framework or synthetic) is being torn down/brought up; not actionable.
            val transitioning: Boolean = false,
            // A placeholder kept visible after the OS dropped the device on a failed claim and never
            // gave it back; the user must physically replug. Not actionable.
            val needsReplug: Boolean = false,
            // A held synthetic whose return-to-Standard never re-enumerated; the toggle stays live so the
            // user picks Direct / retry / replug instead of the app silently reverting.
            val restoreStuck: Boolean = false,
            // Why the last Direct claim for this model failed, surfaced on the card under the toggle.
            val directFailure: DirectClaimFailure? = null,
            val pollRateHz: Int = 0,
            val vendorId: Int = 0,
            val productId: Int = 0,
            val transport: Transport = Transport.Usb,
        ) {
            val isDisconnecting: Boolean get() = disconnectingTimeLeftSec != null
        }

        // Last-seen Standard-mode capabilities for a model, kept so the path UI can still show what
        // Standard offers after the framework InputDevice has gone (claimed into Direct).
        data class FrameworkCaps(
            val hasGyro: Boolean,
            val hasRumble: Boolean,
        )

        // Build the pure transient projection of a Device. restoreStuck is gated on isUsbSynthetic, so
        // that identity flag rides along for the reducer's guards.
        private fun Device.placeholderState(): PlaceholderState =
            PlaceholderState(
                transitioning = transitioning,
                needsReplug = needsReplug,
                restoreStuck = restoreStuck,
                disconnectingTimeLeftSec = disconnectingTimeLeftSec,
                isUsbSynthetic = isUsbSynthetic,
            )

        // Copy a reducer result back onto a Device. Only the transient fields move; identity and the
        // hot-path fields are untouched.
        private fun Device.withPlaceholder(state: PlaceholderState): Device =
            copy(
                transitioning = state.transitioning,
                needsReplug = state.needsReplug,
                restoreStuck = state.restoreStuck,
                disconnectingTimeLeftSec = state.disconnectingTimeLeftSec,
            )

        private val inputManager =
            context.getSystemService(Context.INPUT_SERVICE) as InputManager

        private val usbManager =
            context.getSystemService(Context.USB_SERVICE) as UsbManager

        private val _devices = MutableStateFlow<Map<Int, Device>>(emptyMap())
        val devices: StateFlow<Map<Int, Device>> = _devices.asStateFlow()

        @Volatile private var installed = false

        private val disconnectJobs = ConcurrentHashMap<Int, Job>()

        fun install() {
            if (installed) return
            installed = true
            inputManager.registerInputDeviceListener(this, null)
            syncAll()
            btConnections.start { refreshTransports() }
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
            val device = makeRoutedDevice(deviceId, dev)
            _devices.update { map ->
                // A live device is back: drop any stale loader placeholder for the same model so the
                // slot swaps cleanly to the re-enumerated device instead of briefly showing two cards.
                val withoutStalePlaceholder =
                    map.filterNot { (id, d) ->
                        id != deviceId &&
                            (d.transitioning || d.needsReplug) &&
                            !d.isUsbSynthetic &&
                            d.vendorId == device.vendorId &&
                            d.productId == device.productId
                    }
                withoutStalePlaceholder + (deviceId to device)
            }
        }

        private fun makeRoutedDevice(
            deviceId: Int,
            dev: InputDevice,
        ): Device {
            val vid = runCatching { dev.vendorId }.getOrDefault(0)
            val pid = runCatching { dev.productId }.getOrDefault(0)
            val hasGyro = PhysicalMotionProbe.hasGyro(deviceId)
            val hasRumble = probeRumble(dev)
            if (vid != 0 && pid != 0) {
                lastFrameworkCaps[vpKey(vid, pid)] = FrameworkCaps(hasGyro, hasRumble)
            }
            return Device(
                id = deviceId,
                name = dev.name,
                hasGyro = hasGyro,
                hasRumble = hasRumble,
                // A model that just failed a Direct claim re-enumerates with the cause already attached.
                directFailure = directFailed[vpKey(vid, pid)],
                vendorId = vid,
                productId = pid,
                transport = resolveTransport(dev.name, vid, pid),
            )
        }

        private fun probeRumble(dev: InputDevice): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dev.vibratorManager.vibratorIds.isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                dev.vibrator?.hasVibrator() == true
            }

        private fun resolveTransport(
            name: String,
            vendorId: Int,
            productId: Int,
        ): Transport =
            when {
                btConnections.isConnected(name) -> Transport.Bluetooth
                usbManager.deviceList.values.any { it.vendorId == vendorId && it.productId == productId } ->
                    Transport.Usb
                else -> Transport.Bluetooth
            }

        private fun refreshTransports() {
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (d.isUsbSynthetic) d else d.copy(transport = resolveTransport(d.name, d.vendorId, d.productId))
                }
            }
        }

        private val directFailed = ConcurrentHashMap<Int, DirectClaimFailure>()

        private val lastFrameworkCaps = ConcurrentHashMap<Int, FrameworkCaps>()

        fun frameworkCapsFor(
            vendorId: Int,
            productId: Int,
        ): FrameworkCaps? = lastFrameworkCaps[vpKey(vendorId, productId)]

        private fun vpKey(
            vendorId: Int,
            productId: Int,
        ): Int = (vendorId shl 16) or (productId and 0xFFFF)

        // Record why a Direct claim failed: shown on the model's card and consulted so the model is not
        // auto-retried into Direct on the next plug-in (an explicit user pick still claims).
        fun markDirectFailed(
            vendorId: Int,
            productId: Int,
            reason: DirectClaimFailure,
        ) {
            directFailed[vpKey(vendorId, productId)] = reason
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (!d.isUsbSynthetic && d.vendorId == vendorId && d.productId == productId) {
                        d.copy(directFailure = reason)
                    } else {
                        d
                    }
                }
            }
        }

        fun clearDirectFailed(
            vendorId: Int,
            productId: Int,
        ) {
            directFailed.remove(vpKey(vendorId, productId))
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (d.directFailure != null && d.vendorId == vendorId && d.productId == productId) {
                        d.copy(directFailure = null)
                    } else {
                        d
                    }
                }
            }
        }

        fun directFailureFor(
            vendorId: Int,
            productId: Int,
        ): DirectClaimFailure? = directFailed[vpKey(vendorId, productId)]

        // The manager marks a model "transitioning" around a path switch so the disconnect reaper does
        // not silently remove the framework device when claiming force-detaches the kernel HID driver.
        // Instead the slot is held as a loader placeholder until the manager ends the transition.
        private val transitioningModels = ConcurrentHashMap.newKeySet<Int>()

        fun beginModelTransition(
            vendorId: Int,
            productId: Int,
        ) {
            transitioningModels.add(vpKey(vendorId, productId))
        }

        fun endModelTransition(
            vendorId: Int,
            productId: Int,
        ) {
            transitioningModels.remove(vpKey(vendorId, productId))
            // Drop the stale placeholder (loader or needs-replug); a live re-enumerated entry of the
            // same model is neither flag and is left in place.
            _devices.update { map ->
                map.filterNot { (_, d) ->
                    (d.transitioning || d.needsReplug) &&
                        !d.isUsbSynthetic &&
                        d.vendorId == vendorId &&
                        d.productId == productId
                }
            }
        }

        // Settle a held loader placeholder into a visible "needs replug" card (the OS never returned
        // the device). Kept until the device re-enumerates (onInputDeviceAdded) or is unplugged.
        fun markNeedsReplug(
            vendorId: Int,
            productId: Int,
        ) {
            transitioningModels.remove(vpKey(vendorId, productId))
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (d.transitioning && !d.isUsbSynthetic && d.vendorId == vendorId && d.productId == productId) {
                        d.withPlaceholder(placeholderTransition(d.placeholderState(), PlaceholderEvent.MarkNeedsReplug))
                    } else {
                        d
                    }
                }
            }
        }

        // A held synthetic whose return-to-Standard never came back: keep it visible as an actionable
        // "Standard isn't responding" card (toggle stays live) instead of silently re-claiming Direct.
        fun markRestoreStuck(
            vendorId: Int,
            productId: Int,
        ) {
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (d.isUsbSynthetic && d.vendorId == vendorId && d.productId == productId) {
                        d.withPlaceholder(placeholderTransition(d.placeholderState(), PlaceholderEvent.MarkRestoreStuck))
                    } else {
                        d
                    }
                }
            }
        }

        // Back to the held-loader look while a retry waits for the framework device to re-enumerate.
        fun clearRestoreStuck(
            vendorId: Int,
            productId: Int,
        ) {
            _devices.update { map ->
                map.mapValues { (_, d) ->
                    if (d.isUsbSynthetic && d.vendorId == vendorId && d.productId == productId) {
                        d.withPlaceholder(placeholderTransition(d.placeholderState(), PlaceholderEvent.ClearRestoreStuck))
                    } else {
                        d
                    }
                }
            }
        }

        fun setUsbSyntheticTransitioning(
            deviceId: Int,
            value: Boolean,
        ) {
            _devices.update { map ->
                val cur = map[deviceId] ?: return@update map
                // Same idempotent short-circuit as before: if the flag is unchanged, do not re-emit.
                if (cur.transitioning == value) {
                    map
                } else {
                    val next =
                        cur.withPlaceholder(
                            placeholderTransition(cur.placeholderState(), PlaceholderEvent.SetSyntheticTransitioning(value)),
                        )
                    map + (deviceId to next)
                }
            }
        }

        private fun isModelTransitioning(d: Device): Boolean =
            d.vendorId != 0 && d.productId != 0 && vpKey(d.vendorId, d.productId) in transitioningModels

        // Hold a removed framework device as a visible loader placeholder instead of reaping it.
        private fun holdAsTransitioning(deviceId: Int) {
            disconnectJobs.remove(deviceId)?.cancel()
            _devices.update { map ->
                val cur = map[deviceId] ?: return@update map
                val next = cur.withPlaceholder(placeholderTransition(cur.placeholderState(), PlaceholderEvent.HoldAsTransitioning))
                map + (deviceId to next)
            }
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            val current = _devices.value[deviceId] ?: return
            if (isModelTransitioning(current)) holdAsTransitioning(deviceId) else scheduleDisconnect(current)
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            val dev = InputDevice.getDevice(deviceId)
            if (dev == null || !isGamepad(dev)) {
                _devices.value[deviceId]?.let { cur ->
                    if (isModelTransitioning(cur)) holdAsTransitioning(deviceId) else scheduleDisconnect(cur)
                }
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
                _devices.update { it + (deviceId to makeRoutedDevice(deviceId, dev)) }
            }
        }

        private fun scheduleDisconnect(device: Device) {
            disconnectJobs.remove(device.id)?.cancel()
            disconnectJobs[device.id] =
                scope.launch {
                    var remaining = DISCONNECT_GRACE_SEC
                    while (isActive && remaining > 0) {
                        val snapshot =
                            _devices.updateAndGet { map ->
                                val cur = map[device.id] ?: return@updateAndGet map
                                map + (device.id to cur.copy(disconnectingTimeLeftSec = remaining))
                            }
                        if (device.id !in snapshot) return@launch
                        delay(1000L)
                        remaining -= 1
                    }
                    _devices.update { it - device.id }
                    disconnectJobs.remove(device.id)
                }
        }

        private fun cancelDisconnect(deviceId: Int) {
            disconnectJobs.remove(deviceId)?.cancel()
            _devices.update { map ->
                val cur = map[deviceId] ?: return@update map
                if (cur.isDisconnecting) map + (deviceId to cur.copy(disconnectingTimeLeftSec = null)) else map
            }
        }

        fun addUsbSynthetic(
            deviceId: Int,
            name: String,
            hasGyro: Boolean,
            pollRateHz: Int,
            vendorId: Int,
            productId: Int,
        ) {
            _devices.update { map ->
                map +
                    (
                        deviceId to
                            Device(
                                id = deviceId,
                                name = name,
                                hasGyro = hasGyro,
                                isUsbSynthetic = true,
                                pollRateHz = pollRateHz,
                                vendorId = vendorId,
                                productId = productId,
                            )
                    )
            }
        }

        fun removeUsbSynthetic(deviceId: Int) {
            _devices.update { it - deviceId }
        }

        // Drop a framework device a Direct claim just superseded (its interface was stolen) instead of
        // leaving it in the 5s disconnect grace, where it would collide with the framework that
        // re-enumerates on a switch back to Standard and briefly show two cards for one controller.
        fun forgetSupersededFramework(deviceId: Int) {
            disconnectJobs.remove(deviceId)?.cancel()
            _devices.update { it - deviceId }
        }

        fun updateMeasuredPollRate(
            deviceId: Int,
            rateHz: Int,
        ) {
            _devices.update { map ->
                val cur = map[deviceId] ?: return@update map
                if (cur.pollRateHz == rateHz) map else map + (deviceId to cur.copy(pollRateHz = rateHz))
            }
        }

        // Pushed at device-add time so the native input thread never crosses back into Java per event.
        private fun pushDeadzones(dev: InputDevice) {
            val src = InputDevice.SOURCE_JOYSTICK
            native.setDeviceDeadzones(
                dev.id,
                dev.getMotionRange(MotionEvent.AXIS_X, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Y, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_Z, src)?.flat ?: 0f,
                dev.getMotionRange(MotionEvent.AXIS_RZ, src)?.flat ?: 0f,
            )
            val vid = runCatching { dev.vendorId }.getOrDefault(0)
            native.setDeviceQuirk(dev.id, resolveGamepadQuirk(vid))
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

        companion object {
            private const val TAG = "PhysicalGamepadRegistry"

            // Covers a USB cable jiggle / re-enumeration without holding state long enough that a real removal feels stuck.
            private const val DISCONNECT_GRACE_SEC = 5

            fun isSyntheticId(deviceId: Int): Boolean = deviceId < 0
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

// Pure projection of a device's transient ("placeholder") fields plus the identity flag that gates
// them. Mutators compute the next value via placeholderTransition and copy it back onto the Device
// unchanged. Keeping the booleans (not a single enum) is deliberate: transitioning, needsReplug,
// restoreStuck and the disconnect countdown are independent fields on Device that consumers read
// directly, so collapsing them would be lossy.
internal data class PlaceholderState(
    val transitioning: Boolean,
    val needsReplug: Boolean,
    val restoreStuck: Boolean,
    val disconnectingTimeLeftSec: Int?,
    val isUsbSynthetic: Boolean,
)

internal sealed interface PlaceholderEvent {
    // A removed framework device held as a loader placeholder rather than reaped.
    data object HoldAsTransitioning : PlaceholderEvent

    // setUsbSyntheticTransitioning(value): flips only the loader flag.
    data class SetSyntheticTransitioning(
        val value: Boolean,
    ) : PlaceholderEvent

    // The OS never returned a held framework device: settle the loader into a replug card.
    data object MarkNeedsReplug : PlaceholderEvent

    // A held synthetic's return-to-Standard never re-enumerated: keep it actionable.
    data object MarkRestoreStuck : PlaceholderEvent

    // Retry: drop the stuck look and go back to the held-loader look.
    data object ClearRestoreStuck : PlaceholderEvent
}

// Pure (state, event) -> state for the per-device placeholder phase. No StateFlow, native, or map
// access. Each imperative mutator matches the same device subset it did before and applies this
// result; events are never dispatched to a device the mutator's filter excludes.
internal fun placeholderTransition(
    current: PlaceholderState,
    event: PlaceholderEvent,
): PlaceholderState =
    when (event) {
        // Entering the loader look also clears any in-flight disconnect countdown, exactly as
        // holdAsTransitioning did inline.
        PlaceholderEvent.HoldAsTransitioning ->
            current.copy(transitioning = true, disconnectingTimeLeftSec = null)

        is PlaceholderEvent.SetSyntheticTransitioning ->
            current.copy(transitioning = event.value)

        // Contradictory-combo guard: a device cannot be both transitioning and needsReplug, so
        // settling to needsReplug clears the loader flag.
        PlaceholderEvent.MarkNeedsReplug ->
            current.copy(transitioning = false, needsReplug = true)

        // restoreStuck only applies to a synthetic; on anything else this is a no-op. Callers already
        // filter to synthetics, the guard makes the invariant explicit in the reducer.
        PlaceholderEvent.MarkRestoreStuck ->
            if (current.isUsbSynthetic) {
                current.copy(transitioning = false, restoreStuck = true)
            } else {
                current
            }

        PlaceholderEvent.ClearRestoreStuck ->
            if (current.isUsbSynthetic) {
                current.copy(restoreStuck = false, transitioning = true)
            } else {
                current
            }
    }
