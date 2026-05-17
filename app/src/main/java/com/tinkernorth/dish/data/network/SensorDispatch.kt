// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.

package com.tinkernorth.dish.data.network

import android.os.Handler
import android.os.HandlerThread

/**
 * Supplies the [Handler] that `SensorManager` delivers IMU callbacks on.
 *
 * `SensorManager.registerListener(listener, sensor, delay)` — the 3-arg
 * overload — derives its callback looper from the **main** looper. For the
 * motion path that would put the whole scale → rate-limit → encrypt →
 * UDP-send pipeline on the UI thread, at the sensor's native rate, competing
 * with the touch overlay's rendering and input. Passing an explicit [Handler]
 * bound to a dedicated [HandlerThread] (the 4-arg `registerListener` overload)
 * moves every callback — and the pipeline it drives — onto a background
 * thread. It also makes registration safe from a `Looper`-less caller, since
 * the callback looper no longer depends on the registering thread.
 *
 * Split behind an interface purely as a unit-test seam: [HandlerThread] and
 * [Handler] are Android framework types that cannot be constructed in a plain
 * JVM unit test.
 */
interface SensorDispatch {
    /** Start the backing thread if it is not running and return its [Handler]. */
    fun acquire(): Handler

    /** Quit the backing thread. Idempotent; safe to call before [acquire]. */
    fun release()
}

/**
 * Production [SensorDispatch] backed by a dedicated [HandlerThread]. The thread
 * is created lazily on [acquire] and torn down on [release], so it exists only
 * while sensors are actually streaming — a sensor callback thread left running
 * is a measurable battery cost.
 */
class HandlerThreadSensorDispatch(
    private val threadName: String,
) : SensorDispatch {
    private var thread: HandlerThread? = null

    override fun acquire(): Handler {
        thread?.let { return Handler(it.looper) }
        val started = HandlerThread(threadName).apply { start() }
        thread = started
        return Handler(started.looper)
    }

    override fun release() {
        thread?.quitSafely()
        thread = null
    }
}
