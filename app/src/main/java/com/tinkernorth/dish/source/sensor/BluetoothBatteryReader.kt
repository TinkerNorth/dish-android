// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.sensor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * API < 31 fallback for reading a *wireless gamepad's own* battery level.
 *
 * `InputDevice.getBatteryState()` only exists on API 31+ ([Build.VERSION_CODES.S]).
 * On API 24–30 a Bluetooth gamepad's battery is invisible through the public
 * `InputDevice` API, so [PhysicalBatterySource] would otherwise fall all the
 * way back to reporting the *phone's* battery as the controller's — wrong for
 * any wireless pad.
 *
 * `BluetoothDevice` has carried a `getBatteryLevel()` method since API 18, but
 * it is `@hide` — not in the public SDK. This reader calls it by reflection.
 * Reflection on a hidden API is inherently best-effort: AOSP forks and some
 * OEM ROMs rename or remove it, and API 28+ greylisting can block the call. So
 * every step is guarded — a failure anywhere returns `null` and the caller
 * falls through to the phone-battery path exactly as before. **No crash, no
 * regression** — this is purely additive coverage.
 *
 * On API 31+ this class is never consulted; [PhysicalBatterySource] uses the
 * public `getBatteryState()` there.
 *
 * Matching: a Bluetooth gamepad's `InputDevice.getName()` is, in practice, the
 * same string as the bonded `BluetoothDevice.getName()`. [readLevel] therefore
 * matches by name via the pure [matchBondedDeviceName] helper (unit-tested);
 * a USB-wired pad simply won't match any bonded device and yields `null`.
 */
class BluetoothBatteryReader(
    private val context: Context,
) {
    /**
     * `BluetoothDevice.getBatteryLevel()` resolved once by reflection, or null
     * if it could not be resolved on this ROM. The triple-state cache (null vs
     * resolved) is collapsed into [resolved] so a missing method is not
     * re-looked-up on every poll.
     */
    private var batteryLevelMethod: java.lang.reflect.Method? = null

    @Volatile private var resolved = false

    /**
     * Battery level (0..100) for the bonded Bluetooth device whose name equals
     * [inputDeviceName], or null when there is no match, the hidden API is
     * unavailable, or the device reports an unknown level.
     *
     * Best-effort: any reflection / permission / ROM failure is swallowed and
     * surfaces as null so the caller falls back to the phone battery.
     */
    fun readLevel(inputDeviceName: String): Int? {
        val device = bondedDeviceNamed(inputDeviceName) ?: return null
        val method = batteryLevelMethod() ?: return null
        return try {
            val raw = method.invoke(device) as? Int ?: return null
            // getBatteryLevel() returns -1 (UNKNOWN) or 0..100. Anything else
            // is a ROM quirk — reject it rather than emit a bogus wire value.
            raw.takeIf { it in 0..100 }
        } catch (e: ReflectiveOperationException) {
            Log.d(TAG, "getBatteryLevel reflection failed: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            // Method.invoke throws IllegalArgumentException (a RuntimeException,
            // not a ReflectiveOperationException) for an arg/receiver mismatch
            // — a hidden-API ROM quirk. Best-effort: swallow and fall back.
            Log.d(TAG, "getBatteryLevel rejected the call: ${e.message}")
            null
        } catch (e: SecurityException) {
            // BLUETOOTH / BLUETOOTH_CONNECT not granted, or hidden-API blocked.
            Log.d(TAG, "getBatteryLevel blocked: ${e.message}")
            null
        }
    }

    /** The bonded [BluetoothDevice] whose name matches [name], or null. */
    private fun bondedDeviceNamed(name: String): BluetoothDevice? {
        val bonded =
            try {
                adapter()?.bondedDevices ?: return null
            } catch (e: SecurityException) {
                // Missing BLUETOOTH_CONNECT — cannot enumerate bonds.
                Log.d(TAG, "bondedDevices blocked: ${e.message}")
                return null
            }
        val byName: Map<String, BluetoothDevice> =
            bonded
                .mapNotNull { dev ->
                    val n =
                        try {
                            dev.name
                        } catch (_: SecurityException) {
                            null
                        }
                    n?.let { it to dev }
                }.toMap()
        val matchName = matchBondedDeviceName(name, byName.keys) ?: return null
        return byName[matchName]
    }

    /** The default [BluetoothAdapter], or null when the device has no Bluetooth. */
    private fun adapter(): BluetoothAdapter? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }

    /** Lazily resolve `BluetoothDevice.getBatteryLevel()` exactly once. */
    private fun batteryLevelMethod(): java.lang.reflect.Method? {
        if (resolved) return batteryLevelMethod
        batteryLevelMethod =
            try {
                BluetoothDevice::class.java.getMethod("getBatteryLevel")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "getBatteryLevel not present on this ROM: ${e.message}")
                null
            }
        resolved = true
        return batteryLevelMethod
    }

    companion object {
        private const val TAG = "BluetoothBatteryReader"

        /**
         * Pure name-match: the bonded-device name equal to [inputDeviceName],
         * or null if none. Split out so the matching can be unit-tested
         * without a live Bluetooth stack.
         *
         * Match is exact and case-insensitive — an `InputDevice` for a
         * Bluetooth gamepad and its bonded `BluetoothDevice` carry the same
         * vendor name string, occasionally differing only in letter case.
         */
        fun matchBondedDeviceName(
            inputDeviceName: String,
            bondedNames: Collection<String>,
        ): String? {
            val target = inputDeviceName.trim()
            if (target.isEmpty()) return null
            return bondedNames.firstOrNull { it.trim().equals(target, ignoreCase = true) }
        }
    }
}
