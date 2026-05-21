// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.hotpath.input

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import com.tinkernorth.dish.core.jni.SatelliteNative

/**
 * Java-side dispatch target for satellite → phone rumble events.
 *
 * The native receive loop (see `satellite_jni.cpp::receiveAck`) calls
 * [dispatchRumble] from the same JVM-attached thread that called into JNI
 * (Kotlin Dispatchers.IO via [SatelliteNative.receiveAck]). That keeps the
 * call site simple — no AttachCurrentThread, no dispatcher worker thread —
 * and means rumble latency is bounded by the receive-loop's poll interval
 * (currently 500 ms recv timeout, much shorter when a packet is actually
 * available).
 *
 * Per the project decision, **all rumble is routed to the phone's vibrator**
 * — there is no fallback path that tries to actuate a connected physical
 * gamepad via [android.view.InputDevice.getVibratorManager]. Letting the
 * virtual on-screen gamepad / phone body handle every rumble keeps the
 * actuation single-rooted and avoids the "which device should buzz?"
 * decision a player paired with a physical pad would otherwise have to make.
 *
 * The class name and `dispatchRumble` static signature are referenced by
 * `nativeInstall` in `satellite_jni.cpp` — keep them in sync.
 */
object RumbleBridge {
    init {
        // Defensive: the .so is normally already loaded via SatelliteNative,
        // but install() may be called before any other Kotlin entry point
        // touches that singleton (DishApplication.onCreate runs early).
        System.loadLibrary("satellite")
    }

    @Volatile private var vibratorManager: VibratorManager? = null

    @Volatile private var vibrator: Vibrator? = null

    /**
     * Wire up the bridge once the application context is available. Called
     * from [com.tinkernorth.dish.DishApplication.onCreate]. Also registers
     * this class with the native layer — must run from a JVM call so the
     * app classloader is on the stack (FindClass in JNI_OnLoad would fail).
     *
     * The native side caches the resolved class + method id, so re-invoking
     * `install` is safe but redundant.
     */
    fun install(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorManager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager?
        } else {
            // Pre-API-31 phones expose the legacy single-actuator service.
            // Functionally identical from the user's wrist; it just can't
            // route strong vs weak motors independently.
            @Suppress("DEPRECATION")
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        }
        nativeInstall()
    }

    @JvmStatic
    private external fun nativeInstall()

    /**
     * Wire-format rumble event from the satellite. Magnitudes are 0..65535
     * (XInput scale) and `durationMs == 0` means "stop / continuous-clear".
     *
     * The combined vibration is split across the device's actuators on
     * API 31+ (`VibratorManager`) so a phone with two motors gets
     * proportional drive on each. On older phones we collapse the two
     * magnitudes into a single peak intensity for the legacy [Vibrator]
     * service.
     *
     * `controllerIndex` is currently ignored: each WiFi connection is
     * single-controller, so every rumble for this session targets the
     * phone unconditionally.
     */
    @JvmStatic
    fun dispatchRumble(
        @Suppress("UNUSED_PARAMETER") sessionHandle: Int,
        @Suppress("UNUSED_PARAMETER") controllerIndex: Int,
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Int,
    ) {
        // Stop / no-op packet: cancel any in-flight vibration so we don't
        // outlast the satellite-side game.
        if (durationMs == 0 || (strongMagnitude == 0 && weakMagnitude == 0)) {
            cancelAll()
            return
        }
        // Clamp the wire-format duration to the wire-side refresh deadline
        // so a hung satellite doesn't strand a multi-second buzz on the
        // device. The satellite stamps `wireDurationMs = 500` by default
        // (see `SessionService::handleRumbleFromBackend`), but a malicious
        // or buggy server could send anything; protect the user.
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
            // Phone has no haptics actuator at all. Nothing to do.
            return
        }
        // Map the satellite's two-motor model onto whatever actuators this
        // device exposes. Most phones have one general-purpose actuator;
        // foldables / gaming phones may have two and we use them both.
        //
        // Magnitude is normalized to the VibrationEffect 1..255 range.
        val combinedBuilder = CombinedVibration.startParallel()
        val strongAmp = magnitudeTo255(strongMagnitude)
        val weakAmp = magnitudeTo255(weakMagnitude)
        // ids[0] gets the strong motor; ids[1] (if present) gets weak.
        if (strongAmp > 0) {
            combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, strongAmp))
        }
        if (ids.size >= 2 && weakAmp > 0) {
            combinedBuilder.addVibrator(ids[1], VibrationEffect.createOneShot(durationMs, weakAmp))
        } else if (ids.size < 2 && weakAmp > 0 && strongAmp == 0) {
            // Single-actuator phone where only the weak motor is non-zero —
            // still drive that actuator, otherwise `mgr.vibrate` on an empty
            // CombinedVibration is a silent no-op.
            combinedBuilder.addVibrator(ids[0], VibrationEffect.createOneShot(durationMs, weakAmp))
        }
        // CombinedVibration.startParallel() always returns a valid combination
        // — but combine() throws if no per-vibrator effect was added (both
        // magnitudes zero), which we've already early-returned out of above.
        try {
            mgr.vibrate(combinedBuilder.combine())
        } catch (_: IllegalStateException) {
            // Empty combination — fine, treat as a no-op.
        }
    }

    private fun dispatchLegacy(
        strongMagnitude: Int,
        weakMagnitude: Int,
        durationMs: Long,
    ) {
        val v = vibrator ?: return
        // Pre-API-31 has only one actuator and one magnitude. Use the peak
        // of the two motors so the player feels the louder of the two.
        val peak = maxOf(strongMagnitude, weakMagnitude)
        val amp = magnitudeTo255(peak)
        if (amp == 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, amp))
        } else {
            // API < 26: no amplitude control, fixed-strength buzz of the
            // requested duration. Almost no devices in our supported range
            // hit this branch.
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

    /**
     * Map a 0..65535 wire-format magnitude into VibrationEffect's 1..255
     * amplitude range. Returns 0 only when the input is exactly 0 — even
     * tiny non-zero magnitudes become a barely-perceptible buzz so the
     * player gets the same on/off response they'd get from a physical pad.
     */
    private fun magnitudeTo255(magnitude: Int): Int = rumbleMagnitudeTo255(magnitude)
}

// ── Pure helpers (extracted for unit testing) ────────────────────────────────
// The dispatch path inside RumbleBridge needs system services (Vibrator,
// VibratorManager) and so can only run on a real Android device. The two
// pure transformations below are what actually shape the user's wrist
// experience, and they're trivially testable in isolation.

/**
 * Scale a wire-format magnitude (0..65535, XInput convention) into a
 * VibrationEffect amplitude (0..255). Returns 0 only for exact zero;
 * otherwise clamps into the 1..255 range so even tiny magnitudes produce
 * a perceptible buzz.
 */
internal fun rumbleMagnitudeTo255(magnitude: Int): Int {
    val clamped = magnitude.coerceIn(0, 65535)
    if (clamped == 0) return 0
    // 65535 → 255; 1 → 1 (rounded up).
    val scaled = (clamped * 255 + 32767) / 65535
    return scaled.coerceIn(1, 255)
}

/**
 * Apply the [RumbleBridge] safety clamp to an incoming wire-format
 * `durationMs`. The wire format is 16-bit unsigned, but a buggy or
 * malicious satellite could strand a multi-second buzz on the device;
 * we cap it to 1500 ms to bound the worst case at "the user's wrist
 * survives one bad packet". Returns 0 when the input was 0 (the
 * "stop / no-op" sentinel).
 */
internal fun rumbleSafeDurationMs(durationMs: Int): Int {
    if (durationMs == 0) return 0
    return durationMs.coerceIn(1, 1500)
}
