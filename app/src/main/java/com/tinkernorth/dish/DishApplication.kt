// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.lifecycle.ProcessLifecycleOwner
import com.tinkernorth.dish.bench.HotPathBenchController
import com.tinkernorth.dish.composer.CrashReportingController
import com.tinkernorth.dish.composer.DiagnosticsLogRecorder
import com.tinkernorth.dish.composer.StreamingServiceController
import com.tinkernorth.dish.composer.WakeStateController
import com.tinkernorth.dish.core.jni.PhysicalInputNative
import com.tinkernorth.dish.hotpath.input.BluetoothGamepadBridge
import com.tinkernorth.dish.hotpath.input.PhysicalGamepadRegistry
import com.tinkernorth.dish.hotpath.input.PhysicalSlotBindingObserver
import com.tinkernorth.dish.hotpath.input.RumbleBridge
import com.tinkernorth.dish.hotpath.input.RumbleRouter
import com.tinkernorth.dish.source.bluetooth.BluetoothGamepadRegistry
import com.tinkernorth.dish.source.inputrate.InputRateStore
import com.tinkernorth.dish.source.sensor.PhysicalBatterySource
import com.tinkernorth.dish.source.sensor.PhysicalMotionSource
import com.tinkernorth.dish.source.sensor.VirtualBatterySource
import com.tinkernorth.dish.source.store.LatencyProfilingStore
import com.tinkernorth.dish.source.store.ThemePreferenceStore
import com.tinkernorth.dish.source.system.BluetoothAdapterStateObserver
import com.tinkernorth.dish.source.system.BluetoothBondMonitor
import com.tinkernorth.dish.source.system.BluetoothPermissionStateObserver
import com.tinkernorth.dish.source.system.ConnectionForegroundObserver
import com.tinkernorth.dish.source.system.NetworkStateObserver
import com.tinkernorth.dish.source.usb.PollRateSampler
import com.tinkernorth.dish.source.usb.UsbGamepadManager
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

    @Inject lateinit var crashReportingController: CrashReportingController

    @Inject lateinit var themePreferenceStore: ThemePreferenceStore

    @Inject lateinit var usbGamepadManager: UsbGamepadManager

    @Inject lateinit var pollRateSampler: PollRateSampler

    @Inject lateinit var inputRateStore: InputRateStore

    @Inject lateinit var rumbleRouter: RumbleRouter

    @Inject lateinit var physicalInputNative: PhysicalInputNative

    @Inject lateinit var latencyProfilingStore: LatencyProfilingStore

    @Inject lateinit var diagnosticsLogRecorder: DiagnosticsLogRecorder

    // Exposed so StreamingService (framework-owned lifecycle) can reuse the Hilt singleton scope.
    @Inject lateinit var processScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        installStrictModeIfDebuggable()
        // AppCompatDelegate default night mode is process-wide; apply here before any Activity
        // inflates so the first frame already matches the user's pick.
        themePreferenceStore.applyPersistedMode()
        // Must install before native-load try so the opt-in applies even when load fails.
        ProcessLifecycleOwner.get().lifecycle.addObserver(crashReportingController)
        // Missing ABI on sideloaded builds throws UnsatisfiedLinkError on first
        // native ref; route to NativeUnavailableActivity instead of crashing.
        try {
            installNativeBackedObservers()
            HotPathBenchController.install(this, processScope)
            diagnosticsLogRecorder.install()
            // Re-arm latency profiling only if the user previously left it on (they accepted the
            // warning then). Default is false, so a fresh install keeps the hot path measurement-free.
            physicalInputNative.setHotPathBench(latencyProfilingStore.state.value)
        } catch (t: UnsatisfiedLinkError) {
            nativeLoadFailed = true
            android.util.Log.e(
                "DishApplication",
                "Satellite native library failed to load, falling back to NativeUnavailableActivity",
                t,
            )
        }
    }

    private fun installStrictModeIfDebuggable() {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    private fun installNativeBackedObservers() {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(connectionForegroundObserver)
        // Process-scoped so bindings survive the MainActivity → GamepadOverlayActivity handoff.
        physicalGamepadRegistry.install()
        usbGamepadManager.install()
        pollRateSampler.install()
        inputRateStore.install()
        lifecycle.addObserver(physicalSlotBindingObserver)
        lifecycle.addObserver(physicalBatterySource)
        lifecycle.addObserver(virtualBatterySource)
        lifecycle.addObserver(physicalMotionSource)
        lifecycle.addObserver(wakeStateController)
        BluetoothGamepadBridge.install(btRegistry)
        lifecycle.addObserver(bluetoothBondMonitor)
        lifecycle.addObserver(bluetoothAdapterStateObserver)
        lifecycle.addObserver(bluetoothPermissionStateObserver)
        lifecycle.addObserver(networkStateObserver)
        lifecycle.addObserver(streamingServiceController)
        RumbleBridge.install(rumbleRouter)
    }

    companion object {
        @Volatile var nativeLoadFailed: Boolean = false
            private set
    }
}
