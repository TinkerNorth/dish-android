// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.source.lights

import android.content.Context
import android.hardware.input.InputManager
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the light bar of a framework (Standard / Bluetooth) DualShock 4 or DualSense through
 * `android.hardware.lights` (API 31+). A boundary to the input service behind a narrow surface: it
 * holds no capability opinion (the composer decides where a light bar is advertised) and turns a
 * color into an open session plus a `requestLights` call.
 *
 * A session is opened lazily on the first color for a device and reused for later colors; the strong
 * reference in [held] keeps it alive for the whole stream (its finalizer would close it, turning the
 * bar off, if it were collected). Closing a session writes color 0 to the light it touched, so the
 * bar goes dark: that is the honest end state when a stream stops, since the API offers no "return
 * to the pad's own color". A session is only closed once it has landed at least one color (tracked
 * by [Held.lastArgb]), because closing one that never requested throws in the service. Identical
 * colors are coalesced so an unchanged frame never costs a binder round-trip.
 *
 * The Android I/O sits behind [Lightbars] so the open-once / reuse / coalesce / close lifecycle is
 * testable without the framework. The light object is re-resolved by rule on every write (see
 * [AndroidLightbar]), so a light id the service reassigns when the merged input device gains a
 * sub-device cannot leave the bar stuck on a stale id. Designed so a player-id light could be added
 * later as a second addressed light without restructuring; only the light bar is wired today.
 */
@Singleton
class FrameworkLightGateway
    internal constructor(
        private val lightbars: Lightbars,
    ) {
        @Inject
        constructor(
            @ApplicationContext context: Context,
        ) : this(AndroidLightbars(context))

        // The input-service boundary, faked in tests. `open` returns null when the device has no
        // light bar the app can drive (or below API 31); `write` returns false when the request did
        // not land (the device left, the light vanished, or the session went dead), so the caller
        // drops the session.
        internal interface Lightbars {
            fun open(deviceId: Int): Lightbar?
        }

        internal interface Lightbar {
            fun write(argb: Int): Boolean

            fun close()
        }

        private class Held(
            val lightbar: Lightbar,
        ) {
            // Null until a color has successfully landed; also the coalesce key. A session that never
            // landed a color must not be closed (that throws in the service), only dropped.
            var lastArgb: Int? = null
        }

        // Small map, touched from the feedback receive threads and the lifecycle release hooks; all
        // access is under [lock] so a device's open / write / close can never interleave with itself.
        private val held = HashMap<Int, Held>()
        private val lock = Any()

        /**
         * Paint [deviceId]'s light bar the given color. A no-op for a device with no drivable light
         * bar; redundant colors are dropped so an unchanged frame never reaches the input service.
         */
        fun setColor(
            deviceId: Int,
            r: Int,
            g: Int,
            b: Int,
        ) {
            // Alpha is brightness on the native side (it scales the LEDs and drives the global
            // channel), so full opacity is what makes the requested color land at full strength.
            val argb = 0xFF000000.toInt() or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
            synchronized(lock) {
                val current = held[deviceId]
                if (current != null && current.lastArgb == argb) return
                val holder = current ?: lightbars.open(deviceId)?.let { Held(it).also { h -> held[deviceId] = h } } ?: return
                if (holder.lightbar.write(argb)) {
                    holder.lastArgb = argb
                } else {
                    dropLocked(deviceId, holder)
                }
            }
        }

        /** Give [deviceId]'s light bar back to the system (turning it off). Idempotent. */
        fun release(deviceId: Int) {
            synchronized(lock) { held.remove(deviceId)?.let { closeLocked(it) } }
        }

        /** Release every light bar, e.g. when physical-slot streaming stops process-wide. */
        fun releaseAll() {
            synchronized(lock) {
                held.values.forEach { closeLocked(it) }
                held.clear()
            }
        }

        // The write said the bar is gone. Forget it either way; close it only if it had landed a
        // color, since closing an un-requested session throws (its finalizer reclaims that one).
        private fun dropLocked(
            deviceId: Int,
            holder: Held,
        ) {
            held.remove(deviceId)
            if (holder.lastArgb != null) holder.lightbar.close()
        }

        private fun closeLocked(holder: Held) {
            if (holder.lastArgb != null) holder.lightbar.close()
        }
    }

private class AndroidLightbars(
    context: Context,
) : FrameworkLightGateway.Lightbars {
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager

    override fun open(deviceId: Int): FrameworkLightGateway.Lightbar? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return openSession(deviceId)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun openSession(deviceId: Int): FrameworkLightGateway.Lightbar? {
        val device = inputManager.getInputDevice(deviceId) ?: return null
        // Confirm a bar exists before opening, so a pad with none never holds an idle session.
        if (lightbarOf(device) == null) return null
        val session = lightCall("openSession", deviceId) { device.lightsManager.openSession() } ?: return null
        return AndroidLightbar(inputManager, deviceId, session)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class AndroidLightbar(
    private val inputManager: InputManager,
    private val deviceId: Int,
    private val session: LightsManager.LightsSession,
) : FrameworkLightGateway.Lightbar {
    override fun write(argb: Int): Boolean {
        // Re-resolve every write: the service reassigns light ids when the merged device gains a
        // sub-device, and a stale id is silently ignored, so a cached light could go dead-quiet.
        val device = inputManager.getInputDevice(deviceId) ?: return false
        val light = lightbarOf(device) ?: return false
        val request = LightsRequest.Builder().addLight(light, LightState.Builder().setColor(argb).build()).build()
        return lightCall("requestLights", deviceId) { session.requestLights(request) } != null
    }

    override fun close() {
        lightCall("close", deviceId) { session.close() }
    }
}

// The lights API throws IllegalArgumentException (a request after close) and IllegalStateException
// (a session that is no longer registered) across the binder; either means the bar is unreachable
// now, so the caller drops the session instead of taking the stream down. Both are caught by name
// rather than as RuntimeException, which the detekt rules forbid.
@RequiresApi(Build.VERSION_CODES.S)
private inline fun <T> lightCall(
    op: String,
    deviceId: Int,
    block: () -> T,
): T? =
    try {
        block()
    } catch (e: IllegalStateException) {
        Log.w(LIGHT_TAG, "$op failed for device $deviceId: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(LIGHT_TAG, "$op failed for device $deviceId: ${e.message}")
        null
    }

private const val LIGHT_TAG = "FrameworkLightGateway"
