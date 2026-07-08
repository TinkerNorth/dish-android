// SPDX-License-Identifier: LGPL-3.0-or-later

package com.tinkernorth.dish.bench

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.tinkernorth.dish.BuildConfig
import com.tinkernorth.dish.core.jni.SatelliteNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug-only driver for the native hot-path latency benchmark (stage-1 USB-direct +
 * stage-2 heartbeat RTT). Registered only on debuggable builds; never on release.
 *
 * Drive it over adb (no UI):
 *   adb shell am broadcast -a com.tinkernorth.dish.HOTPATH_BENCH -p com.tinkernorth.dish --ez on true
 *   ...play with a USB-direct controller for ~30 s...
 *   adb shell am broadcast -a com.tinkernorth.dish.HOTPATH_BENCH -p com.tinkernorth.dish --ez on false
 *
 * While enabled it logs a JSON snapshot every [LOG_INTERVAL_MS] under tag [TAG];
 * disabling logs a final reset snapshot. Read with:
 *   adb logcat -s DishHotpathBench
 */
object HotPathBenchController {
    private const val TAG = "DishHotpathBench"
    private const val ACTION = "com.tinkernorth.dish.HOTPATH_BENCH"
    private const val LOG_INTERVAL_MS = 3000L

    private var logJob: Job? = null

    fun install(
        app: Application,
        scope: CoroutineScope,
    ) {
        if (!BuildConfig.HOTPATH_BENCH) return

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.action != ACTION) return
                    val on = intent.getBooleanExtra("on", true)
                    if (on) enable(scope) else disable()
                }
            }
        ContextCompat.registerReceiver(
            app,
            receiver,
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "ready: am broadcast -a $ACTION -p ${app.packageName} --ez on true")
    }

    private fun enable(scope: CoroutineScope) {
        if (logJob?.isActive == true) return
        SatelliteNative.setHotPathBench(true)
        Log.i(TAG, "enabled; stream a USB-direct controller now")
        logJob =
            scope.launch {
                while (isActive) {
                    delay(LOG_INTERVAL_MS)
                    Log.i(TAG, SatelliteNative.hotPathBenchJson(false))
                }
            }
    }

    private fun disable() {
        logJob?.cancel()
        logJob = null
        Log.i(TAG, "final " + SatelliteNative.hotPathBenchJson(true))
        SatelliteNative.setHotPathBench(false)
        Log.i(TAG, "disabled")
    }
}
