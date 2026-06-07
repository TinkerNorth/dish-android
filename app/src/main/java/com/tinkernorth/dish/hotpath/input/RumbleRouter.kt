// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import com.tinkernorth.dish.ui.main.VIRTUAL_SLOT_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RumbleTarget {
    object Phone : RumbleTarget

    data class Framework(
        val deviceId: Int,
    ) : RumbleTarget

    data class DirectUsb(
        val deviceId: Int,
    ) : RumbleTarget

    object None : RumbleTarget
}

@Singleton
class RumbleRouter
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val satellite: SatelliteConnectionManager,
        private val native: PhysicalInputNative,
        private val scope: CoroutineScope,
    ) {
        // A claimed USB pad has no oneshot duration, so a dropped session could leave it buzzing;
        // each rumble schedules a stop at the clamped duration, cancelled by the next rumble.
        private val usbStopJobs = ConcurrentHashMap<Int, Job>()

        private val phoneVibratorManager: VibratorManager? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
            } else {
                null
            }

        @Suppress("DEPRECATION")
        private val phoneVibrator: Vibrator? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                null
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
            }

        fun dispatch(
            sessionHandle: Int,
            controllerIndex: Int,
            strongMagnitude: Int,
            weakMagnitude: Int,
            durationMs: Int,
        ) {
            val target = resolveTarget(sessionHandle, controllerIndex)
            if (target is RumbleTarget.None) return
            if (durationMs == 0 || (strongMagnitude == 0 && weakMagnitude == 0)) {
                cancel(target)
                return
            }
            val safeDuration = rumbleSafeDurationMs(durationMs).toLong()
            actuate(target, strongMagnitude, weakMagnitude, safeDuration)
        }

        private fun resolveTarget(
            sessionHandle: Int,
            controllerIndex: Int,
        ): RumbleTarget {
            if (sessionHandle < 0) return RumbleTarget.None
            val conn =
                satellite.connections.value.values
                    .firstOrNull { it.handle == sessionHandle }
                    ?: return RumbleTarget.None
            val slotId = resolveSlotId(conn.slots.value, controllerIndex) ?: return RumbleTarget.None
            return classifyTarget(slotId)
        }

        private fun actuate(
            target: RumbleTarget,
            strongMagnitude: Int,
            weakMagnitude: Int,
            durationMs: Long,
        ) {
            when (target) {
                RumbleTarget.Phone ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        phoneVibratorManager?.let { vibrateManager(it, strongMagnitude, weakMagnitude, durationMs) }
                    } else {
                        phoneVibrator?.let { vibrateSingle(it, strongMagnitude, weakMagnitude, durationMs) }
                    }
                is RumbleTarget.Framework -> {
                    val dev = InputDevice.getDevice(target.deviceId) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        vibrateManager(dev.vibratorManager, strongMagnitude, weakMagnitude, durationMs)
                    } else {
                        @Suppress("DEPRECATION")
                        vibrateSingle(dev.vibrator, strongMagnitude, weakMagnitude, durationMs)
                    }
                }
                is RumbleTarget.DirectUsb -> {
                    usbStopJobs.remove(target.deviceId)?.cancel()
                    native.sendUsbRumble(target.deviceId, strongMagnitude, weakMagnitude)
                    val job =
                        scope.launch {
                            delay(durationMs)
                            native.sendUsbRumble(target.deviceId, 0, 0)
                        }
                    usbStopJobs[target.deviceId] = job
                    job.invokeOnCompletion { usbStopJobs.remove(target.deviceId, job) }
                }
                RumbleTarget.None -> Unit
            }
        }

        private fun cancel(target: RumbleTarget) {
            when (target) {
                RumbleTarget.Phone ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        phoneVibratorManager?.cancel()
                    } else {
                        phoneVibrator?.cancel()
                    }
                is RumbleTarget.Framework -> {
                    val dev = InputDevice.getDevice(target.deviceId) ?: return
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        dev.vibratorManager.cancel()
                    } else {
                        @Suppress("DEPRECATION")
                        dev.vibrator.cancel()
                    }
                }
                is RumbleTarget.DirectUsb -> {
                    usbStopJobs.remove(target.deviceId)?.cancel()
                    native.sendUsbRumble(target.deviceId, 0, 0)
                }
                RumbleTarget.None -> Unit
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun vibrateManager(
            mgr: VibratorManager,
            strongMagnitude: Int,
            weakMagnitude: Int,
            durationMs: Long,
        ) {
            val ids = mgr.vibratorIds
            if (ids.isEmpty()) return
            val combinedBuilder = CombinedVibration.startParallel()
            val strongAmp = rumbleMagnitudeTo255(strongMagnitude)
            val weakAmp = rumbleMagnitudeTo255(weakMagnitude)
            if (strongAmp > 0) {
                combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, strongAmp))
            }
            if (ids.size >= 2 && weakAmp > 0) {
                combinedBuilder.addVibrator(ids[1], VibrationEffect.createOneShot(durationMs, weakAmp))
            } else if (ids.size < 2 && weakAmp > 0 && strongAmp == 0) {
                // Single actuator + weak-only: still drive ids[0], else vibrate on an empty combination is a no-op.
                combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, weakAmp))
            }
            try {
                mgr.vibrate(combinedBuilder.combine())
            } catch (_: IllegalStateException) {
            }
        }

        private fun vibrateSingle(
            vibrator: Vibrator,
            strongMagnitude: Int,
            weakMagnitude: Int,
            durationMs: Long,
        ) {
            if (!vibrator.hasVibrator()) return
            val amp = rumbleMagnitudeTo255(maxOf(strongMagnitude, weakMagnitude))
            if (amp == 0) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amp))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

internal fun resolveSlotId(
    slots: Map<String, SatelliteConnection.SlotBinding>,
    controllerIndex: Int,
): String? = slots.entries.firstOrNull { it.value.controllerIndex == controllerIndex }?.key

internal fun classifyTarget(slotId: String): RumbleTarget {
    if (slotId == VIRTUAL_SLOT_ID) return RumbleTarget.Phone
    val id = slotId.toIntOrNull() ?: return RumbleTarget.None
    return if (id < 0) RumbleTarget.DirectUsb(id) else RumbleTarget.Framework(id)
}

// Returns 0 only for exact zero; tiny magnitudes clamp to 1 so on/off response matches a physical pad.
internal fun rumbleMagnitudeTo255(magnitude: Int): Int {
    val clamped = magnitude.coerceIn(0, 65535)
    if (clamped == 0) return 0
    val scaled = (clamped * 255 + 32767) / 65535
    return scaled.coerceIn(1, 255)
}

// Cap at 1500ms so a buggy/malicious satellite can't strand a multi-second buzz on the device.
internal fun rumbleSafeDurationMs(durationMs: Int): Int {
    if (durationMs == 0) return 0
    return durationMs.coerceIn(1, 1500)
}
