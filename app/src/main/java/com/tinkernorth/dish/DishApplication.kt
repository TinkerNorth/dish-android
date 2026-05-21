// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.composer.StreamingService
import com.tinkernorth.dish.composer.StreamingServiceController
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.PhysicalSlotBindingObserver
import com.tinkernorth.dish.hotpath.input.RumbleBridge
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.PhysicalMotionSource
import com.tinkernorth.dish.source.sensor.VirtualBatterySource
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothBondMonitor
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import com.tinkernorth.dish.source.system.ConnectionForegroundObserver
import com.tinkernorth.dish.source.system.NetworkStateObserver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

@HiltAndroidApp
class DishApplication : Application() {
    @Inject lateinit var connectionForegroundObserver: ConnectionForegroundObserver

    @Inject lateinit var physicalSlotBindingObserver: PhysicalSlotBindingObserver

    @Inject lateinit var physicalBatterySource: PhysicalBatterySource

    @Inject lateinit var virtualBatterySource: VirtualBatterySource

    @Inject lateinit var physicalMotionSource: PhysicalMotionSource

    @Inject lateinit var physicalGamepadRegistry: PhysicalGamepadRegistry

    @Inject lateinit var wakeStateController: WakeStateController

    @Inject lateinit var btRegistry: BluetoothGamepadRegistry

    @Inject lateinit var bluetoothBondMonitor: BluetoothBondMonitor

    @Inject lateinit var bluetoothAdapterStateObserver: BluetoothAdapterStateObserver

    @Inject lateinit var bluetoothPermissionStateObserver: BluetoothPermissionStateObserver

    @Inject lateinit var networkStateObserver: NetworkStateObserver

    @Inject lateinit var streamingServiceController: StreamingServiceController

    /**
     * Process-scoped CoroutineScope, exposed for [com.tinkernorth.dish.composer.StreamingService]
     * whose framework-owned lifecycle can't see the Hilt singletons that
     * everything else uses. Same instance Hilt hands to every @Singleton.
     */
    @Inject lateinit var processScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // The satellite native library is loaded eagerly from a handful of
        // companion init blocks (SatelliteNative, RumbleBridge, BluetoothGamepadBridge).
        // If the APK ships without the matching ABI (rare but real on
        // sideloaded builds or downgraded devices) the first reference throws
        // UnsatisfiedLinkError and the whole process dies before drawing.
        // Catch it here and flip a flag the launcher activity reads: instead
        // of crashing, the user lands on NativeUnavailableActivity with a
        // themed message + recovery instructions.
        try {
            installNativeBackedObservers()
        } catch (t: UnsatisfiedLinkError) {
            nativeLoadFailed = true
            android.util.Log.e(
                "DishApplication",
                "Satellite native library failed to load — falling back to NativeUnavailableActivity",
                t,
            )
        }
    }

    /**
     * All the lifecycle wiring that touches native code, factored so the
     * UnsatisfiedLinkError catch in [onCreate] is one block and the success
     * path stays readable. If this throws, no observers are registered — the
     * fallback activity is the only thing the user will see this session.
     */
    private fun installNativeBackedObservers() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(connectionForegroundObserver)
        // Physical-gamepad presence and the resulting native-pipeline bindings
        // both live at process scope so they survive the MainActivity →
        // GamepadOverlayActivity handoff (without this, the overlay would
        // arrive with every per-device binding already cleared).
        physicalGamepadRegistry.install()
        lifecycle.addObserver(physicalSlotBindingObserver)
        // Physical-pad battery reporting (Task 1.2) is also process-scoped so
        // it keeps polling across the dashboard → overlay hand-off, exactly
        // like the slot bindings above. It reports a wireless pad's own
        // battery, or the phone's battery for a USB-wired / batteryless pad.
        lifecycle.addObserver(physicalBatterySource)
        // The virtual controller's battery — the phone's own — needs the same
        // process-scoped feed. VIRTUAL_SLOT_ID in BatteryStatusStore used to be
        // written only by the gamepad overlay, so the dashboard indicator froze
        // the moment you left the overlay. VirtualBatterySource polls it for the
        // dashboard on every screen; the overlay still owns the wire send.
        lifecycle.addObserver(virtualBatterySource)
        // Physical-pad IMU forwarding (Task 1.1, step 2) — process-scoped for
        // the same hand-off reason. Registers per-pad gyro/accel listeners via
        // InputDevice.getSensorManager() (API 31+) for every physical pad
        // routed to a satellite, and forwards MSG_MOTION. Listeners are scoped
        // to the bound-pad lifecycle so none leaks.
        lifecycle.addObserver(physicalMotionSource)
        // Same handoff hazard: the partial wake lock and the "keep the screen
        // on" decision used to live on MainActivity and got torn down the
        // moment the gamepad overlay covered it. Hoisting to process scope
        // means both activities just observe shouldKeepScreenOn and toggle
        // the per-window flag.
        lifecycle.addObserver(wakeStateController)
        // The native input thread calls into BluetoothGamepadBridge for
        // physical-gamepad reports routed to a BT slot; install the registry
        // here (process-scoped) so it's ready before any input arrives.
        BluetoothGamepadBridge.install(btRegistry)
        // Catch KEY_MISSING + unexpected bond loss on remembered hosts and
        // surface a Toast — without it the HID profile just hangs on Registered
        // forever with no signal to the user that the peer dropped its key.
        lifecycle.addObserver(bluetoothBondMonitor)
        // System-state observers: BT adapter on/off, BT runtime permission,
        // and Wi-Fi vs cellular vs none. Each exposes a StateFlow that
        // ConnectionsActivity renders as a persistent in-section banner with
        // an actionable CTA (Turn on / Grant / Open Wi-Fi settings).
        lifecycle.addObserver(bluetoothAdapterStateObserver)
        lifecycle.addObserver(bluetoothPermissionStateObserver)
        lifecycle.addObserver(networkStateObserver)
        // Foreground service: starts when a slot is actively streaming, stops
        // when none are. Without this the OS can Doze the UDP socket the
        // moment the user navigates away from a Dish activity. The service
        // posts a single ongoing notification with a Stop action that cancels
        // every live session through the managers.
        lifecycle.addObserver(streamingServiceController)
        // Rumble flows back from the satellite (game → virtual pad → wire).
        // RumbleBridge owns the phone's VibratorManager / Vibrator and is the
        // single dispatch target for satellite_jni.cpp::receiveAck. Routed
        // unconditionally to the device — no physical-controller fallback,
        // intentionally — see RumbleBridge.kt for the design rationale.
        RumbleBridge.install(this)
    }

    companion object {
        /**
         * Set to true by [onCreate] if the satellite native library failed
         * to load. MainActivity reads this on creation and routes the user
         * to NativeUnavailableActivity instead of crashing.
         */
        @Volatile var nativeLoadFailed: Boolean = false
            private set
    }
}
