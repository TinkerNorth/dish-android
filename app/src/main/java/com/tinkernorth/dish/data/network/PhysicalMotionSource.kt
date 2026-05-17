// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.data.repository.PhysicalGamepadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Forwards a *physical* gamepad's own IMU (gyro + accelerometer) to the
 * satellite it is routed to — the physical-controller half of roadmap
 * Task 1.1, mirroring what [PhoneMotionSource] does for the on-screen touch
 * controller.
 *
 * ### Why this exists
 *
 * Without this source a physical gamepad bound *through* dish-android forwards
 * **zero motion** even when it has a gyroscope: only the phone's own sensors
 * ([PhoneMotionSource]) were ever wired up. A DualSense / 8BitDo Pro / Joy-Con
 * paired to the phone and routed to a satellite would stream buttons and
 * sticks but silently drop gyro aim.
 *
 * ### The API
 *
 * The roadmap's first sketch suggested `InputDevice.getMotionRange(AXIS_GAS /
 * RX / RY / RZ)` — but those axes are triggers and the right stick, **not** an
 * IMU. The correct modern API is [InputDevice.getSensorManager] (API 31+,
 * [Build.VERSION_CODES.S]): it returns a [SensorManager] scoped to that one
 * input device, exposing the pad's own `TYPE_GYROSCOPE` / `TYPE_ACCELEROMETER`.
 * On API < 31 there is no per-device sensor surface at all, so this source is
 * API-gated off and physical-pad motion is simply unavailable there (the
 * phone-overlay [PhoneMotionSource] still works on every API level).
 *
 * ### Pipeline
 *
 * Per reachable pad: `InputDevice.getSensorManager()` (rad/s, m/s², the
 * controller body frame) → [MotionScaling] (DSU int16) → [MotionRateLimiter]
 * (≤ 250 Hz gate, keyed per device) → [SatelliteConnection.sendMotion].
 *
 * **No axis remap.** Unlike [PhoneMotionSource], which rotates the phone's
 * sensor frame into the landscape screen frame, a game controller's IMU is
 * already reported in the controller body frame — and Android documents that
 * frame for game controllers as right-handed `+X` right / `+Y` up / `+Z`
 * toward the player, which is exactly the wire convention. So the conversion
 * is a straight scale with an identity remap; the receiver does no rotation.
 *
 * ### Lifecycle
 *
 * Wiring mirrors [PhysicalBatterySource] / [PhysicalSlotBindingObserver]: a
 * process-scoped `@Singleton` observing the cross-product of
 * [PhysicalGamepadRegistry] devices, [ConnectionHub] bindings + liveness, and
 * each [SatelliteConnection]'s slot table. When a pad becomes reachable its
 * gyro/accel listeners are registered; when it drops off — unbound, the
 * satellite disconnects, or the app backgrounds ([onStop]) — they are
 * unregistered. Listener registration is strictly scoped to the bound-pad
 * lifecycle, so a gyro listener is never left running on a pad that is no
 * longer streaming. Gyro listeners are a measurable battery cost; leaking one
 * would drain both the pad and the phone.
 *
 * Motion is a Bluetooth-HID non-feature (no `MSG_MOTION` channel), so — like
 * [PhysicalBatterySource] — only SATELLITE-bound pads are listened to.
 */
@Singleton
class PhysicalMotionSource
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionHub,
        private val satellite: SatelliteConnectionManager,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        /**
         * One pad's live sensor listening state: the per-device [SensorManager]
         * (from [InputDevice.getSensorManager]), the registered listener, and
         * the rate limiter. Held so [PadListener.release] can cleanly
         * unregister the listener when the pad stops streaming.
         */
        private inner class PadListener(
            val deviceId: Int,
            val slotId: String,
            private val sensorManager: SensorManager,
            gyro: Sensor,
            private val accel: Sensor?,
        ) {
            private val rateLimiter = MotionRateLimiter()

            // Latest accelerometer triple, pre-scaled to wire int16. Written on
            // the accel callback, read on the gyro callback — both on the same
            // (sensor) thread, so no lock is needed.
            private var accelX: Short = 0
            private var accelY: Short = 0
            private var accelZ: Short = 0

            private val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        when (event.sensor.type) {
                            Sensor.TYPE_ACCELEROMETER -> onAccel(event.values)
                            Sensor.TYPE_GYROSCOPE -> onGyro(event.values)
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) = Unit
                }

            init {
                sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
                accel?.let {
                    sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME)
                }
            }

            /** Unregister the sensor listener. Idempotent. */
            fun release() {
                sensorManager.unregisterListener(listener)
                rateLimiter.clearAll()
            }

            private fun onAccel(values: FloatArray) {
                if (values.size < 3) return
                // No remap — a controller's IMU is already in the body frame,
                // which matches the wire's +X-right/+Y-up/+Z-toward-player.
                accelX = MotionScaling.accelMssToWire(values[0])
                accelY = MotionScaling.accelMssToWire(values[1])
                accelZ = MotionScaling.accelMssToWire(values[2])
            }

            private fun onGyro(values: FloatArray) {
                if (values.size < 3) return
                val conn = reachable[slotId] ?: return
                val sample =
                    convertControllerSample(
                        gyroX = values[0],
                        gyroY = values[1],
                        gyroZ = values[2],
                        accelX = accelX,
                        accelY = accelY,
                        accelZ = accelZ,
                    )
                rateLimiter.publish(deviceId, sample) { s, deltaUs ->
                    conn.sendMotion(
                        slotId,
                        s.gyroX,
                        s.gyroY,
                        s.gyroZ,
                        s.accelX,
                        s.accelY,
                        s.accelZ,
                        deltaUs,
                    )
                }
            }
        }

        private var bindingsJob: Job? = null

        /** Snapshot of `slotId -> connection` for pads currently reachable. */
        @Volatile private var reachable: Map<String, SatelliteConnection> = emptyMap()

        /**
         * Live sensor listeners, keyed by `slotId` (the InputDevice id string).
         * Mutated from the flow collector ([onReachableChanged], on [scope]) and
         * from the lifecycle [onStop] (main thread); every access is guarded by
         * [listenersLock] so a final in-flight reachability update racing
         * [onStop] can never leak a registered sensor listener.
         */
        private val listeners = HashMap<String, PadListener>()
        private val listenersLock = Any()

        override fun onStart(owner: LifecycleOwner) {
            if (bindingsJob != null) return
            bindingsJob =
                reachableSlots()
                    .onEach(::onReachableChanged)
                    .launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            bindingsJob?.cancel()
            bindingsJob = null
            // Release every sensor listener when the app backgrounds — a gyro
            // listener left registered is a real battery drain.
            synchronized(listenersLock) {
                listeners.values.forEach { it.release() }
                listeners.clear()
            }
            reachable = emptyMap()
        }

        /**
         * Stream of `slotId -> SatelliteConnection` for every physical pad
         * bound to a CONNECTED satellite whose slot is registered — exactly the
         * gate [SatelliteConnection.sendMotion] needs (it drops a report for an
         * unregistered slot). Identical to [PhysicalBatterySource]'s reachability.
         */
        private fun reachableSlots(): Flow<Map<String, SatelliteConnection>> =
            combine(
                registry.devices,
                hub.bindings,
                hub.connections,
            ) { devices, bindings, summaries ->
                resolveReachable(devices.keys, bindings, summaries)
            }

        private fun resolveReachable(
            deviceIds: Set<Int>,
            bindings: Map<String, String>,
            summaries: List<ConnectionSummary>,
        ): Map<String, SatelliteConnection> {
            val byId = summaries.associateBy { it.id }
            return deviceIds
                .associateBy { it.toString() }
                .mapNotNull { (slotId, _) ->
                    reachableConnection(slotId, bindings, byId)?.let { slotId to it }
                }.toMap()
        }

        /**
         * The [SatelliteConnection] the pad at [slotId] can stream motion to,
         * or null when it isn't reachable: not bound, bound to Bluetooth (no
         * motion channel), the satellite isn't CONNECTED, or the controller
         * isn't registered yet.
         */
        private fun reachableConnection(
            slotId: String,
            bindings: Map<String, String>,
            summariesById: Map<String, ConnectionSummary>,
        ): SatelliteConnection? {
            val cid = bindings[slotId] ?: return null
            val summary = summariesById[cid] ?: return null
            val onLiveSatellite =
                summary.kind == ConnectionKind.SATELLITE &&
                    summary.live == ConnectionLive.CONNECTED
            if (!onLiveSatellite) return null
            val conn = satellite.get(cid) ?: return null
            return conn.takeIf { it.slots.value[slotId]?.registered == true }
        }

        /**
         * React to a change in the reachable-slot set: register sensor
         * listeners for pads that just became reachable, release them for pads
         * that dropped off. The [reachable] map is published first so an
         * in-flight gyro callback resolving its connection sees the new state.
         */
        private fun onReachableChanged(next: Map<String, SatelliteConnection>) {
            reachable = next
            synchronized(listenersLock) {
                // Release listeners for pads no longer reachable.
                val gone = listeners.keys - next.keys
                for (slotId in gone) {
                    listeners.remove(slotId)?.release()
                    Log.d(TAG, "pad $slotId no longer reachable, motion listener released")
                }
                // Register listeners for newly-reachable pads.
                for (slotId in next.keys - listeners.keys) {
                    startListening(slotId)?.let { listeners[slotId] = it }
                }
            }
        }

        /**
         * Build a [PadListener] for [slotId] — resolve the pad's per-device
         * [SensorManager] and its gyro/accel sensors. Returns null (best-effort)
         * when motion can't be sourced: API < 31, the device is gone, or the
         * pad simply has no gyroscope.
         */
        private fun startListening(slotId: String): PadListener? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            val deviceId = slotId.toIntOrNull() ?: return null
            val device = InputDevice.getDevice(deviceId) ?: return null
            val sensorManager = device.sensorManager
            val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return null
            val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            Log.i(
                TAG,
                "pad $slotId motion listener started (gyro=${gyro.name}, accel=${accel?.name})",
            )
            return PadListener(deviceId, slotId, sensorManager, gyro, accel)
        }

        companion object {
            private const val TAG = "PhysicalMotionSource"

            /**
             * Convert one fused controller IMU frame to a wire
             * [MotionRateLimiter.MotionSample]: gyro in rad/s and accel already
             * pre-scaled to wire int16 (the accel callback scales eagerly).
             *
             * **Identity axis remap.** Unlike [PhoneMotionSource], which calls
             * [MotionScaling.remapLandscape] to rotate the phone's sensor frame
             * into the landscape screen frame, a game controller's IMU is
             * reported in the controller body frame — and Android documents
             * that frame for game controllers as right-handed `+X` right /
             * `+Y` up / `+Z` toward the player, which is exactly the wire
             * convention. So the gyro axes are scaled straight through with no
             * rotation and the receiver does no rotation either.
             *
             * Pure (no Android types) so the physical-pad conversion path can
             * be pinned by a JVM unit test — the [PadListener] sensor callbacks
             * themselves need a device and so cannot be unit-tested directly.
             */
            fun convertControllerSample(
                gyroX: Float,
                gyroY: Float,
                gyroZ: Float,
                accelX: Short,
                accelY: Short,
                accelZ: Short,
            ): MotionRateLimiter.MotionSample =
                MotionRateLimiter.MotionSample(
                    gyroX = MotionScaling.gyroRadToWire(gyroX),
                    gyroY = MotionScaling.gyroRadToWire(gyroY),
                    gyroZ = MotionScaling.gyroRadToWire(gyroZ),
                    accelX = accelX,
                    accelY = accelY,
                    accelZ = accelZ,
                )
        }
    }
