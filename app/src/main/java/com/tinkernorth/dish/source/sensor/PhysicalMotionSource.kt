// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.source.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.view.InputDevice
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.tinkernorth.dish.composer.ConnectionCoordinator
import com.tinkernorth.dish.composer.MotionCapability
import com.tinkernorth.dish.composer.MotionCapabilityComposer
import com.tinkernorth.dish.composer.PhysicalReachability
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.source.connection.SatelliteConnection
import com.tinkernorth.dish.source.connection.SatelliteConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhysicalMotionSource
    @Inject
    constructor(
        private val registry: PhysicalGamepadRegistry,
        private val hub: ConnectionCoordinator,
        private val satellite: SatelliteConnectionManager,
        private val motionCapability: MotionCapabilityComposer,
        private val scope: CoroutineScope,
    ) : DefaultLifecycleObserver {
        private inner class PadListener(
            val deviceId: Int,
            val slotId: String,
            private val sensorManager: SensorManager,
            gyro: Sensor,
            private val accel: Sensor?,
            handler: Handler,
        ) {
            private val rateLimiter = MotionRateLimiter()

            private var accelX: Short = 0
            private var accelY: Short = 0
            private var accelZ: Short = 0

            // Gate first gyro emission until accel reports; otherwise ships (0,0,0) read as zero-gravity.
            private var accelSeen: Boolean = false

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
                // 4-arg registerListener keeps callbacks off the main thread.
                sensorManager.registerListener(
                    listener,
                    gyro,
                    SensorManager.SENSOR_DELAY_GAME,
                    handler,
                )
                accel?.let {
                    sensorManager.registerListener(
                        listener,
                        it,
                        SensorManager.SENSOR_DELAY_GAME,
                        handler,
                    )
                }
            }

            fun release() {
                sensorManager.unregisterListener(listener)
                rateLimiter.clearAll()
            }

            private fun onAccel(values: FloatArray) {
                if (values.size < 3) return
                // No remap — a controller's IMU is already in the wire frame.
                accelX = MotionScaling.accelMssToWire(values[0])
                accelY = MotionScaling.accelMssToWire(values[1])
                accelZ = MotionScaling.accelMssToWire(values[2])
                accelSeen = true
            }

            private fun onGyro(values: FloatArray) {
                if (values.size < 3) return
                if (!shouldEmitGyro(accel != null, accelSeen)) return
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

        @Volatile private var reachable: Map<String, SatelliteConnection> = emptyMap()

        // Guarded by listenersLock — a final reachability update racing onStop could leak a listener.
        private val listeners = HashMap<String, PadListener>()
        private val listenersLock = Any()

        private val sensorDispatch: SensorDispatch = HandlerThreadSensorDispatch("PhysicalPadSensor")

        @Volatile private var sensorHandler: Handler? = null

        override fun onStart(owner: LifecycleOwner) {
            if (bindingsJob != null) return
            sensorHandler = sensorDispatch.acquire()
            bindingsJob =
                PhysicalReachability
                    .reachableSlots(
                        registry.devices,
                        hub.bindings,
                        hub.connections,
                        satellite.connections,
                    ).combine(motionCapability.state, ::filterByCapability)
                    .onEach(::onReachableChanged)
                    .launchIn(scope)
        }

        override fun onStop(owner: LifecycleOwner) {
            bindingsJob?.cancel()
            bindingsJob = null
            // Release listeners — a leaked gyro listener drains both pad and phone.
            synchronized(listenersLock) {
                listeners.values.forEach { it.release() }
                listeners.clear()
            }
            reachable = emptyMap()
            sensorDispatch.release()
            sensorHandler = null
        }

        private fun onReachableChanged(next: Map<String, SatelliteConnection>) {
            reachable = next
            synchronized(listenersLock) {
                val gone = listeners.keys - next.keys
                for (slotId in gone) {
                    listeners.remove(slotId)?.release()
                    Log.d(TAG, "pad $slotId no longer reachable, motion listener released")
                }
                // null handler ⇒ onStop already tore down; next onStart re-derives the set.
                sensorHandler?.let { handler ->
                    for (slotId in next.keys - listeners.keys) {
                        startListening(slotId, handler)?.let { listeners[slotId] = it }
                    }
                }
            }
        }

        private fun startListening(
            slotId: String,
            handler: Handler,
        ): PadListener? {
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
            return PadListener(deviceId, slotId, sensorManager, gyro, accel, handler)
        }

        companion object {
            private const val TAG = "PhysicalMotionSource"

            internal fun shouldEmitGyro(
                hasAccelSensor: Boolean,
                accelSeen: Boolean,
            ): Boolean = !hasAccelSensor || accelSeen

            internal fun filterByCapability(
                reachable: Map<String, com.tinkernorth.dish.source.connection.SatelliteConnection>,
                caps: Map<String, MotionCapability>,
            ): Map<String, com.tinkernorth.dish.source.connection.SatelliteConnection> =
                reachable.filterKeys { slotId ->
                    val cap = caps[slotId] ?: return@filterKeys false
                    cap.hasGyro && cap.userEnabled
                }

            // Identity axis remap — controller IMU body frame already matches wire convention.
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
