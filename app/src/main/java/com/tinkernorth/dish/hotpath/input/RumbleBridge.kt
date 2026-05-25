// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.core.jni.SatelliteNative

// All rumble routed to phone vibrator by design; no fallback to physical pad actuators.
object RumbleBridge {
    init {
        System.loadLibrary("satellite")
    }

    @Volatile private var vibratorManager: VibratorManager? = null

    @Volatile private var vibrator: Vibrator? = null

    // Must run from a JVM call so the app classloader is on the stack (FindClass in JNI_OnLoad would fail).
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
        } else {
            @Suppress("DEPRECATION")
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        }
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    @JvmStatic
    fun dispatchRumble(
        @Suppress("UNUSED_PARAMETER") sessionHandle: Int,
        @Suppress("UNUSED_PARAMETER") controllerIndex: Int,
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Int,
    ) {
        if (durationMs == 0 || (strongMagnitude == 0 && weakMagnitude == 0)) {
            cancelAll()
            return
        }
        // Bound worst case at "the user's wrist survives one bad packet" from a buggy/malicious satellite.
        val safeDuration = durationMs.coerceIn(1, 1500).toLong()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dispatchApi31(strongMagnitude, weakMagnitude, safeDuration)
        } else {
            dispatchLegacy(strongMagnitude, weakMagnitude, safeDuration)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun dispatchApi31(
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Long,
    ) {
        val mgr = vibratorManager ?: return
        val ids = mgr.vibratorIds
        if (ids.isEmpty()) {
            return
        }
        val combinedBuilder = CombinedVibration.startParallel()
        val strongAmp = magnitudeTo255(strongMagnitude)
        val weakAmp = magnitudeTo255(weakMagnitude)
        if (strongAmp > 0) {
            combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, strongAmp))
        }
        if (ids.size >= 2 && weakAmp > 0) {
            combinedBuilder.addVibrator(ids[1], VibrationEffect.createOneShot(durationMs, weakAmp))
        } else if (ids.size < 2 && weakAmp > 0 && strongAmp == 0) {
            // Single actuator + weak-only: still drive ids[0], else mgr.vibrate on an empty combination is a silent no-op.
            combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, weakAmp))
        }
        try {
            mgr.vibrate(combinedBuilder.combine())
        } catch (_: IllegalStateException) {
        }
    }

    private fun dispatchLegacy(
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Long,
    ) {
        val v = vibrator ?: return
        val peak = maxOf(strongMagnitude, weakMagnitude)
        val amp = magnitudeTo255(peak)
        if (amp == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private fun cancelAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager?.cancel()
        } else {
            vibrator?.cancel()
        }
    }

    private fun magnitudeTo255(magnitude: Int): Int = rumbleMagnitudeTo255(magnitude)
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
